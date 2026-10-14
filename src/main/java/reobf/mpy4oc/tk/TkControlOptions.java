package reobf.mpy4oc.tk;

import java.util.Arrays;
import java.util.Map;

final class TkControlOptions {
    private TkControlOptions() {}
    static boolean supports(TkWidget w, String key) {
        if (w instanceof Entry && Arrays.asList("show", "justify", "validate", "notify").contains(key)) return true;
        if (w instanceof Spinbox && Arrays.asList("from", "to", "increment", "values", "wrap", "format", "delegate").contains(key)) return true;
        if (w instanceof Combobox && Arrays.asList("values", "postcommand").contains(key)) return true;
        if (w instanceof Treeview && Arrays.asList("columns", "displaycolumns", "selectmode", "show", "showindicator", "xscrollcommand", "yscrollcommand", "notify").contains(key)) return true;
        if (w instanceof Text && Arrays.asList("wrap", "xscrollcommand", "yscrollcommand", "undo", "autoseparators", "maxundo", "notify",
                "inactiveselectbackground", "insertbackground").contains(key)) return true;
        if (w instanceof Label && !(w instanceof Entry) && key.equals("wraplength")) return true;
        if ((w.kind == TkWidget.Kind.LABEL || w.kind == TkWidget.Kind.BUTTON) && key.equals("image")) return true;
        if (w instanceof Checkbutton && Arrays.asList("selected", "group").contains(key)) return true;
        if (w instanceof Listbox && Arrays.asList("selectmode", "xscrollcommand", "yscrollcommand").contains(key)) return true;
        if (w instanceof Scale && Arrays.asList("orient", "length", "from", "to", "resolution", "value", "showvalue").contains(key)) return true;
        if (w instanceof Progressbar && Arrays.asList("orient", "length", "mode", "maximum", "value").contains(key)) return true;
        if (w instanceof Canvas && Arrays.asList("scrollregion", "xscrollcommand", "yscrollcommand", "xscrollincrement", "yscrollincrement", "confine").contains(key)) return true;
        if (w instanceof Separator && key.equals("orient")) return true;
        if (w instanceof Menu && Arrays.asList("activebackground", "activeforeground", "disabledforeground", "selectcolor", "postcommand", "select", "notify").contains(key)) return true;
        if (w instanceof Menubutton && Arrays.asList("menu", "direction", "indicatoron").contains(key)) return true;
        if (TkWindow.is(w) && key.equals("menu")) return true;
        if (w instanceof Panedwindow && Arrays.asList("orient", "sashthickness", "sashwidth", "sashpad", "showhandle", "handlesize", "handlepad", "sashrelief").contains(key)) return true;
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
            boolean undo = m.containsKey("undo") ? TkOptions.bool(m.get("undo")) : text.undo;
            boolean auto = m.containsKey("autoseparators") ? TkOptions.bool(m.get("autoseparators")) : text.autoseparators;
            int maxundo = TkOptions.number(m, "maxundo", text.maxundo, Integer.MAX_VALUE), notify = TkOptions.number(m, "notify", text.notify, Integer.MAX_VALUE);
            int isbg = TkOptions.number(m, "inactiveselectbackground", text.inactiveSelectBackground, 0xFFFFFF), ibg = TkOptions.number(m, "insertbackground", text.insertBackground, 0xFFFFFF);
            return () -> {
                text.columns=columns; text.lines=lines; text.wrap=wrap; text.xscrollcommand=xc; text.yscrollcommand=yc;
                text.options(undo, auto, maxundo, notify, isbg, ibg); text.tree.changed(true);
            };
        }
        if (w instanceof Entry) return entry((Entry) w, m);
        if (TkWindow.is(w) && m.containsKey("menu")) {
            Frame window = (Frame) w;
            int id = menuId(w, m);
            return () -> TkWindow.setMenu(window, id);
        }
        if (w instanceof Panedwindow) {
            Panedwindow p = (Panedwindow) w;
            boolean horizontal = orient(m, p.horizontal);
            int thickness = TkOptions.number(m, "sashthickness", p.sashThickness, 64), sashWidth = TkOptions.number(m, "sashwidth", p.sashWidth, 64);
            int sashPad = TkOptions.number(m, "sashpad", p.sashPad, 64), handleSize = TkOptions.number(m, "handlesize", p.handleSize, 64);
            int handlePad = TkOptions.number(m, "handlepad", p.handlePad, 1024);
            boolean showHandle = m.containsKey("showhandle") ? TkOptions.bool(m.get("showhandle")) : p.showHandle;
            TkWidget.Relief sashRelief = m.containsKey("sashrelief") ? TkOptions.relief(TkOptions.text(m.get("sashrelief"))) : p.sashRelief;
            return () -> {
                p.horizontal = horizontal; p.sashThickness = thickness; p.sashWidth = sashWidth; p.sashPad = sashPad; p.handleSize = handleSize;
                p.handlePad = handlePad; p.showHandle = showHandle; p.sashRelief = sashRelief; p.tree.changed(true);
            };
        }
        if (w instanceof Menu) {
            Menu menu = (Menu) w;
            int abg = TkOptions.number(m, "activebackground", menu.activeBackground, 0xFFFFFF), afg = TkOptions.number(m, "activeforeground", menu.activeForeground, 0xFFFFFF);
            int dfg = TkOptions.number(m, "disabledforeground", menu.disabledForeground, 0xFFFFFF), sel = TkOptions.number(m, "selectcolor", menu.selectColor, 0xFFFFFF);
            boolean post = m.containsKey("postcommand") ? TkOptions.bool(m.get("postcommand")) : menu.postcommand;
            boolean select = m.containsKey("select") ? TkOptions.bool(m.get("select")) : menu.selectNotify;
            int notify = TkOptions.number(m, "notify", menu.notify, Integer.MAX_VALUE);
            return () -> {
                menu.activeBackground = abg; menu.activeForeground = afg; menu.disabledForeground = dfg; menu.selectColor = sel;
                menu.postcommand = post; menu.selectNotify = select; menu.notify = notify; menu.tree.changed(false);
            };
        }
        if (w instanceof Menubutton) {
            Menubutton mb = (Menubutton) w;
            int id = m.containsKey("menu") ? menuId(w, m) : mb.menu;
            String direction = TkOptions.string(m, "direction", mb.direction);
            if (!Arrays.asList(Menubutton.DIRECTIONS).contains(direction))
                throw new IllegalArgumentException("bad direction \"" + direction + "\": must be above, below, flush, left, or right");
            boolean indicator = m.containsKey("indicatoron") ? TkOptions.bool(m.get("indicatoron")) : mb.indicator;
            int wrap = TkOptions.number(m, "wraplength", mb.wraplength, 8192);
            return () -> { mb.menu = id; mb.direction = direction; mb.wraplength(wrap); if (indicator != mb.indicator) { mb.indicator = indicator; mb.tree.changed(true); } };
        }
        if (w instanceof Treeview) return treeview((Treeview) w, m);
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
        if (w instanceof Label) {
            Label label = (Label) w;
            int image = TkOptions.number(m, "image", label.image, Integer.MAX_VALUE);
            if (m.containsKey("image") && image != 0 && w.tree.ownedImage(image) == null) throw new IllegalArgumentException("image does not exist");
            return () -> { label.wraplength(wrap); if (image != label.image) label.image(image); };
        }
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
        if (w instanceof Separator) {
            Separator s = (Separator) w; boolean horizontal = orient(m, s.horizontal);
            return () -> { if (horizontal != s.horizontal) { s.horizontal = horizontal; s.tree.changed(true); } };
        }
        if (w instanceof Progressbar) {
            Progressbar p = (Progressbar) w; boolean horizontal = orient(m, p.horizontal);
            String mode = TkOptions.string(m, "mode", p.indeterminate ? "indeterminate" : "determinate");
            if (!mode.equals("determinate") && !mode.equals("indeterminate")) throw new IllegalArgumentException("mode must be determinate or indeterminate");
            int length = TkOptions.number(m, "length", p.length, 8192);
            double maximum = number(m, "maximum", p.maximum), value = number(m, "value", p.value);
            if (maximum <= 0) throw new IllegalArgumentException("maximum must be positive");
            return () -> {
                boolean relayout = horizontal != p.horizontal || length != p.length;
                p.horizontal = horizontal; p.indeterminate = mode.equals("indeterminate"); p.length = length; p.maximum = maximum; p.value(value);
                p.tree.changed(relayout);
            };
        }
        if (w instanceof Canvas) {
            Canvas c = (Canvas) w;
            int[] region = c.scrollregion;
            if (m.containsKey("scrollregion")) region = scrollregion(m.get("scrollregion"));
            int xi = TkOptions.number(m, "xscrollincrement", c.xscrollincrement, 8192), yi = TkOptions.number(m, "yscrollincrement", c.yscrollincrement, 8192);
            int xc = TkOptions.number(m, "xscrollcommand", c.xscrollcommand, Integer.MAX_VALUE), yc = TkOptions.number(m, "yscrollcommand", c.yscrollcommand, Integer.MAX_VALUE);
            boolean confine = m.containsKey("confine") ? TkOptions.bool(m.get("confine")) : c.confine;
            int[] chosen = region;
            return () -> {
                c.scrollregion = chosen; c.xscrollincrement = xi; c.yscrollincrement = yi; c.xscrollcommand = xc; c.yscrollcommand = yc; c.confine = confine;
                c.origin(c.viewX, c.viewY);
            };
        }
        return () -> {};
    }
    /** A -menu option: 0, or a Menu of the program's. */
    private static int menuId(TkWidget w, Map<String, Object> m) {
        int id = TkOptions.integer(m.get("menu"), 0, Integer.MAX_VALUE);
        if (id != 0 && !(w.tree.owned(id) instanceof Menu)) throw new IllegalArgumentException("menu must be a Menu");
        return id;
    }

    static final java.util.List<String> VALIDATE = Arrays.asList("all", "key", "focus", "focusin", "focusout", "none");
    /** Values a Spinbox or Combobox holds: this many at most, of single-line text, this many bytes in all. */
    static final int MAX_VALUES = 4096, MAX_VALUE_BYTES = 65536;

    /** An Entry's options (-show, -justify, -validate and the program's callback), a Spinbox's or a Combobox's too. */
    private static Runnable entry(Entry e, Map<String, Object> m) {
        String show = TkOptions.string(m, "show", e.show);
        if (!show.isEmpty()) {
            TkFontMetrics.checkText(show);
            int mask = show.codePointAt(0);
            if (mask < 32 || mask == 127) throw new IllegalArgumentException("-show must start with a printable character");
        }
        int justify = m.containsKey("justify") ? Entry.justify(TkOptions.text(m.get("justify"))) : e.justify;
        String validate = TkOptions.string(m, "validate", e.validate);
        if (!VALIDATE.contains(validate))
            throw new IllegalArgumentException("bad validate \"" + validate + "\": must be all, key, focus, focusin, focusout, or none");
        int notify = TkOptions.number(m, "notify", e.notify, Integer.MAX_VALUE);
        Runnable more = e instanceof Spinbox ? spinbox((Spinbox) e, m) : e instanceof Combobox ? combobox((Combobox) e, m) : () -> {};
        return () -> {
            boolean relayout = !show.equals(e.show);
            e.show = show; e.justify = justify; e.validate = validate; e.notify = notify;
            more.run();
            e.tree.changed(relayout);
        };
    }

    private static Runnable spinbox(Spinbox s, Map<String, Object> m) {
        double from = number(m, "from", s.from), to = number(m, "to", s.to), increment = number(m, "increment", s.increment);
        if (!s.ttk && from > to) throw new IllegalArgumentException("-to value must be greater than -from value");
        String format = TkOptions.string(m, "format", s.format);
        if (format.length() > 256) throw new IllegalArgumentException("format too long");
        if (!s.ttk && !format.isEmpty()) TkNumberFormat.classicFormat(format);
        java.util.List<String> values = m.containsKey("values") ? values(m.get("values")) : null;
        boolean wrap = m.containsKey("wrap") ? TkOptions.bool(m.get("wrap")) : s.wrap;
        boolean delegate = m.containsKey("delegate") ? TkOptions.bool(m.get("delegate")) : s.delegate;
        boolean newFormat = m.containsKey("format") && !format.isEmpty();
        return () -> { s.configure(from, to, increment, format, values, wrap, newFormat); s.delegate = delegate; };
    }

    private static Runnable combobox(Combobox c, Map<String, Object> m) {
        java.util.List<String> values = m.containsKey("values") ? values(m.get("values")) : null;
        int height = TkOptions.number(m, "height", c.height, 4096);
        boolean postcommand = m.containsKey("postcommand") ? TkOptions.bool(m.get("postcommand")) : c.postcommand;
        return () -> {
            if (values != null) { c.values.clear(); c.values.addAll(values); }
            c.height = height; c.postcommand = postcommand;
        };
    }

    /** A Treeview's options: -columns, -displaycolumns, -height (rows), -selectmode, -show, scroll commands, the program's callback. */
    private static Runnable treeview(Treeview t, Map<String, Object> m) {
        java.util.List<String> columns = m.containsKey("columns") ? values(m.get("columns")) : null;
        java.util.List<String> display = m.containsKey("displaycolumns") ? values(m.get("displaycolumns")) : null;
        int lines = TkOptions.number(m, "height", t.lines, 4096);
        String mode = TkOptions.string(m, "selectmode", t.selectmode);
        if (!Arrays.asList("none", "browse", "extended").contains(mode))
            throw new IllegalArgumentException("bad selectmode \"" + mode + "\": must be none, browse, or extended");
        boolean tree = t.showTree, headings = t.showHeadings;
        boolean showIndicator = m.containsKey("showindicator") ? TkOptions.bool(m.get("showindicator")) : t.showIndicator;
        if (m.containsKey("show")) {
            tree = headings = false;
            for (String part : values(m.get("show"))) {
                if (part.equals("tree")) tree = true;
                else if (part.equals("headings")) headings = true;
                else throw new IllegalArgumentException("bad value \"" + part + "\": must be tree or headings");
            }
        }
        int xc = TkOptions.number(m, "xscrollcommand", t.xscrollcommand, Integer.MAX_VALUE), yc = TkOptions.number(m, "yscrollcommand", t.yscrollcommand, Integer.MAX_VALUE);
        int notify = TkOptions.number(m, "notify", t.notify, Integer.MAX_VALUE);
        boolean showTree = tree, showHeadings = headings;
        // Check -displaycolumns against the new -columns before changing anything.
        if (display != null && !(display.size() == 1 && display.get(0).equals("#all"))) {
            java.util.List<String> ids = columns != null ? columns : new java.util.ArrayList<>();
            if (columns == null) for (Treeview.Column c : t.columns) ids.add(c.id);
            for (String spec : display) if (!ids.contains(spec) && !spec.matches("[0-9]+")) throw new IllegalArgumentException("Invalid column index " + spec);
        }
        return () -> {
            if (columns != null) t.columns(columns);
            if (display != null) t.displayColumns(display);
            boolean relayout = lines != t.lines || showTree != t.showTree || showHeadings != t.showHeadings;
            t.lines = lines; t.selectmode = mode; t.showTree = showTree; t.showHeadings = showHeadings;
            t.showIndicator = showIndicator;
            t.xscrollcommand = xc; t.yscrollcommand = yc; t.notify = notify;
            t.tree.changed(relayout);
        };
    }

    /** -values: single lines of text, MAX_VALUES at most and MAX_VALUE_BYTES in all. */
    static java.util.List<String> values(Object raw) {
        Object[] items = TkOptions.sequence(raw, MAX_VALUES);
        java.util.List<String> values = new java.util.ArrayList<>(items.length);
        long bytes = 0;
        for (Object item : items) {
            String value = TkOptions.text(item);
            TkFontMetrics.checkText(value);
            if (value.indexOf('\n') >= 0) throw new IllegalArgumentException("values must be single lines");
            bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            values.add(value);
        }
        if (bytes > MAX_VALUE_BYTES) throw new IllegalArgumentException("values longer than " + MAX_VALUE_BYTES + " bytes");
        return values;
    }

    /** A Canvas scrollregion: '' or an empty sequence for none, else x1, y1, x2, y2 within the coordinate range. */
    static int[] scrollregion(Object value) {
        if (value instanceof String && ((String) value).trim().isEmpty()) return null;
        Object[] raw = value instanceof String ? ((String) value).trim().split("\\s+") : TkOptions.sequence(value, 4);
        if (raw.length == 0) return null;
        if (raw.length != 4) throw new IllegalArgumentException("scrollregion must be (x1, y1, x2, y2)");
        int[] r = new int[4];
        for (int i = 0; i < 4; i++) {
            double n;
            if (raw[i] instanceof Number) n = ((Number) raw[i]).doubleValue();
            else try { n = Double.parseDouble(TkOptions.text(raw[i])); } catch (NumberFormatException bad) { throw new IllegalArgumentException("scrollregion must be numbers"); }
            if (!Double.isFinite(n) || Math.abs(n) > Canvas.MAX_VIEW) throw new IllegalArgumentException("scrollregion must be within -32768..32768");
            r[i] = (int) Math.round(n);
        }
        if (r[2] < r[0] || r[3] < r[1]) throw new IllegalArgumentException("scrollregion must have x1 <= x2 and y1 <= y2");
        return r;
    }
    static Object cget(TkWidget w, String option) {
        if (w instanceof Entry) {
            Entry e = (Entry) w;
            switch (option) {
                case "show": return e.show;
                case "justify": return Entry.JUSTIFY[e.justify];
                case "validate": return e.validate;
                case "notify": return e.notify;
            }
        }
        if (w instanceof Spinbox) {
            Spinbox s = (Spinbox) w;
            switch (option) {
                case "from": return s.from;
                case "to": return s.to;
                case "increment": return s.increment;
                case "values": return s.values.toArray();
                case "wrap": return s.wrap;
                case "format": return s.format;
            }
        }
        if (w instanceof Treeview) {
            Treeview t = (Treeview) w;
            switch (option) {
                case "columns": { Object[] ids = new Object[t.columns.size()]; for (int i = 0; i < ids.length; i++) ids[i] = t.columns.get(i).id; return ids; }
                case "displaycolumns": {
                    if (t.display == null) return new Object[] {"#all"};
                    Object[] ids = new Object[t.display.size()]; for (int i = 0; i < ids.length; i++) ids[i] = t.display.get(i).id; return ids;
                }
                case "selectmode": return t.selectmode;
                case "showindicator": return t.showIndicator;
                case "show": { java.util.List<Object> s = new java.util.ArrayList<>(); if (t.showTree) s.add("tree"); if (t.showHeadings) s.add("headings"); return s.toArray(); }
                case "xscrollcommand": return t.xscrollcommand;
                case "yscrollcommand": return t.yscrollcommand;
                case "notify": return t.notify;
            }
        }
        if (w instanceof Combobox) {
            Combobox c = (Combobox) w;
            if (option.equals("values")) return c.values.toArray();
            if (option.equals("postcommand")) return c.postcommand;
        }
        if (w instanceof Text) {
            Text t = (Text) w;
            switch (option) {
                case "wrap": return t.wrap;
                case "undo": return t.undo ? 1 : 0;
                case "autoseparators": return t.autoseparators ? 1 : 0;
                case "maxundo": return t.maxundo;
                case "inactiveselectbackground": return t.inactiveSelectBackground;
                case "insertbackground": return t.insertBackground;
            }
        }
        if (w instanceof Label && option.equals("wraplength")) return ((Label) w).wraplength;
        if (w instanceof Label && option.equals("image")) return ((Label) w).image;
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
        if (w instanceof Separator && option.equals("orient")) return ((Separator) w).horizontal ? "horizontal" : "vertical";
        if (w instanceof Progressbar) {
            Progressbar p = (Progressbar) w;
            switch (option) {
                case "orient": return p.horizontal ? "horizontal" : "vertical";
                case "length": return p.length;
                case "mode": return p.indeterminate ? "indeterminate" : "determinate";
                case "maximum": return p.maximum;
                case "value": return p.value;
            }
        }
        if (w instanceof Canvas) {
            Canvas c = (Canvas) w;
            switch (option) {
                case "scrollregion": return c.scrollregion == null ? new Object[0] : new Object[] {c.scrollregion[0], c.scrollregion[1], c.scrollregion[2], c.scrollregion[3]};
                case "xscrollincrement": return c.xscrollincrement;
                case "yscrollincrement": return c.yscrollincrement;
                case "xscrollcommand": return c.xscrollcommand;
                case "yscrollcommand": return c.yscrollcommand;
                case "confine": return c.confine;
            }
        }
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
