package reobf.mpy4oc.tk;

import java.util.List;

/** Parcel/cavity packing, including look-ahead expansion for mixed directions.
 * Semantics: https://www.tcl-lang.org/man/tcl8.6/TkCmd/pack.htm
 */
final class TkPackLayout {
    private TkPackLayout() {}

    static void layout(Frame root, int width, int height) {
        measure(root);
        place(root, new TkRect(0, 0, width, height), new TkRect(0, 0, width, height));
    }

    private static void measure(TkWidget widget) {
        int w, h;
        if (widget instanceof Notebook) {
            Notebook book = (Notebook) widget;
            for (TkWidget child : book.children) measure(child);
            int[] size = book.natural(); w = size[0]; h = size[1];
        } else if (widget instanceof Panedwindow) {
            Panedwindow paned = (Panedwindow) widget;
            for (TkWidget child : paned.children) measure(child);
            // A pane may be a sibling, measured by the parent only after this.
            for (Panedwindow.Pane pane : paned.panes) if (pane.widget.parent != paned) measure(pane.widget);
            int[] size = paned.natural(); w = size[0]; h = size[1];
        } else if (widget instanceof Frame) {
            Frame frame = (Frame) widget;
            for (TkWidget child : frame.children) measure(child);
            w = h = 1;
            int usedX = 0, usedY = 0;
            for (TkWidget child : frame.packed) {
                if (child.pack.side.vertical()) {
                    w = Math.max(w, usedX + outerWidth(child));
                    usedY += outerHeight(child);
                } else {
                    h = Math.max(h, usedY + outerHeight(child));
                    usedX += outerWidth(child);
                }
            }
            w = Math.max(w, usedX) + widget.insetWidth();
            h = Math.max(h, usedY) + widget.insetHeight();
            if (!frame.gridded.isEmpty()) {
                int[] cols = TkGridLayout.resolve(frame, true), rows = TkGridLayout.resolve(frame, false);
                w = TkGridLayout.total(cols) + widget.insetWidth();
                h = TkGridLayout.total(rows) + widget.insetHeight();
            }
            if (frame instanceof Labelframe && (!frame.packed.isEmpty() || !frame.gridded.isEmpty())) {
                // Wide (or tall) enough for the label between the corners.
                Labelframe labelframe = (Labelframe) frame; int least = labelframe.minAlong();
                if (labelframe.vertical()) w = Math.max(w, least); else h = Math.max(h, least);
            }
            if (frame.gridded.isEmpty() ? !frame.propagate || frame.packed.isEmpty() : !frame.gridPropagate) {
                w = widget.width > 0 ? widget.width : w;
                h = widget.height > 0 ? widget.height : h;
            }
        } else if (widget instanceof Text) {
            Text text = (Text) widget;
            w = text.columns * 8 + text.insetWidth();
            h = text.lines * 16 + text.insetHeight();
        } else if (widget instanceof Canvas) {
            w = widget.width + 2 * widget.border; h = widget.height + 2 * widget.border;
        } else if (widget instanceof Listbox) {
            Listbox list = (Listbox) widget;
            w = (list.columns == 0 ? list.maxWidth() : list.columns * 8) + list.insetWidth();
            h = (list.lines == 0 ? list.size() : list.lines) * 16 + list.insetHeight();
        } else if (widget instanceof Scrollbar) {
            boolean horizontal = ((Scrollbar) widget).horizontal;
            w = horizontal ? 100 : widget.width; h = horizontal ? widget.width : 100;
        } else if (widget instanceof Scale) {
            Scale scale = (Scale) widget;
            w = scale.horizontal ? scale.length : scale.showvalue ? 64 : 24;
            h = scale.horizontal ? scale.showvalue ? 44 : 24 : scale.length;
        } else if (widget instanceof Treeview) {
            Treeview tree = (Treeview) widget;   // the columns side by side, -height rows under the headings
            w = tree.treeWidth() + tree.insetWidth();
            h = tree.natural() + tree.insetHeight();
        } else if (widget instanceof Separator) {
            w = h = 2; // Tk's separator element; pack or grid it with fill to stretch the line
        } else if (widget instanceof Menu) {
            w = h = 1;   // never laid out: shown as a menubar or posted
        } else if (widget instanceof Sizegrip) {
            w = h = Sizegrip.SIZE;
        } else if (widget instanceof Progressbar) {
            Progressbar bar = (Progressbar) widget;
            w = bar.horizontal ? bar.length : 18;
            h = bar.horizontal ? 18 : bar.length;
        } else if (widget instanceof Label && widget.tree.images.get(((Label) widget).image) != null) {
            // A PhotoImage label sizes to its image (Tk measures image labels in pixels).
            TkImage image = widget.tree.images.get(((Label) widget).image);
            w = image.width + widget.insetWidth();
            h = image.height + widget.insetHeight();
        } else {
            Label label = (Label) widget;
            w = widget.width > 0 ? widget.width : (label.columns > 0 ? label.columns * TkFontMetrics.CELL_WIDTH
                    : TkFontMetrics.width(label.paintText())) + widget.insetWidth() + (widget instanceof Checkbutton ? 20 : 0);
            if (widget.width == 0 && label.minColumns > 0) w = Math.max(w, label.minColumns * TkFontMetrics.CELL_WIDTH + widget.insetWidth());
            h = widget.height > 0 ? widget.height : (label.lines > 0 ? label.lines * TkFontMetrics.LINE_HEIGHT
                    : TkFontMetrics.height(label.paintText())) + widget.insetHeight();
        }
        widget.requestedWidth = Math.max(1, Math.min(8192, w));
        widget.requestedHeight = Math.max(1, Math.min(8192, h));
    }

    private static int outerWidth(TkWidget w) { return w.requestedWidth + 2 * w.pack.ipadx + w.pack.left + w.pack.right; }
    private static int outerHeight(TkWidget w) { return w.requestedHeight + 2 * w.pack.ipady + w.pack.top + w.pack.bottom; }

    private static void hide(TkWidget w) {
        w.bounds = w.clip = TkRect.EMPTY;
        if (w instanceof Frame) for (TkWidget child : ((Frame) w).children) hide(child);
    }

    static void place(TkWidget widget, TkRect bounds, TkRect parentClip) {
        widget.bounds = bounds;
        widget.clip = bounds.intersect(parentClip);
        if (!(widget instanceof Frame)) return;
        Frame frame = (Frame) widget;
        for (TkWidget child : frame.children) hide(child);
        TkRect cavity = frame.inner();
        TkRect clip = cavity.intersect(widget.clip);
        TkGridLayout.layout(frame, cavity, clip);
        TkPlace.layout(frame);
        if (frame instanceof Panedwindow) ((Panedwindow) frame).layoutPanes(frame.bounds, widget.clip);
        if (frame instanceof Notebook) {
            // Only the selected tab's page is mapped, inside the page border.
            Notebook book = (Notebook) frame; book.layoutTabs();
            if (book.current >= 0) {
                Notebook.Tab tab = book.tabs.get(book.current);
                place(tab.page, book.pageBox(tab), book.clientInner().intersect(widget.clip));
            }
        }
        if (frame instanceof Labelframe && ((Labelframe) frame).labelWidget != null) {
            // The label widget sits in the label's box across the border: clipped to the frame, not its inside.
            Labelframe labelframe = (Labelframe) frame;
            place(labelframe.labelWidget, labelframe.labelBox(), widget.clip);
        }
        List<TkWidget> packed = frame.packed;
        for (int i = 0; i < packed.size(); i++) {
            TkWidget child = packed.get(i);
            TkPack p = child.pack;
            if (cavity.empty()) break;
            boolean vertical = p.side.vertical();
            int wanted = vertical ? outerHeight(child) : outerWidth(child);
            int available = vertical ? cavity.height : cavity.width;
            int amount = Math.min(available, wanted + (p.expand ? expansion(packed, i, available, vertical) : 0));
            TkRect parcel;
            if (vertical) {
                parcel = new TkRect(cavity.x, p.side == TkPack.Side.TOP ? cavity.y : cavity.y + cavity.height - amount,
                        cavity.width, amount);
                cavity = new TkRect(cavity.x, cavity.y + (p.side == TkPack.Side.TOP ? amount : 0),
                        cavity.width, cavity.height - amount);
            } else {
                parcel = new TkRect(p.side == TkPack.Side.LEFT ? cavity.x : cavity.x + cavity.width - amount,
                        cavity.y, amount, cavity.height);
                cavity = new TkRect(cavity.x + (p.side == TkPack.Side.LEFT ? amount : 0), cavity.y,
                        cavity.width - amount, cavity.height);
            }
            int availableW = Math.max(0, parcel.width - p.left - p.right);
            int availableH = Math.max(0, parcel.height - p.top - p.bottom);
            int w = p.fill.x() ? availableW : Math.min(availableW, child.requestedWidth + 2 * p.ipadx);
            int h = p.fill.y() ? availableH : Math.min(availableH, child.requestedHeight + 2 * p.ipady);
            if (w == 0 || h == 0) continue;
            place(child, new TkRect(parcel.x + p.left + (availableW - w) * p.anchor.x / 2,
                    parcel.y + p.top + (availableH - h) * p.anchor.y / 2, w, h), clip);
        }
    }

    private static int expansion(List<TkWidget> packed, int start, int space, boolean vertical) {
        int count = 0, limit = space, remaining = space;
        for (int i = start; i < packed.size(); i++) {
            TkWidget child = packed.get(i);
            int size = vertical ? outerHeight(child) : outerWidth(child);
            if (child.pack.side.vertical() == vertical) {
                remaining -= size;
                if (child.pack.expand) count++;
            } else if (count > 0) limit = Math.min(limit, (remaining - size) / count);
        }
        return Math.max(0, Math.min(limit, count == 0 ? 0 : remaining / count));
    }
}
