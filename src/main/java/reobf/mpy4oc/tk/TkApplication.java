package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * The programs one computer runs on one physical display. The computer holding it may open
 * several here, each with its own windows (its Tk root and Toplevels, see TkWindow.program)
 * on the shared desktop; a persisted token per program rejects stale handles.
 */
public final class TkApplication {
    private final TileTkDisplay tile;
    /** The computer holding the display, and the run of it that claimed it (TileTkDisplay.ownerBootOf). */
    private String owner = "", ownerBoot = "";
    /** Its programs here: number (TkWindow.program, never reused) -> token. */
    private final Map<Integer, String> programs = new LinkedHashMap<>();
    private int nextProgram = 1;
    TkApplication(TileTkDisplay tile) { this.tile = tile; }
    public String owner() { return owner; }
    /** The programs' tokens, in the order they opened. */
    public List<String> tokens() { return new ArrayList<>(programs.values()); }
    /** Whether a program has windows here. */
    public boolean claimed() { return !programs.isEmpty(); }
    /** The token of the program {@code widget} belongs to: "" on the desktop or for a program that left. */
    public String tokenOf(TkWidget widget) {
        String token = widget == null ? null : programs.get(TkTree.programOf(widget));
        return token == null ? "" : token;
    }
    private Integer program(String token) {
        for (Map.Entry<Integer, String> entry : programs.entrySet()) if (entry.getValue().equals(token)) return entry.getKey();
        return null;
    }

    public Object call(String caller, String operation, String expectedToken, Object... args) {
        int count;
        switch (operation) {
            case "claim": case "release": case "claim_free": case "screen": count = 0; break;
            case "forget": case "destroy": case "focus": case "focused": case "bounds": case "flags": count = 1; break;
            case "configure": case "cget": case "pack": count = 2; break;
            case "create": case "bind": case "listbox": case "control": case "layout": case "canvas": case "text": case "wm": case "image": case "notebook": case "tree": case "menu": case "paned": case "grab": count = 3; break;
            case "entry":
                if (args.length < 2) throw new IllegalArgumentException("Entry operation required");
                String action = TkOptions.text(args[1]);
                switch (action) {
                    case "get": case "selection_clear": case "selection_present": case "selection_get": case "xview": count = 2; break;
                    case "index": case "icursor": case "selection_from": case "selection_to": case "selection_adjust": case "xview_moveto": case "set": count = 3; break;
                    default: count = 4;
                }
                break;
            default: throw new IllegalArgumentException("unsupported Tk operation: " + operation);
        }
        if (args.length != count) throw new IllegalArgumentException("wrong argument count for " + operation);
        // Tk(): a new program on this display. With no display named (claim_free) only if no
        // other computer that is still running holds it ("" then); naming one (claim) takes it
        // over. One call, so two computers cannot both take it.
        if (operation.equals("claim_free") || operation.equals("claim")) return open(caller, operation.equals("claim"));
        Integer program = program(expectedToken);
        if (program == null || !owner.equals(caller))
            throw new IllegalArgumentException("Tk window is no longer attached to this display");
        TkTree tree = tile.widgets();
        // Every widget or image the call names must be this program's (TkTree.owned, ownedImage).
        tree.caller = program;
        try {
            Object result = run(tree, program, operation, args);
            // A change that leaves something on the display undrawn is an error, not a widget that does
            // not show: a widget being made is not made; other changes stay (removing things is fine).
            if (DRAWS.contains(operation) && !removing(args) && !tree.drawsEverything()) {
                if (operation.equals("create")) tree.get((Integer) result).destroy();
                throw new IllegalArgumentException(FULL);
            }
            return result;
        } finally {
            tree.caller = 0;
        }
    }

    /** The operations that can make the display draw more. */
    private static final java.util.Set<String> DRAWS = new java.util.HashSet<>(java.util.Arrays.asList(
            "create", "configure", "pack", "layout", "wm", "listbox", "control", "text", "canvas", "notebook", "entry", "tree", "menu", "paned"));
    static final String FULL = "the display is full: it draws at most " + TkDisplayList.MAX_RECORDS + " paint records and "
            + TkDisplayList.MAX_PAINT_BYTES / 1024 + " KB of text";
    /** Whether a call (its action, after the widget) takes something away or hides it. */
    private static boolean removing(Object[] args) {
        String action = args.length > 1 && args[1] instanceof String ? (String) args[1] : "";
        return action.startsWith("delete") || action.startsWith("detach") || action.startsWith("forget") || action.startsWith("remove") || action.equals("dtag")
                || action.equals("withdraw") || action.equals("iconify") || action.equals("hide");
    }

    private Object run(TkTree tree, int program, String operation, Object[] args) {
        if (operation.equals("release")) { release(program); return null; }
        if (operation.equals("screen")) { TkScene s = tile.scene(); return new Object[] {s.width, s.height}; }
        if (operation.equals("create")) {
            String kind = TkOptions.text(args[0]); int parentId = TkOptions.integer(args[1], 0, Integer.MAX_VALUE);
            Map<String, Object> options = TkOptions.map(args[2]); TkWidget w;
            if (kind.equals("window")) {
                // A Toplevel: one more window of this program on the desktop (the root, 0).
                if (parentId != 0) throw new IllegalArgumentException("a Toplevel's parent is the desktop (0)");
                w = TkWindow.create(tree.root());
            } else {
                if (parentId == 0) throw new IllegalArgumentException("the desktop holds only windows");
                TkWidget parent = tree.owned(parentId);
                if (!(parent instanceof Frame)) throw new IllegalArgumentException("parent must be a Frame");
                Frame frame = (Frame) parent;
                switch (kind) {
                case "frame": w = new Frame(frame); break;
                case "labelframe": w = new Labelframe(frame, false); break;
                case "ttk_labelframe": w = new Labelframe(frame, true); break;
                case "notebook": w = new Notebook(frame); break;
                case "label": w = new Label(frame, ""); break;
                case "button": w = new Button(frame, ""); break;
                    case "entry": w = new Entry(frame); break;
                    case "spinbox": w = new Spinbox(frame, false); break;
                    case "ttk_spinbox": w = new Spinbox(frame, true); break;
                    case "combobox": w = new Combobox(frame); break;
                    case "treeview": w = new Treeview(frame); break;
                    case "menu": w = new Menu(frame); break;
                    case "panedwindow": w = new Panedwindow(frame, false); break;
                    case "ttk_panedwindow": w = new Panedwindow(frame, true); break;
                    case "menubutton": w = new Menubutton(frame, false); break;
                    case "ttk_menubutton": w = new Menubutton(frame, true); break;
                    case "message": w = new Message(frame); break;
                    case "checkbutton": w = new Checkbutton(frame, false); break;
                    case "radiobutton": w = new Checkbutton(frame, true); break;
                    case "listbox": w = new Listbox(frame); break;
                    case "scrollbar": w = new Scrollbar(frame); break;
                    case "scale": w = new Scale(frame); break;
                    case "progressbar": w = new Progressbar(frame); break;
                    case "separator": w = new Separator(frame); break;
                    case "sizegrip": w = new Sizegrip(frame); break;
                    case "canvas": w = new Canvas(frame); break;
                    case "text": w = new Text(frame); break;
                default: throw new IllegalArgumentException("unsupported widget: " + kind);
                }
            }
            try { TkOptions.configure(w, options); }
            catch (RuntimeException e) { w.destroy(); throw e; }
            return w.id;
        }
        if (operation.equals("image")) {
            tile.scene();
            return TkImageApi.call(tree, TkOptions.integer(args[0], 0, Integer.MAX_VALUE), TkOptions.text(args[1]), TkOptions.map(args[2]));
        }
        TkWidget w = tree.owned(TkOptions.integer(args[0], 0, Integer.MAX_VALUE));
        switch (operation) {
            case "text":
                if (!(w instanceof Text)) throw new IllegalArgumentException("Text expected");
                tile.scene(); return TkTextApi.call((Text) w, TkOptions.text(args[1]), TkOptions.sequence(args[2], 256));
            case "canvas":
                if (!(w instanceof Canvas)) throw new IllegalArgumentException("Canvas expected");
                tile.scene(); return ((Canvas) w).call(TkOptions.text(args[1]), TkOptions.map(args[2]));
            case "layout": tile.scene(); return TkGeometry.call(w, TkOptions.text(args[1]), TkOptions.map(args[2]));
            case "tree":
                if (!(w instanceof Treeview)) throw new IllegalArgumentException("Treeview expected");
                tile.scene(); return TkTreeviewApi.call((Treeview) w, TkOptions.text(args[1]), TkOptions.sequence(args[2], Treeview.MAX_BATCH + 4));
            case "grab":
                switch (TkOptions.text(args[1])) {
                    case "set": tree.grab(w); return null;
                    case "release": tree.releaseGrab(w); return null;
                    case "current": { TkWidget g = tree.grabOf(program); return g == null ? 0 : g.id; }
                    case "status": return tree.grabOf(program) == w ? "local" : "";
                    default: throw new IllegalArgumentException("bad grab option");
                }
            case "paned":
                if (!(w instanceof Panedwindow)) throw new IllegalArgumentException("Panedwindow expected");
                tile.scene(); return TkPanedApi.call(tree, (Panedwindow) w, TkOptions.text(args[1]), TkOptions.sequence(args[2], 4));
            case "menu":
                if (!(w instanceof Menu)) throw new IllegalArgumentException("Menu expected");
                tile.scene(); return TkMenuApi.call((Menu) w, TkOptions.text(args[1]), TkOptions.sequence(args[2], 3));
            case "notebook":
                if (!(w instanceof Notebook)) throw new IllegalArgumentException("Notebook expected");
                tile.scene(); return ((Notebook) w).call(TkOptions.text(args[1]), TkOptions.sequence(args[2], 3));
            case "wm":
                if (!TkWindow.is(w)) throw new IllegalArgumentException("Toplevel expected");
                tile.scene(); return TkWindow.wm(tree, (Frame) w, TkOptions.text(args[1]), TkOptions.map(args[2]));
            case "configure":
                TkOptions.configure(w, TkOptions.map(args[1]));
                return null;
            case "listbox": case "control":
                tile.scene(); return TkControlApi.call(w, TkOptions.text(args[1]), TkOptions.sequence(args[2], Listbox.MAX_BATCH + 1));
            case "cget": return cget(w, TkOptions.text(args[1]));
            case "pack": w.pack(TkOptions.pack(TkOptions.map(args[1]), w.pack)); return null;
            case "forget": w.forget(); return null;
            case "destroy": w.destroy(); return null;
            case "focus": tree.focusFor(program, w.id); return null;
            case "focused": {
                // The widget with the keyboard, if it is this program's (Tk's focus_get); 0 for none.
                tile.scene(); TkWidget focused = tree.get(tree.focused());
                return focused != null && TkTree.programOf(focused) == program ? focused.id : 0;
            }
            case "flags": tile.scene(); return tree.flags(w);
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
                    // ttk's set (a Spinbox's, a Combobox's): no validation, indices kept.
                    case "set": e.value(TkOptions.text(args[2])); e.tree.changed(false); break;
                    // The program's answer to a player's change it was asked to validate.
                    case "validated": e.validated(TkOptions.integer(args[2], 0, Integer.MAX_VALUE), TkOptions.bool(args[3])); break;
                    default: throw new IllegalArgumentException("unsupported Entry operation");
                }
                return e.text();
            default: throw new IllegalArgumentException("unsupported Tk operation: " + operation);
        }
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
            case "height": return w instanceof Text ? ((Text) w).lines : w instanceof Listbox ? ((Listbox) w).lines : w instanceof Combobox ? ((Combobox) w).height : w instanceof Treeview ? ((Treeview) w).lines
                    : w instanceof Label ? ((Label) w).lines : w.height;
            case "padx": return w.padLeft;
            case "pady": return w.padTop;
            case "relief": return w.relief.option();
            case "labelanchor": if (w instanceof Labelframe) return ((Labelframe) w).labelAnchor.option(); return TkControlOptions.cget(w, option);
            case "labelwidget":
                if (w instanceof Labelframe) return ((Labelframe) w).labelWidget == null ? 0 : ((Labelframe) w).labelWidget.id;
                return TkControlOptions.cget(w, option);
            case "borderwidth": return w.border;
            case "anchor": return w.textAnchor.name().toLowerCase(java.util.Locale.ROOT);
            case "state": return !w.enabled ? "disabled" : w instanceof Entry && ((Entry) w).readonly ? "readonly" : "normal";
            case "takefocus": return w.takefocus < 0 ? "" : (Object) w.takefocus;
            default: return TkControlOptions.cget(w, option);
        }
    }

    NBTTagCompound save() {
        NBTTagCompound n = new NBTTagCompound(); n.setString("owner", owner);
        if (!ownerBoot.isEmpty()) n.setString("boot", ownerBoot);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Integer, String> entry : programs.entrySet()) {
            NBTTagCompound saved = new NBTTagCompound();
            saved.setInteger("id", entry.getKey()); saved.setString("token", entry.getValue());
            list.appendTag(saved);
        }
        n.setTag("programs", list); n.setInteger("nextProgram", nextProgram);
        return n;
    }
    /** An older save's single program ("token") is not kept: its code expects the display to itself. */
    void load(NBTTagCompound n) {
        reset();
        String address = n.getString("owner"), boot = n.getString("boot");
        NBTTagList list = n.getTagList("programs", 10);
        int next = Math.max(1, n.getInteger("nextProgram"));
        Map<Integer, String> loaded = new LinkedHashMap<>();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound saved = list.getCompoundTagAt(i);
            int id = saved.getInteger("id"); String token = saved.getString("token");
            if (id < 1 || id >= next || loaded.containsKey(id) || !UUID.fromString(token).toString().equals(token) || loaded.containsValue(token))
                throw new IllegalArgumentException("invalid Tk program");
            loaded.put(id, token);
        }
        if (loaded.isEmpty()) return;
        if (address.isEmpty() || address.length() > 128 || boot.length() > 64) throw new IllegalArgumentException("invalid Tk application identity");
        owner = address; ownerBoot = boot; programs.putAll(loaded); nextProgram = next;
    }
    /** Whether a loaded tree belongs to these programs: the desktop holds only their windows, and only they have images. */
    boolean accepts(TkTree tree) {
        for (TkWidget child : tree.root().children)
            if (!TkWindow.is(child) || !programs.containsKey(((Frame) child).window.program)) return false;
        for (TkImage image : tree.images()) if (!programs.containsKey(image.program)) return false;
        return true;
    }
    void reset() { programs.clear(); owner = ownerBoot = ""; }

    /** Tk() opens a program: its token and root window, or "" when another running computer holds the display (claim_free). */
    private Object open(String caller, boolean takeOver) {
        if (caller == null || caller.isEmpty()) throw new IllegalArgumentException("computer address required");
        // Normally unreachable: a slave's component is hidden from computers.
        if (tile.isSlave()) throw new IllegalArgumentException("display is part of a multiblock group; use the group's main display");
        boolean held = !programs.isEmpty();
        if (held && !takeOver && !owner.equals(caller) && tile.ownerRunning(owner) && ownerBoot.equals(tile.ownerBootOf(owner))) return "";
        // Free, or held by another computer (stopped, or taken over) or by an earlier run of this
        // one: start a fresh desktop. Otherwise the program joins the ones already here.
        if (!held || !owner.equals(caller) || !ownerBoot.equals(tile.ownerBootOf(caller))) {
            programs.clear();
            owner = caller; ownerBoot = tile.ownerBootOf(caller);
            tile.replaceWidgets(new TkTree(tile::markDirty));
        }
        TkTree tree = tile.widgets();
        int program = nextProgram;
        tree.caller = program;
        Frame window;
        try {
            window = TkWindow.create(tree.root());   // its root window, like a Toplevel
            if (!tree.drawsEverything()) { window.destroy(); throw new IllegalArgumentException(FULL); }
        } finally {
            tree.caller = 0;
        }
        nextProgram++;
        String token = UUID.randomUUID().toString();
        programs.put(program, token);
        tile.markDirty();
        return new Object[] {token, window.id};
    }

    /** A program let the display go (root.destroy(), its command ended): its windows close; the last one frees the display. */
    private void release(int program) {
        tile.widgets().releaseProgram(program);
        programs.remove(program);
        if (programs.isEmpty()) { tile.widgets().clear(); owner = ownerBoot = ""; tile.applicationReleased(); }
        tile.markDirty();
    }

    /** The computer at address stopped or restarted: if it held this display, its programs are gone. */
    public void ownerStopped(String address) {
        if (programs.isEmpty() || !owner.equals(address)) return;
        tile.widgets().clear(); reset(); tile.markDirty(); tile.applicationReleased();
    }
    /** The display was merged into another group: drop its programs and their windows. */
    void evict() { tile.widgets().clear(); reset(); tile.markDirty(); }
}
