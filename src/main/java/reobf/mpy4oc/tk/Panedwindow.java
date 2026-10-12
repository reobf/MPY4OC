package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tk's panedwindow (classic) and ttk::panedwindow: panes side by side (or stacked) with a sash
 * between each two that the player drags. Panes are children of the panedwindow or of its parent
 * (Tk allows siblings); it lays them out itself (winfo_manager() 'panedwindow'). The geometry
 * follows Tk 8.6: ttk's PanedSize/PlaceSashes/ShoveUp/ShoveDown (ttkPanedwindow.c: requested
 * sizes plus the difference shared by -weight, the last pane taking what is left) and the classic
 * ComputeGeometry/ArrangePanes/MoveSash (tkPanedWindow.c: -stretch panes share in proportion
 * to their sizes, -minsize, -sticky, -padx/-pady, -hide), checked against Tk 8.6.14.
 */
public final class Panedwindow extends Frame {
    static final int STRETCH_FIRST = 1, STRETCH_LAST = 2, STRETCH_MIDDLE = 4, STRETCH_ALWAYS = 7, STRETCH_NEVER = 0;
    static final String[] STRETCHES = {"always", "first", "last", "middle", "never"};
    static final int MAX_PANES = 256;

    static final class Pane {
        final TkWidget widget;
        // ttk: -weight; the size it asks for (its requested size until a sash moves), its sash's position.
        int weight, reqSize = -1, sashPos;
        // classic: -width/-height (0 for none), -minsize, -padx/-pady, -sticky (n e s w bits), -stretch, -hide.
        int width, height, minSize, padx, pady, sticky = 15, stretch = STRETCH_LAST;
        boolean hide;
        // classic: the parcel's top left, the sash's, the handle's (from the last layout), and the pane's size along the panes.
        int x, y, sashX, sashY, handleX, handleY, paneWidth, paneHeight;
        Pane(TkWidget widget) { this.widget = widget; }
    }

    final boolean ttk;
    final List<Pane> panes = new ArrayList<>();
    boolean horizontal;
    /** ttk: the sash's thickness from the style (5 in default, 6 in clam). */
    int sashThickness = 5;
    /** classic: -sashwidth, -sashpad, -showhandle, -handlesize, -handlepad, -sashrelief. */
    int sashWidth = 3, sashPad, handleSize = 8, handlePad = 8;
    boolean showHandle;
    Relief sashRelief = Relief.FLAT;
    /** The sash the pointer is on or a player drags (lit), -1 for none; never saved. */
    int litSash = -1;

    Panedwindow(Frame parent, boolean ttk) {
        super(parent.tree, parent, Kind.PANEDWINDOW);
        this.ttk = ttk;
        horizontal = !ttk;           // ttk's default orientation is vertical, the classic one's horizontal
        if (!ttk) border = 1;
    }

    @Override String containerManager() { return "panedwindow"; }
    @Override void release(TkWidget child) {
        int index = indexOf(child);
        if (index >= 0) { panes.remove(index).widget.container = null; tree.changed(true); }
    }

    int indexOf(TkWidget widget) {
        for (int i = 0; i < panes.size(); i++) if (panes.get(i).widget == widget) return i;
        return -1;
    }

    // ---- the pane list --------------------------------------------------------------------

    /** Insert widget at position (it moves there if it is a pane already); options as pane(). */
    void insert(int position, TkWidget widget, Map<String, Object> options) {
        alive();
        int index = indexOf(widget);
        if (index < 0) {
            if (TkWindow.is(widget) || widget == this || widget.parent != this && widget.parent != parent)
                throw new IllegalArgumentException("can't add a widget that is not a child of the panedwindow or of its parent");
            if (panes.size() >= MAX_PANES) throw new IllegalArgumentException("too many panes");
            Pane pane = new Pane(widget);
            Runnable apply = configure(pane, options);
            widget.unmanage();
            widget.container = this;
            apply.run();
            panes.add(Math.max(0, Math.min(position, panes.size())), pane);
        } else {
            Pane pane = panes.get(index);
            configure(pane, options).run();
            panes.remove(index);
            panes.add(Math.max(0, Math.min(position, panes.size())), pane);
        }
        tree.changed(true);
    }

    void forget(int index) {
        alive();
        Pane pane = panes.remove(index);
        pane.widget.container = null;
        tree.changed(true);
    }

    /** A pane's options: ttk -weight; classic -width, -height, -minsize, -padx, -pady, -sticky, -stretch, -hide. */
    void configurePane(int index, Map<String, Object> options) {
        alive();
        configure(panes.get(index), options).run();
        tree.changed(true);
    }

    Map<String, Object> paneOptions(int index) {
        Pane p = panes.get(index);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (ttk) { m.put("weight", p.weight); return m; }
        m.put("width", p.width); m.put("height", p.height); m.put("minsize", p.minSize); m.put("padx", p.padx); m.put("pady", p.pady);
        m.put("sticky", sticky(p.sticky)); m.put("stretch", STRETCHES[stretchIndex(p.stretch)]); m.put("hide", p.hide);
        return m;
    }

    private Runnable configure(Pane pane, Map<String, Object> options) {
        int weight = pane.weight, width = pane.width, height = pane.height, minSize = pane.minSize, padx = pane.padx, pady = pane.pady;
        int sticky = pane.sticky, stretch = pane.stretch;
        boolean hide = pane.hide;
        for (Map.Entry<String, Object> e : options.entrySet()) {
            Object v = e.getValue();
            String key = e.getKey();
            if (ttk && !key.equals("weight") || !ttk && key.equals("weight")) throw new IllegalArgumentException("unknown option \"-" + key + "\"");
            switch (key) {
                case "weight": weight = TkOptions.integer(v, 0, 1 << 20); break;
                case "width": width = TkOptions.integer(v, 0, 8192); break;
                case "height": height = TkOptions.integer(v, 0, 8192); break;
                case "minsize": minSize = TkOptions.integer(v, 0, 8192); break;
                case "padx": padx = TkOptions.integer(v, 0, 1024); break;
                case "pady": pady = TkOptions.integer(v, 0, 1024); break;
                case "sticky": sticky = stickyBits(TkOptions.text(v)); break;
                case "stretch": stretch = stretch(TkOptions.text(v)); break;
                case "hide": hide = TkOptions.bool(v); break;
                default: throw new IllegalArgumentException("unknown option \"-" + key + "\"");
            }
        }
        int w = weight, wd = width, ht = height, ms = minSize, px = padx, py = pady, st = sticky, sr = stretch;
        boolean hd = hide;
        return () -> { pane.weight = w; pane.width = wd; pane.height = ht; pane.minSize = ms; pane.padx = px; pane.pady = py; pane.sticky = st; pane.stretch = sr; pane.hide = hd; };
    }

    static int stickyBits(String value) {
        int bits = 0;
        for (char c : value.toCharArray()) {
            switch (Character.toLowerCase(c)) {
                case 'n': bits |= 1; break;
                case 'e': bits |= 2; break;
                case 's': bits |= 4; break;
                case 'w': bits |= 8; break;
                case ' ': case ',': break;
                default: throw new IllegalArgumentException("bad stickyness value \"" + value + "\": must be a string containing zero or more of n, e, s, and w");
            }
        }
        return bits;
    }
    static String sticky(int bits) {
        return ((bits & 1) != 0 ? "n" : "") + ((bits & 2) != 0 ? "e" : "") + ((bits & 4) != 0 ? "s" : "") + ((bits & 8) != 0 ? "w" : "");
    }
    static int stretch(String value) {
        switch (value) {
            case "always": return STRETCH_ALWAYS;
            case "first": return STRETCH_FIRST;
            case "last": return STRETCH_LAST;
            case "middle": return STRETCH_MIDDLE;
            case "never": return STRETCH_NEVER;
            default: throw new IllegalArgumentException("bad stretch \"" + value + "\": must be always, first, last, middle, or never");
        }
    }
    private static int stretchIndex(int stretch) {
        switch (stretch) {
            case STRETCH_ALWAYS: return 0;
            case STRETCH_FIRST: return 1;
            case STRETCH_LAST: return 2;
            case STRETCH_MIDDLE: return 3;
            default: return 4;
        }
    }

    // ---- geometry: the requested size --------------------------------------------------------

    private int along(TkWidget w) { return horizontal ? w.requestedWidth : w.requestedHeight; }
    private int across(TkWidget w) { return horizontal ? w.requestedHeight : w.requestedWidth; }

    /** The requested size (children measured): ttk's PanedSize, classic ComputeGeometry. */
    int[] natural() {
        if (ttk) {
            int along = 0, across = 0;
            for (Pane p : panes) {
                if (p.reqSize < 0) p.reqSize = along(p.widget);
                along += p.reqSize;
                across = Math.max(across, across(p.widget));
            }
            along += Math.max(0, panes.size() - 1) * sashThickness;
            int w = horizontal ? along : across, h = horizontal ? across : along;
            return new int[] {width > 0 ? width : Math.max(1, w), height > 0 ? height : Math.max(1, h)};
        }
        int bw = border, x = bw, y = bw, reqAcross = 0;
        int[] sash = sashGeometry();
        for (Pane p : panes) {
            if (p.hide) continue;
            p.x = x; p.y = y;
            if (horizontal) {
                p.paneWidth = Math.max(paneSize(p), p.minSize);
                x += p.paneWidth + 2 * p.padx;
                p.sashX = x + sash[1]; p.sashY = y; p.handleX = x + sash[2]; p.handleY = y + handlePad;
                x += sash[0];
                reqAcross = Math.max(reqAcross, (p.height > 0 ? p.height : p.widget.requestedHeight) + 2 * p.pady);
            } else {
                p.paneHeight = Math.max(paneSize(p), p.minSize);
                y += p.paneHeight + 2 * p.pady;
                p.sashX = x; p.sashY = y + sash[1]; p.handleX = x + handlePad; p.handleY = y + sash[2];
                y += sash[0];
                reqAcross = Math.max(reqAcross, (p.width > 0 ? p.width : p.widget.requestedWidth) + 2 * p.padx);
            }
        }
        int w = horizontal ? x - sash[0] + bw : reqAcross + 2 * bw, h = horizontal ? reqAcross + 2 * bw : y - sash[0] + bw;
        return new int[] {width > 0 ? width : Math.max(1, w), height > 0 ? height : Math.max(1, h)};
    }

    /** A classic pane's size along the panes: -width/-height if set, else its widget's request. */
    private int paneSize(Pane p) {
        if (horizontal) return p.width > 0 ? p.width : p.widget.requestedWidth;
        return p.height > 0 ? p.height : p.widget.requestedHeight;
    }

    /** classic: {the sash's whole width with padding and handle, the sash's offset in it, the handle's offset}. */
    private int[] sashGeometry() {
        if (showHandle && handleSize > sashWidth)
            return new int[] {2 * sashPad + handleSize, (handleSize - sashWidth) / 2 + sashPad, sashPad};
        return new int[] {2 * sashPad + sashWidth, sashPad, (sashWidth - handleSize) / 2 + sashPad};
    }

    // ---- geometry: placing the panes ---------------------------------------------------------

    /** Lay the panes out in the panedwindow's box (TkPackLayout.place calls this). */
    void layoutPanes(TkRect box, TkRect clip) {
        if (ttk) placeTtk(box, clip); else placeClassic(box, clip);
    }

    private void placeTtk(TkRect box, TkRect clip) {
        int n = panes.size();
        if (n == 0) return;
        int available = horizontal ? box.width : box.height;
        int reqSize = 0, totalWeight = 0;
        for (Pane p : panes) {
            if (p.reqSize < 0) p.reqSize = along(p.widget);
            reqSize += p.reqSize;
            totalWeight += p.weight * (p.reqSize != 0 ? 1 : 0);
        }
        int difference = available - reqSize - sashThickness * (n - 1), delta = 0, remainder = 0;
        if (totalWeight != 0) {
            delta = difference / totalWeight;
            remainder = difference % totalWeight;
            if (remainder < 0) { delta--; remainder += totalWeight; }
        }
        int pos = 0;
        for (Pane p : panes) {
            int weight = p.weight * (p.reqSize != 0 ? 1 : 0);
            int size = p.reqSize + delta * weight;
            if (weight > remainder) weight = remainder;
            remainder -= weight;
            size += weight;
            if (size < 0) size = 0;
            p.sashPos = pos += size;
            pos += sashThickness;
        }
        shoveUp(n - 1, available);
        pos = 0;
        for (Pane p : panes) {
            int size = p.sashPos - pos;
            if (size > 0) {
                TkRect r = horizontal ? new TkRect(box.x + pos, box.y, size, box.height) : new TkRect(box.x, box.y + pos, box.width, size);
                TkPackLayout.place(p.widget, r, clip);
            }
            pos = p.sashPos + sashThickness;
        }
    }

    private int shoveUp(int i, int pos) {
        Pane pane = panes.get(i);
        if (i == 0) {
            if (pos < 0) pos = 0;
        } else {
            Pane previous = panes.get(i - 1);
            if (pos < previous.sashPos + sashThickness) pos = shoveUp(i - 1, pos - sashThickness) + sashThickness;
        }
        return pane.sashPos = pos;
    }

    private int shoveDown(int i, int pos) {
        Pane pane = panes.get(i);
        if (i == panes.size() - 1) pos = pane.sashPos;   // the sentinel: the available space
        else {
            Pane next = panes.get(i + 1);
            if (pos + sashThickness > next.sashPos) pos = shoveDown(i + 1, pos + sashThickness) - sashThickness;
        }
        return pane.sashPos = pos;
    }

    /** ttk sashpos: move sash index to pos, pushing the others (each pane keeps its new size). The new position. */
    int sashpos(int index, int pos) {
        alive();
        tree.render();
        Pane pane = panes.get(index);
        if (pos <= pane.sashPos) shoveUp(index, pos); else shoveDown(index, pos);
        // AdjustPanes: each pane asks for the size it has now.
        int start = 0;
        for (Pane p : panes) { p.reqSize = Math.max(0, p.sashPos - start); start = p.sashPos + sashThickness; }
        tree.changed(true);
        return pane.sashPos;
    }

    int sashCount() { return Math.max(0, panes.size() - 1); }

    private boolean stretchable(int stretch, int index, int first, int last) {
        if (first == last) return true;
        int requirement = index == first ? STRETCH_FIRST : index == last ? STRETCH_LAST : STRETCH_MIDDLE;
        return (stretch & requirement) == requirement;
    }

    private void placeClassic(TkRect box, TkRect clip) {
        int first = -1, last = -1;
        for (int i = 0; i < panes.size(); i++) if (!panes.get(i).hide) { if (first < 0) first = i; last = i; }
        if (first < 0) return;
        int bw = border, pwWidth = box.width - 2 * bw, pwHeight = box.height - 2 * bw;
        int x = bw, y = bw, stretchReserve = horizontal ? pwWidth : pwHeight;
        int[] sash = sashGeometry();
        int paneDynSize = 0, paneDynMinSize = 0, sashCount = 0;
        for (int i = 0; i < panes.size(); i++) {
            Pane p = panes.get(i);
            if (p.hide) continue;
            int paneSize = horizontal ? p.paneWidth : p.paneHeight;
            stretchReserve -= paneSize + 2 * (horizontal ? p.padx : p.pady);
            if (stretchable(p.stretch, i, first, last)) { paneDynSize += paneSize; paneDynMinSize += p.minSize; }
            if (i != last) { stretchReserve -= sash[0]; sashCount++; }
        }
        for (int i = 0; i < panes.size(); i++) {
            Pane p = panes.get(i);
            if (p.hide) continue;
            int slaveWidth = p.width > 0 ? p.width : p.widget.requestedWidth;
            int slaveHeight = p.height > 0 ? p.height : p.widget.requestedHeight;
            int paneSize = horizontal ? p.paneWidth : p.paneHeight, pwSize = horizontal ? pwWidth : pwHeight;
            if (stretchable(p.stretch, i, first, last)) {
                double frac = paneDynSize > 0 ? (double) paneSize / paneDynSize : (double) paneSize / pwSize;
                paneDynSize -= paneSize;
                paneDynMinSize -= p.minSize;
                int stretchAmount = (int) (frac * stretchReserve);
                if (paneSize + stretchAmount >= p.minSize) { stretchReserve -= stretchAmount; paneSize += stretchAmount; }
                else { stretchReserve += paneSize - p.minSize; paneSize = p.minSize; }
                if (i == last && stretchReserve > 0) { paneSize += stretchReserve; stretchReserve = 0; }
            } else if (stretchReserve + paneDynSize - paneDynMinSize < 0) {
                // Short of more room than the stretchable panes can give up: this one shrinks too.
                if (paneSize + paneDynSize - paneDynMinSize + stretchReserve <= p.minSize) {
                    stretchReserve += paneSize - p.minSize;
                    paneSize = p.minSize;
                } else {
                    paneSize += stretchReserve + paneDynSize - paneDynMinSize;
                    stretchReserve = paneDynMinSize - paneDynSize;
                }
            }
            int paneWidth = horizontal ? paneSize : pwWidth - 2 * p.padx, paneHeight = horizontal ? pwHeight - 2 * p.pady : paneSize;
            int sxReserve = sashCount > 0 && horizontal ? sash[0] * sashCount : 0, syReserve = sashCount > 0 && !horizontal ? sash[0] * sashCount : 0;
            if (pwWidth - sxReserve < x + paneWidth - bw) paneWidth = pwWidth - sxReserve - x + bw;
            if (pwHeight - syReserve < y + paneHeight - bw) paneHeight = pwHeight - syReserve - y + bw;
            slaveWidth = Math.min(slaveWidth, paneWidth);
            slaveHeight = Math.min(slaveHeight, paneHeight);
            p.x = x; p.y = y;
            if (horizontal) {
                x += paneWidth + 2 * p.padx;
                if (x < bw) x = bw;
                p.sashX = x + sash[1]; p.sashY = y; p.handleX = x + sash[2]; p.handleY = y + handlePad;
                x += sash[0];
            } else {
                y += paneHeight + 2 * p.pady;
                if (y < bw) y = bw;
                p.sashX = x; p.sashY = y + sash[1]; p.handleX = x + handlePad; p.handleY = y + sash[2];
                y += sash[0];
            }
            // AdjustForSticky.
            int sx = p.x, sy = p.y, diffx = Math.max(0, paneWidth - slaveWidth), diffy = Math.max(0, paneHeight - slaveHeight);
            if ((p.sticky & 2) != 0 && (p.sticky & 8) != 0) slaveWidth += diffx;
            if ((p.sticky & 1) != 0 && (p.sticky & 4) != 0) slaveHeight += diffy;
            if ((p.sticky & 8) == 0) sx += (p.sticky & 2) != 0 ? diffx : diffx / 2;
            if ((p.sticky & 1) == 0) sy += (p.sticky & 4) != 0 ? diffy : diffy / 2;
            sx += p.padx; sy += p.pady;
            if (slaveWidth > 0 && slaveHeight > 0 && !(horizontal ? sx - bw > pwWidth : sy - bw > pwHeight))
                TkPackLayout.place(p.widget, new TkRect(box.x + sx, box.y + sy, slaveWidth, slaveHeight), clip);
            sashCount--;
        }
    }

    /** classic sash place: sash index's start to (x, y) as far as -minsize lets it (MoveSash). */
    void sashPlace(int index, int x, int y) {
        alive();
        tree.render();
        moveSash(index, (horizontal ? x : y) - (horizontal ? panes.get(index).sashX : panes.get(index).sashY));
        tree.changed(true);
    }

    private void moveSash(int sash, int diff) {
        if (diff == 0) return;
        int sashOffset = sashGeometry()[1];
        for (Pane p : panes) {
            if (p.hide) continue;
            if (horizontal) p.paneWidth = p.width = p.sashX - sashOffset - p.x - 2 * p.padx;
            else p.paneHeight = p.height = p.sashY - sashOffset - p.y - 2 * p.pady;
        }
        int nextSash = sash + 1;
        while (nextSash < panes.size() - 1 && panes.get(nextSash).hide) nextSash++;
        int expand, reduceFirst, reduceLast, step;
        if (diff > 0) { expand = sash; reduceFirst = nextSash; reduceLast = panes.size(); step = 1; }
        else { diff = -diff; expand = nextSash; reduceFirst = sash; reduceLast = -1; step = -1; }
        int reserve = 0;
        for (int i = reduceFirst; i != reduceLast; i += step) {
            Pane p = panes.get(i);
            if (!p.hide) reserve += (horizontal ? p.width : p.height) - p.minSize;
        }
        if (reserve <= 0) return;
        if (diff > reserve) diff = reserve;
        Pane grown = panes.get(expand);
        if (horizontal) grown.paneWidth = grown.width += diff; else grown.paneHeight = grown.height += diff;
        for (int i = reduceFirst; i != reduceLast; i += step) {
            Pane p = panes.get(i);
            if (p.hide) continue;
            int size = horizontal ? p.width : p.height;
            if (diff > size - p.minSize) { diff -= size - p.minSize; size = p.minSize; }
            else { size -= diff; i = reduceLast - step; }
            if (horizontal) p.paneWidth = p.width = size; else p.paneHeight = p.height = size;
        }
    }

    /** classic: whether sash index exists (a visible pane before another). */
    boolean validSash(int index) { return index >= 0 && index < panes.size() - 1 && !panes.get(index).hide; }

    int[] sashCoord(int index) { tree.render(); Pane p = panes.get(index); return new int[] {p.sashX, p.sashY}; }

    // ---- the pointer ------------------------------------------------------------------------

    /** The sash at (x, y) in display coordinates, or -1; for classic, -2 - index for a handle. */
    int sashAt(int x, int y) {
        TkRect b = bounds;
        int lx = x - b.x, ly = y - b.y;
        if (ttk) {
            if (lx < 0 || ly < 0 || lx >= b.width || ly >= b.height) return -1;
            int along = horizontal ? lx : ly;
            for (int i = 0; i < panes.size() - 1; i++) {
                int pos = panes.get(i).sashPos;
                if (along >= pos && along < pos + sashThickness) return i;
            }
            return -1;
        }
        int sashLong = (horizontal ? b.height : b.width) - 2 * border;
        int size, lpad, rpad;
        if (showHandle && handleSize > sashWidth) {
            size = handleSize; lpad = (handleSize - sashWidth) / 2; rpad = handleSize - lpad; lpad += sashPad; rpad += sashPad;
        } else { size = sashWidth; lpad = rpad = sashPad; }
        int last = -1;
        for (int i = 0; i < panes.size(); i++) if (!panes.get(i).hide) last = i;
        for (int i = 0; i < panes.size() - 1; i++) {
            Pane p = panes.get(i);
            if (p.hide || i == last) continue;
            boolean in = horizontal
                    ? p.sashX - lpad <= lx && lx <= p.sashX + rpad + size && p.sashY <= ly && ly <= p.sashY + sashLong
                    : p.sashX <= lx && lx <= p.sashX + sashLong && p.sashY - lpad <= ly && ly <= p.sashY + rpad + size;
            if (!in) continue;
            if (showHandle && (horizontal ? p.handleY <= ly && ly <= p.handleY + handleSize : p.handleX <= lx && lx <= p.handleX + handleSize)) return -2 - i;
            return i;
        }
        return -1;
    }

    /** The sash's position along the panes, in display coordinates (for a drag's start). */
    int sashStart(int index) {
        Pane p = panes.get(index);
        if (ttk) return (horizontal ? bounds.x : bounds.y) + p.sashPos;
        return (horizontal ? bounds.x + p.sashX : bounds.y + p.sashY);
    }

    /** A player's drag of sash index to along (display coordinates of the sash's start). */
    void dragSash(int index, int along) {
        if (index >= panes.size() - 1) return;
        if (ttk) sashpos(index, along - (horizontal ? bounds.x : bounds.y));
        else if (validSash(index)) sashPlace(index, along - bounds.x, along - bounds.y);
    }

    // ---- painting ---------------------------------------------------------------------------

    /** The sashes (and classic handles): the background shows between panes; a lit sash is drawn in the hint colour. */
    void paintSashes(List<TkDisplayList.Item> out, int background, int state) {
        int part = 1;
        for (int i = 0; i < panes.size() - 1; i++) {
            Pane p = panes.get(i);
            TkRect r;
            if (ttk) r = horizontal ? new TkRect(bounds.x + p.sashPos, bounds.y, sashThickness, bounds.height)
                    : new TkRect(bounds.x, bounds.y + p.sashPos, bounds.width, sashThickness);
            else {
                if (!validSash(i) || i == lastVisible()) continue;
                r = horizontal ? new TkRect(bounds.x + p.sashX, bounds.y + p.sashY, sashWidth, bounds.height - 2 * border)
                        : new TkRect(bounds.x + p.sashX, bounds.y + p.sashY, bounds.width - 2 * border, sashWidth);
            }
            TkRect clipped = r.intersect(clip);
            boolean lit = i == litSash;
            if (!clipped.empty() && (lit || !ttk && sashRelief != Relief.FLAT))
                out.add(new TkDisplayList.Item(id, part, Kind.FRAME, r, clipped, lit ? TkWindow.HINT : background, lit ? TkWindow.HINT : background,
                        lit ? 0 : 2, state, r.x, r.y, "", -1, 0, 0, null, 0, lit ? TkRelief.FLAT : sashRelief.code()));
            part++;
            if (!ttk && showHandle) {
                TkRect h = new TkRect(bounds.x + p.handleX, bounds.y + p.handleY, handleSize, handleSize), hc = h.intersect(clip);
                if (!hc.empty()) out.add(new TkDisplayList.Item(id, part, Kind.FRAME, h, hc, background, background, 2, state, h.x, h.y, "", -1, 0, 0, null, 0, TkRelief.RAISED));
            }
            part++;
        }
    }

    private int lastVisible() {
        int last = -1;
        for (int i = 0; i < panes.size(); i++) if (!panes.get(i).hide) last = i;
        return last;
    }

    /** Most records: its background and two per sash. */
    int maxRecords() { return 1 + 2 * panes.size(); }
}
