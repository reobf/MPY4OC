package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Integer constraint layout, following Tk 8.6's grid boundary algorithm.
 * Adapted from tkGrid.c. Copyright (c) 1996-1997 Sun Microsystems, Inc.
 * See docs/licenses/tk.txt for the upstream license.
 */
final class TkGridLayout {
    private TkGridLayout() {}
    static int total(int[] offsets) { return offsets.length == 0 ? 0 : offsets[offsets.length - 1]; }
    static int count(Frame frame, boolean horizontal) {
        int count = 0;
        for (int index : (horizontal ? frame.columns : frame.rows).keySet()) count = Math.max(count, index + 1);
        for (TkWidget w : frame.gridded) count = Math.max(count, horizontal ? w.grid.column + w.grid.columnspan : w.grid.row + w.grid.rowspan);
        return count;
    }
    private static final class Span {
        final int start, end, size;
        Span(int start, int end, int size) { this.start = start; this.end = end; this.size = size; }
    }
    // Arrays have a leading zero boundary; slot i occupies [boundary i-1, boundary i].
    static int[] resolve(Frame frame, boolean horizontal) {
        int n = count(frame, horizontal);
        int[] size = new int[n + 1], weight = new int[n + 1], pad = new int[n + 1];
        int[] lo = new int[n + 1], hi = new int[n + 1];
        Map<Integer, TkGrid.Slot> config = horizontal ? frame.columns : frame.rows;
        for (Map.Entry<Integer, TkGrid.Slot> e : config.entrySet()) {
            int i = e.getKey() + 1; TkGrid.Slot s = e.getValue(); size[i] = s.minsize; weight[i] = s.weight; pad[i] = s.pad;
        }
        List<Span> spans = new ArrayList<>();
        for (TkWidget w : frame.gridded) {
            TkGrid g = w.grid;
            int start = horizontal ? g.column : g.row, span = horizontal ? g.columnspan : g.rowspan;
            int wanted = horizontal ? w.requestedWidth + g.left + g.right + 2 * g.ipadx
                    : w.requestedHeight + g.top + g.bottom + 2 * g.ipady;
            if (span == 1) size[start + 1] = Math.max(size[start + 1], wanted + pad[start + 1]);
            else spans.add(new Span(start, start + span, wanted));
        }
        for (int i = 1; i <= n; i++) {
            lo[i] = lo[i - 1] + size[i];
            for (Span s : spans) if (s.end == i) lo[i] = Math.max(lo[i], lo[s.start] + s.size);
        }
        java.util.Arrays.fill(hi, lo[n]); hi[0] = 0;
        int offset = lo[n];
        for (int i = n; i > 1; i--) {
            for (Span s : spans) if (s.end == i && s.start > 0) hi[s.start] = Math.min(hi[s.start], offset - s.size);
            offset -= size[i]; offset = hi[i - 1] = Math.min(offset, hi[i - 1]);
        }
        for (int start = 1; start <= n;) {
            if (lo[start] == hi[start]) { start++; continue; }
            int end = start + 1;
            while (end < n && lo[end] != hi[end]) end++;
            int weights = 0, need = 0;
            for (int i = start; i <= end; i++) { weights += weight[i]; need += size[i]; }
            boolean equal = weights == 0;
            if (equal) weights = end - start + 1;
            int have = hi[end] - lo[start - 1];
            while (true) {
                int previous = lo[start - 1], cumulative = 0, grown = 0, i;
                for (i = start; i <= end; i++) {
                    int wi = equal ? 1 : weight[i]; cumulative += wi;
                    int growth = share(have - need, cumulative, weights) - grown; grown += growth;
                    if (wi > 0 && previous + size[i] + growth > hi[i]) {
                        int next = share(hi[i] - size[i] - previous, weights, wi);
                        if (next > weights) next = next / weights * weights;
                        if (next <= 0) {
                            next = have - need - 1;
                            if (next > 3 * weights) next = next * 3 / 4;
                            if (next > weights) next = next / weights * weights;
                            next = Math.max(1, next);
                        }
                        have = next + need; break;
                    }
                    previous = Math.max(previous + size[i] + growth, lo[i]);
                }
                if (i > end) break;
            }
            int cumulative = 0, grown = 0;
            for (int i = start; i <= end; i++) {
                cumulative += equal ? 1 : weight[i];
                int growth = share(have - need, cumulative, weights) - grown; grown += growth;
                size[i] += growth; lo[i] = Math.max(lo[i], lo[i - 1] + size[i]);
            }
            for (int i = end; i > start; i--) hi[i - 1] = Math.min(hi[i - 1], hi[i] - size[i]);
        }
        return java.util.Arrays.copyOfRange(lo, 1, n + 1);
    }
    private static int share(int amount, int weight, int total) { return (int) ((long) amount * weight / total); }
    private static int at(int[] offsets, int index) { return index == 0 ? 0 : offsets[index - 1]; }

    static void fit(int[] offsets, int available, Map<Integer, TkGrid.Slot> slots) {
        int diff = available - total(offsets), weights = 0;
        for (TkGrid.Slot slot : slots.values()) weights += slot.weight;
        if (diff == 0 || weights == 0) return;
        if (diff > 0) {
            int cumulative = 0;
            for (int i = 0; i < offsets.length; i++) {
                cumulative += slots.getOrDefault(i, TkGrid.Slot.DEFAULT).weight;
                offsets[i] += share(diff, cumulative, weights);
            }
            return;
        }
        int[] minimum = new int[offsets.length]; int minTotal = 0;
        for (int i = 0; i < offsets.length; i++) {
            TkGrid.Slot s = slots.getOrDefault(i, TkGrid.Slot.DEFAULT);
            minimum[i] = s.weight == 0 ? offsets[i] - at(offsets, i) : s.minsize;
            minTotal += minimum[i];
        }
        if (available <= minTotal) {
            int sum = 0;
            for (int i = 0; i < offsets.length; i++) { sum += minimum[i]; offsets[i] = sum; }
            return;
        }
        int[] active = new int[offsets.length];
        while (diff < 0) {
            weights = 0;
            for (int i = 0; i < offsets.length; i++) {
                active[i] = offsets[i] - at(offsets, i) > minimum[i] ? slots.getOrDefault(i, TkGrid.Slot.DEFAULT).weight : 0;
                weights += active[i];
            }
            if (weights == 0) break;
            int step = diff;
            for (int i = 0; i < offsets.length; i++) if (active[i] > 0)
                step = Math.max(step, share(minimum[i] - offsets[i] + at(offsets, i), weights, active[i]));
            int cumulative = 0;
            for (int i = 0; i < offsets.length; i++) { cumulative += active[i]; offsets[i] += share(step, cumulative, weights); }
            diff -= step;
        }
    }
    static void layout(Frame frame, TkRect cavity, TkRect clip) {
        int[] cols = resolve(frame, true), rows = resolve(frame, false);
        fit(cols, cavity.width, frame.columns); fit(rows, cavity.height, frame.rows);
        frame.gridColumns = cols; frame.gridRows = rows;
        frame.gridX = cavity.x + (cavity.width - total(cols)) * frame.gridAnchor.x / 2;
        frame.gridY = cavity.y + (cavity.height - total(rows)) * frame.gridAnchor.y / 2;
        for (TkWidget child : frame.gridded) {
            TkGrid g = child.grid;
            int x = frame.gridX + at(cols, g.column) + g.left, y = frame.gridY + at(rows, g.row) + g.top;
            int width = at(cols, g.column + g.columnspan) - at(cols, g.column) - g.left - g.right;
            int height = at(rows, g.row + g.rowspan) - at(rows, g.row) - g.top - g.bottom;
            int w = Math.min(width, child.requestedWidth + 2 * g.ipadx), h = Math.min(height, child.requestedHeight + 2 * g.ipady);
            if (g.has('e') && g.has('w')) w = width;
            if (g.has('n') && g.has('s')) h = height;
            if (!g.has('w')) x += (width - w) / (g.has('e') ? 1 : 2);
            if (!g.has('n')) y += (height - h) / (g.has('s') ? 1 : 2);
            if (w > 0 && h > 0) TkPackLayout.place(child, new TkRect(x, y, w, h), clip);
        }
    }
}
