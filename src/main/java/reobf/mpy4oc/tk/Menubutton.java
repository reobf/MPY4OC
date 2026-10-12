package reobf.mpy4oc.tk;

/**
 * Tk's menubutton (classic) and ttk::menubutton: a label that posts its -menu when pressed,
 * below it by default (-direction). Classic with -indicatoron shows a small raised bar on the
 * right; ttk always shows a down arrow there.
 */
public final class Menubutton extends Label {
    static final String[] DIRECTIONS = {"above", "below", "left", "right", "flush"};
    /** The indicator's column on the right (classic indicatoron, ttk always). */
    static final int INDICATOR = 16;
    final boolean ttk;
    /** -menu: the Menu it posts (an id), 0 for none. */
    int menu;
    String direction = "below";
    /** Classic -indicatoron. */
    boolean indicator;

    Menubutton(Frame parent, boolean ttk) {
        super(parent, "", Kind.MENUBUTTON);
        this.ttk = ttk;
        if (ttk) { padLeft = padRight = 3; padTop = padBottom = 3; border = 1; relief = Relief.RAISED; }
        else { padLeft = padRight = 5; padTop = padBottom = 4; border = 1; relief = Relief.FLAT; }
    }

    boolean showsIndicator() { return ttk || indicator; }

    /** The label ends before the indicator. */
    @Override int insetRight() { return super.insetRight() + (showsIndicator() ? INDICATOR : 0); }

    /** Its menu, if that is still a Menu. */
    Menu menu() {
        TkWidget m = menu == 0 ? null : tree.get(menu);
        return m instanceof Menu ? (Menu) m : null;
    }

    /** Where its menu goes when posted (Tk's MbPost): the box's top left for a menu of that size, on a display that size. */
    int[] postAt(int width, int height) {
        TkRect b = bounds;
        switch (direction) {
            case "above": return new int[] {b.x, b.y - height};
            case "left": return new int[] {b.x - width, b.y};
            case "right": return new int[] {b.x + b.width, b.y};
            case "flush": return new int[] {b.x, b.y};
            default: return new int[] {b.x, b.y + b.height};
        }
    }
}
