package reobf.mpy4oc.tk;

/** Radio groups use stable Python variable IDs, not Java callback objects. */
public final class Checkbutton extends Button {
    boolean selected;
    int group;
    public Checkbutton(Frame parent, boolean radio) {
        super(parent, "", radio ? Kind.RADIOBUTTON : Kind.CHECKBUTTON);
        border = 0; relief = Relief.FLAT; textAnchor = TkPack.Anchor.W;
    }
    public boolean selected() { return selected; }
    public void selected(boolean value) {
        alive();
        if (value && kind == Kind.RADIOBUTTON && group != 0)
            for (TkWidget w : tree.widgets.values())
                if (w instanceof Checkbutton && w.kind == Kind.RADIOBUTTON && ((Checkbutton) w).group == group)
                    ((Checkbutton) w).selected = false;
        selected = value; tree.changed(false);
    }
    void activate() { if (enabled) selected(kind == Kind.RADIOBUTTON || !selected); }
}
