package reobf.mpy4oc.tk;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;

/** One Python application per physical display; a persisted token rejects stale handles. */
public final class TkApplication {
    private final TileTkDisplay tile;
    private String owner = "", token = "";
    TkApplication(TileTkDisplay tile) { this.tile = tile; }
    public String token() { return token; }
    public String owner() { return owner; }

    public Object call(String caller, String operation, String expectedToken, Object... args) {
        int count;
        switch (operation) {
            case "claim": case "release": count = 0; break;
            case "geometry": case "forget": case "destroy": case "focus": case "bounds": count = 1; break;
            case "configure": case "cget": case "pack": count = 2; break;
            case "create": case "bind": case "listbox": case "control": case "layout": case "canvas": case "text": count = 3; break;
            case "entry":
                if (args.length < 2) throw new IllegalArgumentException("Entry operation required");
                String action = TkOptions.text(args[1]);
                switch (action) {
                    case "get": case "selection_clear": case "selection_present": case "selection_get": case "xview": count = 2; break;
                    case "index": case "icursor": case "selection_from": case "selection_to": case "selection_adjust": case "xview_moveto": count = 3; break;
                    default: count = 4;
                }
                break;
            default: throw new IllegalArgumentException("unsupported Tk operation: " + operation);
        }
        if (args.length != count) throw new IllegalArgumentException("wrong argument count for " + operation);
        if (operation.equals("claim")) {
            if (caller == null || caller.isEmpty()) throw new IllegalArgumentException("computer address required");
            token = UUID.randomUUID().toString(); owner = caller;
            tile.replaceWidgets(new TkTree(tile::markDirty)); tile.markDirty(); return token;
        }
        if (token.isEmpty() || !token.equals(expectedToken) || !owner.equals(caller))
            throw new IllegalArgumentException("Tk window is no longer attached to this display");
        TkTree tree = tile.widgets();
        if (operation.equals("release")) { tree.clear(); token = owner = ""; tile.markDirty(); tile.applicationReleased(); return null; }
        if (operation.equals("geometry")) {
            Map<String, Object> options = TkOptions.map(args[0]); TkScene s = tile.scene();
            for (String key : options.keySet()) if (!java.util.Arrays.asList("width", "height", "title", "background").contains(key))
                throw new IllegalArgumentException("unsupported window option: " + key);
            tile.configureScene(TkOptions.number(options, "width", s.width, 1024), TkOptions.number(options, "height", s.height, 1024),
                    TkOptions.string(options, "title", s.title), TkOptions.number(options, "background", s.background, 0xFFFFFF));
            return new Object[] {tile.scene().width, tile.scene().height, tile.scene().title};
        }
        if (operation.equals("create")) {
            String kind = TkOptions.text(args[0]); TkWidget parent = required(tree, args[1]);
            if (!(parent instanceof Frame)) throw new IllegalArgumentException("parent must be a Frame");
            Map<String, Object> options = TkOptions.map(args[2]); Frame frame = (Frame) parent; TkWidget w;
            switch (kind) {
                case "frame": w = new Frame(frame); break;
                case "label": w = new Label(frame, ""); break;
                case "button": w = new Button(frame, ""); break;
                    case "entry": w = new Entry(frame); break;
                    case "message": w = new Message(frame); break;
                    case "checkbutton": w = new Checkbutton(frame, false); break;
                    case "radiobutton": w = new Checkbutton(frame, true); break;
                    case "listbox": w = new Listbox(frame); break;
                    case "scrollbar": w = new Scrollbar(frame); break;
                    case "scale": w = new Scale(frame); break;
                    case "canvas": w = new Canvas(frame); break;
                    case "text": w = new Text(frame); break;
                default: throw new IllegalArgumentException("unsupported widget: " + kind);
            }
            try { TkOptions.configure(w, options); }
            catch (RuntimeException e) { w.destroy(); throw e; }
            return w.id;
        }
        TkWidget w = required(tree, args[0]);
        switch (operation) {
            case "text":
                if (!(w instanceof Text)) throw new IllegalArgumentException("Text expected");
                tile.scene(); return TkTextApi.call((Text) w, TkOptions.text(args[1]), TkOptions.sequence(args[2], 4));
            case "canvas":
                if (!(w instanceof Canvas)) throw new IllegalArgumentException("Canvas expected");
                tile.scene(); return ((Canvas) w).call(TkOptions.text(args[1]), TkOptions.map(args[2]));
            case "layout": tile.scene(); return TkGeometry.call(w, TkOptions.text(args[1]), TkOptions.map(args[2]));
            case "configure":
                TkOptions.configure(w, TkOptions.map(args[1]));
                if (w.id == 0) { TkScene s = tile.scene(); tile.configureScene(s.width, s.height, s.title, w.background); }
                return null;
            case "listbox": case "control":
                tile.scene(); return TkControlApi.call(w, TkOptions.text(args[1]), TkOptions.sequence(args[2], Listbox.MAX_BATCH + 1));
            case "cget": return cget(w, TkOptions.text(args[1]));
            case "pack": w.pack(TkOptions.pack(TkOptions.map(args[1]), w.pack)); return null;
            case "forget": w.forget(); return null;
            case "destroy": w.destroy(); return null;
            case "focus": tree.focus(w.id); return null;
            case "bounds": tile.scene(); TkRect r = w.bounds(); return new Object[] {r.x, r.y, r.width, r.height};
            case "bind":
                String sequence = TkOptions.text(args[1]); TkBindings.validate(sequence);
                int callback = TkOptions.integer(args[2], 0, Integer.MAX_VALUE);
                if (callback == 0) w.bindings.remove(sequence); else w.bindings.put(sequence, callback);
                tree.changed(false); return null;
            case "entry":
                if (!(w instanceof Entry)) throw new IllegalArgumentException("Entry expected");
                Entry e = (Entry) w; String action = TkOptions.text(args[1]);
                switch (action) {
                    case "get": return e.text();
                    case "index": return index(e, args[2]);
                    case "insert": e.insert(index(e, args[2]), TkOptions.text(args[3])); break;
                    case "delete": e.delete(index(e, args[2]), index(e, args[3])); break;
                    case "icursor": e.cursor(index(e, args[2])); break;
                    case "selection_clear": e.selectionClear(); break;
                    case "selection_present": return e.selectionPresent();
                    case "selection_get": e.selectionFirst(); return e.selectedText();
                    case "selection_range": e.selectionRange(index(e, args[2]), index(e, args[3])); break;
                    case "selection_from": e.selectionFrom(index(e, args[2])); break;
                    case "selection_to": e.selectionTo(index(e, args[2])); break;
                    case "selection_adjust": e.selectionAdjust(index(e, args[2])); break;
                    case "xview": tile.scene(); return e.xview();
                    case "xview_moveto":
                        if (!(args[2] instanceof Number)) throw new IllegalArgumentException("fraction expected");
                        tile.scene(); e.xviewTo(((Number) args[2]).doubleValue()); break;
                    case "xview_scroll":
                        String units = TkOptions.text(args[3]);
                        if (!units.equals("units") && !units.equals("pages")) throw new IllegalArgumentException("units or pages expected");
                        tile.scene(); e.scroll(TkOptions.integer(args[2], Integer.MIN_VALUE, Integer.MAX_VALUE), units.equals("pages")); break;
                    default: throw new IllegalArgumentException("unsupported Entry operation");
                }
                return e.text();
            default: throw new IllegalArgumentException("unsupported Tk operation: " + operation);
        }
    }

    private TkWidget required(TkTree tree, Object id) {
        TkWidget widget = tree.get(TkOptions.integer(id, 0, Integer.MAX_VALUE));
        if (widget == null) throw new IllegalArgumentException("widget was destroyed"); return widget;
    }
    private int index(Entry e, Object index) {
        if (index instanceof Number) return e.clamp(TkOptions.integer(index, Integer.MIN_VALUE, Integer.MAX_VALUE));
        String value = TkOptions.text(index);
        if (value.equals("end")) return e.length();
        if (value.equals("insert")) return e.cursor();
        if (value.equals("anchor")) return e.anchor;
        if (value.equals("sel.first")) return e.selectionFirst();
        if (value.equals("sel.last")) return e.selectionLast();
        if (value.startsWith("@")) {
            tile.scene();
            int x = Integer.parseInt(value.substring(1));
            return e.indexAt((int) Math.max(-32767, Math.min(32767, e.bounds().x + (long) x)), false);
        }
        try { return e.clamp(Integer.parseInt(value)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid Entry index: " + value); }
    }
    private Object cget(TkWidget w, String option) {
        switch (option) {
            case "text": return w.text();
            case "background": return w.background;
            case "foreground": return w.foreground;
            case "width": return w instanceof Text ? ((Text) w).columns : w instanceof Message ? ((Message) w).wraplength : w instanceof Listbox ? ((Listbox) w).columns : w instanceof Label ? ((Label) w).columns : w.width;
            case "height": return w instanceof Text ? ((Text) w).lines : w instanceof Listbox ? ((Listbox) w).lines : w instanceof Label ? ((Label) w).lines : w.height;
            case "padx": return w.padx;
            case "pady": return w.pady;
            case "borderwidth": return w.border;
            case "anchor": return w.textAnchor.name().toLowerCase(java.util.Locale.ROOT);
            case "state": return !w.enabled ? "disabled" : w instanceof Entry && ((Entry) w).readonly ? "readonly" : "normal";
            default: return TkControlOptions.cget(w, option);
        }
    }

    NBTTagCompound save() {
        NBTTagCompound n = new NBTTagCompound(); n.setString("owner", owner); n.setString("token", token); return n;
    }
    void load(NBTTagCompound n) {
        String saved = n.getString("token"), address = n.getString("owner");
        if (!saved.isEmpty()) {
            if (!UUID.fromString(saved).toString().equals(saved) || address.isEmpty() || address.length() > 128)
                throw new IllegalArgumentException("invalid Tk application identity");
        }
        token = saved; owner = address;
    }
    void reset() { owner = token = ""; }
}
