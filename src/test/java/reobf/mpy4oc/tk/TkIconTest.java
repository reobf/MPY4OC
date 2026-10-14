package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkIconTest {
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private static TkTree tree() { TkTree tree = new TkTree(); tree.resize(320, 240); return tree; }
    private static TkDisplayList.Item record(TkIcon icon) {
        return icon.tree.render().items.stream().filter(i -> i.id == icon.id && i.icon != null).findFirst().orElseThrow();
    }

    @Test void acceptsOcMapsButRetainsOnlyRenderingDataAndClearsAtomically() {
        TkTree tree = tree(); ItemIcon item = new ItemIcon(tree.root()); item.pack();
        Map<String, Object> oc = map("name", "minecraft:stone", "damage", 3, "size", Long.MAX_VALUE,
                "maxSize", 64, "label", "Ignored", "tag", new byte[10000], "hasTag", true);
        TkOptions.configure(item, map("item", oc, "width", 24, "height", 24, "background", 0x123456));
        assertEquals(map("name", "minecraft:stone", "damage", 3), item.descriptor.options());
        oc.put("name", "minecraft:dirt");
        assertEquals("minecraft:stone", record(item).icon.name, "descriptor is detached from caller data");
        assertEquals(24, item.bounds().width); assertEquals(24, item.bounds().height);
        TkIconDescriptor before = item.descriptor;
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(item, map("width", 50, "item", map("name", "bad name"))));
        assertSame(before, item.descriptor); assertEquals(24, item.width);
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(item, map("item", null, "width", -1)));
        assertSame(before, item.descriptor);
        TkOptions.configure(item, map("item", null));
        assertNull(item.descriptor);
        assertTrue(tree.render().items.stream().noneMatch(i -> i.icon != null));
        assertEquals(1, tree.render().items.size(), "background/border remains when cleared");
        TkOptions.configure(item, map("item", map("name", "minecraft:stone")));
        TkOptions.configure(item, map("item", new byte[0])); assertNull(item.descriptor);
        FluidIcon fluid = new FluidIcon(tree.root());
        TkOptions.configure(fluid, map("fluid", map("name", "water", "amount", Long.MAX_VALUE, "label", "Water", "damage", -1, "nbt", new Object())));
        assertEquals(map("name", "water"), fluid.descriptor.options());
        TkOptions.configure(fluid, map("fluid", "")); assertNull(fluid.descriptor);
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(item, map("fluid", map("name", "water"))));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(new Label(tree.root(), ""), map("item", oc)));
    }

    @Test void validatesBoundedNamesDamageAndNbtWithoutLookingUpRegistries() {
        assertEquals("UnknownMod:Missing.Item", new TkIconDescriptor(false, "UnknownMod:Missing.Item", 65535, "").name);
        for (Object bad : new Object[] {-1, 65536, 1.5, Double.NaN, true, "0"})
            assertThrows(IllegalArgumentException.class, () -> TkIconDescriptor.parse(false, map("name", "minecraft:stone", "damage", bad)));
        for (String bad : new String[] {"", "a b", "a:b:c", "../stone", "a".repeat(257), "a\u0000", "a\ud800"})
            assertThrows(IllegalArgumentException.class, () -> new TkIconDescriptor(false, bad, 0, ""));
        String nbt = "{display:{Name:\"Named Stone\"},ench:[{id:16s,lvl:1s}],value:3b}";
        TkIconDescriptor descriptor = new TkIconDescriptor(false, "minecraft:stone", 2, nbt);
        assertEquals("Named Stone", descriptor.copyTag().getCompoundTag("display").getString("Name"));
        NBTTagCompound copy = descriptor.copyTag(); copy.getCompoundTag("display").setString("Name", "mutated");
        assertEquals("Named Stone", descriptor.copyTag().getCompoundTag("display").getString("Name"));
        for (String bad : new String[] {"[]", "{x:[}", "{x:\"unfinished}", "{x:".repeat(17) + "0" + "}".repeat(17),
                "{x:\"" + "a".repeat(4096) + "\"}", "{x:\"" + "中".repeat(1400) + "\"}", "{x:\"\ud800\"}"})
            assertThrows(IllegalArgumentException.class, () -> new TkIconDescriptor(false, "minecraft:stone", 0, bad));
        assertDoesNotThrow(() -> new TkIconDescriptor(false, "minecraft:stone", 0, "{x:\"" + "[".repeat(32) + "\"}"));
        assertDoesNotThrow(() -> new TkIconDescriptor(false, "minecraft:stone", 0, "{x:".repeat(16) + "0" + "}".repeat(16)));
        assertThrows(IllegalArgumentException.class, () -> new TkIconDescriptor(true, "water", 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new TkIconDescriptor(true, "water", 0, "{}"));
    }

    @Test void pixelsPaddingBorderAnchorClippingAndAllGeometryManagersWork() {
        TkTree tree = tree(); ItemIcon item = new ItemIcon(tree.root());
        TkOptions.configure(item, map("item", map("name", "minecraft:stone"), "width", 40, "height", 24,
                "padx", 2, "pady", 3, "borderwidth", 1, "relief", "solid", "anchor", "e"));
        item.pack();
        assertEquals(46, item.bounds().width); assertEquals(32, item.bounds().height);
        TkDisplayList.Item icon = record(item);
        assertEquals(new TkRect(item.bounds.x + 19, item.bounds.y + 4, 24, 24), icon.bounds);
        assertEquals(TkRelief.SOLID, tree.render().items.get(0).relief);
        assertSame(item, tree.hit(icon.bounds.x + 1, icon.bounds.y + 1));
        item.forget(); item.grid(new TkGrid(0, 0));
        assertEquals(46, item.bounds().width); assertEquals("grid", item.manager());
        item.forget(); item.place(new TkPlace(-30, 10, 46, 32, 0, 0, null, null, TkPack.Anchor.NW, "inside"));
        icon = record(item);
        assertEquals(-11, icon.bounds.x); assertEquals(0, icon.clip.x); assertEquals(13, icon.clip.width);
        TkOptions.configure(item, map("state", "disabled"));
        assertEquals(TkDisplayList.DISABLED, record(item).state & TkDisplayList.DISABLED);
        // Place can enlarge a widget beyond its explicit size; still a valid bounded icon record.
        item.place(new TkPlace(0, 0, 0, 0, 0, 0, 16.0, 16.0, TkPack.Anchor.NW, "inside"));
        assertDoesNotThrow(tree::render);
    }

    @Test void nbtPersistenceIncludesDescriptorBindingsAndRejectsCorruption() {
        TkTree tree = tree(); ItemIcon item = new ItemIcon(tree.root()); FluidIcon fluid = new FluidIcon(tree.root());
        TkOptions.configure(item, map("item", map("name", "minecraft:wool", "damage", 14, "nbt", "{display:{Name:\"Red\"}}")));
        TkOptions.configure(fluid, map("fluid", map("name", "water")));
        item.bindings.put("<Button-1>", 42); item.pack(); fluid.pack();
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {});
        assertEquals(tree.render(), loaded.render());
        assertEquals(item.bindings, loaded.get(item.id).bindings);
        assertEquals(item.descriptor, ((ItemIcon) loaded.get(item.id)).descriptor);
        NBTTagList nodes = saved.getTagList("nodes", 10);
        nodes.getCompoundTagAt(1).getCompoundTag("icon").setString("nbt", "{broken");
        assertThrows(IllegalArgumentException.class, () -> TkTreePersistence.load(saved, 320, 240, () -> {}));
        TkOptions.configure(item, map("item", null)); TkOptions.configure(fluid, map("fluid", null));
        assertEquals(tree.render(), TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {}).render());
    }

    @Test void descriptorsCountTowardsTreeAndPaintBudgets() {
        TkTree tree = tree();
        Map<String, Object> descriptor = map("name", "minecraft:stone", "nbt", "{x:\"" + "a".repeat(4000) + "\"}");
        int bytes = TkIconDescriptor.parse(false, descriptor).bytes();
        for (int i = 0; i < TkIconDescriptor.MAX_TREE_BYTES / bytes; i++)
            TkOptions.configure(new ItemIcon(tree.root()), map("item", descriptor));
        ItemIcon extra = new ItemIcon(tree.root());
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(extra, map("item", descriptor, "width", 80)));
        assertNull(extra.descriptor); assertEquals(24, extra.width);
        TkIconDescriptor icon = TkIconDescriptor.parse(false, descriptor);
        List<TkDisplayList.Item> records = new ArrayList<>();
        TkRect box = new TkRect(0, 0, 24, 24);
        for (int i = 0; i <= TkDisplayList.MAX_PAINT_BYTES / bytes; i++)
            records.add(new TkDisplayList.Item(i + 1, 1, TkWidget.Kind.ITEMICON, box, box, 0, 0, 0, 0, 0, 0, "", -1, 0, 0, null, 0, 0, icon));
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayList(records));
        assertTrue(TkDisplayList.trimToBudget(records)); assertDoesNotThrow(() -> new TkDisplayList(records));
        assertThrows(IllegalArgumentException.class, () -> new TkDisplayList.Item(1, 1, TkWidget.Kind.FLUIDICON, box, box,
                0, 0, 0, 0, 0, 0, "", -1, 0, 0, null, 0, 0, icon));
    }

    @Test void guiAndSurfacePacketsAndPatchesRoundTripWithAppendedKinds() throws Exception {
        assertEquals(22, TkWidget.Kind.PANEDWINDOW.ordinal());
        assertEquals(23, TkWidget.Kind.ITEMICON.ordinal()); assertEquals(24, TkWidget.Kind.FLUIDICON.ordinal());
        TkTree tree = tree(); ItemIcon item = new ItemIcon(tree.root()); FluidIcon fluid = new FluidIcon(tree.root());
        item.pack(); fluid.pack();
        TkOptions.configure(item, map("item", map("name", "minecraft:wool", "damage", 14, "nbt", "{display:{Name:\"Red\"}}")));
        TkOptions.configure(fluid, map("fluid", map("name", "water")));
        TkScene before = new TkScene(320, 240, 0, true, tree.render());
        TkTarget target = new TkTarget(0, 0, 0, 0, "icon-test", 1);
        assertEquals(before, roundTrip(new TkPackets.OpenGui(target, 1, before)).scene);
        assertEquals(before, roundTrip(new TkPackets.SurfaceSync(target, -1, 1, TkPackets.ALL, before)).scene);
        TkOptions.configure(item, map("item", null));
        TkOptions.configure(fluid, map("fluid", map("name", "lava")));
        TkScene after = new TkScene(320, 240, 0, true, tree.render());
        TkPackets.SceneSync delta = new TkPackets.SceneSync(target, 1, 2, TkPackets.DISPLAY, after);
        delta.displayPatch = TkDisplayPatch.between(before.display, after.display);
        assertEquals(after, roundTrip(delta).apply(before));
        ByteBuf wire = Unpooled.buffer();
        try {
            new TkPackets.OpenGui(target, 1, before).toBytes(wire);
            byte[] name = "minecraft:wool".getBytes(StandardCharsets.UTF_8);
            int offset = -1;
            for (int i = 2; i <= wire.writerIndex() - name.length; i++) {
                boolean found = true;
                for (int j = 0; j < name.length; j++) if (wire.getByte(i + j) != name[j]) { found = false; break; }
                if (found) { offset = i; break; }
            }
            assertTrue(offset >= 0);
            wire.setShort(offset - 2, TkIconDescriptor.MAX_NAME_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> new TkPackets.OpenGui().fromBytes(wire));
        } finally { wire.release(); }
    }

    @SuppressWarnings("unchecked")
    private static <T extends IMessage> T roundTrip(T packet) throws Exception {
        ByteBuf wire = Unpooled.buffer();
        try {
            packet.toBytes(wire);
            T decoded = (T) packet.getClass().getDeclaredConstructor().newInstance(); decoded.fromBytes(wire);
            assertFalse(wire.isReadable()); return decoded;
        } finally { wire.release(); }
    }
}
