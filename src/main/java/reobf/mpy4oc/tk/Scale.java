package reobf.mpy4oc.tk;

public final class Scale extends TkWidget {
    boolean horizontal, showvalue = true;
    double from, to = 100, resolution = 1, value;
    int command, length = 100;
    public Scale(Frame parent) { super(parent.tree, parent, Kind.SCALE); border = 1; }
    public double get() { return value; }
    public void set(double n, boolean notify) {
        alive(); if (!Double.isFinite(n)) throw new IllegalArgumentException("finite value required");
        if (resolution > 0) n = from + Math.rint((n - from) / resolution) * resolution;
        n = Math.max(Math.min(from, to), Math.min(Math.max(from, to), n));
        if (value != n) { value = n; tree.changed(false); if (notify) tree.queue(this, command, value); }
    }
    int trackLength() { return Math.max(1, (horizontal ? bounds.width : bounds.height) - 16); }
    int position() { return 8 + (int) Math.round((from == to ? 0 : (value - from) / (to - from)) * trackLength()); }
    /** The value under a point relative to the widget (ttk.Scale.get(x, y)). */
    double valueAt(int x, int y) {
        double t = Math.max(0, Math.min(1, ((horizontal ? x : y) - 8) / (double) trackLength()));
        return from + (to - from) * t;
    }
    /** The slider centre for {@code n}, relative to the widget (Tk's coords). */
    int[] coords(double n) {
        double t = from == to ? 0 : Math.max(0, Math.min(1, (n - from) / (to - from)));
        int along = 8 + (int) Math.round(t * trackLength());
        return horizontal ? new int[] {along, bounds.height - 10} : new int[] {bounds.width - 10, along};
    }
    void drag(int x, int y) { set(from + (to - from) * Math.max(0, Math.min(1, ((horizontal ? x - bounds.x : y - bounds.y) - 8) / (double) trackLength())), true); }
    void key(int code) {
        double step = resolution > 0 ? resolution : Math.abs(to - from) / 100;
        if (to < from) step = -step;
        if (code == 203 || code == 200) set(value - step, true);
        else if (code == 205 || code == 208) set(value + step, true);
        else if (code == 199) set(from, true); else if (code == 207) set(to, true);
    }
}
