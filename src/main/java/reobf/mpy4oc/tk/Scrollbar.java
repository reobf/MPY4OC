package reobf.mpy4oc.tk;

public final class Scrollbar extends TkWidget {
    boolean horizontal;
    double first, last = 1;
    int command, grab = -1;
    public Scrollbar(Frame parent) { super(parent.tree, parent, Kind.SCROLLBAR); width = 16; height = 100; border = 1; relief = Relief.SUNKEN; }
    public void set(double first, double last) {
        alive(); if (!Double.isFinite(first) || !Double.isFinite(last)) throw new IllegalArgumentException("finite fractions required");
        this.first = Math.max(0, Math.min(1, first)); this.last = Math.max(this.first, Math.min(1, last)); tree.changed(false);
    }
    int length() { return horizontal ? bounds.width : bounds.height; }
    int arrow() { return Math.min(16, length() / 3); }
    int track() { return Math.max(1, length() - 2 * arrow()); }
    int thumbSize() { return Math.min(track(), Math.max(8, (int) Math.round(track() * (last - first)))); }
    int thumbStart() { return arrow() + (int) Math.round((track() - thumbSize()) * (last - first >= 1 ? 0 : first / (1 - last + first))); }
    int point(int x, int y) { return horizontal ? x - bounds.x : y - bounds.y; }
    void press(int x, int y) {
        int p = point(x, y); grab = -1;
        if (p < arrow()) tree.queue(this, command, "scroll", -1, "units");
        else if (p >= length() - arrow()) tree.queue(this, command, "scroll", 1, "units");
        else if (p < thumbStart()) tree.queue(this, command, "scroll", -1, "pages");
        else if (p >= thumbStart() + thumbSize()) tree.queue(this, command, "scroll", 1, "pages");
        else grab = p - thumbStart();
    }
    /** Classic Tk's element at a point relative to the widget: arrow1, trough1, slider, trough2, arrow2, or "". */
    String identify(int x, int y) {
        if (x < 0 || y < 0 || x >= bounds.width || y >= bounds.height) return "";
        int p = horizontal ? x : y;
        if (p < arrow()) return "arrow1";
        if (p >= length() - arrow()) return "arrow2";
        if (p < thumbStart()) return "trough1";
        return p < thumbStart() + thumbSize() ? "slider" : "trough2";
    }
    /** The trough length Tk's delta and fraction divide by (tkScrollbar.c: length - 1 - 2 * arrow). */
    private double span() { return Math.max(1, length() - 1 - 2 * arrow()); }
    /** The change in the first fraction for moving the slider (dx, dy) pixels (Tk's delta; the view does not matter). */
    double delta(int dx, int dy) { return (horizontal ? dx : dy) / span(); }
    /** Where (x, y) lies along the trough, 0..1 (Tk's fraction: moveto there puts the slider's top at the point). */
    double fraction(int x, int y) { return Math.max(0, Math.min(1, ((horizontal ? x : y) - arrow()) / span())); }
    void drag(int x, int y) {
        if (grab >= 0) {
            double fraction = (point(x, y) - arrow() - grab) / (double) Math.max(1, track() - thumbSize()) * (1 - last + first);
            tree.queue(this, command, "moveto", Math.max(0, Math.min(1, fraction)));
        }
    }
}
