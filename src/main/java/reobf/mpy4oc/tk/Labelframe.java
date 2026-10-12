package reobf.mpy4oc.tk;

import java.util.Locale;

/**
 * Tk's labelframe: a Frame with a title on one side of its border (-text), or a child widget in
 * the title's place (-labelwidget). The classic LabelFrame and ttk::labelframe lay the label out
 * differently; both are here, following Tk 8.6 (default theme). The label's band is part of the
 * frame's insets, so pack, grid and place lay the children out below it.
 */
public final class Labelframe extends Frame {
    /** Tk's -labelanchor: the side the label is on, and where along it. */
    public enum Anchor {
        NW(0, 0), N(0, 1), NE(0, 2), EN(1, 0), E(1, 1), ES(1, 2), SE(2, 2), S(2, 1), SW(2, 0), WS(3, 2), W(3, 1), WN(3, 0);
        /** 0 top, 1 right, 2 bottom, 3 left. */
        final int side;
        /** 0 at the start (left or top), 1 centred, 2 at the end. */
        final int along;
        Anchor(int side, int along) { this.side = side; this.along = along; }
        public String option() { return name().toLowerCase(Locale.ROOT); }
    }

    /** ttk::labelframe rather than the classic one: they place the label differently. */
    public final boolean ttk;
    String text = "";
    Anchor labelAnchor = Anchor.NW;
    /** The child shown as the label (managed here, not by pack/grid/place), or null. */
    TkWidget labelWidget;

    public Labelframe(Frame parent, boolean ttk) {
        super(parent.tree, parent, Kind.LABELFRAME);
        this.ttk = ttk;
        border = 2; relief = Relief.GROOVE;   // both Tk's LabelFrame and the default theme's TLabelframe
    }

    @Override public String text() { return text; }
    @Override String containerManager() { return "labelframe"; }
    @Override void release(TkWidget child) {
        if (child == labelWidget) { labelWidget = null; child.container = null; tree.changed(true); }
    }

    Labelframe text(String value) {
        alive(); TkFontMetrics.checkText(value); tree.checkTextBudget(this, value);
        text = value; tree.changed(true); return this;
    }
    Labelframe labelAnchor(Anchor value) { alive(); labelAnchor = java.util.Objects.requireNonNull(value); tree.changed(true); return this; }

    /** Show widget (a child of this frame, or null for the text) as the label. */
    Labelframe labelWidget(TkWidget widget) {
        alive();
        if (widget != null && (widget.parent != this || TkWindow.is(widget)))
            throw new IllegalArgumentException("a labelwidget must be a child of its labelframe");
        if (widget == labelWidget) return this;
        TkWidget old = labelWidget;
        labelWidget = null;
        if (old != null) old.container = null;   // no longer managed: unmapped, as in Tk
        if (widget != null) { widget.unmanage(); widget.container = this; }
        labelWidget = widget;
        tree.changed(true); return this;
    }

    /** Tk's error messages differ between the two. */
    static Anchor anchor(String value, boolean ttk) {
        for (Anchor anchor : Anchor.values()) if (anchor.option().equals(value)) return anchor;
        throw new IllegalArgumentException(ttk ? "Bad label anchor specification " + value
                : "bad labelanchor \"" + value + "\": must be e, en, es, n, ne, nw, s, se, sw, w, wn, or ws");
    }

    /** Whether the label is on the top or bottom side. */
    boolean vertical() { return labelAnchor.side % 2 == 0; }

    /**
     * The label's natural width and height: the label widget's, or the text's (the classic one keeps
     * 1 px around it; ttk reserves one character even for no text). Null: no label at all, which is
     * a classic labelframe without text.
     */
    int[] labelSize() {
        if (labelWidget != null) return new int[] {labelWidget.requestedWidth, labelWidget.requestedHeight};
        if (text.isEmpty()) return ttk ? new int[] {TkFontMetrics.CELL_WIDTH, TkFontMetrics.LINE_HEIGHT} : null;
        int width = TkFontMetrics.width(text), height = TkFontMetrics.height(text);
        return ttk ? new int[] {width, height} : new int[] {width + 2, height + 2};
    }

    private int across(int[] size) { return vertical() ? size[1] : size[0]; }
    private int alongSize(int[] size) { return vertical() ? size[0] : size[1]; }
    /** How far the label sits from the corner along its side. */
    private int lead() { return ttk ? 8 : border + (border > 0 ? 4 : 0); }

    /** The band the label takes on its side: in place of the border (classic), or before it (ttk). */
    int band() {
        int[] size = labelSize();
        if (size == null) return border;
        return ttk ? across(size) + border : Math.max(across(size), border);
    }

    @Override int insetTop() { return labelAnchor.side == 0 ? band() + padTop : super.insetTop(); }
    @Override int insetRight() { return labelAnchor.side == 1 ? band() + padRight : super.insetRight(); }
    @Override int insetBottom() { return labelAnchor.side == 2 ? band() + padBottom : super.insetBottom(); }
    @Override int insetLeft() { return labelAnchor.side == 3 ? band() + padLeft : super.insetLeft(); }

    /** The least size along the label's side, so that the label fits between the corners; 0 without one. */
    int minAlong() {
        int[] size = labelSize();
        if (size == null) return 0;
        return ttk ? alongSize(size) + 16 + 2 * border : alongSize(size) + 2 * lead();
    }

    /** Where the label goes (display coordinates), or null without one. A classic label is at least as thick as the border. */
    TkRect labelBox() {
        int[] size = labelSize();
        if (size == null) return null;
        int w = size[0], h = size[1];
        if (!ttk) { if (vertical()) h = Math.max(h, border); else w = Math.max(w, border); }
        int width = bounds.width, height = bounds.height, lead = lead(), length = vertical() ? width : height, own = vertical() ? w : h;
        int at = labelAnchor.along == 0 ? lead : labelAnchor.along == 1 ? (length - own) / 2 : length - lead - own;
        int x, y;
        switch (labelAnchor.side) {
            case 0: x = at; y = 0; break;
            case 1: x = width - w; y = at; break;
            case 2: x = at; y = height - h; break;
            default: x = 0; y = at;
        }
        return new TkRect(bounds.x + x, bounds.y + y, w, h);
    }

    /** Where the border is drawn: moved in on the label's side so that it runs through the label's middle. */
    TkRect borderBox() {
        int[] size = labelSize();
        int shift = 0;
        if (size != null) {
            int t = across(size);
            shift = ttk ? t - t / 2 : t <= border ? 0 : labelAnchor.side == 0 ? (t - border + 1) / 2 : (t - border) / 2;
        }
        int top = labelAnchor.side == 0 ? shift : 0, right = labelAnchor.side == 1 ? shift : 0;
        int bottom = labelAnchor.side == 2 ? shift : 0, left = labelAnchor.side == 3 ? shift : 0;
        return new TkRect(bounds.x + left, bounds.y + top, Math.max(0, bounds.width - left - right), Math.max(0, bounds.height - top - bottom));
    }

    /** The text's origin in its box. */
    int textInset() { return ttk ? 0 : 1; }
}
