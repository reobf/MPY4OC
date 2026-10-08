package reobf.mpy4oc.tk;

import java.util.*;
import net.minecraft.nbt.*;

final class TkControlPersistence {
    private TkControlPersistence() {}
    static void save(TkWidget w, NBTTagCompound n) {
        if (w instanceof Checkbutton) {
            Checkbutton c = (Checkbutton) w; n.setInteger("group", c.group); n.setBoolean("selected", c.selected);
        } else if (w instanceof Listbox) {
            Listbox b = (Listbox) w;
            n.setInteger("columns", b.columns); n.setInteger("lines", b.lines); n.setString("selectmode", b.selectmode);
            n.setInteger("xscrollcommand", b.xscrollcommand); n.setInteger("yscrollcommand", b.yscrollcommand);
            n.setInteger("top", b.top); n.setInteger("scrollX", b.scrollX); n.setInteger("active", b.active); n.setInteger("selectionAnchor", b.anchor);
            n.setIntArray("selectedRows", b.selected.stream().toArray());
            NBTTagList rows = new NBTTagList(); for (String row : b.rows) rows.appendTag(new NBTTagString(row)); n.setTag("rows", rows);
        } else if (w instanceof Scrollbar) {
            Scrollbar b = (Scrollbar) w; n.setBoolean("horizontal", b.horizontal); n.setInteger("command", b.command); n.setDouble("first", b.first); n.setDouble("last", b.last);
        } else if (w instanceof Scale) {
            Scale s = (Scale) w; n.setBoolean("horizontal", s.horizontal); n.setInteger("command", s.command); n.setInteger("length", s.length);
            n.setDouble("from", s.from); n.setDouble("to", s.to); n.setDouble("resolution", s.resolution); n.setDouble("value", s.value); n.setBoolean("showvalue", s.showvalue);
        }
    }
    static void load(TkWidget w, NBTTagCompound n) {
        Map<String, Object> m = new HashMap<>();
        if (w instanceof Checkbutton) { m.put("group", n.getInteger("group")); m.put("selected", n.getBoolean("selected")); }
        else if (w instanceof Listbox) {
            Listbox b = (Listbox) w;
            m.put("width", n.getInteger("columns")); m.put("height", n.getInteger("lines")); m.put("selectmode", n.getString("selectmode"));
            m.put("xscrollcommand", n.getInteger("xscrollcommand")); m.put("yscrollcommand", n.getInteger("yscrollcommand"));
            NBTTagList rows = n.getTagList("rows", 8);
            if (rows.tagCount() > Listbox.MAX_ROWS) throw new IllegalArgumentException("too many saved Listbox rows");
            boolean enabled = b.enabled; b.enabled = true;
            try { for (int start = 0; start < rows.tagCount(); start += Listbox.MAX_BATCH) {
                String[] batch = new String[Math.min(Listbox.MAX_BATCH, rows.tagCount() - start)];
                for (int i = 0; i < batch.length; i++) batch[i] = rows.getStringTagAt(start + i); b.insert(b.size(), batch);
            } } finally { b.enabled = enabled; }
            for (int i : n.getIntArray("selectedRows")) { if (i < 0 || i >= b.size()) throw new IllegalArgumentException("invalid selected row"); b.selected.set(i); }
            // The view is clamped against the laid-out size only when the Listbox is
            // painted, so an unmapped or clipped one may hold any scrolled value. Clamp.
            b.top = n.getInteger("top"); b.scrollX = n.getInteger("scrollX"); b.clampScroll();
            b.active = TkOptions.integer(n.getInteger("active"), 0, Math.max(0, b.size() - 1));
            b.anchor = TkOptions.integer(n.getInteger("selectionAnchor"), 0, Math.max(0, b.size() - 1));
        } else if (w instanceof Scrollbar) {
            m.put("orient", n.getBoolean("horizontal") ? "horizontal" : "vertical"); m.put("command", n.getInteger("command"));
            ((Scrollbar) w).set(n.getDouble("first"), n.getDouble("last"));
        } else if (w instanceof Scale) {
            m.put("orient", n.getBoolean("horizontal") ? "horizontal" : "vertical"); m.put("command", n.getInteger("command"));
            m.put("length", n.getInteger("length")); m.put("showvalue", n.getBoolean("showvalue"));
            for (String key : new String[] {"from", "to", "resolution", "value"}) m.put(key, n.getDouble(key));
        }
        if (!m.isEmpty()) TkOptions.configure(w, m);
    }
}
