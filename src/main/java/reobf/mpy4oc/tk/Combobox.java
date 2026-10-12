package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;

/**
 * ttk::combobox: an Entry with an arrow at its right end that drops down a list of its -values
 * (TkPopup, the display's popup layer). Readonly, a click anywhere on it opens the list.
 */
public final class Combobox extends Entry {
    /** The arrow's column, inside the border. */
    static final int ARROW = 16;
    final List<String> values = new ArrayList<>();
    /** -height: the most rows the list shows (more scroll). */
    int height = 10;
    /** Tk's currentIndex: kept while the text is still that value (see {@link #current()}). */
    int currentIndex = -1;
    /** The program has a -postcommand: a player's request to open the list goes to it first (it opens it then). */
    boolean postcommand;
    /** The list's first row shown, kept between openings as Tk's popdown keeps its view; never saved. */
    int listTop;

    Combobox(Frame parent) { super(parent, Kind.COMBOBOX); }

    /** The text ends before the arrow. */
    @Override int insetRight() { return super.insetRight() + ARROW; }

    TkRect arrowBox() {
        return new TkRect(bounds.x + bounds.width - border - ARROW, bounds.y + border, ARROW, Math.max(0, bounds.height - 2 * border));
    }

    boolean onArrow(int x, int y) { return bounds.contains(x, y) && x >= bounds.x + bounds.width - border - ARROW; }

    /** Tk's identify at (x, y) in the widget: downarrow, textarea, or "" outside (or unmapped). */
    String identify(int x, int y) {
        bounds();
        if (x < 0 || y < 0 || x >= bounds.width || y >= bounds.height) return "";
        return onArrow(bounds.x + x, bounds.y + y) ? "downarrow" : "textarea";
    }

    /** Tk's [$cb current]: the index of the value among -values (the one it was set from while that still matches), -1 if none. */
    int current() {
        String value = text();
        if (currentIndex < 0 || currentIndex >= values.size() || !values.get(currentIndex).equals(value)) currentIndex = values.indexOf(value);
        return currentIndex;
    }

    /** Tk's [$cb current index]: that value goes in the entry. */
    void current(int index) {
        alive();
        if (index < 0 || index >= values.size()) throw new IllegalArgumentException("Index " + index + " out of range");
        currentIndex = index;
        value(values.get(index));
        tree.changed(false);
    }

    /**
     * A player's choice (ttk::combobox::SelectEntry): the value goes in, all selected with the
     * cursor at its end, and the program hears of it (<<ComboboxSelected>>).
     */
    void choose(int index) {
        current(index);
        selectionRange(0, length());
        cursor(length());
        tree.notice(this, notify, "selected", index);
    }
}
