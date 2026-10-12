package reobf.mpy4oc.tk;

/**
 * ttk.Sizegrip: a grip in the bottom right corner; dragging it resizes the Toplevel it is in,
 * as dragging that window's bottom right corner does. On the desktop (the display root, which
 * the player cannot resize) or in a maximized or fixed-size window it does nothing.
 */
public final class Sizegrip extends TkWidget {
    /** Tk's default theme grip is 15x15. */
    static final int SIZE = 15;
    /** Grip dots (2x2, from the bottom right corner's 15x15 square): a triangle of three rows. */
    static final int[][] DOTS = {{11, 3}, {7, 7}, {11, 7}, {3, 11}, {7, 11}, {11, 11}};
    public Sizegrip(Frame parent) { super(parent.tree, parent, Kind.SIZEGRIP); }

    /** The edges a press here resizes in its window (none when there is nothing to resize). */
    int edges() {
        Frame window = TkWindow.of(this);
        if (window == null || window.place == null || window.window.zoomed()) return 0;
        return (window.window.resizableX ? TkWindow.RIGHT : 0) | (window.window.resizableY ? TkWindow.BOTTOM : 0);
    }
}
