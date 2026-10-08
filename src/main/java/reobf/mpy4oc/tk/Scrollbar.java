package reobf.mpy4oc.tk;

public final class Scrollbar extends TkWidget {
    boolean horizontal;
    double first, last = 1;
    int command, grab = -1;
    public Scrollbar(Frame parent) { super(parent.tree, parent, Kind.SCROLLBAR); width = 16; height = 100; border = 1; }
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
    void drag(int x, int y) {
        if (grab >= 0) {
            double fraction = (point(x, y) - arrow() - grab) / (double) Math.max(1, track() - thumbSize()) * (1 - last + first);
            tree.queue(this, command, "moveto", Math.max(0, Math.min(1, fraction)));
        }
    }
}
