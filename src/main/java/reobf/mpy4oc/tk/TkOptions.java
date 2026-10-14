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
            if (!java.util.Arrays.asList("text", "background", "foreground", "width", "height", "padx", "pady", "padding", "relief",
                    "borderwidth", "anchor", "state", "command", "propagate", "takefocus", "labelanchor", "labelwidget", "look", "held", "minwidth").contains(key) && !TkControlOptions.supports(w, key)
                    && !(w instanceof TkIcon && ((TkIcon) w).option().equals(key)))
                throw new IllegalArgumentException("unsupported widget option: " + key);
            if (key.equals("text") && !(w instanceof Label || w instanceof Labelframe)
                    || (key.equals("labelanchor") || key.equals("labelwidget")) && !(w instanceof Labelframe) || key.equals("command") && !(w instanceof Button || w instanceof Scale || w instanceof Scrollbar || w instanceof Notebook)
                    || key.equals("propagate") && !(w instanceof Frame)) throw new IllegalArgumentException("option does not apply: " + key);
        }
        String text = string(m, "text", w.text());
        TkFontMetrics.checkText(text); w.tree.checkTextBudget(w, text);
        if (w instanceof Entry && text.indexOf('\n') >= 0) throw new IllegalArgumentException("Entry accepts one line");
        int bg = number(m, "background", w.background, 0xFFFFFF), fg = number(m, "foreground", w.foreground, 0xFFFFFF);
        int border = number(m, "borderwidth", w.border, 16);
        // -padx/-pady set both sides; ttk's -padding gives the four (left, top, right, bottom).
        int[] pad = {w.padLeft, w.padTop, w.padRight, w.padBottom};
        if (m.containsKey("padx")) pad[0] = pad[2] = number(m, "padx", 0, 1024);
        if (m.containsKey("pady")) pad[1] = pad[3] = number(m, "pady", 0, 1024);
        if (m.containsKey("padding")) {
            Object[] sides = sequence(m.get("padding"), 4);
            if (sides.length != 4) throw new IllegalArgumentException("padding takes left, top, right and bottom");
            for (int i = 0; i < 4; i++) pad[i] = integer(sides[i], 0, 1024);
        }
        TkWidget.Relief relief = m.containsKey("relief") ? relief(text(m.get("relief"))) : w.relief;
        int width = number(m, "width", w instanceof Label ? ((Label) w).columns : w.width, w instanceof Label && !(w instanceof Message) ? 1024 : 8192);
        // A Combobox's -height is the rows of its list (TkControlOptions), not lines of text.
        boolean listRows = w instanceof Combobox || w instanceof Treeview;
        int height = listRows ? (w instanceof Label ? ((Label) w).lines : w.height) : number(m, "height", w instanceof Label ? ((Label) w).lines : w.height, w instanceof Label ? 512 : 8192);
        TkPack.Anchor anchor = anchor(string(m, "anchor", w.textAnchor.name()));
        String state = string(m, "state", w instanceof Entry && ((Entry) w).readonly ? "readonly" : w.enabled ? "normal" : "disabled");
        if (!state.equals("normal") && !state.equals("disabled") && !(w instanceof Entry && state.equals("readonly")))
            throw new IllegalArgumentException("invalid state: " + state);
        int command = number(m, "command", w instanceof Button ? ((Button) w).command() : w instanceof Notebook ? ((Notebook) w).command : 0, Integer.MAX_VALUE);
        boolean propagate = m.containsKey("propagate") ? bool(m.get("propagate")) : w instanceof Frame && ((Frame) w).propagate;
        int takefocus = m.containsKey("takefocus") ? takefocus(m.get("takefocus")) : w.takefocus;
        // ttk: the style's look (TkLook rules), the states the program holds, a minimum width in characters (TButton's -width -9).
        TkLook look = m.containsKey("look") ? TkLook.parse(m.get("look")) : w.look;
        int held = m.containsKey("held") ? integer(m.get("held"), 0, 0xFFFF) : w.held;
        int minColumns = m.containsKey("minwidth") && w instanceof Label ? integer(m.get("minwidth"), 0, 1024) : w instanceof Label ? ((Label) w).minColumns : 0;
        if (m.containsKey("minwidth") && !(w instanceof Label)) throw new IllegalArgumentException("option does not apply: minwidth");
        Labelframe labelframe = w instanceof Labelframe ? (Labelframe) w : null;
        Labelframe.Anchor labelAnchor = labelframe == null ? null
                : m.containsKey("labelanchor") ? Labelframe.anchor(text(m.get("labelanchor")), labelframe.ttk) : labelframe.labelAnchor;
        TkWidget labelWidget = labelframe == null ? null : labelframe.labelWidget;
        if (labelframe != null && m.containsKey("labelwidget")) {
            int id = integer(m.get("labelwidget"), 0, Integer.MAX_VALUE);
            labelWidget = id == 0 ? null : w.tree.owned(id);
            if (id != 0 && (labelWidget == null || labelWidget.parent != w || TkWindow.is(labelWidget)))
                throw new IllegalArgumentException("a labelwidget must be a child of its labelframe");
        }
        TkIcon icon = w instanceof TkIcon ? (TkIcon) w : null;
        TkIconDescriptor descriptor = icon == null ? null : m.containsKey(icon.option())
                ? TkIconDescriptor.parse(w.kind == TkWidget.Kind.FLUIDICON, m.get(icon.option())) : icon.descriptor;
        if (icon != null) icon.checkDescriptor(descriptor);
        Runnable controls = TkControlOptions.prepare(w, m);
        w.colors(bg, fg); w.padding(pad[0], pad[1], pad[2], pad[3], border); w.relief(relief); w.textAnchor(anchor); w.enabled(!state.equals("disabled"));
        if (w instanceof Label) {
            ((Label) w).text(text);
            if (!(w instanceof Message) && (m.containsKey("width") || !listRows && m.containsKey("height"))) ((Label) w).textSize(width, height);
        } else w.size(width, height);
        if (w instanceof Entry) ((Entry) w).readonly = state.equals("readonly");
        if (w instanceof Button) ((Button) w).command(command);
        if (w instanceof Notebook) ((Notebook) w).command = command;
        if (w instanceof Frame) ((Frame) w).propagate(propagate);
        if (labelframe != null) { labelframe.text(text); labelframe.labelAnchor(labelAnchor); labelframe.labelWidget(labelWidget); }
        if (takefocus != w.takefocus) { w.takefocus = takefocus; w.tree.changed(false); }
        if (m.containsKey("look")) { w.look = look != null && look.isEmpty() ? null : look; w.tree.changed(false); }
        if (held != w.held) { w.held = held; w.tree.changed(false); }
        if (w instanceof Label && minColumns != ((Label) w).minColumns) { ((Label) w).minColumns = minColumns; w.tree.changed(true); }
        controls.run();
        if (icon != null) icon.descriptor(descriptor);
    }

    /** Tk's -relief values, with its error message. */
    static TkWidget.Relief relief(String value) {
        for (TkWidget.Relief relief : TkWidget.Relief.values()) if (relief.option().equals(value)) return relief;
        throw new IllegalArgumentException("bad relief \"" + value + "\": must be flat, groove, raised, ridge, solid, or sunken");
    }

    /** -takefocus: '' (the widget's default) is -1; 0/1, a boolean, or "0"/"1". Tk's script form is not supported. */
    static int takefocus(Object value) {
        if (value instanceof String || value instanceof byte[]) {
            String s = text(value).trim();
            if (s.isEmpty()) return -1;
            if (s.equals("0") || s.equals("1")) return s.charAt(0) - '0';
            throw new IllegalArgumentException("takefocus must be '', 0 or 1");
        }
        return bool(value) ? 1 : 0;
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
