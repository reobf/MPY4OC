package net.mpy.vm;

import net.mpy.loader.MpyModule;

import java.util.LinkedHashMap;
import java.util.Map;

/** A loaded Python module: dotted name, the import root it lives under, its
 *  namespace, and the code it came from ({@code null} for a synthetic package -
 *  a directory imported without an __init__ file, which this VM permits). */
public final class PyModule {
    public final String name;    // dotted name, e.g. "game.ai.brain"
    public final String root;    // the import root it was resolved under
    public final MpyModule code; // null for synthetic (no-__init__) packages
    public final Map<String, Object> ns = new LinkedHashMap<>();

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
        this.name = name;
        this.root = root;
        this.code = code;
        ns.put("__name__", name);
        if (code != null) code.ns = ns;   // frames from this module scope here
    }

    @Override public String toString() { return "<module '" + name + "'>"; }
}
