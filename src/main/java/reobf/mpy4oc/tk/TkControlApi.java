package reobf.mpy4oc.tk;

final class TkControlApi {
    private TkControlApi() {}
    static void count(Object[] args, int n) { if (args.length != n) throw new IllegalArgumentException("wrong argument count"); }
    static double number(Object value) {
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) throw new IllegalArgumentException("finite number required");
        return ((Number) value).doubleValue();
    }
    static Object call(TkWidget w, String action, Object[] a) {
        if (w instanceof Listbox) return list((Listbox) w, action, a);
        if (w instanceof Checkbutton) {
            Checkbutton c = (Checkbutton) w; count(a, 0);
            switch (action) {
                case "select": c.selected(true); break;
                case "deselect": c.selected(false); break;
                case "toggle": if (c.kind == TkWidget.Kind.RADIOBUTTON) throw new IllegalArgumentException("radio cannot toggle"); c.selected(!c.selected); break;
                case "invoke": c.activate(); break;
                default: throw new IllegalArgumentException("unsupported checkbutton action");
            }
            return c.selected;
        }
        if (w instanceof Scale) {
            Scale s = (Scale) w;
            if (action.equals("get")) { count(a, 0); return s.value; }
            if (action.equals("set")) { count(a, 1); if (s.enabled) s.set(number(a[0]), true); return s.value; }
        }
        if (w instanceof Scrollbar) {
            Scrollbar b = (Scrollbar) w;
            if (action.equals("get")) { count(a, 0); return new Object[] {b.first, b.last}; }
            if (action.equals("set")) { count(a, 2); b.set(number(a[0]), number(a[1])); return null; }
        }
        throw new IllegalArgumentException("unsupported control action: " + action);
    }
    private static Object list(Listbox b, String action, Object[] a) {
        switch (action) {
            case "size": count(a, 0); return b.size();
            case "curselection": count(a, 0); return b.curselection();
            case "index": count(a, 1); return b.index(a[0], true);
            case "get":
                if (a.length == 1) return b.get(b.index(a[0], false));
                count(a, 2); return b.get(b.index(a[0], false), b.index(a[1], false));
            case "insert":
                if (a.length < 1) throw new IllegalArgumentException("index required");
                String[] rows = new String[a.length - 1]; for (int i = 0; i < rows.length; i++) rows[i] = TkOptions.text(a[i + 1]);
                b.insert(b.index(a[0], true), rows); break;
            case "delete": count(a, 2); b.delete(b.index(a[0], false), b.index(a[1], false)); break;
            case "selection_set": case "selection_clear": count(a, 2); b.selection(b.index(a[0], false), b.index(a[1], false), action.equals("selection_set")); break;
            case "selection_includes": count(a, 1); int at = b.index(a[0], false); return at >= 0 && b.selected.get(at);
            case "selection_anchor": count(a, 1); b.anchor = b.clamp(b.index(a[0], false)); b.tree.changed(false); break;
            case "activate": count(a, 1); b.active = b.clamp(b.index(a[0], false)); b.tree.changed(false); break;
            case "nearest": count(a, 1); return b.nearest(TkOptions.integer(a[0], -32767, 32767));
            case "see": count(a, 1); b.see(b.index(a[0], false)); break;
            case "bbox":
                count(a, 1); int row = b.index(a[0], false);
                if (row < b.top || row >= b.size() || row >= b.top + b.page()) return null;
                return new Object[] {b.padx + b.border - b.scrollX, b.pady + b.border + (row - b.top) * 16, TkFontMetrics.width(b.get(row)), 16};
            case "xview": case "yview":
                boolean x = action.equals("xview");
                if (a.length == 0) return b.view(x);
                if (a.length == 1) {
                    int n = b.index(a[0], true);
                    if (x) b.scrollX = (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) n * 8)); else b.top = Math.max(0, n);
                    b.tree.changed(false); break;
                }
                String op = TkOptions.text(a[0]);
                if (op.equals("moveto")) { count(a, 2); b.moveto(x, number(a[1])); }
                else if (op.equals("scroll")) {
                    count(a, 3); String units = TkOptions.text(a[2]);
                    if (!units.equals("units") && !units.equals("pages")) throw new IllegalArgumentException("units or pages required");
                    b.scroll(x, TkOptions.integer(a[1], Integer.MIN_VALUE, Integer.MAX_VALUE), units.equals("pages"));
                } else throw new IllegalArgumentException("moveto or scroll required");
                break;
            default: throw new IllegalArgumentException("unsupported Listbox operation: " + action);
        }
        return null;
    }
}
