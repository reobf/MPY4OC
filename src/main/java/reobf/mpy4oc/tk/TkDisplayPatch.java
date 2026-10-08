package reobf.mpy4oc.tk;

import java.util.*;

/** Stable widget/part keys permit row changes without resending other text. */
public final class TkDisplayPatch {
    public final long[] order;
    public final TkDisplayList changed;
    public TkDisplayPatch(long[] order, TkDisplayList changed) {
        if (order.length > TkDisplayList.MAX_RECORDS) throw new IllegalArgumentException("too many paint keys");
        Set<Long> keys = new HashSet<>();
        for (long key : order) if (key >>> 32 < 1 || key >>> 32 > Integer.MAX_VALUE || (key & 0xFFFFFFFFL) > Integer.MAX_VALUE || !keys.add(key))
            throw new IllegalArgumentException("invalid/duplicate paint key");
        for (TkDisplayList.Item item : changed.items) if (!keys.contains(item.key())) throw new IllegalArgumentException("unreferenced paint record");
        this.order = order.clone(); this.changed = changed;
    }
    public static TkDisplayPatch between(TkDisplayList before, TkDisplayList after) {
        Map<Long, TkDisplayList.Item> old = new HashMap<>(); for (TkDisplayList.Item item : before.items) old.put(item.key(), item);
        long[] order = new long[after.items.size()]; List<TkDisplayList.Item> changes = new ArrayList<>();
        for (int i = 0; i < order.length; i++) { TkDisplayList.Item item = after.items.get(i); order[i] = item.key(); if (!item.equals(old.get(item.key()))) changes.add(item); }
        return new TkDisplayPatch(order, new TkDisplayList(changes));
    }
    public TkDisplayList apply(TkDisplayList before) {
        Map<Long, TkDisplayList.Item> items = new HashMap<>(); for (TkDisplayList.Item item : before.items) items.put(item.key(), item);
        for (TkDisplayList.Item item : changed.items) items.put(item.key(), item);
        List<TkDisplayList.Item> result = new ArrayList<>();
        for (long key : order) { TkDisplayList.Item item = items.get(key); if (item == null) throw new IllegalArgumentException("missing paint record"); result.add(item); }
        return new TkDisplayList(result);
    }
}
