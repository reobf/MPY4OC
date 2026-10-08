package reobf.mpy4oc.tk;

import java.util.List;

/** Converts compound controls to ordinary clipped paint records. */
final class TkPaint {
    private TkPaint() {}
    static boolean special(TkWidget w, boolean focused, List<TkDisplayList.Item> out) {
        if (!(w instanceof Text || w instanceof Canvas || w instanceof Listbox || w instanceof Checkbutton || w instanceof Scrollbar || w instanceof Scale)) return false;
        int state = (focused ? TkDisplayList.FOCUSED : 0) | (!w.tree.available(w) ? TkDisplayList.DISABLED : 0);
        add(out, w, 0, w.bounds, "", w.bounds.x, w.bounds.y, w.background, w.foreground, w.border, state);
        if (w instanceof Text) {
            ((Text) w).paint(out, state);
        } else if (w instanceof Canvas) {
            ((Canvas) w).paint(out, state);
        } else if (w instanceof Checkbutton) {
            Checkbutton c = (Checkbutton) w;
            int x = w.bounds.x + w.padx, y = w.bounds.y + (w.bounds.height - 14) / 2;
            add(out, w, 1, new TkRect(x, y, 14, 14), c.selected ? w.kind == TkWidget.Kind.RADIOBUTTON ? "o" : "x" : "", x + 3, y - 1, 0xFFFFFF, w.foreground, 1, state);
            add(out, w, 2, new TkRect(x + 20, w.bounds.y, Math.max(0, w.bounds.width - w.padx - 20), w.bounds.height), c.paintText(), x + 20,
                    w.bounds.y + (w.bounds.height - TkFontMetrics.height(c.paintText())) / 2, w.background, w.foreground, focused ? 1 : 0, state);
        } else if (w instanceof Listbox) {
            Listbox list = (Listbox) w; list.viewport();
            int x = w.bounds.x + w.padx + w.border, y = w.bounds.y + w.pady + w.border, width = list.pixels();
            for (int row = list.top; row < list.size() && y < w.bounds.y + w.bounds.height - w.border; row++, y += 16) {
                String text = list.rows.get(row); boolean selected = list.selected.get(row) && (state & TkDisplayList.DISABLED) == 0;
                add(out, w, row + 1, new TkRect(x, y, width, 16), TkText.visible(text, list.scrollX, width), x + TkText.visibleOffset(text, list.scrollX), y,
                        selected ? 0x2456A6 : w.background, selected ? 0xFFFFFF : w.foreground, focused && row == list.active ? 1 : 0, state);
            }
        } else if (w instanceof Scrollbar) {
            Scrollbar bar = (Scrollbar) w; int arrow = bar.arrow();
            part(out, w, 1, bar.horizontal, 0, arrow, bar.horizontal ? "<" : "^", 0xD9D9D9, state);
            part(out, w, 2, bar.horizontal, bar.length() - arrow, arrow, bar.horizontal ? ">" : "v", 0xD9D9D9, state);
            part(out, w, 3, bar.horizontal, bar.thumbStart(), bar.thumbSize(), "", focused ? 0x6699CC : 0xAAAAAA, state);
        } else {
            Scale scale = (Scale) w; boolean h = scale.horizontal;
            int cross = h ? w.bounds.y + w.bounds.height - 12 : w.bounds.x + w.bounds.width - 12;
            TkRect track = h ? new TkRect(w.bounds.x + 8, cross, scale.trackLength(), 4) : new TkRect(cross, w.bounds.y + 8, 4, scale.trackLength());
            add(out, w, 1, track, "", track.x, track.y, 0x777777, 0, 0, state);
            TkRect thumb = h ? new TkRect(w.bounds.x + scale.position() - 6, cross - 5, 12, 14) : new TkRect(cross - 5, w.bounds.y + scale.position() - 6, 14, 12);
            add(out, w, 2, thumb, "", thumb.x, thumb.y, focused ? 0x6699CC : 0xAAAAAA, 0, 1, state);
            if (scale.showvalue) {
                String text = scale.value == Math.rint(scale.value) ? Long.toString((long) scale.value) : Double.toString(scale.value);
                add(out, w, 3, new TkRect(w.bounds.x + 2, w.bounds.y + 2, Math.max(0, w.bounds.width - 4), 16), text,
                        w.bounds.x + 2, w.bounds.y + 2, w.background, w.foreground, 0, state);
            }
        }
        return true;
    }
    private static void part(List<TkDisplayList.Item> out, TkWidget w, int part, boolean h, int start, int size, String text, int bg, int state) {
        TkRect r = h ? new TkRect(w.bounds.x + start, w.bounds.y, size, w.bounds.height) : new TkRect(w.bounds.x, w.bounds.y + start, w.bounds.width, size);
        add(out, w, part, r, text, r.x + (r.width - 8) / 2, r.y + (r.height - 16) / 2, bg, w.foreground, 1, state);
    }
    private static void add(List<TkDisplayList.Item> out, TkWidget w, int part, TkRect rect, String text, int tx, int ty, int bg, int fg, int border, int state) {
        TkRect r = rect.intersect(w.bounds), clip = r.intersect(w.clip);
        if (!clip.empty()) out.add(new TkDisplayList.Item(w.id, part, part == 0 ? w.kind : TkWidget.Kind.LABEL,
                r, clip, bg, fg, border, state, tx, ty, text, -1, 0, 0));
    }
}
