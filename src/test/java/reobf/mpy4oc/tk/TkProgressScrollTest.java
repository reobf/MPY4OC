package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.netty.buffer.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkProgressScrollTest {
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    static TkDisplayList.Item part(TkTree tree, TkWidget w, int part) {
        return tree.render().items.stream().filter(i -> i.id == w.id && i.part == part).findFirst().orElse(null);
    }

    // ---- Progressbar ----------------------------------------------------------------------

    Progressbar bar(TkTree tree, Map<String, Object> options) {
        Progressbar p = new Progressbar(tree.root());
        TkOptions.configure(p, options);
        p.pack();
        tree.render();
        return p;
    }

    @Test void determinateBarsFillToTheValueVerticalOnesFromTheBottom() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Progressbar p = bar(tree, map("length", 202, "value", 50.0));
        assertEquals(202, p.bounds.width); assertEquals(18, p.bounds.height);
        TkDisplayList.Item fill = part(tree, p, 1);
        assertEquals(new TkRect(p.bounds.x + 1, p.bounds.y + 1, 100, 16), fill.bounds, "half of the 200 px trough, inside the border");
        assertEquals(p.foreground, fill.background);
        TkOptions.configure(p, map("value", 0.0));
        assertNull(part(tree, p, 1), "an empty bar has no fill record");
        TkOptions.configure(p, map("value", 250.0));
        assertEquals(200, part(tree, p, 1).bounds.width, "past the maximum it is just full");
        Progressbar v = bar(tree, map("orient", "vertical", "length", 102, "maximum", 4.0, "value", 1.0));
        TkDisplayList.Item up = part(tree, v, 1);
        assertEquals(25, up.bounds.height); assertEquals(v.bounds.y + v.bounds.height - 1 - 25, up.bounds.y, "fills from the bottom");
        assertEquals(TkWidget.Kind.PROGRESSBAR, part(tree, v, 0).kind);
    }

    @Test void indeterminateBlocksBounceAndStepWraps() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Progressbar p = bar(tree, map("length", 102, "mode", "indeterminate"));
        int trough = 100, block = 20;
        assertArrayEquals(new int[] {0, block}, p.bar());
        TkOptions.configure(p, map("value", 50.0));
        assertArrayEquals(new int[] {(trough - block) / 2, block}, p.bar(), "half way along");
        TkOptions.configure(p, map("value", 100.0));
        assertArrayEquals(new int[] {trough - block, block}, p.bar(), "at the far end after one maximum");
        TkOptions.configure(p, map("value", 150.0));
        assertArrayEquals(new int[] {(trough - block) / 2, block}, p.bar(), "and back");
        // Stepping (as start() does) keeps counting in indeterminate mode, so the block comes back.
        TkOptions.configure(p, map("value", 0.0));
        for (int i = 0; i < 150; i++) TkControlApi.call(p, "step", new Object[] {1.0});
        assertEquals(150.0, p.value); assertArrayEquals(new int[] {(trough - block) / 2, block}, p.bar(), "on its way back");
        TkOptions.configure(p, map("value", 9.99e8));
        TkControlApi.call(p, "step", new Object[] {2e6});
        assertTrue(Math.abs(p.value) <= 100, "folded by whole periods near the limit: " + p.value);
        TkOptions.configure(p, map("mode", "determinate", "value", 90.0));
        assertEquals(10.0, TkControlApi.call(p, "step", new Object[] {20.0}), "step past the maximum wraps");
        assertEquals(11.0, TkControlApi.call(p, "step", new Object[] {1.0}));
        TkOptions.configure(p, map("value", 0.0));
        assertEquals(-10.0, TkControlApi.call(p, "step", new Object[] {-110.0}), "fmod keeps the sign, as Tk's");
        for (int i = 0; i < 3; i++) TkControlApi.call(p, "step", new Object[] {-6e8});
        assertTrue(Math.abs(p.value) < 100, "never leaves (-maximum, maximum)");
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(p, map("mode", "spin")));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(p, map("maximum", 0.0)));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(p, map("command", 3)));
        assertEquals("determinate", TkControlOptions.cget(p, "mode"));
        assertEquals(100.0, TkControlOptions.cget(p, "maximum"));
    }

    @Test void progressbarsSaveAndCrossTheWire() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        bar(tree, map("length", 150, "value", 30.0, "maximum", 60.0));
        bar(tree, map("orient", "vertical", "mode", "indeterminate", "value", 7.0));
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        assertEquals(tree.render(), loaded.render());
        TkScene scene = new TkScene(320, 240, 0, true, tree.render());
        ByteBuf wire = Unpooled.buffer();
        try {
            new TkPackets.OpenGui(new TkTarget(0, 0, 0, 0, "screen", 1), 1, scene).toBytes(wire);
            TkPackets.OpenGui decoded = new TkPackets.OpenGui(); decoded.fromBytes(wire);
            assertEquals(scene, decoded.scene);
        } finally {
            wire.release();
        }
    }

    // ---- Canvas scrolling -----------------------------------------------------------------

    Canvas canvas(TkTree tree, int border) {
        Canvas c = new Canvas(tree.root());
        TkOptions.configure(c, map("width", 300, "height", 200, "borderwidth", border));
        c.pack(); tree.render();
        return c;
    }
    int create(Canvas c, String type, Object[] coords, Map<String, Object> options) {
        return (Integer) c.call("create", map("type", type, "coords", coords, "options", options));
    }
    Object view(Canvas c, String axis, Object... args) { return c.call(axis, map("args", args)); }

    @Test void viewsFollowTkWithAScrollRegion() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Canvas c = canvas(tree, 0);
        assertArrayEquals(new Object[] {0.0, 1.0}, (Object[]) view(c, "yview"), "no scroll region: everything is in view");
        TkOptions.configure(c, map("scrollregion", new Object[] {0, 0, 600, 1000}));
        assertArrayEquals(new Object[] {0.0, 0.2}, (Object[]) view(c, "yview"));
        assertArrayEquals(new Object[] {0.0, 0.5}, (Object[]) view(c, "xview"));
        view(c, "yview", "moveto", 0.5);
        assertEquals(500, c.viewY);
        view(c, "yview", "moveto", 1.0);
        assertEquals(800, c.viewY, "confined: the region's bottom edge stays at the window's");
        assertArrayEquals(new Object[] {0.8, 1.0}, (Object[]) view(c, "yview"));
        view(c, "yview", "scroll", -1, "units");
        assertEquals(780, c.viewY, "a unit is a tenth of the window");
        view(c, "yview", "scroll", "-2", "pages");
        assertEquals(420, c.viewY, "a page is nine tenths; Tk passes strings");
        view(c, "yview", "scroll", -9, "pages");
        assertEquals(0, c.viewY, "cannot leave the region");
        TkOptions.configure(c, map("yscrollincrement", 50));
        view(c, "yview", "moveto", 0.33);
        assertEquals(350, c.viewY, "rounded to the increment");
        view(c, "yview", "scroll", 1, "units");
        assertEquals(400, c.viewY);
        TkOptions.configure(c, map("height", 180));
        tree.render();
        view(c, "yview", "moveto", 1.0);
        assertEquals(850, c.viewY, "confining shifts by whole increments only, as Tk: the view ends 30 px past the region");
        TkOptions.configure(c, map("height", 200, "yscrollincrement", 0));
        tree.render();
        view(c, "yview", "moveto", 1.0);
        assertEquals(800, c.viewY);
        TkOptions.configure(c, map("height", 220));
        tree.render();
        assertEquals(780, c.viewY, "a taller window is confined again after layout, as Tk does on resize");
        assertArrayEquals(new Object[] {0.78, 1.0}, (Object[]) view(c, "yview"));
        TkOptions.configure(c, map("height", 200));
        tree.render();
        TkOptions.configure(c, map("confine", false));
        view(c, "yview", "moveto", 0.4);
        TkOptions.configure(c, map("yscrollincrement", 50));
        view(c, "yview", "scroll", 100, "units");
        assertEquals(5400, c.viewY, "unconfined views may leave the region");
        assertThrows(IllegalArgumentException.class, () -> view(c, "yview", "scroll", 1, "lines"));
        assertThrows(IllegalArgumentException.class, () -> view(c, "yview", "jump", 1));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(c, map("scrollregion", new Object[] {0, 0, 10})));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(c, map("scrollregion", new Object[] {10, 0, 0, 10})));
        TkOptions.configure(c, map("scrollregion", "-100 -50 400 300"));
        assertArrayEquals(new Object[] {-100, -50, 400, 300}, (Object[]) TkControlOptions.cget(c, "scrollregion"));
        TkOptions.configure(c, map("scrollregion", ""));
        assertArrayEquals(new Object[0], (Object[]) TkControlOptions.cget(c, "scrollregion"));
    }

    @Test void theBorderIsPartOfTheViewAsInTk() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Canvas c = canvas(tree, 2);
        TkOptions.configure(c, map("scrollregion", new Object[] {0, 0, 600, 1000}));
        assertArrayEquals(new Object[] {0.002, 0.202}, (Object[]) view(c, "yview"), "origin 0: the border hides canvas y 0..1");
        view(c, "yview", "moveto", 0.0);
        assertEquals(-2, c.viewY, "moveto 0 puts the region's top just inside the border");
        assertArrayEquals(new Object[] {0.0, 0.2}, (Object[]) view(c, "yview"));
    }

    @Test void scrolledItemsPaintHitAndCullInCanvasCoordinates() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Canvas c = canvas(tree, 0);
        TkOptions.configure(c, map("scrollregion", new Object[] {0, 0, 300, 1000}));
        int top = create(c, "rectangle", new Object[] {10, 10, 60, 60}, map("fill", 0xFF0000));
        int far = create(c, "rectangle", new Object[] {10, 610, 60, 660}, map("fill", 0x00FF00));
        int label = create(c, "text", new Object[] {20, 700}, map("text", "far", "anchor", "nw"));
        TkDisplayList.Item first = part(tree, c, top);
        assertEquals(c.bounds.x, first.textX); assertEquals(c.bounds.y, first.textY, "vector records carry the canvas origin");
        assertNull(part(tree, c, far), "out of view: not sent");
        assertEquals(top, c.hitItem(c.bounds.x + 20, c.bounds.y + 20));
        view(c, "yview", "moveto", 0.6);
        assertEquals(600, c.viewY);
        assertNull(part(tree, c, top));
        TkDisplayList.Item shown = part(tree, c, far);
        assertEquals(c.bounds.y - 600, shown.textY);
        assertEquals(new TkRect(c.bounds.x + 20, c.bounds.y + 100, 24, 16), new TkRect(part(tree, c, label).textX, part(tree, c, label).textY, 24, 16));
        assertEquals(far, c.hitItem(c.bounds.x + 20, c.bounds.y + 20), "window point (20, 20) is canvas (20, 620)");
        assertEquals(0, c.hitItem(c.bounds.x + 100, c.bounds.y + 20));
        assertArrayEquals(new Object[] {0, 600}, (Object[]) c.call("origin", map()));
        assertArrayEquals(new Object[] {9, 609, 62, 662}, (Object[]) c.call("bbox", map("selector", far)), "bbox stays in canvas coordinates");
    }

    @Test void scrollCommandsReportTheViewAndTheWheelScrolls() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Canvas c = canvas(tree, 0);
        TkOptions.configure(c, map("scrollregion", new Object[] {0, 0, 300, 1000}, "yscrollcommand", 7, "xscrollcommand", 8));
        Map<Integer, Object[]> reported = new HashMap<>();
        for (TkTree.Command command : tree.takeCommands()) reported.put(command.callback, command.args);
        assertArrayEquals(new Object[] {0.0, 0.2}, reported.get(7)); assertArrayEquals(new Object[] {0.0, 1.0}, reported.get(8));
        assertTrue(tree.takeCommands().isEmpty(), "unchanged views are not reported again");
        tree.wheel(c.bounds.x + 5, c.bounds.y + 5, -120);
        assertEquals(20, c.viewY, "one wheel notch is one unit");
        List<TkTree.Command> after = tree.takeCommands();
        assertEquals(1, after.size()); assertEquals(7, after.get(0).callback);
        tree.root().bindings.put("<MouseWheel>", 10);
        tree.wheel(c.bounds.x + 5, c.bounds.y + 5, -120);
        assertEquals(20, c.viewY, "a wheel handler on the toplevel scrolls by itself");
        tree.root().bindings.remove("<MouseWheel>");
        c.bindings.put("<MouseWheel>", 9);
        tree.wheel(c.bounds.x + 5, c.bounds.y + 5, -120);
        assertEquals(20, c.viewY, "a program that binds the wheel scrolls itself");
    }

    @Test void theViewIsSaved() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Canvas c = canvas(tree, 0);
        TkOptions.configure(c, map("scrollregion", new Object[] {-50, 0, 300, 1000}, "yscrollcommand", 7, "yscrollincrement", 25, "confine", false));
        create(c, "line", new Object[] {0, 0, 100, 900}, map());
        view(c, "yview", "moveto", 0.5);
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {});
        Canvas d = (Canvas) loaded.get(c.id);
        assertEquals(c.viewY, d.viewY); assertArrayEquals(c.scrollregion, d.scrollregion);
        assertEquals(25, d.yscrollincrement); assertEquals(7, d.yscrollcommand); assertFalse(d.confine);
        assertEquals(tree.render(), loaded.render());
        NBTTagCompound broken = TkTreePersistence.save(tree);
        NBTTagList nodes = broken.getTagList("nodes", 10);
        for (int i = 0; i < nodes.tagCount(); i++) if (nodes.getCompoundTagAt(i).hasKey("canvasView"))
            nodes.getCompoundTagAt(i).getCompoundTag("canvasView").setIntArray("region", new int[] {0, 0, 5});
        assertThrows(IllegalArgumentException.class, () -> TkTreePersistence.load(broken, 320, 240, () -> {}));
    }
}
