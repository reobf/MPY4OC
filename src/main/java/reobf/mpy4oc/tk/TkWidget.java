package reobf.mpy4oc.tk;

/** Server-owned widget. A tree owns IDs and invalidates layout on mutation. */
public abstract class TkWidget {
    public enum Kind { FRAME, LABEL, BUTTON, ENTRY, CHECKBUTTON, RADIOBUTTON, LISTBOX, SCROLLBAR, SCALE, MESSAGE, CANVAS, TEXT }
    public final TkTree tree;
    public final int id;
    public final Frame parent;
    public final Kind kind;
    int width, height, requestedWidth, requestedHeight, padx, pady, border;
    int background = 0xD9D9D9, foreground = 0x000000;
    boolean enabled = true;
    TkPack pack;
    TkGrid grid, savedGrid;
    TkPlace place;
    TkPack.Anchor textAnchor = TkPack.Anchor.CENTER;
    TkRect bounds = TkRect.EMPTY, clip = TkRect.EMPTY;
    final java.util.Map<String, Integer> bindings = new java.util.LinkedHashMap<>();

    TkWidget(TkTree tree, Frame parent, Kind kind) {
        this.tree = tree; this.parent = parent; this.kind = kind;
        id = tree.register(this);
        if (parent != null) parent.children.add(this);
        tree.changed(true);
    }

    public final TkRect bounds() { alive(); tree.render(); return bounds; }
    public final boolean mapped() { return !bounds().empty(); }
    public final boolean enabled() { return enabled; }
    public String text() { return ""; }

    /** Explicit requested dimensions in pixels; zero selects the natural size. */
    public final TkWidget size(int width, int height) {
        alive();
        if (width < 0 || width > 8192 || height < 0 || height > 8192)
            throw new IllegalArgumentException("requested size must be 0..8192");
        this.width = width; this.height = height; tree.changed(true); return this;
    }

    public final TkWidget colors(int background, int foreground) {
        alive(); checkColor(background); checkColor(foreground);
        this.background = background; this.foreground = foreground; tree.changed(false); return this;
    }

    public final TkWidget padding(int padx, int pady, int border) {
        alive();
        if (padx < 0 || padx > 1024 || pady < 0 || pady > 1024 || border < 0 || border > 16)
            throw new IllegalArgumentException("invalid widget padding or border");
        this.padx = padx; this.pady = pady; this.border = border; tree.changed(true); return this;
    }

    public final TkWidget textAnchor(TkPack.Anchor anchor) {
        alive(); textAnchor = java.util.Objects.requireNonNull(anchor); tree.changed(false); return this;
    }

    public final TkWidget enabled(boolean enabled) {
        alive(); this.enabled = enabled; tree.cancelUnavailable(); tree.changed(false); return this;
    }

    public final TkWidget pack(TkPack options) {
        alive();
        if (parent == null) throw new IllegalArgumentException("cannot pack the root");
        java.util.Objects.requireNonNull(options);
        for (TkWidget other : parent.gridded) if (other != this)
            throw new IllegalArgumentException("cannot mix pack and grid in the same parent");
        if (pack == null) unmanage();
        pack = options;
        if (!parent.packed.contains(this)) parent.packed.add(this);
        tree.changed(true); return this;
    }

    public final TkWidget pack() { return pack(TkPack.DEFAULT); }
    public final TkWidget grid(TkGrid options) {
        alive(); if (parent == null) throw new IllegalArgumentException("cannot grid the root");
        java.util.Objects.requireNonNull(options);
        for (TkWidget other : parent.packed) if (other != this)
            throw new IllegalArgumentException("cannot mix pack and grid in the same parent");
        if (grid == null) unmanage();
        grid = savedGrid = options;
        if (!parent.gridded.contains(this)) parent.gridded.add(this);
        tree.changed(true); return this;
    }
    public final TkWidget place(TkPlace options) {
        alive(); if (parent == null) throw new IllegalArgumentException("cannot place the root");
        java.util.Objects.requireNonNull(options); unmanage(); place = options; tree.changed(true); return this;
    }
    public final void gridForget(boolean remember) {
        alive(); if (grid != null) { TkGrid previous = grid; unmanage(); if (remember) savedGrid = previous; tree.changed(true); }
        else if (!remember && savedGrid != null) { savedGrid = null; tree.changed(true); }
    }
    public final String manager() { return pack != null ? "pack" : grid != null ? "grid" : place != null ? "place" : ""; }
    private void unmanage() {
        if (parent != null) { parent.packed.remove(this); parent.gridded.remove(this); }
        pack = null; grid = savedGrid = null; place = null;
    }
    public final void forget() {
        alive();
        if (parent != null) { unmanage(); tree.changed(true); }
    }
    public final void destroy() { alive(); tree.destroy(this); }
    final void alive() { tree.require(this); }
    static void checkColor(int color) {
        if (color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException("invalid RGB color");
    }
}
