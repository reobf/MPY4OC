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
        } else if (w instanceof Separator) {
            n.setBoolean("horizontal", ((Separator) w).horizontal);
        } else if (w instanceof Progressbar) {
            Progressbar p = (Progressbar) w; n.setBoolean("horizontal", p.horizontal); n.setBoolean("indeterminate", p.indeterminate);
            n.setInteger("length", p.length); n.setDouble("maximum", p.maximum); n.setDouble("value", p.value);
        }
        if (w instanceof Entry) {
            // Optional keys (older saves have none): what differs from a new Entry's.
            Entry e = (Entry) w;
            if (!e.show.isEmpty()) n.setString("show", e.show);
            if (e.justify != Entry.LEFT) n.setString("justify", Entry.JUSTIFY[e.justify]);
            if (!e.validate.equals("none")) n.setString("validate", e.validate);
            if (e.notify != 0) n.setInteger("notify", e.notify);
        }
        if (w instanceof Treeview) TkTreeviewPersistence.save((Treeview) w, n);
        if (w.look != null) {
            // ttk: {key: [{on, off, colour or relief}, ...]}.
            NBTTagCompound look = new NBTTagCompound();
            for (Map.Entry<String, Object> e : w.look.save().entrySet()) {
                NBTTagList rules = new NBTTagList();
                for (Object raw : (Object[]) e.getValue()) {
                    Object[] rule = (Object[]) raw;
                    NBTTagCompound r = new NBTTagCompound();
                    r.setInteger("on", (Integer) rule[0]); r.setInteger("off", (Integer) rule[1]);
                    if (rule[2] instanceof Integer) r.setInteger("color", (Integer) rule[2]); else r.setString("relief", (String) rule[2]);
                    rules.appendTag(r);
                }
                look.setTag(e.getKey(), rules);
            }
            n.setTag("look", look);
        }
        if (w.held != 0) n.setInteger("held", w.held);
        if (w instanceof Label && ((Label) w).minColumns != 0) n.setInteger("minColumns", ((Label) w).minColumns);
        if (w instanceof Spinbox) {
            Spinbox s = (Spinbox) w;
            n.setBoolean("ttk", s.ttk); n.setDouble("from", s.from); n.setDouble("to", s.to); n.setDouble("increment", s.increment);
            n.setTag("values", strings(s.values)); n.setBoolean("wrap", s.wrap); n.setString("format", s.format);
            n.setInteger("valueIndex", s.valueIndex); n.setString("lastValue", s.lastValue); n.setBoolean("delegate", s.delegate);
        }
        if (w instanceof Combobox) {
            Combobox c = (Combobox) w;
            n.setTag("values", strings(c.values)); n.setInteger("rows", c.height); n.setInteger("currentIndex", c.currentIndex);
            n.setBoolean("postcommand", c.postcommand);
        }
        if (w instanceof Menu) {
            Menu menu = (Menu) w;
            n.setIntArray("menuColors", new int[] {menu.activeBackground, menu.activeForeground, menu.disabledForeground, menu.selectColor});
            n.setBoolean("postcommand", menu.postcommand); n.setBoolean("select", menu.selectNotify); n.setInteger("notify", menu.notify);
            NBTTagList entries = new NBTTagList();
            for (Menu.Entry e : menu.entries) {
                NBTTagCompound t = new NBTTagCompound();
                t.setString("type", e.type.option()); t.setString("label", e.label); t.setString("accelerator", e.accelerator);
                t.setBoolean("disabled", e.disabled); t.setBoolean("selected", e.selected); t.setBoolean("indicator", e.indicator);
                t.setInteger("underline", e.underline); t.setInteger("menu", e.cascade);
                t.setIntArray("colors", new int[] {e.foreground, e.background, e.activeBackground, e.activeForeground, e.selectColor});
                entries.appendTag(t);
            }
            n.setTag("entries", entries);
        }
        if (w instanceof Panedwindow) {
            Panedwindow p = (Panedwindow) w;
            n.setBoolean("ttk", p.ttk); n.setBoolean("horizontal", p.horizontal); n.setBoolean("showHandle", p.showHandle);
            n.setIntArray("sash", new int[] {p.sashThickness, p.sashWidth, p.sashPad, p.handleSize, p.handlePad});
            n.setString("sashRelief", p.sashRelief.option());
            NBTTagList panes = new NBTTagList();
            for (Panedwindow.Pane pane : p.panes) {
                NBTTagCompound t = new NBTTagCompound();
                t.setInteger("widget", pane.widget.id);
                t.setIntArray("values", new int[] {pane.weight, pane.reqSize, pane.width, pane.height, pane.minSize, pane.padx, pane.pady, pane.sticky, pane.stretch, pane.hide ? 1 : 0});
                panes.appendTag(t);
            }
            n.setTag("panes", panes);
        }
        if (w instanceof Menubutton) {
            Menubutton mb = (Menubutton) w;
            n.setBoolean("ttk", mb.ttk); n.setInteger("menu", mb.menu); n.setString("direction", mb.direction); n.setBoolean("indicator", mb.indicator);
        }
    }
    /** A saved panedwindow's panes, once every widget exists (a pane may be a sibling saved after it). */
    static void loadPanes(Panedwindow p, NBTTagCompound n) {
        NBTTagList panes = n.getTagList("panes", 10);
        if (panes.tagCount() > Panedwindow.MAX_PANES) throw new IllegalArgumentException("too many saved panes");
        for (int i = 0; i < panes.tagCount(); i++) {
            NBTTagCompound t = panes.getCompoundTagAt(i);
            TkWidget widget = p.tree.get(t.getInteger("widget"));
            int[] v = t.getIntArray("values");
            if (widget == null || v.length != 10 || widget.parent != p && widget.parent != p.parent || TkWindow.is(widget) || !widget.manager().isEmpty()
                    || p.indexOf(widget) >= 0)
                throw new IllegalArgumentException("invalid saved pane");
            for (int k = 0; k < v.length; k++) if (v[k] < (k == 1 ? -1 : 0) || v[k] > (1 << 20)) throw new IllegalArgumentException("invalid saved pane");
            Panedwindow.Pane pane = new Panedwindow.Pane(widget);
            pane.weight = v[0]; pane.reqSize = v[1]; pane.width = v[2]; pane.height = v[3]; pane.minSize = v[4]; pane.padx = v[5]; pane.pady = v[6];
            pane.sticky = v[7] & 15; pane.stretch = v[8] & 7; pane.hide = v[9] != 0;
            widget.container = p;
            p.panes.add(pane);
        }
        p.tree.changed(true);
    }

    /** A saved menu's colours, notices and entries (cascades by id: a cascade's menu may come later in the save). */
    private static void loadMenu(Menu menu, NBTTagCompound n) {
        int[] colors = n.getIntArray("menuColors");
        if (colors.length != 4) throw new IllegalArgumentException("invalid saved menu colours");
        for (int c : colors) TkWidget.checkColor(c);
        menu.activeBackground = colors[0]; menu.activeForeground = colors[1]; menu.disabledForeground = colors[2]; menu.selectColor = colors[3];
        menu.postcommand = n.getBoolean("postcommand"); menu.selectNotify = n.getBoolean("select"); menu.notify = Math.max(0, n.getInteger("notify"));
        NBTTagList list = n.getTagList("entries", 10);
        if (list.tagCount() > Menu.MAX_ENTRIES) throw new IllegalArgumentException("too many saved menu entries");
        List<Menu.Entry> entries = new ArrayList<>();
        int bytes = 0;
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            Menu.Entry e = new Menu.Entry();
            e.type = Menu.Type.valueOf(t.getString("type").toUpperCase(Locale.ROOT));
            e.label = t.getString("label"); e.accelerator = t.getString("accelerator");
            for (String text : new String[] {e.label, e.accelerator}) {
                TkFontMetrics.checkText(text);
                if (text.indexOf('\n') >= 0) throw new IllegalArgumentException("invalid saved menu label");
                bytes += text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            e.disabled = t.getBoolean("disabled"); e.selected = t.getBoolean("selected"); e.indicator = t.getBoolean("indicator");
            e.underline = TkOptions.integer(t.getInteger("underline"), -1, 1024); e.cascade = Math.max(0, t.getInteger("menu"));
            int[] c = t.getIntArray("colors");
            if (c.length != 5) throw new IllegalArgumentException("invalid saved menu entry colours");
            for (int color : c) if (color < -1 || color > 0xFFFFFF) throw new IllegalArgumentException("invalid saved menu entry colours");
            e.foreground = c[0]; e.background = c[1]; e.activeBackground = c[2]; e.activeForeground = c[3]; e.selectColor = c[4];
            entries.add(e);
        }
        menu.tree.checkTextBytes(menu, bytes);
        menu.entries.clear(); menu.entries.addAll(entries);
    }

    private static NBTTagList strings(List<String> values) {
        NBTTagList list = new NBTTagList();
        for (String value : values) list.appendTag(new NBTTagString(value));
        return list;
    }
    /** Saved -values, checked as the program's are. */
    private static List<String> strings(NBTTagCompound n) {
        NBTTagList list = n.getTagList("values", 8);
        Object[] raw = new Object[list.tagCount()];
        for (int i = 0; i < raw.length; i++) raw[i] = list.getStringTagAt(i);
        return TkControlOptions.values(raw);
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
        } else if (w instanceof Separator) {
            m.put("orient", n.getBoolean("horizontal") ? "horizontal" : "vertical");
        } else if (w instanceof Progressbar) {
            m.put("orient", n.getBoolean("horizontal") ? "horizontal" : "vertical"); m.put("mode", n.getBoolean("indeterminate") ? "indeterminate" : "determinate");
            m.put("length", n.getInteger("length")); m.put("maximum", n.getDouble("maximum")); m.put("value", n.getDouble("value"));
        }
        if (n.hasKey("takefocus", 3)) m.put("takefocus", n.getInteger("takefocus"));
        if (n.hasKey("look", 10)) {
            NBTTagCompound look = n.getCompoundTag("look");
            Map<String, Object> keys = new LinkedHashMap<>();
            for (Object key : look.func_150296_c()) {
                NBTTagList rules = look.getTagList((String) key, 10);
                Object[] parsed = new Object[rules.tagCount()];
                for (int i = 0; i < parsed.length; i++) {
                    NBTTagCompound r = rules.getCompoundTagAt(i);
                    parsed[i] = new Object[] {r.getInteger("on"), r.getInteger("off"), r.hasKey("color", 3) ? (Object) r.getInteger("color") : r.getString("relief")};
                }
                keys.put((String) key, parsed);
            }
            m.put("look", keys);
        }
        if (n.hasKey("held", 3)) m.put("held", n.getInteger("held"));
        if (n.hasKey("minColumns", 3)) m.put("minwidth", n.getInteger("minColumns"));
        if (w instanceof Entry) {
            if (n.hasKey("show", 8)) m.put("show", n.getString("show"));
            if (n.hasKey("justify", 8)) m.put("justify", n.getString("justify"));
            if (n.hasKey("validate", 8)) m.put("validate", n.getString("validate"));
            if (n.hasKey("notify", 3)) m.put("notify", n.getInteger("notify"));
        }
        if (!m.isEmpty()) TkOptions.configure(w, m);
        // A Spinbox's and a Combobox's state is put back as it was: configuring it would step
        // or reformat the value, as Tk does when a program changes -from or -values.
        if (w instanceof Treeview) TkTreeviewPersistence.load((Treeview) w, n);
        if (w instanceof Spinbox) {
            Spinbox s = (Spinbox) w;
            double from = n.getDouble("from"), to = n.getDouble("to"), increment = n.getDouble("increment");
            for (double d : new double[] {from, to, increment})
                if (!Double.isFinite(d) || Math.abs(d) > 1e9) throw new IllegalArgumentException("invalid saved Spinbox range");
            if (!s.ttk && from > to) throw new IllegalArgumentException("invalid saved Spinbox range");
            String format = n.getString("format");
            if (format.length() > 256) throw new IllegalArgumentException("invalid saved Spinbox format");
            if (!s.ttk && !format.isEmpty()) TkNumberFormat.classicFormat(format);
            s.from = from; s.to = to; s.increment = increment; s.format = format; s.wrap = n.getBoolean("wrap");
            s.values.clear(); s.values.addAll(strings(n));
            int index = n.getInteger("valueIndex");
            if (index < (s.ttk ? -1 : 0) || index > Math.max(0, s.values.size() - 1) && !s.ttk) throw new IllegalArgumentException("invalid saved Spinbox index");
            s.valueIndex = index; s.lastValue = n.getString("lastValue"); s.delegate = n.getBoolean("delegate");
        }
        if (w instanceof Menu) loadMenu((Menu) w, n);
        if (w instanceof Panedwindow) {
            Panedwindow p = (Panedwindow) w;
            int[] sash = n.getIntArray("sash");
            if (sash.length != 5) throw new IllegalArgumentException("invalid saved panedwindow");
            for (int v : sash) if (v < 0 || v > 1024) throw new IllegalArgumentException("invalid saved panedwindow");
            p.horizontal = n.getBoolean("horizontal"); p.showHandle = n.getBoolean("showHandle");
            p.sashThickness = sash[0]; p.sashWidth = sash[1]; p.sashPad = sash[2]; p.handleSize = sash[3]; p.handlePad = sash[4];
            p.sashRelief = TkOptions.relief(n.getString("sashRelief"));
        }
        if (w instanceof Menubutton) {
            // Its menu by id (it may come later in the save), looked up when it is pressed.
            Menubutton mb = (Menubutton) w;
            mb.menu = Math.max(0, n.getInteger("menu")); mb.indicator = n.getBoolean("indicator");
            String direction = n.getString("direction");
            if (!Arrays.asList(Menubutton.DIRECTIONS).contains(direction)) throw new IllegalArgumentException("invalid saved Menubutton direction");
            mb.direction = direction;
        }
        if (w instanceof Combobox) {
            Combobox c = (Combobox) w;
            c.values.clear(); c.values.addAll(strings(n));
            c.height = TkOptions.integer(n.getInteger("rows"), 0, 4096);
            c.currentIndex = TkOptions.integer(n.getInteger("currentIndex"), -1, Integer.MAX_VALUE);
            c.postcommand = n.getBoolean("postcommand");
        }
    }
}
