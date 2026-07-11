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

    public PyModule(String name, String root, MpyModule code) {
        this.name = name;
        this.root = root;
        this.code = code;
        ns.put("__name__", name);
        if (code != null) code.ns = ns;   // frames from this module scope here
    }

    @Override public String toString() { return "<module '" + name + "'>"; }
}
