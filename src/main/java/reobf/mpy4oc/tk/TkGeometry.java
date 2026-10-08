package reobf.mpy4oc.tk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Validated geometry commands shared by the component API and persistence. */
final class TkGeometry {
    private TkGeometry() {}
    static void keys(Map<String, Object> m, String... keys) {
        for (String key : m.keySet()) if (!java.util.Arrays.asList(keys).contains(key))
            throw new IllegalArgumentException("unsupported geometry option: " + key);
    }
    static TkGrid grid(TkWidget w, Map<String, Object> m) {
        keys(m, "row", "column", "rowspan", "columnspan", "sticky", "left", "right", "top", "bottom", "ipadx", "ipady");
        TkGrid g = w.grid == null ? w.savedGrid : w.grid;
        int nextRow = 0;
        if (w.parent != null) for (TkWidget child : w.parent.gridded) nextRow = Math.max(nextRow, child.grid.row + child.grid.rowspan);
        if (g == null) g = new TkGrid(m.containsKey("row") ? n(m, "row", 0, 127) : nextRow, 0);
        return new TkGrid(n(m, "row", g.row, 127), n(m, "column", g.column, 127), n(m, "rowspan", g.rowspan, 128), n(m, "columnspan", g.columnspan, 128),
                TkOptions.string(m, "sticky", g.sticky), n(m, "left", g.left, 1024), n(m, "right", g.right, 1024),
                n(m, "top", g.top, 1024), n(m, "bottom", g.bottom, 1024), n(m, "ipadx", g.ipadx, 1024), n(m, "ipady", g.ipady, 1024));
    }
    private static int n(Map<String, Object> m, String key, int fallback, int max) { return TkOptions.number(m, key, fallback, max); }
    static TkPlace place(Map<String, Object> m, TkPlace previous) {
        keys(m, "x", "y", "width", "height", "relx", "rely", "relwidth", "relheight", "anchor", "bordermode");
        TkPlace p = previous == null ? TkPlace.DEFAULT : previous;
        return new TkPlace(pixel(m, "x", p.x), pixel(m, "y", p.y), dimension(m, "width", p.width), dimension(m, "height", p.height),
                relative(m, "relx", p.relx), relative(m, "rely", p.rely), relative(m, "relwidth", p.relwidth), relative(m, "relheight", p.relheight),
                TkOptions.anchor(TkOptions.string(m, "anchor", p.anchor.name())), TkOptions.string(m, "bordermode", p.bordermode));
    }
    private static int pixel(Map<String, Object> m, String key, int fallback) { return m.containsKey(key) ? TkOptions.integer(m.get(key), -8192, 8192) : fallback; }
    private static Integer dimension(Map<String, Object> m, String key, Integer fallback) {
        return !m.containsKey(key) ? fallback : "".equals(m.get(key)) ? null : pixel(m, key, 0);
    }
    private static Double relative(Map<String, Object> m, String key, Double fallback) {
        if (!m.containsKey(key)) return fallback;
        if ("".equals(m.get(key)) && (key.equals("relwidth") || key.equals("relheight"))) return null;
        if (!(m.get(key) instanceof Number)) throw new IllegalArgumentException("relative dimension must be a number");
        return ((Number) m.get(key)).doubleValue();
    }
    static Map<String, Object> gridInfo(TkGrid g) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (g == null) return m;
        m.put("row", g.row); m.put("column", g.column); m.put("rowspan", g.rowspan); m.put("columnspan", g.columnspan); m.put("sticky", g.sticky);
        m.put("left", g.left); m.put("right", g.right); m.put("top", g.top); m.put("bottom", g.bottom); m.put("ipadx", g.ipadx); m.put("ipady", g.ipady); return m;
    }
    static Map<String, Object> placeInfo(TkPlace p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) return m;
        m.put("x", p.x); m.put("y", p.y); m.put("relx", p.relx); m.put("rely", p.rely);
        m.put("width", p.width == null ? "" : p.width); m.put("height", p.height == null ? "" : p.height);
        m.put("relwidth", p.relwidth == null ? "" : p.relwidth); m.put("relheight", p.relheight == null ? "" : p.relheight);
        m.put("anchor", p.anchor.name().toLowerCase(java.util.Locale.ROOT)); m.put("bordermode", p.bordermode); return m;
    }
    static Object call(TkWidget w, String action, Map<String, Object> m) {
        switch (action) {
            case "grid": w.grid(grid(w, m)); return null;
            case "place": w.place(place(m, w.place)); return null;
            case "grid_remove": keys(m); w.gridForget(true); return null;
            case "grid_forget": keys(m); w.gridForget(false); return null;
            case "pack_forget": case "place_forget":
                keys(m); if (w.manager().equals(action.substring(0, action.indexOf('_')))) w.forget(); return null;
            case "grid_info": keys(m); return gridInfo(w.grid);
            case "place_info": keys(m); return placeInfo(w.place);
            case "manager": keys(m); return w.manager();
            case "request": keys(m); w.tree.render(); return new Object[] {w.requestedWidth, w.requestedHeight};
        }
        if (!(w instanceof Frame)) throw new IllegalArgumentException("geometry container must be a Frame");
        Frame f = (Frame) w;
        switch (action) {
            case "grid_propagate": case "pack_propagate":
                keys(m, "flag"); boolean grid = action.equals("grid_propagate");
                if (m.containsKey("flag")) { if (grid) f.gridPropagate(TkOptions.bool(m.get("flag"))); else f.propagate(TkOptions.bool(m.get("flag"))); }
                return grid ? f.gridPropagate : f.propagate;
            case "grid_anchor":
                keys(m, "anchor"); if (m.containsKey("anchor")) f.gridAnchor(TkOptions.anchor(TkOptions.text(m.get("anchor"))));
                return f.gridAnchor.name().toLowerCase(java.util.Locale.ROOT);
            case "rowconfigure": case "columnconfigure":
                keys(m, "index", "weight", "minsize", "pad"); int index = TkOptions.integer(m.get("index"), 0, 127);
                Map<Integer, TkGrid.Slot> slots = action.equals("rowconfigure") ? f.rows : f.columns;
                TkGrid.Slot previous = slots.getOrDefault(index, TkGrid.Slot.DEFAULT);
                TkGrid.Slot slot = new TkGrid.Slot(n(m, "weight", previous.weight, 1000), n(m, "minsize", previous.minsize, 8192), n(m, "pad", previous.pad, 1024));
                if (m.size() > 1) { if (action.equals("rowconfigure")) f.rowconfigure(index, slot); else f.columnconfigure(index, slot); }
                Map<String, Object> values = new LinkedHashMap<>(); values.put("weight", slot.weight); values.put("minsize", slot.minsize); values.put("pad", slot.pad); return values;
            case "grid_size": keys(m); return new Object[] {TkGridLayout.count(f, true), TkGridLayout.count(f, false)};
            case "grid_bbox":
                keys(m, "column", "row", "column2", "row2"); f.tree.render();
                if (f.gridded.isEmpty()) return new Object[] {0, 0, 0, 0};
                int c = n(m, "column", 0, 127), r = n(m, "row", 0, 127);
                int c2 = n(m, "column2", m.containsKey("column") ? c : Math.max(0, f.gridColumns.length - 1), 127);
                int r2 = n(m, "row2", m.containsKey("row") ? r : Math.max(0, f.gridRows.length - 1), 127);
                int left = boundary(f.gridColumns, Math.min(c, c2)), top = boundary(f.gridRows, Math.min(r, r2));
                return new Object[] {f.gridX - f.bounds.x + left, f.gridY - f.bounds.y + top,
                        boundary(f.gridColumns, Math.max(c, c2) + 1) - left, boundary(f.gridRows, Math.max(r, r2) + 1) - top};
            case "grid_slaves": case "place_slaves": case "pack_slaves":
                keys(m); java.util.List<Object> ids = new java.util.ArrayList<>();
                java.util.List<TkWidget> children = action.equals("grid_slaves") ? f.gridded : action.equals("pack_slaves") ? f.packed : f.children;
                for (TkWidget child : children) if (!action.equals("place_slaves") || child.place != null) ids.add(child.id);
                if (!action.equals("pack_slaves")) java.util.Collections.reverse(ids); return ids.toArray();
            default: throw new IllegalArgumentException("unsupported geometry command: " + action);
        }
    }
    private static int boundary(int[] offsets, int i) { return i == 0 || offsets.length == 0 ? 0 : offsets[Math.min(i, offsets.length) - 1]; }
}
