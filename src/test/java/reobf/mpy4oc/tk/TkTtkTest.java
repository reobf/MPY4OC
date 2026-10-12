package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

/** Server side of the ttk groundwork: takefocus, double clicks, state flags, Separator, Sizegrip, Scrollbar/Scale queries. */
class TkTtkTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(320, 240); return tree; }
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }
    private static void click(TkTree tree, TkWidget w) {
        TkRect b = w.bounds();
        tree.pointerDown(1, b.x + b.width / 2, b.y + b.height / 2);
        tree.pointerUp(1, b.x + b.width / 2, b.y + b.height / 2);
    }

    @Test void takefocusDecidesTabAndClicks() {
        TkTree tree = desktop();
        Button a = new Button(tree.root(), "a"); a.pack();
        Button skipped = new Button(tree.root(), "b"); skipped.pack();
        Label label = new Label(tree.root(), "label"); label.pack();
        Entry entry = new Entry(tree.root()); entry.pack();
        TkOptions.configure(skipped, options("takefocus", false));
        TkOptions.configure(label, options("takefocus", 1));
        TkOptions.configure(entry, options("takefocus", "0"));
        tree.focusNext(false); assertEquals(a.id, tree.focused());
        tree.focusNext(false); assertEquals(label.id, tree.focused(), "takefocus=0 is skipped, takefocus=1 joins Tab");
        tree.focusNext(false); assertEquals(a.id, tree.focused(), "and the Entry with takefocus=0 is skipped too");
        click(tree, skipped);
        assertEquals(a.id, tree.focused(), "a click does not focus a takefocus=0 button");
        click(tree, entry);
        assertEquals(entry.id, tree.focused(), "an Entry is focused by a click whatever its takefocus");
        tree.focus(label.id);
        assertEquals(label.id, tree.focused(), "focus_set on a takefocus=1 Label");
        Label plain = new Label(tree.root(), "plain"); plain.pack();
        assertThrows(IllegalArgumentException.class, () -> tree.focus(plain.id), "a plain Label still cannot take focus");
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(plain, options("takefocus", "maybe")));
        TkOptions.configure(skipped, options("takefocus", ""));
        assertEquals(-1, skipped.takefocus, "'' is the widget's default again");

        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        assertEquals(1, loaded.get(label.id).takefocus);
        assertEquals(0, loaded.get(entry.id).takefocus);
        assertEquals(-1, loaded.get(skipped.id).takefocus);
    }

    private static Frame window(TkTree tree, int x, int y) { return window(tree, x, y, 100); }
    private static Frame window(TkTree tree, int x, int y, int height) {
        Frame window = TkWindow.create(tree.root());
        TkWindow.wm(tree, window, "geometry", options("width", 120, "height", height, "x", x, "y", y));
        return window;
    }

    @Test void tabSkipsWindowsAndMovesOnFromAClickedWidget() {
        TkTree tree = desktop();
        Frame win = window(tree, 10, 10);
        TkOptions.configure(win, options("takefocus", 1));
        Button b1 = new Button(win, "b1"); b1.pack();
        Entry entry = new Entry(win); entry.pack();
        TkOptions.configure(entry, options("takefocus", 0));
        Button b2 = new Button(win, "b2"); b2.pack();
        tree.render();
        tree.focus(b1.id);
        tree.focusNext(false); assertEquals(b2.id, tree.focused(), "a takefocus=1 window is no Tab stop; the takefocus=0 Entry is skipped");
        tree.focusNext(false); assertEquals(b1.id, tree.focused(), "and Tab does not stick on the window");
        click(tree, entry); assertEquals(entry.id, tree.focused());
        tree.focusNext(false); assertEquals(b2.id, tree.focused(), "Tab moves on from a clicked takefocus=0 Entry (Tk's tk_focusNext)");
        click(tree, entry);
        tree.focusNext(true); assertEquals(b1.id, tree.focused(), "Shift-Tab goes back from it");
    }

    @Test void aClickThatTakesNoFocusStillMovesTheKeyboardToItsWindow() {
        TkTree tree = desktop();
        Frame a = window(tree, 5, 5), b = window(tree, 160, 5, 200);
        Entry entry = new Entry(a); entry.pack();
        Button button = new Button(b, "x"); button.pack();
        Scale scale = new Scale(b); scale.pack();
        Listbox list = new Listbox(b); list.pack();
        for (TkWidget w : new TkWidget[] {button, scale, list}) TkOptions.configure(w, options("takefocus", 0));
        tree.render();
        for (TkWidget w : new TkWidget[] {button, scale}) {
            tree.focus(entry.id);
            click(tree, w);
            assertNotEquals(entry.id, tree.focused(), w.kind + ": keys no longer go to the other window's Entry");
            assertEquals(0, tree.focused(), w.kind + ": its window has no focus to restore");
        }
        tree.focus(entry.id);
        click(tree, list);
        assertEquals(list.id, tree.focused(), "a Listbox takes the focus on a click whatever its takefocus (Tk's class binding)");
        Button same = new Button(a, "same"); same.pack();
        TkOptions.configure(same, options("takefocus", 0));
        tree.render();
        tree.focus(entry.id);
        click(tree, same);
        assertEquals(entry.id, tree.focused(), "in the same window the focus stays where it was");
    }

    @Test void aRightPressOrAKeyBetweenClicksIsNoDoubleClick() {
        TkTree tree = desktop();
        long[] now = {1000};
        tree.clock = () -> now[0];
        Button a = new Button(tree.root(), "a"); a.pack();
        tree.render();
        click(tree, a);
        now[0] = 1100; tree.rightPress(1, a.id); tree.rightRelease(1);
        now[0] = 1200; click(tree, a);
        assertFalse(tree.doubleClicked(1), "a right press in between");
        now[0] = 1300; click(tree, a);
        assertTrue(tree.doubleClicked(1), "the click after it was a new first click");
        now[0] = 2000; click(tree, a);
        tree.interruptClick(1);
        now[0] = 2100; click(tree, a);
        assertFalse(tree.doubleClicked(1), "a key in between");
        now[0] = 3000; click(tree, a);
        tree.interruptClick(2);
        now[0] = 3100; click(tree, a);
        assertTrue(tree.doubleClicked(1), "another session's key does not matter");
    }

    @Test void doubleClicksOnTheSameWidget() {
        TkTree tree = desktop();
        long[] now = {1000};
        tree.clock = () -> now[0];
        Button a = new Button(tree.root(), "a"); a.pack();
        Button b = new Button(tree.root(), "b"); b.pack();
        click(tree, a); assertFalse(tree.doubleClicked(1));
        now[0] = 1300; click(tree, a); assertTrue(tree.doubleClicked(1), "within 500 ms on the same widget");
        now[0] = 1400; click(tree, a); assertFalse(tree.doubleClicked(1), "a third click starts over");
        now[0] = 1500; click(tree, b); assertFalse(tree.doubleClicked(1), "another widget");
        now[0] = 2500; click(tree, b); assertFalse(tree.doubleClicked(1), "too slow");
        now[0] = 2600; tree.pointerDown(2, b.bounds().x + 2, b.bounds().y + 2);
        assertFalse(tree.doubleClicked(2), "another session's first click");
        assertFalse(tree.doubleClicked(1));
    }

    @Test void stateFlagsTheDisplayKnows() {
        TkTree tree = desktop();
        Checkbutton check = new Checkbutton(tree.root(), false); check.pack();
        Entry entry = new Entry(tree.root()); entry.pack();
        Button button = new Button(tree.root(), "b"); button.pack();
        tree.render();
        assertEquals(0, tree.flags(check));
        check.selected(true);
        assertEquals(4, tree.flags(check), "selected");
        TkOptions.configure(entry, options("state", "readonly"));
        assertEquals(2, tree.flags(entry), "readonly");
        TkOptions.configure(entry, options("state", "disabled"));
        assertEquals(1, tree.flags(entry), "disabled");
        tree.focus(button.id);
        TkRect r = button.bounds();
        tree.pointerDown(1, r.x + 2, r.y + 2);
        assertEquals(8 | 16, tree.flags(button), "focus and pressed");
        tree.pointerUp(1, r.x + 2, r.y + 2);
        Frame window = TkWindow.create(tree.root());
        tree.render();
        assertEquals(32, tree.flags(check) & 32, "a window is active: the desktop is in the background");
        assertEquals(0, tree.flags(window) & 32);
    }

    @Test void separatorsAreEtchedLines() {
        TkTree tree = desktop();
        Separator line = new Separator(tree.root());
        line.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.X, false, TkPack.Anchor.CENTER, 0, 0, 0, 0, 0, 0));
        tree.render();
        assertEquals(2, line.requestedWidth); assertEquals(2, line.requestedHeight);
        List<TkDisplayList.Item> parts = records(tree, line.id);
        assertEquals(1, parts.size());
        assertEquals(TkWidget.Kind.FRAME, parts.get(0).kind, "painted with an older record kind");
        assertEquals(TkDisplayList.PRESSED, parts.get(0).state & TkDisplayList.PRESSED, "a sunken bevel: dark above, light below");
        assertEquals(new TkRect(0, 0, 320, 2), parts.get(0).bounds);
        TkOptions.configure(line, options("orient", "vertical"));
        assertEquals("vertical", TkControlOptions.cget(line, "orient"));
        line.pack(new TkPack(TkPack.Side.LEFT, TkPack.Fill.Y, false, TkPack.Anchor.CENTER, 0, 0, 0, 0, 0, 0));
        tree.render();
        assertEquals(new TkRect(0, 0, 2, 240), records(tree, line.id).get(0).bounds);
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        assertFalse(((Separator) loaded.get(line.id)).horizontal);
        assertEquals(tree.render(), loaded.render());
    }

    @Test void aSizegripResizesItsWindow() {
        TkTree tree = desktop();
        Frame window = TkWindow.create(tree.root());
        TkWindow.wm(tree, window, "geometry", options("width", 100, "height", 60, "x", 20, "y", 20));
        Sizegrip grip = new Sizegrip(window);
        grip.pack(new TkPack(TkPack.Side.BOTTOM, TkPack.Fill.NONE, false, TkPack.Anchor.SE, 0, 0, 0, 0, 0, 0));
        tree.render();
        assertEquals(Sizegrip.SIZE, grip.requestedWidth);
        assertEquals(1 + Sizegrip.DOTS.length, records(tree, grip.id).size(), "a background and six dots");
        TkRect g = grip.bounds();
        assertEquals(new TkRect(20 + 100 - 15, 38 + 60 - 15, 15, 15), g);
        assertEquals(TkWindow.Zone.EDGE, tree.zoneAt(g.x + 7, g.y + 7), "the window manager's, like the window's corner");
        tree.pointerDown(1, g.x + 7, g.y + 7);
        tree.pointerDrag(1, g.x + 37, g.y + 27);
        assertTrue(tree.managing(1), "its release is not the program's");
        tree.pointerUp(1, g.x + 37, g.y + 27);
        assertEquals(new TkRect(20, 38, 130, 80), window.bounds());

        TkWindow.wm(tree, window, "resizable", options("width", false));
        tree.render(); g = grip.bounds();
        tree.pointerDown(1, g.x + 7, g.y + 7); tree.pointerDrag(1, g.x + 37, g.y + 27); tree.pointerUp(1, g.x + 37, g.y + 27);
        assertEquals(new TkRect(20, 38, 130, 100), window.bounds(), "only the resizable axis");
        TkWindow.wm(tree, window, "resizable", options("height", false));
        tree.render(); g = grip.bounds();
        assertEquals(TkWindow.Zone.NONE, tree.zoneAt(g.x + 7, g.y + 7), "a fixed-size window's grip is inert");

        Sizegrip onDesktop = new Sizegrip(tree.root()); onDesktop.pack();
        tree.render();
        assertEquals(0, onDesktop.edges(), "the desktop cannot be resized");
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        assertTrue(loaded.get(grip.id) instanceof Sizegrip);
    }

    @Test void scrollbarAndScaleQueries() {
        TkTree tree = desktop();
        Scrollbar bar = new Scrollbar(tree.root()); bar.pack();
        tree.render();
        bar.set(0.2, 0.4); tree.render();
        assertEquals("arrow1", bar.identify(5, 3));
        assertEquals("arrow2", bar.identify(5, 97));
        assertEquals("trough1", bar.identify(5, 20));
        assertEquals("slider", bar.identify(5, bar.thumbStart() + 2));
        assertEquals("trough2", bar.identify(5, 80));
        assertEquals("", bar.identify(-1, 5));
        // Tk 8.6 on a 16x100 classic scrollbar, whatever the view: (y - 16) / 67.
        double[] golden = {0, 0.134, 0.507, 0.761, 1.0};
        int[] ys = {10, 25, 50, 67, 90};
        for (int i = 0; i < ys.length; i++) assertEquals(golden[i], bar.fraction(5, ys[i]), 0.001, "y=" + ys[i]);
        assertEquals(0.1493, bar.delta(0, 10), 0.0001);
        bar.set(0, 1); tree.render();
        assertEquals(0.507, bar.fraction(5, 50), 0.001, "a full view does not change it");
        assertEquals(0.1493, bar.delta(0, 10), 0.0001);

        Scale scale = new Scale(tree.root());
        TkOptions.configure(scale, options("orient", "horizontal", "from", 0, "to", 1.0, "resolution", 0, "length", 116, "showvalue", false));
        scale.pack(); tree.render();
        assertEquals(0.5, scale.valueAt(8 + 50, 5), 1e-9);
        assertArrayEquals(new int[] {8 + 50, scale.bounds().height - 10}, scale.coords(0.5));
        assertEquals(0.0, scale.valueAt(-100, 5));
    }

    @Test void canvasItemsPreferTheirDoubleClickBindings() {
        Canvas c = new Canvas(new TkTree().root()); c.size(100, 100); c.pack(); c.tree.render();
        int item = (Integer) c.call("create", Map.of("type", "rectangle", "coords", new Object[] {0, 0, 50, 50}, "options", Map.of("fill", 1, "tags", new Object[] {"a"})));
        c.call("bind", Map.of("selector", "a", "sequence", "<Button-1>", "callback", 1));
        c.call("bind", Map.of("selector", "all", "sequence", "<Button-1>", "callback", 2));
        c.call("bind", Map.of("selector", "a", "sequence", "<Double-Button-1>", "callback", 3));
        List<Object[]> bound = c.callbacks(item, "<Button-1>", "<Double-Button-1>");
        assertEquals(2, bound.size());
        assertArrayEquals(new Object[] {2, "<Button-1>"}, bound.get(0), "'all' has no double binding: its single one runs");
        assertArrayEquals(new Object[] {3, "<Double-Button-1>"}, bound.get(1), "'a' runs its double binding instead");
    }

    @Test void newBindableSequences() {
        for (String s : new String[] {"<Up>", "<Down>", "<Prior>", "<Next>", "<Double-Button-1>"}) TkBindings.validate(s);
        assertThrows(IllegalArgumentException.class, () -> TkBindings.validate("<Escape>"), "the GUI keeps Escape to close itself");
    }
}
