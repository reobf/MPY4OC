package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ttk stage 6 on the server: menus, a window's menubar, Menubuttons, and the posted menus' pointer and keys. */
class TkMenuTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(320, 240); return tree; }
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static Frame window(TkTree tree, int x, int y, int width, int height) {
        Frame w = TkWindow.create(tree.root());
        TkWindow.wm(tree, w, "geometry", options("width", width, "height", height, "x", x, "y", y));
        return w;
    }
    private static List<String> notices(TkTree tree) {
        return tree.takeCommands().stream().map(c -> c.widget + " " + Arrays.stream(c.args).map(String::valueOf).collect(Collectors.joining(" "))).collect(Collectors.toList());
    }
    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }

    /**
     * File: tearoff (y 1..9), Open Ctrl+O (9..29), separator (29..37), Bold (checked, 37..57), Gone
     * (disabled, 57..77), More (cascade to {@code sub}, 77..97); box 98 high.
     */
    private static Menu file(Frame parent, Menu sub) {
        Menu file = new Menu(parent);
        file.entries(new Object[] {
                options("type", "tearoff"),
                options("label", "Open", "accelerator", "Ctrl+O", "underline", 0),
                options("type", "separator"),
                options("type", "checkbutton", "label", "Bold", "selected", true),
                options("label", "Gone", "state", "disabled"),
                options("type", "cascade", "label", "More", "menu", sub.id)});
        TkOptions.configure(file, options("notify", 7));
        return file;
    }
    private static Menu sub(Frame parent) {
        Menu sub = new Menu(parent);
        sub.entries(new Object[] {options("label", "Inner"), options("label", "Other")});
        TkOptions.configure(sub, options("notify", 8));
        return sub;
    }

    @Test void theMenubarSitsBetweenTitleAndClient() {
        TkTree tree = desktop();
        Frame w = window(tree, 20, 30, 200, 100);
        assertEquals(new TkRect(20, 48, 200, 100), w.bounds());
        Menu sub = sub(w), file = file(w, sub), bar = new Menu(w);
        bar.entries(new Object[] {options("type", "cascade", "label", "File", "menu", file.id), options("label", "Help")});
        TkOptions.configure(w, options("menu", bar.id));
        assertEquals(new TkRect(20, 68, 200, 100), w.bounds(), "the window's top stays; the client moves down");
        assertArrayEquals(new Object[] {200, 100, 20, 30}, (Object[]) TkWindow.wm(tree, w, "geometry", options()), "geometry: the client's size, the window's top");
        assertEquals(TkWindow.Zone.MENUBAR, tree.zoneAt(30, 55));
        assertEquals(TkWindow.Zone.TITLE, tree.zoneAt(30, 35));
        assertEquals(0, TkWindow.barEntryAt(w, 30, 55));
        assertEquals(1, TkWindow.barEntryAt(w, 80, 55), "File is 4 characters and 6 px either side: Help from x 64");
        TkDisplayList.Item help = records(tree, w.id).stream().filter(i -> i.part == TkWindow.PART_MENUBAR + 1 + 1).findFirst().get();
        assertEquals("Help", help.text);
        TkOptions.configure(w, options("menu", 0));
        assertEquals(new TkRect(20, 48, 200, 100), w.bounds(), "taken away, the client moves back up");
        TkOptions.configure(w, options("menu", bar.id));
        TkWindow.wm(tree, w, "geometry", options("x", 10, "y", 5));
        assertEquals(new TkRect(10, 43, 200, 100), w.bounds(), "geometry's y is the window's top");
    }

    @Test void theMenubarDropsDownFollowsThePointerAndInvokes() {
        TkTree tree = desktop();
        Frame w = window(tree, 20, 30, 200, 100);
        Menu sub = sub(w), file = file(w, sub), bar = new Menu(w);
        bar.entries(new Object[] {options("type", "cascade", "label", "File", "menu", file.id), options("label", "Help")});
        TkOptions.configure(bar, options("notify", 9));
        TkOptions.configure(w, options("menu", bar.id));
        tree.render();
        tree.pointerDown(1, 30, 55);
        assertTrue(tree.listOpen());
        assertEquals(1, tree.menus.levels.size());
        assertEquals(20, tree.menus.levels.get(0).x); assertEquals(68, tree.menus.levels.get(0).y, "dropped below the bar entry");
        assertNull(tree.pointerUp(1, 30, 55));
        assertTrue(tree.listOpen(), "released on the bar entry: it stays down");
        assertTrue(records(tree, w.id).stream().anyMatch(i -> i.part == TkWindow.PART_MENUBAR + 1 && i.background == 0xECECEC), "the open entry is lit");
        // The pointer: the highlight follows; a disabled entry lights nothing; a cascade opens to the right.
        tree.pointerHover(40, 68 + 15);
        assertEquals(1, file.active);
        tree.pointerHover(40, 68 + 60);
        assertEquals(-1, file.active, "Gone is disabled");
        tree.pointerHover(40, 68 + 85);
        assertEquals(5, file.active);
        assertEquals(2, tree.menus.levels.size());
        TkMenus.Level inner = tree.menus.levels.get(1);
        assertEquals(sub, inner.menu);
        assertEquals(20 + file.boxWidth() - 1, inner.x); assertEquals(68 + 77 - 1, inner.y);
        tree.pointerHover(40, 68 + 15);
        assertEquals(1, tree.menus.levels.size(), "another entry closes the cascade");
        // Over another menubar entry: its menu instead (Help is a command: none, only lit).
        tree.pointerHover(80, 55);
        assertEquals(1, tree.menus.barIndex); assertTrue(tree.menus.levels.isEmpty());
        tree.pointerHover(30, 55);
        assertEquals(0, tree.menus.barIndex); assertEquals(1, tree.menus.levels.size());
        // Painted on the popup layer: the box, rows, the check mark, the accelerator, the arrow.
        List<TkDisplayList.Item> posted = records(tree, file.id);
        assertTrue(posted.stream().allMatch(i -> i.part >= TkDisplayList.POPUP_PART));
        assertTrue(posted.stream().anyMatch(i -> Menu.CHECK.equals(i.text)));
        assertTrue(posted.stream().anyMatch(i -> "Ctrl+O".equals(i.text)));
        assertTrue(posted.stream().anyMatch(i -> Menu.CASCADE.equals(i.text)));
        // A press and release on Open runs it: everything unposts, then the program hears of it.
        notices(tree);
        tree.pointerDown(1, 40, 68 + 15);
        assertNull(tree.pointerUp(1, 40, 68 + 15));
        assertFalse(tree.listOpen());
        assertEquals(List.of(file.id + " invoke 1"), notices(tree));
        assertTrue(records(tree, file.id).isEmpty());
        // A command on the menubar runs on its release.
        tree.pointerDown(1, 80, 55);
        tree.pointerUp(1, 80, 55);
        assertEquals(List.of(bar.id + " invoke 1"), notices(tree));
        // A press elsewhere unposts and goes no further.
        tree.pointerDown(1, 30, 55);
        tree.pointerUp(1, 30, 55);
        tree.pointerDown(2, 300, 200);
        assertFalse(tree.listOpen());
        assertTrue(tree.managing(2), "the press was the menus'");
        tree.pointerUp(2, 300, 200);
        assertEquals(List.of(), notices(tree));
    }

    @Test void keysTraverseMenus() {
        TkTree tree = desktop();
        Frame w = window(tree, 20, 30, 200, 100);
        Menu sub = sub(w), file = file(w, sub), bar = new Menu(w);
        bar.entries(new Object[] {options("type", "cascade", "label", "File", "menu", file.id), options("type", "cascade", "label", "Edit", "menu", sub.id)});
        TkOptions.configure(w, options("menu", bar.id));
        tree.render();
        tree.key(68, 0);   // F10
        assertTrue(tree.listOpen());
        assertEquals(1, file.active, "the first entry after the tearoff");
        tree.key(208, 0); assertEquals(3, file.active, "Down skips the separator");
        tree.key(208, 0); assertEquals(5, file.active, "and the disabled entry");
        tree.key(208, 0); assertEquals(1, file.active, "wrapping");
        tree.key(200, 0); assertEquals(5, file.active);
        tree.key(205, 0);
        assertEquals(2, tree.menus.levels.size()); assertEquals(0, sub.active, "Right opens the cascade at its first entry");
        tree.key(203, 0);
        assertEquals(1, tree.menus.levels.size(), "Left closes it");
        tree.key(203, 0);
        assertEquals(1, tree.menus.barIndex, "Left again: the menubar's previous menu, wrapping");
        assertEquals(sub, tree.menus.levels.get(0).menu); assertEquals(0, sub.active);
        tree.key(205, 0);
        assertEquals(0, tree.menus.barIndex);
        tree.key(1, 0);
        assertFalse(tree.listOpen(), "Escape");
        notices(tree);
        tree.key(68, 0);
        tree.key(28, 0);
        assertEquals(List.of(file.id + " invoke 1"), notices(tree), "Return runs the lit entry");
        tree.key(68, 0);
        tree.key(0, 0, 'b');
        assertEquals(List.of(), notices(tree), "Bold has no underline");
        tree.key(0, 0, 'O');
        assertEquals(List.of(file.id + " invoke 1"), notices(tree), "Open's underlined O");
    }

    @Test void menubuttonsPostTheirMenu() {
        TkTree tree = desktop();
        Frame w = window(tree, 20, 30, 200, 150);
        Menu sub = sub(w), file = file(w, sub);
        Menubutton mb = new Menubutton(w, false);
        TkOptions.configure(mb, options("text", "Pick", "menu", file.id, "relief", "raised", "indicatoron", true));
        mb.pack();
        TkRect b = mb.bounds();
        tree.pointerDown(1, b.x + 2, b.y + 2);
        assertTrue(tree.listOpen());
        assertEquals(b.x, tree.menus.levels.get(0).x); assertEquals(b.y + b.height, tree.menus.levels.get(0).y, "below it");
        assertEquals(TkRelief.SUNKEN, records(tree, mb.id).stream().filter(i -> i.part == 0).findFirst().get().relief, "a raised one looks pushed in");
        assertTrue(records(tree, mb.id).stream().anyMatch(i -> i.part == 1), "its indicator");
        tree.pointerUp(1, b.x + 2, b.y + 2);
        assertTrue(tree.listOpen(), "released on the button: still posted");
        // Pressed again and dragged to Open: the release runs it.
        tree.pointerDown(1, b.x + 2, b.y + 2);
        assertFalse(tree.listOpen(), "a press on the button again unposts");
        tree.pointerUp(1, b.x + 2, b.y + 2);
        notices(tree);
        tree.pointerDown(1, b.x + 2, b.y + 2);
        int y = b.y + b.height + 15;
        tree.pointerDrag(1, b.x + 10, y);
        assertEquals(1, file.active);
        tree.pointerUp(1, b.x + 10, y);
        assertEquals(List.of(file.id + " invoke 1"), notices(tree));
        TkOptions.configure(mb, options("direction", "right"));
        tree.pointerDown(1, b.x + 2, b.y + 2);
        assertEquals(b.x + b.width, tree.menus.levels.get(0).x);
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(mb, options("direction", "up")));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(mb, options("menu", w.id)), "a Menu");
    }

    @Test void theProgramPostsAndAsksWhereEntriesAre() {
        TkTree tree = desktop();
        Frame w = window(tree, 20, 30, 200, 100);
        Menu sub = sub(w), file = file(w, sub);
        assertEquals(9, TkMenuApi.call(file, "yposition", new Object[] {1}));
        assertEquals(3, TkMenuApi.call(file, "index_at", new Object[] {40}));
        assertEquals(-1, TkMenuApi.call(file, "index_at", new Object[] {500}));
        TkMenuApi.call(file, "post", new Object[] {100, 100, 3});
        assertEquals(100 - 37 - Menu.ROW / 2, tree.menus.levels.get(0).y, "tk_popup's entry under the point");
        assertEquals(3, TkMenuApi.call(file, "active", new Object[0]));
        assertEquals(true, TkMenuApi.call(file, "posted", new Object[0]));
        TkMenuApi.call(file, "unpost", new Object[0]);
        assertFalse(tree.listOpen());
        assertEquals(-1, file.active, "unposting clears the highlight");
        TkMenuApi.call(file, "activate", new Object[] {2});
        assertEquals(-1, file.active, "a separator is never active");
        TkMenuApi.call(file, "post", new Object[] {300, 230, -1});
        TkMenus.Level l = tree.menus.levels.get(0);
        assertTrue(l.x + file.boxWidth() <= 320 && l.y + file.boxHeight() <= 240, "kept on the display");
        // Destroying a posted menu unposts it.
        file.destroy();
        assertFalse(tree.listOpen());
        assertThrows(IllegalArgumentException.class, () -> sub.entries(new Object[] {options("type", "zzz")}));
        assertThrows(IllegalArgumentException.class, () -> sub.entries(new Object[] {options("label", "a\nb")}));
        assertThrows(IllegalArgumentException.class, () -> sub.entries(new Object[] {options("label", "a", "state", "zzz")}));
    }

    @Test void menusAreSaved() {
        TkTree tree = desktop();
        Frame w = window(tree, 20, 30, 200, 100);
        Menu sub = sub(w), file = file(w, sub), bar = new Menu(w);
        bar.entries(new Object[] {options("type", "cascade", "label", "File", "menu", file.id)});
        TkOptions.configure(w, options("menu", bar.id));
        Menubutton mb = new Menubutton(w, true);
        TkOptions.configure(mb, options("text", "T", "menu", sub.id, "direction", "above"));
        mb.pack();
        TkOptions.configure(file, options("activebackground", 0x112233, "postcommand", true));
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        Menu f = (Menu) loaded.get(file.id);
        assertEquals(6, f.entries.size());
        assertEquals("Ctrl+O", f.entries.get(1).accelerator);
        assertTrue(f.entries.get(3).selected); assertTrue(f.entries.get(4).disabled);
        assertEquals(sub.id, f.entries.get(5).cascade);
        assertEquals(0x112233, f.activeBackground); assertTrue(f.postcommand); assertEquals(7, f.notify);
        Frame lw = (Frame) loaded.get(w.id);
        assertEquals(bar.id, lw.window.menu);
        assertEquals(new TkRect(20, 68, 200, 100), lw.bounds());
        Menubutton lmb = (Menubutton) loaded.get(mb.id);
        assertTrue(lmb.ttk); assertEquals(sub.id, lmb.menu); assertEquals("above", lmb.direction);
    }
}
