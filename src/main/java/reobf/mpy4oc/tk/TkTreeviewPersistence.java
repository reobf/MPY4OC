package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** A Treeview's items, columns and view in NBT (optional keys of the widget's node). */
final class TkTreeviewPersistence {
    private TkTreeviewPersistence() {}

    private static NBTTagList strings(List<String> values) {
        NBTTagList list = new NBTTagList();
        for (String v : values) list.appendTag(new NBTTagString(v));
        return list;
    }
    private static List<String> strings(NBTTagCompound n, String key) {
        NBTTagList list = n.getTagList(key, 8);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) out.add(list.getStringTagAt(i));
        return out;
    }

    static void save(Treeview t, NBTTagCompound n) {
        NBTTagList items = new NBTTagList();
        for (Treeview.Item item : t.everything()) {
            NBTTagCompound i = new NBTTagCompound();
            i.setString("id", item.id);
            // A detached subtree's top has no parent.
            if (item.parent != null) i.setString("parent", item.parent.id);
            i.setString("text", item.text); i.setString("image", item.image);
            i.setTag("values", strings(item.values)); i.setTag("tags", strings(item.tags));
            i.setBoolean("open", item.open);
            items.appendTag(i);
        }
        n.setTag("treeItems", items);
        NBTTagList columns = new NBTTagList();
        List<Treeview.Column> all = new ArrayList<>();
        all.add(t.treeColumn); all.addAll(t.columns);
        for (Treeview.Column c : all) {
            NBTTagCompound col = new NBTTagCompound();
            col.setString("id", c.id); col.setInteger("width", c.width); col.setInteger("minwidth", c.minWidth); col.setBoolean("stretch", c.stretch);
            col.setString("anchor", c.anchor); col.setString("heading", c.heading); col.setString("headingAnchor", c.headingAnchor);
            col.setString("headingImage", c.headingImage); col.setBoolean("command", c.command);
            columns.appendTag(col);
        }
        n.setTag("treeColumns", columns);
        if (t.display != null) { List<String> ids = new ArrayList<>(); for (Treeview.Column c : t.display) ids.add(c.id); n.setTag("treeDisplay", strings(ids)); }
        n.setInteger("treeLines", t.lines); n.setString("selectmode", t.selectmode);
        n.setBoolean("showTree", t.showTree); n.setBoolean("showHeadings", t.showHeadings);
        List<String> selected = new ArrayList<>(); for (Treeview.Item i : t.selected) selected.add(i.id);
        n.setTag("treeSelected", strings(selected));
        if (t.focus != null) n.setString("treeFocus", t.focus.id);
        n.setInteger("treeTop", t.top); n.setInteger("treeScrollX", t.scrollX); n.setInteger("treeSlack", t.slack); n.setInteger("treeSerial", t.serial);
        n.setInteger("xscrollcommand", t.xscrollcommand); n.setInteger("yscrollcommand", t.yscrollcommand); n.setInteger("treeNotify", t.notify);
        NBTTagList tags = new NBTTagList();
        for (Map.Entry<String, int[]> e : t.tagColors.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("tag", e.getKey()); tag.setInteger("fg", e.getValue()[0]); tag.setInteger("bg", e.getValue()[1]);
            tags.appendTag(tag);
        }
        n.setTag("treeTags", tags);
    }

    static void load(Treeview t, NBTTagCompound n) {
        NBTTagList columns = n.getTagList("treeColumns", 10);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i < columns.tagCount(); i++) ids.add(columns.getCompoundTagAt(i).getString("id"));
        t.columns(TkControlOptions.values(ids.toArray()));
        for (int i = 0; i < columns.tagCount(); i++) {
            NBTTagCompound col = columns.getCompoundTagAt(i);
            Treeview.Column c = i == 0 ? t.treeColumn : t.columns.get(i - 1);
            Map<String, Object> m = new HashMap<>();
            m.put("width", col.getInteger("width")); m.put("minwidth", col.getInteger("minwidth")); m.put("stretch", col.getBoolean("stretch"));
            m.put("anchor", col.getString("anchor"));
            t.configureColumn(c, m);
            c.width = TkOptions.integer(col.getInteger("width"), 0, 8192);   // as it was, below minwidth too (slack)
            Map<String, Object> h = new HashMap<>();
            h.put("text", col.getString("heading")); h.put("anchor", col.getString("headingAnchor")); h.put("image", col.getString("headingImage"));
            h.put("command", col.getBoolean("command"));
            t.configureHeading(c, h);
        }
        if (n.hasKey("treeDisplay", 9)) t.displayColumns(strings(n, "treeDisplay"));
        NBTTagList items = n.getTagList("treeItems", 10);
        if (items.tagCount() > Treeview.MAX_ITEMS) throw new IllegalArgumentException("too many saved Treeview items");
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound it = items.getCompoundTagAt(i);
            Map<String, Object> m = new HashMap<>();
            m.put("text", it.getString("text")); m.put("image", it.getString("image"));
            m.put("values", strings(it, "values").toArray()); m.put("tags", strings(it, "tags").toArray()); m.put("open", it.getBoolean("open"));
            Treeview.Item parent = it.hasKey("parent", 8) ? t.item(it.getString("parent")) : null;
            t.insert(parent != null ? parent : t.root, Integer.MAX_VALUE, it.getString("id"), m);
            if (parent == null) {   // the top of a detached subtree
                Treeview.Item item = t.item(it.getString("id"));
                t.detach(java.util.Collections.singletonList(item));
            }
        }
        t.lines = TkOptions.integer(n.getInteger("treeLines"), 0, 4096);
        Map<String, Object> m = new HashMap<>();
        m.put("selectmode", n.getString("selectmode"));
        List<Object> show = new ArrayList<>();
        if (n.getBoolean("showTree")) show.add("tree");
        if (n.getBoolean("showHeadings")) show.add("headings");
        m.put("show", show.toArray());
        m.put("xscrollcommand", n.getInteger("xscrollcommand")); m.put("yscrollcommand", n.getInteger("yscrollcommand")); m.put("notify", n.getInteger("treeNotify"));
        TkControlOptions.prepare(t, m).run();
        for (String id : strings(n, "treeSelected")) t.selected.add(t.item(id));
        if (n.hasKey("treeFocus", 8)) t.focus = t.item(n.getString("treeFocus"));
        t.top = Math.max(0, n.getInteger("treeTop")); t.scrollX = Math.max(0, Math.min(8192 * Treeview.MAX_COLUMNS, n.getInteger("treeScrollX")));
        t.slack = n.getInteger("treeSlack"); t.serial = Math.max(0, n.getInteger("treeSerial"));
        NBTTagList tags = n.getTagList("treeTags", 10);
        for (int i = 0; i < tags.tagCount() && i < 4096; i++) {
            NBTTagCompound tag = tags.getCompoundTagAt(i);
            t.tagColors.put(tag.getString("tag"), new int[] {
                    TkOptions.integer(tag.getInteger("fg"), -1, 0xFFFFFF), TkOptions.integer(tag.getInteger("bg"), -1, 0xFFFFFF)});
        }
    }
}
