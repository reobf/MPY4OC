package net.mpy.qstr;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Insertion-ordered attribute storage. The String Map facade keeps host APIs,
 * namespace views and the existing snapshot format live; bytecodes use integer IDs. */
public final class SymbolMap extends AbstractMap<String, Object> {
    private static final class Storage extends Int2ObjectLinkedOpenHashMap<Object> {
        Storage(int expected) { super(expected); }
        long estimatedBytes() { return 80L + 20L * (n + 1); }
    }

    // Both the pool and the storage are allocated on first use: most maps (an
    // exception's attributes, a function's attributes, a detached host object's
    // namespace bound to a VM before anything is stored) stay empty, and an empty
    // map must cost two fields, not a pool with three hash tables.
    private SymbolPool symbols;
    private Storage entries;

    /** Detached host objects can be populated before being attached to a VM. */
    public SymbolMap() {}
    public SymbolMap(SymbolPool symbols) { this.symbols = symbols; }
    public SymbolPool symbols() {
        if (symbols == null) symbols = new SymbolPool();
        return symbols;
    }

    /** The pool this map is bound to, or null while it is detached and empty: for
     *  bookkeeping that must not allocate one (memory accounting). */
    public SymbolPool symbolsOrNull() { return symbols; }

    private Storage storage() {
        if (entries == null) entries = new Storage(2);
        return entries;
    }

    /** Re-index a detached host namespace without changing its identity or order. */
    public SymbolMap bind(SymbolPool target) {
        if (symbols == target) return this;
        if (symbols != null && entries != null && !entries.isEmpty()) {
            Storage replacement = new Storage(entries.size());
            for (var entry : entries.int2ObjectEntrySet())
                replacement.put(target.intern(symbols.name(entry.getIntKey())), entry.getValue());
            entries = replacement;
        }
        symbols = target;
        return this;
    }

    public Object getSymbol(SymbolPool pool, int id) { return bind(pool).entries == null ? null : entries.get(id); }
    public boolean containsSymbol(SymbolPool pool, int id) { return bind(pool).entries != null && entries.containsKey(id); }
    public Object putSymbol(SymbolPool pool, int id, Object value) { return bind(pool).storage().put(id, value); }
    public Object removeSymbol(SymbolPool pool, int id) { return bind(pool).entries == null ? null : entries.remove(id); }
    public long estimatedBytes() { return 32 + (entries == null ? 0 : entries.estimatedBytes()); }

    @Override public int size() { return entries == null ? 0 : entries.size(); }
    @Override public void clear() { if (entries != null) entries.clear(); }
    @Override public boolean containsKey(Object key) {
        return key instanceof String && entries != null && symbols != null && entries.containsKey(symbols.find((String) key));
    }
    @Override public Object get(Object key) {
        return key instanceof String && entries != null && symbols != null ? entries.get(symbols.find((String) key)) : null;
    }
    @Override public Object put(String key, Object value) { return storage().put(symbols().intern(key), value); }
    @Override public Object remove(Object key) {
        return key instanceof String && entries != null && symbols != null ? entries.remove(symbols.find((String) key)) : null;
    }
    @Override public Collection<Object> values() {
        return new AbstractCollection<Object>() {
            @Override public int size() { return SymbolMap.this.size(); }
            @Override public void clear() { SymbolMap.this.clear(); }
            @Override public Iterator<Object> iterator() {
                return entries == null ? Collections.emptyIterator() : entries.values().iterator();
            }
        };
    }

    @Override public Set<Map.Entry<String, Object>> entrySet() {
        return new AbstractSet<Map.Entry<String, Object>>() {
            @Override public int size() { return SymbolMap.this.size(); }
            @Override public void clear() { SymbolMap.this.clear(); }
            @Override public Iterator<Map.Entry<String, Object>> iterator() {
                if (entries == null) return Collections.emptyIterator();
                var iterator = entries.int2ObjectEntrySet().iterator();
                SymbolPool pool = symbols;
                return new Iterator<Map.Entry<String, Object>>() {
                    public boolean hasNext() { return iterator.hasNext(); }
                    public Map.Entry<String, Object> next() {
                        var entry = iterator.next();
                        return new SimpleEntry<String, Object>(pool.name(entry.getIntKey()), entry.getValue()) {
                            @Override public Object setValue(Object value) {
                                Object old = SymbolMap.this.put(getKey(), value);
                                super.setValue(value);
                                return old;
                            }
                        };
                    }
                    public void remove() { iterator.remove(); }
                };
            }
        };
    }
}
