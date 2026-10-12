package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ttk::treeview: a tree of items (text, values in columns, open or closed, tags) under column
 * headings, as Tk 8.6's default theme lays it out (1 px border, 19 px headings, 20 px rows, 20 px
 * per level). The whole tree lives here; only the rows in view are painted. Column widths follow
 * the widget's width as Tk's slack algorithm moves them (resize), and a heading separator drags
 * as Tk's DragColumn does. Player input as ttk/treeview.tcl: see press, key.
 */
public final class Treeview extends TkWidget {
    static final int HEADING = 19, ROW = 20, INDENT = 20, INDICATOR = 16, HALO = 4;
    static final int MAX_ITEMS = 20000, MAX_BYTES = 2 * 1024 * 1024, MAX_COLUMNS = 64, MAX_BATCH = 4096;
    static final int SELECTED = 0x4A6984, HEADING_BACKGROUND = 0xD9D9D9;
    static final String[] ANCHORS = {"n", "ne", "e", "se", "s", "sw", "w", "nw", "center"};

    static final class Item {
        final String id;
        Item parent;
        final List<Item> children = new ArrayList<>();
        String text = "", image = "";
        final List<String> values = new ArrayList<>(), tags = new ArrayList<>();
        boolean open;
        Item(String id) { this.id = id; }
        int bytes() {
            int n = text.getBytes(StandardCharsets.UTF_8).length + image.length();
            for (String v : values) n += v.getBytes(StandardCharsets.UTF_8).length;
            for (String t : tags) n += t.length();
            return n;
        }
    }

    static final class Column {
        final String id;
        int width = 200, minWidth = 20;
        boolean stretch = true;
        String anchor = "w";
        String heading = "", headingAnchor = "center", headingImage = "";
        boolean command;
        Column(String id) { this.id = id; }
    }

    final Item root = new Item("");
    final Map<String, Item> items = new HashMap<>();
    /** Column #0, the tree (its id is ""), and the data columns (-columns) in order. */
    final Column treeColumn = new Column("");
    final List<Column> columns = new ArrayList<>();
    /** -displaycolumns: the data columns shown, in order; null for #all. */
    List<Column> display;
    int lines = 10;
    String selectmode = "extended";
    boolean showTree = true, showHeadings = true;
    final Set<Item> selected = new HashSet<>();
    Item focus;
    int top, scrollX, slack, serial, bytes, xscrollcommand, yscrollcommand, notify;
    /** Tag colours drawn (-foreground, -background): tag -> {fg, bg}, -1 unset. */
    final Map<String, int[]> tagColors = new LinkedHashMap<>();
    /** A player's press on a heading (its display index, drawn pressed) or on a separator (the column left of it); never saved. */
    int pressedHeading = -1, dragging = -1;
    private String lastX = "", lastY = "";
    private List<Item> rows;
    private List<Integer> depths;

    public Treeview(Frame parent) {
        super(parent.tree, parent, Kind.TREEVIEW);
        border = 1; relief = Relief.SUNKEN; background = 0xFFFFFF;
    }

    // ---- the tree ----------------------------------------------------------------------------

    Item item(String id) {
        if (id.isEmpty()) return root;
        Item item = items.get(id);
        if (item == null) throw new IllegalArgumentException("Item " + id + " not found");
        return item;
    }

    private void changedTree() { rows = null; tree.changed(false); }

    /** Whether item is attached: in the tree under the root (not detached). */
    boolean attached(Item item) {
        for (Item i = item; i != null; i = i.parent) if (i == root) return true;
        return false;
    }

    private static boolean within(Item item, Item ancestor) {
        for (Item i = item; i != null; i = i.parent) if (i == ancestor) return true;
        return false;
    }

    private void unlink(Item item) {
        if (item.parent != null) item.parent.children.remove(item);
        item.parent = null;
    }

    /** Put item under parent at index (Tk's move: the index counts parent's children other than item). */
    private void link(Item item, Item parent, int index) {
        if (within(parent, item)) throw new IllegalArgumentException("Cannot insert " + item.id + " as descendant of " + parent.id);
        unlink(item);
        int at = Math.max(0, Math.min(index, parent.children.size()));
        parent.children.add(at, item);
        item.parent = parent;
    }

    private int index(Item parent, Object raw) {
        if (raw instanceof String && TkOptions.text(raw).equals("end")) return parent.children.size();
        if (raw instanceof Number) return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(((Number) raw).doubleValue())));
        String text = TkOptions.text(raw);
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException bad) { throw new IllegalArgumentException("expected integer but got \"" + text + "\""); }
    }

    /** All items under root in preorder (attached ones), and with {@code openOnly} only the shown ones, with their depths. */
    private void walk(Item from, int depth, boolean openOnly, List<Item> out, List<Integer> levels) {
        for (Item child : from.children) {
            out.add(child);
            if (levels != null) levels.add(depth);
            if (!openOnly || child.open) walk(child, depth + 1, openOnly, out, levels);
        }
    }

    /** The rows shown (attached, every ancestor open), top to bottom. */
    List<Item> rows() {
        if (rows == null) {
            rows = new ArrayList<>(); depths = new ArrayList<>();
            walk(root, 0, true, rows, depths);
        }
        return rows;
    }
    int depth(int row) { rows(); return depths.get(row); }

    private List<Item> preorder() { List<Item> out = new ArrayList<>(); walk(root, 0, false, out, null); return out; }

    void setOptions(Item item, Map<String, Object> m) {
        for (String key : m.keySet())
            if (!Arrays.asList("text", "values", "open", "tags", "image").contains(key)) throw new IllegalArgumentException("unknown option \"-" + key + "\"");
        String text = m.containsKey("text") ? TkOptions.text(m.get("text")) : item.text;
        if (text.indexOf('\n') >= 0) throw new IllegalArgumentException("Treeview text must be one line");
        TkFontMetrics.checkText(text);
        List<String> values = m.containsKey("values") ? strings(m.get("values")) : item.values;
        List<String> tags = m.containsKey("tags") ? strings(m.get("tags")) : item.tags;
        String image = m.containsKey("image") ? TkOptions.text(m.get("image")) : item.image;
        if (image.length() > 256) throw new IllegalArgumentException("image name too long");
        int before = item.bytes();
        Item probe = new Item("");
        probe.text = text; probe.image = image; probe.values.addAll(values); probe.tags.addAll(tags);
        int grown = probe.bytes() - before;
        if (bytes + grown > MAX_BYTES || displayBytes() + grown > 8 * MAX_BYTES / 2) throw new IllegalArgumentException("Treeview text budget exceeded");
        item.text = text; item.image = image;
        if (values != item.values) { item.values.clear(); item.values.addAll(values); }
        if (tags != item.tags) { item.tags.clear(); item.tags.addAll(tags); }
        if (m.containsKey("open")) item.open = TkOptions.bool(m.get("open"));
        bytes += grown;
        rows = null;
    }

    /** Every item: the attached ones in preorder, then the detached subtrees. */
    List<Item> everything() {
        List<Item> out = preorder();
        Set<Item> seen = new HashSet<>(out);
        for (Item item : items.values()) {
            Item top = item;
            while (top.parent != null) top = top.parent;
            if (top == root || seen.contains(top)) continue;
            out.add(top); seen.add(top);
            List<Item> below = new ArrayList<>();
            walk(top, 0, false, below, null);
            out.addAll(below); seen.addAll(below);
        }
        return out;
    }

    private int displayBytes() {
        int n = 0;
        for (TkWidget w : tree.widgets.values()) if (w instanceof Treeview) n += ((Treeview) w).bytes;
        return n;
    }

    private static List<String> strings(Object raw) {
        Object[] items = TkOptions.sequence(raw, MAX_BATCH);
        List<String> out = new ArrayList<>(items.length);
        for (Object item : items) {
            String text = TkOptions.text(item);
            if (text.indexOf('\n') >= 0) throw new IllegalArgumentException("Treeview values must be single lines");
            TkFontMetrics.checkText(text);
            out.add(text);
        }
        return out;
    }

    String insert(Item parent, int index, String id, Map<String, Object> m) {
        alive();
        if (items.size() >= MAX_ITEMS) throw new IllegalArgumentException("Treeview item limit reached (" + MAX_ITEMS + ")");
        if (id == null) {
            do { serial++; id = String.format("I%03X", serial); } while (items.containsKey(id));
        } else {
            if (id.isEmpty() || items.containsKey(id)) throw new IllegalArgumentException("Item " + id + " already exists");
            if (id.length() > 256) throw new IllegalArgumentException("item id too long");
        }
        Item item = new Item(id);
        setOptions(item, m);
        bytes += id.length();
        items.put(id, item);
        item.parent = parent;
        parent.children.add(Math.max(0, Math.min(index, parent.children.size())), item);
        changedTree();
        return id;
    }

    /** Delete items and all below them: the ids gone, and whether the selection changed. */
    Object[] delete(List<Item> victims) {
        alive();
        for (Item v : victims) if (v == root) throw new IllegalArgumentException("Cannot delete root item");
        Set<Item> gone = new HashSet<>();
        List<Object> ids = new ArrayList<>();
        for (Item v : victims) {
            List<Item> all = new ArrayList<>(); all.add(v); walk(v, 0, false, all, null);
            for (Item i : all) if (gone.add(i)) ids.add(i.id);
        }
        boolean selection = false;
        for (Item i : gone) {
            items.remove(i.id); bytes -= i.bytes() + i.id.length();
            selection |= selected.remove(i);
            if (focus == i) focus = null;
        }
        for (Item v : victims) unlink(v);
        changedTree();
        return new Object[] {ids.toArray(), selection};
    }

    void detach(List<Item> victims) {
        alive();
        for (Item v : victims) if (v == root) throw new IllegalArgumentException("Cannot detach root item");
        for (Item v : victims) unlink(v);
        changedTree();
    }

    void move(Item item, Item parent, Object index) {
        alive();
        if (item == root) throw new IllegalArgumentException("Cannot move root item");
        if (within(parent, item)) throw new IllegalArgumentException("Cannot insert " + item.id + " as descendant of " + parent.id);
        int at = index(parent, index);
        // Tk's index counts the parent's children other than the item itself.
        int position = 0, seen = 0;
        for (Item sibling : parent.children) {
            if (seen >= at) break;
            if (sibling != item) seen++;
            position++;
        }
        boolean after = parent.children.indexOf(item) >= 0 && parent.children.indexOf(item) < position;
        link(item, parent, after ? position - 1 : position);
        changedTree();
    }

    void children(Item parent, List<Item> list) {
        alive();
        for (Item child : list) if (within(parent, child)) throw new IllegalArgumentException("Cannot insert " + child.id + " as descendant of " + parent.id);
        for (Item child : new ArrayList<>(parent.children)) unlink(child);
        for (Item child : list) link(child, parent, parent.children.size());
        changedTree();
    }

    List<Item> selection() {
        List<Item> out = new ArrayList<>();
        for (Item i : preorder()) if (selected.contains(i)) out.add(i);
        return out;
    }

    void select(String op, List<Item> list) {
        alive();
        for (Item i : list) if (i == root) throw new IllegalArgumentException("Item  not found");
        switch (op) {
            case "set": selected.clear(); selected.addAll(list); break;
            case "add": selected.addAll(list); break;
            case "remove": selected.removeAll(list); break;
            case "toggle": for (Item i : list) if (!selected.remove(i)) selected.add(i); break;
            default: throw new IllegalArgumentException("bad selection operation \"" + op + "\": must be add, remove, set, or toggle");
        }
        tree.changed(false);
    }

    // ---- columns --------------------------------------------------------------------------------

    /** The columns shown, left to right: #0 (with -show tree), then -displaycolumns. */
    List<Column> shown() {
        List<Column> out = new ArrayList<>();
        if (showTree) out.add(treeColumn);
        out.addAll(display != null ? display : columns);
        return out;
    }

    /** Tk's GetColumn: "#0", "#n" (the n-th displayed data column), a column's id, or an index into -columns. */
    Column column(String spec) {
        if (spec.equals("#0")) return treeColumn;
        for (Column c : columns) if (c.id.equals(spec)) return c;
        if (spec.startsWith("#")) {
            int n;
            try { n = Integer.parseInt(spec.substring(1)); }
            catch (NumberFormatException bad) { throw new IllegalArgumentException("Invalid column index " + spec); }
            List<Column> data = display != null ? display : columns;
            if (n < 0 || n > data.size()) throw new IllegalArgumentException("Column " + spec + " out of range");
            return data.get(n - 1);
        }
        try {
            int n = Integer.parseInt(spec);
            if (n < 0 || n >= columns.size()) throw new IllegalArgumentException("Column index " + spec + " out of bounds");
            return columns.get(n);
        } catch (NumberFormatException bad) { throw new IllegalArgumentException("Invalid column index " + spec); }
    }

    int treeWidth() { int w = 0; for (Column c : shown()) w += c.width; return w; }

    /** -columns: new data columns (those kept by id keep their settings); -displaycolumns returns to #all. */
    void columns(List<String> ids) {
        if (ids.size() > MAX_COLUMNS) throw new IllegalArgumentException("at most " + MAX_COLUMNS + " columns");
        List<Column> next = new ArrayList<>();
        for (String id : ids) {
            if (id.isEmpty() || id.length() > 256) throw new IllegalArgumentException("invalid column id");
            Column old = null;
            for (Column c : columns) if (c.id.equals(id)) old = c;
            for (Column c : next) if (c.id.equals(id)) throw new IllegalArgumentException("duplicate column " + id);
            next.add(old != null ? old : new Column(id));
        }
        columns.clear(); columns.addAll(next);
        display = null;
        tree.changed(true);
    }

    void displayColumns(List<String> specs) {
        if (specs.size() == 1 && specs.get(0).equals("#all")) { display = null; tree.changed(true); return; }
        List<Column> next = new ArrayList<>();
        for (String spec : specs) {
            Column c = column(spec);
            if (c == treeColumn) throw new IllegalArgumentException("Cannot display column #0");
            next.add(c);
        }
        display = next;
        tree.changed(true);
    }

    static String anchor(String value) {
        for (String a : ANCHORS) if (a.equals(value)) return a;
        throw new IllegalArgumentException("bad anchor \"" + value + "\": must be n, ne, e, se, s, sw, w, nw, or center");
    }

    void configureColumn(Column c, Map<String, Object> m) {
        for (String key : m.keySet())
            if (!Arrays.asList("width", "minwidth", "stretch", "anchor").contains(key)) throw new IllegalArgumentException(
                    key.equals("id") ? "Attempt to change read-only option" : "unknown option \"-" + key + "\"");
        int width = m.containsKey("width") ? TkOptions.integer(m.get("width"), 0, 8192) : c.width;
        int minWidth = m.containsKey("minwidth") ? TkOptions.integer(m.get("minwidth"), 0, 8192) : c.minWidth;
        String anchor = m.containsKey("anchor") ? anchor(TkOptions.text(m.get("anchor"))) : c.anchor;
        boolean stretch = m.containsKey("stretch") ? TkOptions.bool(m.get("stretch")) : c.stretch;
        c.minWidth = minWidth; c.width = Math.max(width, minWidth); c.anchor = anchor; c.stretch = stretch;
        tree.changed(true);
    }

    void configureHeading(Column c, Map<String, Object> m) {
        for (String key : m.keySet())
            if (!Arrays.asList("text", "image", "anchor", "command").contains(key)) throw new IllegalArgumentException("unknown option \"-" + key + "\"");
        String text = m.containsKey("text") ? TkOptions.text(m.get("text")) : c.heading;
        if (text.indexOf('\n') >= 0) throw new IllegalArgumentException("heading text must be one line");
        TkFontMetrics.checkText(text);
        String anchor = m.containsKey("anchor") ? anchor(TkOptions.text(m.get("anchor"))) : c.headingAnchor;
        String image = m.containsKey("image") ? TkOptions.text(m.get("image")) : c.headingImage;
        if (image.length() > 256) throw new IllegalArgumentException("image name too long");
        c.heading = text; c.headingAnchor = anchor; c.headingImage = image;
        if (m.containsKey("command")) c.command = TkOptions.bool(m.get("command"));
        tree.changed(false);
    }

    // ---- Tk's column width management (ttkTreeview.c) ------------------------------------------

    private int firstColumn() { return 0; }

    private static int stretch(Column c, int n) {
        int width = n + c.width;
        if (width < c.minWidth) { n = c.minWidth - c.width; c.width = c.minWidth; }
        else c.width = width;
        return n;
    }

    private int pickupSlack(int extra) {
        int next = slack + extra;
        if (next < 0 && 0 <= slack || next > 0 && 0 >= slack) { slack = 0; return next; }
        slack = next;
        return 0;
    }

    private int shoveLeft(List<Column> shown, int i, int n) {
        while (n != 0 && i >= firstColumn()) {
            Column c = shown.get(i);
            if (c.stretch) n -= stretch(c, n);
            --i;
        }
        return n;
    }

    private int shoveRight(List<Column> shown, int i, int n) {
        while (n != 0 && i < shown.size()) {
            Column c = shown.get(i);
            if (c.stretch) n -= stretch(c, n);
            ++i;
        }
        return n;
    }

    private int distribute(List<Column> shown, int n) {
        int w = treeWidth(), m = 0;
        for (Column c : shown) if (c.stretch) m++;
        if (m == 0) return n;
        int d = n / m, r = n % m;
        if (r < 0) { r += m; --d; }
        for (Column c : shown) if (c.stretch) n -= stretch(c, d + (Math.floorMod(++w, m) < r ? 1 : 0));
        return n;
    }

    /** Tk's ResizeColumns: the columns follow the widget's width (slack first, then evenly, then leftwards). */
    void resize(int width) {
        List<Column> shown = shown();
        int delta = width - (treeWidth() + slack);
        slack += shoveLeft(shown, shown.size() - 1, distribute(shown, pickupSlack(delta)));
    }

    /** Tk's DragColumn: a separator dragged by delta pixels; the columns right of it give or take the space. */
    void dragColumn(int i, int delta) {
        List<Column> shown = shown();
        Column c = shown.get(i);
        int dl = delta - shoveLeft(shown, i - 1, delta - stretch(c, delta));
        int dr = shoveRight(shown, i + 1, pickupSlack(-dl));
        slack += dr;
        tree.changed(false);
    }

    // ---- geometry and view ------------------------------------------------------------------------

    int headingHeight() { return showHeadings ? HEADING : 0; }
    int natural() { return lines * ROW + headingHeight(); }
    /** The rows' area width and height inside the border and padding. */
    int areaWidth() { return Math.max(0, bounds.width - insetWidth()); }
    int page() { return Math.max(1, (bounds.height - insetHeight() - headingHeight()) / ROW); }

    /** Lay the columns out for the current size and keep the view in range (Tk does this at each layout). */
    void fit() {
        if (bounds.empty()) return;
        resize(areaWidth());
        int n = rows().size();
        top = Math.max(0, Math.min(top, Math.max(0, n - page())));
        scrollX = Math.max(0, Math.min(scrollX, Math.max(0, treeWidth() - areaWidth())));
        notifyView(false); notifyView(true);
    }

    Object[] view(boolean x) {
        int n = x ? treeWidth() : rows().size(), first = x ? scrollX : top, visible = x ? areaWidth() : page();
        return n == 0 ? new Object[] {0.0, 1.0} : new Object[] {first / (double) n, Math.min(1.0, (first + visible) / (double) n)};
    }

    private void notifyView(boolean x) {
        Object[] view = view(x); int callback = x ? xscrollcommand : yscrollcommand;
        String key = callback + ":" + view[0] + ":" + view[1];
        if (!key.equals(x ? lastX : lastY)) { if (x) lastX = key; else lastY = key; tree.queue(this, callback, view); }
    }

    void moveto(boolean x, double fraction) {
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("finite fraction required");
        int at = (int) (Math.max(0, Math.min(1, fraction)) * (x ? treeWidth() : rows().size()));
        if (x) scrollX = at; else top = at;
        fit(); tree.changed(false);
    }

    void scroll(boolean x, int amount, boolean pages) {
        long next = (x ? scrollX : top) + (long) amount * (pages ? x ? areaWidth() : page() : 1);
        int at = (int) Math.max(0, Math.min(Integer.MAX_VALUE, next));
        if (x) scrollX = at; else top = at;
        fit(); tree.changed(false);
    }

    /** Tk's see: the item's ancestors open, and it scrolled into view. */
    void see(Item item) {
        for (Item i = item.parent; i != null && i != root; i = i.parent) i.open = true;
        rows = null;
        int at = rows().indexOf(item);
        if (at < 0) return;
        if (at < top) top = at;
        else if (at >= top + page()) top = at - page() + 1;
        fit(); tree.changed(false);
    }

    /** Left edge of each shown column in widget pixels (before scrolling), and one past the last. */
    private int[] edges(List<Column> shown) {
        int[] x = new int[shown.size() + 1];
        x[0] = insetLeft();
        for (int i = 0; i < shown.size(); i++) x[i + 1] = x[i] + shown.get(i).width;
        return x;
    }

    /** The row at y (widget pixels), or -1 (the headings, below the last row). */
    int rowAt(int y) {
        int inside = y - insetTop() - headingHeight();
        if (inside < 0 || y >= bounds.height - insetBottom()) return -1;
        int row = top + inside / ROW;
        return row < rows().size() ? row : -1;
    }

    /** The shown column at x (widget pixels), or -1. */
    int columnAt(int x) {
        List<Column> shown = shown(); int[] e = edges(shown);
        int at = x + scrollX;
        for (int i = 0; i < shown.size(); i++) if (at >= e[i] && at < e[i + 1]) return i;
        return -1;
    }

    /** The separator (right edge of shown column i) within HALO pixels of x in the headings, or -1. */
    int separatorAt(int x, int y) {
        if (!showHeadings || y < insetTop() || y >= insetTop() + HEADING) return -1;
        List<Column> shown = shown(); int[] e = edges(shown);
        for (int i = 0; i < shown.size(); i++) if (Math.abs(x + scrollX - e[i + 1]) <= HALO) return i;
        return -1;
    }

    /** Tk's identify: region, item (row), column, element, at widget pixels (x, y). */
    String identify(String component, int x, int y) {
        bounds(); fit();
        boolean heading = showHeadings && y >= insetTop() && y < insetTop() + HEADING;
        int column = columnAt(x), row = rowAt(y);
        switch (component) {
            case "region":
                if (separatorAt(x, y) >= 0) return "separator";
                if (heading) return column >= 0 ? "heading" : "nothing";
                if (row < 0 || column < 0) return "nothing";
                return showTree && column == 0 ? "tree" : "cell";
            case "item": case "row": return row < 0 ? "" : rows().get(row).id;
            case "column": return column < 0 ? "" : "#" + (showTree ? column : column + 1);
            case "element":
                if (heading) return column >= 0 ? "text" : "";
                if (row < 0 || column < 0) return "";
                if (showTree && column == 0) {
                    int left = edges(shown())[0] - scrollX + depth(row) * INDENT;
                    if (x < left) return "";
                    if (x < left + INDICATOR) return "Treeitem.indicator";
                }
                return "text";
            case "cell":
                return row < 0 || column < 0 ? "" : rows().get(row).id + " #" + (showTree ? column : column + 1);
            default: throw new IllegalArgumentException("bad component \"" + component + "\": must be region, item, column, row, or element");
        }
    }

    /** Tk's bbox: the item's row (or one of its cells) in widget pixels, or null when not in view. */
    Object[] bbox(Item item, Column column) {
        bounds(); fit();
        int row = rows().indexOf(item);
        if (row < top || row >= top + page() || bounds.empty()) return null;
        int y = insetTop() + headingHeight() + (row - top) * ROW;
        List<Column> shown = shown(); int[] e = edges(shown);
        if (column == null) return new Object[] {e[0] - scrollX, y, treeWidth(), ROW};
        int i = shown.indexOf(column);
        if (i < 0) return null;
        int x = e[i] - scrollX, w = shown.get(i).width;
        if (column == treeColumn) { int indent = depth(row) * INDENT; x += indent; w = Math.max(0, w - indent); }
        return new Object[] {x, y, w, ROW};
    }

    // ---- the player (ttk/treeview.tcl) --------------------------------------------------------------

    private void notice(Object... args) { tree.notice(this, notify, args); }

    /** The selection changed through a selection command: <<TreeviewSelect>>. */
    private void selected(String op, Item item) { select(op, java.util.Collections.singletonList(item)); notice("select"); }

    private void browseTo(Item item) { see(item); focus = item; selected("set", item); }

    /** SelectOp: choose (a click), toggle (Ctrl), extend (Shift), as -selectmode does it. */
    void selectOp(Item item, String op) {
        switch (selectmode) {
            case "none": focus = item; tree.changed(false); return;
            case "browse": browseTo(item); return;
            default:
                if (op.equals("toggle")) selected("toggle", item);
                else if (op.equals("extend") && focus != null && attached(focus)) {
                    List<Item> order = preorder();
                    int a = order.indexOf(focus), b = order.indexOf(item);
                    select("set", order.subList(Math.min(a, b), Math.max(a, b) + 1));
                    notice("select");
                } else browseTo(item);
        }
    }

    /** Toggle: open or close an item (OpenItem makes it the focus); the program hears <<TreeviewOpen>> or <<TreeviewClose>>. */
    void toggle(Item item) {
        if (item.open) { notice("close", item.id); item.open = false; }
        else { focus = item; notice("open", item.id); item.open = true; }
        changedTree();
        fit();
    }

    /** A press at widget pixels (x, y): a separator, a heading, a row (its indicator toggles). */
    void press(int x, int y, int modifiers, boolean twice) {
        bounds(); fit();
        int separator = separatorAt(x, y);
        if (separator >= 0) { dragging = separator; return; }
        String region = identify("region", x, y);
        if (region.equals("heading")) { pressedHeading = columnAt(x); tree.changed(false); return; }
        if (!region.equals("tree") && !region.equals("cell")) return;
        Item item = rows().get(rowAt(y));
        if (twice) { toggle(item); return; }   // <Double-1>: Toggle instead of the press binding
        selectOp(item, (modifiers & 1) != 0 ? "extend" : (modifiers & 2) != 0 ? "toggle" : "choose");
        if (identify("element", x, y).equals("Treeitem.indicator")) toggle(item);
    }

    void drag(int x, int y) {
        if (dragging >= 0 && dragging < shown().size()) {
            int right = edges(shown())[dragging + 1] - scrollX;
            if (x != right) dragColumn(dragging, x - right);
        }
    }

    /** The release: a heading pressed and released on itself runs its command. */
    void release(int x, int y) {
        int heading = pressedHeading;
        pressedHeading = -1; dragging = -1;
        tree.changed(false);
        if (heading < 0) return;
        boolean over = showHeadings && y >= insetTop() && y < insetTop() + HEADING && columnAt(x) == heading;
        List<Column> shown = shown();
        if (over && heading < shown.size() && shown.get(heading).command) notice("heading", "#" + (showTree ? heading : heading + 1));
    }

    void key(int code) {
        if (code == 201 || code == 209) { scroll(false, code == 201 ? -1 : 1, true); return; }
        if (code == 199 || code == 207) { moveto(true, code == 199 ? 0 : 1); return; }
        Item at = focus;
        if (at == null || !attached(at)) return;
        if (code == 28 || code == 156 || code == 57) { toggle(at); return; }
        Item next;
        switch (code) {
            case 200: {
                int i = at.parent.children.indexOf(at);
                if (i == 0) next = at.parent == root ? null : at.parent;
                else {
                    next = at.parent.children.get(i - 1);
                    while (next.open && !next.children.isEmpty()) next = next.children.get(next.children.size() - 1);
                }
                break;
            }
            case 208: {
                if (at.open && !at.children.isEmpty()) next = at.children.get(0);
                else {
                    Item up = at; next = null;
                    while (up != null && up != root) {
                        int i = up.parent.children.indexOf(up);
                        if (i + 1 < up.parent.children.size()) { next = up.parent.children.get(i + 1); break; }
                        up = up.parent;
                    }
                }
                break;
            }
            case 203:
                if (at.open && !at.children.isEmpty()) { toggle(at); next = at; }
                else next = at.parent == root ? null : at.parent;
                break;
            case 205:
                if (!at.open && !at.children.isEmpty()) { toggle(at); next = at; }
                else next = at.children.isEmpty() ? null : at.children.get(0);
                break;
            default: return;
        }
        if (next != null) selectOp(next, "choose");
    }

    // ---- paint ----------------------------------------------------------------------------------

    void paint(boolean focused, List<TkDisplayList.Item> out) {
        fit();
        int state = (focused ? TkDisplayList.FOCUSED : 0) | (!tree.available(this) ? TkDisplayList.DISABLED : 0);
        TkRect inner = inner();
        // The Treeview style's colours (TkLook); a row's in its own state (selected), the headings' from Heading.
        int look = tree.state(this) & ~TkLook.SELECTED, field = lookColor("fieldbackground", look, lookColor("background", look, background));
        add(out, 0, Kind.FRAME, bounds, bounds, "", 0, 0, field, foreground, border, state, lookRelief("relief", relief).code());
        List<Column> shown = shown(); int[] e = edges(shown);
        int x0 = bounds.x - scrollX, columnsShown = shown.size();
        if (showHeadings) {
            for (int i = 0; i < columnsShown; i++) {
                Column c = shown.get(i);
                TkRect box = new TkRect(x0 + e[i], inner.y, c.width, HEADING), clip = box.intersect(inner);
                if (clip.empty()) continue;
                String text = TkText.visible(c.heading, 0, Math.max(0, c.width - 4));
                int headingLook = look | (pressedHeading == i ? TkLook.PRESSED : 0);
                add(out, 1 + i, Kind.BUTTON, box, clip, text, anchorX(c.headingAnchor, box, TkFontMetrics.width(text)), box.y + 1,
                        lookColor("headingbackground", headingLook, HEADING_BACKGROUND), lookColor("headingforeground", headingLook, foreground), 1,
                        (state & ~TkDisplayList.FOCUSED) | (pressedHeading == i ? TkDisplayList.PRESSED : 0),
                        this.look == null ? TkRelief.RAISED : this.look.relief("headingrelief", headingLook, Relief.RAISED).code());
            }
        }
        List<Item> rows = rows();
        int y = inner.y + headingHeight();
        for (int r = top, slot = 0; r < rows.size() && y < inner.y + inner.height; r++, slot++, y += ROW) {
            Item item = rows.get(r);
            boolean chosen = selected.contains(item);
            int[] colors = colors(item);
            int rowLook = look | (chosen ? TkLook.SELECTED : 0);
            int bg = chosen ? lookColor("background", rowLook, SELECTED) : colors[1] >= 0 ? colors[1] : lookColor("background", rowLook, background);
            int fg = chosen ? lookColor("foreground", rowLook, 0xFFFFFF) : colors[0] >= 0 ? colors[0] : lookColor("foreground", rowLook, foreground);
            int base = 100 + slot * (columnsShown + 1);
            for (int i = 0; i < columnsShown; i++) {
                Column c = shown.get(i);
                TkRect cell = new TkRect(x0 + e[i], y, c.width, ROW), clip = cell.intersect(inner);
                if (clip.empty()) continue;
                int indent = c == treeColumn ? depth(r) * INDENT + INDICATOR : 0;
                String value = c == treeColumn ? item.text : value(item, c);
                int room = Math.max(0, c.width - indent - 4);
                String text = TkText.visible(value, 0, room);
                TkRect space = new TkRect(cell.x + indent, cell.y, Math.max(1, c.width - indent), ROW);
                add(out, base + i, Kind.LABEL, cell, clip, text, anchorX(c.anchor, space, TkFontMetrics.width(text)), y + 2, bg, fg, 0, state, TkRelief.FLAT);
            }
            if (showTree && !item.children.isEmpty()) {
                TkRect mark = new TkRect(x0 + e[0] + depth(r) * INDENT, y, INDICATOR, ROW), clip = mark.intersect(inner);
                if (!clip.empty()) add(out, base + columnsShown, Kind.LABEL, mark, clip, item.open ? "▾" : "▸", mark.x + 4, y + 2, bg, fg, 0, state, TkRelief.FLAT);
            }
        }
    }

    private static int anchorX(String anchor, TkRect box, int width) {
        int room = box.width - 4 - width;
        if (anchor.equals("e") || anchor.equals("ne") || anchor.equals("se")) return box.x + 2 + Math.max(0, room);
        if (anchor.equals("w") || anchor.equals("nw") || anchor.equals("sw")) return box.x + 2;
        return box.x + 2 + Math.max(0, room / 2);   // n, s, center
    }

    /** An item's tag colours {fg, bg}: the first of its tags that sets each. */
    private int[] colors(Item item) {
        int fg = -1, bg = -1;
        for (String tag : item.tags) {
            int[] c = tagColors.get(tag);
            if (c == null) continue;
            if (fg < 0) fg = c[0];
            if (bg < 0) bg = c[1];
        }
        return new int[] {fg, bg};
    }

    String value(Item item, Column c) {
        int i = columns.indexOf(c);
        return i >= 0 && i < item.values.size() ? item.values.get(i) : "";
    }

    private void add(List<TkDisplayList.Item> out, int part, Kind kind, TkRect rect, TkRect area, String text, int tx, int ty,
                     int bg, int fg, int border, int state, int relief) {
        TkRect r = rect.intersect(bounds), clip = r.intersect(clip()).intersect(area);
        if (!clip.empty()) out.add(new TkDisplayList.Item(id, part, kind, r, clip, bg, fg, border, state, tx, ty, text, -1, 0, 0, null, 0, relief));
    }
    private TkRect clip() { return clip; }

    /** At most this many paint records, on a display rows text rows tall (TkPaint.maxRecords). */
    int maxRecords(int displayRows) {
        int shown = shown().size();
        return 1 + shown + Math.min(items.size(), displayRows + 1) * (shown + 1);
    }
    long maxBytes(int displayRows, int rowBytes) {
        int shown = shown().size();
        long headings = 0;
        for (Column c : shown()) headings += c.heading.getBytes(StandardCharsets.UTF_8).length;
        return Math.min(headings, rowBytes + 4L * shown) + Math.min((long) bytes, (long) (displayRows + 1) * (rowBytes + 4L * (shown + 1)));
    }
}
