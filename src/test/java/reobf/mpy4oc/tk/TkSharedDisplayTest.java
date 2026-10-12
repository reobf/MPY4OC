package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The programs of one computer share a display: each Tk() is a window on the desktop with the
 * Toplevels it opens, and a program can reach only its own widgets and images.
 */
class TkSharedDisplayTest {
    @org.junit.jupiter.api.BeforeAll static void registerTile() {
        TkTestRegistration.ensure();
    }

    /** Tk() on the display from computer "pc": {token, root window id}. */
    static Object[] open(TileTkDisplay tile) {
        return (Object[]) tile.application().call("pc", "claim_free", "");
    }
    static Object call(TileTkDisplay tile, Object[] program, String operation, Object... args) {
        return tile.application().call("pc", operation, (String) program[0], args);
    }
    static List<Integer> ids(List<TkWidget> widgets) {
        return widgets.stream().map(w -> w.id).collect(Collectors.toList());
    }

    @Test void eachProgramHasItsOwnWindowsAndReachesOnlyThem() {
        TileTkDisplay tile = new TileTkDisplay();
        Object[] a = open(tile), b = open(tile);
        int wa = (Integer) a[1], wb = (Integer) b[1];
        assertNotEquals(a[0], b[0]);
        assertEquals(List.of(a[0], b[0]), tile.application().tokens());
        assertEquals(List.of(wa, wb), ids(tile.widgets().root().children()), "two windows on the desktop, the newer on top");
        int la = (Integer) call(tile, a, "create", "label", wa, Map.of("text", "a"));
        int lb = (Integer) call(tile, b, "create", "button", wb, Map.of("text", "b"));
        int image = (Integer) ((Object[]) call(tile, a, "image", 0, "create", Map.of("width", 2, "height", 2)))[0];
        for (Executable reach : List.<Executable>of(
                () -> call(tile, b, "configure", la, Map.of("text", "x")),
                () -> call(tile, b, "destroy", la),
                () -> call(tile, b, "cget", la, "text"),
                () -> call(tile, b, "create", "label", wa, Map.of()),
                () -> call(tile, b, "wm", wa, "title", Map.of("title", "x")),
                () -> call(tile, b, "wm", wb, "lift", Map.of("other", wa)),
                () -> call(tile, b, "configure", lb, Map.of("image", image)),
                () -> call(tile, b, "image", image, "size", Map.of()),
                () -> call(tile, b, "configure", 0, Map.of("background", 0)),
                () -> call(tile, b, "create", "label", 0, Map.of())))
            assertThrows(IllegalArgumentException.class, reach);
        assertEquals("a", call(tile, a, "cget", la, "text"), "nothing of a's changed");
        call(tile, a, "configure", la, Map.of("image", image));

        // Signals go to the program the widget belongs to; the desktop is nobody's.
        assertEquals(a[0], tile.application().tokenOf(tile.widgets().get(la)));
        assertEquals(b[0], tile.application().tokenOf(tile.widgets().get(lb)));
        assertEquals("", tile.application().tokenOf(tile.widgets().root()));
        int top = (Integer) call(tile, b, "create", "window", 0, Map.of());
        assertEquals(b[0], tile.application().tokenOf(tile.widgets().get(top)), "a Toplevel is its program's");
        assertThrows(IllegalArgumentException.class, () -> call(tile, b, "create", "window", wb, Map.of()), "Toplevels live on the desktop");

        // One program letting go closes its windows only; the last frees the display.
        call(tile, a, "release");
        assertNull(tile.widgets().get(wa)); assertNull(tile.widgets().get(la)); assertNull(tile.widgets().image(image));
        assertEquals(List.of(b[0]), tile.application().tokens());
        assertEquals("pc", tile.application().owner());
        assertThrows(IllegalArgumentException.class, () -> call(tile, a, "cget", la, "text"), "a's token is gone");
        assertEquals("b", call(tile, b, "cget", lb, "text"));
        call(tile, b, "release");
        assertTrue(tile.application().tokens().isEmpty());
        assertEquals("", tile.application().owner());
        assertTrue(tile.widgets().root().children().isEmpty());
    }

    @Test void anotherRunningComputerTakesTheDisplayOnlyByNamingIt() {
        TileTkDisplay tile = new TileTkDisplay();
        tile.ownerAlive = address -> true;
        Object[] a = open(tile);
        assertEquals("", tile.application().call("other", "claim_free", ""));
        Object[] taken = (Object[]) tile.application().call("other", "claim", "");
        assertEquals(List.of(taken[0]), tile.application().tokens(), "the old computer's programs are gone");
        assertEquals(List.of(taken[1]), ids(tile.widgets().root().children()));
        assertThrows(IllegalArgumentException.class, () -> call(tile, a, "cget", a[1], "text"));
    }

    @Test void aProgramBehindAnotherDoesNotTakeTheKeyboard() {
        TileTkDisplay tile = new TileTkDisplay();
        Object[] a = open(tile), b = open(tile);
        int wa = (Integer) a[1], wb = (Integer) b[1];
        int ea = (Integer) call(tile, a, "create", "entry", wa, Map.of());
        int eb = (Integer) call(tile, b, "create", "entry", wb, Map.of());
        call(tile, a, "pack", ea, Map.of()); call(tile, b, "pack", eb, Map.of());
        call(tile, b, "focus", eb);
        assertEquals(eb, tile.widgets().focused(), "b's window is on top: it takes the keyboard");
        assertEquals(eb, call(tile, b, "focused", wb));
        assertEquals(0, call(tile, a, "focused", wa), "a does not see b's focus");
        call(tile, a, "focus", ea);
        assertEquals(eb, tile.widgets().focused(), "a's window is behind b's: the keys stay with b");
        tile.widgets().focus(wa);   // the window manager raises a's window (a click on it)
        assertEquals(ea, tile.widgets().focused(), "a's window gives the keyboard to what a asked for");
        call(tile, a, "focus", ea);
        assertEquals(ea, call(tile, a, "focused", wa));
    }

    @Test void programsAndWhatTheyOwnSurviveNbt() {
        TileTkDisplay tile = new TileTkDisplay();
        Object[] a = open(tile), b = open(tile);
        int la = (Integer) call(tile, a, "create", "label", a[1], Map.of("text", "kept"));
        int image = (Integer) ((Object[]) call(tile, b, "image", 0, "create", Map.of("width", 2, "height", 2)))[0];
        NBTTagCompound saved = new NBTTagCompound(); tile.writeToNBT(saved);
        TileTkDisplay restored = new TileTkDisplay(); restored.readFromNBT(saved);
        assertEquals(tile.application().tokens(), restored.application().tokens());
        assertEquals("kept", call(restored, a, "cget", la, "text"));
        assertThrows(IllegalArgumentException.class, () -> call(restored, b, "cget", la, "text"));
        assertArrayEquals(new Object[] {2, 2}, (Object[]) call(restored, b, "image", image, "size", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> call(restored, a, "image", image, "size", Map.of()));
        Object[] c = open(restored);
        assertEquals(3, restored.application().tokens().size(), "a third program joins the two");
        assertNotEquals(a[1], c[1]); assertNotEquals(b[1], c[1]);
    }

    @Test void anOlderSaveWithItsProgramOnTheWholeDisplayIsDropped() {
        // Before programs shared displays: one token, and widgets right on the display root.
        TkTree old = new TkTree();
        new Label(old.root(), "old");
        NBTTagCompound application = new NBTTagCompound();
        application.setString("owner", "pc"); application.setString("token", UUID.randomUUID().toString());
        NBTTagCompound state = new NBTTagCompound();
        state.setInteger("width", 320); state.setInteger("height", 240);
        state.setTag("tree", TkTreePersistence.save(old, null)); state.setTag("application", application);
        NBTTagCompound saved = new NBTTagCompound(); saved.setTag("mpy4oc:display", state);
        TileTkDisplay tile = new TileTkDisplay(); tile.readFromNBT(saved);
        assertTrue(tile.application().tokens().isEmpty());
        assertEquals("", tile.application().owner());
        assertTrue(tile.widgets().root().children().isEmpty(), "an empty desktop");
        assertArrayEquals(new Object[] {320, 240}, tile.getResolution(null, null), "its canvas size is kept");
    }

    @Test void twoProgramsUploadingFilesAtOnceDoNotMixThePieces() throws Exception {
        TileTkDisplay tile = new TileTkDisplay();
        Object[] a = open(tile), b = open(tile);
        byte[] small = png(3, 2), large = png(5, 4);
        call(tile, a, "image", 0, "upload", Map.of("data", Arrays.copyOf(small, 10), "offset", 0));
        call(tile, b, "image", 0, "upload", Map.of("data", Arrays.copyOf(large, 10), "offset", 0));
        call(tile, a, "image", 0, "upload", Map.of("data", Arrays.copyOfRange(small, 10, small.length), "offset", 10));
        call(tile, b, "image", 0, "upload", Map.of("data", Arrays.copyOfRange(large, 10, large.length), "offset", 10));
        Object[] made = (Object[]) call(tile, a, "image", 0, "create", Map.of("upload", small.length));
        assertArrayEquals(new Object[] {3, 2}, Arrays.copyOfRange(made, 1, 3));
        made = (Object[]) call(tile, b, "image", 0, "create", Map.of("upload", large.length));
        assertArrayEquals(new Object[] {5, 4}, Arrays.copyOfRange(made, 1, 3));
    }

    @Test void aChangeThatWouldLeaveSomethingUndrawnIsAnError() {
        TileTkDisplay tile = TkTestGroups.joined(8, 8);   // 2560x2560: room for more rows than the display draws
        Object[] a = open(tile);
        int root = (Integer) a[1];
        call(tile, a, "wm", root, "geometry", Map.of("width", 2550, "height", 2500, "x", 0, "y", 0));
        Object[] rows = new Object[201];
        rows[0] = "end";
        for (int k = 1; k < rows.length; k++) rows[k] = "r" + k;
        int made = 0;
        IllegalArgumentException full = null;
        for (int i = 0; i < 80 && full == null; i++) {
            int box = (Integer) call(tile, a, "create", "listbox", root, Map.of());
            call(tile, a, "layout", box, "place", Map.of("x", i * 31, "y", 0, "width", 30, "height", 2480));
            try {
                call(tile, a, "listbox", box, "insert", rows);
                made++;
            } catch (IllegalArgumentException error) {
                full = error;
                assertTrue(tile.widgets().overflowed(), "the rows it added stay, undrawn: hence the error");
                call(tile, a, "destroy", box);
            }
        }
        assertNotNull(full, "the display never filled up");
        assertEquals(TkApplication.FULL, full.getMessage());
        assertTrue(made > 40, made + " listboxes");
        assertFalse(tile.widgets().overflowed(), "without the listbox that did not fit, everything is drawn");

        // Fill what is left, a row at a time: the row that does not fit is an error; taking it away is not.
        int last = (Integer) call(tile, a, "create", "listbox", root, Map.of());
        call(tile, a, "layout", last, "place", Map.of("x", 2500, "y", 0, "width", 30, "height", 2480));
        boolean filled = false;
        for (int k = 0; k < 200 && !filled; k++) {
            try {
                call(tile, a, "listbox", last, "insert", new Object[] {"end", "x" + k});
            } catch (IllegalArgumentException error) {
                filled = true;
                call(tile, a, "listbox", last, "delete", new Object[] {"end", "end"});
            }
        }
        assertTrue(filled);
        assertFalse(tile.widgets().overflowed());

        // A Toplevel would not be drawn (its title bar and edges need records too): it is not made.
        int before = tile.widgets().root().children().size();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> call(tile, a, "create", "window", 0, Map.of()));
        assertEquals(TkApplication.FULL, refused.getMessage());
        assertEquals(before, tile.widgets().root().children().size(), "the Toplevel was not made");
        assertFalse(tile.widgets().overflowed());
    }

    static byte[] png(int width, int height) throws java.io.IOException {
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "png", out);
        return out.toByteArray();
    }
}
