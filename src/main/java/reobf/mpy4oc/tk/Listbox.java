package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded server-side rows; rendering walks only the visible viewport. */
public final class Listbox extends TkWidget {
    public static final int MAX_ROWS = 20000, MAX_BYTES = 2 * 1024 * 1024, MAX_BATCH = 256;
    final ArrayList<String> rows = new ArrayList<>();
    final BitSet selected = new BitSet();
    private final TreeMap<Integer, Integer> widths = new TreeMap<>();
    int bytes, columns = 20, lines = 10, top, scrollX, active, anchor, xscrollcommand, yscrollcommand;
    long selectionVersion;
    String selectmode = "browse";
    private String lastX = "", lastY = "";
    public Listbox(Frame parent) { super(parent.tree, parent, Kind.LISTBOX); background = 0xFFFFFF; border = 1; padx = pady = 2; }
    public int size() { return rows.size(); }
    int maxWidth() { return widths.isEmpty() ? 0 : widths.lastKey(); }
    int page() { return Math.max(1, (bounds.height - 2 * (pady + border)) / TkFontMetrics.LINE_HEIGHT); }
    int pixels() { return Math.max(1, bounds.width - 2 * (padx + border)); }
    private void width(String row, int delta) {
        int key = TkFontMetrics.width(row), count = widths.getOrDefault(key, 0) + delta;
        if (count == 0) widths.remove(key); else widths.put(key, count);
    }
    public void insert(int index, String... values) {
        alive(); if (!enabled) return;
        if (values.length > MAX_BATCH || size() + values.length > MAX_ROWS) throw new IllegalArgumentException("Listbox row/batch limit exceeded");
        int added = 0;
        for (String value : values) {
            TkFontMetrics.checkText(value); if (value.indexOf('\n') >= 0) throw new IllegalArgumentException("Listbox rows must be single lines");
            added += value.getBytes(StandardCharsets.UTF_8).length;
        }
        int total = added;
        for (TkWidget w : tree.widgets.values()) if (w instanceof Listbox) total += ((Listbox) w).bytes;
        if (bytes + added > MAX_BYTES || total > 8 * 1024 * 1024) throw new IllegalArgumentException("Listbox text budget exceeded");
        int at = Math.max(0, Math.min(size(), index)), oldSize = size();
        rows.addAll(at, Arrays.asList(values)); bytes += added;
        for (String value : values) width(value, 1);
        for (int i = oldSize - 1; i >= at; i--) selected.set(i + values.length, selected.get(i));
        selected.clear(at, at + values.length);
        if (oldSize > 0) { if (active >= at) active += values.length; if (anchor >= at) anchor += values.length; if (top > at) top += values.length; }
        tree.changed(columns == 0 || lines == 0);
    }
    public void delete(int first, int last) {
        alive(); if (!enabled) return;
        int a = Math.max(0, first), b = Math.min(size() - 1, last);
        if (a > b) return;
        int oldSize = size(), count = b - a + 1;
        for (int i = a; i <= b; i++) { String row = rows.get(i); bytes -= row.getBytes(StandardCharsets.UTF_8).length; width(row, -1); }
        rows.subList(a, b + 1).clear();
        for (int i = a; i < oldSize - count; i++) selected.set(i, selected.get(i + count));
        selected.clear(oldSize - count, oldSize);
        active = adjusted(active, a, b); anchor = adjusted(anchor, a, b); top = adjusted(top, a, b);
        tree.changed(columns == 0 || lines == 0);
    }
    private int adjusted(int n, int first, int last) { return clamp(n < first ? n : n <= last ? first : n - last + first - 1); }
    int clamp(int n) { return Math.max(0, Math.min(size() - 1, n)); }
    int index(Object raw, boolean endIsSize) {
        if (raw instanceof Number) return TkOptions.integer(raw, Integer.MIN_VALUE, Integer.MAX_VALUE);
        String text = TkOptions.text(raw);
        if (text.equals("end")) return endIsSize ? size() : size() - 1;
        if (text.equals("active")) return active;
        if (text.equals("anchor")) return anchor;
        if (text.startsWith("@")) {
            String[] xy = text.substring(1).split(",");
            if (xy.length != 2) throw new IllegalArgumentException("expected @x,y");
            return nearest(Integer.parseInt(xy[1]));
        }
        return Integer.parseInt(text);
    }
    public String get(int index) { return index < 0 || index >= size() ? "" : rows.get(index); }
    public Object[] get(int first, int last) {
        int a = Math.max(0, first), b = Math.min(size() - 1, last);
        if (b - (long) a + 1 > MAX_BATCH) throw new IllegalArgumentException("read at most 256 rows per call");
        return a > b ? new Object[0] : rows.subList(a, b + 1).toArray();
    }
    public Object[] curselection() { return selected.stream().mapToObj(Integer::valueOf).toArray(); }
    public void selection(int first, int last, boolean value) {
        alive(); if (!enabled) return;
        int a = Math.max(0, first), b = Math.min(size() - 1, last);
        if (a <= b) { selected.set(a, b + 1, value); tree.changed(false); }
    }
    public int nearest(int localY) { bounds(); return clamp(top + Math.floorDiv(localY - pady - border, TkFontMetrics.LINE_HEIGHT)); }
    public void see(int index) {
        bounds(); int at = clamp(index);
        if (at < top) top = at; else if (at >= top + page()) top = at - page() + 1;
        tree.changed(false);
    }
    void viewport() {
        top = Math.max(0, Math.min(top, Math.max(0, size() - page())));
        scrollX = Math.max(0, Math.min(scrollX, Math.max(0, maxWidth() - pixels())));
        notifyView(false); notifyView(true);
    }
    public Object[] view(boolean x) {
        bounds(); return fractions(x);
    }
    private Object[] fractions(boolean x) {
        int n = x ? maxWidth() : size(), first = x ? scrollX : top, visible = x ? pixels() : page();
        return n == 0 ? new Object[] {0.0, 1.0} : new Object[] {first / (double) n, Math.min(1.0, (first + visible) / (double) n)};
    }
    private void notifyView(boolean x) {
        Object[] view = fractions(x); int callback = x ? xscrollcommand : yscrollcommand;
        String key = callback + ":" + view[0] + ":" + view[1];
        if (!key.equals(x ? lastX : lastY)) { if (x) lastX = key; else lastY = key; tree.queue(this, callback, view); }
    }
    public void moveto(boolean x, double fraction) {
        alive(); if (!Double.isFinite(fraction)) throw new IllegalArgumentException("finite fraction required");
        bounds(); int at = (int) (Math.max(0, Math.min(1, fraction)) * (x ? maxWidth() : size()));
        if (x) scrollX = at; else top = at; clampScroll(); tree.changed(false);
    }
    public void scroll(boolean x, int amount, boolean pages) {
        alive(); bounds(); long next = (x ? scrollX : top) + (long) amount * (pages ? x ? pixels() : Math.max(1, page() - 1) : x ? 8 : 1);
        int at = (int) Math.max(0, Math.min(Integer.MAX_VALUE, next)); if (x) scrollX = at; else top = at; clampScroll(); tree.changed(false);
    }
    /**
     * Size-independent bounds for the view. {@link #viewport()} narrows them against the
     * laid-out size, but only for a painted Listbox; these keep every stored value
     * within what the NBT loader accepts (rows are at most 1 KiB, i.e. 8192 pixels).
     */
    void clampScroll() {
        top = Math.max(0, Math.min(top, Math.max(0, size() - 1)));
        scrollX = Math.max(0, Math.min(scrollX, 8192));
    }
    void press(int y, int modifiers) {
        if (size() == 0) return;
        int at = nearest(y - bounds.y); BitSet before = (BitSet) selected.clone(); active = at;
        if (selectmode.equals("multiple")) selected.flip(at);
        else if (selectmode.equals("extended") && (modifiers & 1) != 0) { selected.clear(); selected.set(Math.min(anchor, at), Math.max(anchor, at) + 1); }
        else if (selectmode.equals("extended") && (modifiers & 2) != 0) { selected.flip(at); anchor = at; }
        else { selected.clear(); selected.set(at); anchor = at; }
        if (!before.equals(selected)) selectionVersion++; tree.changed(false);
    }
    void drag(int y) {
        if (size() == 0 || selectmode.equals("single") || selectmode.equals("multiple")) return;
        if (y < bounds.y) top = Math.max(0, top - 1); else if (y >= bounds.y + bounds.height) top++;
        clampScroll();
        int at = clamp(top + Math.floorDiv(y - bounds.y - pady - border, 16)); BitSet before = (BitSet) selected.clone();
        active = at; selected.clear(); selected.set(selectmode.equals("extended") ? Math.min(anchor, at) : at, selectmode.equals("extended") ? Math.max(anchor, at) + 1 : at + 1);
        if (!before.equals(selected)) selectionVersion++; tree.changed(false);
    }
    void key(int code, int modifiers) {
        if (size() == 0) return;
        int at = active;
        if (code == 200) at--; else if (code == 208) at++; else if (code == 199) at = 0; else if (code == 207) at = size() - 1;
        else if (code == 201) at -= page(); else if (code == 209) at += page(); else if (code != 57) return;
        at = clamp(at); see(at); press(bounds.y + pady + border + (at - top) * 16 + 1, modifiers);
    }
}
