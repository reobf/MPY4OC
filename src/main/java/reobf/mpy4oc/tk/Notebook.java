package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ttk::notebook (default theme): a row of tabs along the top, and below them, inside a 1 px raised
 * border, the page of the selected tab. Pages are children of the notebook that it lays out itself
 * (winfo_manager() 'notebook'); only the selected one is mapped. Which tab becomes current when
 * tabs are added, selected, hidden, disabled, forgotten or destroyed, and the geometry, follow
 * Tk 8.6's ttkNotebook.c and measurements of Tk 8.6.14.
 */
public final class Notebook extends Frame {
    static final int NORMAL = 0, DISABLED = 1, HIDDEN = 2;
    static final String[] STATES = {"normal", "disabled", "hidden"};
    /** The default theme's tab: at least 24 wide; a 1 px border (none at the bottom), 4/2 px padding, a 1 px focus ring. */
    static final int MIN_TAB_WIDTH = 24, TAB_PAD_X = 4, TAB_PAD_Y = 2;
    /** Unselected tabs are darker than the notebook (Tk's -darker colour of the default theme). */
    static final int UNSELECTED = 0xC3C3C3;

    static final class Tab {
        final TkWidget page;
        String text = "", sticky = "nsew", image = "", compound = "";
        int state = NORMAL, underline = -1;
        int[] padding = {0, 0, 0, 0};
        /** Where the tab was laid out (display coordinates); empty when hidden. */
        TkRect box = TkRect.EMPTY;
        Tab(TkWidget page) { this.page = page; }
    }

    final List<Tab> tabs = new ArrayList<>();
    /** The selected tab, or -1. */
    int current = -1;
    /** Python's callback: told when a player's click or key changes the tab. */
    int command;

    public Notebook(Frame parent) { super(parent.tree, parent, Kind.NOTEBOOK); }

    @Override String containerManager() { return "notebook"; }
    @Override void release(TkWidget child) { int index = indexOf(child); if (index >= 0) remove(index); }
    @Override int textBytes() {
        int bytes = 0;
        for (Tab tab : tabs) bytes += tab.text.getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    int indexOf(TkWidget page) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).page == page) return i;
        return -1;
    }
    private void check(int index) {
        if (index < 0 || index >= tabs.size()) throw new IllegalArgumentException("Slave index " + index + " out of bounds");
    }

    // ---- the tab list (NotebookAddCommand, AddTab, SelectTab, SelectNearestTab, TabRemoved) ----

    /** A new tab at the end; for a page that has one, its options, and the tab shown again if hidden. */
    void add(TkWidget page, Map<String, Object> options) {
        alive();
        int index = indexOf(page);
        if (index < 0) { insertNew(tabs.size(), page, options); return; }
        Tab tab = tabs.get(index);
        if (tab.state == HIDDEN) tab.state = NORMAL;    // as Tk: shown first, then the options
        configure(tab, options).run();
        if (index == current && tab.state != NORMAL) selectNearest();
        tree.changed(true);
    }

    /**
     * At position a new tab, or an existing one moved there. As in Tk, a number must name a tab
     * that is there (0 to n - 1, even for a new tab); -1 is "end": after the last tab, or the last
     * place for one that moves.
     */
    void insert(int position, TkWidget page, Map<String, Object> options) {
        alive();
        boolean end = position == -1;
        if (!end) check(position);
        int index = indexOf(page);
        if (index < 0) { insertNew(end ? tabs.size() : position, page, options); return; }
        if (end) position = tabs.size() - 1;
        Tab tab = tabs.get(index);
        configure(tab, options).run();
        if (current == index) current = position;
        else if (position <= current && current < index) current++;
        else if (index < current && current <= position) current--;
        tabs.add(position, tabs.remove(index));
        tree.changed(true);
    }

    private void insertNew(int position, TkWidget page, Map<String, Object> options) {
        if (page.parent != this || TkWindow.is(page)) throw new IllegalArgumentException("a page must be a child of its notebook");
        Tab tab = new Tab(page);
        Runnable apply = configure(tab, options);
        page.unmanage();
        page.container = this;
        apply.run();
        tabs.add(position, tab);
        if (current < 0) select(position);              // the first tab is selected (a hidden one shown)
        else if (current >= position) current++;
        tree.changed(true);
    }

    void forget(int index) { alive(); check(index); remove(index); }

    /** A saved tab, put back as it was (nothing selected yet: the save says which tab is current). */
    void restoreTab(TkWidget page, Map<String, Object> options) {
        if (indexOf(page) >= 0) throw new IllegalArgumentException("a page has one tab");
        Tab tab = new Tab(page);
        configure(tab, options).run();
        page.unmanage();
        page.container = this;
        tabs.add(tab);
        tree.changed(true);
    }

    private void remove(int index) {
        if (index == current) selectNearest();
        if (index < current) current--;
        Tab tab = tabs.remove(index);
        tab.page.container = null;                       // unmanaged: unmapped, manager ''
        tree.changed(true);
    }

    void hide(int index) {
        alive(); check(index);
        tabs.get(index).state = HIDDEN;
        if (index == current) selectNearest();
        tree.changed(true);
    }

    /** Select a tab: not a disabled one; a hidden one is shown. Whether the selection changed. */
    boolean select(int index) {
        alive(); check(index);
        Tab tab = tabs.get(index);
        if (index == current || tab.state == DISABLED) return false;
        if (tab.state == HIDDEN) tab.state = NORMAL;
        current = index;
        tree.changed(true);
        return true;
    }

    /** The current tab went away or became unusable: the next normal tab after it, else before it, else none. */
    private void selectNearest() {
        int next = -1;
        for (int i = current + 1; i < tabs.size() && next < 0; i++) if (tabs.get(i).state == NORMAL) next = i;
        for (int i = current - 1; i >= 0 && next < 0; i--) if (tabs.get(i).state == NORMAL) next = i;
        current = next;
    }

    void configureTab(int index, Map<String, Object> options) {
        alive(); check(index);
        configure(tabs.get(index), options).run();
        if (index == current && tabs.get(index).state != NORMAL) selectNearest();
        tree.changed(true);
    }

    /** A player's Left/Right: the next normal tab that way, round the end (ttk::notebook::CycleTab). Whether it changed. */
    boolean cycle(int direction) {
        if (current < 0 || tabs.isEmpty()) return false;
        int n = tabs.size(), select = Math.floorMod(current + direction, n);
        while (tabs.get(select).state != NORMAL && select != current) select = Math.floorMod(select + direction, n);
        return select != current && select(select);
    }

    /** Validate tab options, and return what applies them (nothing changes if one is bad). */
    private Runnable configure(Tab tab, Map<String, Object> options) {
        String text = tab.text, sticky = tab.sticky, image = tab.image, compound = tab.compound;
        int state = tab.state, underline = tab.underline;
        int[] padding = tab.padding;
        for (Map.Entry<String, Object> e : options.entrySet()) {
            Object value = e.getValue();
            switch (e.getKey()) {
                case "text": text = TkOptions.text(value); break;
                case "image": image = TkOptions.text(value); break;
                case "compound": compound = TkOptions.text(value); break;
                case "underline": underline = TkOptions.integer(value, -1, 1 << 20); break;
                case "state":
                    String name = TkOptions.text(value); state = java.util.Arrays.asList(STATES).indexOf(name);
                    if (state < 0) throw new IllegalArgumentException("bad state \"" + name + "\": must be normal, disabled, or hidden");
                    break;
                case "sticky": sticky = sticky(TkOptions.text(value)); break;
                case "padding": {
                    Object[] sides = TkOptions.sequence(value, 4);
                    if (sides.length != 4) throw new IllegalArgumentException("padding takes left, top, right and bottom");
                    padding = new int[4];
                    for (int i = 0; i < 4; i++) padding[i] = TkOptions.integer(sides[i], 0, 1024);
                    break;
                }
                default: throw new IllegalArgumentException("unknown option \"-" + e.getKey() + "\"");
            }
        }
        if (!text.equals(tab.text)) {
            TkFontMetrics.checkText(text);
            int bytes = textBytes() - tab.text.getBytes(StandardCharsets.UTF_8).length + text.getBytes(StandardCharsets.UTF_8).length;
            tree.checkTextBytes(this, bytes);
        }
        if (image.length() > 256 || compound.length() > 16) throw new IllegalArgumentException("tab option too long");
        String t = text, s = sticky, i = image, c = compound; int st = state, u = underline; int[] p = padding;
        return () -> { tab.text = t; tab.sticky = s; tab.image = i; tab.compound = c; tab.state = st; tab.underline = u; tab.padding = p; };
    }

    /** Tk's -sticky: any of n, s, e, w, given back in that order. */
    static String sticky(String value) {
        StringBuilder out = new StringBuilder();
        for (char side : value.toCharArray()) if ("nsew".indexOf(side) < 0) throw new IllegalArgumentException("Bad -sticky specification " + value);
        for (char side : "nsew".toCharArray()) if (value.indexOf(side) >= 0) out.append(side);
        return out.toString();
    }

    /** A tab's options for Python: text, state, sticky, padding (four), underline, image, compound. */
    Object[] tabOptions(int index) {
        check(index); Tab t = tabs.get(index);
        return new Object[] {t.text, STATES[t.state], t.sticky, t.padding[0], t.padding[1], t.padding[2], t.padding[3], t.underline, t.image, t.compound};
    }

    // ---- geometry ----

    private static int tabWidth(Tab tab) { return Math.max(MIN_TAB_WIDTH, TkFontMetrics.width(tab.text) + 2 * (1 + TAB_PAD_X + 1)); }
    private static int tabHeight(Tab tab) { return TkFontMetrics.height(tab.text) + 1 + 2 * (TAB_PAD_Y + 1); }
    int rowWidth() { int w = 0; for (Tab t : tabs) if (t.state != HIDDEN) w += tabWidth(t); return w; }
    int rowHeight() { int h = 0; for (Tab t : tabs) if (t.state != HIDDEN) h = Math.max(h, tabHeight(t)); return h; }

    /** Natural size: the widest page (with its tab's padding; or -width/-height) or the tab row, the border, the padding. */
    int[] natural() {
        int pageWidth = 0, pageHeight = 0;
        for (Tab t : tabs) {
            pageWidth = Math.max(pageWidth, t.page.requestedWidth + t.padding[0] + t.padding[2]);
            pageHeight = Math.max(pageHeight, t.page.requestedHeight + t.padding[1] + t.padding[3]);
        }
        if (width > 0) pageWidth = width;
        if (height > 0) pageHeight = height;
        return new int[] {Math.max(rowWidth(), pageWidth) + 2 + padLeft + padRight, rowHeight() + pageHeight + 2 + padTop + padBottom};
    }

    /** Lay the tabs out along the top, left to right; a shorter tab sits on the page border. */
    void layoutTabs() {
        int x = bounds.x + padLeft, top = bounds.y + padTop, row = rowHeight();
        for (Tab t : tabs) {
            if (t.state == HIDDEN) { t.box = TkRect.EMPTY; continue; }
            int w = tabWidth(t), h = tabHeight(t);
            t.box = new TkRect(x, top + row - h, w, h);
            x += w;
        }
    }

    /** The page's border (1 px) below the tabs. */
    TkRect clientBox() {
        int row = rowHeight();
        return new TkRect(bounds.x + padLeft, bounds.y + padTop + row,
                Math.max(0, bounds.width - padLeft - padRight), Math.max(0, bounds.height - padTop - padBottom - row));
    }

    /** Inside the page border. */
    TkRect clientInner() {
        TkRect c = clientBox();
        return new TkRect(c.x + 1, c.y + 1, Math.max(0, c.width - 2), Math.max(0, c.height - 2));
    }

    /** Where a tab's page goes: inside the border and the tab's padding, stuck to the sides its -sticky names. */
    TkRect pageBox(Tab tab) {
        TkRect c = clientInner();
        TkRect area = new TkRect(c.x + tab.padding[0], c.y + tab.padding[1],
                Math.max(0, c.width - tab.padding[0] - tab.padding[2]), Math.max(0, c.height - tab.padding[1] - tab.padding[3]));
        int w = Math.min(tab.page.requestedWidth, area.width), h = Math.min(tab.page.requestedHeight, area.height);
        boolean n = tab.sticky.indexOf('n') >= 0, s = tab.sticky.indexOf('s') >= 0, e = tab.sticky.indexOf('e') >= 0, west = tab.sticky.indexOf('w') >= 0;
        int x = e && west ? area.x : west ? area.x : e ? area.x + area.width - w : area.x + (area.width - w) / 2;
        int y = n && s ? area.y : n ? area.y : s ? area.y + area.height - h : area.y + (area.height - h) / 2;
        if (e && west) w = area.width;
        if (n && s) h = area.height;
        return new TkRect(x, y, w, h);
    }

    /** The tab at (x, y) (display coordinates), or -1. */
    int tabAt(int x, int y) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).box.contains(x, y)) return i;
        return -1;
    }

    /** The tab element at (x, y) (display coordinates) and the tab's index: its edge "tab", "padding", "focus", "label"; "" off the tabs. */
    Object[] identify(int x, int y) {
        int index = tabAt(x, y);
        if (index < 0) return new Object[] {"", -1};
        TkRect b = tabs.get(index).box;
        int left = x - b.x, top = y - b.y, right = b.x + b.width - 1 - x, bottom = b.y + b.height - 1 - y;
        int side = Math.min(left, right);
        String element = side < 1 || top < 1 ? "tab"
                : side < 1 + TAB_PAD_X || top < 1 + TAB_PAD_Y || bottom < TAB_PAD_Y ? "padding"
                : side < 2 + TAB_PAD_X || top < 2 + TAB_PAD_Y || bottom < TAB_PAD_Y + 1 ? "focus" : "label";
        return new Object[] {element, index};
    }

    /** Python's notebook operations. */
    Object call(String action, Object[] args) {
        switch (action) {
            case "add": add(page(args[0]), TkOptions.map(args[1])); return null;
            case "insert": insert(TkOptions.integer(args[0], -1, 1 << 20), page(args[1]), TkOptions.map(args[2])); return null;
            case "forget": forget(TkOptions.integer(args[0], -(1 << 20), 1 << 20)); return null;
            case "hide": hide(TkOptions.integer(args[0], -(1 << 20), 1 << 20)); return null;
            case "select": select(TkOptions.integer(args[0], -(1 << 20), 1 << 20)); return null;
            case "current": return current;
            case "selected": return current < 0 ? 0 : tabs.get(current).page.id;
            case "tabs": { Object[] ids = new Object[tabs.size()]; for (int i = 0; i < ids.length; i++) ids[i] = tabs.get(i).page.id; return ids; }
            case "tab": {
                int index = TkOptions.integer(args[0], -(1 << 20), 1 << 20);
                Map<String, Object> options = TkOptions.map(args[1]);
                if (options.isEmpty()) return tabOptions(index);
                configureTab(index, options); return null;
            }
            case "identify": {
                bounds();
                return identify(bounds.x + TkOptions.integer(args[0], -8192, 8192), bounds.y + TkOptions.integer(args[1], -8192, 8192));
            }
            default: throw new IllegalArgumentException("unsupported notebook operation: " + action);
        }
    }

    private TkWidget page(Object id) {
        int number = TkOptions.integer(id, 0, Integer.MAX_VALUE);
        if (tree.get(number) == null) throw new IllegalArgumentException("unknown widget");
        return tree.owned(number);
    }
}
