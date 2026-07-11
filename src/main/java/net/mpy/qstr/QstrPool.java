package net.mpy.qstr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 0.2 - a global qstr interning pool.
 *
 * MicroPython interns short strings ("qstrs") to small integer ids so that name
 * lookups (LOAD_GLOBAL, LOAD_ATTR, ...) compare ids instead of characters. This
 * pool mirrors that: it seeds the fixed {@link StaticQstrs} at their canonical
 * ids (global qstr 0 == null sentinel, 1..COUNT == static list) and interns any
 * further strings after them.
 *
 * A loaded module keeps its own resolved {@code String[]} qstr table (indices
 * used by its bytecode); this pool is the shared identity map the VM will use in
 * later phases so equal names collapse to one id.
 */
public final class QstrPool {

    private final List<String> byId = new ArrayList<>();
    private final Map<String, Integer> idByStr = new HashMap<>();

    public QstrPool() {
        // Global qstr 0 is MP_QSTRnull - reserved, never referenced.
        byId.add(null);
        // Global qstrs 1..COUNT are the static qstrs.
        for (String s : StaticQstrs.STATIC) {
            idByStr.put(s, byId.size());
            byId.add(s);
        }
    }

    /** Intern a string, returning its stable global qstr id. */
    public int intern(String s) {
        Integer id = idByStr.get(s);
        if (id != null) return id;
        int newId = byId.size();
        byId.add(s);
        idByStr.put(s, newId);
        return newId;
    }

    /** Resolve a global qstr id to its string ({@code null} for id 0). */
    public String str(int id) {
        return (id >= 0 && id < byId.size()) ? byId.get(id) : null;
    }

    /** Look up an existing id, or -1 if the string was never interned. */
    public int find(String s) {
        Integer id = idByStr.get(s);
        return id == null ? -1 : id;
    }

    /** Total number of interned qstrs (including the null sentinel and statics). */
    public int size() { return byId.size(); }
}
