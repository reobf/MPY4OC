package net.mpy.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python exception values (Phase 6): exception <em>types</em> (callable, matchable
 * in {@code except} clauses) and exception <em>instances</em> (what is raised and
 * caught). The hierarchy and str/repr conventions follow MicroPython:
 * {@code repr(ValueError('x'))} is {@code ValueError('x',)} (the args tuple), and
 * {@code str(e)} is "" for no args, {@code str(arg)} for one, else the args tuple.
 */
public final class PyExc {

    private PyExc() {}

    /** An exception type (e.g. ValueError). Identity-comparable; call to instantiate. */
    public static final class Type {
        public final String name;
        public final Type base; // null only for BaseException
        /** For a user-defined exception subclass (class MyError(Exception)): the
         *  backing PyClass holding methods/__init__; null for built-in types. */
        public Object userClass;   // net.mpy.vm.PyClass, kept as Object to avoid a package cycle
        Type(String name, Type base) { this.name = name; this.base = base; }
        public Type(String name, Type base, Object userClass) { this.name = name; this.base = base; this.userClass = userClass; }
        public Instance make(Object[] args) { return new Instance(this, args); }
        @Override public String toString() { return "<class '" + name + "'>"; }
    }

    /** An exception instance: a type plus its args tuple. */
    public static final class Instance {
        public final Type type;
        public Object[] args;
        /** Custom instance attributes for user exception subclasses (self.code etc). */
        public final Map<String, Object> userAttrs = new LinkedHashMap<>();
        /** Traceback entries {file, line, blockName}, appended as the exception
         *  unwinds through frames (MicroPython's add_traceback order). */
        public final java.util.List<Object[]> traceback = new java.util.ArrayList<>();
        public Instance(Type type, Object[] args) { this.type = type; this.args = args == null ? NO_ARGS : args; }
        @Override public String toString() { return repr(this); }
    }

    /** MicroPython-format traceback text (including the final "Type: msg" line). */
    public static String formatTraceback(Instance exc) {
        StringBuilder sb = new StringBuilder();
        if (!exc.traceback.isEmpty()) {
            sb.append("Traceback (most recent call last):\n");
            for (int i = exc.traceback.size() - 1; i >= 0; i--) {   // outermost first
                Object[] e = exc.traceback.get(i);
                sb.append("  File \"").append(e[0]).append("\", line ").append(e[1])
                  .append(", in ").append(e[2]).append('\n');
            }
        }
        String s = str(exc);
        sb.append(exc.type.name);
        if (!s.isEmpty()) sb.append(": ").append(s);
        return sb.toString();
    }

    private static final Object[] NO_ARGS = new Object[0];

    /** All known exception types by name (LOAD_GLOBAL / snapshot resolution). */
    public static final Map<String, Type> TYPES = new LinkedHashMap<>();

    private static Type def(String name, Type base) {
        Type t = new Type(name, base);
        TYPES.put(name, t);
        return t;
    }

    public static final Type BASE_EXCEPTION = def("BaseException", null);
    public static final Type EXCEPTION = def("Exception", BASE_EXCEPTION);
    public static final Type ARITHMETIC_ERROR = def("ArithmeticError", EXCEPTION);
    public static final Type ZERO_DIVISION_ERROR = def("ZeroDivisionError", ARITHMETIC_ERROR);
    public static final Type OVERFLOW_ERROR = def("OverflowError", ARITHMETIC_ERROR);
    public static final Type LOOKUP_ERROR = def("LookupError", EXCEPTION);
    public static final Type INDEX_ERROR = def("IndexError", LOOKUP_ERROR);
    public static final Type KEY_ERROR = def("KeyError", LOOKUP_ERROR);
    public static final Type VALUE_ERROR = def("ValueError", EXCEPTION);
    public static final Type TYPE_ERROR = def("TypeError", EXCEPTION);
    public static final Type RUNTIME_ERROR = def("RuntimeError", EXCEPTION);
    public static final Type NOT_IMPLEMENTED_ERROR = def("NotImplementedError", RUNTIME_ERROR);
    public static final Type NAME_ERROR = def("NameError", EXCEPTION);
    public static final Type ATTRIBUTE_ERROR = def("AttributeError", EXCEPTION);
    public static final Type STOP_ITERATION = def("StopIteration", EXCEPTION);
    public static final Type ASSERTION_ERROR = def("AssertionError", EXCEPTION);
    public static final Type OS_ERROR = def("OSError", EXCEPTION);
    public static final Type IMPORT_ERROR = def("ImportError", EXCEPTION);
    public static final Type MEMORY_ERROR = def("MemoryError", EXCEPTION);
    public static final Type SYNTAX_ERROR = def("SyntaxError", EXCEPTION);
    public static final Type INDENTATION_ERROR = def("IndentationError", SYNTAX_ERROR);
    public static final Type UNICODE_ERROR = def("UnicodeError", VALUE_ERROR);
    public static final Type KEYBOARD_INTERRUPT = def("KeyboardInterrupt", BASE_EXCEPTION);
    public static final Type SYSTEM_EXIT = def("SystemExit", BASE_EXCEPTION);
    public static final Type STOP_ASYNC_ITERATION = def("StopAsyncIteration", EXCEPTION);

    /** All registered exception types (for exposing them as builtins). */
    public static java.util.Collection<Type> ALL_TYPES() { return TYPES.values(); }

    /** isinstance(instance.type, of) via the base chain. */
    public static boolean isSub(Type t, Type of) {
        for (Type x = t; x != null; x = x.base) if (x == of) return true;
        return false;
    }

    /** The {@code except X} match: {@code typeSpec} is a Type or a tuple of Types. */
    public static boolean matches(Object exc, Object typeSpec) {
        if (!(exc instanceof Instance)) return false;
        Type et = ((Instance) exc).type;
        if (typeSpec instanceof Type) return isSub(et, (Type) typeSpec);
        if (typeSpec instanceof PyObj.Tuple) {
            for (Object t : ((PyObj.Tuple) typeSpec).items) {
                if (t instanceof Type && isSub(et, (Type) t)) return true;
            }
            return false;
        }
        throw PyException.typeError("exception must derive from BaseException");
    }

    /** Convert a Java-side PyException (raised by Ops/Methods/the VM) to an instance. */
    public static Instance from(PyException e) {
        Type t = TYPES.get(e.pyType);
        if (t == null) t = RUNTIME_ERROR;
        return new Instance(t, e.args);
    }

    /** MicroPython str(): "" for 0 args, str(arg) for 1, the args tuple for more. */
    public static String str(Instance e) {
        if (e.args.length == 0) return "";
        if (e.args.length == 1) {
            Object a = e.args[0];
            return a instanceof String ? (String) a : PyObj.repr(a);
        }
        return PyObj.repr(new PyObj.Tuple(e.args));
    }

    /** MicroPython repr(): the type name followed by the args tuple (with its
     *  single-element trailing comma). */
    public static String repr(Instance e) {
        return e.type.name + PyObj.repr(new PyObj.Tuple(e.args));
    }
}
