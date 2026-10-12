package reobf.mpy4oc.tk;

/** ttk.Separator: an etched 2 px line along its orientation (pack it with fill to stretch it). */
public final class Separator extends TkWidget {
    boolean horizontal = true;
    public Separator(Frame parent) { super(parent.tree, parent, Kind.SEPARATOR); }

    /** The line itself: 2 px across the middle of the widget, its full length along it. */
    TkRect line() {
        return horizontal ? new TkRect(bounds.x, bounds.y + (bounds.height - 2) / 2, bounds.width, Math.min(2, bounds.height))
                : new TkRect(bounds.x + (bounds.width - 2) / 2, bounds.y, Math.min(2, bounds.width), bounds.height);
    }
}
