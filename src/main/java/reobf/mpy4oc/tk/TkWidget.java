package reobf.mpy4oc.tk;

/** Server-owned widget. A tree owns IDs and invalidates layout on mutation. */
public abstract class TkWidget {
    /** Appended only: NBT stores the names, paint records the ordinals (new kinds paint with older ones). */
    public enum Kind { FRAME, LABEL, BUTTON, ENTRY, CHECKBUTTON, RADIOBUTTON, LISTBOX, SCROLLBAR, SCALE, MESSAGE, CANVAS, TEXT, PROGRESSBAR, SEPARATOR, SIZEGRIP, LABELFRAME, NOTEBOOK,
        SPINBOX, COMBOBOX, TREEVIEW, MENU, MENUBUTTON, PANEDWINDOW, ITEMICON, FLUIDICON }
    public final TkTree tree;
    public final int id;
    public final Frame parent;
    public final Kind kind;
    int width, height, requestedWidth, requestedHeight, border;
    /** The space between the border and the content on each side: Tk's -padx/-pady, ttk's -padding. */
    int padLeft, padTop, padRight, padBottom;
    /** Tk's -relief, how the border is drawn; each widget class starts with Tk's default. */
    Relief relief = Relief.FLAT;
    public enum Relief {
        FLAT, RAISED, SUNKEN, GROOVE, RIDGE, SOLID;
        /** The relief in paint records (see TkRelief). */
        public int code() { return ordinal() + 1; }
        public String option() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    int background = 0xD9D9D9, foreground = 0x000000;
    boolean enabled = true;
    TkPack pack;
    TkGrid grid, savedGrid;
    TkPlace place;
    /** The Frame that lays this widget out in place of pack/grid/place (a Labelframe's label widget), or null. */
    Frame container;
    TkPack.Anchor textAnchor = TkPack.Anchor.CENTER;
    TkRect bounds = TkRect.EMPTY, clip = TkRect.EMPTY;
    final java.util.Map<String, Integer> bindings = new java.util.LinkedHashMap<>();
    /** The size (width << 32 | height, 0 unmapped) last reported by {@code <Configure>}; -1 until the first report, so the first binding hears the current size. Never saved. */
    long configured = -1;
    /** Tk's -takefocus: -1 the widget's default (''), 0 never by Tab or click, 1 always (any widget). */
    int takefocus = -1;
    /** ttk: what the widget's style gives the options it draws (null: a classic widget, or nothing set), and the states the program holds (TkLook bits: alternate, invalid, ...). */
    TkLook look;
    int held;

    /** The style's colour for key in the widget's state (or {@code state}), else fallback. */
    final int lookColor(String key, int fallback) { return look == null ? fallback : look.color(key, tree.state(this), fallback); }
    final int lookColor(String key, int state, int fallback) { return look == null ? fallback : look.color(key, state, fallback); }
    final Relief lookRelief(String key, Relief fallback) { return look == null ? fallback : look.relief(key, tree.state(this), fallback); }

    TkWidget(TkTree tree, Frame parent, Kind kind) {
        this.tree = tree; this.parent = parent; this.kind = kind;
        id = tree.register(this);
        if (parent != null) parent.adopt(this);
        tree.changed(true);
    }

    public final TkRect bounds() { alive(); tree.render(); return bounds; }
    public final boolean mapped() { return !bounds().empty(); }
    public final boolean enabled() { return enabled; }
    public String text() { return ""; }
    /** UTF-8 bytes of this widget's text towards the display's text budget (a Notebook's tabs count too). */
    int textBytes() { return text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }

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

    public final TkWidget padding(int padx, int pady, int border) { return padding(padx, pady, padx, pady, border); }

    public final TkWidget padding(int left, int top, int right, int bottom, int border) {
        alive();
        if (left < 0 || left > 1024 || top < 0 || top > 1024 || right < 0 || right > 1024 || bottom < 0 || bottom > 1024 || border < 0 || border > 16)
            throw new IllegalArgumentException("invalid widget padding or border");
        padLeft = left; padTop = top; padRight = right; padBottom = bottom; this.border = border; tree.changed(true); return this;
    }

    public final TkWidget relief(Relief relief) {
        alive(); this.relief = java.util.Objects.requireNonNull(relief); tree.changed(false); return this;
    }

    /** Border plus padding on one side: where the content (a Frame's children) starts. */
    int insetLeft() { return padLeft + border; }
    int insetTop() { return padTop + border; }
    int insetRight() { return padRight + border; }
    int insetBottom() { return padBottom + border; }
    /** Both sides together. */
    final int insetWidth() { return insetLeft() + insetRight(); }
    final int insetHeight() { return insetTop() + insetBottom(); }
    /** The rectangle inside the border and padding. */
    final TkRect inner() {
        return new TkRect(bounds.x + insetLeft(), bounds.y + insetTop(), Math.max(0, bounds.width - insetWidth()), Math.max(0, bounds.height - insetHeight()));
    }

    public final TkWidget textAnchor(TkPack.Anchor anchor) {
        alive(); textAnchor = java.util.Objects.requireNonNull(anchor); tree.changed(false); return this;
    }

    public final TkWidget enabled(boolean enabled) {
        alive(); this.enabled = enabled; tree.cancelUnavailable(); tree.changed(false); return this;
    }

    private void notWindow() {
        if (TkWindow.is(this)) throw new IllegalArgumentException("a Toplevel is positioned with wm geometry, not a geometry manager");
    }

    public final TkWidget pack(TkPack options) {
        alive(); notWindow();
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
        alive(); notWindow(); if (parent == null) throw new IllegalArgumentException("cannot grid the root");
        java.util.Objects.requireNonNull(options);
        for (TkWidget other : parent.packed) if (other != this)
            throw new IllegalArgumentException("cannot mix pack and grid in the same parent");
        if (grid == null) unmanage();
        grid = savedGrid = options;
        if (!parent.gridded.contains(this)) parent.gridded.add(this);
        tree.changed(true); return this;
    }
    public final TkWidget place(TkPlace options) {
        alive(); notWindow(); if (parent == null) throw new IllegalArgumentException("cannot place the root");
        java.util.Objects.requireNonNull(options); unmanage(); place = options; tree.changed(true); return this;
    }
    public final void gridForget(boolean remember) {
        alive(); if (grid != null) { TkGrid previous = grid; unmanage(); if (remember) savedGrid = previous; tree.changed(true); }
        else if (!remember && savedGrid != null) { savedGrid = null; tree.changed(true); }
    }
    public final String manager() {
        return container != null ? container.containerManager() : pack != null ? "pack" : grid != null ? "grid" : place != null ? "place" : "";
    }
    void unmanage() {
        if (container != null) container.release(this);
        if (parent != null) { parent.packed.remove(this); parent.gridded.remove(this); }
        pack = null; grid = savedGrid = null; place = null;
    }
    public final void forget() {
        alive(); notWindow();
        if (parent != null) { unmanage(); tree.changed(true); }
    }
    public final void destroy() { alive(); tree.destroy(this); }
    final void alive() { tree.require(this); }
    static void checkColor(int color) {
        if (color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException("invalid RGB color");
    }
}
