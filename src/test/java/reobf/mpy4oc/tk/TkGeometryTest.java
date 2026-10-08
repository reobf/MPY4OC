package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

class TkGeometryTest {
    private static Map<String, Object> options(JsonObject json) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            JsonElement v = e.getValue(); String k = e.getKey();
            if (k.equals("padx") || k.equals("pady")) {
                out.put(k.equals("padx") ? "left" : "top", v.isJsonArray() ? v.getAsJsonArray().get(0).getAsInt() : v.getAsInt());
                out.put(k.equals("padx") ? "right" : "bottom", v.isJsonArray() ? v.getAsJsonArray().get(1).getAsInt() : v.getAsInt());
            } else out.put(k, v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : v.getAsString());
        }
        return out;
    }
    @TestFactory Stream<DynamicTest> cpythonTkGoldenRectangles() throws Exception {
        JsonObject data;
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/tk-layout-golden.json"), StandardCharsets.UTF_8)) {
            data = new JsonParser().parse(reader).getAsJsonObject();
        }
        List<DynamicTest> cases = new ArrayList<>();
        for (JsonElement raw : data.getAsJsonArray("cases")) {
            JsonObject spec = raw.getAsJsonObject();
            cases.add(DynamicTest.dynamicTest(spec.get("name").getAsString(), () -> compare(spec)));
        }
        return cases.stream();
    }
    private void compare(JsonObject spec) {
        TkTree tree = new TkTree(); tree.resize(spec.get("width").getAsInt(), spec.get("height").getAsInt());
        Frame root = tree.root(); root.padding(spec.get("padx").getAsInt(), spec.get("pady").getAsInt(), spec.get("border").getAsInt());
        if (spec.has("anchor")) root.gridAnchor(TkOptions.anchor(spec.get("anchor").getAsString()));
        for (String axis : new String[] {"columns", "rows"}) if (spec.has(axis))
            for (JsonElement slot : spec.getAsJsonArray(axis)) TkGeometry.call(root, axis.equals("columns") ? "columnconfigure" : "rowconfigure", options(slot.getAsJsonObject()));
        List<Frame> widgets = new ArrayList<>();
        for (JsonElement raw : spec.getAsJsonArray("widgets")) {
            JsonObject item = raw.getAsJsonObject(); Frame parent = item.has("parent") ? widgets.get(item.get("parent").getAsInt()) : root;
            Frame w = new Frame(parent); w.size(item.get("width").getAsInt(), item.get("height").getAsInt()); widgets.add(w);
            String manager = (item.has("manager") ? item.get("manager") : spec.get("manager")).getAsString();
            Map<String, Object> opts = options(item.getAsJsonObject("options"));
            if (manager.equals("pack")) w.pack(TkOptions.pack(opts, null)); else TkGeometry.call(w, manager, opts);
        }
        tree.render();
        JsonArray bbox = spec.getAsJsonArray("bbox");
        assertArrayEquals(new Object[] {bbox.get(0).getAsInt(), bbox.get(1).getAsInt(), bbox.get(2).getAsInt(), bbox.get(3).getAsInt()},
                (Object[]) TkGeometry.call(root, "grid_bbox", Map.of()), "grid bounding box");
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (int i = 0; i < widgets.size(); i++) {
            Frame w = widgets.get(i); JsonObject item = spec.getAsJsonArray("widgets").get(i).getAsJsonObject(); String tag = "widget " + i;
            JsonArray b = item.getAsJsonArray("bounds"), req = item.getAsJsonArray("requested");
            TkRect expected = new TkRect(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt());
            checks.add(() -> assertEquals(expected, w.bounds(), tag));
            checks.add(() -> assertArrayEquals(new int[] {req.get(0).getAsInt(), req.get(1).getAsInt()}, new int[] {w.requestedWidth, w.requestedHeight}, tag + " request"));
        }
        assertAll(checks);
        TkTree restored = TkTreePersistence.load(TkTreePersistence.save(tree), spec.get("width").getAsInt(), spec.get("height").getAsInt(), () -> {});
        assertEquals(tree.render(), restored.render(), "NBT preserves layout and paint");
    }
    @Test void managerSwitchingAndFailedUpdatesPreserveExistingGeometry() {
        TkTree tree = new TkTree(); Frame a = new Frame(tree.root()), b = new Frame(tree.root()); a.size(40, 20); b.size(30, 10);
        a.pack(); assertThrows(IllegalArgumentException.class, () -> b.grid(new TkGrid(0, 0)));
        a.grid(new TkGrid(2, 3)); b.grid(new TkGrid(0, 0));
        assertThrows(IllegalArgumentException.class, b::pack);
        TkRect before = a.bounds();
        assertThrows(IllegalArgumentException.class, () -> TkGeometry.call(a, "grid", Map.of("columnspan", 128)));
        assertEquals(before, a.bounds());
        a.gridForget(true); assertEquals("", a.manager()); assertNotNull(a.savedGrid);
        TkGeometry.call(a, "grid", Map.of()); assertEquals(before, a.bounds());
        a.gridForget(true);
        TkTree restored = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        TkWidget ar = restored.get(a.id); TkGeometry.call(ar, "grid", Map.of()); assertEquals(before, ar.bounds());
        a.place(TkPlace.DEFAULT); assertEquals("place", a.manager()); assertNull(a.savedGrid);
        TkGeometry.call(a, "pack_forget", Map.of()); assertEquals("place", a.manager());
        b.gridForget(false); a.pack(); assertEquals("pack", a.manager());
        assertThrows(IllegalArgumentException.class, () -> TkGeometry.call(b, "place", Map.of("relx", Double.NaN)));
        assertEquals("", b.manager());
        assertThrows(IllegalArgumentException.class, () -> TkGeometry.call(tree.root(), "columnconfigure", Map.of("index", 128)));
    }
    @Test void clippingHitTestingAndVersionOneRestore() {
        TkTree tree = new TkTree(); Button button = new Button(tree.root(), "partially visible");
        button.place(new TkPlace(-20, -10, 80, 40, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
        assertEquals(new TkRect(-20, -10, 80, 40), button.bounds());
        assertEquals(new TkRect(0, 0, 60, 30), tree.render().items.get(0).clip);
        assertEquals(button, tree.hit(2, 2)); assertNotEquals(button, tree.hit(60, 30));
        tree.focus(button.id); button.forget(); tree.render(); assertEquals(0, tree.focused());
        NBTTagCompound old = TkTreePersistence.save(tree); old.setInteger("version", 1);
        assertEquals(tree.render(), TkTreePersistence.load(old, 320, 240, () -> {}).render());
        tree.root().gridAnchor(TkPack.Anchor.CENTER).columnconfigure(2, new TkGrid.Slot(1, 10, 0));
        assertArrayEquals(new Object[] {0, 0, 0, 0}, (Object[]) TkGeometry.call(tree.root(), "grid_bbox", Map.of()));
    }
}
