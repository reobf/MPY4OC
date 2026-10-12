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
        a = placeArgs(a, kwNames, kwValues);
        switch (kind) {
            case INT:   return constructInt(vm, a);
            case FLOAT: return constructFloat(vm, a);
            case COMPLEX: return constructComplex(a);
            case STR:   return constructStr(vm, a);
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
                    else if (!vm.copyMappingTo(d, a[0])) for (Object v : vm.materialize(a[0])) {
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
                Object o = a.length == 0 ? MISSING : a[0];
                if (o instanceof String || a.length > 1) {   // bytes(str, encoding[, errors]): UTF-8, as str.encode
                    if (!(o instanceof String))
                        throw PyException.typeError((a.length > 1 && a[1] != MISSING ? "encoding" : "errors") + " without a string argument");
                    if (a.length < 2 || a[1] == MISSING) throw PyException.typeError("string argument without an encoding");
                    textArg(a, 1, "bytes", "encoding");
                    textArg(a, 2, "bytes", "errors");
                    return new PyObj.Bytes(((String) o).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                if (o == MISSING) return new PyObj.Bytes(new byte[0]);
                if (o instanceof PyObj.Bytes) return o;
                if (o instanceof Long || o instanceof BigInteger) {   // bytes(n) -> n zero bytes
                    int n = (int) Builtins.toBig(o).longValueExact();
                    net.mpy.runtime.Ops.checkAlloc(n, 1);
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

    /** A parameter given neither by position nor by name, before one that was
     *  (str(errors='strict')); only {@link #placeArgs} produces it. */
    private static final Object MISSING = new Object();

    /** CPython's argument rules for the constructors: how many positional
     *  arguments each takes and which parameters may be named (int's base,
     *  str's and bytes' encoding/errors, complex's real/imag; dict's keywords
     *  are its items). Named arguments are moved to their positional slot, so
     *  int('10', base=16) is int('10', 16) instead of a silent int('10'). */
    private Object[] placeArgs(Object[] a, String[] kwNames, Object[] kwValues) {
        String[] params;           // null entries are positional-only
        switch (kind) {
            case INT:     params = new String[]{null, "base"}; break;
            case STR:     params = new String[]{"object", "encoding", "errors"}; break;
            case BYTES:   params = new String[]{"source", "encoding", "errors"}; break;
            case COMPLEX: params = new String[]{"real", "imag"}; break;
            case RANGE:   params = new String[3]; break;
            case OBJECT:  params = new String[0]; break;
            case FLOAT: case BOOL: case LIST: case TUPLE: case SET: case DICT: params = new String[1]; break;
            default: return a;     // type(): 1 or 3 arguments, checked where it is built
        }
        int named = kind == Kind.DICT ? 0 : kwNames.length;
        if (kind == Kind.OBJECT && a.length + named > 0) throw PyException.typeError("object() takes no arguments");
        if (kind == Kind.RANGE && a.length == 0 && named == 0) throw PyException.typeError("range expected at least 1 argument, got 0");
        if (a.length > params.length) {
            if (kind == Kind.INT || kind == Kind.STR || kind == Kind.BYTES || kind == Kind.COMPLEX)
                throw PyException.typeError(name + "() takes at most " + params.length + " arguments (" + a.length + " given)");
            throw PyException.typeError(name + " expected at most " + params.length + " argument" + (params.length == 1 ? "" : "s") + ", got " + a.length);
        }
        if (named == 0) return a;
        boolean any = false;
        for (String p : params) any |= p != null;
        if (!any) throw PyException.typeError(name + "() takes no keyword arguments");
        Object[] placed = a;
        for (int i = 0; i < named; i++) {
            int slot = java.util.Arrays.asList(params).indexOf(kwNames[i]);
            if (slot < 0) throw PyException.typeError("'" + kwNames[i] + "' is an invalid keyword argument for " + name + "()");
            if (slot < a.length)
                throw PyException.typeError("argument for " + name + "() given by name ('" + kwNames[i] + "') and position (" + (slot + 1) + ")");
            if (slot >= placed.length) {
                Object[] grown = java.util.Arrays.copyOf(placed, slot + 1);
                java.util.Arrays.fill(grown, placed.length, slot + 1, MISSING);
                placed = grown;
            }
            placed[slot] = kwValues[i];
        }
        return placed;
    }

    /** str(), str(object), or str(bytes, encoding[, errors]) -- decoded as bytes.decode does. */
    private Object constructStr(Vm vm, Object[] a) {
        Object o = a.length == 0 ? MISSING : a[0];
        if (a.length < 2) return o == MISSING ? "" : vm.stringify(o, false);
        textArg(a, 1, "str", "encoding");
        textArg(a, 2, "str", "errors");
        Object v = o == MISSING ? new PyObj.Bytes(new byte[0]) : Ops.unwrapNative(o);
        Object encoding = a[1] != MISSING ? Ops.unwrapNative(a[1]) : "utf-8";
        Object errors = a.length > 2 && a[2] != MISSING ? Ops.unwrapNative(a[2]) : "strict";
        if (v instanceof PyObj.Bytes) return net.mpy.runtime.Methods.decode(((PyObj.Bytes) v).data, encoding, errors);
        if (v instanceof PyObj.ByteArray) return net.mpy.runtime.Methods.decode(((PyObj.ByteArray) v).toBytes(), encoding, errors);
        if (v instanceof String) throw PyException.typeError("decoding str is not supported");
        throw PyException.typeError("decoding to str: need a bytes-like object, " + StdLib.typeName(o) + " found");
    }

    /** An encoding/errors argument, when given, must be a str. */
    private static void textArg(Object[] a, int i, String fn, String param) {
        if (i < a.length && a[i] != MISSING && !(Ops.unwrapNative(a[i]) instanceof String))
            throw PyException.typeError(fn + "() argument '" + param + "' must be str, not " + StdLib.typeName(a[i]));
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

    /** int(x) asks a user instance for __int__, then __index__, then __trunc__. */
    private static final String[] INT_DUNDERS = {"__int__", "__index__", "__trunc__"};

    private static Object constructInt(Vm vm, Object[] a) {
        if (a.length == 0) return 0L;
        Object o = a[0];
        if (o == MISSING) throw PyException.typeError("int() missing string argument");   // int(base=16)
        if (a.length == 1 && vm != null) {
            for (String d : INT_DUNDERS) {
                Object r = vm.hooks.callDunder(o, d);
                if (r == null) continue;
                Object ri = Ops.unwrapNative(r);
                if (ri instanceof Boolean) return (Boolean) ri ? 1L : 0L;
                if (ri instanceof Long || ri instanceof BigInteger) return ri;
                throw PyException.typeError(d + " returned non-int (type " + StdLib.typeName(r) + ")");
            }
        }
        o = bytesAsText(Ops.unwrapNative(o));
        if (a.length >= 2) {
            if (!(o instanceof String)) throw PyException.typeError("int() can't convert non-string with explicit base");
            return parseInt((String) o, asLong(a[1]));
        }
        if (o instanceof Long || o instanceof BigInteger) return o;
        if (o instanceof Boolean) return (Boolean) o ? 1L : 0L;
        if (o instanceof Double) return PyObj.normInt(Ops.floatToBig((Double) o));
        if (o instanceof String) return parseInt((String) o, 10);
        throw PyException.typeError("can't convert " + StdLib.typeName(a[0]) + " to int");
    }

    /** int(text, base): optional sign, the 0x/0o/0b prefix matching the base
     *  (base 0 picks it from the prefix and rejects leading zeros). */
    private static Object parseInt(String text, long base) {
        if (base != 0 && (base < 2 || base > 36)) throw PyException.valueError("int() base must be >= 2 and <= 36");
        String s = text.trim();
        int i = 0, b = (int) base;
        boolean neg = false;
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) { neg = s.charAt(0) == '-'; i = 1; }
        if (s.length() - i >= 2 && s.charAt(i) == '0') {
            char p = Character.toLowerCase(s.charAt(i + 1));
            int pb = p == 'x' ? 16 : p == 'o' ? 8 : p == 'b' ? 2 : 0;
            if (pb != 0 && (b == 0 || b == pb)) { b = pb; i += 2; }
        }
        String digits = s.substring(i);
        if (b == 0) {
            b = 10;
            for (int k = 1; k < digits.length() && digits.charAt(0) == '0'; k++)
                if (digits.charAt(k) != '0') throw invalidInt(text, base);
        }
        if (digits.isEmpty() || digits.charAt(0) == '+' || digits.charAt(0) == '-') throw invalidInt(text, base);
        try {
            BigInteger v = new BigInteger(digits, b);
            return PyObj.normInt(neg ? v.negate() : v);
        } catch (NumberFormatException e) {
            throw invalidInt(text, base);
        }
    }

    private static PyException invalidInt(String text, long base) {
        return PyException.valueError("invalid syntax for integer with base " + base + ": " + PyObj.repr(text.trim()));
    }

    /** int()/float() also parse bytes and bytearray (as ASCII text). */
    private static Object bytesAsText(Object o) {
        if (o instanceof PyObj.Bytes) return new String(((PyObj.Bytes) o).data, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (o instanceof PyObj.ByteArray)
            return new String(((PyObj.ByteArray) o).data, 0, ((PyObj.ByteArray) o).size, java.nio.charset.StandardCharsets.ISO_8859_1);
        return o;
    }

    private static Object constructFloat(Vm vm, Object[] a) {
        if (a.length == 0) return 0.0;
        Object o = a[0];
        if (vm != null) {
            Object r = vm.hooks.callDunder(o, "__float__");
            if (r != null) {
                Object rf = Ops.unwrapNative(r);
                if (rf instanceof Double) return rf;
                throw PyException.typeError("__float__ returned non-float (type " + StdLib.typeName(r) + ")");
            }
            r = vm.hooks.callDunder(o, "__index__");
            if (r != null) o = r;
        }
        o = bytesAsText(Ops.unwrapNative(o));
        if (o instanceof Double) return o;
        if (o instanceof Long || o instanceof BigInteger || o instanceof Boolean) return Ops.intToDouble(o);
        if (o instanceof String) return parseFloat((String) o);
        throw PyException.typeError("can't convert " + StdLib.typeName(a[0]) + " to float");
    }

    /** A Python float literal: Java's parseDouble alone would also take hex
     *  floats and 'd'/'f' suffixes. */
    private static final java.util.regex.Pattern FLOAT_LITERAL =
            java.util.regex.Pattern.compile("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    private static double parseFloat(String text) {
        String s = text.trim();
        boolean signed = s.startsWith("+") || s.startsWith("-");
        String word = (signed ? s.substring(1) : s).toLowerCase(java.util.Locale.ROOT);
        if (word.equals("inf") || word.equals("infinity")) return s.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (word.equals("nan")) return Double.NaN;
        if (!FLOAT_LITERAL.matcher(s).matches()) throw PyException.valueError("invalid syntax for number");
        return Double.parseDouble(s);
    }

    private static Object constructComplex(Object[] a) {
        if (a.length == 0) return new PyObj.Complex(0, 0);
        if (a[0] == MISSING) a = new Object[]{0L, a[1]};   // complex(imag=2)
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
