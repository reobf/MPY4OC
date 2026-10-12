package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ttk stage 5 on the server: a widget's look (TkLook rules by state), the pointer's hover, saving. */
class TkLookTest {
    private static Map<String, Object> options(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    /** TButton in the default theme: '.''s background map, the TButton relief map. */
    private static Map<String, Object> buttonLook() {
        Map<String, Object> look = new HashMap<>();
        look.put("background", new Object[] {new Object[] {TkLook.DISABLED, 0, 0xD9D9D9}, new Object[] {TkLook.ACTIVE, 0, 0xECECEC}, new Object[] {0, 0, 0xD9D9D9}});
        look.put("relief", new Object[] {new Object[] {TkLook.PRESSED, TkLook.DISABLED, "sunken"}, new Object[] {0, 0, "raised"}});
        return look;
    }

    @Test void rulesFollowTheState() {
        TkLook look = TkLook.parse(buttonLook());
        assertEquals(0xD9D9D9, look.color("background", 0, -1));
        assertEquals(0xECECEC, look.color("background", TkLook.ACTIVE, -1));
        assertEquals(0xD9D9D9, look.color("background", TkLook.ACTIVE | TkLook.DISABLED, -1), "the first rule that applies");
        assertEquals(TkWidget.Relief.SUNKEN, look.relief("relief", TkLook.PRESSED, null));
        assertEquals(TkWidget.Relief.RAISED, look.relief("relief", TkLook.PRESSED | TkLook.DISABLED, null), "a negated state");
        assertEquals(7, look.color("nothing", 0, 7));
        assertThrows(IllegalArgumentException.class, () -> TkLook.parse(options("relief", new Object[] {new Object[] {0, 0, "wavy"}})));
    }

    @Test void hoverIsTheWidgetUnderThePointer() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Button b = new Button(tree.root(), "b"); b.pack();
        Label l = new Label(tree.root(), "l"); l.pack();
        TkOptions.configure(b, options("look", buttonLook()));
        TkRect r = b.bounds();
        tree.pointerHover(r.x + 1, r.y + 1);
        assertEquals(TkLook.HOVER | TkLook.ACTIVE, tree.state(b) & (TkLook.HOVER | TkLook.ACTIVE));
        assertEquals(0xECECEC, tree.render().items.stream().filter(i -> i.id == b.id).findFirst().get().background);
        TkRect lr = l.bounds();
        tree.pointerHover(lr.x + 1, lr.y + 1);
        assertEquals(TkLook.HOVER, tree.state(l) & (TkLook.HOVER | TkLook.ACTIVE), "only buttons become active");
        assertEquals(0, tree.state(b) & TkLook.HOVER);
        TkOptions.configure(b, options("held", TkLook.ALTERNATE));
        assertEquals(TkLook.ALTERNATE, tree.state(b) & TkLook.ALTERNATE, "the program's states");
    }

    @Test void looksAreSaved() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        Button b = new Button(tree.root(), "b"); b.pack();
        TkOptions.configure(b, options("look", buttonLook(), "held", TkLook.INVALID, "minwidth", 9));
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree), 320, 240, () -> {});
        Button c = (Button) loaded.get(b.id);
        assertEquals(TkLook.INVALID, c.held);
        assertEquals(9, c.minColumns);
        assertEquals(0xECECEC, c.look.color("background", TkLook.ACTIVE, -1));
        assertEquals(TkWidget.Relief.SUNKEN, c.look.relief("relief", TkLook.PRESSED, null));
        assertTrue(c.bounds().width >= 9 * TkFontMetrics.CELL_WIDTH);
    }
}
