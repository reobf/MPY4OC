package net.mpy.vm;

import net.mpy.qstr.SymbolMap;
import net.mpy.qstr.SymbolPool;

/** An instance of a user-defined {@link PyClass}: its class plus attribute dict. */
public final class PyInstance {
    /** Assigned at construction, or filled once by snapshot restore after registering
     *  the instance identity. A class's method namespace may refer back to it. */
    public PyClass cls;
    public final SymbolMap attrs;
    /** For subclasses of a built-in type (str/list/...): the wrapped native value
     *  (a String, PyObj.PyList, ...). null for ordinary classes. */
    public Object nativeValue;
    public PyInstance(PyClass cls) { this(cls, cls == null ? new SymbolPool() : cls.ns.symbols()); }
    public PyInstance(PyClass cls, SymbolPool symbols) {
        this.cls = cls;
        this.attrs = new SymbolMap(symbols);
    }
    @Override public String toString() { return "<" + cls.name + " object>"; }
}
