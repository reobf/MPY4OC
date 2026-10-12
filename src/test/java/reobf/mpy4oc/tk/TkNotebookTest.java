package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

/**
 * ttk::notebook on the server. The rules come from Tk 8.6's ttkNotebook.c and measurements of
 * Tk 8.6.14 (default theme); sizes use MPY4OC's 16 px text, so a tab is 23 px high where Tk's
 * 17 px font makes it 24, and everything below the tabs is 1 px higher than in Tk.
 */
class TkNotebookTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(400, 300); return tree; }
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }
    private static Frame page(Notebook book, int width, int height) { Frame f = new Frame(book); f.size(width, height); return f; }
    private static void natural(TkWidget w) { w.place(new TkPlace(0, 0, null, null, 0, 0, null, null, TkPack.Anchor.NW, "inside")); }
    /** A tab's box relative to its notebook, laid out as the notebook is now. */
    private static TkRect tabBox(Notebook book, int index) { book.bounds(); return relative(book.tabs.get(index).box, book); }
    private static TkRect relative(TkRect r, TkWidget to) { TkRect o = to.bounds(); return new TkRect(r.x - o.x, r.y - o.y, r.width, r.height); }

    @Test void geometryFollowsTk() {
        TkTree tree = desktop();
        Notebook book = new Notebook(tree.root());
        Frame one = page(book, 100, 60), two = page(book, 100, 60);
        book.add(one, options("text", "One")); book.add(two, options("text", "Two"));
        natural(book);
        TkRect b = book.bounds();
        assertArrayEquals(new int[] {102, 85}, new int[] {b.width, b.height}, "widest page + border; tab row + page + border");
        assertEquals(new TkRect(0, 0, 36, 23), tabBox(book, 0), "text + 12 wide, at least 24");
        assertEquals(new TkRect(36, 0, 36, 23), tabBox(book, 1));
        assertEquals(new TkRect(0, 23, 102, 62), relative(book.clientBox(), book));
        assertEquals(new TkRect(1, 24, 100, 60), relative(one.bounds(), book));
        assertFalse(two.mapped(), "only the selected page is mapped");
        assertEquals("notebook", two.manager());
        one.size(10, 10); two.size(10, 10);
        assertArrayEquals(new int[] {74, 35}, new int[] {book.bounds().width, book.bounds().height}, "as wide as the tabs");
        book.size(200, 100);
        assertArrayEquals(new int[] {202, 125}, new int[] {book.bounds().width, book.bounds().height}, "-width/-height size the page area");
        book.size(0, 0); one.size(100, 60); two.size(100, 60);
        // The notebook's padding is outside everything, a tab's padding inside the page border.
        book.padding(5, 5, 5, 5, 0);
        assertEquals(new TkRect(5, 5, 36, 23), tabBox(book, 0));
        assertEquals(new TkRect(6, 29, 100, 60), relative(one.bounds(), book));
        assertArrayEquals(new int[] {112, 95}, new int[] {book.bounds().width, book.bounds().height});
        book.padding(0, 0, 0, 0, 0);
        book.configureTab(0, options("padding", new Object[] {5, 5, 5, 5}));
        assertEquals(new TkRect(0, 0, 36, 23), tabBox(book, 0));
        assertEquals(new TkRect(6, 29, 100, 60), relative(one.bounds(), book));
        assertArrayEquals(new int[] {112, 95}, new int[] {book.bounds().width, book.bounds().height});
        // Tk: padding (1, 2, 3, 4), a tab padded (5, 6, 7, 8) with sticky '' and a 70x40 page.
        TkTree t2 = desktop();
        Notebook nb = new Notebook(t2.root());
        Frame small = page(nb, 70, 40);
        nb.add(small, options("padding", new Object[] {5, 6, 7, 8}, "sticky", ""));
        nb.padding(1, 2, 3, 4, 0); natural(nb);
        assertArrayEquals(new int[] {88, 85}, new int[] {nb.bounds().width, nb.bounds().height});
        assertEquals(new TkRect(7, 32, 70, 40), relative(small.bounds(), nb));
        nb.size(100, 50);
        assertArrayEquals(new int[] {106, 81}, new int[] {nb.bounds().width, nb.bounds().height});
        assertEquals(16, relative(small.bounds(), nb).x, "sticky '': centred across");
        // No tabs: just the border.
        TkTree t3 = desktop();
        Notebook empty = new Notebook(t3.root()); natural(empty);
        assertArrayEquals(new int[] {2, 2}, new int[] {empty.bounds().width, empty.bounds().height});
    }

    @Test void whichTabIsSelected() {
        TkTree tree = desktop();
        Notebook book = new Notebook(tree.root());
        Frame a = page(book, 20, 20), b = page(book, 20, 20), c = page(book, 20, 20);
        book.add(a, options("state", "disabled"));
        assertEquals(-1, book.current, "a disabled first tab is not selected");
        book.add(b, options()); book.add(c, options());
        assertEquals(1, book.current, "the first usable tab added is");
        assertFalse(book.select(0), "a disabled tab cannot be selected");
        book.configureTab(0, options("state", "normal"));
        book.hide(1);
        assertEquals(2, book.current, "hiding the current tab selects the next one after it");
        assertTrue(book.select(1)); assertEquals("normal", book.tabOptions(1)[1], "selecting a hidden tab shows it");
        book.select(2); book.forget(2);
        assertEquals(1, book.current, "none after: the nearest one before");
        assertEquals("", c.manager()); assertFalse(c.mapped());
        Frame d = page(book, 20, 20);
        book.insert(0, d, options());
        assertEquals(2, book.current, "a tab inserted before the current one moves it along");
        assertEquals(b, book.tabs.get(book.current).page);
        book.insert(-1, d, options());
        assertEquals(d, book.tabs.get(2).page, "end: an existing tab moves to the last place");
        assertEquals(b, book.tabs.get(book.current).page);
        book.configureTab(book.current, options("state", "disabled"));
        assertEquals(d, book.tabs.get(book.current).page, "disabling the current tab selects the next one");
        // Left/Right go round the end and pass disabled tabs.
        assertTrue(book.cycle(1)); assertEquals(a, book.tabs.get(book.current).page);
        assertTrue(book.cycle(-1)); assertEquals(d, book.tabs.get(book.current).page);
        // A page given to another manager, or destroyed, loses its tab.
        d.pack();
        assertEquals(-1, book.indexOf(d)); assertEquals("pack", d.manager()); assertEquals(a, book.tabs.get(book.current).page);
        a.destroy();
        assertEquals(-1, book.current, "only the disabled tab is left");
        assertEquals(1, book.tabs.size());
    }

    @Test void tkErrors() {
        TkTree tree = desktop();
        Notebook book = new Notebook(tree.root());
        Frame a = page(book, 20, 20), b = page(book, 20, 20), c = page(book, 20, 20);
        book.add(a, options()); book.add(b, options()); book.add(c, options());
        assertEquals("Slave index 5 out of bounds", assertThrows(IllegalArgumentException.class, () -> book.forget(5)).getMessage());
        assertEquals("Slave index 3 out of bounds", assertThrows(IllegalArgumentException.class, () -> book.insert(3, b, options())).getMessage());
        assertEquals("bad state \"bogus\": must be normal, disabled, or hidden",
                assertThrows(IllegalArgumentException.class, () -> book.configureTab(0, options("state", "bogus"))).getMessage());
        assertEquals("Bad -sticky specification q", assertThrows(IllegalArgumentException.class, () -> book.configureTab(0, options("sticky", "q"))).getMessage());
        assertEquals("unknown option \"-bogus\"", assertThrows(IllegalArgumentException.class, () -> book.configureTab(0, options("bogus", 1))).getMessage());
        assertEquals("nsew", book.tabOptions(0)[2]);
        book.configureTab(0, options("sticky", "we"));
        assertEquals("ew", book.tabOptions(0)[2], "given back in n, s, e, w order");
        Label outside = new Label(tree.root(), "x");
        assertThrows(IllegalArgumentException.class, () -> book.add(outside, options()));
        assertEquals("", outside.manager());
    }

    @Test void paintingClicksAndKeys() {
        TkTree tree = desktop();
        Notebook book = new Notebook(tree.root());
        TkOptions.configure(book, options("command", 7));
        Frame a = page(book, 100, 40), b = page(book, 100, 40), c = page(book, 100, 40);
        Button inside = new Button(b, "in"); inside.pack();
        book.add(a, options("text", "A")); book.add(b, options("text", "B")); book.add(c, options("text", "C", "state", "disabled"));
        natural(book); tree.takeCommands();
        List<TkDisplayList.Item> items = records(tree, book.id);
        assertEquals(TkRelief.FLAT, items.get(0).relief);
        assertEquals(TkRelief.RAISED, items.get(1).relief); assertEquals(1, items.get(1).border);
        TkDisplayList.Item first = items.get(2), second = items.get(3), third = items.get(4);
        assertEquals(TkRelief.TAB, first.relief); assertEquals(0xD9D9D9, first.background, "the selected tab has the notebook's colour");
        assertEquals(24, first.bounds.height, "and reaches over the page border");
        assertEquals(Notebook.UNSELECTED, second.background); assertEquals(23, second.bounds.height);
        assertEquals(TkDisplayList.DISABLED, third.state & TkDisplayList.DISABLED);
        assertEquals("B", second.text);
        // A click on a tab selects it, and the keyboard goes to the first widget of its page.
        TkRect tab = book.tabs.get(1).box;
        tree.pointerDown(1, tab.x + 5, tab.y + 5); tree.pointerUp(1, tab.x + 5, tab.y + 5);
        assertEquals(1, book.current); assertEquals(inside.id, tree.focused());
        List<TkTree.Command> commands = tree.takeCommands();
        assertEquals(1, commands.size()); assertEquals(7, commands.get(0).callback); assertEquals("tab", commands.get(0).args[0]);
        // The current tab again: the notebook takes the keyboard. A disabled tab: nothing changes.
        tree.pointerDown(1, tab.x + 5, tab.y + 5); tree.pointerUp(1, tab.x + 5, tab.y + 5);
        assertEquals(book.id, tree.focused()); assertTrue(tree.takeCommands().isEmpty());
        TkRect disabled = book.tabs.get(2).box;
        tree.pointerDown(1, disabled.x + 5, disabled.y + 5); tree.pointerUp(1, disabled.x + 5, disabled.y + 5);
        assertEquals(1, book.current);
        assertEquals(TkDisplayList.FOCUSED, records(tree, book.id).get(3).state & TkDisplayList.FOCUSED, "the focus ring on the selected tab");
        // Left/Right with the notebook focused; the keyboard stays with it.
        tree.key(205, 0);
        assertEquals(0, book.current, "the disabled tab is passed, round the end");
        assertEquals(book.id, tree.focused());
        assertEquals(1, tree.takeCommands().size());
        // identify: the tab's edge, padding, focus ring, label.
        TkRect t = book.tabs.get(0).box; int x = t.x - book.bounds().x, y = t.y - book.bounds().y;
        assertArrayEquals(new Object[] {"tab", 0}, book.identify(t.x, t.y));
        assertArrayEquals(new Object[] {"padding", 0}, book.identify(t.x + 1, t.y + 1));
        assertArrayEquals(new Object[] {"focus", 0}, book.identify(t.x + 5, t.y + 5));
        assertArrayEquals(new Object[] {"label", 0}, book.identify(t.x + 12, t.y + 8));
        assertArrayEquals(new Object[] {"", -1}, book.identify(t.x + 5, t.y + 40));
        assertEquals(0, x + y, "the first tab starts at the notebook's corner");
    }

    @Test void notebooksAreSaved() {
        TkTree tree = desktop();
        Notebook book = new Notebook(tree.root());
        TkOptions.configure(book, options("command", 9, "padding", new Object[] {1, 2, 3, 4}));
        Frame a = page(book, 50, 30), b = page(book, 60, 20);
        book.add(a, options("text", "First", "padding", new Object[] {1, 1, 1, 1}, "underline", 0));
        book.add(b, options("text", "Second", "sticky", "n", "state", "normal"));
        book.select(1); book.pack();
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 400, 300, () -> {});
        assertEquals(tree.render(), loaded.render());
        Notebook copy = (Notebook) loaded.widgets.get(book.id);
        assertEquals(1, copy.current); assertEquals(9, copy.command); assertEquals(2, copy.tabs.size());
        assertArrayEquals(book.tabOptions(0), copy.tabOptions(0)); assertArrayEquals(book.tabOptions(1), copy.tabOptions(1));
        assertEquals("notebook", copy.tabs.get(0).page.manager());
    }
}
