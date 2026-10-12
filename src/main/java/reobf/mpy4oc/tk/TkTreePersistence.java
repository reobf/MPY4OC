package reobf.mpy4oc.tk;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Versioned NBT model, preserving IDs, creation/pack order and callback IDs, never Java callbacks. */
public final class TkTreePersistence {
    private TkTreePersistence() {}
    public static NBTTagCompound save(TkTree tree) { return save(tree, null); }

    /**
     * With {@code storage}, image pixels go there (only changed ones) and the NBT keeps id,
     * size and version; without, the pixels are inlined (tests only: photos would overflow
     * a chunk).
     */
    public static NBTTagCompound save(TkTree tree, TkImageStorage storage) {
        NBTTagCompound state = new NBTTagCompound();
        NBTTagList images = new NBTTagList();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (TkImage image : tree.images.values()) {
            NBTTagCompound n = new NBTTagCompound();
            n.setInteger("id", image.id); n.setInteger("width", image.width); n.setInteger("height", image.height); n.setLong("version", image.version);
            n.setInteger("program", image.program);
            ids.add(image.id);
            if (storage == null) n.setByteArray("packed", image.packed());
            else if (image.savedVersion != image.version) {
                try { storage.write(image.id, image.packed()); image.savedVersion = image.version; }
                catch (java.io.IOException error) { reobf.mpy4oc.main.MyMod.LOG.warn("Could not save Tk image " + image.id, error); }
            }
            images.appendTag(n);
        }
        if (storage != null) {
            try { storage.retain(ids); }
            catch (java.io.IOException error) { reobf.mpy4oc.main.MyMod.LOG.warn("Could not clean up Tk images", error); }
        }
        state.setTag("images", images); state.setInteger("nextImage", tree.nextImage);
        // Version 6: windows and images say which program they belong to (TkApplication).
        state.setInteger("version", 6); state.setInteger("nextId", tree.nextId); state.setInteger("focus", tree.focused());
        NBTTagList nodes = new NBTTagList();
        for (TkWidget w : tree.widgets.values()) {
            NBTTagCompound n = new NBTTagCompound();
            n.setInteger("id", w.id); n.setInteger("parent", w.parent == null ? -1 : w.parent.id); n.setString("kind", w.kind.name());
            n.setInteger("width", w.width); n.setInteger("height", w.height);
            n.setInteger("padx", w.padLeft); n.setInteger("pady", w.padTop); n.setInteger("borderwidth", w.border);
            if (w.padRight != w.padLeft || w.padBottom != w.padTop) n.setIntArray("padding", new int[] {w.padLeft, w.padTop, w.padRight, w.padBottom});
            n.setString("relief", w.relief.option());
            n.setInteger("background", w.background); n.setInteger("foreground", w.foreground);
            n.setString("anchor", w.textAnchor.name()); n.setString("state", !w.enabled ? "disabled" : w instanceof Entry && ((Entry) w).readonly ? "readonly" : "normal");
            if (w instanceof Label) {
                n.setString("text", w.text()); n.setInteger("columns", ((Label) w).columns); n.setInteger("lines", ((Label) w).lines);
                n.setInteger("wraplength", ((Label) w).wraplength);
            }
            if (w instanceof Button) n.setInteger("command", ((Button) w).command());
            if (w instanceof Notebook) {
                Notebook book = (Notebook) w;
                n.setInteger("current", book.current); n.setInteger("command", book.command);
                NBTTagList tabs = new NBTTagList();
                for (Notebook.Tab tab : book.tabs) {
                    NBTTagCompound t = new NBTTagCompound();
                    t.setInteger("page", tab.page.id); t.setString("text", tab.text); t.setString("state", Notebook.STATES[tab.state]);
                    t.setString("sticky", tab.sticky); t.setIntArray("padding", tab.padding.clone()); t.setInteger("underline", tab.underline);
                    t.setString("image", tab.image); t.setString("compound", tab.compound);
                    tabs.appendTag(t);
                }
                n.setTag("tabs", tabs);
            }
            if (w instanceof Labelframe) {
                Labelframe labelframe = (Labelframe) w;
                n.setString("text", labelframe.text); n.setString("labelanchor", labelframe.labelAnchor.name()); n.setBoolean("ttk", labelframe.ttk);
                if (labelframe.labelWidget != null) n.setInteger("labelwidget", labelframe.labelWidget.id);
            }
            if (w instanceof Label && ((Label) w).image != 0) n.setInteger("image", ((Label) w).image);
            if (w instanceof Entry) {
                Entry e = (Entry) w;
                n.setInteger("cursor", e.cursor()); n.setInteger("selectionAnchor", e.anchor);
                n.setIntArray("selection", new int[] {e.selectionFirst, e.selectionLast});
                n.setInteger("scrollX", e.scrollX);
            }
            TkControlPersistence.save(w, n);
            if (w.takefocus >= 0) n.setInteger("takefocus", w.takefocus);
            if (w instanceof Text) n.setTag("textState", ((Text) w).save());
            if (w instanceof Canvas) TkCanvasPersistence.save((Canvas) w, n);
            TkGeometryPersistence.save(w, n);
            if (w instanceof Frame) {
                Frame f = (Frame) w; n.setBoolean("propagate", f.propagate);
                n.setIntArray("packing", f.packed.stream().mapToInt(child -> child.id).toArray());
                // Stacking order (lift/lower, raised windows); creation order before version 5.
                n.setIntArray("stack", f.children.stream().mapToInt(child -> child.id).toArray());
                if (f.window != null) {
                    NBTTagCompound window = new NBTTagCompound();
                    window.setString("title", f.window.title); window.setInteger("lastFocus", f.window.lastFocus);
                    window.setInteger("program", f.window.program);
                    if (f.window.restore != null) window.setTag("restore", TkGeometryPersistence.placeTag(f.window.restore));
                    // Minimized, maximized (with the placement to restore), wm resizable/minsize/maxsize.
                    window.setBoolean("iconic", f.window.iconic);
                    if (f.window.normal != null) window.setTag("normal", TkGeometryPersistence.placeTag(f.window.normal));
                    window.setBoolean("resizableX", f.window.resizableX); window.setBoolean("resizableY", f.window.resizableY);
                    window.setIntArray("limits", new int[] {f.window.minWidth, f.window.minHeight, f.window.maxWidth, f.window.maxHeight});
                    if (f.window.menu != 0) window.setInteger("menu", f.window.menu);
                    if (f.window.master != 0) window.setInteger("master", f.window.master);
                    if (f.window.hiddenWithMaster) window.setBoolean("hiddenWithMaster", true);
                    n.setTag("window", window);
                }
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

    public static TkTree load(NBTTagCompound state, int width, int height, Runnable onChange) { return load(state, width, height, onChange, null); }

    public static TkTree load(NBTTagCompound state, int width, int height, Runnable onChange, TkImageStorage storage) {
        if (state.getInteger("version") < 1 || state.getInteger("version") > 6) throw new IllegalArgumentException("unknown Tk tree version");
        NBTTagList nodes = state.getTagList("nodes", 10);
        if (nodes.tagCount() < 1 || nodes.tagCount() > TkTree.MAX_WIDGETS + 1) throw new IllegalArgumentException("invalid widget count");
        // A failed restore never exposes a half-built tree to its tile.
        TkTree tree = new TkTree(); tree.resize(width, height);
        loadImages(tree, state, storage);
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
                    case LABELFRAME: w = new Labelframe(f, n.getBoolean("ttk")); break;
                    case NOTEBOOK: w = new Notebook(f); break;
                    case LABEL: w = new Label(f, ""); break;
                    case BUTTON: w = new Button(f, ""); break;
                    case ENTRY: w = new Entry(f); break;
                    case SPINBOX: w = new Spinbox(f, n.getBoolean("ttk")); break;
                    case COMBOBOX: w = new Combobox(f); break;
                    case TREEVIEW: w = new Treeview(f); break;
                    case MENU: w = new Menu(f); break;
                    case PANEDWINDOW: w = new Panedwindow(f, n.getBoolean("ttk")); break;
                    case MENUBUTTON: w = new Menubutton(f, n.getBoolean("ttk")); break;
                    case MESSAGE: w = new Message(f); break;
                    case CHECKBUTTON: w = new Checkbutton(f, false); break;
                    case RADIOBUTTON: w = new Checkbutton(f, true); break;
                    case LISTBOX: w = new Listbox(f); break;
                    case SCROLLBAR: w = new Scrollbar(f); break;
                    case SCALE: w = new Scale(f); break;
                    case PROGRESSBAR: w = new Progressbar(f); break;
                    case SEPARATOR: w = new Separator(f); break;
                    case SIZEGRIP: w = new Sizegrip(f); break;
                    case CANVAS: w = new Canvas(f); break;
                    case TEXT: w = new Text(f); break;
                    default: throw new IllegalArgumentException("unknown widget kind");
                }
            }
            Map<String, Object> options = new HashMap<>();
            for (String key : new String[] {"padx", "pady", "borderwidth", "background", "foreground"}) options.put(key, n.getInteger(key));
            if (n.hasKey("padding", 11)) {
                int[] padding = n.getIntArray("padding");
                if (padding.length != 4) throw new IllegalArgumentException("invalid widget padding");
                options.put("padding", new Object[] {padding[0], padding[1], padding[2], padding[3]});
            }
            if (n.hasKey("relief", 8)) options.put("relief", n.getString("relief"));   // older saves: the class default
            options.put("anchor", n.getString("anchor")); options.put("state", n.getString("state"));
            if (w instanceof Label) {
                options.put("text", n.getString("text")); options.put("width", n.getInteger("columns"));
                if (!(w instanceof Combobox)) options.put("height", n.getInteger("lines"));   // a Combobox's -height is its list's
            }
            if (w instanceof Label && !(w instanceof Entry)) options.put("wraplength", n.getInteger("wraplength"));
            if (w instanceof Message) options.put("width", n.getInteger("wraplength"));
            if (w instanceof Button || w instanceof Notebook) options.put("command", n.getInteger("command"));
            // An image that did not survive (older saves kept deleted ids) just leaves the widget blank.
            if (n.hasKey("image", 3) && tree.images.containsKey(n.getInteger("image"))) options.put("image", n.getInteger("image"));
            if (w instanceof Frame) options.put("propagate", n.getBoolean("propagate"));
            if (w instanceof Labelframe) {
                options.put("text", n.getString("text"));
                options.put("labelanchor", n.getString("labelanchor").toLowerCase(java.util.Locale.ROOT));
            }
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
            if (n.hasKey("window", 10)) {
                if (!(w instanceof Frame) || w.parent != tree.root() || w.pack != null || w.grid != null || w.savedGrid != null)
                    throw new IllegalArgumentException("a window must be a frame on the display root");
                NBTTagCompound window = n.getCompoundTag("window");
                TkWindow windowState = new TkWindow();
                windowState.title = window.getString("title");
                if (!TkScene.validTitle(windowState.title)) throw new IllegalArgumentException("invalid window title");
                windowState.lastFocus = window.getInteger("lastFocus");
                windowState.program = window.getInteger("program");   // 0 before version 6: no program's
                if (windowState.program < 0) throw new IllegalArgumentException("invalid window program");
                if (window.hasKey("restore", 10)) windowState.restore = TkGeometryPersistence.placeFromTag(window.getCompoundTag("restore"));
                if (window.hasKey("normal", 10)) windowState.normal = TkGeometryPersistence.placeFromTag(window.getCompoundTag("normal"));
                windowState.iconic = window.getBoolean("iconic");
                // Saves before minimize/maximize have none of these: resizable, 1x1 minimum, no maximum.
                if (window.hasKey("resizableX", 1)) windowState.resizableX = window.getBoolean("resizableX");
                if (window.hasKey("resizableY", 1)) windowState.resizableY = window.getBoolean("resizableY");
                if (window.hasKey("limits", 11)) {
                    int[] limits = window.getIntArray("limits");
                    if (limits.length != 4 || limits[0] < 1 || limits[1] < 1 || limits[0] > 8192 || limits[1] > 8192
                            || limits[2] < 0 || limits[3] < 0 || limits[2] > 8192 || limits[3] > 8192)
                        throw new IllegalArgumentException("invalid window size limits");
                    windowState.minWidth = limits[0]; windowState.minHeight = limits[1]; windowState.maxWidth = limits[2]; windowState.maxHeight = limits[3];
                }
                if (w.place != null && !TkWindow.canonical(w.place) || windowState.restore != null && !TkWindow.canonical(windowState.restore)
                        || windowState.normal != null && !TkWindow.canonical(windowState.normal)
                        || w.place != null && windowState.restore != null || w.place != null && windowState.iconic)
                    throw new IllegalArgumentException("invalid window placement");
                windowState.menu = Math.max(0, window.getInteger("menu"));   // a Menu by id, looked up when drawn
                windowState.master = Math.max(0, window.getInteger("master"));   // wm transient, by id
                windowState.hiddenWithMaster = window.getBoolean("hiddenWithMaster");
                ((Frame) w).window = windowState;
            }
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
            restack(frame, records.get(w.id).getIntArray("stack"));
            if (frame instanceof Panedwindow) TkControlPersistence.loadPanes((Panedwindow) frame, records.get(w.id));
            if (frame instanceof Notebook) {
                // Pages come after their notebook: their tabs are put back once they all exist.
                Notebook book = (Notebook) frame; NBTTagList tabs = records.get(w.id).getTagList("tabs", 10);
                for (int t = 0; t < tabs.tagCount(); t++) {
                    NBTTagCompound tag = tabs.getCompoundTagAt(t);
                    TkWidget page = tree.get(tag.getInteger("page"));
                    if (page == null || page.parent != book || !page.manager().isEmpty() || TkWindow.is(page)) throw new IllegalArgumentException("invalid notebook page");
                    Map<String, Object> options = new HashMap<>();
                    options.put("text", tag.getString("text")); options.put("state", tag.getString("state")); options.put("sticky", tag.getString("sticky"));
                    int[] padding = tag.getIntArray("padding");
                    if (padding.length != 4) throw new IllegalArgumentException("invalid notebook page");
                    options.put("padding", new Object[] {padding[0], padding[1], padding[2], padding[3]});
                    options.put("underline", tag.getInteger("underline")); options.put("image", tag.getString("image")); options.put("compound", tag.getString("compound"));
                    book.restoreTab(page, options);
                }
                int current = records.get(w.id).getInteger("current");
                if (current < -1 || current >= book.tabs.size() || current >= 0 && book.tabs.get(current).state != Notebook.NORMAL)
                    throw new IllegalArgumentException("invalid current tab");
                book.current = current;
            }
            if (frame instanceof Labelframe && records.get(w.id).hasKey("labelwidget", 3)) {
                TkWidget label = tree.get(records.get(w.id).getInteger("labelwidget"));
                if (label == null || label.parent != frame || !label.manager().isEmpty()) throw new IllegalArgumentException("invalid label widget");
                ((Labelframe) frame).labelWidget(label);
            }
        }
        if (TkWindow.windows(tree.root()).size() > TkWindow.MAX_WINDOWS) throw new IllegalArgumentException("too many windows");
        tree.nextId = state.getInteger("nextId");
        if (tree.nextId <= previous) throw new IllegalArgumentException("invalid next widget ID");
        tree.render(); int focus = state.getInteger("focus");
        // Best effort: the saved focus may no longer be focusable at the restored size
        // (a clipped or unmapped widget). Losing focus beats discarding the whole tree.
        if (focus != 0) try { tree.focus(focus); } catch (IllegalArgumentException unfocusable) { /* keep 0 */ }
        tree.setOnChange(onChange); return tree;
    }

    /** Images before widgets, which refer to them. Pixels that cannot be read come back transparent. */
    private static void loadImages(TkTree tree, NBTTagCompound state, TkImageStorage storage) {
        NBTTagList images = state.getTagList("images", 10);
        if (images.tagCount() > TkImage.MAX_IMAGES) throw new IllegalArgumentException("too many images");
        long pixels = 0; int maximum = 0;
        for (int i = 0; i < images.tagCount(); i++) {
            NBTTagCompound n = images.getCompoundTagAt(i);
            int id = TkOptions.integer(n.getInteger("id"), 1, Integer.MAX_VALUE - 1), w = n.getInteger("width"), h = n.getInteger("height");
            long version = n.getLong("version");
            TkImage.check(w, h);
            if (version < 1 || tree.images.containsKey(id)) throw new IllegalArgumentException("invalid saved image");
            pixels += (long) w * h;
            if (pixels > TkImage.MAX_PIXELS) throw new IllegalArgumentException("image pixel budget exceeded");
            int[] argb;
            boolean stored = false;
            try {
                byte[] packed = n.hasKey("packed", 7) ? n.getByteArray("packed") : storage == null ? null : storage.read(id);
                stored = packed != null && !n.hasKey("packed", 7);
                argb = packed == null ? new int[w * h] : TkImageCodec.unpack(packed, w, h);
                if (packed == null) reobf.mpy4oc.main.MyMod.LOG.warn("Tk image {} has no saved pixels; it is blank", id);
            } catch (java.io.IOException unreadable) {
                // Perhaps only for now (a lock, an I/O error): the blank stand-in must not
                // overwrite the file, so it counts as saved until the program changes it.
                reobf.mpy4oc.main.MyMod.LOG.warn("Tk image " + id + " could not be read; it is blank", unreadable);
                argb = new int[w * h]; stored = true;
            } catch (IllegalArgumentException damaged) {
                reobf.mpy4oc.main.MyMod.LOG.warn("Tk image " + id + " is damaged; it is blank", damaged);
                argb = new int[w * h];
            }
            TkImage image = new TkImage(id, w, h, argb, version);
            image.program = n.getInteger("program");
            if (image.program < 0) throw new IllegalArgumentException("invalid image program");
            if (stored) image.savedVersion = version;
            tree.images.put(id, image);
            maximum = Math.max(maximum, id);
        }
        tree.nextImage = Math.max(1, state.getInteger("nextImage"));
        if (tree.nextImage <= maximum) throw new IllegalArgumentException("invalid next image ID");
    }

    /** Restore a saved stacking order; windows always end up above the root's other children. */
    private static void restack(Frame frame, int[] order) {
        if (order.length != 0) {
            if (order.length != frame.children.size()) throw new IllegalArgumentException("invalid stacking order");
            java.util.List<TkWidget> stacked = new java.util.ArrayList<>();
            for (int id : order) {
                TkWidget child = frame.tree.get(id);
                if (child == null || child.parent != frame || stacked.contains(child)) throw new IllegalArgumentException("invalid stacking order");
                stacked.add(child);
            }
            frame.children.clear(); frame.children.addAll(stacked);
        }
        if (frame.parent == null) {
            java.util.List<TkWidget> windows = new java.util.ArrayList<>();
            for (TkWidget child : frame.children) if (TkWindow.is(child)) windows.add(child);
            frame.children.removeAll(windows); frame.children.addAll(windows);
        }
    }
}
