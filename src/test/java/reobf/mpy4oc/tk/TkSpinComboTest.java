package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ttk stage 3 on the server: Entry -show/-justify, Spinbox (classic and ttk), the Combobox and
 * its list, and player edits held for validation. Expected values are Tk 8.6's, measured.
 */
class TkSpinComboTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(320, 240); return tree; }
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static Spinbox spinbox(TkTree tree, boolean ttk, Object... pairs) {
        Spinbox s = new Spinbox(tree.root(), ttk);
        s.pack();
        TkOptions.configure(s, options(pairs));
        return s;
    }
    /** The values after each of {@code times} steps by dir. */
    private static List<String> steps(Spinbox s, int dir, int times) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < times; i++) { s.step(dir); seen.add(s.text()); }
        return seen;
    }
    private static List<String> notices(TkTree tree) {
        return tree.takeCommands().stream().map(c -> Arrays.stream(c.args).map(String::valueOf).collect(Collectors.joining(" "))).collect(Collectors.toList());
    }
    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }

    @Test void numberFormatsAreCAndTclOnes() {
        for (String ok : new String[] {"%05.1f", "%+5.1f", "%-6.2f", "%08.3f", "%5f", "%5.f", "%.0f", "% 5.2f"})
            assertDoesNotThrow(() -> TkNumberFormat.classicFormat(ok), ok);
        for (String bad : new String[] {"%d", "%.2e", "a%.1f", "%+.1f", "%-6f", "%f", "%.f", "%#5.1f"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TkNumberFormat.classicFormat(bad), bad);
            assertEquals("bad spinbox format specifier \"" + bad + "\"", error.getMessage());
        }
        assertEquals("0.00 ", TkNumberFormat.classic("%-6.2f", 0, 10, 1, 0), "Tk keeps |width| - 1 characters of a left-justified value");
        assertEquals("0.", TkNumberFormat.classic("%-3.1f", 0, 10, 1, 0));
        assertEquals("0    ", TkNumberFormat.classic("%-6.0f", 0, 10, 1, 0));
        assertEquals(" +0.0", TkNumberFormat.classic("%+5.1f", 0, 10, 1, 0));
        assertEquals("0000.000", TkNumberFormat.classic("%08.3f", 0, 10, 1, 0));
        assertEquals("0.000000", TkNumberFormat.classic("%5f", 0, 10, 1, 0));
        assertEquals("    0", TkNumberFormat.classic("%5.f", 0, 10, 1, 0));
        assertEquals("1.2", TkNumberFormat.classic("", 1.25, 2, 0.25, 1.25), "glibc rounds the exact binary value, ties to even");
        assertEquals("0.00e+00", TkNumberFormat.classic("", 0, 1e-5, 1e-7, 0));
        assertEquals("1.00e-07", TkNumberFormat.classic("", 0, 1e-5, 1e-7, 1e-7));
        assertEquals("-2", TkNumberFormat.classic("", -5, 5, 2.5, -2.5));
        assertEquals("4", TkNumberFormat.classic("", -5, 5, 2.5, 4.5));
        assertEquals("0", TkNumberFormat.classic("", 0, 1000000, 1, 0));
        assertEquals(4.0, TkNumberFormat.scan(" 4 ", true));
        assertEquals(4.0, TkNumberFormat.scan("4x", false));
        assertEquals(16.0, TkNumberFormat.scan("0x10", true), "C reads hexadecimal");
        assertEquals(0.0, TkNumberFormat.scan("0x10", false), "Tcl's scan %f stops at the x");
        assertEquals(10.0, TkNumberFormat.scan("1e1", false));
        assertEquals(0.5, TkNumberFormat.scan(".5", true));
        assertEquals(5.0, TkNumberFormat.scan("5.", true));
        assertNull(TkNumberFormat.scan("abc", true));
        assertNull(TkNumberFormat.scan("", false));
        assertEquals("0.2", TkNumberFormat.tcl("%.1f", 0.25));
        assertEquals("0.8", TkNumberFormat.tcl("%.1f", 0.75));
        assertEquals("0.000e+00", TkNumberFormat.tcl("%.3e", 0));
        assertEquals("0", TkNumberFormat.tcl("%d", 0));
        assertEquals("x1.0%", TkNumberFormat.tcl("x%.1f%%", 1));
        assertEquals("1.5", TkNumberFormat.tcl("%g", 1.5));
        assertEquals("expected integer but got \"2.5\"", assertThrows(IllegalArgumentException.class, () -> TkNumberFormat.tcl("%d", 2.5)).getMessage());
    }

    @Test void classicSpinboxStepsAsTk() {
        TkTree tree = desktop();
        Spinbox s = spinbox(tree, false, "from", 0.0, "to", 3.0);
        assertEquals("0", s.text(), "a range formats -from into the entry");
        assertEquals(Arrays.asList("1", "2", "3", "3", "3"), steps(s, 1, 5));
        assertEquals(Arrays.asList("2", "1", "0", "0", "0"), steps(s, -1, 5));
        s = spinbox(tree, false, "from", 0.0, "to", 3.0, "wrap", true);
        assertEquals(Arrays.asList("1", "2", "3", "0", "1"), steps(s, 1, 5));
        assertEquals(Arrays.asList("0", "3", "2", "1", "0"), steps(s, -1, 5));
        s = spinbox(tree, false, "from", 0.0, "to", 2.0, "increment", 0.5);
        assertEquals("0.0", s.text());
        assertEquals(Arrays.asList("0.5", "1.0", "1.5", "2.0", "2.0"), steps(s, 1, 5));
        assertEquals("1.2", spinbox(tree, false, "from", 1.25, "to", 2.0, "increment", 0.25).text());
        s = spinbox(tree, false, "from", 0.0, "to", 100.0, "increment", 7.0, "wrap", true);
        for (String[] c : new String[][] {{"98", "1", "0"}, {"abc", "1", "0"}, {"abc", "-1", "0"}, {"50.7", "1", "58"}}) {
            s.text(c[0]); s.step(Integer.parseInt(c[1])); assertEquals(c[2], s.text(), c[0]);
        }
        s = spinbox(tree, false, "from", 0.0, "to", 10.0, "format", "%05.1f");
        assertEquals("000.0", s.text()); s.step(1); assertEquals("001.0", s.text());
        s = spinbox(tree, false, "values", new Object[] {"a", "b", "c"});
        assertEquals("a", s.text());
        assertEquals(Arrays.asList("b", "c", "c", "c"), steps(s, 1, 4));
        assertEquals(Arrays.asList("b", "a", "a", "a"), steps(s, -1, 4));
        s = spinbox(tree, false, "values", new Object[] {"a", "b", "c"}, "wrap", true);
        assertEquals(Arrays.asList("b", "c", "a", "b"), steps(s, 1, 4));
        s.text("zzz"); s.step(1); assertEquals("c", s.text(), "unknown text keeps Tk's index (b), and steps from it");
        s.text("zzz"); s.step(-1); assertEquals("b", s.text());
        Spinbox reversed = new Spinbox(tree.root(), false);
        assertEquals("-to value must be greater than -from value",
                assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(reversed, options("from", 5.0, "to", 1.0))).getMessage());
        s = spinbox(tree, false, "from", 0.0, "to", 10.0, "state", "readonly");
        s.step(1); assertEquals("1", s.text(), "readonly still steps");
        s = spinbox(tree, false, "from", 0.0, "to", 10.0, "state", "disabled");
        assertNull(s.step(1)); assertEquals("0", s.text(), "disabled does not");
        s = spinbox(tree, false);
        assertEquals("", s.text(), "-from equal to -to: no value");
        assertEquals("", s.step(1)); assertEquals("", s.text(), "and a step changes nothing (but is one: -command runs)");
        s = spinbox(tree, false, "from", 2.0, "to", 10.0);
        assertEquals("2", s.text());
        TkOptions.configure(s, options("from", 5.0)); assertEquals("5", s.text(), "a new -from brings the value into range");
        TkOptions.configure(s, options("values", new Object[] {"x", "y"})); assertEquals("x", s.text());
        TkOptions.configure(s, options("values", new Object[0])); assertEquals("x", s.text(), "no values: the text stays");
        s = spinbox(tree, false, "from", 0.0, "to", 1.0, "increment", 0.1);
        assertEquals(Arrays.asList("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.0"), steps(s, 1, 11));
        s = spinbox(tree, false, "from", -5.0, "to", 5.0, "increment", 2.5);
        assertEquals("-5", s.text());
        assertEquals(Arrays.asList("-2", "0", "2", "4", "5"), steps(s, 1, 5));
        s = spinbox(tree, false, "from", 0.0, "to", 1e-5, "increment", 1e-7);
        assertEquals("0.00e+00", s.text()); s.step(1); assertEquals("1.00e-07", s.text());
        s = spinbox(tree, false, "from", 0.0, "to", 10.0);
        for (String[] c : new String[][] {{" 4 ", "1", "5"}, {"4x", "1", "5"}, {"-3", "1", "0"}, {"15", "-1", "10"}, {"1e1", "-1", "9"}, {"0x10", "-1", "10"}}) {
            s.text(c[0]); s.step(Integer.parseInt(c[1])); assertEquals(c[2], s.text(), c[0]);
        }
        s = spinbox(tree, false, "from", 0.0, "to", 10.0, "wrap", true);
        s.text("15"); s.step(1); assertEquals("0", s.text());
        s.text("-3"); s.step(-1); assertEquals("10", s.text());
        s = spinbox(tree, false, "from", 0.0, "to", 5.0);
        s.selectionRange(0, 1); s.cursor(0); s.step(1);
        assertTrue(s.selectionPresent()); assertEquals(0, s.cursor(), "a classic step keeps the selection and the cursor");
        s = spinbox(tree, false, "values", new Object[] {"ä", "b", "c"});
        s.step(1); s.step(1); s.text("ä"); s.step(1);
        assertEquals("c", s.text(), "Tk does not find text that is not ASCII among the values: it keeps its index");
    }

    @Test void ttkSpinboxStepsAsTk() {
        TkTree tree = desktop();
        Spinbox t = spinbox(tree, true, "from", 0.0, "to", 3.0);
        assertEquals("", t.text(), "ttk puts nothing in at first");
        assertEquals(Arrays.asList("0", "1", "2", "3", "3"), steps(t, 1, 5));
        assertEquals(Arrays.asList("2", "1", "0", "0", "0"), steps(t, -1, 5));
        t = spinbox(tree, true, "from", 0.0, "to", 3.0, "wrap", true);
        assertEquals(Arrays.asList("0", "1", "2", "3", "0"), steps(t, 1, 5));
        assertEquals(Arrays.asList("3", "2", "1", "0", "3"), steps(t, -1, 5));
        t = spinbox(tree, true, "from", 0.0, "to", 2.0, "increment", 0.5);
        assertEquals(Arrays.asList("0.0", "0.5", "1.0", "1.5", "2.0"), steps(t, 1, 5));
        t = spinbox(tree, true, "from", 0.0, "to", 100.0, "increment", 7.0, "wrap", true);
        for (String[] c : new String[][] {{"98", "0"}, {"abc", "0"}, {"50.7", "58"}}) { t.value(c[0]); t.step(1); assertEquals(c[1], t.text(), c[0]); }
        t = spinbox(tree, true, "from", 0.0, "to", 10.0, "format", "%05.1f");
        t.step(1); assertEquals("000.0", t.text());
        t = spinbox(tree, true, "values", new Object[] {"a", "b", "c"});
        assertEquals(Arrays.asList("a", "b", "c", "c"), steps(t, 1, 4));
        assertEquals(Arrays.asList("b", "a", "a", "a"), steps(t, -1, 4));
        t = spinbox(tree, true, "values", new Object[] {"a", "b", "c"}, "wrap", true);
        assertEquals(Arrays.asList("a", "b", "c", "a"), steps(t, 1, 4));
        t.value("zzz"); t.step(1); assertEquals("a", t.text());
        t.value("zzz"); t.step(-1); assertEquals("c", t.text());
        t = spinbox(tree, true, "from", 5.0, "to", 1.0);
        t.step(1); assertEquals("1", t.text(), "ttk takes -from above -to");
        t = spinbox(tree, true, "from", 0.0, "to", 10.0, "format", "%.3e");
        t.step(1); assertEquals("0.000e+00", t.text());
        t = spinbox(tree, true, "from", 0.0, "to", 1.0, "increment", 0.25);
        assertEquals(Arrays.asList("0.0", "0.2", "0.5", "0.8", "1.0", "1.0"), steps(t, 1, 6));
        t = spinbox(tree, true, "from", 0.0, "to", 1.0, "increment", 0.1);
        assertEquals(Arrays.asList("0.0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.0"), steps(t, 1, 12));
        t = spinbox(tree, true, "from", 0.0, "to", 10.0);
        for (String[] c : new String[][] {{" 4 ", "1", "5"}, {"4x", "1", "5"}, {"-3", "1", "0"}, {"15", "-1", "10"}, {"0x10", "-1", "0"}, {"1e1", "-1", "9"}}) {
            t.value(c[0]); t.step(Integer.parseInt(c[1])); assertEquals(c[2], t.text(), c[0]);
        }
        t = spinbox(tree, true, "values", new Object[] {"a", "b", "a", "c"});
        assertEquals(Arrays.asList("a", "b", "a", "c", "c"), steps(t, 1, 5));
        t.value("b"); t.step(-1); assertEquals("a", t.text());
        t = spinbox(tree, true, "from", 0.0, "to", 5.0);
        t.value("2"); t.selectionClear(); t.cursor(0); t.step(1);
        assertTrue(t.selectionPresent()); assertEquals(1, t.cursor(), "ttk selects the new value, the cursor at its end");
        assertEquals(0, t.selectionFirst()); assertEquals(1, t.selectionLast());
        t = spinbox(tree, true, "from", 0.0, "to", 10.0, "state", "readonly");
        t.step(1); assertEquals("0", t.text());
        t = spinbox(tree, true, "from", 0.0, "to", 10.0, "state", "disabled");
        assertNull(t.step(1)); assertEquals("", t.text());
    }

    @Test void spinboxArrowsKeysAndPaint() {
        TkTree tree = desktop();
        Spinbox classic = spinbox(tree, false, "from", 0.0, "to", 9.0, "notify", 7);
        Spinbox ttk = spinbox(tree, true, "from", 0.0, "to", 9.0, "notify", 8);
        Button other = new Button(tree.root(), "x"); other.pack();
        tree.focus(other.id);
        assertEquals("buttonup", classic.identify(classic.bounds().width - 3, 2));
        assertEquals("buttondown", classic.identify(classic.bounds().width - 3, classic.bounds().height - 3));
        assertEquals("entry", classic.identify(4, 4));
        assertEquals("", classic.identify(-1, 4));
        assertEquals("uparrow", ttk.identify(ttk.bounds().width - 3, 2));
        assertEquals("downarrow", ttk.identify(ttk.bounds().width - 3, ttk.bounds().height - 3));
        assertEquals("textarea", ttk.identify(4, 4));
        TkRect up = classic.arrowBox(Spinbox.UP);
        tree.pointerDown(1, up.x + 2, up.y + 2);
        assertEquals("0", classic.text(), "classic Tk steps on the release");
        assertTrue((records(tree, classic.id).stream().filter(i -> i.part == 1).findFirst().get().state & TkDisplayList.PRESSED) != 0, "the arrow is drawn pressed");
        assertNull(tree.pointerUp(1, up.x + 2, up.y + 2));
        assertEquals("1", classic.text());
        assertEquals(other.id, tree.focused(), "a classic arrow leaves the focus");
        assertEquals(Arrays.asList("stepped 1 0 1"), notices(tree));
        TkRect down = ttk.arrowBox(Spinbox.DOWN);
        tree.pointerDown(1, down.x + 2, down.y + 2);
        assertEquals("0", ttk.text(), "ttk steps on the press");
        assertEquals(ttk.id, tree.focused(), "and takes the keyboard");
        tree.pointerUp(1, down.x + 2, down.y + 2);
        assertEquals(Arrays.asList("stepped -1  0"), notices(tree));
        tree.key(200, 0, 0); assertEquals("1", ttk.text(), "Up steps");
        tree.key(208, 0, 0); assertEquals("0", ttk.text(), "Down too");
        notices(tree);
        tree.wheel(ttk.bounds().x + 4, ttk.bounds().y + 4, 120); assertEquals("1", ttk.text(), "ttk's wheel up is <<Increment>>");
        notices(tree);
        TkOptions.configure(ttk, options("delegate", true));
        tree.key(200, 0, 0);
        assertEquals("1", ttk.text(), "with <<Increment>> bound the step is the program's");
        assertEquals(Arrays.asList("step 1"), notices(tree));
        tree.focus(classic.id);
        tree.key(208, 0, 0); assertEquals("0", classic.text());
        List<TkDisplayList.Item> parts = records(tree, classic.id);
        assertEquals(9, parts.size(), "the entry, two buttons and two three-row triangles");
        assertEquals(TkWidget.Kind.ENTRY, parts.get(0).kind, "painted with the older record kinds");
        assertTrue(TkPaint.maxRecords(classic, 20) >= parts.size());
        assertEquals(TkWidget.Kind.ENTRY, tree.render().items.stream().filter(i -> i.id == ttk.id && i.part == 0).findFirst().get().kind);
    }

    @Test void comboboxListAsTk() {
        TkTree tree = desktop();
        Combobox box = new Combobox(tree.root());
        box.pack();
        Object[] values = new Object[15];
        for (int i = 0; i < values.length; i++) values[i] = "item " + i;
        TkOptions.configure(box, options("values", values, "notify", 9));
        assertEquals(10, box.height);
        assertEquals(-1, box.current());
        box.current(1); assertEquals("item 1", box.text()); assertEquals(1, box.current());
        box.value("item 2"); assertEquals(2, box.current());
        box.value("nope"); assertEquals(-1, box.current());
        assertEquals("Index 15 out of range", assertThrows(IllegalArgumentException.class, () -> box.current(15)).getMessage());
        assertEquals("Index -1 out of range", assertThrows(IllegalArgumentException.class, () -> box.current(-1)).getMessage());
        assertEquals("downarrow", box.identify(box.bounds().width - 3, 4));
        assertEquals("textarea", box.identify(4, 4));
        // The arrow opens the list (the Combobox takes the keyboard), highlighted at the first value.
        TkRect arrow = box.arrowBox();
        tree.pointerDown(1, arrow.x + 3, arrow.y + 3);
        assertNull(tree.pointerUp(1, arrow.x + 3, arrow.y + 3));
        assertTrue(tree.listOpen());
        assertEquals(box.id, tree.focused());
        assertEquals(16, tree.flags(box) & 16, "'pressed' while the list is open");
        TkPopup list = tree.popup;
        assertEquals(10, list.rows());
        assertTrue(list.scrolls());
        assertEquals(new TkRect(box.bounds.x, box.bounds.y + box.bounds.height, box.bounds.width, 162), list.box());
        List<TkDisplayList.Item> items = tree.render().items;
        assertTrue(items.get(items.size() - 1).part >= TkDisplayList.POPUP_PART, "the list is the topmost layer");
        assertEquals(1 + 2 + 10, items.stream().filter(i -> i.id == box.id && i.part >= TkDisplayList.POPUP_PART).count());
        // The highlight follows the pointer; a click chooses.
        TkRect rows = list.list();
        tree.pointerHover(rows.x + 5, rows.y + 2 * 16 + 4);
        assertEquals(2, list.active);
        tree.pointerDown(1, rows.x + 5, rows.y + 3 * 16 + 4);
        assertTrue(tree.managing(1), "a press in the list is the list's");
        tree.pointerUp(1, rows.x + 5, rows.y + 3 * 16 + 4);
        assertFalse(tree.listOpen());
        assertEquals("item 3", box.text());
        assertTrue(box.selectionPresent()); assertEquals(box.length(), box.cursor());
        assertEquals(Arrays.asList("selected 3"), notices(tree));
        // Down opens it at the current value; Down moves; Return chooses.
        tree.key(208, 0, 0);
        assertTrue(tree.listOpen()); assertEquals(3, tree.popup.active);
        tree.key(208, 0, 0); tree.key(208, 0, 0);
        assertEquals(5, tree.popup.active);
        tree.key(28, 0, 0);
        assertEquals("item 5", box.text());
        notices(tree);
        // Escape (sent only while a list is open) closes it; so does a press anywhere else, which goes no further.
        tree.key(208, 0, 0); tree.key(1, 0, 0);
        assertFalse(tree.listOpen());
        Button button = new Button(tree.root(), "b"); button.place(TkWindow.place(260, 200, null, null));   // clear of the list
        TkOptions.configure(button, options("command", 4));
        tree.key(208, 0, 0);
        TkRect b = button.bounds();
        tree.pointerDown(2, b.x + 2, b.y + 2);
        assertFalse(tree.listOpen());
        assertTrue(tree.managing(2));
        assertNull(tree.pointerUp(2, b.x + 2, b.y + 2), "the button never saw the press");
        assertEquals("item 5", box.text());
        // Paging and the scrollbar.
        tree.key(208, 0, 0);
        tree.key(209, 0, 0);
        assertEquals(5, tree.popup.top(), "Next scrolls a page (the view ends at the last value)");
        TkRect bar = tree.popup.bar();
        tree.pointerDown(3, bar.x + 2, bar.y + 1);
        assertEquals(0, tree.popup.top(), "a press above the thumb pages up");
        tree.wheel(rows.x + 3, rows.y + 3, -120);
        assertEquals(3, tree.popup.top());
        tree.key(1, 0, 0);
        notices(tree);
        // The wheel over a closed Combobox steps through the values (not past the ends).
        tree.wheel(box.bounds.x + 4, box.bounds.y + 4, -120);
        assertEquals("item 6", box.text());
        assertEquals(Arrays.asList("selected 6"), notices(tree));
        box.current(14);
        tree.wheel(box.bounds.x + 4, box.bounds.y + 4, -120);
        assertEquals("item 14", box.text());
        // Readonly: a press anywhere opens the list; editable, the text area places the cursor.
        tree.pointerDown(1, box.bounds.x + 4, box.bounds.y + 4); tree.pointerUp(1, box.bounds.x + 4, box.bounds.y + 4);
        assertFalse(tree.listOpen());
        TkOptions.configure(box, options("state", "readonly"));
        tree.pointerDown(1, box.bounds.x + 4, box.bounds.y + 4); tree.pointerUp(1, box.bounds.x + 4, box.bounds.y + 4);
        assertTrue(tree.listOpen());
        tree.key(1, 0, 0);
        // A -postcommand: the program runs it, then opens the list.
        TkOptions.configure(box, options("postcommand", true));
        tree.key(208, 0, 0);
        assertFalse(tree.listOpen());
        assertEquals(Arrays.asList("post"), notices(tree));
        TkControlApi.call(box, "post", new Object[0]);
        assertTrue(tree.listOpen());
        assertEquals(true, TkControlApi.call(box, "posted", new Object[0]));
        box.enabled(false);
        assertFalse(tree.listOpen(), "the list goes with its Combobox");
    }

    @Test void theListOpensAboveWhenThereIsNoRoomBelow() {
        TkTree tree = desktop();
        Frame filler = new Frame(tree.root()); filler.size(10, 200); filler.pack();
        Combobox box = new Combobox(tree.root()); box.pack();
        TkOptions.configure(box, options("values", new Object[] {"a", "b", "c"}, "height", 5));
        tree.open(box);
        TkRect list = tree.popup.box();
        assertEquals(3 * 16 + 2, list.height);
        assertEquals(box.bounds.y - list.height, list.y);
        assertFalse(tree.popup.scrolls());
    }

    @Test void validationHoldsPlayerEditsInOrder() {
        TkTree tree = desktop();
        Entry e = new Entry(tree.root()); e.pack();
        TkOptions.configure(e, options("validate", "key", "notify", 3));
        tree.focus(e.id);
        tree.key(30, 0, 'a'); tree.key(48, 0, 'b');
        assertEquals("", e.text(), "nothing changes before the program answers");
        assertEquals(Arrays.asList("validate 1 1 0  a a"), notices(tree));
        e.validated(1, true);
        assertEquals("a", e.text());
        assertEquals(Arrays.asList("validate 2 1 1 a ab b"), notices(tree), "the next key waited behind it");
        e.validated(1, true);
        assertEquals("a", e.text(), "a stale answer does nothing");
        e.validated(2, false);
        assertEquals("a", e.text());
        assertTrue(notices(tree).isEmpty());
        tree.key(14, 0, 0);
        assertEquals(Arrays.asList("validate 3 0 0 a  a"), notices(tree));
        e.text("x");
        e.validated(3, true);
        assertEquals("x", e.text(), "the program changed the text meanwhile: the change is dropped");
        // Typing over a selection: the delete, then the insert, each validated.
        e.text("abc"); e.selectionRange(0, 3); e.cursor(3);
        tree.key(45, 0, 'x');
        assertEquals(Arrays.asList("validate 4 0 0 abc  abc"), notices(tree));
        e.validated(4, false);
        assertEquals(Arrays.asList("validate 5 1 3 abc abcx x"), notices(tree), "rejected, the insert still comes, at the cursor");
        e.validated(5, true);
        assertEquals("abcx", e.text());
        // Paste, cut and focus.
        e.selectionRange(0, 1);
        assertEquals("a", tree.copySelection(true));
        assertEquals(Arrays.asList("validate 6 0 0 abcx bcx a"), notices(tree));
        e.validated(6, true);
        assertEquals("bcx", e.text());
        TkOptions.configure(e, options("validate", "focus"));
        Button other = new Button(tree.root(), "o"); other.pack();
        tree.key(30, 0, 'q');
        assertEquals("bcxq", e.text(), "no key validation: at once");
        tree.focus(other.id);
        tree.focus(e.id);
        assertEquals(Arrays.asList("focusout bcxq", "focusin bcxq"), notices(tree));
        TkOptions.configure(e, options("validate", "none"));
        tree.focus(other.id);
        assertTrue(notices(tree).isEmpty());
    }

    @Test void showMasksAndJustifyPlaces() {
        TkTree tree = desktop();
        Entry e = new Entry(tree.root()); e.pack();
        TkOptions.configure(e, options("show", "*x"));
        e.text("hello");
        e.selectionRange(1, 3);
        assertEquals("*****", e.paintText());
        assertEquals("**", e.selectedText(), "Tk copies the mask");
        assertEquals("*****", records(tree, e.id).get(0).text);
        tree.focus(e.id);
        assertEquals("**", tree.copySelection(false));
        e.cursor(3);
        tree.key(203, 2, 0); assertEquals(0, e.cursor(), "a masked Entry has no words: Ctrl+Left goes to the start");
        tree.key(205, 2, 0); assertEquals(5, e.cursor());
        assertEquals("bad justification \"middle\": must be left, right, or center",
                assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(e, options("justify", "middle"))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(e, options("show", "\n")));
        TkOptions.configure(e, options("show", "", "justify", "right"));
        TkRect b = e.bounds();
        int room = b.width - e.insetWidth();
        assertEquals(b.x + e.insetLeft() + room - 1 - 40, e.textOriginX(), "right: the text and the caret end at the right");
        TkOptions.configure(e, options("justify", "center"));
        assertEquals(b.x + e.insetLeft() + (room - 1 - 40) / 2, e.textOriginX());
        e.text("0123456789".repeat(5));
        assertEquals(b.x + e.insetLeft() - e.scrollX, e.textOriginX(), "longer than the entry: it scrolls as when left-justified");
        assertEquals("center", TkControlOptions.cget(e, "justify"));
        assertEquals("bad validate \"sometimes\": must be all, key, focus, focusin, focusout, or none",
                assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(e, options("validate", "sometimes"))).getMessage());
    }

    @Test void pastingDeletesTheSelectionThenInsertsAtTheCursor() {
        TkTree tree = desktop();
        Entry e = new Entry(tree.root()); e.pack();
        tree.focus(e.id);
        e.text("abcdef"); e.cursor(1); e.selectionRange(3, 5);
        tree.paste("XY");
        assertEquals("aXYbcf", e.text(), "Tk's <<Paste>> on Windows: delete sel.first sel.last; insert insert");
        e.text("abcdef"); e.cursor(1); e.selectionRange(3, 5);
        tree.key(45, 0, 'x');
        assertEquals("axbcdef", e.text(), "typing replaces a selection only when the cursor is in it (tk::EntryInsert)");
    }

    @Test void optionsSurviveSaving() {
        TkTree tree = desktop();
        Spinbox s = spinbox(tree, false, "values", new Object[] {"a", "b c"}, "wrap", true, "notify", 5, "validate", "all", "show", "#", "justify", "right");
        s.step(1);
        Spinbox t = spinbox(tree, true, "from", -2.0, "to", 7.5, "increment", 0.5, "format", "%.2f", "delegate", true);
        t.step(1); t.value("abc");
        Combobox c = new Combobox(tree.root()); c.pack();
        TkOptions.configure(c, options("values", new Object[] {"x", "y"}, "height", 4, "postcommand", true, "notify", 6));
        c.current(1);
        tree.open(c);
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        Spinbox s2 = (Spinbox) loaded.get(s.id), t2 = (Spinbox) loaded.get(t.id);
        Combobox c2 = (Combobox) loaded.get(c.id);
        assertFalse(s2.ttk); assertTrue(t2.ttk);
        assertEquals(Arrays.asList("a", "b c"), s2.values); assertTrue(s2.wrap); assertEquals(1, s2.valueIndex); assertEquals("b c", s2.text());
        assertEquals("all", s2.validate); assertEquals(5, s2.notify); assertEquals("#", s2.show); assertEquals(Entry.RIGHT, s2.justify);
        assertEquals(-2.0, t2.from); assertEquals(7.5, t2.to); assertEquals(0.5, t2.increment); assertEquals("%.2f", t2.format);
        assertEquals("abc", t2.text(), "restoring does not step or reformat"); assertTrue(t2.delegate);
        assertEquals(Arrays.asList("x", "y"), c2.values); assertEquals(4, c2.height); assertTrue(c2.postcommand); assertEquals(1, c2.current());
        assertEquals(1, c2.lines, "a Combobox's -height is its list's, not its lines");
        assertNull(loaded.popup, "an open list is not saved");
    }
}
