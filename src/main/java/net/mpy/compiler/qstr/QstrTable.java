package net.mpy.compiler.qstr;

import net.mpy.compiler.io.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-.mpy qstr table.
 *
 * An .mpy file carries a single global qstr table; bytecode references qstrs by
 * their LOCAL index into this table (0-based, in the order they were interned),
 * NOT by any global id. Verified against sample.mpy: {@code STORE_NAME add} is
 * {@code 16 03} where 03 is the local index of "add".
 *
 * Each entry is serialized (save_qstr) as one of:
 *   - static reference:  vuint((id << 1) | 1)          when the name is a static
 *                                                       qstr (id <= LAST_STATIC)
 *   - inline string:     vuint(len << 1) + bytes + NUL  otherwise
 *
 * The loader interns inline strings into its runtime pool and resolves static
 * references by id, producing the local-index -> runtime-qstr mapping. This is
 * exactly the "emit by name, map at load time" strategy chosen in Step 1: we
 * never need to know a non-static qstr's runtime id.
 *
 * IMPORTANT ordering note: to be byte-identical to mpy-cross, local indices must
 * be assigned in the same order the C compiler first encounters each qstr. That
 * ordering is driven by the compiler (Steps 8/9); this class only guarantees
 * stable insertion-order indices and correct per-entry serialization.
 */
public final class QstrTable {

    /** One qstr slot: either a static-id reference or an inline string. */
    private static final class Entry {
        final String name;
        final int staticId; // >0 if static reference, -1 if inline
        Entry(String name, int staticId) {
            this.name = name;
            this.staticId = staticId;
        }
        boolean isStatic() { return staticId > 0; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>(); // name -> local index

    /**
     * Intern a qstr by name, returning its local index (0-based). Repeated
     * interning of the same name returns the same index.
     */
    public int intern(String name) {
        Integer existing = index.get(name);
        if (existing != null) {
            return existing;
        }
        int id = StaticQstrs.idOf(name);
        int localIndex = entries.size();
        entries.add(new Entry(name, id));
        index.put(name, localIndex);
        return localIndex;
    }

    /** Number of entries (== the count written before the table). */
    public int size() { return entries.size(); }

    /** Name at a given local index (for debugging / disassembly parity). */
    public String nameAt(int localIndex) { return entries.get(localIndex).name; }

    /**
     * Serialize the qstr table body (the entries only, WITHOUT the leading count
     * — the count is written by the container serializer in Step 11, since it is
     * interleaved with the obj-table count in the header layout).
     */
    public void serializeEntries(ByteBuf out) {
        for (Entry e : entries) {
            if (e.isStatic()) {
                out.vuint(((long) e.staticId << 1) | 1);
            } else {
                byte[] b = net.mpy.compiler.io.Utf8.encode(e.name);
                out.vuint((long) b.length << 1); // low bit 0 => inline string
                out.bytes(b);
                out.u8(0);                        // NUL terminator
            }
        }
    }
}
