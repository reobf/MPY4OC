package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The posted menus (one chain per display, drawn above everything, like a Combobox's list):
 * the menu a window's menubar entry, a Menubutton or the program posted, and the cascades
 * opened from it. While posted they have the pointer and the keyboard (Tk's grab): the
 * highlight follows the pointer and opens cascades, a release on an entry invokes it (the
 * program hears of it), a press anywhere else unposts them all and goes no further. Keys as
 * Tk's menu bindings: Up/Down, Right/Left (cascades, or the menubar's next and previous
 * menus), Return/space, Escape, and an entry's underlined letter.
 */
final class TkMenus {
    static final class Level {
        final Menu menu;
        int x, y;
        Level(Menu menu, int x, int y) { this.menu = menu; this.x = x; this.y = y; }
        TkRect box() { return new TkRect(x, y, menu.boxWidth(), menu.boxHeight()); }
    }

    final TkTree tree;
    final List<Level> levels = new ArrayList<>();
    /** Where the chain came from: a window's menubar entry; a Menubutton; else the program. */
    final Frame bar;
    int barIndex = -1;
    final Menubutton button;
    /** Sessions whose press is the menus' (its release on an entry invokes it). */
    private final Set<Long> presses = new HashSet<>();

    private TkMenus(TkTree tree, Frame bar, Menubutton button) { this.tree = tree; this.bar = bar; this.button = button; }

    // ---- opening ------------------------------------------------------------------------

    /** A window's menubar entry index: its cascade drops down below the bar (a command entry just lights until released). */
    static TkMenus fromBar(TkTree tree, Frame window, int index) {
        TkMenus menus = new TkMenus(tree, window, null);
        menus.switchBar(index);
        return menus;
    }

    /** A Menubutton's menu, posted on its -direction side (flipped above when there is no room below, as MbPost). */
    static TkMenus fromButton(TkTree tree, Menubutton button, Menu menu) {
        TkMenus menus = new TkMenus(tree, null, button);
        int w = menu.boxWidth(), h = menu.boxHeight();
        int[] at = button.postAt(w, h);
        if (button.direction.equals("below") && at[1] + h > tree.height) at[1] = button.bounds.y - h;
        else if (button.direction.equals("above") && at[1] < 0) at[1] = button.bounds.y + button.bounds.height;
        menus.open(menu, at[0], at[1], true);
        return menus;
    }

    /** The program's post/tk_popup at (x, y); with entry >= 0 that entry is put under the point and highlighted. */
    static TkMenus fromProgram(TkTree tree, Menu menu, int x, int y, int entry) {
        TkMenus menus = new TkMenus(tree, null, null);
        if (entry >= 0 && entry < menu.entries.size()) y -= menu.entryY(entry) + Menu.ROW / 2;
        menus.open(menu, x, y, false);
        if (entry >= 0) menu.activate(entry);
        return menus;
    }

    /** Post menu at (x, y), kept inside the display; a player's posting tells a program with a -postcommand. */
    private Level open(Menu menu, int x, int y, boolean player) {
        for (Level l : levels) if (l.menu == menu) return null;   // a menu cascading into itself
        int w = menu.boxWidth(), h = menu.boxHeight();
        Level level = new Level(menu, Math.max(0, Math.min(x, tree.width - w)), Math.max(0, Math.min(y, tree.height - h)));
        levels.add(level);
        menu.active = -1;
        if (player && menu.postcommand) tree.notice(menu, menu.notify, "post");
        tree.changed(false);
        return level;
    }

    /** Close the levels from {@code from} on (their highlights go); none left from a menubar or button keeps the chain. */
    void close(int from) {
        while (levels.size() > from) {
            Level l = levels.remove(levels.size() - 1);
            l.menu.active = -1;
        }
        tree.changed(false);
    }

    /** The menubar menu of the window this chain came from, if both still are. */
    Menu barMenu() {
        if (bar == null || bar.window == null || tree.get(bar.id) != bar) return null;
        TkWidget m = bar.window.menu == 0 ? null : tree.get(bar.window.menu);
        return m instanceof Menu ? (Menu) m : null;
    }

    /** Drop down menubar entry index's menu (or only light it, for an entry that is not a cascade). */
    private void switchBar(int index) {
        close(0);
        barIndex = index;
        Menu menu = barMenu();
        if (menu == null || index < 0 || index >= menu.entries.size()) return;
        Menu.Entry e = menu.entries.get(index);
        Menu sub = cascade(e);
        if (sub == null || e.disabled) { tree.changed(false); return; }
        int[] slot = TkWindow.barEntry(bar, index);
        TkRect barBox = TkWindow.menubarBox(bar, bar.bounds);
        open(sub, slot == null ? barBox.x : slot[1], barBox.y + barBox.height, true);
    }

    private Menu cascade(Menu.Entry e) {
        if (e.type != Menu.Type.CASCADE || e.cascade == 0) return null;
        TkWidget m = tree.get(e.cascade);
        return m instanceof Menu ? (Menu) m : null;
    }

    /** Open entry index's cascade of level i to its right (left when there is no room), unless it is open. */
    private void openCascade(int i, int index) {
        Level parent = levels.get(i);
        Menu sub = cascade(parent.menu.entries.get(index));
        if (sub == null) { close(i + 1); return; }
        if (levels.size() > i + 1 && levels.get(i + 1).menu == sub) return;
        close(i + 1);
        TkRect box = parent.box();
        int x = box.x + box.width - 1, y = box.y + parent.menu.entryY(index) - Menu.BORDER;
        if (x + sub.boxWidth() > tree.width) x = box.x - sub.boxWidth() + 1;
        open(sub, x, y, true);
    }

    /** Whether the chain is still valid: its menus alive, its origin there. Drops what is gone; false when nothing is left. */
    boolean validate() {
        for (int i = 0; i < levels.size(); i++)
            if (tree.get(levels.get(i).menu.id) != levels.get(i).menu) { close(i); break; }
        if (bar != null) {
            Menu menu = barMenu();
            if (menu == null || bar.bounds.empty() || barIndex >= menu.entries.size()) return false;
            return true;
        }
        if (button != null && (tree.get(button.id) != button || button.bounds.empty())) return false;
        return !levels.isEmpty();
    }

    // ---- the pointer --------------------------------------------------------------------

    private int levelAt(int x, int y) {
        for (int i = levels.size() - 1; i >= 0; i--) if (levels.get(i).box().contains(x, y)) return i;
        return -1;
    }

    private int barEntryAt(int x, int y) {
        if (barMenu() == null || !TkWindow.menubarBox(bar, bar.bounds).contains(x, y)) return -1;
        return TkWindow.barEntryAt(bar, x, y);
    }

    private boolean onOrigin(int x, int y) {
        if (bar != null) return barEntryAt(x, y) >= 0;
        return button != null && button.bounds.contains(x, y);
    }

    /** The pointer moved (no button, or dragging a press of ours): highlight, cascades, the menubar's other menus. */
    void hover(int x, int y) {
        int i = levelAt(x, y);
        if (i >= 0) {
            Level l = levels.get(i);
            int index = l.menu.entryAt(y - l.y);
            select(l.menu, index);
            if (index >= 0 && l.menu.entries.get(index).selectable() && l.menu.entries.get(index).type == Menu.Type.CASCADE) openCascade(i, index);
            else close(i + 1);
            return;
        }
        int entry = barEntryAt(x, y);
        if (entry >= 0 && entry != barIndex) { switchBar(entry); return; }
        // Off every menu: the deepest one's highlight goes, unless it holds the open cascade.
        if (!levels.isEmpty()) {
            Level last = levels.get(levels.size() - 1);
            if (last.menu.active >= 0 && last.menu.entries.get(last.menu.active).type != Menu.Type.CASCADE) select(last.menu, -1);
        }
    }

    private void select(Menu menu, int index) {
        if (menu.activate(index) && menu.selectNotify) tree.notice(menu, menu.notify, "select", menu.active);
    }

    /** A press while posted: true always (the menus have the pointer); a press off them and their origin unposts them. */
    boolean press(long session, int x, int y, int button) {
        if (levelAt(x, y) >= 0) {
            if (button == 0) { hover(x, y); presses.add(session); }
            return true;
        }
        int entry = barEntryAt(x, y);
        if (entry >= 0 && button == 0) {
            // The open entry again closes it; another opens its menu.
            if (entry == barIndex) tree.unpostMenus();
            else { switchBar(entry); presses.add(session); }
            return true;
        }
        tree.unpostMenus();
        return true;
    }

    /** Begin holding a press (the one that posted the chain: on a menubar entry or the Menubutton). */
    void hold(long session) { presses.add(session); }

    boolean holds(long session) { return presses.contains(session); }

    boolean drag(long session, int x, int y) {
        if (!presses.contains(session)) return false;
        hover(x, y);
        return true;
    }

    /** The release of our press: on an entry it invokes it; off the menus and their origin it unposts them. */
    boolean release(long session, int x, int y) {
        if (!presses.remove(session)) return false;
        int i = levelAt(x, y);
        if (i >= 0) {
            Level l = levels.get(i);
            int index = l.menu.entryAt(y - l.y);
            if (index >= 0 && l.menu.entries.get(index).invokable()) invoke(l.menu, index);
            return true;
        }
        if (levels.isEmpty() && bar != null) {
            // A menubar entry that is not a cascade: released on it, it runs.
            Menu menu = barMenu();
            if (menu != null && barEntryAt(x, y) == barIndex && barIndex >= 0 && menu.entries.get(barIndex).invokable()) invoke(menu, barIndex);
            else tree.unpostMenus();
            return true;
        }
        if (!onOrigin(x, y)) tree.unpostMenus();
        return true;
    }

    void forget(long session) { presses.remove(session); }

    /** The player chose entry index of menu: everything unposts, then the program hears of it (it runs the entry: Menu.invoke). */
    private void invoke(Menu menu, int index) {
        tree.unpostMenus();
        tree.notice(menu, menu.notify, "invoke", index);
    }

    // ---- the keyboard -------------------------------------------------------------------

    void key(int code, int modifiers, int character) {
        if (levels.isEmpty()) {
            if (code == 1) tree.unpostMenus();
            else if (code == 205 || code == 203) nextBar(code == 205 ? 1 : -1);
            return;
        }
        int deepest = levels.size() - 1;
        Level level = levels.get(deepest);
        Menu menu = level.menu;
        switch (code) {
            case 200: case 208: select(menu, menu.next(menu.active, code == 200 ? -1 : 1)); break;
            case 205: {
                Menu.Entry e = menu.active >= 0 ? menu.entries.get(menu.active) : null;
                if (e != null && e.type == Menu.Type.CASCADE && cascade(e) != null) enter(deepest, menu.active);
                else if (bar != null) nextBar(1);
                break;
            }
            case 203:
                if (levels.size() > 1) close(deepest);
                else if (bar != null) nextBar(-1);
                break;
            case 28: case 156: case 57: choose(deepest, menu.active); break;
            case 1:
                if (levels.size() > 1) close(deepest); else tree.unpostMenus();
                break;
            default:
                // An entry's underlined letter (Tk's MenuFind within the menu).
                if (character > 0 && (modifiers & 2) == 0) {
                    for (int i = 0; i < menu.entries.size(); i++) {
                        Menu.Entry e = menu.entries.get(i);
                        if (e.selectable() && e.underline >= 0 && e.underline < e.label.length()
                                && Character.toLowerCase(e.label.charAt(e.underline)) == Character.toLowerCase((char) character)) {
                            select(menu, i);
                            choose(deepest, i);
                            break;
                        }
                    }
                }
        }
    }

    /** Return on entry index of level i: a cascade opens with its first entry lit; others run. */
    private void choose(int i, int index) {
        if (index < 0) return;
        Menu menu = levels.get(i).menu;
        Menu.Entry e = menu.entries.get(index);
        if (e.type == Menu.Type.CASCADE) enter(i, index);
        else if (e.invokable()) invoke(menu, index);
    }

    private void enter(int i, int index) {
        openCascade(i, index);
        if (levels.size() > i + 1) {
            Menu sub = levels.get(i + 1).menu;
            select(sub, sub.next(-1, 1));
        }
    }

    /** The menubar's next (or previous) cascade, wrapping, with its first entry lit. */
    private void nextBar(int step) {
        Menu menu = barMenu();
        if (menu == null) return;
        List<int[]> slots = menu.barEntries(0, Integer.MAX_VALUE / 2);
        if (slots.isEmpty()) return;
        int at = 0;
        for (int k = 0; k < slots.size(); k++) if (slots.get(k)[0] == barIndex) at = k;
        for (int k = 1; k <= slots.size(); k++) {
            int index = slots.get(Math.floorMod(at + step * k, slots.size()))[0];
            Menu.Entry e = menu.entries.get(index);
            if (e.selectable()) {
                switchBar(index);
                if (!levels.isEmpty()) { Menu sub = levels.get(0).menu; select(sub, sub.next(-1, 1)); }
                return;
            }
        }
    }

    // ---- painting -----------------------------------------------------------------------

    void paint(List<TkDisplayList.Item> out, TkRect screen) {
        for (Level l : levels) l.menu.paint(out, l.x, l.y, screen);
    }

    /** The program unposts menu: it and the cascades after it close (everything, for the first). */
    void unpost(Menu menu) {
        for (int i = 0; i < levels.size(); i++) if (levels.get(i).menu == menu) { if (i == 0) tree.unpostMenus(); else close(i); return; }
    }

    boolean posted(Menu menu) {
        for (Level l : levels) if (l.menu == menu) return true;
        return false;
    }
}
