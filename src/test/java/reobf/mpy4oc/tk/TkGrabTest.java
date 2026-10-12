package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Step 29 on the server: wm transient (a dialog's window) and a program's grab (a modal dialog). */
class TkGrabTest {
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static Frame window(TkTree tree, int x, int y, int program) {
        tree.caller = program;
        Frame w = TkWindow.create(tree.root());
        tree.caller = 0;
        TkWindow.wm(tree, w, "geometry", options("width", 100, "height", 60, "x", x, "y", y));
        return w;
    }

    @Test void transientWindowsGoWithTheirMaster() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Frame main = window(tree, 10, 10, 1), dialog = window(tree, 40, 40, 1), other = window(tree, 80, 80, 1);
        assertEquals(3, TkWindow.tasks(tree.root()).size());
        assertEquals(main.id, TkWindow.wm(tree, dialog, "transient", options("master", main.id)));
        assertEquals(2, TkWindow.tasks(tree.root()).size(), "a transient window has no taskbar button");
        tree.raise(main);
        assertSame(dialog, tree.root().children.get(tree.root().children.size() - 1), "raising the master keeps its dialog above it");
        assertSame(main, tree.root().children.get(tree.root().children.size() - 2));
        tree.hide(main, true);
        assertTrue(dialog.bounds().empty(), "minimizing the master hides its dialog");
        tree.show(main, false);
        assertFalse(dialog.bounds().empty(), "and showing it brings the dialog back");
        assertThrows(IllegalArgumentException.class, () -> TkWindow.wm(tree, main, "transient", options("master", dialog.id)), "no cycles");
        TkWindow.wm(tree, dialog, "transient", options("master", 0));
        assertEquals(3, TkWindow.tasks(tree.root()).size());
        assertEquals(0, TkWindow.wm(tree, other, "transient", options()));
    }

    @Test void aGrabHoldsBackTheProgramsOtherWindows() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Frame main = window(tree, 10, 10, 1), dialog = window(tree, 120, 40, 1), elsewhere = window(tree, 200, 150, 2);
        Button inMain = new Button(main, "a"), inDialog = new Button(dialog, "b"), inOther = new Button(elsewhere, "c");
        inMain.pack(); inDialog.pack(); inOther.pack();
        assertFalse(tree.blocked(inMain));
        tree.grab(dialog);
        assertTrue(tree.blocked(inMain), "outside the grab");
        assertTrue(tree.blocked(main));
        assertFalse(tree.blocked(inDialog), "inside it");
        assertFalse(tree.blocked(inOther), "another program's windows are not held");
        assertSame(dialog, tree.grabOf(1));
        tree.raise(main);
        tree.raiseGrab(inMain);
        assertSame(dialog, tree.root().children.get(tree.root().children.size() - 1), "a press on the held window brings the dialog up");
        tree.grab(inDialog);
        assertSame(inDialog, tree.grabOf(1), "a new grab replaces the old one");
        tree.releaseGrab(dialog);
        assertSame(inDialog, tree.grabOf(1), "releasing another widget's grab does nothing");
        dialog.destroy();
        assertNull(tree.grabOf(1), "the grab goes with its widget");
        assertFalse(tree.blocked(inMain));
    }
}
