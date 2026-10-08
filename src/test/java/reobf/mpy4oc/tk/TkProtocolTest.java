package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkProtocolTest {
    private TkTarget target(long session) { return new TkTarget(0, 10, 64, -20, "display-address", session); }

    @Test
    void fullScenesAndUnicodeClipboardSurviveWireEncoding() throws Exception {
        TkScene scene = new TkScene(800, 600, 0x123456, "共享画布 🌍", false);
        TkPackets.OpenGui open = roundTrip(new TkPackets.OpenGui(target(1), 5, scene));
        assertEquals(target(1), open.target);
        assertEquals(scene, open.scene);
        assertEquals(5, open.revision);
        assertTrue(open.isFull());
        TkPackets.Clipboard clipboard = roundTrip(new TkPackets.Clipboard(target(1), "中文\nsecond line 🌍"));
        assertEquals("中文\nsecond line 🌍", clipboard.text);
        TkPackets.InputEvent input = roundTrip(new TkPackets.InputEvent(target(1), TkPackets.InputEvent.KEY_DOWN,
                0, 0, 30, '中', 3));
        assertEquals('中', input.character);
        assertEquals(3, input.modifiers);
        TkPackets.InputEvent supplementary = roundTrip(new TkPackets.InputEvent(target(1), TkPackets.InputEvent.KEY_DOWN, 0, 0, 0, 0x1F600, 0));
        assertEquals(0x1F600, supplementary.character);
        TkPackets.InputEvent drag = roundTrip(new TkPackets.InputEvent(target(1), TkPackets.InputEvent.DRAG, -20, 1400, 0, 0, 1));
        assertEquals(-20, drag.x); assertEquals(1400, drag.y);
        TkPackets.InputEvent wheel = roundTrip(new TkPackets.InputEvent(target(1), TkPackets.InputEvent.WHEEL, 30, 40, -120, 0, 2));
        assertEquals(-120, wheel.code);
        assertEquals("选中", roundTrip(new TkPackets.CopyResult(target(1), 17, "选中")).text);
        assertTrue(roundTrip(new TkPackets.CopyRequest(target(1), 17, true)).cut);
        Entry entry = new Entry(new TkTree().root()); entry.text("中文 selected"); entry.pack(); entry.selectionRange(1, 5);
        TkScene selected = new TkScene(320, 240, 0, "Selection", true, entry.tree.render());
        assertTrue(selected.display.items.get(0).selectionWidth > 0);
        assertEquals(selected, roundTrip(new TkPackets.OpenGui(target(1), 2, selected)).scene);
    }

    @Test
    void negativeAndOversizedGeometrySurvivesFullAndDeltaEncoding() throws Exception {
        TkTree tree = new TkTree(); Label label = new Label(tree.root(), "裁剪测试");
        label.place(new TkPlace(-30, -12, 700, 280, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
        TkScene scene = new TkScene(320, 240, 0, "Place", true, tree.render());
        assertEquals(scene, roundTrip(new TkPackets.OpenGui(target(1), 1, scene)).scene);
        TkViewers.Viewer<String> viewer = new TkViewers<String>().open(UUID.randomUUID(), "one");
        viewer.lastScene = scene; viewer.revision = 1;
        TkClientState client = new TkClientState(new TkPackets.OpenGui(target(viewer.session), 1, scene));
        label.place(new TkPlace(-100, -20, 1000, 300, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
        TkScene moved = new TkScene(320, 240, 0, "Place", true, tree.render());
        assertTrue(client.accept(roundTrip(viewer.update(target(viewer.session), 2, moved))));
        assertEquals(moved, client.scene());
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayList.Item(1, TkWidget.Kind.FRAME,
                new TkRect(Integer.MIN_VALUE, 0, 10, 10), new TkRect(0, 0, 10, 10), 0, 0, 0, 0, 0, 0, ""));
    }

    @Test
    void twoViewersReceiveTheSameDeltaAndLateJoinGetsCurrentState() throws Exception {
        TkScene initial = TkScene.defaults();
        TkScene updated = new TkScene(640, 480, 0xCAFE00, "Live", false);
        TkViewers<String> viewers = new TkViewers<>();
        for (String owner : new String[] {"one", "two"}) {
            TkViewers.Viewer<String> viewer = viewers.open(UUID.randomUUID(), owner);
            viewer.lastScene = initial;
            viewer.revision = 1;
            TkClientState client = new TkClientState(new TkPackets.OpenGui(target(viewer.session), 1, initial));
            TkPackets.SceneSync delta = roundTrip(viewer.update(target(viewer.session), 4, updated));
            assertFalse(delta.isFull());
            assertEquals(TkPackets.SIZE | TkPackets.BACKGROUND | TkPackets.TITLE | TkPackets.SURFACE, delta.mask);
            assertTrue(client.accept(delta));
            assertEquals(updated, client.scene());
            assertEquals(4, client.revision());
        }
        assertEquals(updated, new TkClientState(roundTrip(new TkPackets.OpenGui(target(50), 4, updated))).scene());
    }

    @Test
    void cancelledChangesDoNotAdvanceAnUnsentRevision() {
        TkViewers.Viewer<String> viewer = new TkViewers<String>().open(UUID.randomUUID(), "one");
        viewer.lastScene = TkScene.defaults();
        viewer.revision = 1;
        assertNull(viewer.update(target(viewer.session), 3, TkScene.defaults()));
        TkScene updated = new TkScene(320, 240, 0xFFFFFF, TkScene.DEFAULT_TITLE, true);
        TkPackets.SceneSync delta = viewer.update(target(viewer.session), 4, updated);
        assertEquals(1, delta.baseRevision);
        assertEquals(TkPackets.BACKGROUND, delta.mask);
        TkClientState client = new TkClientState(new TkPackets.OpenGui(target(viewer.session), 1, TkScene.defaults()));
        assertTrue(client.accept(delta));
        assertEquals(updated, client.scene());
    }

    @Test
    void missingDeltaRequestsResyncWhileStalePacketsAndOtherSessionsAreIgnored() {
        TkClientState client = new TkClientState(new TkPackets.OpenGui(target(1), 1, TkScene.defaults()));
        TkScene changed = new TkScene(320, 240, 0xABCDEF, "Updated", true);
        assertFalse(client.accept(new TkPackets.SceneSync(target(1), 2, 3, TkPackets.BACKGROUND, changed)));
        assertEquals(TkScene.defaults(), client.scene());
        assertTrue(client.accept(new TkPackets.SceneSync(target(1), -1, 3, TkPackets.ALL, changed)));
        assertTrue(client.accept(new TkPackets.SceneSync(target(1), 1, 2, TkPackets.BACKGROUND, TkScene.defaults())));
        assertTrue(client.accept(new TkPackets.SceneSync(target(2), -1, 4, TkPackets.ALL, TkScene.defaults())));
        assertEquals(changed, client.scene());
        assertEquals(3, client.revision());
    }

    @Test
    void oldCloseCannotRemoveReopenedSessionAndInputBudgetRenewsEachTick() {
        TkViewers<String> viewers = new TkViewers<>();
        UUID player = UUID.randomUUID();
        TkViewers.Viewer<String> old = viewers.open(player, "old", 200);
        TkViewers.Viewer<String> current = viewers.open(player, "new", 200);
        assertNull(viewers.find(player, old.session));
        assertFalse(viewers.close(player, old.session));
        assertSame(current, viewers.find(player, current.session));
        assertNull(viewers.find(UUID.randomUUID(), current.session));
        assertTrue(current.allowInput(200, 4096));
        assertTrue(current.allowInput(200, 4096));
        assertFalse(current.allowInput(200, 1));
        for (int i = 0; i < 32; i++) assertTrue(current.allowInput(201, 0));
        assertFalse(current.allowInput(201, 0));
        assertTrue(current.allowInput(202, 0));
        assertFalse(current.expired(302));
        assertTrue(current.expired(303));
        assertTrue(viewers.close(player, current.session));
        assertTrue(viewers.isEmpty());
    }

    @Test
    void malformedOrOversizedPacketsAreRejectedBeforeStateChanges() {
        assertThrows(IllegalArgumentException.class, () -> new TkTarget(0, 0, 256, 0, "address", 1));
        assertThrows(IllegalArgumentException.class, () -> new TkScene(63, 240, 0, "title", true));
        assertThrows(IllegalArgumentException.class, () -> new TkScene(320, 240, -1, "title", true));
        assertThrows(IllegalArgumentException.class, () -> new TkScene(320, 240, 0, "line\nbreak", true));
        assertThrows(IllegalArgumentException.class, () -> new TkScene(320, 240, 0, "中".repeat(171), true));
        assertThrows(IllegalArgumentException.class, () -> new TkPackets.SceneSync(target(1), -1, 1, 16, TkScene.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new TkPackets.InputEvent(target(1), 0, 1024, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TkPackets.InputEvent(target(1), TkPackets.InputEvent.DRAG, -1025, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TkPackets.InputEvent(target(1), TkPackets.InputEvent.KEY_DOWN, 0, 0, 0, 0xD800, 0));
        assertThrows(IllegalArgumentException.class, () -> new TkPackets.InputEvent(target(1), TkPackets.InputEvent.WHEEL, 0, 0, 1201, 0, 0));
        ByteBuf buffer = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> new TkPackets.Clipboard(target(1), "中".repeat(1366)).toBytes(buffer));
            buffer.clear();
            new TkPackets.Clipboard(target(1), "ok").toBytes(buffer);
            buffer.setByte(buffer.writerIndex() - 2, 0xC0);
            assertThrows(IllegalArgumentException.class, () -> new TkPackets.Clipboard().fromBytes(buffer));
            buffer.clear();
            new TkPackets.CloseRequest(target(1)).toBytes(buffer);
            buffer.writeByte(42);
            assertThrows(IllegalArgumentException.class, () -> new TkPackets.CloseRequest().fromBytes(buffer));
        } finally { buffer.release(); }
    }

    @SuppressWarnings("unchecked")
    private <T extends IMessage> T roundTrip(T packet) throws Exception {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.toBytes(buffer);
            T decoded = (T) packet.getClass().getDeclaredConstructor().newInstance();
            decoded.fromBytes(buffer);
            assertFalse(buffer.isReadable());
            return decoded;
        } finally { buffer.release(); }
    }
}
