package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

class TkWindowTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(320, 240); return tree; }

    private static Frame window(TkTree tree, int x, int y, int width, int height) {
        Frame w = TkWindow.create(tree.root());
        Map<String, Object> m = new HashMap<>();
        m.put("width", width); m.put("height", height); m.put("x", x); m.put("y", y);
        TkWindow.wm(tree, w, "geometry", m);
        return w;
    }

    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }

    private static TkDisplayList.Item part(TkTree tree, int id, int part) {
        return records(tree, id).stream().filter(i -> i.part == part).findFirst().orElse(null);
    }

    @Test void windowsStayAboveTheDesktopAndPaintTheirDecorationsFirst() {
        TkTree tree = desktop();
        Label before = new Label(tree.root(), "desktop"); before.pack();
        Frame w = window(tree, 20, 10, 100, 60);
        Label after = new Label(tree.root(), "later"); after.pack();
        assertEquals(Arrays.asList(before, after, w), tree.root().children(), "desktop children stay below windows");

        List<TkDisplayList.Item> parts = records(tree, w.id);
        // edge, title bar, minimize, maximize, close, client; then the taskbar strip and this window's button on it
        assertEquals(Arrays.asList(1, 2, 5, 6, 3, 0, 7, 4), parts.stream().map(i -> i.part).collect(Collectors.toList()));
        // geometry +X+Y is the window's top left including the title bar.
        assertEquals(new TkRect(20, 10 + TkWindow.TITLE, 100, 60), w.bounds());
        TkDisplayList.Item title = parts.get(1), close = parts.get(4);
        assertEquals(new TkRect(20, 10, 100 - 3 * TkWindow.BUTTON, TkWindow.TITLE), title.bounds);
        assertEquals("tk", title.text);
        assertEquals(TkWindow.ACTIVE, title.background, "the only window is active");
        assertEquals(new TkRect(120 - TkWindow.CLOSE, 10, TkWindow.CLOSE, TkWindow.TITLE), close.bounds);
        assertEquals("×", close.text);
        assertThrows(IllegalArgumentException.class, () -> w.pack(), "the window manager positions windows");
        assertThrows(IllegalArgumentException.class, () -> TkWindow.create(w), "only the root holds windows");
    }

    @Test void clicksRaiseWindowsAndTitleBarsDragThem() {
        TkTree tree = desktop();
        Frame bottom = window(tree, 10, 10, 120, 80), top = window(tree, 60, 40, 120, 80);
        Button inTop = new Button(top, "top"); inTop.pack();
        tree.render();
        // Where they overlap the top window wins; the bottom title bar is still reachable.
        assertSame(top, TkWindow.of(tree.hit(100, 70)));
        assertEquals(TkWindow.Zone.TITLE, tree.zoneAt(20, 15));
        assertEquals(TkWindow.ACTIVE, records(tree, top.id).get(1).background);
        assertEquals(TkWindow.INACTIVE, records(tree, bottom.id).get(1).background);

        tree.pointerDown(1, 20, 15);
        assertSame(bottom, tree.root().children().get(tree.root().children().size() - 1), "a click raises the window");
        assertEquals(TkWindow.ACTIVE, records(tree, bottom.id).get(1).background);
        assertNull(tree.capture(1), "a title bar drag captures no widget");
        assertNull(tree.pointerDrag(1, 50, 35));
        assertEquals(new TkRect(40, 30 + TkWindow.TITLE, 120, 80), bottom.bounds(), "moved by the drag delta");
        // Dragged far off the top left, the title bar stays reachable.
        tree.pointerDrag(1, -500, -500);
        assertEquals(TkWindow.MIN_VISIBLE - (120 - 3 * TkWindow.BUTTON), bottom.bounds().x, "24 px of the draggable title, left of the buttons, stay on screen");
        assertEquals(TkWindow.TITLE, bottom.bounds().y);
        assertNull(tree.pointerUp(1, -500, -500));
        tree.pointerDrag(1, 200, 200);
        assertEquals(TkWindow.TITLE, bottom.bounds().y, "the drag ended with the release");
    }

    @Test void theCloseBoxAsksTheApplicationOnlyWhenReleasedOverIt() {
        TkTree tree = desktop();
        Frame w = window(tree, 10, 10, 100, 50);
        tree.render();
        int cx = 110 - TkWindow.CLOSE / 2, cy = 10 + TkWindow.TITLE / 2;
        assertEquals(TkWindow.Zone.CLOSE, tree.zoneAt(cx, cy));
        tree.pointerDown(7, cx, cy);
        assertEquals(TkDisplayList.PRESSED, part(tree, w.id, TkWindow.PART_CLOSE).state & TkDisplayList.PRESSED);
        tree.pointerUp(7, 40, 40);
        assertTrue(tree.takeCloseRequests().isEmpty(), "released elsewhere: no close");
        assertEquals(0, part(tree, w.id, TkWindow.PART_CLOSE).state & TkDisplayList.PRESSED);
        tree.pointerDown(7, cx, cy);
        tree.pointerUp(7, cx, cy);
        assertEquals(Arrays.asList(w.id), tree.takeCloseRequests());
        assertTrue(tree.takeCloseRequests().isEmpty());
        assertNotNull(tree.get(w.id), "the program decides; the tree does not close the window itself");
    }

    @Test void focusAndTabStayWithinAWindowAndFollowClicks() {
        TkTree tree = desktop();
        Frame a = window(tree, 0, 0, 150, 100), b = window(tree, 160, 0, 150, 100);
        Entry a1 = new Entry(a); a1.pack(); Button a2 = new Button(a, "ok"); a2.pack();
        Entry b1 = new Entry(b); b1.pack();
        tree.focus(a1.id);
        tree.focusNext(false); assertEquals(a2.id, tree.focused());
        tree.focusNext(false); assertEquals(a1.id, tree.focused(), "Tab cycles within window a");
        tree.pointerDown(1, 200, 5); tree.pointerUp(1, 200, 5);
        assertEquals(0, tree.focused(), "window b has had no focus yet");
        tree.focusNext(false); assertEquals(b1.id, tree.focused(), "Tab with nothing focused starts in the active window");
        tree.pointerDown(1, 50, 5); tree.pointerUp(1, 50, 5);
        assertEquals(a1.id, tree.focused(), "clicking a's title bar restores its focus");
        assertSame(a, tree.activeWindow());
    }

    @Test void windowManagerCommands() {
        TkTree tree = desktop();
        Frame w = TkWindow.create(tree.root());
        Map<String, Object> none = new HashMap<>();
        Object[] natural = (Object[]) TkWindow.wm(tree, w, "geometry", none);
        assertEquals(200, natural[0], "an empty Toplevel is 200x200, as in Tk");
        Map<String, Object> m = new HashMap<>();
        m.put("width", 100); m.put("height", 40); m.put("x", 5); m.put("y", 7); m.put("right", true); m.put("bottom", true);
        Object[] g = (Object[]) TkWindow.wm(tree, w, "geometry", m);
        assertArrayEquals(new Object[] {100, 40, 320 - 100 - 5, 240 - TkWindow.TASKBAR - 40 - TkWindow.TITLE - 7}, g,
                "-X-Y from the right of the display and the bottom of the work area (above the taskbar)");
        Map<String, Object> title = new HashMap<>(); title.put("title", "设置");
        assertEquals("设置", TkWindow.wm(tree, w, "title", title));
        assertEquals("normal", TkWindow.wm(tree, w, "state", none));
        TkWindow.wm(tree, w, "withdraw", none);
        assertEquals("withdrawn", TkWindow.wm(tree, w, "state", none));
        assertTrue(w.bounds().empty());
        assertTrue(records(tree, w.id).isEmpty(), "no decorations while withdrawn");
        TkWindow.wm(tree, w, "deiconify", none);
        assertEquals(new TkRect(215, 240 - TkWindow.TASKBAR - 40 - 7, 100, 40), w.bounds(), "back where it was");
        Map<String, Object> far = new HashMap<>(); far.put("x", 5000); far.put("y", -100);
        Object[] clamped = (Object[]) TkWindow.wm(tree, w, "geometry", far);
        assertEquals(320 - TkWindow.MIN_VISIBLE, clamped[2]); assertEquals(0, clamped[3]);
    }

    @Test void stackingTitlesAndWithdrawnWindowsSurviveNbt() {
        TkTree tree = desktop();
        Frame a = window(tree, 0, 0, 100, 60), b = window(tree, 50, 30, 100, 60);
        Entry entry = new Entry(a); entry.pack();
        Map<String, Object> title = new HashMap<>(); title.put("title", "第一");
        TkWindow.wm(tree, a, "title", title);
        tree.focus(entry.id);
        tree.restack(a, true, null);
        Label desktop = new Label(tree.root(), "desk"); desktop.pack();
        Frame c = window(tree, 0, 100, 50, 50);
        TkWindow.wm(tree, c, "withdraw", new HashMap<>());

        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {});
        assertEquals(tree.render(), loaded.render(), "same picture, decorations included");
        assertEquals(tree.root().children().stream().map(w -> w.id).collect(Collectors.toList()),
                loaded.root().children().stream().map(w -> w.id).collect(Collectors.toList()));
        Frame la = (Frame) loaded.get(a.id), lc = (Frame) loaded.get(c.id);
        assertEquals("第一", la.windowTitle());
        assertEquals(entry.id, loaded.focused());
        assertTrue(lc.bounds().empty(), "still withdrawn");
        assertEquals("normal", TkWindow.wm(loaded, la, "state", new HashMap<>()));
        TkWindow.wm(loaded, lc, "deiconify", new HashMap<>());
        assertEquals(new TkRect(0, 100 + TkWindow.TITLE, 50, 50), lc.bounds());
        assertNotNull(loaded.get(b.id));
    }

    @Test void focusOnAWindowRaisesItAndRestoresItsFocus() {
        TkTree tree = desktop();
        Frame a = window(tree, 0, 0, 150, 100), b = window(tree, 160, 0, 150, 100);
        Entry entry = new Entry(a); entry.pack();
        tree.focus(entry.id);
        tree.focus(b.id);
        assertSame(b, tree.activeWindow(), "focus_set() on a Toplevel raises it");
        assertEquals(0, tree.focused(), "b has no widget that had focus");
        tree.focus(a.id);
        assertSame(a, tree.activeWindow());
        assertEquals(entry.id, tree.focused(), "a gets its last focused widget back");
    }

    @Test void narrowWindowsKeepADraggableTitleBar() {
        TkTree tree = desktop();
        Frame w = window(tree, 100, 50, 16, 16);
        tree.render();
        assertEquals(TkWindow.Zone.TITLE, tree.zoneAt(108, 59), "the decorations are wider than a tiny client");
        assertEquals(TkWindow.Zone.CLOSE, tree.zoneAt(100 + TkWindow.minBar(w) - 4, 59));
        assertEquals(TkWindow.Zone.EDGE, tree.zoneAt(130, 70), "beside the narrow client, under the bar");
        tree.pointerDown(1, 108, 59);
        tree.pointerDrag(1, 128, 79);
        assertEquals(new TkRect(120, 70 + TkWindow.TITLE, 16, 16), w.bounds());
    }

    @Test void aRejectedGeometryChangesNothing() {
        TkTree tree = desktop();
        Frame w = window(tree, 10, 10, 100, 50);
        Map<String, Object> bad = new HashMap<>();
        bad.put("width", 300); bad.put("x", 9000);
        assertThrows(IllegalArgumentException.class, () -> TkWindow.wm(tree, w, "geometry", bad));
        assertEquals(new TkRect(10, 10 + TkWindow.TITLE, 100, 50), w.bounds());
        assertEquals(new TkRect(10, 10 + TkWindow.TITLE, 100, 50), w.place == null ? null : new TkRect(w.place.x, w.place.y, w.place.width, w.place.height));
    }

    @Test void savedWindowsMustUseWindowManagerPlacements() {
        TkTree tree = desktop();
        Frame w = window(tree, 10, 10, 100, 50);
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTreePersistence.load(saved, 320, 240, () -> {});
        net.minecraft.nbt.NBTTagList nodes = saved.getTagList("nodes", 10);
        for (int i = 0; i < nodes.tagCount(); i++) {
            NBTTagCompound node = nodes.getCompoundTagAt(i);
            if (node.getInteger("id") == w.id) node.getCompoundTag("placeOptions").setDouble("relx", 5);
        }
        assertThrows(IllegalArgumentException.class, () -> TkTreePersistence.load(saved, 320, 240, () -> {}),
                "a crafted relative placement could hide the window beyond reach");
    }

    @Test void overBudgetTheTopWindowKeepsItsRecords() {
        List<TkDisplayList.Item> items = new ArrayList<>();
        TkRect r = new TkRect(0, 0, 10, 10);
        // The lower layer alone nearly fills the budget; with the top layer it does not fit.
        for (int i = 1; i <= TkDisplayList.MAX_RECORDS - 1000; i++) items.add(new TkDisplayList.Item(i, TkWidget.Kind.LABEL, r, r, 0, 0, 0, 0, 0, 0, ""));
        int top = items.size();
        for (int i = 100001; i <= 102000; i++) items.add(new TkDisplayList.Item(i, TkWidget.Kind.LABEL, r, r, 0, 0, 0, 0, 0, 0, ""));
        assertTrue(TkDisplayList.trimToBudget(items, Arrays.asList(top)));
        assertEquals(TkDisplayList.MAX_RECORDS, items.size());
        assertTrue(items.stream().filter(i -> i.id > 100000).count() == 2000, "the top layer is complete");
        assertEquals(1, items.get(0).id, "kept records stay in paint order");
        new TkDisplayList(items);
    }

    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static void click(TkTree tree, int x, int y) { tree.pointerDown(1, x, y); tree.pointerUp(1, x, y); }
    private static void drag(TkTree tree, int x, int y, int toX, int toY) { tree.pointerDown(1, x, y); tree.pointerDrag(1, toX, toY); tree.pointerUp(1, toX, toY); }

    @Test void edgesAndCornersResizeWithinTheSizeLimits() {
        TkTree tree = desktop();
        Frame w = window(tree, 50, 40, 100, 60);           // client (50, 58, 100, 60), edge box (49, 39, 102, 80)
        tree.render();
        assertEquals(TkWindow.Zone.EDGE, tree.zoneAt(152, 80), "the resize band reaches outside the edge");
        assertEquals(TkWindow.RIGHT, TkWindow.resizeEdges(w, 152, 80));
        assertEquals(TkWindow.RIGHT | TkWindow.BOTTOM, TkWindow.resizeEdges(w, 151, 119), "a corner resizes both ways");
        assertEquals(TkWindow.Zone.NONE, tree.zoneAt(155, 80), "beyond the band is the desktop");
        drag(tree, 152, 80, 172, 80);
        assertEquals(new TkRect(50, 58, 120, 60), w.bounds());
        assertNull(tree.capture(1));
        drag(tree, 171, 119, 181, 124);
        assertEquals(new TkRect(50, 58, 130, 65), w.bounds());
        drag(tree, 47, 80, 37, 80);
        assertEquals(new TkRect(40, 58, 140, 65), w.bounds(), "the left edge moves, the right one stays");
        drag(tree, 100, 37, 100, 27);
        assertEquals(new TkRect(40, 48, 140, 75), w.bounds(), "the top edge moves, the bottom one stays");
        drag(tree, 100, 27, 100, -200);
        assertEquals(TkWindow.TITLE, w.bounds().y, "the title bar stays on the display");
        assertArrayEquals(new Object[] {140, 105, 40, 0}, (Object[]) TkWindow.wm(tree, w, "geometry", options()), "the size is set now");

        TkWindow.wm(tree, w, "minsize", options("width", 80, "height", 50));
        TkWindow.wm(tree, w, "maxsize", options("width", 150, "height", 100));
        assertEquals(new TkRect(40, 18, 140, 100), w.bounds(), "maxsize applies at once");
        drag(tree, 181, 60, -500, 60);
        assertEquals(80, w.bounds().width, "minsize");
        drag(tree, 121, 60, 900, 60);
        assertEquals(150, w.bounds().width, "maxsize");
        assertArrayEquals(new Object[] {80, 50}, (Object[]) TkWindow.wm(tree, w, "minsize", options()));

        TkWindow.wm(tree, w, "resizable", options("width", false));
        assertArrayEquals(new Object[] {false, true}, (Object[]) TkWindow.wm(tree, w, "resizable", options()));
        tree.render();
        assertEquals(TkWindow.Zone.NONE, tree.zoneAt(192, 60), "a fixed width has no band beside the window");
        assertEquals(0, TkWindow.resizeEdges(w, 190, 60));
        drag(tree, 190, 60, 200, 70);
        assertEquals(new TkRect(50, 28, 150, 100), w.bounds(), "its edge moves the window instead");
        assertTrue(records(tree, w.id).stream().noneMatch(i -> i.part == TkWindow.PART_MAXIMIZE), "only a fully resizable window maximizes");
    }

    @Test void minimizedWindowsWaitOnTheTaskbar() {
        TkTree tree = desktop();
        assertFalse(tree.taskbarShown(), "no windows, no taskbar");
        assertEquals(new TkRect(0, 0, 320, 240), tree.root().bounds());
        Frame a = window(tree, 0, 0, 120, 80), b = window(tree, 150, 0, 120, 80);
        Entry entry = new Entry(a); entry.pack();
        tree.focus(entry.id);
        assertTrue(tree.taskbarShown());
        assertEquals(240 - TkWindow.TASKBAR, tree.workHeight());
        assertEquals(new TkRect(0, 0, 320, 240 - TkWindow.TASKBAR), tree.root().bounds(), "the desktop ends above the taskbar");
        click(tree, 160, 5);
        assertSame(b, tree.activeWindow());
        assertEquals(TkWindow.Zone.MINIMIZE, tree.zoneAt(220, 5));
        tree.pointerDown(1, 220, 5);
        assertEquals(TkDisplayList.PRESSED, part(tree, b.id, TkWindow.PART_MINIMIZE).state & TkDisplayList.PRESSED);
        assertTrue(tree.managing(1), "the press is the window manager's");
        tree.pointerUp(1, 220, 5);
        assertEquals("iconic", TkWindow.wm(tree, b, "state", options()));
        assertTrue(b.bounds().empty());
        assertSame(a, tree.activeWindow(), "the next window is active");
        assertEquals(entry.id, tree.focused(), "and has its focus back");

        TkDisplayList.Item taskA = part(tree, a.id, TkWindow.PART_TASK), taskB = part(tree, b.id, TkWindow.PART_TASK);
        assertEquals(new TkRect(2, 241 - TkWindow.TASKBAR, 120, TkWindow.TASKBAR - 2), taskA.bounds);
        assertEquals(new TkRect(124, 241 - TkWindow.TASKBAR, 120, TkWindow.TASKBAR - 2), taskB.bounds);
        assertEquals(TkWindow.ACTIVE, taskA.background);
        assertEquals(TkWindow.TASK_ICONIC, taskB.background);
        assertEquals(TkWindow.Zone.TASKBAR, tree.zoneAt(300, 235));
        assertNull(tree.hit(130, 230), "the taskbar is not the program's");

        tree.pointerDown(1, 130, 230); tree.pointerUp(1, 5, 5);
        assertEquals("iconic", TkWindow.wm(tree, b, "state", options()), "released elsewhere: nothing");
        click(tree, 130, 230);
        assertEquals(new TkRect(150, 18, 120, 80), b.bounds(), "restored where it was");
        assertSame(b, tree.activeWindow());
        click(tree, 130, 230);
        assertEquals("iconic", TkWindow.wm(tree, b, "state", options()), "the active window's button minimizes it");
        click(tree, 10, 230);
        assertNull(tree.activeWindow(), "both minimized");
        assertTrue(tree.taskbarShown(), "minimized windows keep the taskbar");
        click(tree, 10, 230);
        assertSame(a, tree.activeWindow());
        click(tree, 130, 230);
        click(tree, 10, 230);
        assertSame(a, tree.activeWindow(), "an inactive window's button raises it");

        TkWindow.wm(tree, a, "withdraw", options()); TkWindow.wm(tree, b, "withdraw", options());
        assertFalse(tree.taskbarShown(), "withdrawn windows are not on the taskbar");
        assertEquals(new TkRect(0, 0, 320, 240), tree.root().bounds());
        TkWindow.wm(tree, b, "iconify", options());
        assertTrue(tree.taskbarShown(), "iconify puts a withdrawn window on the taskbar");
    }

    @Test void theEdgesADragWouldResizeLightUpUnderThePointer() {
        TkTree tree = desktop();
        Frame w = window(tree, 50, 40, 100, 60);           // edge box (49, 39, 102, 80)
        java.util.function.Supplier<List<Integer>> lit = () -> records(tree, w.id).stream()
                .filter(i -> i.part >= TkWindow.PART_HINT && i.part < TkWindow.PART_HINT + 4).map(i -> i.part).collect(Collectors.toList());
        tree.pointerHover(100, 80);
        assertEquals(List.of(), lit.get(), "nothing is lit over the window's inside");
        tree.pointerHover(152, 80);
        TkDisplayList.Item right = part(tree, w.id, TkWindow.PART_HINT + 1);
        assertEquals(List.of(TkWindow.PART_HINT + 1), lit.get());
        assertEquals(new TkRect(150, 37, 3, 84), right.bounds, "the whole right side: its edge and the two pixels outside");
        assertEquals(TkWindow.HINT, right.background);
        tree.pointerHover(151, 119);
        assertEquals(new TkRect(150, 107, 3, 14), part(tree, w.id, TkWindow.PART_HINT + 1).bounds, "a corner lights the corner lengths");
        assertEquals(new TkRect(139, 118, 14, 3), part(tree, w.id, TkWindow.PART_HINT + 3).bounds);
        tree.pointerHover(100, 50);
        assertEquals(List.of(), lit.get(), "the title bar moves the window: not lit");
        // While a resize drag goes on, its edge stays lit wherever the pointer is.
        tree.pointerDown(1, 152, 80);
        tree.pointerDrag(1, 200, 150);
        assertEquals(148, w.bounds().width);
        assertEquals(List.of(TkWindow.PART_HINT + 1), lit.get());
        tree.pointerUp(1, 200, 150);
        assertEquals(List.of(), lit.get(), "released away from the edge");
        tree.pointerHover(200, 80);
        assertEquals(List.of(TkWindow.PART_HINT + 1), lit.get());
        tree.pointerLeft();
        assertEquals(List.of(), lit.get(), "the pointer left the display");
        TkWindow.wm(tree, w, "resizable", options("width", false, "height", false));
        tree.pointerHover(200, 80);
        assertEquals(List.of(), lit.get(), "a window that cannot be resized shows nothing");
    }

    @Test void maximizeFillsTheWorkAreaUntilRestored() {
        TkTree tree = desktop();
        long[] now = {1000};
        tree.clock = () -> now[0];
        Frame w = window(tree, 50, 40, 100, 60);
        tree.render();
        assertEquals(TkWindow.Zone.MAXIMIZE, tree.zoneAt(120, 45));
        assertEquals(TkWindow.MAXIMIZE_GLYPH, part(tree, w.id, TkWindow.PART_MAXIMIZE).text);
        click(tree, 120, 45);
        TkRect work = new TkRect(0, TkWindow.TITLE, 320, 240 - TkWindow.TASKBAR - TkWindow.TITLE);
        assertEquals(work, w.bounds());
        assertEquals("zoomed", TkWindow.wm(tree, w, "state", options()));
        assertEquals(TkWindow.RESTORE_GLYPH, part(tree, w.id, TkWindow.PART_MAXIMIZE).text);
        drag(tree, 60, 5, 120, 60);
        assertEquals(work, w.bounds(), "a maximized window does not move");
        assertEquals(0, TkWindow.resizeEdges(w, 320, 100), "nor resize");
        click(tree, 290, 5);
        assertEquals(new TkRect(50, 58, 100, 60), w.bounds(), "restored");

        now[0] = 2000; click(tree, 60, 45);
        now[0] = 2300; click(tree, 61, 46);
        assertEquals(work, w.bounds(), "a double click on the title bar maximizes");
        now[0] = 2400; click(tree, 61, 5);
        now[0] = 2600; click(tree, 62, 6);
        assertEquals(new TkRect(50, 58, 100, 60), w.bounds(), "and restores");
        now[0] = 5000; click(tree, 60, 45);
        now[0] = 5600; click(tree, 60, 45);
        assertEquals("normal", TkWindow.wm(tree, w, "state", options()), "too slow for a double click");

        TkWindow.wm(tree, w, "state", options("state", "zoomed"));
        tree.resize(400, 300);
        assertEquals(new TkRect(0, TkWindow.TITLE, 400, 300 - TkWindow.TASKBAR - TkWindow.TITLE), w.bounds(), "it follows the display");
        TkWindow.wm(tree, w, "iconify", options());
        click(tree, 10, 300 - 8);
        assertEquals("zoomed", TkWindow.wm(tree, w, "state", options()), "it comes back maximized");
        TkWindow.wm(tree, w, "geometry", options("x", 10, "y", 10));
        assertEquals(new TkRect(10, 10 + TkWindow.TITLE, 100, 60), w.bounds(), "a new geometry ends maximization");
        TkWindow.wm(tree, w, "state", options("state", "zoomed"));
        TkWindow.wm(tree, w, "resizable", options("width", false, "height", false));
        assertEquals("normal", TkWindow.wm(tree, w, "state", options()), "a window that cannot be resized is not left maximized");
        assertThrows(IllegalArgumentException.class, () -> TkWindow.wm(tree, w, "state", options("state", "big")));
    }

    @Test void configureReportsSizeChanges() {
        TkTree tree = desktop();
        Frame w = window(tree, 10, 10, 100, 60);
        Label inside = new Label(w, "x");
        inside.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.BOTH, true, TkPack.Anchor.CENTER, 0, 0, 0, 0, 0, 0));
        w.bindings.put("<Configure>", 7); w.configured = -1;
        inside.bindings.put("<Configure>", 8); inside.configured = -1;
        tree.changed(false);
        List<int[]> events = tree.takeConfigures();
        assertEquals(2, events.size(), "a new binding hears the current size");
        assertArrayEquals(new int[] {w.id, 10, 10 + TkWindow.TITLE, 100, 60}, events.get(0));
        assertArrayEquals(new int[] {inside.id, 0, 0, 100, 60}, events.get(1));
        assertTrue(tree.takeConfigures().isEmpty());
        drag(tree, 112, 50, 132, 50);
        events = tree.takeConfigures();
        assertEquals(2, events.size());
        assertArrayEquals(new int[] {w.id, 10, 10 + TkWindow.TITLE, 120, 60}, events.get(0), "a resize reports the new size");
        drag(tree, 30, 15, 60, 25);
        assertTrue(tree.takeConfigures().isEmpty(), "moving keeps the size");
        TkWindow.wm(tree, w, "withdraw", options());
        assertTrue(tree.takeConfigures().isEmpty());
        TkWindow.wm(tree, w, "deiconify", options());
        assertEquals(2, tree.takeConfigures().size(), "mapped again");
    }

    @Test void minimizedMaximizedAndLimitedWindowsSurviveNbt() {
        TkTree tree = desktop();
        Frame a = window(tree, 0, 0, 100, 60), b = window(tree, 50, 30, 100, 60), c = window(tree, 20, 100, 80, 40);
        TkWindow.wm(tree, a, "iconify", options());
        TkWindow.wm(tree, b, "state", options("state", "zoomed"));
        TkWindow.wm(tree, c, "resizable", options("height", false));
        TkWindow.wm(tree, c, "minsize", options("width", 60, "height", 30));
        TkWindow.wm(tree, c, "maxsize", options("width", 90, "height", 0));
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {});
        assertEquals(tree.render(), loaded.render(), "same picture, taskbar included");
        Frame la = (Frame) loaded.get(a.id), lb = (Frame) loaded.get(b.id), lc = (Frame) loaded.get(c.id);
        assertEquals("iconic", TkWindow.wm(loaded, la, "state", options()));
        assertEquals("zoomed", TkWindow.wm(loaded, lb, "state", options()));
        TkWindow.wm(loaded, lb, "state", options("state", "normal"));
        assertEquals(new TkRect(50, 30 + TkWindow.TITLE, 100, 60), lb.bounds(), "the size to restore was saved");
        assertArrayEquals(new Object[] {true, false}, (Object[]) TkWindow.wm(loaded, lc, "resizable", options()));
        assertArrayEquals(new Object[] {60, 30}, (Object[]) TkWindow.wm(loaded, lc, "minsize", options()));
        assertArrayEquals(new Object[] {90, 240}, (Object[]) TkWindow.wm(loaded, lc, "maxsize", options()));

        net.minecraft.nbt.NBTTagList nodes = saved.getTagList("nodes", 10);
        for (int i = 0; i < nodes.tagCount(); i++) {
            NBTTagCompound node = nodes.getCompoundTagAt(i);
            if (node.getInteger("id") == c.id) node.getCompoundTag("window").setIntArray("limits", new int[] {0, 30, 90, 0});
        }
        assertThrows(IllegalArgumentException.class, () -> TkTreePersistence.load(saved, 320, 240, () -> {}), "a minimum below 1");
        NBTTagCompound shown = TkTreePersistence.save(tree);
        nodes = shown.getTagList("nodes", 10);
        for (int i = 0; i < nodes.tagCount(); i++) {
            NBTTagCompound node = nodes.getCompoundTagAt(i);
            if (node.getInteger("id") == c.id) node.getCompoundTag("window").setBoolean("iconic", true);
        }
        assertThrows(IllegalArgumentException.class, () -> TkTreePersistence.load(shown, 320, 240, () -> {}), "a shown window cannot be minimized");
    }

    @Test void windowManagerPressesNeverReachTheProgram() {
        TkTree tree = desktop();
        long[] now = {1000};
        tree.clock = () -> now[0];
        Frame w = window(tree, 50, 40, 100, 60);
        Button inside = new Button(w, "ok"); inside.pack();
        tree.render();
        tree.pointerDown(1, 300, 235);
        assertTrue(tree.managing(1), "the bare taskbar");
        assertNull(tree.pointerUp(1, 300, 235));
        assertFalse(tree.managing(1));
        click(tree, 60, 45);
        now[0] = 1200; tree.pointerDown(1, 60, 45);
        assertEquals("zoomed", TkWindow.wm(tree, w, "state", options()));
        assertTrue(tree.managing(1), "the second press of a double click");
        tree.pointerUp(1, 60, 45);
        now[0] = 5000; tree.pointerDown(1, 60, 5);
        assertTrue(tree.managing(1), "a maximized window's title bar");
        assertNull(tree.capture(1));
        tree.pointerUp(1, 60, 5);
        TkRect b = inside.bounds();
        tree.pointerDown(1, b.x + 1, b.y + 1);
        assertFalse(tree.managing(1), "a widget press is the program's");
        tree.pointerUp(1, b.x + 1, b.y + 1);
    }

    @Test void resizingOneAxisKeepsTheOtherNatural() {
        TkTree tree = desktop();
        Frame w = TkWindow.create(tree.root());
        Label first = new Label(w, "one"); first.pack();
        TkWindow.wm(tree, w, "geometry", options("x", 40, "y", 20));
        tree.render();
        TkRect before = w.bounds();
        int right = before.x + TkWindow.decoratedWidth(w, before.width);
        // Halfway down the 38 px frame (title bar included): the right edge alone, clear of both corners.
        drag(tree, right + 1, before.y - 1, right + 41, before.y - 1);
        assertNull(w.place.height, "a width drag leaves the height natural");
        assertEquals(TkWindow.decoratedWidth(w, before.width) + 40, w.bounds().width);
        new Label(w, "two").pack();
        tree.render();
        assertEquals(before.height * 2, w.bounds().height, "the window still grows with its content");
    }

    @Test void aNarrowWindowResizesByItsVisibleFrame() {
        TkTree tree = desktop();
        Frame w = window(tree, 100, 30, 20, 40);           // client 20 wide; the frame is minBar (78) wide
        int bar = TkWindow.minBar(w), right = 100 + bar;      // the frame's right edge column is at 100 + bar
        drag(tree, right, 70, right + 30, 70);
        assertEquals(bar + 30, w.bounds().width, "the frame's edge follows the pointer");
        TkWindow.wm(tree, w, "geometry", options("width", 20));
        drag(tree, right, 70, right - 30, 70);
        assertEquals(20, w.bounds().width, "dragged inward the frame cannot shrink below its title bar; the client keeps its size");
        drag(tree, 99, 70, 69, 70);
        assertEquals(new TkRect(100 + bar - (bar + 30), 48, bar + 30, 40), w.bounds(), "the left edge moves, the frame's right end stays");
    }

    @Test void aFullTaskbarNeverLosesAWindow() {
        TkTree tree = new TkTree(); tree.resize(160, 240);
        List<Frame> windows = new ArrayList<>();
        for (int i = 0; i < TkWindow.MAX_WINDOWS; i++) windows.add(window(tree, 0, 0, 40, 20));
        List<Object[]> buttons = TkWindow.taskButtons(tree);
        assertTrue(buttons.size() < windows.size(), "not every window fits on a 160 px taskbar");
        assertEquals(TkWindow.TASK_MIN, ((TkRect) buttons.get(0)[1]).width, "buttons shrink to the minimum first");
        Frame first = windows.get(0), last = windows.get(windows.size() - 1);
        assertNotNull(part(tree, first.id, TkWindow.PART_MINIMIZE));
        assertNull(part(tree, last.id, TkWindow.PART_MINIMIZE), "no taskbar button, no minimize button");
        assertNull(part(tree, last.id, TkWindow.PART_TASK));
    }

    @Test void onlyRestorableWindowsMaximize() {
        TkTree tree = desktop();
        long[] now = {1000};
        tree.clock = () -> now[0];
        Frame w = window(tree, 50, 40, 150, 60);
        TkWindow.wm(tree, w, "resizable", options("width", false, "height", false));
        TkWindow.wm(tree, w, "state", options("state", "zoomed"));
        assertEquals("normal", TkWindow.wm(tree, w, "state", options()), "no maximize button, no way back: not maximized");
        click(tree, 60, 45);
        now[0] = 1200;
        drag(tree, 60, 45, 120, 100);
        assertEquals(new TkRect(110, 113, 150, 60), w.bounds(), "a quick second press still drags a window that cannot maximize");
    }

    @Test void deiconifyGivesTheWindowTheKeyboard() {
        TkTree tree = desktop();
        Frame a = window(tree, 0, 0, 150, 100), b = window(tree, 160, 0, 150, 100);
        Entry inA = new Entry(a); inA.pack();
        Entry inB = new Entry(b); inB.pack();
        tree.focus(inB.id);
        TkWindow.wm(tree, b, "withdraw", options());
        tree.focus(inA.id);
        TkWindow.wm(tree, b, "deiconify", options());
        assertSame(b, tree.activeWindow());
        assertEquals(inB.id, tree.focused(), "typing goes to the window drawn active");
        tree.focus(inA.id);
        TkWindow.wm(tree, b, "state", options("state", "normal"));
        assertEquals(inA.id, tree.focused(), "state('normal') on a shown window raises it without taking the keyboard");
    }

    @Test void minusOffsetsMeasureFromTheVisibleFrameAndTheWorkArea() {
        TkTree tree = desktop();
        Frame narrow = window(tree, 0, 0, 20, 20);
        Object[] g = (Object[]) TkWindow.wm(tree, narrow, "geometry", options("x", 0, "y", 0, "right", true, "bottom", true));
        assertEquals(320 - TkWindow.decoratedWidth(narrow, 20), g[2], "-0: the title bar's right end at the display's edge");
        assertEquals(TkWindow.Zone.CLOSE, tree.zoneAt(319, (Integer) g[3] + 5), "so its buttons stay on screen");
        TkTree alone = desktop();
        Frame w = window(alone, 10, 10, 100, 60);
        TkWindow.wm(alone, w, "withdraw", options());
        TkWindow.wm(alone, w, "geometry", options("x", 0, "y", 0, "right", true, "bottom", true));
        TkWindow.wm(alone, w, "deiconify", options());
        assertEquals(alone.workHeight(), w.bounds().y + w.bounds().height, "set while withdrawn, -0 still ends at the taskbar");
    }

    @Test void tinyFramesResizeOneEdgeAtATime() {
        TkTree tree = desktop();
        Frame w = window(tree, 50, 50, 100, 2);            // frame (49, 49, 102, 22)
        int edges = TkWindow.resizeEdges(w, 49, 59);
        assertTrue((edges & TkWindow.TOP) == 0 || (edges & TkWindow.BOTTOM) == 0, "never both top and bottom: " + edges);
        assertEquals(TkWindow.LEFT | TkWindow.TOP, TkWindow.resizeEdges(w, 49, 52), "the nearer one");
        assertEquals(TkWindow.LEFT | TkWindow.BOTTOM, TkWindow.resizeEdges(w, 49, 66));
    }

    @Test void sizeLimitsAndTitlesFollowTk() {
        TkTree tree = desktop();
        Frame w = window(tree, 10, 10, 100, 60);
        assertArrayEquals(new Object[] {1, 1}, (Object[]) TkWindow.wm(tree, w, "minsize", options("width", 0, "height", -5)), "no minimum");
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 150; i++) title.append('长');
        TkWindow.wm(tree, w, "title", options("title", title.toString()));
        String task = part(tree, w.id, TkWindow.PART_TASK).text, bar = part(tree, w.id, TkWindow.PART_TITLE).text;
        assertTrue(task.length() <= 120 / 16 + 1 && task.length() > 0, "the taskbar button carries what it shows: " + task.length());
        assertTrue(bar.length() <= 100 / 16 + 1, "and so does the title bar");
        assertEquals(title.toString(), TkWindow.wm(tree, w, "title", options()), "the full title is kept");
    }

    @Test void rightPressesRememberWhereTheyWent() {
        TkTree tree = desktop();
        tree.rightPress(1, -1);
        assertEquals(Integer.valueOf(-1), tree.rightRelease(1));
        assertNull(tree.rightRelease(1), "one release per press");
        tree.rightPress(1, 5);
        tree.release(1);
        assertNull(tree.rightRelease(1), "a cancelled session forgets it");
    }
}
