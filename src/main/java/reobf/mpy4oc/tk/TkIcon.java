package reobf.mpy4oc.tk;

/** Common geometry and descriptor handling for the two registry-backed icons. */
abstract class TkIcon extends TkWidget {
    TkIconDescriptor descriptor;
    TkIcon(Frame parent, Kind kind) { super(parent.tree, parent, kind); width = height = 24; }
    String option() { return kind == Kind.ITEMICON ? "item" : "fluid"; }
    void checkDescriptor(TkIconDescriptor next) {
        int bytes = next == null ? 0 : next.bytes();
        for (TkWidget w : tree.widgets.values()) if (w != this && w instanceof TkIcon && ((TkIcon) w).descriptor != null)
            bytes += ((TkIcon) w).descriptor.bytes();
        if (bytes > TkIconDescriptor.MAX_TREE_BYTES) throw new IllegalArgumentException("icon descriptor budget exceeded");
    }
    void descriptor(TkIconDescriptor next) {
        if (!java.util.Objects.equals(descriptor, next)) { descriptor = next; tree.changed(false); }
    }
    /** Fit a square in the content rectangle, honoring the common anchor option. */
    TkRect iconBox() {
        TkRect r = inner(); int side = Math.min(r.width, r.height);
        return new TkRect(r.x + (r.width - side) * textAnchor.x / 2, r.y + (r.height - side) * textAnchor.y / 2, side, side);
    }
}
