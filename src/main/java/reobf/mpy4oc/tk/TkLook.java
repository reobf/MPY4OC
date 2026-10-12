package reobf.mpy4oc.tk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A ttk widget's look: what its style gives each drawn option (background, foreground, relief,
 * fieldbackground, troughcolor, arrowcolor, indicatorcolor, the tabs', headings' and label's),
 * as rules the program resolved from the style chain and the theme in Tk's lookup order (the
 * maps first, then the plain setting). A rule applies while the widget's state has all its
 * states and none of its negated ones; the first that applies gives the value.
 */
final class TkLook {
    /** Tk's state bits, in its order (ttk::state names): active, disabled, focus, pressed, selected, background, alternate, invalid, readonly, hover. */
    static final int ACTIVE = 1, DISABLED = 2, FOCUS = 4, PRESSED = 8, SELECTED = 16, BACKGROUND = 32, ALTERNATE = 64, INVALID = 128, READONLY = 256, HOVER = 512;
    static final int MAX_KEYS = 32, MAX_RULES = 32;

    /** Per key: the rules' required bits, forbidden bits, and values (Integer colours, or relief names). */
    private final Map<String, int[][]> bits = new LinkedHashMap<>();
    private final Map<String, Object[]> values = new LinkedHashMap<>();

    boolean isEmpty() { return bits.isEmpty(); }
    boolean has(String key) { return bits.containsKey(key); }

    /** From the program: {key: [[on, off, value], ...]}; colours as 0xRRGGBB, reliefs by name. */
    static TkLook parse(Object raw) {
        TkLook look = new TkLook();
        Map<String, Object> m = TkOptions.map(raw);
        if (m.size() > MAX_KEYS) throw new IllegalArgumentException("too many look keys");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String key = e.getKey();
            if (key.length() > 32) throw new IllegalArgumentException("invalid look key");
            Object[] rules = TkOptions.sequence(e.getValue(), MAX_RULES);
            int[][] masks = new int[rules.length][];
            Object[] vals = new Object[rules.length];
            for (int i = 0; i < rules.length; i++) {
                Object[] rule = TkOptions.sequence(rules[i], 3);
                if (rule.length != 3) throw new IllegalArgumentException("a look rule is [on, off, value]");
                masks[i] = new int[] {TkOptions.integer(rule[0], 0, 0xFFFF), TkOptions.integer(rule[1], 0, 0xFFFF)};
                Object value = rule[2];
                if (value instanceof Number) {
                    int color = TkOptions.integer(value, 0, 0xFFFFFF);
                    vals[i] = color;
                } else {
                    vals[i] = TkOptions.relief(TkOptions.text(value));
                }
            }
            look.bits.put(key, masks);
            look.values.put(key, vals);
        }
        return look;
    }

    /** The value for key in state {@code state}, or null when no rule applies. */
    Object value(String key, int state) {
        int[][] masks = bits.get(key);
        if (masks == null) return null;
        Object[] vals = values.get(key);
        for (int i = 0; i < masks.length; i++)
            if ((state & masks[i][0]) == masks[i][0] && (state & masks[i][1]) == 0) return vals[i];
        return null;
    }

    int color(String key, int state, int fallback) {
        Object v = value(key, state);
        return v instanceof Integer ? (Integer) v : fallback;
    }

    TkWidget.Relief relief(String key, int state, TkWidget.Relief fallback) {
        Object v = value(key, state);
        return v instanceof TkWidget.Relief ? (TkWidget.Relief) v : fallback;
    }

    /** Back to the program's form, for saving. */
    Map<String, Object> save() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[][]> e : bits.entrySet()) {
            Object[] vals = values.get(e.getKey()), rules = new Object[vals.length];
            for (int i = 0; i < vals.length; i++)
                rules[i] = new Object[] {e.getValue()[i][0], e.getValue()[i][1], vals[i] instanceof TkWidget.Relief ? ((TkWidget.Relief) vals[i]).option() : vals[i]};
            out.put(e.getKey(), rules);
        }
        return out;
    }
}
