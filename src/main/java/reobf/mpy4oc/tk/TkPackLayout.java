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
        if (widget instanceof Frame) {
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
            w = Math.max(w, usedX) + 2 * (widget.padx + widget.border);
            h = Math.max(h, usedY) + 2 * (widget.pady + widget.border);
            if (!frame.gridded.isEmpty()) {
                int[] cols = TkGridLayout.resolve(frame, true), rows = TkGridLayout.resolve(frame, false);
                w = TkGridLayout.total(cols) + 2 * (widget.padx + widget.border);
                h = TkGridLayout.total(rows) + 2 * (widget.pady + widget.border);
            }
            if (frame.gridded.isEmpty() ? !frame.propagate || frame.packed.isEmpty() : !frame.gridPropagate) {
                w = widget.width > 0 ? widget.width : w;
                h = widget.height > 0 ? widget.height : h;
            }
        } else if (widget instanceof Text) {
            Text text = (Text) widget;
            w = text.columns * 8 + 2 * (text.padx + text.border);
            h = text.lines * 16 + 2 * (text.pady + text.border);
        } else if (widget instanceof Canvas) {
            w = widget.width + 2 * widget.border; h = widget.height + 2 * widget.border;
        } else if (widget instanceof Listbox) {
            Listbox list = (Listbox) widget;
            w = (list.columns == 0 ? list.maxWidth() : list.columns * 8) + 2 * (list.padx + list.border);
            h = (list.lines == 0 ? list.size() : list.lines) * 16 + 2 * (list.pady + list.border);
        } else if (widget instanceof Scrollbar) {
            boolean horizontal = ((Scrollbar) widget).horizontal;
            w = horizontal ? 100 : widget.width; h = horizontal ? widget.width : 100;
        } else if (widget instanceof Scale) {
            Scale scale = (Scale) widget;
            w = scale.horizontal ? scale.length : scale.showvalue ? 64 : 24;
            h = scale.horizontal ? scale.showvalue ? 44 : 24 : scale.length;
        } else {
            Label label = (Label) widget;
            w = widget.width > 0 ? widget.width : (label.columns > 0 ? label.columns * TkFontMetrics.CELL_WIDTH
                    : TkFontMetrics.width(label.paintText())) + 2 * (widget.padx + widget.border) + (widget instanceof Checkbutton ? 20 : 0);
            h = widget.height > 0 ? widget.height : (label.lines > 0 ? label.lines * TkFontMetrics.LINE_HEIGHT
                    : TkFontMetrics.height(label.paintText())) + 2 * (widget.pady + widget.border);
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
        int insetX = frame.padx + frame.border, insetY = frame.pady + frame.border;
        TkRect cavity = new TkRect(bounds.x + insetX, bounds.y + insetY,
                Math.max(0, bounds.width - 2 * insetX), Math.max(0, bounds.height - 2 * insetY));
        TkRect clip = cavity.intersect(widget.clip);
        TkGridLayout.layout(frame, cavity, clip);
        TkPlace.layout(frame);
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
