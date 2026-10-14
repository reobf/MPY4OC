package net.mpy.vm;

import java.util.LinkedHashMap;
import java.util.Map;

/** An instance of a user-defined {@link PyClass}: its class plus attribute dict. */
public final class PyInstance {
    /** Assigned at construction, or filled once by snapshot restore after registering
     *  the instance identity. A class's method namespace may refer back to it. */
    public PyClass cls;
    public final Map<String, Object> attrs = new LinkedHashMap<>();
    /** For subclasses of a built-in type (str/list/...): the wrapped native value
     *  (a String, PyObj.PyList, ...). null for ordinary classes. */
    public Object nativeValue;
    public PyInstance(PyClass cls) { this.cls = cls; }
    @Override public String toString() { return "<" + cls.name + " object>"; }
}
