package reobf.mpy4oc.tk;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

final class TkGeometryPersistence {
    private TkGeometryPersistence() {}
    private static NBTTagCompound options(Map<String, Object> values) {
        NBTTagCompound n = new NBTTagCompound();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() instanceof Number) n.setDouble(e.getKey(), ((Number) e.getValue()).doubleValue());
            else n.setString(e.getKey(), (String) e.getValue());
        }
        return n;
    }
    private static Map<String, Object> options(NBTTagCompound n) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Object raw : n.func_150296_c()) {
            String key = (String) raw;
            if (n.hasKey(key, 99)) values.put(key, n.getDouble(key));
            else if (n.hasKey(key, 8)) values.put(key, n.getString(key));
            else throw new IllegalArgumentException("invalid geometry value");
        }
        return values;
    }
    static NBTTagCompound placeTag(TkPlace place) { return options(TkGeometry.placeInfo(place)); }
    static TkPlace placeFromTag(NBTTagCompound n) { return TkGeometry.place(options(n), null); }

    static void save(TkWidget w, NBTTagCompound n) {
        if (w.savedGrid != null) n.setTag("gridOptions", options(TkGeometry.gridInfo(w.savedGrid)));
        n.setBoolean("gridded", w.grid != null);
        if (w.place != null) n.setTag("placeOptions", options(TkGeometry.placeInfo(w.place)));
        if (w instanceof Frame) {
            Frame f = (Frame) w; n.setBoolean("gridPropagate", f.gridPropagate); n.setString("gridAnchor", f.gridAnchor.name());
            n.setIntArray("gridOrder", f.gridded.stream().mapToInt(child -> child.id).toArray());
            n.setTag("gridRows", slots(f.rows)); n.setTag("gridColumns", slots(f.columns));
        }
    }
    private static NBTTagList slots(Map<Integer, TkGrid.Slot> slots) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Integer, TkGrid.Slot> e : slots.entrySet()) {
            NBTTagCompound n = new NBTTagCompound(); n.setInteger("index", e.getKey());
            n.setInteger("weight", e.getValue().weight); n.setInteger("minsize", e.getValue().minsize); n.setInteger("pad", e.getValue().pad); list.appendTag(n);
        }
        return list;
    }
    static void load(TkWidget w, NBTTagCompound n) {
        if (n.hasKey("gridOptions", 10)) w.savedGrid = TkGeometry.grid(w, options(n.getCompoundTag("gridOptions")));
        if (n.getBoolean("gridded")) {
            if (w.savedGrid == null) throw new IllegalArgumentException("missing grid options");
            w.grid = w.savedGrid;
        }
        if (n.hasKey("placeOptions", 10)) w.place = TkGeometry.place(options(n.getCompoundTag("placeOptions")), null);
        int managers = (w.pack == null ? 0 : 1) + (w.grid == null ? 0 : 1) + (w.place == null ? 0 : 1);
        if (managers > 1 || w.parent == null && (managers != 0 || w.savedGrid != null)) throw new IllegalArgumentException("invalid geometry managers");
        if (w instanceof Frame) {
            Frame f = (Frame) w;
            f.gridPropagate = !n.hasKey("gridPropagate") || n.getBoolean("gridPropagate");
            f.gridAnchor = n.hasKey("gridAnchor") ? TkOptions.anchor(n.getString("gridAnchor")) : TkPack.Anchor.NW;
            slots(f.rows, n.getTagList("gridRows", 10)); slots(f.columns, n.getTagList("gridColumns", 10));
        }
    }
    private static void slots(Map<Integer, TkGrid.Slot> slots, NBTTagList list) {
        if (list.tagCount() > TkGrid.MAX_SLOTS) throw new IllegalArgumentException("too many grid slots");
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i); int index = TkOptions.integer(n.getInteger("index"), 0, 127);
            if (slots.put(index, new TkGrid.Slot(n.getInteger("weight"), n.getInteger("minsize"), n.getInteger("pad"))) != null)
                throw new IllegalArgumentException("duplicate grid slot");
        }
    }
    static void order(Frame f, NBTTagCompound n) {
        for (int id : n.getIntArray("gridOrder")) {
            TkWidget w = f.tree.get(id);
            if (w == null || w.parent != f || w.grid == null || f.gridded.contains(w)) throw new IllegalArgumentException("invalid grid order");
            f.gridded.add(w);
        }
        for (TkWidget w : f.children) if ((w.grid != null) != f.gridded.contains(w)) throw new IllegalArgumentException("missing gridded child");
        if (!f.gridded.isEmpty() && !f.packed.isEmpty()) throw new IllegalArgumentException("mixed pack and grid");
    }
}
