package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Retained Canvas items. IDs never alias deleted items; updates validate before publication. */
public final class Canvas extends TkWidget {
    static final int MAX_ITEMS = 512, MAX_TREE_ITEMS = 1024, MAX_BINDINGS = 256;
    final LinkedHashMap<Integer, Item> items = new LinkedHashMap<>();
    final Map<String, Map<String, Integer>> tagBindings = new LinkedHashMap<>();
    int nextItem = 1, current;
    public Canvas(Frame parent) { super(parent.tree, parent, Kind.CANVAS); width = 300; height = 200; }

    static final class Item {
        final int id;
        final TkVector vector;
        final String text, state;
        final List<String> tags;
        final TkPack.Anchor anchor;
        Item(int id, TkVector v, String text, String state, List<String> tags, TkPack.Anchor anchor) {
            this.id = id; vector = v; this.text = text; this.state = state;
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags)); this.anchor = anchor;
        }
        TkRect textBounds() {
            int w = TkFontMetrics.width(text), h = text.isEmpty() ? 0 : TkFontMetrics.height(text);
            return new TkRect((int) Math.round(vector.at(0)-w*anchor.x/2.0),
                    (int) Math.round(vector.at(1)-h*anchor.y/2.0), w, h);
        }
        Item update(double[] coords, Map<String, Object> m) {
            for (String k : m.keySet()) if (!Arrays.asList("fill", "outline", "width", "tags", "state", "text", "anchor").contains(k))
                throw new IllegalArgumentException("unsupported Canvas item option: " + k);
            boolean textItem = vector.shape == TkVector.Shape.TEXT;
            if ((!textItem && (m.containsKey("text") || m.containsKey("anchor")))
                    || (textItem || vector.shape == TkVector.Shape.LINE) && m.containsKey("outline")
                    || textItem && m.containsKey("width")) throw new IllegalArgumentException("Canvas option does not apply to item type");
            int fill = color(m, "fill", vector.fill), outline = color(m, "outline", vector.outline);
            double width = m.containsKey("width") ? number(m.get("width")) : vector.width;
            String text = TkOptions.string(m, "text", this.text), state = TkOptions.string(m, "state", this.state);
            TkFontMetrics.checkText(text);
            if (!Arrays.asList("normal", "disabled", "hidden").contains(state)) throw new IllegalArgumentException("invalid Canvas item state");
            List<String> tags = this.tags;
            if (m.containsKey("tags")) {
                Object value = m.get("tags"); Object[] raw = value instanceof String || value instanceof byte[]
                        ? new Object[] {value} : TkOptions.sequence(value, 16);
                tags = new ArrayList<>();
                for (Object t : raw) { String tag = tag(t); if (!tags.contains(tag)) tags.add(tag); }
            }
            return new Item(id, new TkVector(vector.shape, coords == null ? vector.coords() : coords, fill, outline, width).sameOutlineAs(vector),
                    text, state, tags, m.containsKey("anchor") ? TkOptions.anchor(TkOptions.text(m.get("anchor"))) : anchor);
        }
    }
    private static double number(Object o) {
        if (!(o instanceof Number) || !Double.isFinite(((Number) o).doubleValue())) throw new IllegalArgumentException("finite number expected");
        return ((Number) o).doubleValue();
    }
    private static int color(Map<String, Object> m, String key, int fallback) {
        return m.containsKey(key) ? TkOptions.integer(m.get(key), -1, 0xffffff) : fallback;
    }
    static String selector(Object value) {
        if (value instanceof Number) return Integer.toString(TkOptions.integer(value, 1, Integer.MAX_VALUE));
        String s = TkOptions.text(value);
        if (s.isEmpty() || s.length() > 64) throw new IllegalArgumentException("invalid Canvas selector");
        return s;
    }
    static String tag(Object value) {
        String s = selector(value);
        if (s.matches("[+-]?[0-9]+") || s.equals("all") || s.equals("current")) throw new IllegalArgumentException("reserved Canvas tag: " + s);
        return s;
    }
    private List<Item> matching(String selector) {
        List<Item> result = new ArrayList<>();
        for (Item i : items.values()) if (selector.equals("all") || selector.equals("current") && i.id == current
                || selector.equals(Integer.toString(i.id)) || i.tags.contains(selector)) result.add(i);
        return result;
    }
    private static double[] coords(Object value) {
        Object[] raw = TkOptions.sequence(value, TkVector.MAX_COORDS); double[] points = new double[raw.length];
        for (int i = 0; i < raw.length; i++) points[i] = number(raw[i]); return points;
    }
    private void publish(LinkedHashMap<Integer, Item> next) {
        checkBudget(next); items.clear(); items.putAll(next); if (!items.containsKey(current)) current = 0; tree.changed(false);
    }
    /** Self-intersections a filled polygon may have, and all filled polygons together. */
    static final int MAX_POLYGON_CROSSINGS = 128, MAX_TREE_CROSSINGS = 512;
    void checkBudget(Map<Integer, Item> next) {
        int count = 0, coords = 0, bytes = 0, crossings = 0;
        if (next.size() > MAX_ITEMS) throw new IllegalArgumentException("Canvas item limit reached");
        for (TkWidget w : tree.widgets.values()) if (w instanceof Canvas)
            for (Item i : (w == this ? next : ((Canvas) w).items).values()) {
                count++; coords += i.vector.count(); bytes += i.text.getBytes(StandardCharsets.UTF_8).length;
                // Only filled polygons are tessellated on the client; strokes are linear.
                if (i.vector.shape == TkVector.Shape.POLYGON && i.vector.fill >= 0) {
                    int c = i.vector.crossings();
                    if (c > MAX_POLYGON_CROSSINGS) throw new IllegalArgumentException(
                            "Canvas polygon has too many self-intersections (" + c + " > " + MAX_POLYGON_CROSSINGS + ")");
                    crossings += c;
                }
            }
        if (count > MAX_TREE_ITEMS || coords > TkVector.MAX_TOTAL_COORDS || bytes > TkDisplayList.MAX_TEXT_BYTES
                || crossings > MAX_TREE_CROSSINGS)
            throw new IllegalArgumentException("Canvas tree budget exceeded");
    }
    public Object call(String action, Map<String, Object> m) {
        alive();
        if (action.equals("create")) {
            if (nextItem == Integer.MAX_VALUE) throw new IllegalArgumentException("Canvas item IDs exhausted");
            TkVector.Shape shape = TkVector.Shape.valueOf(TkOptions.text(m.get("type")).toUpperCase(Locale.ROOT));
            int fill = shape == TkVector.Shape.RECTANGLE || shape == TkVector.Shape.OVAL ? -1 : 0;
            int outline = fill == -1 ? 0 : -1;
            Item item = new Item(nextItem, new TkVector(shape, coords(m.get("coords")), fill, outline, 1), "", "normal", Collections.emptyList(), TkPack.Anchor.CENTER)
                    .update(null, TkOptions.map(m.get("options")));
            LinkedHashMap<Integer, Item> next = new LinkedHashMap<>(items); next.put(item.id, item); publish(next); return nextItem++;
        }
        String selector = selector(m.getOrDefault("selector", "all"));
        List<Item> selected = matching(selector); Item first = selected.isEmpty() ? null : selected.get(0);
        switch (action) {
            case "find": return selected.stream().map(i -> i.id).toArray();
            case "type": return first == null ? "" : first.vector.shape.name().toLowerCase(Locale.ROOT);
            case "tags": return first == null ? new Object[0] : first.tags.toArray();
            case "coords":
                if (!m.containsKey("coords")) return first == null ? new Object[0] : Arrays.stream(first.vector.coords()).boxed().toArray();
                double[] points = coords(m.get("coords"));
                if (first != null) { LinkedHashMap<Integer, Item> next = new LinkedHashMap<>(items); next.put(first.id, first.update(points, Collections.emptyMap())); publish(next); }
                return null;
            case "cget": return first == null ? "" : cget(first, TkOptions.text(m.get("option")));
            case "bbox": {
                TkRect box = null;
                for (Item i : selected) if (!i.state.equals("hidden")) { TkRect r = bbox(i); if (!r.empty()) box = box == null ? r : union(box, r); }
                return box == null ? new Object[0] : new Object[] {box.x, box.y, box.x+box.width, box.y+box.height};
            }
            case "bind": {
                String sequence = TkOptions.text(m.get("sequence")); validateSequence(sequence);
                int callback = TkOptions.integer(m.get("callback"), 0, Integer.MAX_VALUE);
                Map<String, Integer> bindings = tagBindings.get(selector);
                if (callback == 0) { if (bindings != null) { bindings.remove(sequence); if (bindings.isEmpty()) tagBindings.remove(selector); } }
                else {
                    int count = tagBindings.values().stream().mapToInt(Map::size).sum();
                    if (count >= MAX_BINDINGS && (bindings == null || !bindings.containsKey(sequence))) throw new IllegalArgumentException("Canvas binding limit reached");
                    if (bindings == null) { bindings = new LinkedHashMap<>(); tagBindings.put(selector, bindings); }
                    bindings.put(sequence, callback);
                }
                tree.changed(false); return null;
            }
            case "configure": case "move": case "delete": case "addtag": case "dtag": case "raise": case "lower": break;
            default: throw new IllegalArgumentException("unsupported Canvas operation: " + action);
        }
        LinkedHashMap<Integer, Item> next = new LinkedHashMap<>(items);
        Map<String, Object> options = action.equals("configure") ? TkOptions.map(m.get("options")) : Collections.emptyMap();
        double dx = action.equals("move") ? number(m.get("dx")) : 0, dy = action.equals("move") ? number(m.get("dy")) : 0;
        String tag = action.equals("addtag") ? tag(m.get("tag")) : action.equals("dtag") ? selector(m.getOrDefault("tag", selector)) : "";
        for (Item item : selected) {
            if (action.equals("delete")) { next.remove(item.id); continue; }
            if (action.equals("raise") || action.equals("lower")) continue;
            double[] changed = null; Map<String, Object> style = options;
            if (action.equals("move")) { changed = item.vector.coords(); for (int j = 0; j < changed.length; j += 2) { changed[j] += dx; changed[j+1] += dy; } }
            if (action.equals("addtag") || action.equals("dtag")) {
                List<String> tags = new ArrayList<>(item.tags); if (action.equals("dtag")) tags.remove(tag); else if (!tags.contains(tag)) tags.add(tag);
                style = Collections.singletonMap("tags", tags.toArray());
            }
            next.put(item.id, item.update(changed, style));
        }
        if (action.equals("raise") || action.equals("lower")) {
            boolean up = action.equals("raise"); List<Item> order = new ArrayList<>(items.values());
            Item relative = null;
            if (m.containsKey("relative")) {
                List<Item> relatives = matching(selector(m.get("relative")));
                if (relatives.isEmpty()) throw new IllegalArgumentException("relative Canvas item not found");
                relative = relatives.get(up ? relatives.size()-1 : 0);
                if (selected.contains(relative)) return null;
            }
            order.removeAll(selected); int index = relative == null ? up ? order.size() : 0 : order.indexOf(relative)+(up ? 1 : 0);
            order.addAll(index, selected); next.clear(); for (Item i : order) next.put(i.id, i);
        }
        publish(next);
        if (action.equals("delete")) for (Item item : selected) tagBindings.remove(Integer.toString(item.id));
        return null;
    }
    static void validateSequence(String sequence) {
        if (!Arrays.asList("<Button-1>", "<ButtonRelease-1>", "<Button-3>", "<ButtonRelease-3>", "<Motion>", "<B1-Motion>", "<MouseWheel>").contains(sequence))
            throw new IllegalArgumentException("Canvas tag_bind supports pointer events only");
    }
    private Object cget(Item i, String key) {
        switch (key) {
            case "fill": return i.vector.fill; case "outline": return i.vector.outline; case "width": return i.vector.width;
            case "text": return i.text; case "state": return i.state; case "tags": return i.tags.toArray();
            case "anchor": return i.anchor.name().toLowerCase(Locale.ROOT);
            default: throw new IllegalArgumentException("unsupported Canvas item option: " + key);
        }
    }
    private TkRect union(TkRect a, TkRect b) { int x = Math.min(a.x,b.x), y = Math.min(a.y,b.y); return new TkRect(x,y,Math.max(a.x+a.width,b.x+b.width)-x,Math.max(a.y+a.height,b.y+b.height)-y); }
    private TkRect bbox(Item i) {
        if (i.vector.shape == TkVector.Shape.TEXT) return i.textBounds();
        TkVector v = i.vector; double x0=v.at(0),x1=x0,y0=v.at(1),y1=y0;
        for (int j=2;j<v.count();j+=2) { x0=Math.min(x0,v.at(j));x1=Math.max(x1,v.at(j));y0=Math.min(y0,v.at(j+1));y1=Math.max(y1,v.at(j+1)); }
        double pad = (v.shape == TkVector.Shape.LINE || v.outline >= 0) ? Math.max(1,v.width)/2 : 0;
        int x=(int)Math.floor(x0-pad),y=(int)Math.floor(y0-pad);
        return new TkRect(x,y,(int)Math.ceil(x1+pad)-x+1,(int)Math.ceil(y1+pad)-y+1);
    }
    public int hitItem(int x, int y) {
        if (!tree.available(this) || !interior().contains(x,y)) return 0;
        List<Item> order = new ArrayList<>(items.values());
        for (int j=order.size()-1;j>=0;j--) { Item i=order.get(j); if (i.state.equals("normal") && i.vector.hit(x-bounds.x,y-bounds.y,1,i.textBounds())) return i.id; }
        return 0;
    }
    List<Integer> callbacks(int itemId, String sequence) {
        current = itemId; Item i = items.get(itemId); List<Integer> result = new ArrayList<>();
        if (i == null || !i.state.equals("normal")) return result;
        List<String> selectors = new ArrayList<>(); selectors.add("all"); selectors.addAll(i.tags); selectors.add("current"); selectors.add(Integer.toString(i.id));
        for (String s : selectors) { Map<String,Integer> bindings=tagBindings.get(s); if (bindings!=null && bindings.containsKey(sequence)) result.add(bindings.get(sequence)); }
        return result;
    }
    private TkRect interior() { return clip.intersect(new TkRect(bounds.x+border,bounds.y+border,Math.max(0,bounds.width-2*border),Math.max(0,bounds.height-2*border))); }
    void paint(List<TkDisplayList.Item> out, int state) {
        TkRect clip = interior(); if (clip.empty()) return;
        for (Item i : items.values()) if (!i.state.equals("hidden")) {
            TkRect text = i.textBounds();
            out.add(new TkDisplayList.Item(id,i.id,Kind.CANVAS,bounds,clip,background,Math.max(0,i.vector.fill),0,
                    state | (i.state.equals("disabled") ? TkDisplayList.DISABLED : 0),bounds.x+text.x,bounds.y+text.y,i.text,-1,0,0,i.vector));
        }
    }
}
