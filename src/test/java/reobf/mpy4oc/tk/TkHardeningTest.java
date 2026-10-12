package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

/** Regressions for the multiplayer hardening pass (audit of 2026-10-08). */
class TkHardeningTest {
    private static TkPack fillY() { return new TkPack(TkPack.Side.LEFT, TkPack.Fill.Y, false, TkPack.Anchor.CENTER, 0,0,0,0,0,0); }

    @Test void renderTruncatesAFrameOverThePaintBudgetInsteadOfThrowing() {
        // A plain Python program could build this; render() runs in the tile entity tick,
        // where an exception used to crash the server.
        // 70 listboxes showing 128 rows each: more records than a display draws (MAX_RECORDS).
        TkTree tree = new TkTree(); tree.resize(2048, 2048);
        String[] rows = new String[130]; Arrays.fill(rows, "x");
        for (int i = 0; i < 70; i++) {
            Listbox list = new Listbox(tree.root()); TkOptions.configure(list, Map.of("width", 1));
            list.pack(fillY()); list.insert(0, rows);
        }
        TkDisplayList frame = assertDoesNotThrow(tree::render);
        assertTrue(tree.overflowed());
        assertTrue(frame.items.size() <= TkDisplayList.MAX_RECORDS);
        assertDoesNotThrow(() -> TkTreePersistence.save(tree));
    }

    @Test void entryPasteTakesTheLongestPrefixThatFitsBothBudgets() {
        TkTree tree = new TkTree(); Entry e = new Entry(tree.root()); e.pack(); tree.render();
        e.paste("a\u0001b\tc" + "d".repeat(2000));
        assertEquals(1024, e.text().length()); assertTrue(e.text().startsWith("ab c"));
        // Tree-wide budget: other widgets already hold most of it.
        TkTree full = new TkTree();
        for (int i = 0; i < 15; i++) new Label(full.root(), "x".repeat(1024)).pack();
        new Label(full.root(), "x".repeat(512)).pack();
        Entry f = new Entry(full.root()); f.pack(); full.render();
        f.paste("中".repeat(400)); // 3 bytes each; only 512 bytes of the tree budget remain
        assertEquals(170, f.text().codePointCount(0, f.text().length()));
    }

    @Test void textPasteFiltersControlCharactersAndInsertsWhatFits() {
        TkTree tree = new TkTree(); Text t = new Text(tree.root()); t.pack(); tree.render();
        t.paste("a\u0001b\r\nc\u007f");
        assertEquals("ab\nc\n", t.content());
        t.paste("y".repeat(Text.MAX_CHARS));
        assertEquals(Text.MAX_CHARS, t.length());
    }

    @Test void altGrAndOptionTypeCharactersButCtrlAloneIsAShortcut() {
        TkTree tree = new TkTree(); Entry e = new Entry(tree.root()); e.pack(); tree.render();
        e.key(16, '@', 6);   // Windows AltGr = Ctrl+Alt
        e.key(30, 'ą', 6);   // AltGr+A on a Polish layout must type, not select all
        e.key(18, '€', 4);   // macOS Option
        assertEquals("@ą€", e.text());
        e.key(30, 'a', 2);   // Ctrl+A: select all, no insertion
        assertEquals("@ą€", e.text()); assertTrue(e.selectionPresent());
        Text t = new Text(tree.root()); t.pack(); tree.render();
        t.key(26, '{', 6); t.key(16, '@', 4);
        assertEquals("{@\n", t.content());
    }

    @Test void scrolledHiddenListboxAndClippedFocusSurviveSaveAndLoad() {
        TkTree tree = new TkTree();
        Frame filler = new Frame(tree.root()); filler.size(320, 230); filler.pack();
        Entry entry = new Entry(tree.root()); entry.pack();
        Listbox list = new Listbox(tree.root()); list.pack();
        String[] rows = new String[50]; Arrays.fill(rows, "row"); list.insert(0, rows);
        list.scroll(false, 1_000_000, false); list.scroll(true, 1_000_000, false);
        assertTrue(list.top <= 49 && list.scrollX <= 8192, "the view is bounded even while unmapped");
        tree.render(); tree.focus(entry.id);
        NBTTagCompound saved = TkTreePersistence.save(tree);
        // Restored at a size where the focused Entry is clipped away: keep the tree, drop focus.
        TkTree restored = assertDoesNotThrow(() -> TkTreePersistence.load(saved, 320, 100, () -> {}));
        assertEquals(0, restored.focused());
        assertEquals(50, ((Listbox) restored.get(list.id)).size());
    }

    @Test void filledPolygonsAreLimitedBySelfIntersections() {
        Canvas c = new Canvas(new TkTree().root()); c.size(320, 240); c.pack(); c.tree.render();
        double[] pentagram = new double[10];
        for (int i = 0; i < 5; i++) { double a = Math.PI / 2 + i * 4 * Math.PI / 5; pentagram[2*i] = 100 + 50 * Math.cos(a); pentagram[2*i+1] = 100 + 50 * Math.sin(a); }
        assertEquals(5, new TkVector(TkVector.Shape.POLYGON, pentagram, 0, -1, 1).crossings());
        assertEquals(0, new TkVector(TkVector.Shape.POLYGON, new double[]{10,120,90,120,90,140,30,140,30,200,10,200}, 0, -1, 1).crossings());
        // {256/127}: a valid 256-point polygon whose tessellation took seconds on clients.
        Object[] star = new Object[512];
        for (int i = 0; i < 256; i++) { double a = 2 * Math.PI * 127 * i / 256; star[2*i] = 160 + 100 * Math.cos(a); star[2*i+1] = 120 + 100 * Math.sin(a); }
        assertThrows(IllegalArgumentException.class, () -> c.call("create", Map.of("type", "polygon", "coords", star, "options", Map.of("fill", 0))));
        // Unfilled, it is only stroked (linear work), so it is allowed.
        assertDoesNotThrow(() -> c.call("create", Map.of("type", "polygon", "coords", star, "options", Map.of("fill", -1, "outline", 0))));
        // An idle area chart: every sample lies on the baseline that closes the polygon.
        // Contacts are not crossings; this used to be rejected as 199 self-intersections.
        Object[] area = new Object[404];
        area[0] = 0; area[1] = 200;
        for (int i = 0; i < 200; i++) { area[2 + 2*i] = i + 1; area[3 + 2*i] = 200; }
        area[402] = 256; area[403] = 200;
        int chart = (Integer) assertDoesNotThrow(() -> c.call("create", Map.of("type", "polygon", "coords", area, "options", Map.of("fill", 0x00ff00))));
        // A move is a translation: the cached count is carried over, not recomputed.
        c.call("move", Map.of("selector", chart, "dx", 3, "dy", 0));
    }

    @Test void onlyKeysThatChangeTextDrawOnTheEditAllowance() {
        TkTarget t = new TkTarget(0, 0, 0, 0, "a", 1);
        int down = reobf.mpy4oc.tk.net.TkPackets.InputEvent.KEY_DOWN;
        assertTrue(reobf.mpy4oc.tk.net.TkNetwork.editsText(new reobf.mpy4oc.tk.net.TkPackets.InputEvent(t, down, 0, 0, 30, 'a', 0)));
        assertTrue(reobf.mpy4oc.tk.net.TkNetwork.editsText(new reobf.mpy4oc.tk.net.TkPackets.InputEvent(t, down, 0, 0, 14, 0, 0)), "Backspace");
        assertFalse(reobf.mpy4oc.tk.net.TkNetwork.editsText(new reobf.mpy4oc.tk.net.TkPackets.InputEvent(t, down, 0, 0, 203, 0, 0)), "Left arrow");
        assertFalse(reobf.mpy4oc.tk.net.TkNetwork.editsText(new reobf.mpy4oc.tk.net.TkPackets.InputEvent(t, down, 0, 0, 42, 0, 1)), "Shift alone");
        assertFalse(reobf.mpy4oc.tk.net.TkNetwork.editsText(new reobf.mpy4oc.tk.net.TkPackets.InputEvent(t,
                reobf.mpy4oc.tk.net.TkPackets.InputEvent.KEY_UP, 0, 0, 30, 'a', 0)), "key release");
    }

    @Test void onlyPlayersTheOwningComputerAcceptsMayOperateTheDisplay() throws Exception {
        TileTkDisplay tile = new TileTkDisplay();
        Object context = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {li.cil.oc.api.machine.Context.class, li.cil.oc.api.network.Environment.class},
                (p, m, a) -> m.getName().equals("canInteract") ? "alice".equals(a[0]) : null);
        li.cil.oc.api.network.Node machine = node(Map.of("host", context));
        li.cil.oc.api.network.Network network = (li.cil.oc.api.network.Network) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {li.cil.oc.api.network.Network.class},
                (p, m, a) -> m.getName().equals("node") && "computer".equals(a[0]) ? machine : null);
        java.lang.reflect.Field field = li.cil.oc.api.prefab.TileEntityEnvironment.class.getDeclaredField("node");
        field.setAccessible(true); field.set(tile, node(Map.of("network", network, "address", "display")));
        assertFalse(tile.canControl("alice"), "no application yet: nobody operates it");
        tile.application().call("computer", "claim", "", new Object[0]);
        assertTrue(tile.canControl("alice"));
        assertFalse(tile.canControl("mallory"), "not on the computer's user list");
        tile.application().call("elsewhere", "claim", "", new Object[0]);
        assertFalse(tile.canControl("alice"), "owner not reachable on this network");
    }

    private static li.cil.oc.api.network.Node node(Map<String, Object> answers) {
        return (li.cil.oc.api.network.Node) java.lang.reflect.Proxy.newProxyInstance(TkHardeningTest.class.getClassLoader(),
                new Class<?>[] {li.cil.oc.api.network.Node.class}, (p, m, a) -> answers.get(m.getName()));
    }

    @Test void viewersCapTextEditsPerTickAndThrottleResyncs() {
        TkViewers.Viewer<String> viewer = new TkViewers<String>().open(UUID.randomUUID(), "one", 0);
        // Token bucket: a burst (keys bunched after lag) is absorbed, then 4 per tick.
        for (int i = 0; i < TkViewers.Viewer.EDIT_BURST; i++) assertTrue(viewer.allowInput(5, 32, true), "burst edit " + i);
        assertFalse(viewer.allowInput(5, 32, true), "the bucket is empty");
        assertTrue(viewer.allowInput(5, 32, false), "pointer input still has budget");
        for (int i = 0; i < TkViewers.Viewer.EDITS_PER_TICK; i++) assertTrue(viewer.allowInput(6, 32, true), "refill " + i);
        assertFalse(viewer.allowInput(6, 32, true), "only EDITS_PER_TICK come back per tick");
        TkScene scene = TkScene.defaults();
        viewer.sent(1, scene);
        assertTrue(viewer.requestResync(10)); assertTrue(viewer.stale(1));
        assertFalse(viewer.requestResync(10 + TkViewers.Viewer.RESYNC_INTERVAL - 1));
        assertNotNull(viewer.prepare(new TkTarget(0, 0, 0, 0, "a", viewer.session), 1, scene));
        assertTrue(viewer.stale(1), "prepare alone does not count as sent");
        viewer.sent(1, scene); assertFalse(viewer.stale(1));
        assertTrue(viewer.requestResync(10 + TkViewers.Viewer.RESYNC_INTERVAL));
    }
}
