package net.mpy.vm;

import net.mpy.runtime.Ops;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyExc;
import net.mpy.runtime.PyObj;

import java.math.BigInteger;
import java.util.function.Predicate;

/**
 * A first-class, callable type object for the built-in types (int/str/list/...)
 * and the metaclass `type` / base `object`. Being callable, the type object IS
 * the constructor - {@code int("5")} calls this, {@code type(x)} reads it as a
 * type - so there's no separate constructor function and no side table mapping
 * one to the other. {@code type()} / {@code isinstance()} / {@code issubclass()}
 * test against these directly, and {@code __name__} is a real attribute.
 *
 * Construction logic lives in {@link #construct}; it takes the VM so that types
 * needing frame-driven work (iterating a generator argument, building a class
 * for 3-arg {@code type}) can do it. Exception types are represented by
 * {@link PyExc.Type} elsewhere; this class only wraps them for {@code type()} of
 * an exception instance when needed.
 */
public final class BuiltinType {
    public final String name;
    private final Predicate<Object> test;   // isinstance predicate (value types)
    public final PyExc.Type excType;        // non-null only for exception wrappers
    private final Kind kind;                // dispatch tag for construct()

    private enum Kind { INT, FLOAT, STR, BOOL, LIST, TUPLE, DICT, SET, BYTES, RANGE, OBJECT, TYPE, FUNCTION, COMPLEX, EXC }

    private BuiltinType(String name, Kind kind, Predicate<Object> test, PyExc.Type excType) {
        this.name = name; this.kind = kind; this.test = test; this.excType = excType;
    }

    static BuiltinType exc(PyExc.Type t) {
        return new BuiltinType(t.name, Kind.EXC, null, t);
    }

    /** isinstance(value, this). */
    public boolean matches(Object v) {
        if (excType != null) return v instanceof PyExc.Instance && PyExc.isSub(((PyExc.Instance) v).type, excType);
        return test.test(v);
    }

    /** Whether this type object is itself callable to construct (all value types
     *  plus `type`; `object` and exception wrappers are not constructed here). */
    boolean isConstructor() {
        return kind != Kind.EXC && kind != Kind.FUNCTION;
    }

    /** Whether a user class may inherit from this built-in type. Matches the set
     *  MicroPython permits: int, float, str, list, tuple, dict, set, bytes. */
    boolean canSubclass() {
        switch (kind) {
            case INT: case FLOAT: case STR: case LIST:
            case TUPLE: case DICT: case SET: case BYTES: return true;
            default: return false;
        }
    }

    public boolean isStr()  { return kind == Kind.STR; }
    public boolean isList() { return kind == Kind.LIST; }

    @Override public String toString() { return "<class '" + name + "'>"; }

    /**
     * Construct: the type object called as a function. Most are one-argument
     * conversions; {@code type} is special (1-arg = type query, 3-arg = build a
     * class); {@code object} builds a bare instance.
     */
    Object construct(Vm vm, Object[] a, String[] kwNames, Object[] kwValues) {
        switch (kind) {
            case INT:   return constructInt(a);
            case FLOAT: return constructFloat(a);
            case COMPLEX: return constructComplex(a);
            case STR:   return a.length == 0 ? "" : vm.stringify(a[0], false);
            case BOOL:  return a.length == 0 ? Boolean.FALSE : (Boolean) vm.truthy(a[0]);
            case LIST: {
                PyObj.PyList out = new PyObj.PyList();
                if (a.length >= 1) out.items.addAll(vm.materialize(a[0]));
                return out;
            }
            case TUPLE: {
                if (a.length == 0) return new PyObj.Tuple(new Object[0]);
                return new PyObj.Tuple(vm.materialize(a[0]).toArray());
            }
            case DICT: {
                PyObj.PyDict d = new PyObj.PyDict();
                if (a.length >= 1 && a[0] != PyObj.NONE) {
                    if (a[0] instanceof PyObj.PyDict) d.map.putAll(((PyObj.PyDict) a[0]).map);
                    else for (Object v : vm.materialize(a[0])) {
                        if (!(v instanceof PyObj.Tuple) || ((PyObj.Tuple) v).items.length != 2)
                            throw PyException.typeError("dict update sequence element is not a pair");
                        Object[] kv = ((PyObj.Tuple) v).items;
                        d.map.put(kv[0], kv[1]);
                    }
                }
                for (int i = 0; i < kwNames.length; i++) d.map.put(kwNames[i], kwValues[i]);
                return d;
            }
            case SET: {
                PyObj.PySet s = new PyObj.PySet();
                if (a.length == 1) {
                    for (Object v : vm.materialize(a[0])) {
                        boolean dup = false;
                        for (Object x : s.items) if (Ops.pyEquals(x, v)) { dup = true; break; }
                        if (!dup) s.items.add(v);
                    }
                } else if (a.length > 1) throw PyException.typeError("set expected at most 1 argument");
                return s;
            }
            case BYTES: {
                if (a.length == 0) return new PyObj.Bytes(new byte[0]);
                Object o = a[0];
                if (o instanceof PyObj.Bytes) return o;
                if (o instanceof Long || o instanceof BigInteger) {   // bytes(n) -> n zero bytes
                    int n = (int) Builtins.toBig(o).longValueExact();
                    return new PyObj.Bytes(new byte[n]);
                }
                // bytes(iterable-of-ints)
                java.util.List<Object> items = vm.materialize(o);
                byte[] b = new byte[items.size()];
                for (int i = 0; i < b.length; i++) b[i] = (byte) Builtins.toBig(items.get(i)).intValueExact();
                return new PyObj.Bytes(b);
            }
            case RANGE: {
                if (a.length == 1) return new PyObj.Range(0, asLong(a[0]), 1);
                if (a.length == 2) return new PyObj.Range(asLong(a[0]), asLong(a[1]), 1);
                if (a.length == 3) return new PyObj.Range(asLong(a[0]), asLong(a[1]), asLong(a[2]));
                throw PyException.typeError("range expected 1 to 3 arguments");
            }
            case OBJECT:
                return new PyInstance(OBJECT_CLASS);
            case TYPE:
                // 1-arg: type query; 3-arg: build a class dynamically
                if (a.length == 1) return Builtins.typeOf(a[0]);
                if (a.length == 3) return buildClass(a);
                throw PyException.typeError("type() takes 1 or 3 arguments");
            default:
                throw PyException.typeError("cannot create '" + name + "' instances");
        }
    }

    /** type(name, bases, dict) -> a new class. */
    private static Object buildClass(Object[] a) {
        if (!(a[0] instanceof String)) throw PyException.typeError("type() argument 1 must be str");
        String cname = (String) a[0];
        if (!(a[1] instanceof PyObj.Tuple)) throw PyException.typeError("type() argument 2 must be a tuple");
        Object[] baseObjs = ((PyObj.Tuple) a[1]).items;
        java.util.List<PyClass> baseList = new java.util.ArrayList<>();
        BuiltinType nativeBase = null;
        for (Object bo : baseObjs) {
            if (bo instanceof PyClass) baseList.add((PyClass) bo);
            else if (bo instanceof BuiltinType && ((BuiltinType) bo).canSubclass()) nativeBase = (BuiltinType) bo;
            else throw PyException.typeError("type() bases must be classes");
        }
        if (!(a[2] instanceof PyObj.PyDict)) throw PyException.typeError("type() argument 3 must be a dict");
        PyClass cls = new PyClass(cname, baseList.toArray(new PyClass[0]));
        if (nativeBase != null) cls.nativeBase = nativeBase;
        for (var e : ((PyObj.PyDict) a[2]).map.entrySet()) {
            if (!(e.getKey() instanceof String))
                throw PyException.typeError("type() namespace keys must be str");
            cls.ns.put((String) e.getKey(), e.getValue());
        }
        return cls;
    }

    private static long asLong(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof BigInteger) return ((BigInteger) o).longValueExact();
        throw PyException.typeError("expected int, got " + StdLib.typeName(o));
    }

    private static Object constructInt(Object[] a) {
        if (a.length == 0) return 0L;
        Object o = a[0];
        if (o instanceof Long || o instanceof BigInteger) return o;
        if (o instanceof Boolean) return (Boolean) o ? 1L : 0L;
        if (o instanceof Double) return PyObj.normInt(java.math.BigDecimal.valueOf((Double) o).toBigInteger());
        if (o instanceof String) {
            int base = a.length >= 2 ? (int) asLong(a[1]) : 10;
            String s = ((String) o).trim();
            try { return PyObj.normInt(new BigInteger(s, base)); }
            catch (NumberFormatException e) {
                throw PyException.valueError("invalid syntax for integer with base " + base + ": " + PyObj.repr(s));
            }
        }
        throw PyException.typeError("can't convert to int");
    }

    private static Object constructFloat(Object[] a) {
        if (a.length == 0) return 0.0;
        Object o = a[0];
        if (o instanceof Double) return o;
        if (o instanceof Long) return (double) (Long) o;
        if (o instanceof BigInteger) return ((BigInteger) o).doubleValue();
        if (o instanceof Boolean) return (Boolean) o ? 1.0 : 0.0;
        if (o instanceof String) {
            String s = ((String) o).trim();
            String l = s.toLowerCase();
            if (l.equals("inf") || l.equals("+inf") || l.equals("infinity")) return Double.POSITIVE_INFINITY;
            if (l.equals("-inf") || l.equals("-infinity")) return Double.NEGATIVE_INFINITY;
            if (l.equals("nan") || l.equals("-nan")) return Double.NaN;
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) { throw PyException.valueError("invalid syntax for number"); }
        }
        throw PyException.typeError("can't convert to float");
    }

    private static Object constructComplex(Object[] a) {
        if (a.length == 0) return new PyObj.Complex(0, 0);
        if (a.length == 1) {
            Object o = a[0];
            if (o instanceof PyObj.Complex) return o;
            if (o instanceof String) return parseComplexStr((String) o);
            return new PyObj.Complex(Ops.complexRe(o), 0);
        }
        // complex(re, im)
        double re = Ops.complexRe(a[0]) - Ops.complexIm(a[1]);
        double im = Ops.complexIm(a[0]) + Ops.complexRe(a[1]);
        return new PyObj.Complex(re, im);
    }
    private static Object parseComplexStr(String s) {
        s = s.trim();
        if (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length() - 1);
        try {
            if (s.endsWith("j") || s.endsWith("J")) {
                String body = s.substring(0, s.length() - 1);
                // find split point for re+imj (sign not at position 0 or after e/E)
                int split = -1;
                for (int i = 1; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if ((c == '+' || c == '-') && body.charAt(i - 1) != 'e' && body.charAt(i - 1) != 'E') split = i;
                }
                if (split < 0) {
                    double im = body.isEmpty() || body.equals("+") ? 1.0 : body.equals("-") ? -1.0 : Double.parseDouble(body);
                    return new PyObj.Complex(0, im);
                }
                double re = Double.parseDouble(body.substring(0, split));
                String is = body.substring(split);
                double im = is.equals("+") ? 1.0 : is.equals("-") ? -1.0 : Double.parseDouble(is);
                return new PyObj.Complex(re, im);
            }
            return new PyObj.Complex(Double.parseDouble(s), 0);
        } catch (NumberFormatException e) {
            throw PyException.valueError("complex() arg is a malformed string");
        }
    }

    /** A minimal base class so object() instances have a home. */
    static final PyClass OBJECT_CLASS = new PyClass("object", new PyClass[0]);

    /** Resolve a value-type by name for snapshot restore. */
    static BuiltinType byName(String n) {
        switch (n) {
            case "int": return INT; case "float": return FLOAT; case "str": return STR;
            case "bool": return BOOL; case "list": return LIST; case "tuple": return TUPLE;
            case "dict": return DICT; case "set": return SET; case "bytes": return BYTES;
            case "range": return RANGE; case "object": return OBJECT;
            case "type": return TYPE; case "function": return FUNCTION;
            case "complex": return COMPLEX;
            default: throw new IllegalStateException("unknown builtin type: " + n);
        }
    }

    // ---- the built-in type objects ----
    static final BuiltinType INT = new BuiltinType("int", Kind.INT,
            o -> o instanceof Long || o instanceof BigInteger, null);   // bool is NOT int in MicroPython
    static final BuiltinType FLOAT = new BuiltinType("float", Kind.FLOAT, o -> o instanceof Double, null);
    static final BuiltinType COMPLEX = new BuiltinType("complex", Kind.COMPLEX, o -> o instanceof PyObj.Complex, null);
    static final BuiltinType STR = new BuiltinType("str", Kind.STR, o -> o instanceof String, null);
    static final BuiltinType BOOL = new BuiltinType("bool", Kind.BOOL, o -> o instanceof Boolean, null);
    static final BuiltinType LIST = new BuiltinType("list", Kind.LIST, o -> o instanceof PyObj.PyList, null);
    static final BuiltinType TUPLE = new BuiltinType("tuple", Kind.TUPLE, o -> o instanceof PyObj.Tuple, null);
    static final BuiltinType DICT = new BuiltinType("dict", Kind.DICT, o -> o instanceof PyObj.PyDict, null);
    static final BuiltinType SET = new BuiltinType("set", Kind.SET, o -> o instanceof PyObj.PySet, null);
    static final BuiltinType BYTES = new BuiltinType("bytes", Kind.BYTES, o -> o instanceof PyObj.Bytes, null);
    static final BuiltinType RANGE = new BuiltinType("range", Kind.RANGE, o -> o instanceof PyObj.Range, null);
    static final BuiltinType OBJECT = new BuiltinType("object", Kind.OBJECT, o -> true, null);
    static final BuiltinType TYPE = new BuiltinType("type", Kind.TYPE,
            o -> o instanceof PyClass || o instanceof BuiltinType || o instanceof PyExc.Type, null);
    static final BuiltinType CODE = new BuiltinType("code", Kind.OBJECT, o -> o instanceof CompiledCode, null);
    static final BuiltinType FUNCTION = new BuiltinType("function", Kind.FUNCTION,
            o -> o instanceof PyFunction || o instanceof Closure || o instanceof HostFunction
                    || o instanceof BoundPyMethod || o instanceof Vm.NativeBuiltin, null);
}
