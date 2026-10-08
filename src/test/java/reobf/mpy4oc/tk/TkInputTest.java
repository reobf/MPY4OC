package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkInputTest {
    private Entry entry(String text) {
        TkTree tree = new TkTree(); Entry entry = new Entry(tree.root()); entry.text(text); entry.pack(); tree.focus(entry.id); return entry;
    }

    @Test void shiftSelectionAndReplacementUseCodePointsAndKeepVariableUpdatesStable() {
        Entry e = entry("A😀中文Z"); e.cursor(1);
        e.key(205, 0, 1); e.key(205, 0, 1);
        assertEquals("😀中", e.selectedText()); assertEquals(3, e.cursor());
        e.text(e.text()); assertEquals("😀中", e.selectedText(), "same-value variable refresh preserves selection");
        e.key(0, '新', 0); assertEquals("A新文Z", e.text()); assertEquals(2, e.cursor()); assertFalse(e.selectionPresent());
        e.key(30, 1, 2); assertEquals(e.text(), e.selectedText());
        e.key(14, 0, 0); assertEquals("", e.text()); assertEquals(0, e.cursor());
    }

    @Test void programmaticEditsAdjustCursorSelectionAndAnchorRatherThanJumpingToInsertion() {
        Entry e = entry("abcd"); e.cursor(1); e.selectionFrom(1); e.selectionRange(1, 3);
        e.insert(4, "😀"); assertEquals(1, e.cursor()); assertEquals("bc", e.selectedText());
        e.insert(0, "X"); assertEquals(2, e.cursor()); assertEquals(2, e.anchor); assertEquals("bc", e.selectedText());
        e.delete(1, 3); assertEquals("Xcd😀", e.text()); assertEquals(1, e.cursor()); assertEquals("c", e.selectedText());
        e.selectionAdjust(4); assertEquals("cd😀", e.selectedText());
        e.selectionClear(); assertThrows(IllegalArgumentException.class, e::selectionFirst);
    }

    @Test void pasteReplacesSelectionAtomicallyAndDoesNotEraseOnRejectedInput() {
        Entry e = entry("x".repeat(1024)); e.selectionRange(10, 11);
        e.paste("中"); assertEquals(1024, e.length()); assertEquals("x", e.selectedText());
        e.selectionRange(10, 13); e.paste("中😀");
        assertEquals(1022, e.length()); assertEquals('中', e.text().charAt(10)); assertFalse(e.selectionPresent());
        e.text("abc"); e.selectionRange(1, 2); e.paste("\u0000\ud800");
        assertEquals("abc", e.text()); assertEquals("b", e.selectedText());
        e.paste("甲\n乙"); assertEquals("a甲 乙c", e.text());
    }

    @Test void readonlyStillSelectsAndCopiesButCannotCutOrPaste() {
        Entry e = entry("read only"); e.readonly = true; e.key(30, 0, 2);
        assertEquals("read only", e.tree.copySelection(true)); assertEquals("read only", e.text());
        e.paste("no"); e.key(14, 0, 0); assertEquals("read only", e.text());
        assertEquals(-1, e.tree.render().items.get(0).caretX);
        e.enabled(false); assertEquals("", e.tree.copySelection(false));
    }

    @Test void draggingIsCapturedPerViewerAndStationaryEdgeDragsScroll() {
        Entry e = entry("0123456789".repeat(12)); e.textSize(8, 1); TkRect r = e.bounds();
        int start = r.x + e.padx + e.border;
        e.tree.pointerDown(11, start, r.y + 5);
        assertNull(e.tree.pointerDrag(12, start + 20, r.y + 5)); assertFalse(e.selectionPresent());
        e.tree.pointerDrag(11, r.x + r.width + 4, r.y + 5); int oldScroll = e.scrollX;
        e.tree.pointerDrag(11, r.x + r.width + 4, r.y + 5);
        assertTrue(e.scrollX >= oldScroll); assertTrue(e.selectionPresent());
        e.tree.pointerDown(12, start, r.y + 5); assertNull(e.tree.capture(11));
        e.tree.pointerDrag(11, 2047, 0); assertFalse(e.selectionPresent());
        e.forget(); assertNull(e.tree.capture(12));
        e.pack(); e.tree.pointerDown(12, start, r.y + 5); e.destroy(); assertNull(e.tree.capture(12));
    }

    @Test void wordNavigationWheelScrollingAndKeyboardReturnToCaret() {
        Entry e = entry("one two three four five six seven"); e.textSize(8, 1); e.cursor(0);
        e.key(205, 0, 2); assertEquals(4, e.cursor());
        e.key(205, 0, 3); assertEquals("two ", e.selectedText());
        e.selectionClear(); e.cursor(0); TkRect r = e.bounds();
        e.tree.wheel(r.x + 2, r.y + 2, -120); e.tree.render(); assertTrue(e.scrollX > 0);
        e.key(199, 0, 0); e.tree.render(); assertEquals(0, e.scrollX);
        e.key(207, 0, 1); assertEquals(e.text(), e.selectedText());
    }

    @Test void selectionAndHorizontalViewPersistButMouseCaptureDoesNot() {
        Entry e = entry("0123456789".repeat(10)); e.textSize(6, 1); e.cursor(70); e.selectionFrom(10); e.selectionRange(10, 70);
        e.tree.render(); e.xviewTo(.3); NBTTagCompound saved = TkTreePersistence.save(e.tree);
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {}); Entry restored = (Entry) loaded.get(e.id);
        assertEquals(e.selectedText(), restored.selectedText()); assertEquals(10, restored.anchor); assertEquals(70, restored.cursor());
        assertEquals(e.scrollX, restored.scrollX); assertEquals(e.tree.render(), loaded.render()); assertNull(loaded.capture(1));
        // Step-4 NBT has only the cursor, with the same schema version.
        NBTTagCompound old = saved.getTagList("nodes", 10).getCompoundTagAt(1);
        old.removeTag("selection"); old.removeTag("selectionAnchor"); old.removeTag("scrollX");
        assertFalse(((Entry) TkTreePersistence.load(saved, 320, 240, () -> {}).get(e.id)).selectionPresent());
    }

    @Test void motionCoalescesFramesAndReleaseFlushesFinalSignedDragEndpoint() {
        List<TkPackets.InputEvent> packets = new ArrayList<>();
        TkPointerInput pointer = new TkPointerInput(new TkTarget(0, 0, 64, 0, "screen", 1), packets::add);
        for (int x = 0; x < 100; x++) pointer.sample(x, 10, true, 0);
        assertTrue(packets.isEmpty()); pointer.tick(); assertEquals(1, packets.size()); assertEquals(99, packets.get(0).x);
        pointer.tick(); assertEquals(1, packets.size(), "stationary hover sends no packets");
        pointer.press(0); pointer.sample(-25, 10, false, 1); pointer.release(0);
        assertEquals(TkPackets.InputEvent.DRAG, packets.get(2).kind); assertEquals(-25, packets.get(2).x);
        assertEquals(TkPackets.InputEvent.MOUSE_UP, packets.get(3).kind); pointer.tick(); assertEquals(4, packets.size());
    }

    @Test void onlyLatestRequestedClipboardReplyMayBeAcceptedOnce() {
        TkTarget target = new TkTarget(0, 0, 64, 0, "screen", 1);
        TkClientState state = new TkClientState(new TkPackets.OpenGui(target, 1, TkScene.defaults()));
        assertNull(state.acceptCopy(new TkPackets.CopyResult(target, 1, "unsolicited")));
        int old = state.requestCopy(), current = state.requestCopy();
        assertNull(state.acceptCopy(new TkPackets.CopyResult(target, old, "stale")));
        assertNull(state.acceptCopy(new TkPackets.CopyResult(new TkTarget(0, 0, 64, 0, "screen", 2), current, "other session")));
        assertEquals("中文", state.acceptCopy(new TkPackets.CopyResult(target, current, "中文")));
        assertNull(state.acceptCopy(new TkPackets.CopyResult(target, current, "duplicate")));
    }
}
