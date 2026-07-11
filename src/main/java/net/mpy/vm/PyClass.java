package net.mpy.vm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user-defined class (Phase 7+): name, bases (multiple inheritance supported),
 * and the namespace produced by executing the class body. Lookup follows
 * MicroPython's rule: the class's own dict, then each base depth-first
 * left-to-right (MicroPython does not implement C3 linearisation).
 */
public final class PyClass {
    public final String name;
    public final PyClass[] bases;
    public final Map<String, Object> ns = new LinkedHashMap<>();
    /** If this class (or an ancestor) inherits a built-in type, the native base
     *  (BuiltinType.STR / LIST / ...); instances carry a wrapped native value. */
    public BuiltinType nativeBase;
    /** If this class subclasses a built-in exception, the PyExc.Type identity used
     *  for raise/except matching; null for non-exception classes. */
    public net.mpy.runtime.PyExc.Type excType;

    public PyClass(String name, PyClass[] bases) {
        this.name = name;
        this.bases = bases == null ? new PyClass[0] : bases;
        // inherit a native base from any base class
        for (PyClass b : this.bases) if (b.nativeBase != null) { this.nativeBase = b.nativeBase; break; }
    }

    /** The native base for this class, walking the chain (own field already
     *  inherits from bases at construction). */
    public BuiltinType nativeBase() { return nativeBase; }

    /** Look up a name: own dict, then bases depth-first left-to-right. */
    public Object lookup(String attr) {
        Object v = ns.get(attr);
        if (v != null) return v;
        for (PyClass b : bases) {
            v = b.lookup(attr);
            if (v != null) return v;
        }
        return null;
    }

    /** Look up starting from the bases only (the super() rule). */
    public Object lookupInBases(String attr) {
        for (PyClass b : bases) {
            Object v = b.lookup(attr);
            if (v != null) return v;
        }
        return null;
    }

    @Override public String toString() { return "<class '" + name + "'>"; }
}
