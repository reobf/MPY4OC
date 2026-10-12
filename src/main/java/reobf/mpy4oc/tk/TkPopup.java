package reobf.mpy4oc.tk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Combobox's open list (ttk's popdown): one per display, drawn above every window and the
 * taskbar, below the Combobox (above it when there is no room below), as wide as it. While it
 * is open it has the pointer and the keyboard (Tk grabs them): the highlight follows the
 * pointer, a click on a row or Return chooses, a press anywhere else closes it and goes no
 * further. More values than -height scroll, with a scrollbar.
 */
final class TkPopup {
    static final int ROW = TkFontMetrics.LINE_HEIGHT, SCROLLBAR = 12;
    /** Paint parts of the list, after the Combobox's own (a client may look for them: parts this high are the popup layer). */
    static final int PART = TkDisplayList.POPUP_PART;
    static final int SELECTED = 0x2456A6;

    final Combobox owner;
    /** The highlighted value (Tk's active row and selection; it follows the pointer). */
    int active;
    /** Sessions whose press began on a row: their release chooses the highlighted one. */
    private final Set<Long> presses = new HashSet<>();
    /** Sessions dragging the scrollbar's thumb, and where in the thumb they hold it. */
    private final Map<Long, Integer> thumbs = new HashMap<>();

    TkPopup(Combobox owner) {
        this.owner = owner;
        int current = owner.current();
        active = Math.max(0, current);
        see(active);
    }

    int count() { return owner.values.size(); }
    /** Rows shown: the values, at most -height of them and what the display has room for. */
    int rows() { return Math.max(0, Math.min(count(), Math.min(owner.height, (owner.tree.height - 2) / ROW))); }
    boolean scrolls() { return count() > rows(); }
    int top() { owner.listTop = Math.max(0, Math.min(owner.listTop, count() - rows())); return owner.listTop; }

    /** The whole list with its 1 px border: below the Combobox, or above it if it does not fit below (as PlacePopdown). */
    TkRect box() {
        TkRect b = owner.bounds;
        int height = rows() * ROW + 2, y = b.y + b.height;
        if (y + height > owner.tree.height) y = Math.max(0, b.y - height);
        return new TkRect(b.x, y, Math.max(3, b.width), height);
    }
    /** The rows' area, inside the border and left of the scrollbar. */
    TkRect list() {
        TkRect box = box();
        return new TkRect(box.x + 1, box.y + 1, Math.max(1, box.width - 2 - (scrolls() ? SCROLLBAR : 0)), rows() * ROW);
    }
    TkRect bar() {
        TkRect box = box();
        return new TkRect(box.x + box.width - 1 - SCROLLBAR, box.y + 1, SCROLLBAR, rows() * ROW);
    }
    private int thumbSize() { TkRect bar = bar(); return Math.min(bar.height, Math.max(8, bar.height * rows() / Math.max(1, count()))); }
    private int thumbStart() {
        TkRect bar = bar(); int range = count() - rows();
        return bar.y + (range <= 0 ? 0 : (bar.height - thumbSize()) * top() / range);
    }

    /** The value whose row is at (x, y), or -1. */
    int rowAt(int x, int y) {
        TkRect list = list();
        if (!list.contains(x, y)) return -1;
        int row = top() + (y - list.y) / ROW;
        return row < count() ? row : -1;
    }

    /** Tk's listbox see: an index just out of view scrolls in, one further away is centred. */
    void see(int index) {
        int rows = Math.max(1, rows()), top = top();
        if (index < top) owner.listTop = top - index <= rows / 3 ? index : index - (rows - 1) / 2;
        else if (index >= top + rows) owner.listTop = index - (top + rows - 1) <= rows / 3 ? index - rows + 1 : index - (rows - 1) / 2;
        top();
    }

    private void scroll(int rows) { owner.listTop += rows; top(); owner.tree.changed(false); }

    /** A left press in the list: a row highlights (its release chooses it), the scrollbar pages or grabs its thumb. */
    void press(long session, int x, int y) {
        int row = rowAt(x, y);
        if (row >= 0) { active = row; presses.add(session); owner.tree.changed(false); return; }
        if (scrolls() && bar().contains(x, y)) {
            int start = thumbStart(), size = thumbSize();
            if (y < start) scroll(-rows());
            else if (y >= start + size) scroll(rows());
            else thumbs.put(session, y - start);
        }
    }

    /** The pointer moved with no button down: the highlight follows it (LBHover). */
    void hover(int x, int y) {
        int row = rowAt(x, y);
        if (row >= 0 && row != active) { active = row; owner.tree.changed(false); }
    }

    /** A drag: of the thumb, or of a row press (the highlight follows; above or below the rows, the list scrolls a row). */
    boolean drag(long session, int x, int y) {
        Integer grab = thumbs.get(session);
        if (grab != null) {
            TkRect bar = bar(); int range = count() - rows(), track = bar.height - thumbSize();
            if (range > 0 && track > 0) {
                int top = Math.round((y - grab - bar.y) * (float) range / track);
                if (top != owner.listTop) { owner.listTop = top; top(); owner.tree.changed(false); }
            }
            return true;
        }
        if (!presses.contains(session)) return false;
        TkRect list = list();
        if (count() == 0) return true;
        if (y < list.y) { scroll(-1); active = top(); }
        else if (y >= list.y + list.height) { scroll(1); active = Math.min(count() - 1, top() + rows() - 1); }
        else active = Math.min(count() - 1, top() + (y - list.y) / ROW);
        owner.tree.changed(false);
        return true;
    }

    /** Whether the session's press is the list's (its release goes to no widget). */
    boolean holds(long session) { return presses.contains(session) || thumbs.containsKey(session); }

    /** The release of a press in the list: true if it was one; a row press chooses the highlighted value. */
    boolean release(long session) {
        if (thumbs.remove(session) != null) return true;
        if (!presses.remove(session)) return false;
        if (count() > 0) owner.tree.chooseFromList(owner, Math.min(active, count() - 1));
        else owner.tree.unpost();
        return true;
    }

    void forget(long session) { presses.remove(session); thumbs.remove(session); }

    void wheel(int delta) { if (scrolls()) scroll(delta > 0 ? -3 : 3); }

    /**
     * A key while the list is open, as Tk's listbox and combobox bindings take it: Up/Down move the
     * highlight, Prior/Next scroll a page, Ctrl+Home/End go to the ends, Return chooses, Tab
     * chooses and moves on, Escape closes; other keys do nothing.
     */
    void key(int code, int modifiers) {
        int n = count();
        boolean ctrl = (modifiers & 2) != 0;
        switch (code) {
            case 200: case 208: if (n > 0) highlight(Math.max(0, Math.min(n - 1, active + (code == 200 ? -1 : 1)))); break;
            case 201: case 209: if (scrolls()) scroll(code == 201 ? -rows() : rows()); break;
            case 199: case 207: if (ctrl && n > 0) highlight(code == 199 ? 0 : n - 1); break;
            case 28: case 156: if (n > 0) owner.tree.chooseFromList(owner, Math.min(active, n - 1)); else owner.tree.unpost(); break;
            case 15: owner.tree.tabFromList(owner, (modifiers & 1) != 0, n > 0 ? Math.min(active, n - 1) : -1); break;
            case 1: owner.tree.unpost(); break;
            default: break;
        }
    }

    private void highlight(int index) { active = index; see(index); owner.tree.changed(false); }

    /** Paint records: the bordered box, the scrollbar, a row per shown value (the highlighted one selected). */
    void paint(List<TkDisplayList.Item> out, TkRect screen) {
        TkRect box = box(), clip = box.intersect(screen);
        if (clip.empty()) return;
        int id = owner.id;
        out.add(new TkDisplayList.Item(id, PART, TkWidget.Kind.FRAME, box, clip, 0xFFFFFF, 0, 1, 0, box.x, box.y, "", -1, 0, 0, null, 0, TkRelief.SOLID));
        if (scrolls()) {
            TkRect bar = bar(), barClip = bar.intersect(screen);
            if (!barClip.empty()) out.add(new TkDisplayList.Item(id, PART + 1, TkWidget.Kind.FRAME, bar, barClip, 0xD9D9D9, 0xD9D9D9, 0, 0, bar.x, bar.y, "", -1, 0, 0));
            TkRect thumb = new TkRect(bar.x, thumbStart(), bar.width, thumbSize()), thumbClip = thumb.intersect(screen);
            if (!thumbClip.empty()) out.add(new TkDisplayList.Item(id, PART + 2, TkWidget.Kind.BUTTON, thumb, thumbClip, 0xC0C0C0, 0, 1, 0,
                    thumb.x, thumb.y, "", -1, 0, 0, null, 0, TkRelief.RAISED));
        }
        TkRect list = list();
        int top = top();
        for (int i = 0; i < rows(); i++) {
            int index = top + i;
            TkRect row = new TkRect(list.x, list.y + i * ROW, list.width, ROW), rowClip = row.intersect(screen);
            if (rowClip.empty()) continue;
            boolean selected = index == active;
            String text = TkText.visible(owner.values.get(index), 0, Math.max(0, list.width - 2));
            out.add(new TkDisplayList.Item(id, PART + 3 + i, TkWidget.Kind.LABEL, row, rowClip, selected ? SELECTED : 0xFFFFFF,
                    selected ? 0xFFFFFF : owner.foreground, 0, 0, row.x + 2, row.y, text, -1, 0, 0, null, 0, TkRelief.FLAT));
        }
    }

    /** Most paint records and text bytes the list can draw (TkPaint.maxRecords, maxBytes), on a display {@code rows} text rows tall. */
    static int maxRecords(Combobox c, int rows) { return 3 + Math.min(c.values.size(), Math.min(c.height, rows)); }
    static long maxBytes(Combobox c, int rows, int rowBytes) {
        long bytes = 0;
        for (String value : c.values) bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return Math.min(bytes, (long) Math.min(c.values.size(), Math.min(c.height, rows)) * rowBytes);
    }
}
