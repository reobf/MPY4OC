package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

/**
 * The classic LabelFrame and ttk::labelframe. Geometry is checked against Tk 8.6.14 (default
 * theme; tools/tk-labelframe-golden.py) with pixel-sized label widgets, so fonts play no part.
 */
class TkLabelframeTest {
    private static TkTree desktop() { TkTree tree = new TkTree(); tree.resize(400, 300); return tree; }
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static List<TkDisplayList.Item> records(TkTree tree, int id) {
        return tree.render().items.stream().filter(i -> i.id == id).collect(Collectors.toList());
    }
    private static int[] ints(JsonElement e) {
        JsonArray a = e.getAsJsonArray(); int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }
    private static int[] relative(TkWidget w, TkWidget to) {
        TkRect r = w.bounds(), o = to.bounds();
        return new int[] {r.x - o.x, r.y - o.y, r.width, r.height};
    }

    @Test void geometryMatchesTk() throws Exception {
        JsonObject data;
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/tk-labelframe-golden.json"), StandardCharsets.UTF_8)) {
            data = new JsonParser().parse(reader).getAsJsonObject();
        }
        int count = 0;
        for (JsonElement element : data.getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            boolean ttk = c.get("cls").getAsString().equals("ttk");
            String name = c.toString();
            TkTree tree = desktop();
            Labelframe frame = new Labelframe(tree.root(), ttk);
            Map<String, Object> o = options("labelanchor", c.get("anchor").getAsString());
            if (!c.get("bd").isJsonNull()) o.put("borderwidth", c.get("bd").getAsInt());
            if (!c.get("padding").isJsonNull()) {
                int[] p = ints(c.get("padding"));
                if (ttk) o.put("padding", new Object[] {p[0], p[1], p[2], p[3]});
                else { o.put("padx", p[0]); o.put("pady", p[1]); }
            }
            TkOptions.configure(frame, o);
            Frame child = new Frame(frame); child.size(50, 20); child.pack();
            int[] label = ints(c.get("label"));
            Frame box = new Frame(frame); box.size(label[0], label[1]);
            TkOptions.configure(frame, options("labelwidget", box.id));
            Integer width = null, height = null;
            if (!c.get("size").isJsonNull()) { int[] s = ints(c.get("size")); width = s[0]; height = s[1]; }
            frame.place(new TkPlace(0, 0, width, height, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
            TkRect bounds = frame.bounds();
            assertArrayEquals(ints(c.get("req")), new int[] {frame.requestedWidth, frame.requestedHeight}, "requested size " + name);
            assertArrayEquals(ints(c.get("actual")), new int[] {bounds.width, bounds.height}, "size " + name);
            assertArrayEquals(ints(c.get("child")), relative(child, frame), "child " + name);
            assertArrayEquals(ints(c.get("box")), relative(box, frame), "label widget " + name);
            assertEquals("labelframe", box.manager());
            count++;
        }
        assertEquals(176, count);
    }

    @Test void textLabelsAndTheBorder() {
        TkTree tree = desktop();
        // classic: the text's box has 1 px around it (8 px per character, 16 px lines); no text, no label.
        Labelframe classic = new Labelframe(tree.root(), false);
        TkOptions.configure(classic, options("text", "Title"));
        Frame child = new Frame(classic); child.size(50, 20); child.pack();
        classic.place(new TkPlace(0, 0, null, null, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
        TkRect b = classic.bounds();
        assertEquals(new TkRect(b.x + 6, b.y, 42, 18), classic.labelBox(), "at bd + 4 from the corner");
        assertArrayEquals(new int[] {2, 18, 50, 20}, relative(child, classic));
        assertEquals(54, classic.requestedWidth, "max(child + 2 bd, label + 2 (bd + 4))");
        List<TkDisplayList.Item> items = records(tree, classic.id);
        assertEquals(3, items.size());
        assertEquals(TkRelief.FLAT, items.get(0).relief);
        TkDisplayList.Item ring = items.get(1), text = items.get(2);
        assertEquals(TkRelief.GROOVE, ring.relief); assertEquals(2, ring.border);
        assertEquals(new TkRect(b.x, b.y + 8, b.width, b.height - 8), ring.bounds, "the border runs through the label's middle: (18 - 2 + 1) / 2");
        assertEquals("Title", text.text); assertEquals(b.x + 7, text.textX); assertEquals(b.y + 1, text.textY);
        TkOptions.configure(classic, options("text", ""));
        assertEquals(2, child.bounds().y - classic.bounds().y, "no text: an ordinary groove frame");
        assertEquals(2, records(tree, classic.id).size());
        // ttk: the text at 8 px from the edge, the border at half the label's height; no text still keeps one character.
        Labelframe themed = new Labelframe(tree.root(), true);
        TkOptions.configure(themed, options("text", "Title", "labelanchor", "n"));
        Frame inner = new Frame(themed); inner.size(50, 20); inner.pack();
        themed.place(new TkPlace(100, 100, null, null, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
        TkRect t = themed.bounds();
        assertEquals(60, themed.requestedWidth, "max(50 + 4, 40 + 16 + 4)");
        assertEquals(new TkRect(t.x + 10, t.y, 40, 16), themed.labelBox(), "centred");
        assertArrayEquals(new int[] {5, 18, 50, 20}, relative(inner, themed));
        assertEquals(new TkRect(t.x, t.y + 8, t.width, t.height - 8), themed.borderBox());
        TkOptions.configure(themed, options("text", ""));
        assertArrayEquals(new int[] {2, 18, 50, 20}, relative(inner, themed), "one character's height is kept");
    }

    @Test void theLabelWidgetIsManagedByTheFrame() {
        TkTree tree = desktop();
        Labelframe frame = new Labelframe(tree.root(), false); frame.pack();
        Label label = new Label(frame, "L"); Label other = new Label(frame, "M");
        Frame child = new Frame(frame); child.size(30, 30); child.pack();
        TkOptions.configure(frame, options("text", "T", "labelwidget", label.id));
        assertEquals("labelframe", label.manager()); assertTrue(label.mapped());
        assertTrue(records(tree, frame.id).stream().noneMatch(i -> i.text.equals("T")), "the text is kept but not drawn");
        assertEquals("T", frame.text());
        TkOptions.configure(frame, options("labelwidget", other.id));
        assertEquals("", label.manager(), "the old one is no longer managed"); assertFalse(label.mapped());
        other.pack();
        assertNull(frame.labelWidget, "packing it elsewhere takes it from the frame");
        assertTrue(records(tree, frame.id).stream().anyMatch(i -> i.text.equals("T")));
        TkOptions.configure(frame, options("labelwidget", label.id));
        label.destroy();
        assertNull(frame.labelWidget);
        Label outside = new Label(tree.root(), "x");
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(frame, options("labelwidget", outside.id)));
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(frame, options("labelanchor", "bogus")));
        assertEquals("bad labelanchor \"bogus\": must be e, en, es, n, ne, nw, s, se, sw, w, wn, or ws", bad.getMessage());
        Labelframe themed = new Labelframe(tree.root(), true);
        bad = assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(themed, options("labelanchor", "bogus")));
        assertEquals("Bad label anchor specification bogus", bad.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(new Frame(tree.root()), options("labelanchor", "n")));
    }

    @Test void labelframesAreSaved() {
        TkTree tree = desktop();
        Labelframe frame = new Labelframe(tree.root(), true);
        TkOptions.configure(frame, options("text", "Options", "labelanchor", "wn", "relief", "ridge"));
        Frame child = new Frame(frame); child.size(40, 40); child.pack();
        Label label = new Label(frame, "lw");
        frame.pack();
        Labelframe plain = new Labelframe(tree.root(), false);
        TkOptions.configure(plain, options("text", "Plain"));
        TkOptions.configure(frame, options("labelwidget", label.id));
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 400, 300, () -> {});
        assertEquals(tree.render(), loaded.render());
        Labelframe copy = (Labelframe) loaded.widgets.get(frame.id);
        assertTrue(copy.ttk); assertEquals("Options", copy.text); assertEquals(Labelframe.Anchor.WN, copy.labelAnchor);
        assertEquals(label.id, copy.labelWidget.id); assertEquals("labelframe", copy.labelWidget.manager());
        assertFalse(((Labelframe) loaded.widgets.get(plain.id)).ttk);
    }
}
