package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

class TkPersistenceTest {
    @org.junit.jupiter.api.BeforeAll static void registerTile() {
        TkTestRegistration.ensure();
    }
    @Test void restoresIdsPackOrderBindingsFocusAndUnicodeEntry() {
        TkTree tree = new TkTree();
        Frame frame = new Frame(tree.root()); frame.pack();
        Label removed = new Label(frame, "discard"); removed.destroy();
        Button first = new Button(frame, "first").command(47);
        Button second = new Button(frame, "second").command(48);
        second.pack(); first.pack();
        Entry entry = new Entry(frame); entry.text("中文😀abc"); entry.cursor(3); entry.pack();
        entry.bindings.put("<Return>", 123);
        new Label(frame, "hidden"); tree.focus(entry.id);
        NBTTagCompound saved = TkTreePersistence.save(tree);
        TkTree restored = TkTreePersistence.load(saved, 320, 240, () -> {});
        assertEquals(tree.render(), restored.render());
        assertEquals(47, ((Button) restored.get(first.id)).command());
        Entry read = (Entry) restored.get(entry.id);
        assertEquals(3, read.cursor()); assertEquals(123, read.bindings.get("<Return>"));
        assertEquals(entry.id, restored.focused());
        assertTrue(new Label(restored.root(), "new").id > entry.id + 1);
        assertNull(restored.get(removed.id));
    }

    @Test void rejectsInvalidOrDuplicatePackingWithoutExposingPartialTree() {
        TkTree tree = new TkTree(); Label child = new Label(tree.root(), "ok"); child.pack();
        NBTTagCompound saved = TkTreePersistence.save(tree);
        saved.getTagList("nodes", 10).getCompoundTagAt(0).setIntArray("packing", new int[] {child.id, child.id});
        assertThrows(IllegalArgumentException.class, () -> TkTreePersistence.load(saved, 320, 240, () -> fail("partial load leaked")));
        assertEquals("ok", tree.get(child.id).text());
    }

    @Test void applicationIdentityAndTreeSurviveNbtAndRejectReplacedOwner() {
        TileTkDisplay tile = new TileTkDisplay();
        Object[] opened = (Object[]) tile.application().call("computer-one", "claim", "");
        String token = (String) opened[0];
        int id = (Integer) tile.application().call("computer-one", "create", token, "label", opened[1], Map.of("text", "saved"));
        NBTTagCompound saved = new NBTTagCompound(); tile.writeToNBT(saved);
        TileTkDisplay restored = new TileTkDisplay(); restored.readFromNBT(saved);
        assertEquals(java.util.List.of(token), restored.application().tokens());
        assertEquals("saved", restored.application().call("computer-one", "cget", token, id, "text"));
        assertThrows(IllegalArgumentException.class, () -> restored.application().call("other", "destroy", token, id));
        String next = (String) ((Object[]) restored.application().call("computer-two", "claim", ""))[0];
        assertNotEquals(token, next);
        assertThrows(IllegalArgumentException.class, () -> restored.application().call("computer-one", "release", token));
        assertEquals(java.util.List.of(next), restored.application().tokens());
    }

    @Test void optionsAreValidatedBeforeMutationAndEntryEditsUseCodePoints() {
        TkTree tree = new TkTree(); Entry entry = new Entry(tree.root()); entry.pack();
        entry.text("a😀中z"); entry.cursor(3); entry.key(14, 0, 0);
        assertEquals("a😀z", entry.text()); assertEquals(2, entry.cursor());
        entry.paste("文\n字"); assertEquals("a😀文 字z", entry.text());
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(entry, Map.of("text", "mutated", "borderwidth", 17)));
        assertEquals("a😀文 字z", entry.text());
        TkOptions.configure(entry, Map.of("state", "readonly")); entry.paste("no");
        assertEquals("a😀文 字z", entry.text());
        TkOptions.configure(entry, Map.of("text", "via variable")); assertEquals("via variable", entry.text());
        entry.padding(1024, 1024, 16); tree.resize(64, 64); tree.focus(entry.id);
        assertDoesNotThrow(tree::render);
    }
}
