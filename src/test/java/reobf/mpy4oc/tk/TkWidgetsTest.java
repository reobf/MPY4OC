package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reobf.mpy4oc.tk.net.TkPackets;

class TkWidgetsTest {
    private TkTree tree() { TkTree tree = new TkTree(); tree.resize(200, 120); return tree; }
    private Frame frame(Frame parent, int width, int height, TkPack.Side side, TkPack.Fill fill, boolean expand) {
        Frame frame = new Frame(parent); frame.size(width, height); frame.pack(new TkPack(side, fill, expand, 0, 0)); return frame;
    }
    private void rect(TkWidget widget, int x, int y, int width, int height) {
        assertEquals(new TkRect(x, y, width, height), widget.bounds());
    }

    @Test void fourSidesConsumeParcelsInPackingOrder() {
        TkTree tree = tree();
        Frame top = frame(tree.root(), 10, 20, TkPack.Side.TOP, TkPack.Fill.X, false);
        Frame bottom = frame(tree.root(), 10, 10, TkPack.Side.BOTTOM, TkPack.Fill.X, false);
        Frame left = frame(tree.root(), 20, 10, TkPack.Side.LEFT, TkPack.Fill.Y, false);
        Frame right = frame(tree.root(), 30, 10, TkPack.Side.RIGHT, TkPack.Fill.Y, false);
        Frame middle = frame(tree.root(), 10, 10, TkPack.Side.TOP, TkPack.Fill.BOTH, true);
        rect(top, 0, 0, 200, 20); rect(bottom, 0, 110, 200, 10);
        rect(left, 0, 20, 20, 90); rect(right, 170, 20, 30, 90); rect(middle, 20, 20, 150, 90);
    }

    @Test void expandAllocatesSpaceWhileFillControlsWidgetSize() {
        TkTree tree = tree();
        Frame first = frame(tree.root(), 40, 10, TkPack.Side.TOP, TkPack.Fill.NONE, true);
        Frame second = frame(tree.root(), 40, 10, TkPack.Side.TOP, TkPack.Fill.NONE, true);
        rect(first, 80, 25, 40, 10); rect(second, 80, 85, 40, 10);
        first.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.BOTH, true, 0, 0));
        second.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.BOTH, true, 0, 0));
        rect(first, 0, 0, 200, 60); rect(second, 0, 60, 200, 60);
    }

    @Test void mixedAxesReserveWidthForLaterTopPackedContent() {
        TkTree tree = tree();
        Frame left = frame(tree.root(), 40, 10, TkPack.Side.LEFT, TkPack.Fill.Y, true);
        Frame top = frame(tree.root(), 100, 20, TkPack.Side.TOP, TkPack.Fill.X, false);
        Frame right = frame(tree.root(), 40, 10, TkPack.Side.RIGHT, TkPack.Fill.BOTH, true);
        // The first expandable side may take only 100 pixels, leaving the top its requested width.
        rect(left, 30, 0, 40, 120); rect(top, 100, 0, 100, 20); rect(right, 100, 20, 100, 100);
    }

    @Test void asymmetricPaddingInternalPaddingAndAnchorUseDistinctSpaces() {
        TkTree tree = tree();
        Frame child = new Frame(tree.root()); child.size(20, 10);
        child.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.NONE, true, TkPack.Anchor.SE, 3, 7, 5, 11, 2, 4));
        rect(child, 169, 91, 24, 18);
        child.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.BOTH, true, TkPack.Anchor.NW, 3, 7, 5, 11, 2, 4));
        rect(child, 3, 5, 190, 104);
    }

    @Test void nestedNaturalSizesPropagateUsingOcUnicodeMetrics() {
        TkTree tree = tree();
        Frame parent = new Frame(tree.root()); parent.size(100, 100); parent.padding(3, 4, 2); parent.pack();
        Label label = new Label(parent, "A中\nBC");
        label.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.NONE, false, TkPack.Anchor.CENTER, 5, 5, 6, 6, 2, 3));
        assertEquals(32, TkFontMetrics.width("A中B"));
        assertEquals(16, TkFontMetrics.width("𠀀"));
        assertEquals(32, TkFontMetrics.height(label.text()));
        rect(parent, 75, 0, 50, 64); rect(label, 85, 12, 30, 40);
        parent.propagate(false); parent.size(80, 50);
        rect(parent, 60, 0, 80, 50);
        assertTrue(label.bounds().height < 40);
    }

    @Test void exhaustedCavitiesHideChildrenAndForgettingKeepsOtherPackOrder() {
        TkTree tree = tree();
        Frame first = new Frame(tree.root()), second = new Frame(tree.root());
        first.size(20, 120); second.size(20, 30);
        second.pack(); first.pack(); // Packing order is separate from creation order.
        rect(second, 90, 0, 20, 30); rect(first, 90, 30, 20, 90);
        second.forget(); rect(first, 90, 0, 20, 120); assertFalse(second.mapped());
        second.pack(); assertFalse(second.mapped()); assertEquals(1, tree.render().items.size());
        tree.clear(); assertTrue(tree.render().items.isEmpty());
    }

    @Test void focusAndCommandsRespectHitTestingSessionAndDisabledState() {
        TkTree tree = tree();
        Button first = new Button(tree.root(), "一").command(7), second = new Button(tree.root(), "Two").command(8);
        first.pack(); second.pack();
        TkRect r = first.bounds();
        tree.pointerDown(10, r.x + 2, r.y + 2);
        assertEquals(first.id, tree.focused());
        assertNull(tree.pointerUp(11, r.x + 2, r.y + 2)); // Another player cannot release this press.
        TkTree.Command command = tree.pointerUp(10, r.x + 2, r.y + 2);
        assertEquals(first.id, command.widget); assertEquals(7, command.callback);
        assertNull(tree.pointerUp(10, r.x + 2, r.y + 2));
        tree.key(15, 0); assertEquals(second.id, tree.focused()); assertEquals(8, tree.key(28, 0).callback);
        tree.key(15, 1); assertEquals(first.id, tree.focused());
        tree.pointerDown(10, r.x + 2, r.y + 2); first.enabled(false);
        assertNull(tree.pointerUp(10, r.x + 2, r.y + 2)); assertEquals(0, tree.focused());
        tree.focusNext(false); assertEquals(second.id, tree.focused());
        second.forget(); tree.render(); assertEquals(0, tree.focused());
    }

    @Test void immutablePaintSnapshotsCacheUntilDirtyAndSurviveFullAndDeltaPackets() {
        AtomicInteger changes = new AtomicInteger(); TkTree tree = new TkTree(changes::incrementAndGet);
        Label label = new Label(tree.root(), "中文 🌍"); label.pack();
        TkDisplayList old = tree.render(); assertSame(old, tree.render());
        label.text("更新"); TkDisplayList updated = tree.render();
        assertNotEquals(old, updated); assertEquals("中文 🌍", old.items.get(0).text); assertTrue(changes.get() > 0);
        assertThrows(UnsupportedOperationException.class, () -> updated.items.clear());
        TkTarget target = new TkTarget(0, 1, 64, 2, "display", 1);
        TkScene before = new TkScene(320, 240, 0, true, old);
        TkScene after = new TkScene(320, 240, 0, true, updated);
        TkClientState client = new TkClientState(new TkPackets.OpenGui(target, 1, before));
        ByteBuf bytes = Unpooled.buffer();
        try {
            new TkPackets.OpenGui(target, 1, before).toBytes(bytes);
            TkPackets.OpenGui open = new TkPackets.OpenGui(); open.fromBytes(bytes); assertEquals(before, open.scene);
            bytes.clear();
            int mask = TkPackets.changes(before, after); assertEquals(TkPackets.DISPLAY, mask);
            new TkPackets.SceneSync(target, 1, 2, mask, after).toBytes(bytes);
            TkPackets.SceneSync delta = new TkPackets.SceneSync(); delta.fromBytes(bytes);
            assertTrue(client.accept(delta)); assertEquals(after, client.scene());
        } finally { bytes.release(); }
    }

    @Test void clippingAndDestructionPreventInvisibleOrRemovedWidgetsReceivingInput() {
        TkTree tree = tree();
        Frame frame = new Frame(tree.root()); frame.size(60, 40); frame.propagate(false); frame.padding(0, 0, 4); frame.pack();
        Button button = new Button(frame, "A very wide button").command(1); button.pack(new TkPack(TkPack.Side.TOP, TkPack.Fill.BOTH, true, 0, 0));
        TkRect bounds = button.bounds(); assertEquals(frame.id, tree.hit(frame.bounds().x, 0).id);
        assertEquals(button.id, tree.hit(bounds.x + 1, bounds.y + 1).id);
        tree.pointerDown(1, bounds.x + 1, bounds.y + 1);
        int oldId = button.id; frame.destroy();
        assertNull(tree.get(oldId)); assertNull(tree.pointerUp(1, bounds.x + 1, bounds.y + 1));
        assertThrows(IllegalArgumentException.class, button::pack);
        assertTrue(new Button(tree.root(), "New").id > oldId);
    }

    @Test void invalidPaintAndResourceLimitsFailWithoutPartiallyCreatingWidgets() {
        TkTree tree = tree();
        for (int i = 0; i < 16; i++) new Label(tree.root(), "x".repeat(1024));
        int count = tree.root().children().size();
        assertThrows(IllegalArgumentException.class, () -> new Label(tree.root(), "x"));
        assertEquals(count, tree.root().children().size());
        assertThrows(IllegalArgumentException.class, () -> new Label(tree.root(), "bad\ttext"));
        TkRect rect = new TkRect(0, 0, 10, 10);
        TkDisplayList.Item item = new TkDisplayList.Item(1, TkWidget.Kind.FRAME, rect, rect, 0, 0, 0, 0, 0, 0, "");
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayList(Arrays.asList(item, item)));
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayList.Item(1, TkWidget.Kind.FRAME, rect,
                new TkRect(0, 0, 11, 10), 0, 0, 0, 0, 0, 0, ""));
        tree.clear();
        for (int i = 0; i < TkTree.MAX_WIDGETS; i++) new Frame(tree.root());
        assertThrows(IllegalArgumentException.class, () -> new Frame(tree.root()));
    }

    @Test void longMultilineTextRemainsSerializableWhenAnchoredInsideATinyWidget() {
        TkTree tree = tree();
        Label label = new Label(tree.root(), "\n".repeat(1024));
        label.size(1, 1); label.textAnchor(TkPack.Anchor.S); label.pack();
        TkDisplayList display = tree.render();
        assertEquals(-16400, display.items.get(0).textY);
        ByteBuf bytes = Unpooled.buffer();
        try {
            TkScene scene = new TkScene(200, 120, 0, true, display);
            new TkPackets.OpenGui(new TkTarget(0, 0, 64, 0, "display", 1), 1, scene).toBytes(bytes);
            TkPackets.OpenGui decoded = new TkPackets.OpenGui(); decoded.fromBytes(bytes);
            assertEquals(scene, decoded.scene);
        } finally { bytes.release(); }
    }
}
