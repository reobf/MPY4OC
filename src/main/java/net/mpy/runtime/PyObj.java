package net.mpy.runtime;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * D1 - Python value representation in Java.
 *
 * Python values are plain java.lang.Object, using these conventions so the
 * whole system (loader, constant table, and the future VM) shares one model:
 *
 *   Python        Java
 *   ------        ----
 *   None          PyObj.NONE          (singleton)
 *   True/False    java.lang.Boolean
 *   int (small)   java.lang.Long      (fits a MicroPython small int)
 *   int (big)     java.math.BigInteger
 *   float         java.lang.Double
 *   complex       PyObj.Complex
 *   str           java.lang.String
 *   bytes         PyObj.Bytes         (wrapper so repr differs from str)
 *   tuple         PyObj.Tuple
 *   Ellipsis      PyObj.ELLIPSIS      (singleton)
 *
 * Later phases add PyObj subtypes for list/dict/function/etc. For Phase 0/1 we
 * only need the value kinds that can appear in a .mpy constant object table
 * plus the inline small-int carried by LOAD_CONST_SMALL_INT.
 */
public final class PyObj {

    private PyObj() {}

    // ---- singletons ---------------------------------------------------------

    /** The Python {@code None} singleton. */
    public static final Object NONE = new Object() {
        @Override public String toString() { return "None"; }
    };

    /** The Python {@code Ellipsis} singleton. */
    public static final Object ELLIPSIS = new Object() {
        @Override public String toString() { return "Ellipsis"; }
    };

    /** The NotImplemented singleton: a binary dunder returns this to signal
     *  "I can't handle this operand; try the reflected operation." */
    public static final Object NOT_IMPLEMENTED = new Object() {
        @Override public String toString() { return "NotImplemented"; }
    };

    // ---- wrappers -----------------------------------------------------------

    /** Immutable Python {@code bytes}. Wrapped so it can be told apart from str. */
    public static final class Bytes {
        public final byte[] data;
        public Bytes(byte[] data) { this.data = data; }

        @Override public boolean equals(Object o) {
            return o instanceof Bytes && Arrays.equals(data, ((Bytes) o).data);
        }
        @Override public int hashCode() { return Arrays.hashCode(data); }
        @Override public String toString() { return repr(this); }
    }

    /** Immutable Python {@code tuple}. */
    public static final class Tuple {
        public final Object[] items;
        /** namedtuple field names (parallel to items), or null for a plain tuple. */
        public final String[] fieldNames;
        /** namedtuple type name (for repr), or null for a plain tuple. */
        public final String typeName;
        public Tuple(Object[] items) { this.items = items; this.fieldNames = null; this.typeName = null; }
        public Tuple(Object[] items, String[] fieldNames, String typeName) {
            this.items = items; this.fieldNames = fieldNames; this.typeName = typeName;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Tuple && Arrays.equals(items, ((Tuple) o).items);
        }
        @Override public int hashCode() { return Arrays.hashCode(items); }
        @Override public String toString() { return repr(this); }
    }

    /** Python {@code complex}. */
    public static final class Complex {
        public final double re, im;
        public Complex(double re, double im) { this.re = re; this.im = im; }
        @Override public String toString() { return repr(this); }
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Normalise a Java integer to the small-int (Long) / big-int (BigInteger)
     * split used throughout. Values that fit in a signed 63-bit range stay Long.
     */
    public static Object normInt(BigInteger v) {
        if (v.bitLength() < 63) return v.longValue();
        return v;
    }

    /** A Python-ish repr, good enough for disassembly / debugging output. */
    public static String repr(Object o) {
        if (o == null || o == NONE) return "None";
        if (o == ELLIPSIS) return "Ellipsis";
        if (o instanceof Boolean) return ((Boolean) o) ? "True" : "False";
        if (o instanceof String) return reprStr((String) o);
        if (o instanceof Bytes) return reprBytes(((Bytes) o).data);
        if (o instanceof Double) return reprFloat((Double) o);
        if (o instanceof Complex) {
            Complex c = (Complex) o;
            // Python: pure-imaginary (real == +0.0) prints as "{im}j"; otherwise
            // "({re}{sign}{im}j)". Complex components drop a trailing ".0".
            if (c.re == 0.0) {
                return complexComponent(c.im) + "j";
            }
            String sign = (c.im < 0) ? "-" : "+";
            return "(" + complexComponent(c.re) + sign + complexComponent(Math.abs(c.im)) + "j)";
        }
        if (o instanceof Tuple) {
            Tuple t = (Tuple) o;
            Object[] it = t.items;
            if (t.fieldNames != null) {                 // namedtuple: Name(x=1, y=2)
                StringBuilder sb = new StringBuilder(t.typeName).append("(");
                for (int i = 0; i < it.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(t.fieldNames[i]).append("=").append(repr(it[i]));
                }
                return sb.append(")").toString();
            }
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < it.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(it[i]));
            }
            if (it.length == 1) sb.append(",");
            return sb.append(")").toString();
        }
        if (o instanceof PyList) {
            java.util.List<Object> xs = ((PyList) o).items;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < xs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(xs.get(i)));
            }
            return sb.append("]").toString();
        }
        if (o instanceof PyDict) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<Object, Object> e : ((PyDict) o).map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (o instanceof PySet) {
            java.util.Set<Object> s = ((PySet) o).items;
            if (s.isEmpty()) return "set()";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object x : s) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(x));
            }
            return sb.append("}").toString();
        }
        if (o instanceof Slice) {
            Slice s = (Slice) o;
            return "slice(" + repr(s.start) + ", " + repr(s.stop) + ", " + repr(s.step) + ")";
        }
        if (o instanceof Range) {
            Range r = (Range) o;
            return r.step == 1 ? "range(" + r.start + ", " + r.stop + ")"
                               : "range(" + r.start + ", " + r.stop + ", " + r.step + ")";
        }
        if (o instanceof PyExc.Instance) return PyExc.repr((PyExc.Instance) o);
        if (o instanceof PyExc.Type) return "<class '" + ((PyExc.Type) o).name + "'>";
        return o.toString(); // Long, BigInteger, etc.
    }

    /** Float formatting for complex components: whole numbers print without ".0". */
    private static String complexComponent(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static String reprFloat(double d) {
        return floatRepr(d);
    }

    private static String reprStr(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == q || cp == '\\') sb.append('\\').appendCodePoint(cp);
            else if (cp == '\n') sb.append("\\n");
            else if (cp == '\r') sb.append("\\r");
            else if (cp == '\t') sb.append("\\t");
            else if (cp >= 0x20 && cp < 0x7f) sb.appendCodePoint(cp);
            else if (cp < 0x100) sb.append(String.format("\\x%02x", cp));
            else if (cp < 0x10000) sb.append(String.format("\\u%04x", cp));
            else sb.append(String.format("\\U%08x", cp));
        }
        return sb.append(q).toString();
    }

    private static String reprBytes(byte[] b) {
        StringBuilder sb = new StringBuilder("b'");
        for (byte x : b) {
            int c = x & 0xff;
            if (c == '\\' || c == '\'') sb.append('\\').append((char) c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c >= 0x20 && c < 0x7f) sb.append((char) c);
            else sb.append(String.format("\\x%02x", c));
        }
        return sb.append('\'').toString();
    }

    // ---- mutable containers (created during execution, not in .mpy tables) --

    /** Python {@code list}. */
    public static final class PyList {
        public final java.util.List<Object> items;
        public PyList() { this.items = new java.util.ArrayList<>(); }
        public PyList(java.util.List<Object> items) { this.items = items; }
        @Override public String toString() { return repr(this); }
    }

    /** Python {@code dict} (insertion-ordered, like CPython/MicroPython). */
    public static final class PyDict {
        public final java.util.Map<Object, Object> map;
        public PyDict() { this.map = new java.util.LinkedHashMap<>(); }
        /** Wrap an existing map as a live view (used by globals()/locals()). The
         *  supplied map's entries are shared, so writes propagate both ways. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public PyDict(java.util.Map backing) { this.map = backing; }
        @Override public String toString() { return repr(this); }
    }

    /** Python {@code set} (insertion-ordered for stable repr). */
    public static final class PySet {
        public final java.util.LinkedHashSet<Object> items;
        public PySet() { this.items = new java.util.LinkedHashSet<>(); }
        @Override public String toString() { return repr(this); }
    }

    /** Python {@code slice}. */
    public static final class Slice {
        public final Object start, stop, step;
        public Slice(Object start, Object stop, Object step) {
            this.start = start; this.stop = stop; this.step = step;
        }
        @Override public String toString() { return repr(this); }
    }

    /** Python {@code range} as a value (lazy: start/stop/step only). */
    public static final class Range {
        public final long start, stop, step;
        public Range(long start, long stop, long step) {
            if (step == 0) throw PyException.valueError("zero step");
            this.start = start; this.stop = stop; this.step = step;
        }
        public long length() {
            long n = (step > 0) ? (stop - start + step - 1) / step : (start - stop - step - 1) / (-step);
            return Math.max(0, n);
        }
        public long get(long i) { return start + i * step; }
        @Override public String toString() { return repr(this); }
    }

    /** A closure cell: a mutable box shared between an outer function's local and
     *  the inner functions that capture it (LOAD/STORE_DEREF). */
    public static final class Cell {
        public Object value;
        public Cell(Object value) { this.value = value; }
        @Override public String toString() { return "<cell>"; }
    }

    /** CPython/MicroPython-style repr of a float: shortest round-trip digits,
     *  plain decimal for 1e-4..1e16, else scientific like {@code 1e+20} / {@code 2e-05}. */
    public static String floatRepr(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        String sign = "";
        if (v < 0 || (v == 0.0 && 1 / v < 0)) { sign = "-"; v = -v; }
        if (v == 0.0) return sign + "0.0";
        // Java's Double.toString gives the shortest round-trip digits; re-format them.
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(v));
        String digits = bd.unscaledValue().toString();
        int pointPos = digits.length() - bd.scale();   // value = 0.<digits> * 10^pointPos
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        digits = digits.substring(0, end);
        StringBuilder sb = new StringBuilder(sign);
        if (pointPos >= -3 && pointPos <= 16) {
            if (pointPos <= 0) {
                sb.append("0.");
                for (int i = 0; i < -pointPos; i++) sb.append('0');
                sb.append(digits);
            } else if (pointPos >= digits.length()) {
                sb.append(digits);
                for (int i = digits.length(); i < pointPos; i++) sb.append('0');
                sb.append(".0");
            } else {
                sb.append(digits, 0, pointPos).append('.').append(digits, pointPos, digits.length());
            }
        } else {
            int exp = pointPos - 1;
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
            sb.append('e').append(exp < 0 ? '-' : '+');
            int ae = Math.abs(exp);
            if (ae < 10) sb.append('0');
            sb.append(ae);
        }
        return sb.toString();
    }

    /** Sentinel returned by an iterator when exhausted (Python StopIteration). */
    public static final Object STOP_ITERATION = new Object() {
        @Override public String toString() { return "<stop-iteration>"; }
    };

    /** A simple forward iterator over a Java iterator of Python values. */
    /** A forward iterator over a fixed list of Python values (list + position, so
     *  it can be captured in a VM snapshot). */
    public static final class Iter {
        public final java.util.List<Object> items;
        public int pos;
        public Iter(java.util.List<Object> items) { this.items = items; }
        /** Next value, or {@link #STOP_ITERATION} when exhausted. */
        public Object next() { return pos < items.size() ? items.get(pos++) : STOP_ITERATION; }
        @Override public String toString() { return "<iterator>"; }
    }
}
