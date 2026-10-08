package reobf.mpy4oc.tk;

import java.util.Arrays;
import java.util.Map;

final class TkControlOptions {
    private TkControlOptions() {}
    static boolean supports(TkWidget w, String key) {
        if (w instanceof Text && Arrays.asList("wrap", "xscrollcommand", "yscrollcommand").contains(key)) return true;
        if (w instanceof Label && !(w instanceof Entry) && key.equals("wraplength")) return true;
        if (w instanceof Checkbutton && Arrays.asList("selected", "group").contains(key)) return true;
        if (w instanceof Listbox && Arrays.asList("selectmode", "xscrollcommand", "yscrollcommand").contains(key)) return true;
        if (w instanceof Scale && Arrays.asList("orient", "length", "from", "to", "resolution", "value", "showvalue").contains(key)) return true;
        return w instanceof Scrollbar && key.equals("orient");
    }
    static double number(Map<String, Object> m, String key, double fallback) {
        if (!m.containsKey(key)) return fallback;
        Object value = m.get(key); if (!(value instanceof Number)) throw new IllegalArgumentException("number expected");
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || Math.abs(n) > 1e9) throw new IllegalArgumentException("finite number in -1e9..1e9 expected"); return n;
    }
    private static boolean orient(Map<String, Object> m, boolean old) {
        String value = TkOptions.string(m, "orient", old ? "horizontal" : "vertical");
        if (!value.equals("horizontal") && !value.equals("vertical")) throw new IllegalArgumentException("invalid orientation");
        return value.equals("horizontal");
    }
    static Runnable prepare(TkWidget w, Map<String, Object> m) {
        if (w instanceof Text) {
            Text text = (Text) w;
            String wrap = TkOptions.string(m, "wrap", text.wrap);
            if (!Arrays.asList("none", "char", "word").contains(wrap)) throw new IllegalArgumentException("invalid Text wrap");
            int columns = TkOptions.number(m, "width", text.columns, 1024), lines = TkOptions.number(m, "height", text.lines, 512);
            if (columns == 0 || lines == 0) throw new IllegalArgumentException("Text width and height must be positive");
            int xc = TkOptions.number(m, "xscrollcommand", text.xscrollcommand, Integer.MAX_VALUE), yc = TkOptions.number(m, "yscrollcommand", text.yscrollcommand, Integer.MAX_VALUE);
            return () -> { text.columns=columns; text.lines=lines; text.wrap=wrap; text.xscrollcommand=xc; text.yscrollcommand=yc; text.tree.changed(true); };
        }
        int wrap = w instanceof Label ? TkOptions.number(m, "wraplength", ((Label) w).wraplength, 8192) : 0;
        if (w instanceof Message) {
            int pixels = TkOptions.number(m, "width", wrap, 8192);
            return () -> { ((Message) w).textSize(0, 0); ((Message) w).wraplength(pixels); };
        }
        if (w instanceof Checkbutton) {
            Checkbutton c = (Checkbutton) w;
            int group = TkOptions.number(m, "group", c.group, Integer.MAX_VALUE);
            boolean selected = m.containsKey("selected") ? TkOptions.bool(m.get("selected")) : c.selected;
            return () -> { c.wraplength(wrap); c.group = group; c.selected(selected); };
        }
        if (w instanceof Label) return () -> ((Label) w).wraplength(wrap);
        if (w instanceof Listbox) {
            Listbox list = (Listbox) w;
            String mode = TkOptions.string(m, "selectmode", list.selectmode);
            if (!Arrays.asList("single", "browse", "multiple", "extended").contains(mode)) throw new IllegalArgumentException("invalid selectmode");
            int columns = TkOptions.number(m, "width", list.columns, 1024), lines = TkOptions.number(m, "height", list.lines, 512);
            int xc = TkOptions.number(m, "xscrollcommand", list.xscrollcommand, Integer.MAX_VALUE), yc = TkOptions.number(m, "yscrollcommand", list.yscrollcommand, Integer.MAX_VALUE);
            return () -> { list.columns = columns; list.lines = lines; list.selectmode = mode; list.xscrollcommand = xc; list.yscrollcommand = yc; list.tree.changed(true); };
        }
        if (w instanceof Scrollbar) {
            Scrollbar bar = (Scrollbar) w; boolean horizontal = orient(m, bar.horizontal);
            int command = TkOptions.number(m, "command", bar.command, Integer.MAX_VALUE);
            return () -> { bar.horizontal = horizontal; bar.command = command; };
        }
        if (w instanceof Scale) {
            Scale s = (Scale) w; boolean horizontal = orient(m, s.horizontal);
            boolean show = m.containsKey("showvalue") ? TkOptions.bool(m.get("showvalue")) : s.showvalue;
            int length = TkOptions.number(m, "length", s.length, 8192), command = TkOptions.number(m, "command", s.command, Integer.MAX_VALUE);
            double from = number(m, "from", s.from), to = number(m, "to", s.to), resolution = number(m, "resolution", s.resolution), value = number(m, "value", s.value);
            return () -> { s.horizontal = horizontal; s.showvalue = show; s.length = length; s.command = command; s.from = from; s.to = to; s.resolution = resolution; s.set(value, false); };
        }
        return () -> {};
    }
    static Object cget(TkWidget w, String option) {
        if (w instanceof Text && option.equals("wrap")) return ((Text) w).wrap;
        if (w instanceof Label && option.equals("wraplength")) return ((Label) w).wraplength;
        if (w instanceof Checkbutton) {
            Checkbutton c = (Checkbutton) w; if (option.equals("selected")) return c.selected; if (option.equals("group")) return c.group;
        }
        if (w instanceof Listbox) {
            Listbox list = (Listbox) w;
            if (option.equals("selectmode")) return list.selectmode;
            if (option.equals("xscrollcommand")) return list.xscrollcommand;
            if (option.equals("yscrollcommand")) return list.yscrollcommand;
        }
        if (w instanceof Scrollbar && option.equals("orient")) return ((Scrollbar) w).horizontal ? "horizontal" : "vertical";
        if (w instanceof Scale) {
            Scale s = (Scale) w;
            switch (option) {
                case "orient": return s.horizontal ? "horizontal" : "vertical";
                case "length": return s.length;
                case "from": return s.from;
                case "to": return s.to;
                case "resolution": return s.resolution;
                case "value": return s.value;
                case "showvalue": return s.showvalue;
            }
        }
        throw new IllegalArgumentException("unsupported widget option: " + option);
    }
}
