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

    // ---- scrolling (Tk's canvas view) -----------------------------------------------------

    /** Canvas coordinates are limited to this (TkVector); so are the view origin and the scroll region. */
    static final int MAX_VIEW = 32768;
    /** The canvas coordinate at the window's top left corner (Tk's xOrigin/yOrigin, border included). */
    int viewX, viewY;
    /** x1, y1, x2, y2 that the scrollbars cover, or null for none. */
    int[] scrollregion;
    boolean confine = true;
    int xscrollincrement, yscrollincrement, xscrollcommand, yscrollcommand;
    private String lastX = "", lastY = "";

    private int window(boolean x) {
        int laid = x ? bounds.width : bounds.height;
        return laid > 0 ? laid : (x ? width : height) + 2 * border;
    }

    /**
     * Move the view as Tk's CanvasSetOrigin does: round to the scroll increment (measured
     * from just inside the border), then, when confined to a scroll region, keep as much of
     * the region in view as fits.
     */
    void origin(long x, long y) {
        int nx = place(x, true), ny = place(y, false);
        if (nx != viewX || ny != viewY) { viewX = nx; viewY = ny; tree.changed(false); }
    }
    private int place(long origin, boolean x) {
        int inset = border, increment = x ? xscrollincrement : yscrollincrement;
        origin = Math.max(-MAX_VIEW, Math.min(MAX_VIEW, origin));
        // Tk's rounding, both branches as written there (C and Java % truncate alike).
        if (increment > 0) {
            if (origin >= 0) { origin += increment / 2; origin -= (origin + inset) % increment; }
            else { origin = -origin + increment / 2; origin = -(origin - (origin - inset) % increment); }
        }
        if (confine && scrollregion != null) {
            long lo = scrollregion[x ? 0 : 1], hi = scrollregion[x ? 2 : 3];
            long left = origin + inset, right = origin + window(x) - inset;
            // Shift by whole increments only, so the view stays on the increment grid.
            if (right > hi && left > lo) {
                long delta = Math.min(right - hi, left - lo);
                if (increment > 0) delta -= delta % increment;
                origin -= delta;
            } else if (left < lo && right < hi) {
                long delta = Math.min(lo - left, hi - right);
                if (increment > 0) delta -= delta % increment;
                origin += delta;
            }
        }
        return (int) Math.max(-MAX_VIEW, Math.min(MAX_VIEW, origin));
    }

    /** Tk's xview/yview: the visible part of the scroll region as fractions (0, 1 without one). */
    Object[] fractions(boolean x) {
        int inset = border;
        double first = 0, last = 1;
        if (scrollregion != null) {
            double lo = scrollregion[x ? 0 : 1], range = scrollregion[x ? 2 : 3] - lo, origin = x ? viewX : viewY;
            if (range > 0) {
                first = Math.max(0, (origin + inset - lo) / range);
                last = Math.max(first, Math.min(1, (origin + window(x) - inset - lo) / range));
            }
        }
        return new Object[] {first, last};
    }

    /** xview/yview with arguments: ("moveto", fraction) or ("scroll", count, "units"|"pages"). */
    Object view(boolean x, Object[] args) {
        if (args.length == 0) return fractions(x);
        String op = TkOptions.text(args[0]);
        int inset = border, span = Math.max(0, window(x) - 2 * inset), origin = x ? viewX : viewY;
        long next;
        if (op.equals("moveto")) {
            if (args.length != 2) throw new IllegalArgumentException("moveto takes a fraction");
            double fraction = Math.max(0, Math.min(1, numeric(args[1])));
            int lo = scrollregion == null ? 0 : scrollregion[x ? 0 : 1], hi = scrollregion == null ? 0 : scrollregion[x ? 2 : 3];
            next = lo - inset + (long) (fraction * (hi - lo) + 0.5);
        } else if (op.equals("scroll")) {
            if (args.length != 3) throw new IllegalArgumentException("scroll takes a count and units or pages");
            double count = numeric(args[1]);
            if (count != Math.rint(count) || Math.abs(count) > 1e6) throw new IllegalArgumentException("scroll count must be an integer");
            String what = TkOptions.text(args[2]);
            int increment = x ? xscrollincrement : yscrollincrement;
            if (what.equals("units")) next = increment > 0 ? origin + (long) count * increment : (long) (origin + count * .1 * span);
            else if (what.equals("pages")) next = (long) (origin + count * .9 * span);
            else throw new IllegalArgumentException("units or pages required");
        } else throw new IllegalArgumentException("moveto or scroll required");
        if (x) origin(next, viewY); else origin(viewX, next);
        return null;
    }
    private static double numeric(Object value) {
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if (Double.isFinite(n)) return n;
        } else {
            try { double n = Double.parseDouble(TkOptions.text(value)); if (Double.isFinite(n)) return n; }
            catch (NumberFormatException ignored) { /* below */ }
        }
        throw new IllegalArgumentException("number expected");
    }

    /**
     * The mouse wheel scrolls a canvas that has a scroll region, unless the program handles
     * the wheel itself: on the canvas, its items, or its toplevel (the bindtags a wheel
     * event reaches, see TileTkDisplay.boundEvent). Tk has no wheel binding for canvases.
     */
    void wheel(int delta) {
        TkWidget top = TkWindow.of(this);
        if (top == null) top = tree.root();
        if (scrollregion == null || bindings.containsKey("<MouseWheel>") || top.bindings.containsKey("<MouseWheel>")
                || tagBindings.values().stream().anyMatch(b -> b.containsKey("<MouseWheel>"))) return;
        view(false, new Object[] {"scroll", delta > 0 ? -1 : 1, "units"});
    }

    private void notifyView(boolean x) {
        int callback = x ? xscrollcommand : yscrollcommand;
        Object[] view = fractions(x);
        String key = callback + ":" + view[0] + ":" + view[1];
        if (!key.equals(x ? lastX : lastY)) { if (x) lastX = key; else lastY = key; tree.queue(this, callback, view); }
    }

    static final class Item {
        final int id;
        final TkVector vector;
        final String text, state;
        final List<String> tags;
        final TkPack.Anchor anchor;
        /** The PhotoImage of an image item. */
        final int image;
        Item(int id, TkVector v, String text, String state, List<String> tags, TkPack.Anchor anchor) {
            this(id, v, text, state, tags, anchor, 0);
        }
        Item(int id, TkVector v, String text, String state, List<String> tags, TkPack.Anchor anchor, int image) {
            this.id = id; vector = v; this.text = text; this.state = state;
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags)); this.anchor = anchor; this.image = image;
        }
        Item withImage(int value) { return new Item(id, vector, text, state, tags, anchor, value); }
        /** Where an image item's picture lies, given the image's size. */
        TkRect imageBounds(TkImage image) {
            if (image == null) return TkRect.EMPTY;
            return new TkRect((int) Math.round(vector.at(0) - image.width * anchor.x / 2.0),
                    (int) Math.round(vector.at(1) - image.height * anchor.y / 2.0), image.width, image.height);
        }
        TkRect textBounds() {
            int w = TkFontMetrics.width(text), h = text.isEmpty() ? 0 : TkFontMetrics.height(text);
            return new TkRect((int) Math.round(vector.at(0)-w*anchor.x/2.0),
                    (int) Math.round(vector.at(1)-h*anchor.y/2.0), w, h);
        }
        Item update(double[] coords, Map<String, Object> m) { return update(coords, m, null); }
        Item update(double[] coords, Map<String, Object> m, TkTree tree) {
            for (String k : m.keySet()) if (!Arrays.asList("fill", "outline", "width", "tags", "state", "text", "anchor", "image").contains(k))
                throw new IllegalArgumentException("unsupported Canvas item option: " + k);
            boolean textItem = vector.shape == TkVector.Shape.TEXT, imageItem = vector.shape == TkVector.Shape.IMAGE;
            if ((!textItem && m.containsKey("text")) || !textItem && !imageItem && m.containsKey("anchor")
                    || !imageItem && m.containsKey("image")
                    || imageItem && (m.containsKey("fill") || m.containsKey("outline") || m.containsKey("width"))
                    || (textItem || vector.shape == TkVector.Shape.LINE) && m.containsKey("outline")
                    || textItem && m.containsKey("width")) throw new IllegalArgumentException("Canvas option does not apply to item type");
            int image = this.image;
            if (m.containsKey("image")) {
                image = TkOptions.integer(m.get("image"), 0, Integer.MAX_VALUE);
                if (image != 0 && (tree == null || tree.ownedImage(image) == null)) throw new IllegalArgumentException("image does not exist");
            }
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
                    text, state, tags, m.containsKey("anchor") ? TkOptions.anchor(TkOptions.text(m.get("anchor"))) : anchor, image);
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
        if (action.equals("xview") || action.equals("yview"))
            return view(action.equals("xview"), m.containsKey("args") ? TkOptions.sequence(m.get("args"), 3) : new Object[0]);
        if (action.equals("origin")) return new Object[] {viewX, viewY};
        if (action.equals("create")) {
            if (nextItem == Integer.MAX_VALUE) throw new IllegalArgumentException("Canvas item IDs exhausted");
            TkVector.Shape shape = TkVector.Shape.valueOf(TkOptions.text(m.get("type")).toUpperCase(Locale.ROOT));
            int fill = shape == TkVector.Shape.RECTANGLE || shape == TkVector.Shape.OVAL || shape == TkVector.Shape.IMAGE ? -1 : 0;
            int outline = fill == -1 && shape != TkVector.Shape.IMAGE ? 0 : -1;
            Item item = new Item(nextItem, new TkVector(shape, coords(m.get("coords")), fill, outline, shape == TkVector.Shape.IMAGE ? 0 : 1),
                    "", "normal", Collections.emptyList(), TkPack.Anchor.CENTER)
                    .update(null, TkOptions.map(m.get("options")), tree);
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
                if (first != null) { LinkedHashMap<Integer, Item> next = new LinkedHashMap<>(items); next.put(first.id, first.update(points, Collections.emptyMap(), tree)); publish(next); }
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
            next.put(item.id, item.update(changed, style, tree));
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
        if (!Arrays.asList("<Button-1>", "<ButtonRelease-1>", "<Button-3>", "<ButtonRelease-3>", "<Motion>", "<B1-Motion>", "<MouseWheel>", "<Double-Button-1>").contains(sequence))
            throw new IllegalArgumentException("Canvas tag_bind supports pointer events only");
    }
    private Object cget(Item i, String key) {
        switch (key) {
            case "fill": return i.vector.fill; case "outline": return i.vector.outline; case "width": return i.vector.width;
            case "text": return i.text; case "state": return i.state; case "tags": return i.tags.toArray();
            case "anchor": return i.anchor.name().toLowerCase(Locale.ROOT);
            case "image": return i.image;
            default: throw new IllegalArgumentException("unsupported Canvas item option: " + key);
        }
    }
    private TkRect union(TkRect a, TkRect b) { int x = Math.min(a.x,b.x), y = Math.min(a.y,b.y); return new TkRect(x,y,Math.max(a.x+a.width,b.x+b.width)-x,Math.max(a.y+a.height,b.y+b.height)-y); }
    private TkRect bbox(Item i) {
        if (i.vector.shape == TkVector.Shape.TEXT) return i.textBounds();
        if (i.vector.shape == TkVector.Shape.IMAGE) return i.imageBounds(tree.images.get(i.image));
        TkVector v = i.vector; double x0=v.at(0),x1=x0,y0=v.at(1),y1=y0;
        for (int j=2;j<v.count();j+=2) { x0=Math.min(x0,v.at(j));x1=Math.max(x1,v.at(j));y0=Math.min(y0,v.at(j+1));y1=Math.max(y1,v.at(j+1)); }
        double pad = (v.shape == TkVector.Shape.LINE || v.outline >= 0) ? Math.max(1,v.width)/2 : 0;
        int x=(int)Math.floor(x0-pad),y=(int)Math.floor(y0-pad);
        return new TkRect(x,y,(int)Math.ceil(x1+pad)-x+1,(int)Math.ceil(y1+pad)-y+1);
    }
    public int hitItem(int x, int y) {
        if (!tree.available(this) || !interior().contains(x,y)) return 0;
        List<Item> order = new ArrayList<>(items.values());
        for (int j=order.size()-1;j>=0;j--) {
            Item i=order.get(j);
            TkRect box = i.vector.shape == TkVector.Shape.IMAGE ? i.imageBounds(tree.images.get(i.image)) : i.textBounds();
            if (i.state.equals("normal") && i.vector.hit(x-bounds.x+viewX,y-bounds.y+viewY,1,box)) return i.id;
        }
        return 0;
    }
    /**
     * Item bindings for {@code sequence}, as {callback, sequence}; per tag, {@code preferred}
     * (a double click's {@code <Double-Button-1>}) wins over {@code sequence} where both are bound.
     */
    List<Integer> callbacks(int itemId, String sequence) {
        List<Integer> ids = new ArrayList<>();
        for (Object[] bound : callbacks(itemId, sequence, null)) ids.add((Integer) bound[0]);
        return ids;
    }
    List<Object[]> callbacks(int itemId, String sequence, String preferred) {
        current = itemId; Item i = items.get(itemId); List<Object[]> result = new ArrayList<>();
        if (i == null || !i.state.equals("normal")) return result;
        List<String> selectors = new ArrayList<>(); selectors.add("all"); selectors.addAll(i.tags); selectors.add("current"); selectors.add(Integer.toString(i.id));
        for (String s : selectors) {
            Map<String,Integer> bindings=tagBindings.get(s);
            if (bindings == null) continue;
            String used = preferred != null && bindings.containsKey(preferred) ? preferred : sequence;
            if (bindings.containsKey(used)) result.add(new Object[] {bindings.get(used), used});
        }
        return result;
    }
    private TkRect interior() { return clip.intersect(new TkRect(bounds.x+border,bounds.y+border,Math.max(0,bounds.width-2*border),Math.max(0,bounds.height-2*border))); }
    /**
     * Item records, scrolled by the view. Canvas (0, 0) is at (ox, oy) on the display: text
     * and image records carry their own top left there, the other shapes carry that origin
     * in textX/textY and the client draws their (unchanged) coordinates from it. Items
     * wholly outside the visible part are left out, so a large scrolled canvas sends only
     * what can be seen.
     */
    void paint(List<TkDisplayList.Item> out, int state) {
        // As Tk does on every resize: keep a confined view inside the region at the new size
        // (the tree is rendering already, so no change notification is needed).
        viewX = place(viewX, true); viewY = place(viewY, false);
        notifyView(false); notifyView(true);
        TkRect clip = interior(); if (clip.empty()) return;
        int ox = bounds.x - viewX, oy = bounds.y - viewY;
        TkRect visible = new TkRect(clip.x - ox, clip.y - oy, clip.width, clip.height);
        for (Item i : items.values()) if (!i.state.equals("hidden")) {
            int itemState = state | (i.state.equals("disabled") ? TkDisplayList.DISABLED : 0);
            if (i.vector.shape == TkVector.Shape.IMAGE) {
                TkImage image = tree.images.get(i.image);
                if (image == null) continue;
                TkRect box = i.imageBounds(image);
                if (!overlaps(box, visible)) continue;
                out.add(new TkDisplayList.Item(id,i.id,Kind.CANVAS,bounds,clip,background,0,0,itemState,ox+box.x,oy+box.y,"",-1,0,0,i.vector,image.id));
                continue;
            }
            if (i.vector.shape == TkVector.Shape.TEXT) {
                TkRect text = i.textBounds();
                if (!overlaps(text, visible)) continue;
                out.add(new TkDisplayList.Item(id,i.id,Kind.CANVAS,bounds,clip,background,Math.max(0,i.vector.fill),0,itemState,ox+text.x,oy+text.y,i.text,-1,0,0,i.vector));
                continue;
            }
            if (!overlaps(bbox(i), visible)) continue;
            out.add(new TkDisplayList.Item(id,i.id,Kind.CANVAS,bounds,clip,background,Math.max(0,i.vector.fill),0,itemState,ox,oy,"",-1,0,0,i.vector));
        }
    }
    private static boolean overlaps(TkRect a, TkRect b) {
        return !a.empty() && a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
    }
}
