package reobf.mpy4oc.tk;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Versioned NBT model, preserving IDs, creation/pack order and callback IDs, never Java callbacks. */
public final class TkTreePersistence {
    private TkTreePersistence() {}
    public static NBTTagCompound save(TkTree tree) {
        NBTTagCompound state = new NBTTagCompound();
        state.setInteger("version", 4); state.setInteger("nextId", tree.nextId); state.setInteger("focus", tree.focused());
        NBTTagList nodes = new NBTTagList();
        for (TkWidget w : tree.widgets.values()) {
            NBTTagCompound n = new NBTTagCompound();
            n.setInteger("id", w.id); n.setInteger("parent", w.parent == null ? -1 : w.parent.id); n.setString("kind", w.kind.name());
            n.setInteger("width", w.width); n.setInteger("height", w.height);
            n.setInteger("padx", w.padx); n.setInteger("pady", w.pady); n.setInteger("borderwidth", w.border);
            n.setInteger("background", w.background); n.setInteger("foreground", w.foreground);
            n.setString("anchor", w.textAnchor.name()); n.setString("state", !w.enabled ? "disabled" : w instanceof Entry && ((Entry) w).readonly ? "readonly" : "normal");
            if (w instanceof Label) {
                n.setString("text", w.text()); n.setInteger("columns", ((Label) w).columns); n.setInteger("lines", ((Label) w).lines);
                n.setInteger("wraplength", ((Label) w).wraplength);
            }
            if (w instanceof Button) n.setInteger("command", ((Button) w).command());
            if (w instanceof Entry) {
                Entry e = (Entry) w;
                n.setInteger("cursor", e.cursor()); n.setInteger("selectionAnchor", e.anchor);
                n.setIntArray("selection", new int[] {e.selectionFirst, e.selectionLast});
                n.setInteger("scrollX", e.scrollX);
            }
            TkControlPersistence.save(w, n);
            if (w instanceof Text) n.setTag("textState", ((Text) w).save());
            if (w instanceof Canvas) TkCanvasPersistence.save((Canvas) w, n);
            TkGeometryPersistence.save(w, n);
            if (w instanceof Frame) {
                Frame f = (Frame) w; n.setBoolean("propagate", f.propagate);
                n.setIntArray("packing", f.packed.stream().mapToInt(child -> child.id).toArray());
            }
            if (w.pack != null) {
                TkPack p = w.pack; NBTTagCompound pack = new NBTTagCompound();
                pack.setString("side", p.side.name()); pack.setString("fill", p.fill.name()); pack.setString("anchor", p.anchor.name()); pack.setBoolean("expand", p.expand);
                pack.setIntArray("padding", new int[] {p.left, p.right, p.top, p.bottom, p.ipadx, p.ipady}); n.setTag("pack", pack);
            }
            NBTTagCompound bindings = new NBTTagCompound();
            for (Map.Entry<String, Integer> e : w.bindings.entrySet()) bindings.setInteger(e.getKey(), e.getValue());
            n.setTag("bindings", bindings); nodes.appendTag(n);
        }
        state.setTag("nodes", nodes); return state;
    }

    public static TkTree load(NBTTagCompound state, int width, int height, Runnable onChange) {
        if (state.getInteger("version") < 1 || state.getInteger("version") > 4) throw new IllegalArgumentException("unknown Tk tree version");
        NBTTagList nodes = state.getTagList("nodes", 10);
        if (nodes.tagCount() < 1 || nodes.tagCount() > TkDisplayList.MAX_ITEMS + 1) throw new IllegalArgumentException("invalid widget count");
        // A failed restore never exposes a half-built tree to its tile.
        TkTree tree = new TkTree(); tree.resize(width, height);
        Map<Integer, NBTTagCompound> records = new HashMap<>(); int previous = -1;
        for (int i = 0; i < nodes.tagCount(); i++) {
            NBTTagCompound n = nodes.getCompoundTagAt(i);
            int id = n.getInteger("id"), parent = n.getInteger("parent");
            if (id <= previous || id == Integer.MAX_VALUE || i == 0 && (id != 0 || parent != -1)) throw new IllegalArgumentException("invalid widget ID order");
            previous = id;
            TkWidget.Kind kind = TkWidget.Kind.valueOf(n.getString("kind"));
            TkWidget w;
            if (i == 0) {
                if (kind != TkWidget.Kind.FRAME) throw new IllegalArgumentException("root must be a frame");
                w = tree.root();
            } else {
                if (!(tree.get(parent) instanceof Frame)) throw new IllegalArgumentException("missing parent frame");
                tree.nextId = id; Frame f = (Frame) tree.get(parent);
                switch (kind) {
                    case FRAME: w = new Frame(f); break;
                    case LABEL: w = new Label(f, ""); break;
                    case BUTTON: w = new Button(f, ""); break;
                    case ENTRY: w = new Entry(f); break;
                    case MESSAGE: w = new Message(f); break;
                    case CHECKBUTTON: w = new Checkbutton(f, false); break;
                    case RADIOBUTTON: w = new Checkbutton(f, true); break;
                    case LISTBOX: w = new Listbox(f); break;
                    case SCROLLBAR: w = new Scrollbar(f); break;
                    case SCALE: w = new Scale(f); break;
                    case CANVAS: w = new Canvas(f); break;
                    case TEXT: w = new Text(f); break;
                    default: throw new IllegalArgumentException("unknown widget kind");
                }
            }
            Map<String, Object> options = new HashMap<>();
            for (String key : new String[] {"padx", "pady", "borderwidth", "background", "foreground"}) options.put(key, n.getInteger(key));
            options.put("anchor", n.getString("anchor")); options.put("state", n.getString("state"));
            if (w instanceof Label) { options.put("text", n.getString("text")); options.put("width", n.getInteger("columns")); options.put("height", n.getInteger("lines")); }
            if (w instanceof Label && !(w instanceof Entry)) options.put("wraplength", n.getInteger("wraplength"));
            if (w instanceof Message) options.put("width", n.getInteger("wraplength"));
            if (w instanceof Button) options.put("command", n.getInteger("command"));
            if (w instanceof Frame) options.put("propagate", n.getBoolean("propagate"));
            TkOptions.configure(w, options); w.size(n.getInteger("width"), n.getInteger("height"));
            TkControlPersistence.load(w, n);
            if (w instanceof Text) ((Text) w).load(n.getCompoundTag("textState"));
            if (w instanceof Canvas) TkCanvasPersistence.load((Canvas) w, n);
            if (w instanceof Entry) {
                Entry e = (Entry) w; e.cursor(n.getInteger("cursor"));
                e.selectionFrom(n.hasKey("selection", 11) ? n.getInteger("selectionAnchor") : e.cursor());
                int[] selection = n.getIntArray("selection");
                if (selection.length != 0) {
                    if (selection.length != 2 || selection[0] < -1 || selection[1] > e.length()
                            || !(selection[0] == -1 && selection[1] == -1 || selection[0] >= 0 && selection[1] > selection[0]))
                        throw new IllegalArgumentException("invalid Entry selection");
                    e.selectionFirst = selection[0]; e.selectionLast = selection[1];
                }
                e.restoreScroll(Math.max(0, Math.min(8192, n.getInteger("scrollX"))));
            }
            if (n.hasKey("pack", 10)) {
                NBTTagCompound p = n.getCompoundTag("pack"); int[] pads = p.getIntArray("padding");
                if (pads.length != 6 || w.parent == null) throw new IllegalArgumentException("invalid pack data");
                w.pack = new TkPack(TkPack.Side.valueOf(p.getString("side")), TkPack.Fill.valueOf(p.getString("fill")), p.getBoolean("expand"),
                        TkOptions.anchor(p.getString("anchor")), pads[0], pads[1], pads[2], pads[3], pads[4], pads[5]);
            }
            TkGeometryPersistence.load(w, n);
            NBTTagCompound binds = n.getCompoundTag("bindings");
            for (Object raw : binds.func_150296_c()) {
                String sequence = (String) raw;
                TkBindings.validate(sequence); int callback = binds.getInteger(sequence);
                if (callback <= 0) throw new IllegalArgumentException("invalid callback ID"); w.bindings.put(sequence, callback);
            }
            records.put(id, n);
        }
        for (TkWidget w : tree.widgets.values()) if (w instanceof Frame) {
            Frame frame = (Frame) w;
            for (int id : records.get(w.id).getIntArray("packing")) {
                TkWidget child = tree.get(id);
                if (child == null || child.parent != frame || child.pack == null || frame.packed.contains(child)) throw new IllegalArgumentException("invalid packing order");
                frame.packed.add(child);
            }
            for (TkWidget child : frame.children) if ((child.pack != null) != frame.packed.contains(child)) throw new IllegalArgumentException("missing packed child");
            TkGeometryPersistence.order(frame, records.get(w.id));
        }
        tree.nextId = state.getInteger("nextId");
        if (tree.nextId <= previous) throw new IllegalArgumentException("invalid next widget ID");
        tree.render(); int focus = state.getInteger("focus");
        // Best effort: the saved focus may no longer be focusable at the restored size
        // (a clipped or unmapped widget). Losing focus beats discarding the whole tree.
        if (focus != 0) try { tree.focus(focus); } catch (IllegalArgumentException unfocusable) { /* keep 0 */ }
        tree.setOnChange(onChange); return tree;
    }
}
