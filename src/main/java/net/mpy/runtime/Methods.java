package net.mpy.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Methods of the built-in types (Phase 5): a registry from (type, name) to an
 * implementation over the {@link PyObj} value model, used by LOAD_METHOD /
 * CALL_METHOD and by LOAD_ATTR (which hands out a {@link BoundMethod}).
 * Behaviour follows MicroPython's objlist/objdict/objstr/objset/objtuple.
 */
public final class Methods {

    private Methods() {}

    /** A method implementation: {@code self.name(args) -> result}. */
    public interface Impl {
        Object call(Object self, Object[] args);
    }

    /** A method bound to a receiver, created by LOAD_ATTR (e.g. {@code f = xs.append}).
     *  Stores (self, name) and re-resolves the implementation at call time, which
     *  keeps it trivially snapshotable. */
    public static final class BoundMethod {
        public final Object self;
        public final String name;
        public BoundMethod(Object self, String name) { this.self = self; this.name = name; }
        public Object call(Object[] args) {
            Impl impl = lookup(self, name);
            if (impl == null) throw PyException.typeError("object is not callable");
            return impl.call(self, args);
        }
        @Override public String toString() { return "<bound_method " + name + ">"; }
    }

    /** What LOAD_METHOD pushes (slot 0 of the two-slot convention): a method name to
     *  be resolved against the receiver at CALL_METHOD time. Name-based so a snapshot
     *  taken mid-call-setup (e.g. a yield inside the argument list) round-trips. */
    public static final class Ref {
        public final String name;
        public Ref(String name) { this.name = name; }
        @Override public String toString() { return "<method " + name + ">"; }
    }

    // registries per receiver kind
    private static final Map<String, Impl> LIST = new HashMap<>();
    private static final Map<String, Impl> DICT = new HashMap<>();
    private static final Map<String, Impl> STR = new HashMap<>();
    private static final Map<String, Impl> SET = new HashMap<>();
    private static final Map<String, Impl> TUPLE = new HashMap<>();
    private static final Map<String, Impl> BYTES = new HashMap<>();

    /** The implementation of {@code self.name}, or null if the type has no such method. */
    public static Impl lookup(Object self, String name) {
        if (self instanceof PyObj.PyList) return LIST.get(name);
        if (self instanceof PyObj.PyDict) return DICT.get(name);
        if (self instanceof String) return STR.get(name);
        if (self instanceof PyObj.PySet) return SET.get(name);
        if (self instanceof PyObj.Tuple) return TUPLE.get(name);
        if (self instanceof PyObj.Bytes) return BYTES.get(name);
        return null;
    }

    /** A short Python type name for error messages. */
    public static String typeName(Object o) {
        if (o instanceof PyObj.PyList) return "list";
        if (o instanceof PyObj.PyDict) return "dict";
        if (o instanceof String) return "str";
        if (o instanceof PyObj.PySet) return "set";
        if (o instanceof PyObj.Tuple) return "tuple";
        if (o instanceof PyObj.Bytes) return "bytes";
        if (o instanceof Long || o instanceof java.math.BigInteger) return "int";
        if (o instanceof Double) return "float";
        if (o instanceof Boolean) return "bool";
        if (o == null || o == PyObj.NONE) return "NoneType";
        return o.getClass().getSimpleName();
    }

    // ---- helpers --------------------------------------------------------------

    private static void arity(Object[] a, int min, int max, String name) {
        if (a.length < min || a.length > max) {
            throw PyException.typeError(name + "() takes " + (min == max ? String.valueOf(min) : min + ".." + max)
                    + " arguments but " + a.length + " were given");
        }
    }
    private static int asInt(Object o) {
        if (o instanceof Long) return (int) (long) (Long) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        throw PyException.typeError("an integer is required");
    }
    private static List<Object> listOfIterable(Object o) {
        List<Object> out = new ArrayList<>();
        PyObj.Iter it = Ops.getIter(o);
        Object v;
        while ((v = it.next()) != PyObj.STOP_ITERATION) out.add(v);
        return out;
    }
    private static int seqIndexOf(List<Object> xs, Object v, String name) {
        for (int i = 0; i < xs.size(); i++) if (Ops.pyEquals(xs.get(i), v)) return i;
        throw PyException.valueError(name);
    }

    // ---- list -----------------------------------------------------------------
    static {
        LIST.put("append", (s, a) -> { arity(a, 1, 1, "append");
            ((PyObj.PyList) s).items.add(a[0]); return PyObj.NONE; });
        LIST.put("clear", (s, a) -> { arity(a, 0, 0, "clear");
            ((PyObj.PyList) s).items.clear(); return PyObj.NONE; });
        LIST.put("copy", (s, a) -> { arity(a, 0, 0, "copy");
            return new PyObj.PyList(new ArrayList<>(((PyObj.PyList) s).items)); });
        LIST.put("count", (s, a) -> { arity(a, 1, 1, "count");
            long c = 0; for (Object x : ((PyObj.PyList) s).items) if (Ops.pyEquals(x, a[0])) c++; return c; });
        LIST.put("extend", (s, a) -> { arity(a, 1, 1, "extend");
            ((PyObj.PyList) s).items.addAll(listOfIterable(a[0])); return PyObj.NONE; });
        LIST.put("index", (s, a) -> { arity(a, 1, 1, "index");
            return (long) seqIndexOf(((PyObj.PyList) s).items, a[0], "object not in sequence"); });
        LIST.put("insert", (s, a) -> { arity(a, 2, 2, "insert");
            List<Object> xs = ((PyObj.PyList) s).items;
            int i = asInt(a[0]);
            if (i < 0) i += xs.size();
            i = Math.max(0, Math.min(i, xs.size()));
            xs.add(i, a[1]); return PyObj.NONE; });
        LIST.put("pop", (s, a) -> { arity(a, 0, 1, "pop");
            List<Object> xs = ((PyObj.PyList) s).items;
            if (xs.isEmpty()) throw PyException.indexError("pop from empty list");
            int i = a.length == 0 ? xs.size() - 1 : asInt(a[0]);
            if (i < 0) i += xs.size();
            if (i < 0 || i >= xs.size()) throw PyException.indexError("pop index out of range");
            return xs.remove(i); });
        LIST.put("remove", (s, a) -> { arity(a, 1, 1, "remove");
            List<Object> xs = ((PyObj.PyList) s).items;
            xs.remove(seqIndexOf(xs, a[0], "object not in sequence")); return PyObj.NONE; });
        LIST.put("reverse", (s, a) -> { arity(a, 0, 0, "reverse");
            java.util.Collections.reverse(((PyObj.PyList) s).items); return PyObj.NONE; });
        LIST.put("sort", (s, a) -> { arity(a, 0, 0, "sort");
            ((PyObj.PyList) s).items.sort(Ops::compare); return PyObj.NONE; });
    }

    // ---- dict -----------------------------------------------------------------
    private static Object dictFind(PyObj.PyDict d, Object key) {
        for (Object k : d.map.keySet()) if (Ops.pyEquals(k, key)) return k;
        return null;
    }
    static {
        DICT.put("get", (s, a) -> { arity(a, 1, 2, "get");
            PyObj.PyDict d = (PyObj.PyDict) s;
            Object k = dictFind(d, a[0]);
            return k != null ? d.map.get(k) : (a.length == 2 ? a[1] : PyObj.NONE); });
        DICT.put("pop", (s, a) -> { arity(a, 1, 2, "pop");
            PyObj.PyDict d = (PyObj.PyDict) s;
            Object k = dictFind(d, a[0]);
            if (k != null) return d.map.remove(k);
            if (a.length == 2) return a[1];
            throw PyException.keyError(a[0]); });
        DICT.put("setdefault", (s, a) -> { arity(a, 1, 2, "setdefault");
            PyObj.PyDict d = (PyObj.PyDict) s;
            Object k = dictFind(d, a[0]);
            if (k != null) return d.map.get(k);
            Object v = a.length == 2 ? a[1] : PyObj.NONE;
            d.map.put(a[0], v); return v; });
        DICT.put("keys", (s, a) -> { arity(a, 0, 0, "keys");
            return new PyObj.PyList(new ArrayList<>(((PyObj.PyDict) s).map.keySet())); });
        DICT.put("values", (s, a) -> { arity(a, 0, 0, "values");
            return new PyObj.PyList(new ArrayList<>(((PyObj.PyDict) s).map.values())); });
        DICT.put("items", (s, a) -> { arity(a, 0, 0, "items");
            List<Object> out = new ArrayList<>();
            for (var e : ((PyObj.PyDict) s).map.entrySet()) out.add(new PyObj.Tuple(new Object[]{e.getKey(), e.getValue()}));
            return new PyObj.PyList(out); });
        DICT.put("clear", (s, a) -> { arity(a, 0, 0, "clear");
            ((PyObj.PyDict) s).map.clear(); return PyObj.NONE; });
        DICT.put("copy", (s, a) -> { arity(a, 0, 0, "copy");
            PyObj.PyDict d = new PyObj.PyDict();
            d.map.putAll(((PyObj.PyDict) s).map); return d; });
        DICT.put("update", (s, a) -> { arity(a, 1, 1, "update");
            PyObj.PyDict d = (PyObj.PyDict) s;
            if (!(a[0] instanceof PyObj.PyDict)) throw PyException.typeError("update expects a dict");
            for (var e : ((PyObj.PyDict) a[0]).map.entrySet()) Ops.subscrStore(d, e.getKey(), e.getValue());
            return PyObj.NONE; });
    }

    // ---- str ------------------------------------------------------------------
    private static final String WS = " \t\n\r\u000b\f";
    static {
        STR.put("format", (s, a) -> Format.apply((String) s, a));
        STR.put("encode", (s, a) -> {
            arity(a, 0, 1, "encode");   // encoding arg accepted, ignored (utf-8)
            return new PyObj.Bytes(((String) s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        BYTES.put("decode", (s, a) -> {
            arity(a, 0, 1, "decode");
            return new String(((PyObj.Bytes) s).data, java.nio.charset.StandardCharsets.UTF_8);
        });
        BYTES.put("hex", (s, a) -> {
            arity(a, 0, 0, "hex");
            byte[] d = ((PyObj.Bytes) s).data;
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) { sb.append(Character.forDigit((b >> 4) & 0xf, 16)); sb.append(Character.forDigit(b & 0xf, 16)); }
            return sb.toString();
        });
        STR.put("upper", (s, a) -> { arity(a, 0, 0, "upper"); return ((String) s).toUpperCase(); });
        STR.put("lower", (s, a) -> { arity(a, 0, 0, "lower"); return ((String) s).toLowerCase(); });
        STR.put("strip", (s, a) -> { arity(a, 0, 1, "strip");
            String cs = a.length == 1 ? (String) a[0] : WS;
            return strip((String) s, cs, true, true); });
        STR.put("lstrip", (s, a) -> { arity(a, 0, 1, "lstrip");
            return strip((String) s, a.length == 1 ? (String) a[0] : WS, true, false); });
        STR.put("rstrip", (s, a) -> { arity(a, 0, 1, "rstrip");
            return strip((String) s, a.length == 1 ? (String) a[0] : WS, false, true); });
        STR.put("split", (s, a) -> { arity(a, 0, 2, "split");
            return split((String) s, a.length >= 1 && a[0] != PyObj.NONE ? (String) a[0] : null); });
        STR.put("join", (s, a) -> { arity(a, 1, 1, "join");
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object x : listOfIterable(a[0])) {
                if (!(x instanceof String)) throw PyException.typeError("join expects str items");
                if (!first) sb.append((String) s);
                first = false;
                sb.append((String) x);
            }
            return sb.toString(); });
        STR.put("replace", (s, a) -> { arity(a, 2, 2, "replace");
            return ((String) s).replace((String) a[0], (String) a[1]); });
        STR.put("find", (s, a) -> { arity(a, 1, 2, "find"); String str = (String) s;
            int from = a.length > 1 ? asInt(a[1]) : 0;
            if (from < 0) from = Math.max(0, str.length() + from);
            return (long) str.indexOf((String) a[0], from); });
        STR.put("rfind", (s, a) -> { arity(a, 1, 1, "rfind"); return (long) ((String) s).lastIndexOf((String) a[0]); });
        STR.put("index", (s, a) -> { arity(a, 1, 1, "index");
            int i = ((String) s).indexOf((String) a[0]);
            if (i < 0) throw PyException.valueError("substring not found");
            return (long) i; });
        STR.put("count", (s, a) -> { arity(a, 1, 1, "count");
            String str = (String) s, sub = (String) a[0];
            if (sub.isEmpty()) return (long) (str.length() + 1);
            long c = 0; int i = 0;
            while ((i = str.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
            return c; });
        STR.put("startswith", (s, a) -> { arity(a, 1, 1, "startswith"); return ((String) s).startsWith((String) a[0]); });
        STR.put("endswith", (s, a) -> { arity(a, 1, 1, "endswith"); return ((String) s).endsWith((String) a[0]); });
        STR.put("isdigit", (s, a) -> { arity(a, 0, 0, "isdigit");
            String str = (String) s;
            if (str.isEmpty()) return false;
            for (int i = 0; i < str.length(); i++) if (str.charAt(i) < '0' || str.charAt(i) > '9') return false;
            return true; });
        STR.put("isalpha", (s, a) -> { arity(a, 0, 0, "isalpha");
            String str = (String) s;
            if (str.isEmpty()) return false;
            for (int i = 0; i < str.length(); i++) if (!Character.isLetter(str.charAt(i))) return false;
            return true; });
    }

    private static String strip(String s, String chars, boolean left, boolean right) {
        int b = 0, e = s.length();
        if (left) while (b < e && chars.indexOf(s.charAt(b)) >= 0) b++;
        if (right) while (e > b && chars.indexOf(s.charAt(e - 1)) >= 0) e--;
        return s.substring(b, e);
    }

    private static PyObj.PyList split(String s, String sep) {
        List<Object> out = new ArrayList<>();
        if (sep == null) { // whitespace runs, no empty tokens
            int i = 0, n = s.length();
            while (i < n) {
                while (i < n && WS.indexOf(s.charAt(i)) >= 0) i++;
                int b = i;
                while (i < n && WS.indexOf(s.charAt(i)) < 0) i++;
                if (i > b) out.add(s.substring(b, i));
            }
        } else {
            if (sep.isEmpty()) throw PyException.valueError("empty separator");
            int i = 0, j;
            while ((j = s.indexOf(sep, i)) >= 0) { out.add(s.substring(i, j)); i = j + sep.length(); }
            out.add(s.substring(i));
        }
        return new PyObj.PyList(out);
    }

    // ---- set ------------------------------------------------------------------
    private static Object setFind(PyObj.PySet s, Object v) {
        for (Object x : s.items) if (Ops.pyEquals(x, v)) return x;
        return null;
    }
    static {
        SET.put("add", (s, a) -> { arity(a, 1, 1, "add");
            PyObj.PySet st = (PyObj.PySet) s;
            if (setFind(st, a[0]) == null) st.items.add(a[0]);
            return PyObj.NONE; });
        SET.put("remove", (s, a) -> { arity(a, 1, 1, "remove");
            PyObj.PySet st = (PyObj.PySet) s;
            Object k = setFind(st, a[0]);
            if (k == null) throw PyException.keyError(a[0]);
            st.items.remove(k); return PyObj.NONE; });
        SET.put("discard", (s, a) -> { arity(a, 1, 1, "discard");
            PyObj.PySet st = (PyObj.PySet) s;
            Object k = setFind(st, a[0]);
            if (k != null) st.items.remove(k);
            return PyObj.NONE; });
        SET.put("clear", (s, a) -> { arity(a, 0, 0, "clear");
            ((PyObj.PySet) s).items.clear(); return PyObj.NONE; });
        SET.put("copy", (s, a) -> { arity(a, 0, 0, "copy");
            PyObj.PySet c = new PyObj.PySet();
            c.items.addAll(((PyObj.PySet) s).items); return c; });
        SET.put("union", (s, a) -> { arity(a, 1, 1, "union");
            PyObj.PySet c = new PyObj.PySet();
            c.items.addAll(((PyObj.PySet) s).items);
            for (Object x : listOfIterable(a[0])) if (setFind(c, x) == null) c.items.add(x);
            return c; });
        SET.put("intersection", (s, a) -> { arity(a, 1, 1, "intersection");
            PyObj.PySet c = new PyObj.PySet();
            PyObj.PySet other = toSet(a[0]);
            for (Object x : ((PyObj.PySet) s).items) if (setFind(other, x) != null) c.items.add(x);
            return c; });
        SET.put("difference", (s, a) -> { arity(a, 1, 1, "difference");
            PyObj.PySet c = new PyObj.PySet();
            PyObj.PySet other = toSet(a[0]);
            for (Object x : ((PyObj.PySet) s).items) if (setFind(other, x) == null) c.items.add(x);
            return c; });
        SET.put("update", (s, a) -> { arity(a, 1, 1, "update");
            PyObj.PySet st = (PyObj.PySet) s;
            for (Object x : listOfIterable(a[0])) if (setFind(st, x) == null) st.items.add(x);
            return PyObj.NONE; });
    }
    private static PyObj.PySet toSet(Object o) {
        if (o instanceof PyObj.PySet) return (PyObj.PySet) o;
        PyObj.PySet s = new PyObj.PySet();
        for (Object x : listOfIterable(o)) if (setFind(s, x) == null) s.items.add(x);
        return s;
    }

    // ---- tuple ----------------------------------------------------------------
    static {
        TUPLE.put("count", (s, a) -> { arity(a, 1, 1, "count");
            long c = 0; for (Object x : ((PyObj.Tuple) s).items) if (Ops.pyEquals(x, a[0])) c++; return c; });
        TUPLE.put("index", (s, a) -> { arity(a, 1, 1, "index");
            Object[] xs = ((PyObj.Tuple) s).items;
            for (int i = 0; i < xs.length; i++) if (Ops.pyEquals(xs[i], a[0])) return (long) i;
            throw PyException.valueError("object not in sequence"); });
    }
}
