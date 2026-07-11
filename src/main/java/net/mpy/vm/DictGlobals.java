package net.mpy.vm;

import net.mpy.runtime.PyObj;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adapts a Python dict to a String-keyed globals map, so exec(code, ns) reads and
 * writes name bindings directly in the caller-supplied namespace dict. Keys in the
 * dict are expected to be strings (Python identifiers); non-string keys are ignored
 * for name resolution.
 */
final class DictGlobals extends AbstractMap<String, Object> {
    private final PyObj.PyDict dict;

    DictGlobals(PyObj.PyDict dict) { this.dict = dict; }

    @Override public Object get(Object key) { return dict.map.get(key); }
    @Override public boolean containsKey(Object key) { return dict.map.containsKey(key); }
    @Override public Object put(String key, Object value) { return dict.map.put(key, value); }
    @Override public Object remove(Object key) { return dict.map.remove(key); }

    @Override public Set<Entry<String, Object>> entrySet() {
        LinkedHashSet<Entry<String, Object>> out = new LinkedHashSet<>();
        for (Map.Entry<Object, Object> e : dict.map.entrySet())
            if (e.getKey() instanceof String)
                out.add(new SimpleEntry<>((String) e.getKey(), e.getValue()));
        return out;
    }
}
