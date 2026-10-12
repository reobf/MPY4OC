package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;

/**
 * Tk's spinbox and ttk::spinbox: an Entry with two arrows at its right end that step its value
 * (Up and Down do too), through -values or from -from to -to by -increment. Classic and ttk step
 * as Tk 8.6 does (tkEntry.c SpinboxInvoke; ttk/spinbox.tcl Spin); see {@link #step}.
 */
public final class Spinbox extends Entry {
    /** The arrows' column, inside the border. */
    static final int ARROWS = 14;
    static final int UP = 1, DOWN = 2;
    final boolean ttk;
    double from, to, increment = 1;
    final List<String> values = new ArrayList<>();
    boolean wrap;
    String format = "";
    /** Classic: Tk's eIndex into -values. ttk: State(values.index), -1 at first, and the value it last set. */
    int valueIndex;
    String lastValue = "";
    /** The arrow held down (UP, DOWN), drawn pressed; never saved. */
    int pressed;
    /** ttk: the program binds <<Increment>> or <<Decrement>> on it, so a player's step goes to the program, which may stop it. */
    boolean delegate;

    Spinbox(Frame parent, boolean ttk) {
        super(parent, Kind.SPINBOX);
        this.ttk = ttk;
        if (ttk) valueIndex = -1;
    }

    /** The text ends before the arrows. */
    @Override int insetRight() { return super.insetRight() + ARROWS; }

    TkRect arrowBox(int which) {
        int x = bounds.x + bounds.width - border - ARROWS, top = bounds.y + border, height = Math.max(0, bounds.height - 2 * border);
        return which == UP ? new TkRect(x, top, ARROWS, height / 2) : new TkRect(x, top + height / 2, ARROWS, height - height / 2);
    }

    /** The arrow at (x, y) on the display: UP, DOWN or 0. */
    int arrowAt(int x, int y) {
        if (!bounds.contains(x, y) || x < bounds.x + bounds.width - border - ARROWS) return 0;
        return y < bounds.y + border + Math.max(0, bounds.height - 2 * border) / 2 ? UP : DOWN;
    }

    /** Tk's identify at (x, y) in the widget: classic buttonup, buttondown, entry; ttk uparrow, downarrow, textarea; "" outside. */
    String identify(int x, int y) {
        bounds();
        if (x < 0 || y < 0 || x >= bounds.width || y >= bounds.height) return "";
        int arrow = arrowAt(bounds.x + x, bounds.y + y);
        if (arrow == 0) return ttk ? "textarea" : "entry";
        return ttk ? (arrow == UP ? "uparrow" : "downarrow") : (arrow == UP ? "buttonup" : "buttondown");
    }

    /**
     * One step up (+1) or down (-1): the new value is set (ttk then selects it all, the cursor at
     * its end). Returns the value before. Disabled, nothing happens (null); classic Tk's
     * -increment 0 or -from equal to -to change nothing but still count as a step.
     */
    String step(int dir) {
        alive();
        if (!enabled) return null;
        String before = text(), after = ttk ? ttkStep(dir) : classicStep(dir > 0);
        if (after != null) value(after);
        if (ttk) { selectionRange(0, length()); cursor(length()); }
        tree.changed(false);
        return before;
    }

    private String classicStep(boolean up) {
        if (Math.abs(increment) <= 1e-9) return null;
        if (!values.isEmpty()) {
            String current = text();
            if (valueIndex >= values.size() || !values.get(valueIndex).equals(current)) {
                // Tk compares the text's length in characters with each value's in bytes, so it only
                // finds text that is plain ASCII; otherwise it keeps its index.
                int found = ascii(current) ? values.indexOf(current) : -1;
                if (found >= 0) valueIndex = found;
            }
            if (up) { if (++valueIndex >= values.size()) valueIndex = wrap ? 0 : values.size() - 1; }
            else if (--valueIndex < 0) valueIndex = wrap ? values.size() - 1 : 0;
            return values.get(valueIndex);
        }
        if (Math.abs(from - to) < 1e-9) return null;
        Double scanned = TkNumberFormat.scan(text(), true);
        double v;
        if (scanned == null) v = from;
        else if (up) { v = scanned + increment; if (v > to) v = wrap ? from : to; else if (v < from) v = from; }
        else { v = scanned - increment; if (v < from) v = wrap ? to : from; else if (v > to) v = to; }
        return TkNumberFormat.classic(format, from, to, increment, v);
    }

    private static boolean ascii(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 0x80) return false;
        return true;
    }

    private String ttkStep(int dir) {
        if (!values.isEmpty()) {
            String value = text();
            int current = value.equals(lastValue) ? valueIndex : values.indexOf(value);
            valueIndex = adjust(current + dir, 0, values.size() - 1);
            lastValue = values.get(valueIndex);
            return lastValue;
        }
        Double scanned = TkNumberFormat.scan(text(), false);
        double v = scanned == null ? from : scanned + dir * increment;
        v = v < from ? (wrap ? to : from) : v > to ? (wrap ? from : to) : v;
        String fmt = format;
        if (fmt.isEmpty()) {
            // ttk::spinbox::FormatValue guesses the decimals from -increment.
            double delta = Math.abs(increment);
            fmt = 0 < delta && delta < 1 ? "%." + (int) Math.ceil(-Math.log10(delta) - 1e-12) + "f" : "%.0f";
        }
        return TkNumberFormat.tcl(fmt, v);
    }

    private int adjust(int v, int min, int max) { return v < min ? (wrap ? max : min) : v > max ? (wrap ? min : max) : v; }

    /**
     * New options, as Tk's ConfigureEntry applies them: classic -values (given and not empty)
     * put its first value in the entry; otherwise a changed -from or -to, or a new -format,
     * brings the value into range and formats it. ttk changes no value.
     */
    void configure(double from, double to, double increment, String format, List<String> values, boolean wrap, boolean newFormat) {
        double oldFrom = this.from, oldTo = this.to;
        this.from = from; this.to = to; this.increment = increment; this.format = format; this.wrap = wrap;
        if (values != null) {
            this.values.clear(); this.values.addAll(values);
            if (!ttk) valueIndex = 0;
        }
        if (ttk) return;
        if (values != null && !values.isEmpty()) value(values.get(0));
        else if (this.values.isEmpty() && Math.abs(from - to) >= 1e-9
                && (newFormat || Math.abs(from - oldFrom) >= 1e-9 || Math.abs(to - oldTo) >= 1e-9)) {
            Double scanned = TkNumberFormat.scan(text(), true);
            double v = scanned == null ? from : scanned > to ? to : scanned < from ? from : scanned;
            value(TkNumberFormat.classic(format, from, to, increment, v));
        }
    }
}
