package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The "tree" operation: a Treeview's commands from Python, with Tk 8.6's messages. */
final class TkTreeviewApi {
    private TkTreeviewApi() {}

    private static void count(Object[] a, int min, int max) {
        if (a.length < min || a.length > max) throw new IllegalArgumentException("wrong argument count");
    }
    private static String text(Object o) { return TkOptions.text(o); }
    private static List<Treeview.Item> items(Treeview t, Object[] a, int from) {
        List<Treeview.Item> out = new ArrayList<>();
        for (int i = from; i < a.length; i++) out.add(t.item(text(a[i])));
        return out;
    }
    private static Object[] ids(List<Treeview.Item> items) {
        Object[] out = new Object[items.size()];
        for (int i = 0; i < out.length; i++) out[i] = items.get(i).id;
        return out;
    }
    private static String parentId(Treeview t, Treeview.Item item) {
        return item.parent == null || item.parent == t.root ? "" : item.parent.id;
    }

    static Object call(Treeview t, String action, Object[] a) {
        t.alive();
        switch (action) {
            case "insert": {
                count(a, 3, 3);
                Treeview.Item parent = t.item(text(a[0]));
                Map<String, Object> m = TkOptions.map(a[2]);
                String id = m.containsKey("iid") ? text(m.remove("iid")) : null;
                int index = a[1] instanceof Number ? TkOptions.integer(a[1], Integer.MIN_VALUE, Integer.MAX_VALUE)
                        : text(a[1]).equals("end") ? Integer.MAX_VALUE : parse(text(a[1]));
                return t.insert(parent, index, id, m);
            }
            case "item": {
                count(a, 1, 2);
                Treeview.Item item = t.item(text(a[0]));
                if (a.length == 2) {
                    Map<String, Object> m = TkOptions.map(a[1]);
                    setItem(t, item, m);
                    return null;
                }
                return new Object[] {item.text, item.image, item.values.toArray(), item.open, item.tags.toArray()};
            }
            case "delete": return t.delete(items(t, a, 0));
            case "detach": t.detach(items(t, a, 0)); return null;
            case "move": count(a, 3, 3); t.move(t.item(text(a[0])), t.item(text(a[1])), a[2]); return null;
            case "children": count(a, 1, 1); return ids(t.item(text(a[0])).children);
            case "set_children": {
                count(a, 1, Integer.MAX_VALUE);
                t.children(t.item(text(a[0])), items(t, a, 1));
                return null;
            }
            case "parent": count(a, 1, 1); return parentId(t, t.item(text(a[0])));
            case "index": {
                count(a, 1, 1);
                Treeview.Item item = t.item(text(a[0]));
                return item.parent == null ? 0 : item.parent.children.indexOf(item);
            }
            case "next": case "prev": {
                count(a, 1, 1);
                Treeview.Item item = t.item(text(a[0]));
                if (item.parent == null) return "";
                int i = item.parent.children.indexOf(item) + (action.equals("next") ? 1 : -1);
                return i < 0 || i >= item.parent.children.size() ? "" : item.parent.children.get(i).id;
            }
            case "exists": count(a, 1, 1); return text(a[0]).isEmpty() || t.items.containsKey(text(a[0]));
            case "focus":
                count(a, 0, 1);
                if (a.length == 0) return t.focus == null || !t.items.containsKey(t.focus.id) ? "" : t.focus.id;
                Treeview.Item focus = t.item(text(a[0]));
                t.focus = focus == t.root ? null : focus;
                t.tree.changed(false);
                return null;
            case "selection": count(a, 0, 0); return ids(t.selection());
            case "selection_set": case "selection_add": case "selection_remove": case "selection_toggle":
                t.select(action.substring("selection_".length()), items(t, a, 0));
                return null;
            case "column": case "heading": {
                count(a, 1, 2);
                Treeview.Column c = t.column(text(a[0]));
                if (a.length == 2) {
                    if (action.equals("column")) t.configureColumn(c, TkOptions.map(a[1])); else t.configureHeading(c, TkOptions.map(a[1]));
                    return null;
                }
                t.tree.render(); t.fit();
                return action.equals("column") ? new Object[] {c.width, c.minWidth, c.stretch, c.anchor, c.id}
                        : new Object[] {c.heading, c.headingImage, c.headingAnchor, c.command};
            }
            case "set": {
                count(a, 1, 3);
                Treeview.Item item = t.item(text(a[0]));
                if (a.length == 1) {
                    List<Object> pairs = new ArrayList<>();
                    for (int i = 0; i < t.columns.size() && i < item.values.size(); i++) { pairs.add(t.columns.get(i).id); pairs.add(item.values.get(i)); }
                    return pairs.toArray();
                }
                Treeview.Column c = t.column(text(a[1]));
                if (c == t.treeColumn) throw new IllegalArgumentException("Display column #0 cannot be set");
                if (a.length == 2) return t.value(item, c);
                List<String> values = new ArrayList<>(item.values);
                int at = t.columns.indexOf(c);
                while (values.size() <= at) values.add("");
                values.set(at, text(a[2]));
                java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                m.put("values", values.toArray());
                setItem(t, item, m);
                return null;
            }
            case "see": count(a, 1, 1); t.tree.render(); t.see(t.item(text(a[0]))); return null;
            case "bbox": {
                count(a, 1, 2);
                Treeview.Item item = t.item(text(a[0]));
                t.tree.render();
                return t.bbox(item, a.length == 2 ? t.column(text(a[1])) : null);
            }
            case "identify":
                count(a, 3, 3); t.tree.render();
                return t.identify(text(a[0]), TkControlApi.pixel(a[1]), TkControlApi.pixel(a[2]));
            case "xview": case "yview": {
                boolean x = action.equals("xview");
                t.tree.render();
                if (a.length == 0) { t.fit(); return t.view(x); }
                String op = text(a[0]);
                if (op.equals("moveto")) { count(a, 2, 2); t.moveto(x, TkControlApi.number(a[1])); return null; }
                if (op.equals("scroll")) {
                    count(a, 3, 3);
                    String what = text(a[2]);
                    if (!what.equals("units") && !what.equals("pages")) throw new IllegalArgumentException("bad argument \"" + what + "\": must be units or pages");
                    t.scroll(x, TkOptions.integer(a[1], Integer.MIN_VALUE, Integer.MAX_VALUE), what.equals("pages"));
                    return null;
                }
                // A bare number: the first row (Tk's yview index) or pixel column.
                if (x) t.scrollX = TkOptions.integer(a[0], 0, Integer.MAX_VALUE); else t.top = TkOptions.integer(a[0], 0, Integer.MAX_VALUE);
                t.fit(); t.tree.changed(false);
                return null;
            }
            case "tag_configure": {
                count(a, 1, 2);
                String tag = text(a[0]);
                int[] colors = t.tagColors.get(tag);
                if (a.length == 2) {
                    Map<String, Object> m = TkOptions.map(a[1]);
                    int[] next = colors == null ? new int[] {-1, -1} : colors.clone();
                    if (m.containsKey("foreground")) next[0] = TkOptions.integer(m.get("foreground"), -1, 0xFFFFFF);
                    if (m.containsKey("background")) next[1] = TkOptions.integer(m.get("background"), -1, 0xFFFFFF);
                    if (t.tagColors.size() >= 4096 && colors == null) throw new IllegalArgumentException("too many tags");
                    t.tagColors.put(tag, next);
                    t.tree.changed(false);
                    return null;
                }
                return colors == null ? new Object[] {-1, -1} : new Object[] {colors[0], colors[1]};
            }
            case "tag_has": {
                count(a, 1, 2);
                String tag = text(a[0]);
                if (a.length == 2) return t.item(text(a[1])).tags.contains(tag);
                List<Object> out = new ArrayList<>();
                List<Treeview.Item> order = t.everything();
                for (Treeview.Item item : order) if (item.tags.contains(tag)) out.add(item.id);
                return out.toArray();
            }
            default: throw new IllegalArgumentException("unsupported Treeview operation: " + action);
        }
    }

    private static int parse(String text) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException bad) { throw new IllegalArgumentException("expected integer but got \"" + text + "\""); }
    }

    private static void setItem(Treeview t, Treeview.Item item, Map<String, Object> m) {
        if (item == t.root) throw new IllegalArgumentException("Cannot configure root item");

        t.setOptions(item, m);
        t.tree.changed(false);
    }
}
