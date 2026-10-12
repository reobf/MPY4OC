package reobf.mpy4oc.tk;

/**
 * ttk.Progressbar: a bar filled to value/maximum (determinate), or a block bouncing along
 * the trough as the value grows (indeterminate: the value is read modulo maximum, one
 * full sweep there and back every two maxima). Display only: it takes no input or focus.
 * start()/stop() are Python timers calling step(), as Tk's are Tcl timers.
 */
public final class Progressbar extends TkWidget {
    boolean horizontal = true, indeterminate;
    double maximum = 100, value;
    int length = 100;
    public Progressbar(Frame parent) {
        super(parent.tree, parent, Kind.PROGRESSBAR);
        border = 1; relief = Relief.SUNKEN; background = 0xE6E6E6; foreground = 0x06B025;
    }

    void value(double n) {
        if (!Double.isFinite(n) || Math.abs(n) > 1e9) throw new IllegalArgumentException("finite value in -1e9..1e9 required");
        if (value != n) { value = n; tree.changed(false); }
    }

    /**
     * Tk's step: add {@code amount}. A determinate bar then wraps with fmod (C's, sign kept),
     * as Tk's does; an indeterminate one keeps counting (its block goes back and forth),
     * folded by whole periods only if it would leave the value range.
     */
    double step(double amount) {
        alive();
        if (!Double.isFinite(amount) || Math.abs(amount) > 1e9) throw new IllegalArgumentException("finite step in -1e9..1e9 required");
        double next = value + amount;
        if (!indeterminate) next %= maximum;
        else if (Math.abs(next) > 1e9) {
            next %= 2 * maximum;
            if (next > maximum) next -= 2 * maximum; else if (next < -maximum) next += 2 * maximum;
        }
        value(next);
        return value;
    }

    int trough() { return Math.max(0, (horizontal ? bounds.width : bounds.height) - 2 * border); }

    /** The filled part along the trough: {start, size} in pixels from the trough's start. */
    int[] bar() {
        int trough = trough();
        if (maximum <= 0 || trough == 0) return new int[] {0, 0};
        if (!indeterminate) {
            int size = (int) Math.round(Math.max(0, Math.min(1, value / maximum)) * trough);
            // Vertical bars fill from the bottom, as Tk's do.
            return horizontal ? new int[] {0, size} : new int[] {trough - size, size};
        }
        int size = Math.max(1, Math.min(trough, Math.max(10, trough / 5)));
        double cycle = ((value % (2 * maximum)) + 2 * maximum) % (2 * maximum) / maximum;
        double t = cycle <= 1 ? cycle : 2 - cycle;
        return new int[] {(int) Math.round(t * (trough - size)), size};
    }
}
