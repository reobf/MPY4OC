package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tk's menu: a list of entries (command, checkbutton, radiobutton, cascade, separator, and the
 * tearoff line) that is never laid out. It shows as a window's menubar (TkWindow) or posted
 * on the popup layer (TkMenus): a raised box, a row per entry. The program keeps the entries'
 * variables and commands; it sends the whole list (with each check or radio entry's indicator
 * on or off) whenever it changes, and hears of the player's choices as notices.
 */
public final class Menu extends TkWidget {
    static final int MAX_ENTRIES = 512;
    /** Row heights: an entry, a separator, the tearoff line; the box's border. */
    static final int ROW = TkFontMetrics.LINE_HEIGHT + 4, SEPARATOR = 8, TEAROFF = 8, BORDER = 1;
    /** Columns: the indicator's on the left, the gap before an accelerator, the cascade arrow's on the right. */
    static final int MARGIN = 18, GAP = 16, ARROW = 16;
    /** A menubar: its height and the space either side of an entry's label. */
    static final int BAR = ROW, BAR_PAD = 6;
    static final String CHECK = "✓", RADIO = "●", CASCADE = "▸";

    enum Type { TEAROFF, COMMAND, SEPARATOR, CHECKBUTTON, RADIOBUTTON, CASCADE;
        String option() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    static final class Entry {
        Type type = Type.COMMAND;
        String label = "", accelerator = "";
        boolean disabled, selected, indicator = true;
        int underline = -1;
        /** A cascade's menu (a Menu's id), 0 for none. */
        int cascade;
        /** The entry's own colours, -1 for the menu's. */
        int foreground = -1, background = -1, activeBackground = -1, activeForeground = -1, selectColor = -1;

        /** Can be activated (Tk skips separators, the tearoff line only by keyboard, and disabled entries). */
        boolean selectable() { return type != Type.SEPARATOR && !disabled; }
        /** A player's choice of it does something the program hears of. */
        boolean invokable() { return selectable() && type != Type.CASCADE && type != Type.TEAROFF; }
        int height() { return type == Type.SEPARATOR ? SEPARATOR : type == Type.TEAROFF ? TEAROFF : ROW; }
    }

    final List<Entry> entries = new ArrayList<>();
    int activeBackground = 0xECECEC, activeForeground = 0x000000, disabledForeground = 0xA3A3A3, selectColor = 0x000000;
    /** The program has a -postcommand, or a <<MenuSelect>> binding: a player's posting, or a change of the active entry, is noticed. */
    boolean postcommand, selectNotify;
    /** The program's callback for this menu's notices (invoke, post, select). */
    int notify;
    /** The highlighted entry (Tk's active one), -1 for none; never saved. */
    int active = -1;

    Menu(Frame parent) {
        super(parent.tree, parent, Kind.MENU);
        relief = Relief.RAISED; border = 1;
    }

    @Override int textBytes() {
        int bytes = 0;
        for (Entry e : entries) bytes += e.label.getBytes(StandardCharsets.UTF_8).length + e.accelerator.getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    // ---- entries ------------------------------------------------------------------------

    /** The program's whole entry list: maps of type, label, accelerator, state, underline, selected, indicatoron, menu and colours. */
    void entries(Object raw) {
        alive();
        Object[] list = TkOptions.sequence(raw, MAX_ENTRIES);
        List<Entry> parsed = new ArrayList<>();
        int bytes = 0;
        for (Object item : list) {
            Map<String, Object> m = TkOptions.map(item);
            for (String key : m.keySet())
                if (!java.util.Arrays.asList("type", "label", "accelerator", "state", "underline", "selected", "indicatoron", "menu",
                        "foreground", "background", "activebackground", "activeforeground", "selectcolor").contains(key))
                    throw new IllegalArgumentException("unsupported menu entry option: " + key);
            Entry e = new Entry();
            String type = TkOptions.string(m, "type", "command");
            try { e.type = Type.valueOf(type.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException bad) { throw new IllegalArgumentException("bad menu entry type \"" + type + "\""); }
            e.label = line(TkOptions.string(m, "label", ""));
            e.accelerator = line(TkOptions.string(m, "accelerator", ""));
            String state = TkOptions.string(m, "state", "normal");
            if (!state.equals("normal") && !state.equals("active") && !state.equals("disabled"))
                throw new IllegalArgumentException("bad state \"" + state + "\": must be active, normal, or disabled");
            e.disabled = state.equals("disabled");
            e.underline = m.containsKey("underline") ? TkOptions.integer(m.get("underline"), -1, 1024) : -1;
            e.selected = m.containsKey("selected") && TkOptions.bool(m.get("selected"));
            e.indicator = !m.containsKey("indicatoron") || TkOptions.bool(m.get("indicatoron"));
            if (m.containsKey("menu")) {
                int id = TkOptions.integer(m.get("menu"), 0, Integer.MAX_VALUE);
                if (id != 0 && !(tree.owned(id) instanceof Menu)) throw new IllegalArgumentException("a cascade's menu must be a Menu");
                e.cascade = id;
            }
            e.foreground = color(m, "foreground"); e.background = color(m, "background");
            e.activeBackground = color(m, "activebackground"); e.activeForeground = color(m, "activeforeground");
            e.selectColor = color(m, "selectcolor");
            bytes += e.label.getBytes(StandardCharsets.UTF_8).length + e.accelerator.getBytes(StandardCharsets.UTF_8).length;
            parsed.add(e);
        }
        tree.checkTextBytes(this, bytes);
        entries.clear(); entries.addAll(parsed);
        if (active >= entries.size() || active >= 0 && !entries.get(active).selectable()) active = -1;
        tree.changed(true);   // a menubar's entries are laid out with its window
    }

    private static String line(String text) {
        TkFontMetrics.checkText(text);
        if (text.indexOf('\n') >= 0) throw new IllegalArgumentException("menu labels and accelerators are one line");
        return text;
    }
    private static int color(Map<String, Object> m, String key) {
        return m.containsKey(key) ? TkOptions.integer(m.get(key), -1, 0xFFFFFF) : -1;
    }

    /** activate: highlight entry index (-1 none); a separator or a disabled entry highlights nothing. Whether it changed. */
    boolean activate(int index) {
        int chosen = index >= 0 && index < entries.size() && entries.get(index).selectable() ? index : -1;
        if (chosen == active) return false;
        active = chosen; tree.changed(false);
        return true;
    }

    /** The next entry from {@code from} that can be highlighted, by {@code step} (wrapping), or -1. */
    int next(int from, int step) {
        int n = entries.size();
        if (n == 0) return -1;
        int i = from;
        for (int k = 0; k < n; k++) {
            i = from < 0 && k == 0 ? (step > 0 ? 0 : n - 1) : Math.floorMod(i + step, n);
            Entry e = entries.get(i);
            if (e.selectable() && e.type != Type.TEAROFF) return i;
        }
        return -1;
    }

    // ---- a posted menu's geometry -------------------------------------------------------

    /** The top of entry index from the top of the box (Tk's yposition). */
    int entryY(int index) {
        int y = BORDER;
        for (int i = 0; i < index && i < entries.size(); i++) y += entries.get(i).height();
        return y;
    }

    /** The entry at y from the box's top (Tk's @y), or -1. */
    int entryAt(int y) {
        int top = BORDER;
        for (int i = 0; i < entries.size(); i++) {
            int h = entries.get(i).height();
            if (y >= top && y < top + h) return i;
            top += h;
        }
        return -1;
    }

    int boxHeight() { return Math.max(2 * BORDER + 2, entryY(entries.size()) + BORDER); }

    int boxWidth() {
        int labels = 0, accelerators = 0;
        for (Entry e : entries) {
            labels = Math.max(labels, TkFontMetrics.width(e.label));
            accelerators = Math.max(accelerators, TkFontMetrics.width(e.accelerator));
        }
        return 2 * BORDER + MARGIN + labels + (accelerators > 0 ? GAP + accelerators : 0) + ARROW;
    }

    private int text(Entry e, boolean active) {
        if (e.disabled) return disabledForeground;
        if (active) return e.activeForeground >= 0 ? e.activeForeground : activeForeground;
        return e.foreground >= 0 ? e.foreground : foreground;
    }

    /** Paint the menu posted with its box at (x, y): parts from TkDisplayList.POPUP_PART, a box and up to four per entry. */
    void paint(List<TkDisplayList.Item> out, int x, int y, TkRect screen) {
        TkRect box = new TkRect(x, y, boxWidth(), boxHeight()), clip = box.intersect(screen);
        if (clip.empty()) return;
        int base = TkDisplayList.POPUP_PART;
        out.add(new TkDisplayList.Item(id, base, Kind.FRAME, box, clip, background, background, BORDER, 0, box.x, box.y, "", -1, 0, 0, null, 0, TkRelief.RAISED));
        int top = y + BORDER, inner = box.width - 2 * BORDER;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int h = e.height();
            TkRect row = new TkRect(x + BORDER, top, inner, h), rowClip = row.intersect(screen).intersect(clip);
            top += h;
            if (rowClip.empty()) continue;
            int part = base + 1 + 4 * i;
            int bg = e.background >= 0 ? e.background : background;
            if (e.type == Type.SEPARATOR) {
                TkRect line = new TkRect(row.x + 1, row.y + h / 2 - 1, Math.max(1, row.width - 2), 2), lineClip = line.intersect(rowClip);
                if (!lineClip.empty()) out.add(new TkDisplayList.Item(id, part, Kind.FRAME, line, lineClip, bg, bg, 1, 0, line.x, line.y, "", -1, 0, 0, null, 0, TkRelief.SUNKEN));
                continue;
            }
            if (e.type == Type.TEAROFF) {
                String dashes = new String(new char[Math.max(0, row.width / TkFontMetrics.CELL_WIDTH)]).replace('\0', '-');
                out.add(new TkDisplayList.Item(id, part, Kind.LABEL, row, rowClip, bg, text(e, false), 0, 0,
                        row.x, row.y + (h - TkFontMetrics.LINE_HEIGHT) / 2, dashes, -1, 0, 0, null, 0, TkRelief.FLAT));
                continue;
            }
            boolean lit = i == active;
            int rowBg = lit ? (e.activeBackground >= 0 ? e.activeBackground : activeBackground) : bg, fg = text(e, lit);
            int textY = row.y + (h - TkFontMetrics.LINE_HEIGHT) / 2;
            // The mark, accelerator and arrow sit inside the row's border (a lit row's is raised).
            TkRect inside = new TkRect(row.x + 1, row.y + 1, Math.max(1, row.width - 2), Math.max(1, h - 2));
            out.add(new TkDisplayList.Item(id, part, Kind.LABEL, row, rowClip, rowBg, fg, lit ? 1 : 0, 0, row.x + MARGIN, textY,
                    TkText.visible(e.label, 0, Math.max(0, row.width - MARGIN)), -1, 0, 0, null, 0, lit ? TkRelief.RAISED : TkRelief.FLAT));
            if ((e.type == Type.CHECKBUTTON || e.type == Type.RADIOBUTTON) && e.indicator && e.selected) {
                int color = e.disabled ? disabledForeground : e.selectColor >= 0 ? e.selectColor : selectColor;
                TkRect mark = new TkRect(row.x + 2, inside.y, MARGIN - 4, inside.height), markClip = mark.intersect(rowClip);
                if (!markClip.empty()) out.add(new TkDisplayList.Item(id, part + 1, Kind.LABEL, mark, markClip, rowBg, color, 0, 0,
                        mark.x + (mark.width - TkFontMetrics.CELL_WIDTH) / 2, textY, e.type == Type.CHECKBUTTON ? CHECK : RADIO, -1, 0, 0, null, 0, TkRelief.FLAT));
            }
            if (!e.accelerator.isEmpty()) {
                int width = TkFontMetrics.width(e.accelerator);
                TkRect acc = new TkRect(row.x + row.width - ARROW - width, inside.y, width, inside.height), accClip = acc.intersect(rowClip);
                if (!accClip.empty()) out.add(new TkDisplayList.Item(id, part + 2, Kind.LABEL, acc, accClip, rowBg, fg, 0, 0, acc.x, textY,
                        e.accelerator, -1, 0, 0, null, 0, TkRelief.FLAT));
            }
            if (e.type == Type.CASCADE) {
                TkRect arrow = new TkRect(row.x + row.width - ARROW, inside.y, ARROW - 2, inside.height), arrowClip = arrow.intersect(rowClip);
                if (!arrowClip.empty()) out.add(new TkDisplayList.Item(id, part + 3, Kind.LABEL, arrow, arrowClip, rowBg, fg, 0, 0,
                        arrow.x + (arrow.width - TkFontMetrics.CELL_WIDTH) / 2, textY, CASCADE, -1, 0, 0, null, 0, TkRelief.FLAT));
            }
        }
    }

    // ---- as a menubar -------------------------------------------------------------------

    /** The menubar entry boxes across a bar starting at (x, y) and width wide: {index, x, width} for each shown entry. */
    List<int[]> barEntries(int x, int width) {
        List<int[]> out = new ArrayList<>();
        int at = x;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.type == Type.TEAROFF || e.type == Type.SEPARATOR) continue;
            int w = TkFontMetrics.width(e.label) + 2 * BAR_PAD;
            if (at + w > x + width) break;   // one row: what does not fit is not shown
            out.add(new int[] {i, at, w});
            at += w;
        }
        return out;
    }

    /** Paint as a window's menubar in bar (parts from {@code base} on the window); entry {@code open} is lit. */
    void paintBar(List<TkDisplayList.Item> out, int windowId, int base, TkRect bar, TkRect screen, int open) {
        TkRect clip = bar.intersect(screen);
        if (clip.empty()) return;
        out.add(new TkDisplayList.Item(windowId, base, Kind.FRAME, bar, clip, background, background, 0, 0, bar.x, bar.y, "", -1, 0, 0, null, 0, TkRelief.FLAT));
        int textY = bar.y + (BAR - TkFontMetrics.LINE_HEIGHT) / 2;
        for (int[] slot : barEntries(bar.x, bar.width)) {
            Entry e = entries.get(slot[0]);
            boolean lit = slot[0] == open;
            TkRect r = new TkRect(slot[1], bar.y, slot[2], BAR), rClip = r.intersect(clip);
            if (rClip.empty()) continue;
            int bg = lit ? (e.activeBackground >= 0 ? e.activeBackground : activeBackground) : e.background >= 0 ? e.background : background;
            out.add(new TkDisplayList.Item(windowId, base + 1 + slot[0], Kind.LABEL, r, rClip, bg, text(e, lit), lit ? 1 : 0, 0,
                    r.x + BAR_PAD, textY, e.label, -1, 0, 0, null, 0, lit ? TkRelief.RAISED : TkRelief.FLAT));
        }
    }

    // ---- budgets ------------------------------------------------------------------------

    /** Most records posted (a box and four per entry that can show on a display rows text rows tall). */
    int maxRecords(int rows) { return 1 + 4 * Math.min(entries.size(), 2 * rows + 2); }
    long maxBytes(int rows, int rowBytes) {
        long bytes = 0;
        for (Entry e : entries) bytes += Math.min(rowBytes, e.label.getBytes(StandardCharsets.UTF_8).length + e.accelerator.getBytes(StandardCharsets.UTF_8).length + 8);
        return bytes + rowBytes;   // the tearoff's dashes
    }
}
