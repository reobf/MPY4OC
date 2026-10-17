package net.mpy.qstr;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.mpy.loader.MpyModule;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Runtime names, local to one VM. Ordinary Python string values never enter this pool.
 * IDs are transient: snapshots store names and rebuild IDs in the receiving VM. */
public final class SymbolPool {
    private final Object2IntOpenHashMap<String> ids = new Object2IntOpenHashMap<>(2);
    private final List<String> names = new ArrayList<>();
    private WeakHashMap<MpyModule, int[]> tables;   // allocated by the first frame that resolves a name
    private long nameBytes;

    public SymbolPool() { names.add(null); } // zero means not resolved / not present

    public int intern(String name) {
        java.util.Objects.requireNonNull(name, "symbol name");
        int id = ids.getInt(name);
        if (id != 0) return id;
        id = names.size();
        ids.put(name, id);
        names.add(name);
        nameBytes += 40L + 2L * name.length();
        return id;
    }

    /** A failed lookup must not retain an arbitrary player-supplied string. */
    public int find(String name) { return ids.getInt(name); }
    public String name(int id) { return names.get(id); }
    public int size() { return names.size() - 1; }

    /** Only name-bearing bytecodes fill these slots; LOAD_CONST_STRING does not. */
    public int[] table(MpyModule module) {
        if (tables == null) tables = new WeakHashMap<>();
        return tables.computeIfAbsent(module, m -> new int[m.qstrTable.length]);
    }

    public int resolve(MpyModule module, int[] table, int local) {
        if (local < 0 || local >= table.length) return intern(module.qstr(local));
        int id = table[local];
        if (id == 0) table[local] = id = intern(module.qstr(local));
        return id;
    }

    /** Conservative live-size estimate, including retained names and lookup caches. */
    public long estimatedBytes() {
        long bytes = 96 + nameBytes + 48L * size();
        if (tables != null) for (int[] table : tables.values()) bytes += 48L + 4L * table.length;
        return bytes;
    }
}
