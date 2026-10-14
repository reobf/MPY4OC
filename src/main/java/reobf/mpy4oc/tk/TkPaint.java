package reobf.mpy4oc.tk;

import java.util.List;

/** Converts compound controls to ordinary clipped paint records. */
final class TkPaint {
    private TkPaint() {}
    static boolean special(TkWidget w, boolean focused, List<TkDisplayList.Item> out) {
        if (w instanceof TkIcon) {
            TkIcon icon = (TkIcon) w;
            int state = (focused ? TkDisplayList.FOCUSED : 0) | (!w.tree.available(w) ? TkDisplayList.DISABLED : 0);
            int bg = w.lookColor("background", w.background);
            relief(out, w, 0, TkWidget.Kind.FRAME, w.bounds, bg, w.border, w.lookRelief("relief", w.relief).code(), state);
            TkRect box = icon.iconBox(), clip = box.intersect(w.clip);
            if (icon.descriptor != null && !clip.empty())
                out.add(new TkDisplayList.Item(w.id, 1, w.kind, box, clip, bg, w.foreground, 0, state,
                        box.x, box.y, "", -1, 0, 0, null, 0, 0, icon.descriptor));
            return true;
        }
        if (w instanceof Notebook) {
            // The background, the page's raised border, then the tabs: raised, open at the bottom. The
            // selected one has the notebook's colour and reaches 1 px down over the border, so that it
            // runs into its page; it shows the focus ring when the notebook has the keyboard.
            Notebook book = (Notebook) w; book.layoutTabs();
            int state = !w.tree.available(w) ? TkDisplayList.DISABLED : 0, look = w.tree.state(w), background = w.lookColor("background", look, w.background);
            relief(out, w, 0, TkWidget.Kind.FRAME, w.bounds, background, 0, TkRelief.FLAT, state);
            if (!book.clientBox().empty()) relief(out, w, 1, TkWidget.Kind.FRAME, book.clientBox(), background, 1, TkRelief.RAISED, state);
            for (int i = 0; i < book.tabs.size(); i++) {
                Notebook.Tab tab = book.tabs.get(i);
                if (tab.box.empty()) continue;
                boolean selected = i == book.current;
                TkRect box = selected ? new TkRect(tab.box.x, tab.box.y, tab.box.width, tab.box.height + 1) : tab.box;
                TkRect r = box.intersect(w.bounds), clip = r.intersect(w.clip);
                if (clip.empty()) continue;
                int tabState = state | (tab.state == Notebook.DISABLED ? TkDisplayList.DISABLED : 0) | (selected && focused ? TkDisplayList.FOCUSED : 0);
                int textX = box.x + (box.width - TkFontMetrics.width(tab.text)) / 2, textY = box.y + 2 + Notebook.TAB_PAD_Y;
                // TNotebook.Tab's colours, in the tab's own state: selected, disabled.
                int tabLook = look & ~(TkLook.SELECTED | TkLook.DISABLED) | (selected ? TkLook.SELECTED : 0) | (tab.state == Notebook.DISABLED ? TkLook.DISABLED : 0);
                out.add(new TkDisplayList.Item(w.id, 2 + i, TkWidget.Kind.BUTTON, r, clip, w.lookColor("tabbackground", tabLook, selected ? background : Notebook.UNSELECTED),
                        w.lookColor("tabforeground", tabLook, w.foreground), 1, tabState, textX, textY, tab.text, -1, 0, 0, null, 0, TkRelief.TAB));
            }
            return true;
        }
        if (w instanceof Treeview) { ((Treeview) w).paint(focused, out); return true; }
        if (w instanceof Panedwindow) {
            // The background (with a classic one's border), then the sashes over the gaps between panes.
            int state = !w.tree.available(w) ? TkDisplayList.DISABLED : 0, background = w.lookColor("background", w.background);
            relief(out, w, 0, TkWidget.Kind.FRAME, w.bounds, background, w.border, w.relief.code(), state);
            ((Panedwindow) w).paintSashes(out, background, state);
            return true;
        }
        if (w instanceof Labelframe) {
            // The background, the border moved to run through the label, then the label's text on its
            // own background (hiding the border behind it); a label widget is painted as a child.
            Labelframe frame = (Labelframe) w;
            int state = !w.tree.available(w) ? TkDisplayList.DISABLED : 0, look = w.tree.state(w), background = w.lookColor("background", look, w.background);
            relief(out, w, 0, TkWidget.Kind.FRAME, w.bounds, background, 0, TkRelief.FLAT, state);
            if (w.border > 0) relief(out, w, 1, TkWidget.Kind.FRAME, frame.borderBox(), background, w.border, w.lookRelief("relief", w.relief).code(), state);
            TkRect box = frame.labelBox();
            if (box != null && frame.labelWidget == null && !frame.text.isEmpty())
                add(out, w, 2, box, frame.text, box.x + frame.textInset(), box.y + frame.textInset(), w.lookColor("labelbackground", look, background),
                        w.lookColor("labelforeground", look, w.foreground), 0, state);
            return true;
        }
        if (w instanceof Separator || w instanceof Sizegrip) {
            // Painted with the older FRAME/LABEL record kinds, so clients need no new kind.
            int state = !w.tree.available(w) ? TkDisplayList.DISABLED : 0;
            if (w instanceof Separator) {
                // A sunken 1 px bevel on a 2 px line: dark above (left), light below (right) -- etched.
                record(out, w, 0, TkWidget.Kind.FRAME, ((Separator) w).line(), w.lookColor("background", w.background), 1, state | TkDisplayList.PRESSED);
            } else {
                record(out, w, 0, TkWidget.Kind.LABEL, w.bounds, w.lookColor("background", w.background), 0, state);
                int right = w.bounds.x + w.bounds.width - Sizegrip.SIZE, bottom = w.bounds.y + w.bounds.height - Sizegrip.SIZE;
                for (int i = 0; i < Sizegrip.DOTS.length; i++)
                    record(out, w, i + 1, TkWidget.Kind.FRAME, new TkRect(right + Sizegrip.DOTS[i][0], bottom + Sizegrip.DOTS[i][1], 2, 2), 0x7A7A7A, 0, state);
            }
            return true;
        }
        if (!(w instanceof Text || w instanceof Canvas || w instanceof Listbox || w instanceof Checkbutton || w instanceof Scrollbar || w instanceof Scale
                || w instanceof Progressbar)) return false;
        int state = (focused ? TkDisplayList.FOCUSED : 0) | (!w.tree.available(w) ? TkDisplayList.DISABLED : 0);
        // A ttk widget's colours from its style (TkLook): the trough of a Scrollbar or Progressbar is its troughcolor.
        int look = w.tree.state(w), background = w.lookColor("background", look, w.background), foreground = w.lookColor("foreground", look, w.foreground);
        int field = w instanceof Scrollbar || w instanceof Progressbar ? w.lookColor("troughcolor", look, w.background) : background;
        TkRect whole = w.bounds.intersect(w.bounds), wholeClip = whole.intersect(w.clip);
        if (!wholeClip.empty()) out.add(new TkDisplayList.Item(w.id, 0, w.kind, whole, wholeClip, field, foreground, w.border, state, w.bounds.x, w.bounds.y, "",
                -1, 0, 0, null, 0, w.lookRelief("relief", w.relief).code()));
        if (w instanceof Text) {
            ((Text) w).paint(out, state);
        } else if (w instanceof Canvas) {
            ((Canvas) w).paint(out, state);
        } else if (w instanceof Checkbutton) {
            Checkbutton c = (Checkbutton) w;
            int x = w.bounds.x + w.insetLeft(), y = w.bounds.y + (w.bounds.height - 14) / 2;
            // A theme with an indicatorcolor fills the box with it (default, alt, classic); otherwise the box
            // has its indicatorbackground and a mark when selected (clam, and classic widgets).
            boolean filled = w.look != null && w.look.has("indicatorcolor");
            int box = filled ? w.lookColor("indicatorcolor", look, 0xFFFFFF) : w.lookColor("indicatorbackground", look, 0xFFFFFF);
            add(out, w, 1, new TkRect(x, y, 14, 14), c.selected && !filled ? w.kind == TkWidget.Kind.RADIOBUTTON ? "o" : "x" : "", x + 3, y - 1, box, foreground, 1, state);
            add(out, w, 2, new TkRect(x + 20, w.bounds.y, Math.max(0, w.bounds.x + w.bounds.width - w.insetRight() - x - 20), w.bounds.height), c.paintText(), x + 20,
                    w.bounds.y + (w.bounds.height - TkFontMetrics.height(c.paintText())) / 2, background, foreground, focused ? 1 : 0, state);
        } else if (w instanceof Listbox) {
            Listbox list = (Listbox) w; list.viewport();
            TkRect inside = w.inner();
            int x = inside.x, y = inside.y, width = list.pixels();
            // Rows stop at the padding and border, as in Tk (a row partly below is cut there).
            for (int row = list.top; row < list.size() && y < inside.y + inside.height; row++, y += 16) {
                String text = list.rows.get(row); boolean selected = list.selected.get(row) && (state & TkDisplayList.DISABLED) == 0;
                add(out, w, row + 1, new TkRect(x, y, width, 16).intersect(inside), TkText.visible(text, list.scrollX, width), x + TkText.visibleOffset(text, list.scrollX), y,
                        selected ? 0x2456A6 : w.background, selected ? 0xFFFFFF : w.foreground, focused && row == list.active ? 1 : 0, state);
            }
        } else if (w instanceof Scrollbar) {
            Scrollbar bar = (Scrollbar) w; int arrow = bar.arrow();
            int arrows = w.lookColor("background", look, 0xD9D9D9), ink = w.lookColor("arrowcolor", look, foreground);
            part(out, w, 1, bar.horizontal, 0, arrow, bar.horizontal ? "<" : "^", arrows, ink, state);
            part(out, w, 2, bar.horizontal, bar.length() - arrow, arrow, bar.horizontal ? ">" : "v", arrows, ink, state);
            part(out, w, 3, bar.horizontal, bar.thumbStart(), bar.thumbSize(), "", w.lookColor("background", look, focused ? 0x6699CC : 0xAAAAAA), ink, state);
        } else if (w instanceof Progressbar) {
            Progressbar p = (Progressbar) w; int[] bar = p.bar();
            if (bar[1] > 0) {
                int inset = w.border;
                TkRect r = p.horizontal ? new TkRect(w.bounds.x + inset + bar[0], w.bounds.y + inset, bar[1], Math.max(0, w.bounds.height - 2 * inset))
                        : new TkRect(w.bounds.x + inset, w.bounds.y + inset + bar[0], Math.max(0, w.bounds.width - 2 * inset), bar[1]);
                int bar0 = w.lookColor("bar", look, w.foreground);
                add(out, w, 1, r, "", r.x, r.y, bar0, bar0, 0, state);
            }
        } else {
            Scale scale = (Scale) w; boolean h = scale.horizontal;
            int cross = h ? w.bounds.y + w.bounds.height - 12 : w.bounds.x + w.bounds.width - 12;
            TkRect track = h ? new TkRect(w.bounds.x + 8, cross, scale.trackLength(), 4) : new TkRect(cross, w.bounds.y + 8, 4, scale.trackLength());
            add(out, w, 1, track, "", track.x, track.y, w.lookColor("troughcolor", look, 0x777777), 0, 0, state);
            TkRect thumb = h ? new TkRect(w.bounds.x + scale.position() - 6, cross - 5, 12, 14) : new TkRect(cross - 5, w.bounds.y + scale.position() - 6, 14, 12);
            add(out, w, 2, thumb, "", thumb.x, thumb.y, w.lookColor("slider", look, focused ? 0x6699CC : 0xAAAAAA), 0, 1, state);
            if (scale.showvalue) {
                String text = scale.value == Math.rint(scale.value) ? Long.toString((long) scale.value) : Double.toString(scale.value);
                add(out, w, 3, new TkRect(w.bounds.x + 2, w.bounds.y + 2, Math.max(0, w.bounds.width - 4), 16), text,
                        w.bounds.x + 2, w.bounds.y + 2, background, foreground, 0, state);
            }
        }
        return true;
    }
    /**
     * A Spinbox's two arrows or a Combobox's one, painted over the right end of its entry record
     * (the text runs under them): raised buttons (pressed while held, or while the list is open)
     * with a small triangle drawn row by row.
     */
    static void arrows(Entry e, boolean available, boolean posted, List<TkDisplayList.Item> out) {
        int state = available ? 0 : TkDisplayList.DISABLED, look = e.tree.state(e), ink = e.lookColor("arrowcolor", look, available ? e.lookColor("foreground", look, e.foreground) : 0xA3A3A3);
        int button = e.lookColor("buttonbackground", look, 0xD9D9D9);
        if (e instanceof Spinbox) {
            Spinbox s = (Spinbox) e;
            for (int which = Spinbox.UP; which <= Spinbox.DOWN; which++) {
                TkRect box = s.arrowBox(which);
                boolean pressed = s.pressed == which;
                relief(out, e, which, TkWidget.Kind.BUTTON, box, button, 1, TkRelief.RAISED, state | (pressed ? TkDisplayList.PRESSED : 0));
                triangle(out, e, 2 + 3 * which, box, which == Spinbox.UP, 3, ink, pressed);
            }
        } else {
            Combobox c = (Combobox) e;
            TkRect box = c.arrowBox();
            relief(out, e, 1, TkWidget.Kind.BUTTON, box, button, 1, TkRelief.RAISED, state | (posted ? TkDisplayList.PRESSED : 0));
            triangle(out, e, 2, box, false, 4, ink, posted);
        }
    }

    /** A triangle of {@code size} rows (1, 3, 5 ... px wide) centred in box, pointing up or down; parts first ... first + size - 1. */
    private static void triangle(List<TkDisplayList.Item> out, TkWidget w, int first, TkRect box, boolean up, int size, int ink, boolean pressed) {
        int shift = pressed ? 1 : 0, cx = box.x + box.width / 2 + shift, top = box.y + (box.height - size) / 2 + shift;
        for (int row = 0; row < size; row++) {
            int half = up ? row : size - 1 - row;
            record(out, w, first + row, TkWidget.Kind.FRAME, new TkRect(cx - half, top + row, 2 * half + 1, 1), ink, 0, 0);
        }
    }

    /**
     * At most how many paint records {@code w} can need, whatever the layout or the size of its window,
     * on a display {@code rows} text rows tall: its parts (see special), a window's decorations and
     * taskbar button, the rows a Listbox or Text can show, every Canvas item and Notebook tab.
     */
    static int maxRecords(TkWidget w, int rows) {
        if (w instanceof TkIcon) return ((TkIcon) w).descriptor == null ? 1 : 2;
        if (TkWindow.is(w)) {
            // Its client area; edge, title and three buttons; the four resize hints; its taskbar button; its menubar.
            Menu bar = TkWindow.menubar((Frame) w);
            return 1 + 5 + 4 + 1 + (bar == null ? 0 : 1 + bar.entries.size());
        }
        if (w instanceof Menu) return ((Menu) w).maxRecords(rows);   // posted
        if (w instanceof Menubutton) return 2;
        if (w instanceof Notebook) return 2 + ((Notebook) w).tabs.size();
        if (w instanceof Panedwindow) return ((Panedwindow) w).maxRecords();
        if (w instanceof Labelframe) return 3;
        if (w instanceof Sizegrip) return 1 + Sizegrip.DOTS.length;
        if (w instanceof Listbox) return 1 + Math.min(((Listbox) w).size(), rows);
        if (w instanceof Text) return ((Text) w).maxRecords(rows);
        if (w instanceof Canvas) return 1 + ((Canvas) w).items.size();
        if (w instanceof Checkbutton) return 3;
        if (w instanceof Scrollbar || w instanceof Scale) return 4;
        if (w instanceof Progressbar) return 2;
        if (w instanceof Treeview) return ((Treeview) w).maxRecords(rows);
        if (w instanceof Spinbox) return 1 + 2 * (1 + 3);   // the entry; each arrow's button and triangle rows
        if (w instanceof Combobox) return 1 + 1 + 4 + TkPopup.maxRecords((Combobox) w, rows);   // and its list, when open
        return 1;
    }

    /** At most how many bytes of text {@code w}'s records can carry (see maxRecords); one row shows at most {@code rowBytes}. */
    static long maxBytes(TkWidget w, int rows, int rowBytes) {
        if (w instanceof TkIcon) return ((TkIcon) w).descriptor == null ? 0 : ((TkIcon) w).descriptor.bytes();
        if (w instanceof Listbox) { Listbox list = (Listbox) w; return Math.min(list.bytes, (long) Math.min(list.size(), rows) * rowBytes); }
        if (w instanceof Text) return ((Text) w).maxBytes(rows);
        if (w instanceof Treeview) return ((Treeview) w).maxBytes(rows, rowBytes);
        if (w instanceof Canvas) {
            long bytes = 0;
            for (Canvas.Item item : ((Canvas) w).items.values()) bytes += item.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return bytes;
        }
        long bytes = (w instanceof Entry ? ((Entry) w).paintBytes() : w.textBytes()) + 4;   // its text (an Entry's as shown); a part's glyphs
        if (w instanceof Scale) bytes += 32;   // the value it shows
        if (w instanceof Combobox) bytes += TkPopup.maxBytes((Combobox) w, rows, rowBytes);
        if (TkWindow.is(w)) {
            bytes += 2L * ((Frame) w).window.title.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;   // title bar, taskbar
            Menu bar = TkWindow.menubar((Frame) w);
            if (bar != null) bytes += bar.textBytes();
        }
        if (w instanceof Menu) return ((Menu) w).maxBytes(rows, rowBytes);
        return bytes;
    }

    /** A Menubutton's indicator on its right: classic -indicatoron a small raised bar, ttk a down arrow. */
    static void indicator(Menubutton mb, int bg, int fg, boolean available, List<TkDisplayList.Item> out) {
        if (!mb.showsIndicator()) return;
        int state = available ? 0 : TkDisplayList.DISABLED;
        int right = mb.bounds.x + mb.bounds.width - mb.border - mb.padRight;
        if (mb.ttk) {
            TkRect r = new TkRect(right - Menubutton.INDICATOR, mb.bounds.y + mb.border, Menubutton.INDICATOR, Math.max(1, mb.bounds.height - 2 * mb.border));
            TkRect clip = r.intersect(mb.clip);
            if (!clip.empty()) out.add(new TkDisplayList.Item(mb.id, 1, TkWidget.Kind.LABEL, r, clip, bg, available ? fg : 0xA3A3A3, 0, state,
                    r.x + (r.width - TkFontMetrics.CELL_WIDTH) / 2, r.y + (r.height - TkFontMetrics.LINE_HEIGHT) / 2, "▾", -1, 0, 0, null, 0, TkRelief.FLAT));
        } else {
            TkRect r = new TkRect(right - Menubutton.INDICATOR + 3, mb.bounds.y + (mb.bounds.height - 6) / 2, 10, 6), clip = r.intersect(mb.clip);
            if (!clip.empty()) out.add(new TkDisplayList.Item(mb.id, 1, TkWidget.Kind.FRAME, r, clip, bg, bg, 2, state, r.x, r.y, "", -1, 0, 0, null, 0, TkRelief.RAISED));
        }
    }

    private static void part(List<TkDisplayList.Item> out, TkWidget w, int part, boolean h, int start, int size, String text, int bg, int fg, int state) {
        TkRect r = h ? new TkRect(w.bounds.x + start, w.bounds.y, size, w.bounds.height) : new TkRect(w.bounds.x, w.bounds.y + start, w.bounds.width, size);
        add(out, w, part, r, text, r.x + (r.width - 8) / 2, r.y + (r.height - 16) / 2, bg, fg, 1, state);
    }
    private static void relief(List<TkDisplayList.Item> out, TkWidget w, int part, TkWidget.Kind kind, TkRect rect, int border, int relief, int state) {
        relief(out, w, part, kind, rect, w.background, border, relief, state);
    }
    private static void relief(List<TkDisplayList.Item> out, TkWidget w, int part, TkWidget.Kind kind, TkRect rect, int background, int border, int relief, int state) {
        TkRect r = rect.intersect(w.bounds), clip = r.intersect(w.clip);
        if (!clip.empty()) out.add(new TkDisplayList.Item(w.id, part, kind, r, clip, background, w.foreground, border, state, r.x, r.y, "", -1, 0, 0, null, 0, relief));
    }
    private static void record(List<TkDisplayList.Item> out, TkWidget w, int part, TkWidget.Kind kind, TkRect rect, int bg, int border, int state) {
        TkRect r = rect.intersect(w.bounds), clip = r.intersect(w.clip);
        if (!clip.empty()) out.add(new TkDisplayList.Item(w.id, part, kind, r, clip, bg, bg, border, state, r.x, r.y, "", -1, 0, 0));
    }
    private static void add(List<TkDisplayList.Item> out, TkWidget w, int part, TkRect rect, String text, int tx, int ty, int bg, int fg, int border, int state) {
        TkRect r = rect.intersect(w.bounds), clip = r.intersect(w.clip);
        // Part 0 is the widget itself and has its -relief; the other parts keep their own look.
        if (!clip.empty()) out.add(new TkDisplayList.Item(w.id, part, part == 0 ? w.kind : TkWidget.Kind.LABEL,
                r, clip, bg, fg, border, state, tx, ty, text, -1, 0, 0, null, 0, part == 0 ? w.relief.code() : 0));
    }
}
