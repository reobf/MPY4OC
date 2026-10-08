package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Validates a complete update before changing any widget state. */
final class TkOptions {
    private TkOptions() {}
    static String text(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof byte[]) return new String((byte[]) value, StandardCharsets.UTF_8);
        throw new IllegalArgumentException("string expected");
    }
    static int integer(Object value, int min, int max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("integer expected");
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw new IllegalArgumentException("integer out of range");
        return (int) n;
    }
    static boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return integer(value, 0, 1) != 0;
    }
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("options table expected");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) result.put(text(e.getKey()), e.getValue());
        return result;
    }
    static int number(Map<String, Object> m, String key, int fallback, int max) {
        return m.containsKey(key) ? integer(m.get(key), 0, max) : fallback;
    }
    static Object[] sequence(Object value, int limit) {
        if (value instanceof Object[]) { Object[] a = (Object[]) value; if (a.length <= limit) return a; }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.size() > limit) throw new IllegalArgumentException("too many arguments");
            Object[] a = new Object[map.size()];
            for (Map.Entry<?, ?> e : map.entrySet()) { int key = integer(e.getKey(), 1, a.length); a[key - 1] = e.getValue(); }
            for (Object item : a) if (item == null) throw new IllegalArgumentException("dense sequence expected");
            return a;
        }
        throw new IllegalArgumentException("bounded sequence expected");
    }
    static String string(Map<String, Object> m, String key, String fallback) {
        return m.containsKey(key) ? text(m.get(key)) : fallback;
    }
    static TkPack.Anchor anchor(String value) {
        try { return TkPack.Anchor.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("invalid anchor: " + value); }
    }

    static void configure(TkWidget w, Map<String, Object> m) {
        for (String key : m.keySet()) {
            if (!java.util.Arrays.asList("text", "background", "foreground", "width", "height", "padx", "pady",
                    "borderwidth", "anchor", "state", "command", "propagate").contains(key) && !TkControlOptions.supports(w, key))
                throw new IllegalArgumentException("unsupported widget option: " + key);
            if (key.equals("text") && !(w instanceof Label) || key.equals("command") && !(w instanceof Button || w instanceof Scale || w instanceof Scrollbar)
                    || key.equals("propagate") && !(w instanceof Frame)) throw new IllegalArgumentException("option does not apply: " + key);
        }
        String text = string(m, "text", w.text());
        TkFontMetrics.checkText(text); w.tree.checkTextBudget(w, text);
        if (w instanceof Entry && text.indexOf('\n') >= 0) throw new IllegalArgumentException("Entry accepts one line");
        int bg = number(m, "background", w.background, 0xFFFFFF), fg = number(m, "foreground", w.foreground, 0xFFFFFF);
        int padx = number(m, "padx", w.padx, 1024), pady = number(m, "pady", w.pady, 1024), border = number(m, "borderwidth", w.border, 16);
        int width = number(m, "width", w instanceof Label ? ((Label) w).columns : w.width, w instanceof Label && !(w instanceof Message) ? 1024 : 8192);
        int height = number(m, "height", w instanceof Label ? ((Label) w).lines : w.height, w instanceof Label ? 512 : 8192);
        TkPack.Anchor anchor = anchor(string(m, "anchor", w.textAnchor.name()));
        String state = string(m, "state", w instanceof Entry && ((Entry) w).readonly ? "readonly" : w.enabled ? "normal" : "disabled");
        if (!state.equals("normal") && !state.equals("disabled") && !(w instanceof Entry && state.equals("readonly")))
            throw new IllegalArgumentException("invalid state: " + state);
        int command = number(m, "command", w instanceof Button ? ((Button) w).command() : 0, Integer.MAX_VALUE);
        boolean propagate = m.containsKey("propagate") ? bool(m.get("propagate")) : w instanceof Frame && ((Frame) w).propagate;
        Runnable controls = TkControlOptions.prepare(w, m);
        w.colors(bg, fg); w.padding(padx, pady, border); w.textAnchor(anchor); w.enabled(!state.equals("disabled"));
        if (w instanceof Label) {
            ((Label) w).text(text);
            if (!(w instanceof Message) && (m.containsKey("width") || m.containsKey("height"))) ((Label) w).textSize(width, height);
        } else w.size(width, height);
        if (w instanceof Entry) ((Entry) w).readonly = state.equals("readonly");
        if (w instanceof Button) ((Button) w).command(command);
        if (w instanceof Frame) ((Frame) w).propagate(propagate);
        controls.run();
    }

    static TkPack pack(Map<String, Object> m, TkPack old) {
        TkPack p = old == null ? TkPack.DEFAULT : old;
        for (String key : m.keySet()) if (!java.util.Arrays.asList("side", "fill", "expand", "anchor", "left", "right", "top", "bottom", "ipadx", "ipady").contains(key))
            throw new IllegalArgumentException("unsupported pack option: " + key);
        return new TkPack(TkPack.Side.valueOf(string(m, "side", p.side.name()).toUpperCase(Locale.ROOT)),
                TkPack.Fill.valueOf(string(m, "fill", p.fill.name()).toUpperCase(Locale.ROOT)),
                m.containsKey("expand") ? bool(m.get("expand")) : p.expand, anchor(string(m, "anchor", p.anchor.name())),
                number(m, "left", p.left, 1024), number(m, "right", p.right, 1024), number(m, "top", p.top, 1024),
                number(m, "bottom", p.bottom, 1024), number(m, "ipadx", p.ipadx, 1024), number(m, "ipady", p.ipady, 1024));
    }
}
