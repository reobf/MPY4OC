package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

/**
 * ttk stage 2, part one: four-sided padding and -relief. Expected values come from Tk 8.6.14 on
 * X11 (default theme): the 3D shades of a background, the ring pattern of each relief, where
 * padding puts the content.
 */
class TkReliefTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(320, 240); return tree; }
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }

    @Test void shadesFollowTk() {
        // background -> light, dark, as Tk 8.6 draws them on X11
        int[][] cases = {{0xD9D9D9, 0xFFFFFF, 0x828282}, {0xECECEC, 0xFFFFFF, 0x8E8E8E}, {0xC3C3C3, 0xFFFFFF, 0x757575},
                {0x808080, 0xC0C0C0, 0x4D4D4D}, {0x000000, 0x7F7F7F, 0x3F3F3F}, {0xFFFFFF, 0xE6E6E6, 0x999999},
                {0xFF0000, 0xFF7F7F, 0x990000}, {0x00FF00, 0x00E600, 0x009900}, {0x336699, 0x99B3D7, 0x1E3D5C}};
        for (int[] c : cases) {
            assertEquals(c[1], TkRelief.light(c[0]), "light of " + Integer.toHexString(c[0]));
            assertEquals(c[2], TkRelief.dark(c[0]), "dark of " + Integer.toHexString(c[0]));
        }
    }

    @Test void ringsFollowTk() {
        int bg = 0xD9D9D9, L = 0xFFFFFF, D = 0x828282;
        assertEquals(0, TkRelief.rings(TkRelief.FLAT, 3, bg).length);
        assertEquals(0, TkRelief.rings(TkRelief.RAISED, 0, bg).length);
        assertRings(TkRelief.rings(TkRelief.RAISED, 2, bg), new int[][] {{L, D}, {L, D}});
        assertRings(TkRelief.rings(TkRelief.SUNKEN, 2, bg), new int[][] {{D, L}, {D, L}});
        // A groove is a sunken outer half and a raised inner half (so 1 px looks raised); a ridge the reverse.
        assertRings(TkRelief.rings(TkRelief.GROOVE, 1, bg), new int[][] {{L, D}});
        assertRings(TkRelief.rings(TkRelief.GROOVE, 2, bg), new int[][] {{D, L}, {L, D}});
        assertRings(TkRelief.rings(TkRelief.GROOVE, 3, bg), new int[][] {{D, L}, {L, D}, {L, D}});
        assertRings(TkRelief.rings(TkRelief.GROOVE, 4, bg), new int[][] {{D, L}, {D, L}, {L, D}, {L, D}});
        assertRings(TkRelief.rings(TkRelief.RIDGE, 1, bg), new int[][] {{D, L}});
        assertRings(TkRelief.rings(TkRelief.RIDGE, 3, bg), new int[][] {{L, D}, {D, L}, {D, L}});
        assertRings(TkRelief.rings(TkRelief.SOLID, 2, bg), new int[][] {{0, 0}, {0, 0}});
    }

    private static void assertRings(int[][] actual, int[][] expected) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i], "ring " + i);
    }

    @Test void fourSidedPaddingPlacesTheContent() {
        TkTree tree = desktop();
        Frame frame = new Frame(tree.root());
        Frame child = new Frame(frame); child.size(50, 20); child.pack();
        frame.padding(3, 3, 12, 12, 0); frame.pack();
        TkRect f = frame.bounds(), c = child.bounds();
        assertEquals(65, frame.requestedWidth); assertEquals(35, frame.requestedHeight);
        assertEquals(new TkRect(f.x + 3, f.y + 3, 50, 20), c, "ttk.Frame(padding='3 3 12 12'): the child at (3, 3)");
        frame.padding(3, 3, 12, 12, 2);
        f = frame.bounds(); c = child.bounds();
        assertEquals(69, frame.requestedWidth, "the border adds on every side");
        assertEquals(new TkRect(f.x + 5, f.y + 5, 50, 20), c);
        // A label's text starts after the left padding.
        Label label = new Label(tree.root(), "ab"); label.pack();
        TkOptions.configure(label, options("padding", new Object[] {10, 1, 2, 1}, "anchor", "w"));
        TkRect l = label.bounds();
        assertEquals(16 + 12, label.requestedWidth);
        assertEquals(l.x + 10, records(tree, label.id).get(0).textX);
        assertEquals(10, label.padLeft); assertEquals(2, label.padRight);
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(label, options("padding", new Object[] {1, 2, 3})));
    }

    @Test void eachClassStartsWithTksReliefAndPartZeroCarriesIt() {
        java.util.List<java.util.function.Function<Frame, TkWidget>> makers = java.util.Arrays.asList(
                p -> { Frame f = new Frame(p); f.size(10, 10); return f; }, p -> new Label(p, "l"), p -> new Button(p, "b"), Entry::new,
                Listbox::new, p -> { Text t = new Text(p); t.size(100, 50); return t; }, p -> { Canvas c = new Canvas(p); c.size(50, 50); return c; },
                Scale::new, Scrollbar::new, p -> new Checkbutton(p, false), Message::new);
        TkWidget.Relief[] expected = {TkWidget.Relief.FLAT, TkWidget.Relief.FLAT, TkWidget.Relief.RAISED, TkWidget.Relief.SUNKEN,
                TkWidget.Relief.SUNKEN, TkWidget.Relief.SUNKEN, TkWidget.Relief.FLAT, TkWidget.Relief.FLAT, TkWidget.Relief.SUNKEN,
                TkWidget.Relief.FLAT, TkWidget.Relief.FLAT};
        TkWidget[] widgets = new TkWidget[expected.length];
        for (int i = 0; i < expected.length; i++) {
            TkTree tree = desktop();
            TkWidget w = widgets[i] = makers.get(i).apply(tree.root()); w.pack();
            assertEquals(expected[i], w.relief, w.kind.name());
            assertEquals(expected[i].code(), records(tree, w.id).get(0).relief, w.kind.name() + " part 0");
        }
        Label label = (Label) widgets[1];
        TkTree tree = label.tree;
        TkOptions.configure(label, options("relief", "groove", "borderwidth", 2));
        assertEquals(TkRelief.GROOVE, records(tree, label.id).get(0).relief);
        assertEquals("groove", label.relief.option());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(label, options("relief", "bogus")));
        assertEquals("bad relief \"bogus\": must be flat, groove, raised, ridge, solid, or sunken", bad.getMessage());
        assertEquals(TkWidget.Relief.GROOVE, label.relief, "a failed configure changes nothing");
        // Inner parts (a Listbox's rows, the indicator of a Checkbutton) keep their own look.
        for (TkDisplayList.Item item : records(widgets[9].tree, widgets[9].id)) if (item.part > 0) assertEquals(TkRelief.LEGACY, item.relief);
    }

    @Test void paddingAndReliefAreSaved() {
        TkTree tree = desktop();
        Frame frame = new Frame(tree.root()); frame.size(40, 30); frame.pack();
        TkOptions.configure(frame, options("padding", new Object[] {1, 2, 3, 4}, "relief", "ridge", "borderwidth", 3));
        Button button = new Button(tree.root(), "b"); button.pack();
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {});
        assertEquals(tree.render(), loaded.render());
        TkWidget copy = loaded.widgets.get(frame.id);
        assertEquals(TkWidget.Relief.RIDGE, copy.relief);
        assertArrayEquals(new int[] {1, 2, 3, 4}, new int[] {copy.padLeft, copy.padTop, copy.padRight, copy.padBottom});
        // A save from before -relief: each widget gets its class's default.
        NBTTagList nodes = saved.getTagList("nodes", 10);
        for (int i = 0; i < nodes.tagCount(); i++) nodes.getCompoundTagAt(i).removeTag("relief");
        TkTree old = TkTreePersistence.load(saved, 320, 240, () -> {});
        assertEquals(TkWidget.Relief.FLAT, old.widgets.get(frame.id).relief);
        assertEquals(TkWidget.Relief.RAISED, old.widgets.get(button.id).relief);
    }

    @Test void reliefCrossesTheWire() {
        TkDisplayList.Item item = new TkDisplayList.Item(1, 0, TkWidget.Kind.LABEL, new TkRect(0, 0, 20, 10), new TkRect(0, 0, 20, 10),
                0xD9D9D9, 0, 2, 0, 0, 0, "x", -1, 0, 0, null, 0, TkRelief.GROOVE);
        TkScene scene = new TkScene(320, 240, 0, true, new TkDisplayList(java.util.Collections.singletonList(item)));
        ByteBuf wire = Unpooled.buffer();
        try {
            new TkPackets.OpenGui(new TkTarget(0, 0, 0, 0, "screen", 1), 1, scene).toBytes(wire);
            TkPackets.OpenGui decoded = new TkPackets.OpenGui(); decoded.fromBytes(wire);
            assertEquals(scene, decoded.scene);
            assertEquals(TkRelief.GROOVE, decoded.scene.display.items.get(0).relief);
        } finally { wire.release(); }
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayList.Item(1, 0, TkWidget.Kind.LABEL, new TkRect(0, 0, 20, 10),
                new TkRect(0, 0, 20, 10), 0, 0, 0, 0, 0, 0, "", -1, 0, 0, null, 0, TkRelief.TAB + 1));
    }
}
