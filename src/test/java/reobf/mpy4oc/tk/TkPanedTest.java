package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ttk stage 7 on the server: ttk::panedwindow and the classic panedwindow; expected values are Tk 8.6.14's (paned1/2.py). */
class TkPanedTest {
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static TkTree display(int width, int height) { TkTree tree = new TkTree(); tree.resize(width, height); return tree; }
    private static Frame frame(Frame parent, int width, int height) {
        Frame f = new Frame(parent);
        TkOptions.configure(f, options("width", width, "height", height));
        return f;
    }
    private static Panedwindow paned(TkTree tree, boolean ttk, String orient) {
        Panedwindow p = new Panedwindow(tree.root(), ttk);
        TkOptions.configure(p, options("orient", orient));
        return p;
    }
    private static void fill(TkWidget w) { w.pack(TkOptions.pack(options("fill", "both", "expand", true), null)); }
    private static int x(TkWidget w) { return w.bounds().x; }
    private static int width(TkWidget w) { return w.bounds().width; }

    @Test void ttkPanesShareTheSpaceByWeight() {
        TkTree tree = display(300, 200);
        Panedwindow p = paned(tree, true, "horizontal");
        Frame a = frame(p, 80, 50), b = frame(p, 60, 40), c = frame(p, 30, 30);
        p.insert(0, a, options());
        p.insert(1, b, options("weight", 1));
        tree.render();
        assertArrayEquals(new int[] {145, 50}, new int[] {p.requestedWidth, p.requestedHeight}, "80 + 5 + 60; the tallest pane");
        fill(p);
        assertEquals(new TkRect(0, 0, 80, 200), a.bounds());
        assertEquals(new TkRect(85, 0, 215, 200), b.bounds(), "b's weight takes the extra 155");
        assertEquals(80, p.panes.get(0).sashPos);
        assertEquals(100, p.sashpos(0, 100));
        assertEquals(100, width(a)); assertEquals(105, x(b)); assertEquals(195, width(b));
        assertEquals(295, p.sashpos(0, 1000), "pushed against the end");
        assertEquals(0, p.sashpos(0, -5));
        p.sashpos(0, 80);
        tree.resize(400, 200);
        assertEquals(80, width(a)); assertEquals(315, width(b), "growing: the weighted pane");
        tree.resize(200, 200);
        assertEquals(80, width(a)); assertEquals(115, width(b));
        tree.resize(300, 200);
        p.insert(0, c, options());
        assertEquals(30, width(c)); assertEquals(35, x(a)); assertEquals(80, width(a)); assertEquals(120, x(b)); assertEquals(180, width(b));
        p.forget(1);
        assertTrue(a.bounds().empty()); assertEquals("", a.manager()); assertEquals("panedwindow", b.manager());
        assertEquals(30 + 2, p.sashAt(30 + 2, 10) + 32, "identify: the sash under (32, 10)");
        assertEquals(-1, p.sashAt(5, 10));
    }

    @Test void ttkWeightsDivideAsTkDoes() {
        TkTree tree = display(300, 200);
        Panedwindow p = paned(tree, true, "horizontal");
        Frame[] f = {frame(p, 40, 20), frame(p, 50, 20), frame(p, 60, 20)};
        p.insert(0, f[0], options("weight", 1));
        p.insert(1, f[1], options("weight", 2));
        p.insert(2, f[2], options("weight", 0));
        fill(p);
        assertArrayEquals(new int[] {0, 87, 92, 143, 240, 60}, new int[] {x(f[0]), width(f[0]), x(f[1]), width(f[1]), x(f[2]), width(f[2])});
        tree.resize(101, 200);
        assertArrayEquals(new int[] {0, 21, 26, 10, 41, 60}, new int[] {x(f[0]), width(f[0]), x(f[1]), width(f[1]), x(f[2]), width(f[2])},
                "shrinking: -59 shared by weight 1:2, the remainder first");
        tree.resize(300, 200);
        assertEquals(20, p.sashpos(1, 20));
        assertEquals(15, p.panes.get(0).sashPos, "the sash before is pushed");
        TkOptions.configure(p, options("sashthickness", 6));
        p.forget(2); p.forget(1); p.forget(0);
        p.insert(0, frame(p, 30, 10), options()); p.insert(1, frame(p, 30, 10), options());
        tree.render();
        assertEquals(66, p.requestedWidth, "clam's 6-pixel sash");
    }

    @Test void ttkVerticalAndSiblings() {
        TkTree tree = display(300, 200);
        Panedwindow p = paned(tree, true, "vertical");
        Frame a = frame(p, 50, 40), b = frame(p, 70, 30);
        p.insert(0, a, options()); p.insert(1, b, options());
        tree.render();
        assertArrayEquals(new int[] {70, 75}, new int[] {p.requestedWidth, p.requestedHeight});
        fill(p);
        assertEquals(new TkRect(0, 0, 300, 40), a.bounds());
        assertEquals(new TkRect(0, 45, 300, 155), b.bounds(), "no weights: the last pane takes the rest");
        assertEquals(0, p.sashAt(10, 42));
        Frame sibling = frame(tree.root(), 20, 20);
        p.insert(2, sibling, options());
        assertEquals("panedwindow", sibling.manager(), "Tk takes a sibling as a pane");
        assertThrows(IllegalArgumentException.class, () -> p.insert(3, TkWindow.create(tree.root()), options()));
        assertThrows(IllegalArgumentException.class, () -> p.configurePane(0, options("minsize", 3)), "ttk panes have -weight only");
    }

    /** Classic: c1 26x21 and c2 65x21 (Tk's labels 'left' and 'right side'). */
    @Test void classicPanesStretchLastByDefault() {
        TkTree tree = display(300, 200);
        Panedwindow p = paned(tree, false, "horizontal");
        Frame c1 = frame(p, 26, 21), c2 = frame(p, 65, 21);
        p.insert(0, c1, options());
        p.insert(1, c2, options("minsize", 40));
        tree.render();
        assertArrayEquals(new int[] {96, 23}, new int[] {p.requestedWidth, p.requestedHeight}, "1 + 26 + 3 + 65 + 1; 21 + 2");
        fill(p);
        assertEquals(new TkRect(1, 1, 26, 198), c1.bounds());
        assertEquals(new TkRect(30, 1, 269, 198), c2.bounds());
        assertArrayEquals(new int[] {27, 1}, p.sashCoord(0));
        assertEquals(0, p.sashAt(27, 1)); assertEquals(-1, p.sashAt(5, 5));
        p.sashPlace(0, 150, 1);
        assertArrayEquals(new int[] {150, 1}, p.sashCoord(0));
        assertEquals(149, width(c1)); assertEquals(153, x(c2)); assertEquals(146, width(c2));
        p.sashPlace(0, 290, 1);
        assertArrayEquals(new int[] {256, 1}, p.sashCoord(0), "c2's minsize 40");
        assertEquals(40, width(c2));
        assertEquals(255, p.paneOptions(0).get("width"), "moving a sash sets the panes' -width");
        tree.resize(400, 200);
        assertEquals(255, width(c1)); assertEquals(259, x(c2)); assertEquals(140, width(c2));
        p.configurePane(0, options("stretch", "always"));
        tree.resize(500, 200);
        assertEquals(427, width(c1), "stretchable panes share in proportion to their sizes");
        assertEquals(431, x(c2)); assertEquals(68, width(c2));
        assertThrows(IllegalArgumentException.class, () -> p.configurePane(0, options("sticky", "q")));
        assertThrows(IllegalArgumentException.class, () -> p.configurePane(0, options("stretch", "q")));
        assertThrows(IllegalArgumentException.class, () -> p.configurePane(0, options("weight", 1)));
        assertEquals("nesw", p.paneOptions(1).get("sticky"));
    }

    @Test void classicPaddingStickyHideAndBefore() {
        TkTree tree = display(500, 200);
        Panedwindow p = paned(tree, false, "horizontal");
        Frame c2 = frame(p, 65, 21), c3 = frame(p, 40, 21);
        p.insert(0, c2, options());
        fill(p);
        assertEquals(1, x(c2));
        assertFalse(p.validSash(0), "one pane: no sash");
        p.insert(0, c3, options("width", 50, "padx", 3, "pady", 2, "sticky", "n"));
        assertEquals(new TkRect(4, 3, 50, 21), c3.bounds());
        assertEquals(60, x(c2));
        p.configurePane(0, options("hide", true));
        assertTrue(c3.bounds().empty());
        assertEquals(1, x(c2));
        TkOptions.configure(p, options("showhandle", true, "sashrelief", "raised"));
        p.configurePane(0, options("hide", false));
        tree.render();
        int handle = p.sashAt(p.bounds().x + p.panes.get(0).handleX + 2, p.bounds().y + p.panes.get(0).handleY + 2);
        assertEquals(-2, handle, "on the handle of sash 0");
    }

    @Test void playersDragSashesAndPanesAreSaved() {
        TkTree tree = display(300, 200);
        Panedwindow p = paned(tree, true, "horizontal");
        Frame a = frame(p, 80, 50), b = frame(p, 60, 40);
        p.insert(0, a, options()); p.insert(1, b, options("weight", 1));
        fill(p);
        tree.pointerHover(82, 10);
        assertEquals(0, p.litSash, "the sash under the pointer is lit");
        assertTrue(tree.render().items.stream().anyMatch(i -> i.id == p.id && i.background == TkWindow.HINT));
        tree.pointerDown(1, 82, 10);
        tree.pointerDrag(1, 122, 30);
        assertEquals(120, width(a), "the sash follows the pointer");
        tree.pointerUp(1, 122, 30);
        tree.pointerHover(200, 10);
        assertEquals(-1, p.litSash);
        Panedwindow c = paned(tree, false, "vertical");
        Frame top = frame(c, 20, 30), bottom = frame(c, 20, 30);
        c.insert(0, top, options("minsize", 5)); c.insert(1, bottom, options("stretch", "always", "sticky", "ew"));
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 300, 200, () -> {});
        Panedwindow lp = (Panedwindow) loaded.get(p.id), lc = (Panedwindow) loaded.get(c.id);
        assertTrue(lp.ttk && lp.horizontal && !lc.ttk && !lc.horizontal);
        assertEquals(120, loaded.get(a.id).bounds().width);
        assertEquals(1, lp.panes.get(1).weight);
        assertEquals(5, lc.panes.get(0).minSize);
        assertEquals("always", lc.paneOptions(1).get("stretch"));
        assertEquals("ew", lc.paneOptions(1).get("sticky"));
        assertEquals("panedwindow", loaded.get(top.id).manager());
    }
}
