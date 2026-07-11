package net.mpy.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime operation semantics for the VM: the {@code BINARY_OP}/{@code UNARY_OP}
 * operators, truthiness, subscripting, and iteration, over the {@link PyObj}
 * value model. Numeric behaviour follows Python 3 (true vs floor division,
 * sign-of-divisor modulo, small-int/big-int promotion, int/float coercion).
 *
 * Operator indices match the bytecode order in py/runtime0.h (see Operators).
 */
public final class Ops {
    /** Resolve a built-in subclass instance (class MyInt(int)) to its backing
     *  native value so the structural primitives operate on it transparently. */
    public static Object unwrapNative(Object o) {
        if (o instanceof net.mpy.vm.PyInstance) {
            Object nv = ((net.mpy.vm.PyInstance) o).nativeValue;
            if (nv != null) return nv;
        }
        return o;
    }

    public static double complexRe(Object o) {
        if (o instanceof PyObj.Complex) return ((PyObj.Complex) o).re;
        if (o instanceof Double) return (Double) o;
        if (o instanceof Long) return (Long) o;
        if (o instanceof java.math.BigInteger) return ((java.math.BigInteger) o).doubleValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        throw PyException.typeError("not a number");
    }
    public static double complexIm(Object o) {
        return (o instanceof PyObj.Complex) ? ((PyObj.Complex) o).im : 0.0;
    }
    /** A complex result collapses to a real float only when Python would NOT:
     *  Python keeps complex even if imag==0, so we always return Complex here. */
    static Object mkComplex(double re, double im) {
        return new PyObj.Complex(re, im);
    }

    /** Process-wide cap on elements/chars a single operation may allocate
     *  (sequence repeat/concat, range materialisation). Set via Limits. */
    public static volatile long MAX_SEQ_LEN = 1L << 22;

    private static void checkAlloc(long units, long bytesEach) {
        if (units > MAX_SEQ_LEN || units < 0) {
            throw new PyException("MemoryError",
                    "memory allocation failed, allocating " + (units * bytesEach) + " bytes");
        }
    }

    private Ops() {}

    // binary op indices (bytecode subset)
    public static final int LESS = 0, MORE = 1, EQUAL = 2, LESS_EQUAL = 3, MORE_EQUAL = 4,
            NOT_EQUAL = 5, IN = 6, IS = 7, EXCEPTION_MATCH = 8,
            INPLACE_OR = 9, INPLACE_POWER = 21,
            OR = 22, XOR = 23, AND = 24, LSHIFT = 25, RSHIFT = 26, ADD = 27, SUBTRACT = 28,
            MULTIPLY = 29, MAT_MULTIPLY = 30, FLOOR_DIVIDE = 31, TRUE_DIVIDE = 32, MODULO = 33, POWER = 34;

    // unary op indices
    public static final int POSITIVE = 0, NEGATIVE = 1, INVERT = 2, NOT = 3;

    // ---- truthiness ---------------------------------------------------------

    public static boolean isTrue(Object o) {
        if (o == null || o == PyObj.NONE) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Long) return (Long) o != 0L;
        if (o instanceof BigInteger) return ((BigInteger) o).signum() != 0;
        if (o instanceof Double) return (Double) o != 0.0;
        if (o instanceof String) return !((String) o).isEmpty();
        if (o instanceof PyObj.Bytes) return ((PyObj.Bytes) o).data.length != 0;
        if (o instanceof PyObj.Tuple) return ((PyObj.Tuple) o).items.length != 0;
        if (o instanceof PyObj.PyList) return !((PyObj.PyList) o).items.isEmpty();
        if (o instanceof PyObj.PyDict) return !((PyObj.PyDict) o).map.isEmpty();
        if (o instanceof PyObj.PySet) return !((PyObj.PySet) o).items.isEmpty();
        return true;
    }

    // ---- unary --------------------------------------------------------------

    public static Object unaryOp(int op, Object v) {
        switch (op) {
            case NOT: return !isTrue(v);
            case POSITIVE:
                if (isInt(v)) return PyObj.normInt(toBig(v));
                if (v instanceof Double) return v;
                throw PyException.typeError("bad operand type for unary +");
            case NEGATIVE:
                if (isInt(v)) return PyObj.normInt(toBig(v).negate());
                if (v instanceof Double) return -(Double) v;
                throw PyException.typeError("bad operand type for unary -");
            case INVERT:
                if (isInt(v)) return PyObj.normInt(toBig(v).not());
                throw PyException.typeError("bad operand type for unary ~");
            default:
                throw PyException.notImpl("unary op " + op);
        }
    }

    // ---- binary -------------------------------------------------------------

    public static Object binaryOp(int op, Object a, Object b) {
        // in-place operators behave like their normal counterparts for our model
        if (op >= INPLACE_OR && op <= INPLACE_POWER) op += (OR - INPLACE_OR);

        switch (op) {
            case EXCEPTION_MATCH: return PyExc.matches(a, b);
            case IS:  return identical(a, b);
            case IN:  return contains(b, a);
            case EQUAL:     return pyEquals(a, b);
            case NOT_EQUAL: return !pyEquals(a, b);
            case LESS: case MORE: case LESS_EQUAL: case MORE_EQUAL:
                return compareOp(op, a, b);
            default:
                return arith(op, a, b);
        }
    }

    private static Object arith(int op, Object a, Object b) {
        // string / sequence operators
        if (op == ADD) {
            if (a instanceof String && b instanceof String) {
                checkAlloc((long) ((String) a).length() + ((String) b).length(), 1);
                return (String) a + (String) b;
            }
            if (a instanceof PyObj.Bytes && b instanceof PyObj.Bytes) {
                return concatBytes((PyObj.Bytes) a, (PyObj.Bytes) b);
            }
            if (a instanceof PyObj.Tuple && b instanceof PyObj.Tuple) return concatTuple((PyObj.Tuple) a, (PyObj.Tuple) b);
            if (a instanceof PyObj.PyList && b instanceof PyObj.PyList) {
                List<Object> r = new ArrayList<>(((PyObj.PyList) a).items);
                r.addAll(((PyObj.PyList) b).items);
                return new PyObj.PyList(r);
            }
        }
        if (op == MULTIPLY) {
            if (a instanceof String && isIntStrict(b)) return repeat((String) a, (int) toLong(b));
            if (isIntStrict(a) && b instanceof String) return repeat((String) b, (int) toLong(a));
            if (a instanceof PyObj.PyList && isIntStrict(b)) return repeatList((PyObj.PyList) a, (int) toLong(b));
            if (a instanceof PyObj.Tuple && isIntStrict(b)) return repeatTuple((PyObj.Tuple) a, (int) toLong(b));
            if (a instanceof PyObj.Bytes && isIntStrict(b)) return repeatBytes((PyObj.Bytes) a, (int) toLong(b));
            if (isIntStrict(a) && b instanceof PyObj.Bytes) return repeatBytes((PyObj.Bytes) b, (int) toLong(a));
        }

        if (op == MODULO) {
            // printf-style string formatting: "fmt" % arg  or  "fmt" % (args...)
            if (a instanceof String) {
                Object[] args;
                if (b instanceof PyObj.Tuple) args = ((PyObj.Tuple) b).items;
                else args = new Object[]{ b };
                return strFormat((String) a, args);
            }
        }

        // complex arithmetic: promote either operand to Complex
        if (a instanceof PyObj.Complex || b instanceof PyObj.Complex) {
            double ar = complexRe(a), ai = complexIm(a), br = complexRe(b), bi = complexIm(b);
            switch (op) {
                case ADD: return mkComplex(ar + br, ai + bi);
                case SUBTRACT: return mkComplex(ar - br, ai - bi);
                case MULTIPLY: return mkComplex(ar * br - ai * bi, ar * bi + ai * br);
                case TRUE_DIVIDE: {
                    double d = br * br + bi * bi;
                    if (d == 0.0) throw PyException.zeroDiv();
                    return mkComplex((ar * br + ai * bi) / d, (ai * br - ar * bi) / d);
                }
                default: throw PyException.typeError("unsupported operand for complex");
            }
        }

        if (!isNumeric(a) || !isNumeric(b)) {
            throw PyException.typeError("unsupported types for " + opDunder(op)
                    + ": '" + Methods.typeName(a) + "', '" + Methods.typeName(b) + "'");
        }

        // float path if either side is a float
        if (a instanceof Double || b instanceof Double) {
            double x = toDouble(a), y = toDouble(b);
            switch (op) {
                case ADD: return x + y;
                case SUBTRACT: return x - y;
                case MULTIPLY: return x * y;
                case TRUE_DIVIDE: if (y == 0.0) throw PyException.zeroDiv(); return x / y;
                case FLOOR_DIVIDE: if (y == 0.0) throw PyException.zeroDiv(); return Math.floor(x / y);
                case MODULO: if (y == 0.0) throw PyException.zeroDiv(); return pyFmod(x, y);
                case POWER: return Math.pow(x, y);
                default: throw PyException.typeError("bad float op " + op);
            }
        }

        // integer path
        BigInteger x = toBig(a), y = toBig(b);
        switch (op) {
            case ADD: return PyObj.normInt(x.add(y));
            case SUBTRACT: return PyObj.normInt(x.subtract(y));
            case MULTIPLY: return PyObj.normInt(x.multiply(y));
            case TRUE_DIVIDE:
                if (y.signum() == 0) throw PyException.zeroDiv();
                return x.doubleValue() / y.doubleValue();
            case FLOOR_DIVIDE:
                if (y.signum() == 0) throw PyException.zeroDiv();
                return PyObj.normInt(floorDiv(x, y));
            case MODULO:
                if (y.signum() == 0) throw PyException.zeroDiv();
                return PyObj.normInt(floorMod(x, y));
            case POWER:
                if (y.signum() < 0) return Math.pow(x.doubleValue(), y.doubleValue());
                return PyObj.normInt(x.pow(y.intValueExact()));
            case OR:  return PyObj.normInt(x.or(y));
            case XOR: return PyObj.normInt(x.xor(y));
            case AND: return PyObj.normInt(x.and(y));
            case LSHIFT: return PyObj.normInt(x.shiftLeft(y.intValueExact()));
            case RSHIFT: return PyObj.normInt(x.shiftRight(y.intValueExact()));
            default: throw PyException.typeError("bad int op " + op);
        }
    }

    // Python floor division / modulo (result of % has the sign of the divisor)
    private static BigInteger floorDiv(BigInteger x, BigInteger y) {
        BigInteger[] qr = x.divideAndRemainder(y);
        BigInteger q = qr[0];
        if (qr[1].signum() != 0 && qr[1].signum() != y.signum()) q = q.subtract(BigInteger.ONE);
        return q;
    }
    private static BigInteger floorMod(BigInteger x, BigInteger y) {
        BigInteger r = x.mod(y.abs());
        return (y.signum() < 0 && r.signum() != 0) ? r.subtract(y.abs()) : r;
    }
    private static double pyFmod(double x, double y) {
        double r = x % y;
        if (r != 0.0 && ((r < 0) != (y < 0))) r += y;
        return r;
    }

    // ---- comparisons / equality --------------------------------------------

    private static Object compareOp(int op, Object a, Object b) {
        int c = compare(a, b);
        switch (op) {
            case LESS: return c < 0;
            case MORE: return c > 0;
            case LESS_EQUAL: return c <= 0;
            case MORE_EQUAL: return c >= 0;
            default: throw PyException.typeError("bad comparison " + op);
        }
    }

    /** Three-way comparison for ordered types. */
    public static int compare(Object a, Object b) {
        if (isNumeric(a) && isNumeric(b)) {
            if (a instanceof Double || b instanceof Double) return Double.compare(toDouble(a), toDouble(b));
            return toBig(a).compareTo(toBig(b));
        }
        if (a instanceof String && b instanceof String) return ((String) a).compareTo((String) b);
        if (a instanceof PyObj.Tuple && b instanceof PyObj.Tuple)
            return compareSeq(asList(((PyObj.Tuple) a).items), asList(((PyObj.Tuple) b).items));
        if (a instanceof PyObj.PyList && b instanceof PyObj.PyList)
            return compareSeq(((PyObj.PyList) a).items, ((PyObj.PyList) b).items);
        throw PyException.typeError("'<' not supported between instances of these types");
    }

    private static int compareSeq(List<Object> a, List<Object> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!pyEquals(a.get(i), b.get(i))) return compare(a.get(i), b.get(i));
        }
        return Integer.compare(a.size(), b.size());
    }

    public static boolean pyEquals(Object a, Object b) {
        if (isNumeric(a) && isNumeric(b)) {
            if (a instanceof Double || b instanceof Double) return toDouble(a) == toDouble(b);
            return toBig(a).equals(toBig(b));
        }
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof PyObj.Tuple && b instanceof PyObj.Tuple)
            return seqEquals(asList(((PyObj.Tuple) a).items), asList(((PyObj.Tuple) b).items));
        if (a instanceof PyObj.PyList && b instanceof PyObj.PyList)
            return seqEquals(((PyObj.PyList) a).items, ((PyObj.PyList) b).items);
        if (a instanceof PyObj.PyDict && b instanceof PyObj.PyDict) {
            var ma = ((PyObj.PyDict) a).map; var mb = ((PyObj.PyDict) b).map;
            if (ma.size() != mb.size()) return false;
            for (var e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!pyEquals(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof PyObj.PySet && b instanceof PyObj.PySet) {
            var sa = ((PyObj.PySet) a).items; var sb = ((PyObj.PySet) b).items;
            return sa.size() == sb.size() && sa.containsAll(sb);
        }
        if (a instanceof PyObj.Bytes && b instanceof PyObj.Bytes)
            return java.util.Arrays.equals(((PyObj.Bytes) a).data, ((PyObj.Bytes) b).data);
        if (a instanceof PyObj.Complex && b instanceof PyObj.Complex)
            return ((PyObj.Complex) a).re == ((PyObj.Complex) b).re && ((PyObj.Complex) a).im == ((PyObj.Complex) b).im;
        if (a == null || a == PyObj.NONE) return b == null || b == PyObj.NONE;
        return a.equals(b);
    }

    private static boolean seqEquals(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!pyEquals(a.get(i), b.get(i))) return false;
        return true;
    }

    private static Object identical(Object a, Object b) {
        if ((a == null || a == PyObj.NONE) && (b == null || b == PyObj.NONE)) return true;
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        if (a instanceof Long && b instanceof Long) return a.equals(b); // small ints are value-identical
        return a == b;
    }

    // ---- membership ---------------------------------------------------------

    private static Object contains(Object container, Object item) {
        if (container instanceof String && item instanceof String) return ((String) container).contains((String) item);
        if (container instanceof PyObj.Tuple) {
            for (Object x : ((PyObj.Tuple) container).items) if (pyEquals(x, item)) return true;
            return false;
        }
        if (container instanceof PyObj.PyList) {
            for (Object x : ((PyObj.PyList) container).items) if (pyEquals(x, item)) return true;
            return false;
        }
        if (container instanceof PyObj.PySet) {
            for (Object x : ((PyObj.PySet) container).items) if (pyEquals(x, item)) return true;
            return false;
        }
        if (container instanceof PyObj.PyDict) {
            for (Object k : ((PyObj.PyDict) container).map.keySet()) if (pyEquals(k, item)) return true;
            return false;
        }
        throw PyException.typeError("argument of type is not iterable");
    }

    // ---- subscripting -------------------------------------------------------

    public static Object subscrGet(Object obj, Object index) {
        obj = unwrapNative(obj);
        if (obj instanceof PyObj.PyDict) {
            for (java.util.Map.Entry<Object, Object> e : ((PyObj.PyDict) obj).map.entrySet())
                if (pyEquals(e.getKey(), index)) return e.getValue();
            throw PyException.keyError(index);
        }
        if (index instanceof PyObj.Slice) return sliceGet(obj, (PyObj.Slice) index);
        int i = (int) toLong(index);
        if (obj instanceof PyObj.PyList) {
            List<Object> xs = ((PyObj.PyList) obj).items;
            return xs.get(checkIndex(i, xs.size(), "list index out of range"));
        }
        if (obj instanceof PyObj.Tuple) {
            Object[] xs = ((PyObj.Tuple) obj).items;
            return xs[checkIndex(i, xs.length, "tuple index out of range")];
        }
        if (obj instanceof String) {
            String s = (String) obj;
            return String.valueOf(s.charAt(checkIndex(i, s.length(), "string index out of range")));
        }
        if (obj instanceof PyObj.Range) {
            PyObj.Range r = (PyObj.Range) obj;
            return r.get(checkIndex(i, (int) r.length(), "range index out of range"));
        }
        if (obj instanceof PyObj.Bytes) {
            byte[] data = ((PyObj.Bytes) obj).data;
            return (long) (data[checkIndex(i, data.length, "bytes index out of range")] & 0xFF);
        }
        throw PyException.typeError("object is not subscriptable");
    }

    public static void subscrStore(Object obj, Object index, Object value) {
        if (obj instanceof PyObj.PyDict) {
            var map = ((PyObj.PyDict) obj).map;
            Object existing = null;
            for (Object k : map.keySet()) if (pyEquals(k, index)) { existing = k; break; }
            map.put(existing != null ? existing : index, value);
            return;
        }
        if (obj instanceof PyObj.PyList) {
            List<Object> xs = ((PyObj.PyList) obj).items;
            xs.set(checkIndex((int) toLong(index), xs.size(), "list index out of range"), value);
            return;
        }
        throw PyException.typeError("object does not support item assignment");
    }

    private static Object sliceGet(Object obj, PyObj.Slice s) {
        if (obj instanceof String) {
            String str = (String) obj;
            int[] r = sliceIndices(s, str.length());
            StringBuilder sb = new StringBuilder();
            for (int i = r[0]; (r[2] > 0) ? i < r[1] : i > r[1]; i += r[2]) sb.append(str.charAt(i));
            return sb.toString();
        }
        if (obj instanceof PyObj.Bytes) {
            byte[] data = ((PyObj.Bytes) obj).data;
            int[] r = sliceIndices(s, data.length);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            for (int i = r[0]; (r[2] > 0) ? i < r[1] : i > r[1]; i += r[2]) bo.write(data[i]);
            return new PyObj.Bytes(bo.toByteArray());
        }
        List<Object> src;
        boolean tuple = obj instanceof PyObj.Tuple;
        if (tuple) src = asList(((PyObj.Tuple) obj).items);
        else if (obj instanceof PyObj.PyList) src = ((PyObj.PyList) obj).items;
        else throw PyException.typeError("object is not sliceable");
        int[] r = sliceIndices(s, src.size());
        List<Object> out = new ArrayList<>();
        for (int i = r[0]; (r[2] > 0) ? i < r[1] : i > r[1]; i += r[2]) out.add(src.get(i));
        return tuple ? new PyObj.Tuple(out.toArray()) : new PyObj.PyList(out);
    }

    private static int[] sliceIndices(PyObj.Slice s, int len) {
        int step = (s.step == null || s.step == PyObj.NONE) ? 1 : (int) toLong(s.step);
        if (step == 0) throw PyException.valueError("slice step cannot be zero");
        int start, stop;
        if (s.start == null || s.start == PyObj.NONE) start = step > 0 ? 0 : len - 1;
        else start = clampIndex((int) toLong(s.start), len, step);
        if (s.stop == null || s.stop == PyObj.NONE) stop = step > 0 ? len : -1;
        else stop = clampIndex((int) toLong(s.stop), len, step);
        return new int[]{start, stop, step};
    }
    private static int clampIndex(int i, int len, int step) {
        if (i < 0) i += len;
        if (step > 0) return Math.max(0, Math.min(i, len));
        return Math.max(-1, Math.min(i, len - 1));
    }

    private static int checkIndex(int i, int len, String msg) {
        if (i < 0) i += len;
        if (i < 0 || i >= len) throw PyException.indexError(msg);
        return i;
    }

    // ---- iteration ----------------------------------------------------------

    public static PyObj.Iter getIter(Object o) {
        if (o instanceof PyObj.Iter) return (PyObj.Iter) o;
        // built-in subclass instance (class MyList(list)): iterate its native value
        if (o instanceof net.mpy.vm.PyInstance) {
            Object nv = ((net.mpy.vm.PyInstance) o).nativeValue;
            if (nv != null && nv != o) return getIter(nv);
        }
        if (o instanceof PyObj.Range) {
            PyObj.Range r = (PyObj.Range) o;
            long n = r.length();
            checkAlloc(n, 8);
            List<Object> vals = new ArrayList<>((int) n);
            for (long i = 0; i < n; i++) vals.add(r.get(i));
            return new PyObj.Iter(vals);
        }
        if (o instanceof PyObj.Tuple) return new PyObj.Iter(asList(((PyObj.Tuple) o).items));
        if (o instanceof PyObj.PyList) return new PyObj.Iter(new ArrayList<>(((PyObj.PyList) o).items));
        if (o instanceof PyObj.PySet) return new PyObj.Iter(new ArrayList<>(((PyObj.PySet) o).items));
        if (o instanceof PyObj.PyDict) return new PyObj.Iter(new ArrayList<>(((PyObj.PyDict) o).map.keySet()));
        if (o instanceof String) {
            String s = (String) o;
            List<Object> chars = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) chars.add(String.valueOf(s.charAt(i)));
            return new PyObj.Iter(chars);
        }
        if (o instanceof PyObj.Bytes) {
            byte[] data = ((PyObj.Bytes) o).data;
            List<Object> vals = new ArrayList<>(data.length);
            for (byte b : data) vals.add((long) (b & 0xFF));
            return new PyObj.Iter(vals);
        }
        throw PyException.typeError("object is not iterable");
    }

    /** Extract exactly n elements (natural order seq[0..n-1]) for UNPACK_SEQUENCE. */
    public static Object[] unpackSeq(Object seq, int n) {
        List<Object> items;
        if (seq instanceof PyObj.Tuple) items = asList(((PyObj.Tuple) seq).items);
        else if (seq instanceof PyObj.PyList) items = ((PyObj.PyList) seq).items;
        else {
            items = new ArrayList<>();
            PyObj.Iter it = getIter(seq);
            Object v;
            while ((v = it.next()) != PyObj.STOP_ITERATION) items.add(v);
        }
        if (items.size() < n) throw PyException.valueError("not enough values to unpack");
        if (items.size() > n) throw PyException.valueError("too many values to unpack");
        return items.toArray();
    }

    // ---- numeric helpers ----------------------------------------------------

    public static boolean isNumeric(Object o) {
        return o instanceof Long || o instanceof BigInteger || o instanceof Double || o instanceof Boolean;
    }
    private static boolean isInt(Object o) {
        return o instanceof Long || o instanceof BigInteger || o instanceof Boolean;
    }
    /** integer but not bool (for sequence-repeat / shift-count contexts). */
    private static boolean isIntStrict(Object o) { return o instanceof Long || o instanceof BigInteger; }

    private static long toLong(Object o) {
        o = unwrapNative(o);
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof Long) return (Long) o;
        if (o instanceof BigInteger) return ((BigInteger) o).longValueExact();
        if (o instanceof Double) return (long) (double) (Double) o;
        throw PyException.typeError("expected an integer");
    }
    private static BigInteger toBig(Object o) {
        o = unwrapNative(o);
        if (o instanceof Boolean) return (Boolean) o ? BigInteger.ONE : BigInteger.ZERO;
        if (o instanceof Long) return BigInteger.valueOf((Long) o);
        if (o instanceof BigInteger) return (BigInteger) o;
        throw PyException.typeError("expected an integer");
    }
    private static double toDouble(Object o) {
        if (o instanceof Boolean) return (Boolean) o ? 1.0 : 0.0;
        if (o instanceof Long) return (double) (Long) o;
        if (o instanceof BigInteger) return ((BigInteger) o).doubleValue();
        if (o instanceof Double) return (Double) o;
        throw PyException.typeError("expected a number");
    }

    private static List<Object> asList(Object[] a) {
        List<Object> l = new ArrayList<>(a.length);
        for (Object o : a) l.add(o);
        return l;
    }
    /** Map a binary-op number to its Python dunder name, for MicroPython-matching
     *  TypeError text ("unsupported types for __mod__: ..."). */
    private static String opDunder(int op) {
        switch (op) {
            case OR: return "__or__";
            case XOR: return "__xor__";
            case AND: return "__and__";
            case LSHIFT: return "__lshift__";
            case RSHIFT: return "__rshift__";
            case ADD: return "__add__";
            case SUBTRACT: return "__sub__";
            case MULTIPLY: return "__mul__";
            case MAT_MULTIPLY: return "__matmul__";
            case FLOOR_DIVIDE: return "__floordiv__";
            case TRUE_DIVIDE: return "__truediv__";
            case MODULO: return "__mod__";
            case POWER: return "__pow__";
            default: return "__op" + op + "__";
        }
    }

    private static String repeat(String s, int n) {
        checkAlloc((long) s.length() * Math.max(n, 0), 1);
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** printf-style `"fmt" % args` — Python/MicroPython semantics. Supports flags
     *  ( - 0 + space # ), width, .precision, and conversions d i u s r x X o c f F
     *  e E g G %. Args are consumed left to right; %% consumes none. */
    private static String strFormat(String fmt, Object[] args) {
        StringBuilder out = new StringBuilder();
        int ai = 0;
        int n = fmt.length();
        for (int i = 0; i < n; i++) {
            char ch = fmt.charAt(i);
            if (ch != '%') { out.append(ch); continue; }
            i++;
            if (i >= n) throw PyException.valueError("incomplete format");
            if (fmt.charAt(i) == '%') { out.append('%'); continue; }
            // flags
            boolean left = false, zero = false, plus = false, space = false, alt = false;
            for (; i < n; i++) {
                char f = fmt.charAt(i);
                if (f == '-') left = true;
                else if (f == '0') zero = true;
                else if (f == '+') plus = true;
                else if (f == ' ') space = true;
                else if (f == '#') alt = true;
                else break;
            }
            // width (may be '*')
            int width = -1;
            if (i < n && fmt.charAt(i) == '*') {
                width = (int) toLong(args[ai++]); i++;
                if (width < 0) { left = true; width = -width; }
            } else {
                int w = 0; boolean has = false;
                while (i < n && Character.isDigit(fmt.charAt(i))) { w = w * 10 + (fmt.charAt(i) - '0'); i++; has = true; }
                if (has) width = w;
            }
            // precision
            int prec = -1;
            if (i < n && fmt.charAt(i) == '.') {
                i++;
                if (i < n && fmt.charAt(i) == '*') { prec = (int) toLong(args[ai++]); i++; }
                else { int p = 0; while (i < n && Character.isDigit(fmt.charAt(i))) { p = p * 10 + (fmt.charAt(i) - '0'); i++; } prec = p; }
            }
            if (i >= n) throw PyException.valueError("incomplete format");
            char conv = fmt.charAt(i);
            Object arg = (conv == '%') ? null : (ai < args.length ? args[ai++] : errNotEnough());
            String body;
            boolean numeric = false;
            String sign = "";
            switch (conv) {
                case 'd': case 'i': case 'u': {
                    java.math.BigInteger v = toBig(arg);
                    numeric = true;
                    if (v.signum() < 0) { sign = "-"; body = v.negate().toString(); }
                    else { sign = plus ? "+" : (space ? " " : ""); body = v.toString(); }
                    if (prec >= 0) { zero = false; while (body.length() < prec) body = "0" + body; }
                    break;
                }
                case 'x': case 'X': case 'o': {
                    java.math.BigInteger v = toBig(arg);
                    numeric = true;
                    int base = (conv == 'o') ? 8 : 16;
                    if (v.signum() < 0) { sign = "-"; body = v.negate().toString(base); }
                    else { sign = plus ? "+" : (space ? " " : ""); body = v.toString(base); }
                    if (conv == 'X') body = body.toUpperCase();
                    if (prec >= 0) { zero = false; while (body.length() < prec) body = "0" + body; }
                    if (alt && v.signum() != 0) body = (conv == 'o' ? "0o" : (conv == 'X' ? "0X" : "0x")) + body;
                    break;
                }
                case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': {
                    double d = toDouble(arg);
                    numeric = true;
                    int p = prec < 0 ? 6 : prec;
                    String spec = "%" + (plus ? "+" : (space ? " " : "")) + (alt ? "#" : "") + "." + p + conv;
                    body = String.format(spec, d);
                    // Java's %g does not strip trailing zeros the way C/Python does;
                    // strip them (and a dangling '.') unless the alternate (#) flag is set.
                    if ((conv == 'g' || conv == 'G') && !alt && body.indexOf('.') >= 0) {
                        int ePos = body.indexOf(conv == 'g' ? 'e' : 'E');
                        String mant = ePos < 0 ? body : body.substring(0, ePos);
                        String exp = ePos < 0 ? "" : body.substring(ePos);
                        if (mant.indexOf('.') >= 0) {
                            int end = mant.length();
                            while (end > 0 && mant.charAt(end - 1) == '0') end--;
                            if (end > 0 && mant.charAt(end - 1) == '.') end--;
                            mant = mant.substring(0, end);
                        }
                        body = mant + exp;
                    }
                    if (body.startsWith("-")) { sign = "-"; body = body.substring(1); }
                    else if (body.startsWith("+")) { sign = "+"; body = body.substring(1); }
                    else if (body.startsWith(" ")) { sign = " "; body = body.substring(1); }
                    break;
                }
                case 'c': {
                    if (arg instanceof String) body = (String) arg;
                    else body = String.valueOf((char) (int) toLong(arg));
                    break;
                }
                case 's': {
                    body = net.mpy.vm.Builtins.str(arg);
                    if (prec >= 0 && body.length() > prec) body = body.substring(0, prec);
                    break;
                }
                case 'r': {
                    body = PyObj.repr(arg);
                    if (prec >= 0 && body.length() > prec) body = body.substring(0, prec);
                    break;
                }
                default:
                    throw PyException.valueError("unsupported format character '" + conv + "'");
            }
            // apply width with sign/zero/left rules
            int pad = width < 0 ? 0 : width - sign.length() - body.length();
            if (pad < 0) pad = 0;
            if (left) { out.append(sign).append(body); for (int k = 0; k < pad; k++) out.append(' '); }
            else if (zero && numeric) { out.append(sign); for (int k = 0; k < pad; k++) out.append('0'); out.append(body); }
            else { for (int k = 0; k < pad; k++) out.append(' '); out.append(sign).append(body); }
        }
        return out.toString();
    }

    private static Object errNotEnough() {
        throw PyException.typeError("not enough arguments for format string");
    }
    private static PyObj.PyList repeatList(PyObj.PyList a, int n) {
        checkAlloc((long) a.items.size() * Math.max(n, 0), 8);
        List<Object> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.addAll(a.items);
        return new PyObj.PyList(r);
    }
    private static PyObj.Tuple repeatTuple(PyObj.Tuple a, int n) {
        checkAlloc((long) a.items.length * Math.max(n, 0), 8);
        List<Object> r = new ArrayList<>();
        for (int i = 0; i < n; i++) for (Object x : a.items) r.add(x);
        return new PyObj.Tuple(r.toArray());
    }
    private static PyObj.Bytes repeatBytes(PyObj.Bytes a, int n) {
        checkAlloc((long) a.data.length * Math.max(n, 0), 1);
        if (n < 0) n = 0;
        byte[] out = new byte[a.data.length * n];
        for (int i = 0; i < n; i++) System.arraycopy(a.data, 0, out, i * a.data.length, a.data.length);
        return new PyObj.Bytes(out);
    }

    static PyObj.Bytes concatBytes(PyObj.Bytes a, PyObj.Bytes b) {
        checkAlloc((long) a.data.length + b.data.length, 1);
        byte[] out = new byte[a.data.length + b.data.length];
        System.arraycopy(a.data, 0, out, 0, a.data.length);
        System.arraycopy(b.data, 0, out, a.data.length, b.data.length);
        return new PyObj.Bytes(out);
    }

    private static PyObj.Tuple concatTuple(PyObj.Tuple a, PyObj.Tuple b) {
        checkAlloc((long) a.items.length + b.items.length, 8);
        Object[] r = new Object[a.items.length + b.items.length];
        System.arraycopy(a.items, 0, r, 0, a.items.length);
        System.arraycopy(b.items, 0, r, a.items.length, b.items.length);
        return new PyObj.Tuple(r);
    }
}
