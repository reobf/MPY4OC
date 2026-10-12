package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ttk stage 4 on the server: a player's Treeview (ttk/treeview.tcl), its paint and its save, as Tk 8.6 does (tree3.py). */
class TkTreeviewTest {
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static List<String> notices(TkTree tree) {
        return tree.takeCommands().stream().map(c -> Arrays.stream(c.args).map(String::valueOf).collect(Collectors.joining(" "))).collect(Collectors.toList());
    }
    private static List<String> ids(List<Treeview.Item> items) { return items.stream().map(i -> i.id).collect(Collectors.toList()); }
    /** Four parents with two children each, as tree3.py builds them, eight rows high. */
    private static Treeview build(TkTree tree) {
        Treeview t = new Treeview(tree.root());
        t.pack();
        TkOptions.configure(t, options("height", 8, "notify", 5));
        for (int i = 0; i < 4; i++) {
            TkTreeviewApi.call(t, "insert", new Object[] {"", "end", options("iid", "p" + i, "text", "parent" + i)});
            for (int j = 0; j < 2; j++) TkTreeviewApi.call(t, "insert", new Object[] {"p" + i, "end", options("iid", "p" + i + "c" + j, "text", "child")});
        }
        return t;
    }
    private static void click(TkTree tree, Treeview t, int x, int y, int modifiers) {
        TkRect b = t.bounds();
        tree.pointerDown(1, b.x + x, b.y + y, modifiers);
        tree.pointerUp(1, b.x + x, b.y + y);
    }
    private static int row(int i) { return 20 + 20 * i + 10; }

    @Test void aPlayerSelectsAndOpensAsTk() {
        TkTree tree = new TkTree(); tree.resize(640, 480);
        Treeview t = build(tree);
        tree.clock = () -> 0;
        click(tree, t, 50, row(1), 0);
        assertEquals(Arrays.asList("p1"), ids(t.selection())); assertEquals("p1", t.focus.id);
        assertEquals(Arrays.asList("select"), notices(tree));
        tree.clock = () -> 1000;
        click(tree, t, 50, row(3), 2);
        assertEquals(Arrays.asList("p1", "p3"), ids(t.selection())); assertEquals("p1", t.focus.id, "Ctrl toggles, the focus stays");
        tree.clock = () -> 2000;
        click(tree, t, 50, row(0), 1);
        assertEquals(Arrays.asList("p0", "p0c0", "p0c1", "p1"), ids(t.selection()), "Shift: from the focus item, in tree order, closed children too");
        assertEquals("p1", t.focus.id);
        notices(tree);
        tree.clock = () -> 3000;
        click(tree, t, 5, row(0), 0);
        assertTrue(t.item("p0").open); assertEquals(Arrays.asList("p0"), ids(t.selection())); assertEquals("p0", t.focus.id);
        assertEquals(Arrays.asList("select", "open p0"), notices(tree), "the indicator selects, then opens");
        tree.clock = () -> 4000;
        click(tree, t, 5, row(0), 0);
        assertFalse(t.item("p0").open);
        assertEquals(Arrays.asList("select", "close p0"), notices(tree));
        tree.clock = () -> 5000;
        click(tree, t, 60, row(2), 0);
        tree.clock = () -> 5100;
        click(tree, t, 60, row(2), 0);
        assertTrue(t.item("p2").open, "a double click toggles");
        assertEquals(Arrays.asList("select", "open p2"), notices(tree));
        // Keynav from p0 (tree3.py's sequence).
        TkTreeviewApi.call(t, "selection_set", new Object[] {"p0"}); TkTreeviewApi.call(t, "focus", new Object[] {"p0"});
        notices(tree);
        String[][] keys = {{"208", "p1", "select"}, {"208", "p2", "select"}, {"205", "p2", "open p2|select"}, {"208", "p2c0", "select"},
                {"203", "p2", "select"}, {"203", "p2", "close p2|select"}, {"200", "p1", "select"}};
        t.item("p2").open = false;
        for (String[] k : keys) {
            tree.key(Integer.parseInt(k[0]), 0, 0);
            assertEquals(k[1], t.focus.id, "key " + k[0]);
            assertEquals(Arrays.asList(k[2].split("\\|")), notices(tree), "key " + k[0]);
        }
        tree.key(28, 0, 0); assertTrue(t.item("p1").open, "Return toggles");
        tree.key(57, 0, 0); assertFalse(t.item("p1").open, "space too");
        notices(tree);
        t.focus = null;
        tree.key(208, 0, 0);
        assertTrue(notices(tree).isEmpty(), "no focus item: Down does nothing");
        TkOptions.configure(t, options("selectmode", "browse"));
        tree.clock = () -> 9000;
        click(tree, t, 50, row(1), 2);
        assertEquals(Arrays.asList("p1"), ids(t.selection()));
        TkOptions.configure(t, options("selectmode", "none"));
        tree.clock = () -> 10000;
        click(tree, t, 50, row(2), 0);
        assertEquals(Arrays.asList("p1"), ids(t.selection())); assertEquals("p2", t.focus.id, "none: the focus moves only");
    }

    @Test void headingsAndSeparators() {
        TkTree tree = new TkTree(); tree.resize(640, 480);
        Treeview t = new Treeview(tree.root()); t.pack();
        TkOptions.configure(t, options("columns", new Object[] {"a"}, "notify", 6));
        t.configureHeading(t.column("a"), options("text", "A", "command", true));
        click(tree, t, 300, 5, 0);
        assertEquals(Arrays.asList("heading #1"), notices(tree));
        TkRect b = t.bounds();
        tree.pointerDown(1, b.x + 300, b.y + 5);
        assertTrue((tree.render().items.stream().filter(i -> i.id == t.id && i.part == 2).findFirst().get().state & TkDisplayList.PRESSED) != 0);
        tree.pointerUp(1, b.x + 100, b.y + 5);
        assertTrue(notices(tree).isEmpty(), "released over another heading: no command");
        tree.pointerDown(1, b.x + 201, b.y + 5);
        tree.pointerDrag(1, b.x + 150, b.y + 5);
        tree.pointerUp(1, b.x + 150, b.y + 5);
        assertEquals(149, t.treeColumn.width); assertEquals(251, t.column("a").width, "Tk's DragColumn: the right column takes the space");
    }

    @Test void paintsOnlyTheRowsInView() {
        TkTree tree = new TkTree(); tree.resize(640, 480);
        Treeview t = new Treeview(tree.root()); t.pack();
        TkOptions.configure(t, options("columns", new Object[] {"c1"}, "height", 5));
        for (int i = 0; i < 30; i++) TkTreeviewApi.call(t, "insert", new Object[] {"", "end", options("text", "row" + i, "values", new Object[] {"v" + i}, "tags", new Object[] {"hot"})});
        TkTreeviewApi.call(t, "tag_configure", new Object[] {"hot", options("foreground", 0xFF0000)});
        List<TkDisplayList.Item> items = tree.render().items.stream().filter(i -> i.id == t.id).collect(Collectors.toList());
        assertEquals(1 + 2 + 5 * 2, items.size(), "the frame, two headings, two cells for each of the five rows");
        assertTrue(items.size() <= t.maxRecords(30));
        assertEquals("row0", items.stream().filter(i -> i.part == 100).findFirst().get().text);
        assertEquals(0xFF0000, items.stream().filter(i -> i.part == 100).findFirst().get().foreground, "tag colours are drawn");
        TkTreeviewApi.call(t, "selection_set", new Object[] {"I001"});
        assertEquals(Treeview.SELECTED, tree.render().items.stream().filter(i -> i.id == t.id && i.part == 101).findFirst().get().background);
        assertTrue(tree.render().items.stream().allMatch(i -> i.kind != TkWidget.Kind.TREEVIEW), "older record kinds only");
    }

    @Test void anchorsPlaceTheText() {
        TkTree tree = new TkTree(); tree.resize(640, 480);
        Treeview t = new Treeview(tree.root()); t.pack();
        TkOptions.configure(t, options("columns", new Object[] {"a"}));
        t.configureHeading(t.treeColumn, options("text", "Name"));
        t.configureHeading(t.column("a"), options("text", "Size", "anchor", "e"));
        t.configureColumn(t.column("a"), options("anchor", "center"));
        TkTreeviewApi.call(t, "insert", new Object[] {"", "end", options("text", "x", "values", new Object[] {"ab"})});
        tree.focus(t.id);
        List<TkDisplayList.Item> items = tree.render().items.stream().filter(i -> i.id == t.id).collect(Collectors.toList());
        TkDisplayList.Item name = items.stream().filter(i -> i.part == 1).findFirst().get(), size = items.stream().filter(i -> i.part == 2).findFirst().get();
        assertEquals(name.bounds.x + 2 + (200 - 4 - 32) / 2, name.textX, "a heading is centred by default ('center' is not east)");
        assertEquals(size.bounds.x + 2 + 200 - 4 - 32, size.textX);
        assertEquals(0, name.state & TkDisplayList.FOCUSED, "headings draw no focus ring");
        TkDisplayList.Item cell = items.stream().filter(i -> i.part == 101).findFirst().get();
        assertEquals(cell.bounds.x + 2 + (200 - 4 - 16) / 2, cell.textX);
    }

    @Test void itSurvivesSaving() {
        TkTree tree = new TkTree(); tree.resize(640, 480);
        Treeview t = build(tree);
        TkOptions.configure(t, options("columns", new Object[] {"a", "b"}, "displaycolumns", new Object[] {"b"}, "selectmode", "browse", "show", new Object[] {"headings"}));
        t.configureHeading(t.column("a"), options("text", "A", "command", true));
        t.configureColumn(t.column("b"), options("width", 80, "stretch", false, "anchor", "e"));
        TkTreeviewApi.call(t, "item", new Object[] {"p1", options("values", new Object[] {"x", "y"}, "open", true, "tags", new Object[] {"t"})});
        TkTreeviewApi.call(t, "tag_configure", new Object[] {"t", options("background", 0x00FF00)});
        TkTreeviewApi.call(t, "detach", new Object[] {"p3"});
        TkTreeviewApi.call(t, "selection_set", new Object[] {"p1"});
        TkTreeviewApi.call(t, "focus", new Object[] {"p1c0"});
        String auto = (String) TkTreeviewApi.call(t, "insert", new Object[] {"", "end", options()});
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 640, 480, () -> {});
        Treeview u = (Treeview) loaded.get(t.id);
        assertEquals(ids(t.everything()), ids(u.everything()));
        assertEquals(Arrays.asList("p0", "p1", "p2", auto), ids(u.root.children), "p3 stays detached");
        assertTrue(u.items.containsKey("p3c1"));
        assertEquals(Arrays.asList("x", "y"), u.item("p1").values); assertTrue(u.item("p1").open); assertEquals(Arrays.asList("t"), u.item("p1").tags);
        assertEquals("A", u.column("a").heading); assertTrue(u.column("a").command);
        assertEquals(80, u.column("b").width); assertFalse(u.column("b").stretch); assertEquals("e", u.column("b").anchor);
        assertEquals(1, u.display.size()); assertEquals("browse", u.selectmode); assertFalse(u.showTree); assertTrue(u.showHeadings);
        assertEquals(Arrays.asList("p1"), ids(u.selection())); assertEquals("p1c0", u.focus.id);
        assertArrayEquals(new int[] {-1, 0x00FF00}, u.tagColors.get("t"));
        assertNotEquals(auto, TkTreeviewApi.call(u, "insert", new Object[] {"", "end", options()}), "new ids keep counting");
    }
}
