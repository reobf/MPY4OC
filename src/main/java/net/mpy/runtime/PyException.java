package net.mpy.runtime;

/**
 * A Python-level runtime error raised from Java code (Ops, Methods, the VM,
 * builtins). Carries the Python type name and the exception args, so the VM's
 * exception machinery (Phase 6) can convert it into a catchable
 * {@link PyExc.Instance}; if never caught it surfaces to the embedder as this
 * Java exception.
 */
public final class PyException extends RuntimeException {
    public final String pyType;
    public final Object[] args;
    /** The Python exception instance (with traceback), when one was materialised. */
    public PyExc.Instance instance;
    /** True while this exception is a fully-unwound root escape passing through
     *  the VM's own machinery (cleared at nested-execution boundaries). */
    public transient boolean escaped;

    /** MicroPython-format traceback (falls back to "Type: msg" with no frames). */
    public String pyTraceback() {
        if (instance != null) return PyExc.formatTraceback(instance);
        return getMessage();
    }

    public PyException(String pyType, String message) {
        this(pyType, message, new Object[]{message});
    }

    public PyException(String pyType, String display, Object[] args) {
        super(pyType + ": " + display);
        this.pyType = pyType;
        this.args = args;
    }

    public static PyException typeError(String msg)  { return new PyException("TypeError", msg); }
    public static PyException indexError(String msg) { return new PyException("IndexError", msg); }
    public static PyException valueError(String msg) { return new PyException("ValueError", msg); }
    public static PyException zeroDiv()              { return new PyException("ZeroDivisionError", "divide by zero"); }
    public static PyException nameError(String msg)  { return new PyException("NameError", msg); }
    public static PyException notImpl(String msg)    { return new PyException("NotImplementedError", msg); }
    public static PyException attributeError(String msg) { return new PyException("AttributeError", msg); }

    /** KeyError carries the missing key itself as the exception arg. */
    public static PyException keyError(Object key) {
        String display = key instanceof String ? (String) key : PyObj.repr(key);
        return new PyException("KeyError", display, new Object[]{key});
    }
}
