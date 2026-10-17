package net.mpy.vm;

import net.mpy.loader.MpyModule;

import net.mpy.qstr.SymbolMap;
import net.mpy.qstr.SymbolPool;
import java.util.Map;

/** A loaded Python module: dotted name, the import root it lives under, its
 *  namespace, and the code it came from ({@code null} for a synthetic package -
 *  a directory imported without an __init__ file, which this VM permits). */
public final class PyModule {
    public final String name;    // dotted name, e.g. "game.ai.brain"
    public final String root;    // the import root it was resolved under
    public final MpyModule code; // null for synthetic (no-__init__) packages
    public final SymbolMap ns;

    // sys.argv is a real list in each virtual program. The contexts themselves live
    // in Python state and are snapshotted; the VM rebinds this lookup after restore.
    transient java.util.function.Supplier<Object> program;

    @SuppressWarnings("unchecked")
    private static boolean contextAttribute(String attribute) {
        return attribute.equals("argv") || attribute.equals("stdin") || attribute.equals("stdout") || attribute.equals("stderr") || attribute.equals("_print");
    }

    private Map<Object, Object> contextual(String attribute) {
        if (!name.equals("sys") || !contextAttribute(attribute) || program == null) return null;
        Object contexts = ns.get("_contexts");
        if (!(contexts instanceof net.mpy.runtime.PyObj.PyDict)) return null;
        Object context = ((net.mpy.runtime.PyObj.PyDict) contexts).map.get(program.get());
        return context instanceof net.mpy.runtime.PyObj.PyDict ? ((net.mpy.runtime.PyObj.PyDict) context).map : null;
    }

    public Object getAttr(String attribute) {
        Map<Object, Object> context = contextual(attribute);
        return context == null ? ns.get(attribute) : context.get(attribute);
    }

    public Object getAttr(SymbolPool symbols, int attribute) {
        Map<Object, Object> context = contextual(symbols.name(attribute));
        return context == null ? ns.getSymbol(symbols, attribute) : context.get(symbols.name(attribute));
    }

    public void setAttr(SymbolPool symbols, int attribute, Object value) {
        if (name.equals("sys") && contextAttribute(symbols.name(attribute))) {
            setAttr(symbols.name(attribute), value);
        } else if (value == null) {
            if (ns.removeSymbol(symbols, attribute) == null)
                throw new net.mpy.runtime.PyException("AttributeError", "no attribute '" + symbols.name(attribute) + "'");
        } else ns.putSymbol(symbols, attribute, value);
    }

    public void setAttr(String attribute, Object value) {
        Map<Object, Object> context = contextual(attribute);
        if (value == null) {
            Object previous = context == null ? ns.remove(attribute) : context.remove(attribute);
            if (previous == null) throw new net.mpy.runtime.PyException("AttributeError", "no attribute '" + attribute + "'");
        } else if (context == null) ns.put(attribute, value);
        else context.put(attribute, value);
    }

    /** True while this module's top-level code is still executing (cached-before-
     *  executing window). Another coroutine importing it then must wait until
     *  initialization completes -- except a circular import within the SAME frame
     *  chain, which proceeds and sees the partial module (standard Python
     *  semantics). Serialized: a snapshot taken mid-initialization restores with
     *  the flag intact, so waiters keep waiting after a load. */
    public boolean initializing;

    /** Optional lazy-iteration hook. When set (e.g. on a wrapped OC Value that is
     *  iterable), `for x in module` calls this factory to get a fresh element
     *  producer, then pulls one value at a time -- so iterating a huge collection
     *  converts one element to Python at a time rather than materialising all of
     *  them, and each loop starts a clean iteration. Not serialised (a live
     *  iteration is materialised into a plain iterator on snapshot). */
    public transient java.util.function.Supplier<java.util.function.Supplier<Object>> iterFactory;

    public PyModule(String name, String root, MpyModule code) {
        this(name, root, code, new SymbolPool());
    }

    public PyModule(String name, String root, MpyModule code, SymbolPool symbols) {
        ns = new SymbolMap(symbols);
        symbols.intern(name);
        this.name = name;
        this.root = root;
        this.code = code;
        ns.put("__name__", name);
        if (code != null) code.ns = ns;   // frames from this module scope here
    }

    @Override public String toString() { return "<module '" + name + "'>"; }
}
