package net.mpy.vm;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A live view over the VM's combined globals map that hides builtin names, so
 * globals()/locals() expose only user-defined module names (as MicroPython does)
 * while writes still propagate to the real globals. Keys are Strings but the map
 * is typed Object,Object to plug into PyDict.
 */
final class FilteredGlobals extends AbstractMap<Object, Object> {
    private final Map<String, Object> backing;
    private final Set<String> hidden;

    FilteredGlobals(Map<String, Object> backing, Set<String> hidden) {
        this.backing = backing;
        this.hidden = hidden;
    }

    private boolean visible(Object k) {
        return k instanceof String && !hidden.contains(k);
    }

    @Override public Object get(Object key) { return visible(key) ? backing.get(key) : null; }
    @Override public boolean containsKey(Object key) { return visible(key) && backing.containsKey(key); }

    @Override public Object put(Object key, Object value) {
        if (!(key instanceof String)) throw new IllegalArgumentException("globals keys must be str");
        hidden.remove(key);              // a user assignment un-hides the name
        return backing.put((String) key, value);
    }

    @Override public Object remove(Object key) {
        return visible(key) ? backing.remove(key) : null;
    }

    @Override public Set<Entry<Object, Object>> entrySet() {
        LinkedHashSet<Entry<Object, Object>> out = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : backing.entrySet())
            if (!hidden.contains(e.getKey()))
                out.add(new SimpleEntry<>(e.getKey(), e.getValue()));
        return out;
    }
}
