package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.netty.buffer.*;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkControlsTest {
    @Test void wrapKeepsNewlinesAndUnicodeAndUsesMeasuredWidth() {
        assertEquals("\nAB\n中文\n", TkText.wrap("\nAB 中文\n", 32));
        assertEquals("😀\n😀", TkText.wrap("😀😀", TkFontMetrics.width("😀")));
        TkTree tree = new TkTree(); Label label = new Label(tree.root(), "one two three"); label.wraplength(40); label.pack();
        assertTrue(label.bounds().height >= 48);
        Message message = new Message(tree.root()); TkOptions.configure(message, Map.of("text", "abcdefghijk", "width", 32)); message.pack();
        assertEquals(34, message.bounds().width); assertEquals(50, message.bounds().height);
    }

    @Test void checkAndRadioActivateBeforeCallbackAndRespectDisabledState() {
        TkTree tree = new TkTree(); Checkbutton check = new Checkbutton(tree.root(), false); check.command(9); check.pack();
        TkRect r = check.bounds(); tree.pointerDown(1, r.x + 2, r.y + 2); TkTree.Command command = tree.pointerUp(1, r.x + 2, r.y + 2);
        assertTrue(check.selected()); assertEquals(9, command.callback);
        Checkbutton a = new Checkbutton(tree.root(), true), b = new Checkbutton(tree.root(), true);
        a.group = b.group = 22; a.pack(); b.pack(); a.selected(true); b.selected(true);
        assertFalse(a.selected()); assertTrue(b.selected());
        check.enabled(false); assertNull(tree.pointerUp(2, r.x + 2, r.y + 2)); assertTrue(check.selected());
    }

    @Test void tenThousandRowsKeepPaintAndAppendCostBoundedByViewport() {
        TkTree tree = new TkTree(); Listbox list = new Listbox(tree.root()); list.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.BOTH, true, TkPack.Anchor.CENTER, 0,0,0,0,0,0));
        long start = System.nanoTime();
        for (int first = 0; first < 10000; first += 100) {
            String[] rows = new String[100]; for (int i = 0; i < 100; i++) rows[i] = "日志 " + (first + i) + " abcdefghijklmnopqrstuvwxyz";
            list.insert(list.size(), rows); assertTrue(tree.render().items.size() <= 17);
        }
        assertEquals(10000, list.size());
        TkDisplayList before = tree.render(); list.insert(list.size(), "outside viewport"); assertEquals(before, tree.render());
        list.see(9999); TkDisplayList end = tree.render(); assertTrue(end.items.stream().anyMatch(i -> i.text.contains("9999")));
        assertFalse(end.items.stream().anyMatch(i -> i.part == 1));
        long ms = (System.nanoTime() - start) / 1000000; System.out.println("TK 10000 rows + 100 renders: " + ms + " ms, records=" + end.items.size());
        assertTrue(ms < 5000, "large list should not scan all rows on every render");
    }

    @Test void insertDeleteAdjustSelectionAndRejectInvalidBatchesAtomically() {
        Listbox list = new Listbox(new TkTree().root()); list.pack(); list.insert(0, "a", "b", "c"); list.selection(1, 2, true);
        list.insert(0, "x"); assertArrayEquals(new Object[] {2,3}, list.curselection());
        list.delete(1, 2); assertArrayEquals(new Object[] {1}, list.curselection()); assertEquals("c", list.get(1));
        assertThrows(IllegalArgumentException.class, () -> list.insert(0, "valid", "bad\nrow")); assertEquals(2, list.size());
        list.enabled(false); list.delete(0, 10); list.insert(0, "blocked"); assertEquals(2, list.size());
    }

    @Test void scrollingSelectionAndCallbacksAreCoalescedAndUseTotalFractions() {
        TkTree tree = new TkTree(); Listbox list = new Listbox(tree.root()); list.lines = 4; list.yscrollcommand = 17; list.selectmode = "extended"; list.pack();
        String[] rows = new String[100]; Arrays.fill(rows, "line"); list.insert(0, rows); tree.takeCommands();
        for (int i = 0; i < 10; i++) { list.scroll(false, 1, false); tree.render(); }
        List<TkTree.Command> callbacks = tree.takeCommands(); assertEquals(1, callbacks.size());
        assertArrayEquals(new Object[] {.1, .14}, callbacks.get(0).args);
        TkRect r = list.bounds(); tree.pointerDown(1, r.x + 5, r.y + 5); tree.pointerDrag(1, r.x + 5, r.y + 5 + 32);
        assertArrayEquals(new Object[] {10,11,12}, list.curselection()); assertTrue(list.selectionVersion > 0);
        tree.key(208, 1, 0); assertEquals(13, list.active);
    }

    @Test void scrollbarThumbDragAndScaleResolutionProduceBoundedCommands() {
        TkTree tree = new TkTree(); Scrollbar bar = new Scrollbar(tree.root()); bar.command = 5; bar.pack(); bar.set(.2, .4);
        TkRect b = bar.bounds(); tree.pointerDown(1, b.x + 5, b.y + bar.thumbStart() + 2);
        tree.pointerDrag(1, b.x + 5, b.y + b.height); TkTree.Command move = tree.takeCommands().get(0);
        assertEquals("moveto", move.args[0]); assertTrue((Double) move.args[1] <= 1);
        Scale scale = new Scale(tree.root()); TkOptions.configure(scale, Map.of("orient", "horizontal", "from", 10, "to", -10, "resolution", 2, "command", 7)); scale.pack();
        TkRect s = scale.bounds(); tree.pointerDown(2, s.x + s.width - 1, s.y + 5); assertEquals(-10, scale.get());
        tree.key(203, 0, 0); assertEquals(-8, scale.get());
        assertEquals(1, tree.takeCommands().size());
        double old = scale.from; assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(scale, Map.of("from", Double.NaN))); assertEquals(old, scale.from);
    }

    @Test void compoundControlsRoundTripNbtIncludingLargeRowsAndDisabledLists() {
        TkTree tree = new TkTree(); Listbox list = new Listbox(tree.root()); list.pack();
        for (int i = 0; i < 100; i++) list.insert(list.size(), "中文 " + i);
        list.selection(30, 35, true); list.see(60); list.enabled(false);
        Checkbutton check = new Checkbutton(tree.root(), false); check.pack(); check.selected(true);
        Scale scale = new Scale(tree.root()); scale.pack(); scale.set(70, false);
        TkDisplayList expected = tree.render(); NBTTagCompound nbt = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(nbt, 320, 240, () -> {});
        assertEquals(expected, loaded.render()); assertEquals(100, ((Listbox) loaded.get(list.id)).size());
        assertArrayEquals(list.curselection(), ((Listbox) loaded.get(list.id)).curselection());
    }

    @Test void patchesTransmitOnlyChangedRowsAndMissingRecordsRequireResync() {
        TkTree tree = new TkTree(); Listbox list = new Listbox(tree.root()); list.pack(); list.insert(0, "a", "b", "c");
        TkScene before = scene(tree.render()); list.selection(1, 1, true); TkScene after = scene(tree.render());
        TkDisplayPatch patch = TkDisplayPatch.between(before.display, after.display); assertEquals(1, patch.changed.items.size());
        TkTarget target = new TkTarget(0, 0, 0, 0, "screen", 1);
        TkPackets.SceneSync outgoing = new TkPackets.SceneSync(target, 1, 2, TkPackets.DISPLAY, after); outgoing.displayPatch = patch;
        ByteBuf bytes = Unpooled.buffer(); outgoing.toBytes(bytes); int size = bytes.readableBytes(); TkPackets.SceneSync decoded = new TkPackets.SceneSync(); decoded.fromBytes(bytes); bytes.release();
        assertEquals(after, decoded.apply(before)); System.out.println("TK selected-row patch: " + size + " bytes");
        TkClientState empty = new TkClientState(new TkPackets.OpenGui(target, 1, TkScene.defaults())); assertFalse(empty.accept(decoded));
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayPatch(new long[] {1L<<32,1L<<32}, TkDisplayList.EMPTY));
    }
    private TkScene scene(TkDisplayList display) { return new TkScene(320, 240, 0, "test", true, display); }
}
