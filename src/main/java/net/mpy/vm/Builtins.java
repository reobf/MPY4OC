package net.mpy.vm;

import net.mpy.runtime.Ops;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;
import net.mpy.runtime.PyExc;
import net.mpy.runtime.Methods;

import java.math.BigInteger;
import java.util.Map;

/**
 * A small set of Python builtins implemented as {@link HostFunction}s - builtins
 * are just host functions living in globals. Call {@link #install} to make
 * {@code print}, {@code len}, {@code abs}, {@code str}, {@code bool}, {@code min},
 * {@code max} and {@code sum} available to scripts.
 *
 * (Note: {@code range} in a {@code for} loop is compiled to a counting loop and
 * needs no builtin; {@code range} as a value would need a lazy range object and
 * is intentionally left out here.)
 */
public final class Builtins {

    private Builtins() {}

    public static void install(Map<String, Object> globals) {
        // exception types (ValueError, TypeError, ... are looked up via LOAD_GLOBAL)
        globals.putAll(net.mpy.runtime.PyExc.TYPES);
        globals.putIfAbsent("print", Vm.PRINT_BUILTIN);
        globals.putIfAbsent("len", Vm.LEN_BUILTIN);
        globals.putIfAbsent("range", BuiltinType.RANGE);
        globals.putIfAbsent("list", BuiltinType.LIST);
        globals.putIfAbsent("abs", (HostFunction) a -> abs(a[0]));
        globals.putIfAbsent("str", BuiltinType.STR);
        globals.putIfAbsent("repr", Vm.REPR_BUILTIN);
        globals.putIfAbsent("next", Vm.NEXT_BUILTIN);
        globals.putIfAbsent("super", Vm.SUPER_BUILTIN);
        globals.putIfAbsent("set", BuiltinType.SET);
        globals.putIfAbsent("int", BuiltinType.INT);
        globals.putIfAbsent("float", BuiltinType.FLOAT);
        globals.putIfAbsent("chr", (HostFunction) a -> {
            long c = longOf(a[0]);
            return new String(Character.toChars((int) c));
        });
        globals.putIfAbsent("ord", (HostFunction) a -> {
            String s = (String) a[0];
            return (long) s.codePointAt(0);
        });
        globals.putIfAbsent("bool", BuiltinType.BOOL);
        globals.putIfAbsent("min", (HostFunction) a -> minmax(a, true));
        globals.putIfAbsent("max", (HostFunction) a -> minmax(a, false));
        globals.putIfAbsent("sum", Vm.SUM_BUILTIN);
        globals.putIfAbsent("round", (HostFunction) a -> {
            if (a.length == 0 || a.length > 2) throw PyException.typeError("round expected 1 or 2 arguments");
            double x = a[0] instanceof Double ? (Double) a[0]
                    : a[0] instanceof Long ? (Long) a[0]
                    : a[0] instanceof java.math.BigInteger ? ((java.math.BigInteger) a[0]).doubleValue()
                    : a[0] instanceof Boolean ? ((Boolean) a[0] ? 1.0 : 0.0)
                    : Double.NaN;
            if (Double.isNaN(x) && !(a[0] instanceof Double)) throw PyException.typeError("round: expected a number");
            if (a.length == 1) {
                if (a[0] instanceof Long || a[0] instanceof java.math.BigInteger || a[0] instanceof Boolean)
                    return a[0] instanceof Boolean ? (long) ((Boolean) a[0] ? 1 : 0) : a[0];
                double r = Math.rint(x);              // banker's rounding, like Python
                return PyObj.normInt(java.math.BigInteger.valueOf((long) r));
            }
            long nd = a[1] instanceof Long ? (Long) a[1] : ((Boolean) a[1] ? 1 : 0);
            if (a[0] instanceof Long || a[0] instanceof java.math.BigInteger || a[0] instanceof Boolean) {
                if (nd >= 0) return a[0];   // rounding an int to >=0 digits is a no-op
            }
            // MicroPython: nearbyint(val * 10^n) / 10^n, i.e. round-half-to-even
            // on the scaled float - pure float arithmetic (Math.rint == nearbyint
            // in the default rounding mode).
            double mult = Math.pow(10, (double) nd);
            return Math.rint(x * mult) / mult;
        });
        // ---- type queries -------------------------------------------------
        globals.putIfAbsent("type", BuiltinType.TYPE);
        globals.putIfAbsent("isinstance", (HostFunction) a -> {
            if (a.length != 2) throw PyException.typeError("isinstance expected 2 arguments");
            return isInstance(a[0], a[1]);
        });
        globals.putIfAbsent("issubclass", (HostFunction) a -> {
            if (a.length != 2) throw PyException.typeError("issubclass expected 2 arguments");
            return isSubclass(a[0], a[1]);
        });
        // value-type objects with no constructor already installed
        globals.putIfAbsent("object", BuiltinType.OBJECT);
        globals.putIfAbsent("bytes", BuiltinType.BYTES);
        globals.putIfAbsent("complex", BuiltinType.COMPLEX);
        globals.putIfAbsent("globals", Vm.GLOBALS_BUILTIN);
        globals.putIfAbsent("locals", Vm.LOCALS_BUILTIN);
        globals.putIfAbsent("compile", Vm.COMPILE_BUILTIN);
        globals.putIfAbsent("exec", Vm.EXEC_BUILTIN);
        globals.putIfAbsent("eval", Vm.EVAL_BUILTIN);
        globals.putIfAbsent("__jasyncio_create_task", Vm.CREATE_TASK_BUILTIN);
        globals.putIfAbsent("__jasyncio_run", Vm.RUN_BUILTIN);
        globals.putIfAbsent("__thread_test_and_set", Vm.TEST_AND_SET_BUILTIN);
        globals.putIfAbsent("__jasyncio_make_sleep", Vm.MAKE_SLEEP_BUILTIN);
        globals.putIfAbsent("finishDaemonOnExit", Vm.FINISH_DAEMON_BUILTIN);
        globals.putIfAbsent("dict", BuiltinType.DICT);
        globals.putIfAbsent("sorted", Vm.SORTED_BUILTIN);
        globals.putIfAbsent("map", Vm.MAP_BUILTIN);
        globals.putIfAbsent("filter", Vm.FILTER_BUILTIN);
        // ---- attribute access --------------------------------------------
        globals.putIfAbsent("hasattr", (HostFunction) a -> {
            try { return getAttr(a[0], (String) a[1]) != null; }
            catch (PyException e) { return Boolean.FALSE; }
        });
        globals.putIfAbsent("getattr", (HostFunction) a -> {
            Object r;
            try { r = getAttr(a[0], (String) a[1]); }
            catch (PyException e) { if (a.length >= 3) return a[2]; throw e; }
            if (r == null) { if (a.length >= 3) return a[2];
                throw new PyException("AttributeError", "no attribute '" + a[1] + "'"); }
            return r;
        });
        globals.putIfAbsent("setattr", (HostFunction) a -> {
            if (a[0] instanceof PyInstance) { ((PyInstance) a[0]).attrs.put((String) a[1], a[2]); return PyObj.NONE; }
            throw PyException.typeError("can't set attribute on " + StdLib.typeName(a[0]));
        });
        globals.putIfAbsent("delattr", (HostFunction) a -> {
            if (a[0] instanceof PyInstance) {
                if (((PyInstance) a[0]).attrs.remove((String) a[1]) == null)
                    throw new PyException("AttributeError", "'" + ((PyInstance) a[0]).cls.name
                            + "' object has no attribute '" + a[1] + "'");
                return PyObj.NONE;
            }
            throw PyException.typeError("can't delete attribute on " + StdLib.typeName(a[0]));
        });
        globals.putIfAbsent("dir", (HostFunction) a -> {
            // instance: special names, then class-chain namespace, then instance attrs.
            // (contents match MicroPython; element order follows this VM's insertion
            // order rather than the reference's hash-bucket order.)
            java.util.LinkedHashSet<Object> names = new java.util.LinkedHashSet<>();
            if (a.length >= 1 && a[0] instanceof PyInstance) {
                PyInstance inst = (PyInstance) a[0];
                names.add("__class__");
                if (inst.cls.lookup("__init__") != null) names.add("__init__");
                names.add("__module__");
                names.add("__qualname__");
                names.add("__dict__");
                collectClassNames(inst.cls, names);
                names.addAll(inst.attrs.keySet());
            } else if (a.length >= 1 && a[0] instanceof PyClass) {
                names.add("__class__");
                names.add("__module__");
                names.add("__qualname__");
                collectClassNames((PyClass) a[0], names);
            }
            return new PyObj.PyList(new java.util.ArrayList<>(names));
        });
        // ---- numeric / radix ---------------------------------------------
        globals.putIfAbsent("divmod", (HostFunction) a -> {
            Object q = Ops.binaryOp(Ops.FLOOR_DIVIDE, a[0], a[1]);
            Object r = Ops.binaryOp(Ops.MODULO, a[0], a[1]);
            return new PyObj.Tuple(new Object[]{q, r});
        });
        globals.putIfAbsent("hex", (HostFunction) a -> radix(a[0], 16, "0x"));
        globals.putIfAbsent("oct", (HostFunction) a -> radix(a[0], 8, "0o"));
        globals.putIfAbsent("bin", (HostFunction) a -> radix(a[0], 2, "0b"));
        globals.putIfAbsent("pow", (HostFunction) a -> {
            if (a.length == 2) return Ops.binaryOp(Ops.POWER, a[0], a[1]);
            java.math.BigInteger base = toBig(a[0]), exp = toBig(a[1]), mod = toBig(a[2]);
            return PyObj.normInt(base.modPow(exp, mod));
        });
        // ---- iteration helpers (no user callable) ------------------------
        globals.putIfAbsent("all", Vm.ALL_BUILTIN);
        globals.putIfAbsent("any", Vm.ANY_BUILTIN);
        globals.putIfAbsent("enumerate", (HostFunction) a -> {
            long start = a.length >= 2 ? ((Long) a[1]) : 0;
            PyObj.PyList out = new PyObj.PyList();
            PyObj.Iter it = Ops.getIter(a[0]); Object v;
            while ((v = it.next()) != PyObj.STOP_ITERATION)
                out.items.add(new PyObj.Tuple(new Object[]{start++, v}));
            return out;
        });
        globals.putIfAbsent("zip", (HostFunction) a -> {
            PyObj.Iter[] its = new PyObj.Iter[a.length];
            for (int i = 0; i < a.length; i++) its[i] = Ops.getIter(a[i]);
            PyObj.PyList out = new PyObj.PyList();
            outer:
            while (true) {
                Object[] row = new Object[a.length];
                for (int i = 0; i < a.length; i++) {
                    Object v = its[i].next();
                    if (v == PyObj.STOP_ITERATION) break outer;
                    row[i] = v;
                }
                out.items.add(new PyObj.Tuple(row));
            }
            return out;
        });
        globals.putIfAbsent("reversed", (HostFunction) a -> {
            java.util.List<Object> src = toList(a[0]);
            PyObj.PyList out = new PyObj.PyList();
            for (int i = src.size() - 1; i >= 0; i--) out.items.add(src.get(i));
            return out;
        });
        globals.putIfAbsent("iter", (HostFunction) a -> {
            PyObj.Iter it = Ops.getIter(a[0]);
            PyObj.PyList buf = new PyObj.PyList();  // materialize (VM iterators aren't first-class yet)
            Object v; while ((v = it.next()) != PyObj.STOP_ITERATION) buf.items.add(v);
            return Ops.getIter(buf);
        });
        globals.putIfAbsent("staticmethod", (HostFunction) a -> new Descriptors.StaticMethod(a[0]));
        globals.putIfAbsent("classmethod", (HostFunction) a -> new Descriptors.ClassMethod(a[0]));
        globals.putIfAbsent("property", (HostFunction) a -> new Descriptors.Property(a[0]));
        globals.putIfAbsent("tuple", BuiltinType.TUPLE);
    }

    /** str(): strings pass through unquoted; everything else uses repr. */
    // ---- tier-1 builtin helpers ---------------------------------------------

    /** The type object for any runtime value. */
    static Object typeOf(Object v) {
        if (v instanceof PyInstance) return ((PyInstance) v).cls;
        if (v instanceof PyExc.Instance) return ((PyExc.Instance) v).type;
        BuiltinType bt = builtinTypeOf(v);
        if (bt != null) return bt;
        // type objects are instances of `type` (the metaclass)
        if (v instanceof PyClass || v instanceof BuiltinType || v instanceof PyExc.Type)
            return BuiltinType.TYPE;
        if (v instanceof CompiledCode) return BuiltinType.CODE;
        // callables that are functions report as `function`
        if (v instanceof PyFunction || v instanceof Closure || v instanceof HostFunction
                || v instanceof BoundPyMethod || v instanceof Vm.NativeBuiltin)
            return BuiltinType.FUNCTION;
        return BuiltinType.OBJECT;
    }

    private static void collectClassNames(PyClass cls, java.util.LinkedHashSet<Object> out) {
        for (String k : cls.ns.keySet()) out.add(k);
        for (PyClass b : cls.bases) collectClassNames(b, out);
    }

    private static BuiltinType builtinTypeOf(Object v) {
        if (v instanceof Boolean) return BuiltinType.BOOL;   // before int
        if (v instanceof Long || v instanceof java.math.BigInteger) return BuiltinType.INT;
        if (v instanceof Double) return BuiltinType.FLOAT;
        if (v instanceof String) return BuiltinType.STR;
        if (v instanceof PyObj.PyList) return BuiltinType.LIST;
        if (v instanceof PyObj.Tuple) return BuiltinType.TUPLE;
        if (v instanceof PyObj.PyDict) return BuiltinType.DICT;
        if (v instanceof PyObj.PySet) return BuiltinType.SET;
        if (v instanceof PyObj.Bytes) return BuiltinType.BYTES;
        if (v instanceof PyObj.Range) return BuiltinType.RANGE;
        if (v instanceof PyObj.Complex) return BuiltinType.COMPLEX;
        return null;
    }

    static Object isInstance(Object v, Object typeSpec) {
        if (typeSpec instanceof PyObj.Tuple) {
            for (Object t : ((PyObj.Tuple) typeSpec).items)
                if (isInstance(v, t) == Boolean.TRUE) return Boolean.TRUE;
            return Boolean.FALSE;
        }
        if (typeSpec instanceof BuiltinType) {
            BuiltinType bt = (BuiltinType) typeSpec;
            if (bt == BuiltinType.OBJECT) return Boolean.TRUE;
            // a subclass instance of a built-in (class MyStr(str)) is an instance
            // of that built-in: MyStr('x') isinstance str
            if (v instanceof PyInstance) {
                BuiltinType nb = ((PyInstance) v).cls.nativeBase();
                if (nb == bt) return Boolean.TRUE;
            }
            return bt.matches(v);
        }
        if (typeSpec instanceof PyClass) {
            PyClass pc = (PyClass) typeSpec;
            // user exception class: match a PyExc.Instance via the exc type chain
            if (pc.excType != null && v instanceof PyExc.Instance)
                return PyExc.isSub(((PyExc.Instance) v).type, pc.excType);
            if (!(v instanceof PyInstance)) return Boolean.FALSE;
            return classIsSub(((PyInstance) v).cls, pc);
        }
        if (typeSpec instanceof PyExc.Type) {
            return v instanceof PyExc.Instance
                    && PyExc.isSub(((PyExc.Instance) v).type, (PyExc.Type) typeSpec);
        }
        throw PyException.typeError("isinstance() arg 2 must be a type or tuple of types");
    }

    static Object isSubclass(Object cls, Object typeSpec) {
        if (typeSpec instanceof PyObj.Tuple) {
            for (Object t : ((PyObj.Tuple) typeSpec).items)
                if (isSubclass(cls, t) == Boolean.TRUE) return Boolean.TRUE;
            return Boolean.FALSE;
        }
        if (cls instanceof PyClass && typeSpec instanceof PyClass)
            return classIsSub((PyClass) cls, (PyClass) typeSpec);
        if (cls instanceof PyClass && typeSpec instanceof BuiltinType)
            return ((BuiltinType) typeSpec) == BuiltinType.OBJECT ? Boolean.TRUE : Boolean.FALSE;
        if (cls instanceof BuiltinType && typeSpec instanceof BuiltinType)
            return (cls == typeSpec || typeSpec == BuiltinType.OBJECT) ? Boolean.TRUE : Boolean.FALSE;
        if (cls instanceof PyExc.Type && typeSpec instanceof PyExc.Type)
            return PyExc.isSub((PyExc.Type) cls, (PyExc.Type) typeSpec);
        // a BuiltinType wrapping an exception type vs another
        if (cls instanceof BuiltinType && ((BuiltinType) cls).excType != null
                && typeSpec instanceof BuiltinType && ((BuiltinType) typeSpec).excType != null)
            return PyExc.isSub(((BuiltinType) cls).excType, ((BuiltinType) typeSpec).excType);
        if (!(cls instanceof PyClass || cls instanceof BuiltinType || cls instanceof PyExc.Type))
            throw PyException.typeError("issubclass() arg 1 must be a class");
        return Boolean.FALSE;
    }

    private static boolean classIsSub(PyClass c, PyClass of) {
        if (c == of) return true;
        for (PyClass b : c.bases) if (classIsSub(b, of)) return true;
        return false;
    }

    /** getattr core: instance attrs, class methods, module/dict members. Returns
     *  null if absent (callers apply defaults or raise). */
    static Object getAttr(Object obj, String name) {
        if (obj instanceof PyInstance) {
            PyInstance inst = (PyInstance) obj;
            Object v = inst.attrs.get(name);
            if (v != null) return v;
            Object m = inst.cls.lookup(name);
            if (m instanceof PyFunction || m instanceof Closure) return new BoundPyMethod(m, inst);
            return m;
        }
        if (obj instanceof PyModule) return ((PyModule) obj).ns.get(name);
        if (obj instanceof PyClass) {
            if (name.equals("__name__")) return ((PyClass) obj).name;
            return ((PyClass) obj).lookup(name);
        }
        if (obj instanceof PyExc.Type) {
            if (name.equals("__name__")) return ((PyExc.Type) obj).name;
            return null;
        }
        if (obj instanceof BuiltinType) {
            if (name.equals("__name__")) return ((BuiltinType) obj).name;
            return null;
        }
        Object bm = Methods.lookup(obj, name) != null ? new Methods.BoundMethod(obj, name) : null;
        return bm;
    }

    private static Object radix(Object o, int base, String prefix) {
        java.math.BigInteger v = toBig(o);
        String s = v.abs().toString(base);
        return (v.signum() < 0 ? "-" : "") + prefix + s;
    }

    static java.math.BigInteger toBig(Object o) {
        if (o instanceof Long) return java.math.BigInteger.valueOf((Long) o);
        if (o instanceof java.math.BigInteger) return (java.math.BigInteger) o;
        if (o instanceof Boolean) return (Boolean) o ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO;
        throw PyException.typeError("expected int, got " + StdLib.typeName(o));
    }

    static java.util.List<Object> toList(Object o) {
        if (o instanceof PyObj.PyList) return ((PyObj.PyList) o).items;
        java.util.List<Object> out = new java.util.ArrayList<>();
        PyObj.Iter it = Ops.getIter(o); Object v;
        while ((v = it.next()) != PyObj.STOP_ITERATION) out.add(v);
        return out;
    }

    public static String str(Object o) {
        if (o instanceof String) return (String) o;
        if (o instanceof net.mpy.runtime.PyExc.Instance) return net.mpy.runtime.PyExc.str((net.mpy.runtime.PyExc.Instance) o);
        return PyObj.repr(o);
    }

    static int lenOf(Object o) {
        if (o instanceof String) return ((String) o).length();
        if (o instanceof PyObj.Bytes) return ((PyObj.Bytes) o).data.length;
        if (o instanceof PyObj.Tuple) return ((PyObj.Tuple) o).items.length;
        if (o instanceof PyObj.PyList) return ((PyObj.PyList) o).items.size();
        if (o instanceof PyObj.PyDict) return ((PyObj.PyDict) o).map.size();
        if (o instanceof PyObj.PySet) return ((PyObj.PySet) o).items.size();
        if (o instanceof PyObj.Range) return (int) ((PyObj.Range) o).length();
        throw PyException.typeError("object has no len()");
    }

    private static long longOf(Object o) {
        o = Ops.unwrapNative(o);
        if (o instanceof Long) return (Long) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof java.math.BigInteger) return ((java.math.BigInteger) o).longValueExact();
        throw PyException.typeError("an integer is required");
    }

    private static Object abs(Object o) {
        o = Ops.unwrapNative(o);
        if (o instanceof PyObj.Complex) {
            PyObj.Complex c = (PyObj.Complex) o;
            return Math.hypot(c.re, c.im);
        }
        if (o instanceof Double) return Math.abs((Double) o);
        if (o instanceof Long) { long v = (Long) o; return v < 0 ? PyObj.normInt(BigInteger.valueOf(v).negate()) : v; }
        if (o instanceof BigInteger) return PyObj.normInt(((BigInteger) o).abs());
        if (o instanceof Boolean) return (Boolean) o ? 1L : 0L;
        throw PyException.typeError("bad operand type for abs()");
    }

    private static Object minmax(Object[] a, boolean wantMin) {
        Iterable<Object> seq;
        if (a.length == 1) {
            java.util.List<Object> items = new java.util.ArrayList<>();
            PyObj.Iter it = Ops.getIter(a[0]);
            Object v;
            while ((v = it.next()) != PyObj.STOP_ITERATION) items.add(v);
            seq = items;
        } else {
            seq = java.util.Arrays.asList(a);
        }
        Object best = null;
        boolean first = true;
        for (Object x : seq) {
            if (first) { best = x; first = false; continue; }
            int c = Ops.compare(x, best);
            if (wantMin ? c < 0 : c > 0) best = x;
        }
        if (first) throw PyException.valueError((wantMin ? "min" : "max") + "() arg is an empty sequence");
        return best;
    }
}
