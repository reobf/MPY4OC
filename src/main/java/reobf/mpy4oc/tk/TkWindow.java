package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;

/**
 * Window-manager state of a window (a program's Tk root or one of its Toplevels): a Frame on
 * the display root, positioned with place() (client origin, bordermode "ignore"), drawn with
 * a title bar, minimize/maximize/close buttons and a 1 px edge outside its client area, like
 * a desktop window. The display root is the desktop, shared by the programs of the computer
 * that holds the display; it holds only windows, topmost last in {@code root.children}.
 * While any window is shown or minimized, a taskbar along the bottom of the display lists
 * them, and the desktop's work area ends above it.
 */
final class TkWindow {
    /** Title bar height (one text line plus a pixel above and below) and title bar button width. */
    static final int TITLE = 18, CLOSE = 18, BUTTON = 18;
    /** Pixels of title bar that stay on the display when a window is dragged or moved. */
    static final int MIN_VISIBLE = 24;
    /** Resizing grabs the 1 px edge and GRIP pixels outside it; within CORNER pixels of a corner it resizes both ways. */
    static final int GRIP = 3, CORNER = 12;
    /** The taskbar: its height, the widest button and the gap around buttons. */
    static final int TASKBAR = 18, TASK_WIDTH = 120, TASK_MIN = 8, TASK_GAP = 2;
    static final int MAX_WINDOWS = 32;
    static final int ACTIVE = 0x2456A6, INACTIVE = 0x7A8290, EDGE = 0x404040, CLOSE_BACKGROUND = 0xC0392B,
            TASKBAR_BACKGROUND = 0x1E2430, TASK_BACKGROUND = 0x3A4252, TASK_ICONIC = 0x262B36, TASK_ICONIC_TEXT = 0x9AA2B0;
    static final String DEFAULT_TITLE = "tk";
    static final String MINIMIZE_GLYPH = "–", MAXIMIZE_GLYPH = "⧠", RESTORE_GLYPH = "⧉", CLOSE_GLYPH = "×";
    /** Paint parts of a window's decorations (part 0 is its client area). */
    static final int PART_EDGE = 1, PART_TITLE = 2, PART_CLOSE = 3, PART_TASK = 4, PART_MINIMIZE = 5, PART_MAXIMIZE = 6;
    /** The taskbar strip is a record of the first window on it (paint records need a widget id). */
    static final int PART_TASKBAR = 7;
    /** Paint parts of the resize hint, the edges a drag at the pointer would move: left, right, top, bottom. */
    static final int PART_HINT = 8;
    /** The hint's colour and width (the 1 px edge and the two pixels outside it). */
    static final int HINT = 0x5DA9FF, HINT_WIDTH = 3;

    enum Zone { NONE, EDGE, TITLE, CLOSE, MINIMIZE, MAXIMIZE, TASKBAR, MENUBAR }
    /** Paint parts of the menubar: its strip, then one per menu entry (part PART_MENUBAR + 1 + index). */
    static final int PART_MENUBAR = 16;
    /** Edges a resize drag moves. */
    static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;

    String title = DEFAULT_TITLE;
    /** The program the window belongs to (TkApplication's number for it), with everything in it. */
    int program;
    /** The widget that last had keyboard focus in this window; restored when it is clicked. */
    int lastFocus;
    /** Where a withdrawn or minimized window goes back to. */
    TkPlace restore;
    /** Minimized (Tk's "iconic"): hidden but on the taskbar. A hidden window that is not iconic is withdrawn. */
    boolean iconic;
    /** Maximized (Tk's "zoomed"): the placement to go back to; the window fills the work area meanwhile. */
    TkPlace normal;
    /** wm resizable: whether the player may resize each axis (and maximize, when both). */
    boolean resizableX = true, resizableY = true;
    /** wm minsize/maxsize of the client area; a maximum of 0 is no limit. */
    int minWidth = 1, minHeight = 1, maxWidth, maxHeight;
    /** The window's -menu: the Menu shown as its menubar (an id), 0 for none. */
    int menu;
    /** wm transient: the window this one belongs to (an id), 0 for none; hidden because that one was. */
    int master;
    boolean hiddenWithMaster;

    /** wm transient's master, if it is (still) a window. */
    static Frame master(Frame window) {
        int id = window.window.master;
        TkWidget m = id == 0 ? null : window.tree.get(id);
        return is(m) ? (Frame) m : null;
    }
    /** The windows transient for {@code window}. */
    static List<Frame> transients(Frame window) {
        List<Frame> out = new ArrayList<>();
        for (TkWidget child : window.tree.root().children) if (is(child) && master((Frame) child) == window) out.add((Frame) child);
        return out;
    }

    static boolean is(TkWidget widget) { return widget instanceof Frame && ((Frame) widget).window != null; }

    /** The window containing {@code widget} (itself if it is one), or null on the desktop. */
    static Frame of(TkWidget widget) {
        for (TkWidget w = widget; w != null; w = w.parent) if (is(w)) return (Frame) w;
        return null;
    }

    boolean zoomed() { return normal != null; }
    boolean maximizable() { return resizableX && resizableY; }
    int limitWidth(int width) { return Math.max(minWidth, maxWidth > 0 ? Math.min(width, maxWidth) : width); }
    int limitHeight(int height) { return Math.max(minHeight, maxHeight > 0 ? Math.min(height, maxHeight) : height); }

    // ---- decoration geometry -----------------------------------------------------------

    /** A minimize button only where the taskbar has a button to bring the window back. */
    private static boolean minimizable(Frame window) {
        for (Object[] task : taskButtons(window.tree)) if (task[0] == window) return true;
        return false;
    }
    private static int buttons(Frame window) {
        return 1 + (window.window.maximizable() ? 1 : 0) + (minimizable(window) ? 1 : 0);
    }
    /** Decorations are at least this wide, so a narrow window still has a title bar to drag. */
    static int minBar(Frame window) { return buttons(window) * BUTTON + MIN_VISIBLE; }
    static int decoratedWidth(Frame window, int clientWidth) { return Math.max(clientWidth, minBar(window)); }

    /** The window's menubar menu, if its -menu is (still) a Menu. */
    static Menu menubar(Frame window) {
        int id = window.window.menu;
        TkWidget m = id == 0 ? null : window.tree.get(id);
        return m instanceof Menu ? (Menu) m : null;
    }
    /** The decorations above the client area: the title bar, and the menubar under it if the window has one. */
    static int top(Frame window) { return TITLE + (menubar(window) != null ? Menu.BAR : 0); }

    static TkRect outer(Frame window, TkRect client) {
        int top = top(window);
        return new TkRect(client.x - 1, client.y - top - 1, decoratedWidth(window, client.width) + 2, client.height + top + 2);
    }
    static TkRect menubarBox(Frame window, TkRect client) {
        if (menubar(window) == null || client.empty()) return TkRect.EMPTY;
        return new TkRect(client.x, client.y - Menu.BAR, decoratedWidth(window, client.width), Menu.BAR);
    }
    /** The menubar's entries shown: {index, x, width} each. */
    static List<int[]> barEntries(Frame window) {
        Menu menu = menubar(window);
        TkRect bar = menubarBox(window, window.bounds);
        return menu == null || bar.empty() ? new ArrayList<>() : menu.barEntries(bar.x, bar.width);
    }
    /** Menubar entry index's slot {index, x, width}, or null if it is not shown. */
    static int[] barEntry(Frame window, int index) {
        for (int[] slot : barEntries(window)) if (slot[0] == index) return slot;
        return null;
    }
    /** The menubar entry at (x, y), or -1. */
    static int barEntryAt(Frame window, int x, int y) {
        if (!menubarBox(window, window.bounds).contains(x, y)) return -1;
        for (int[] slot : barEntries(window)) if (x >= slot[1] && x < slot[1] + slot[2]) return slot[0];
        return -1;
    }
    static TkRect closeBox(Frame window, TkRect client) {
        return new TkRect(client.x + decoratedWidth(window, client.width) - BUTTON, client.y - top(window), BUTTON, TITLE);
    }
    static TkRect maximizeBox(Frame window, TkRect client) {
        if (!window.window.maximizable()) return TkRect.EMPTY;
        TkRect close = closeBox(window, client);
        return new TkRect(close.x - BUTTON, close.y, BUTTON, TITLE);
    }
    static TkRect minimizeBox(Frame window, TkRect client) {
        if (!minimizable(window)) return TkRect.EMPTY;
        TkRect right = window.window.maximizable() ? maximizeBox(window, client) : closeBox(window, client);
        return new TkRect(right.x - BUTTON, right.y, BUTTON, TITLE);
    }
    static TkRect titleBar(Frame window, TkRect client) {
        return new TkRect(client.x, client.y - top(window), decoratedWidth(window, client.width) - buttons(window) * BUTTON, TITLE);
    }

    /** The decoration of {@code window} at (x, y): its buttons, title bar, edge or resize band. */
    static Zone zone(Frame window, int x, int y) {
        TkRect client = window.bounds;
        if (client.empty() || client.contains(x, y)) return Zone.NONE;
        if (outer(window, client).contains(x, y)) {
            if (closeBox(window, client).contains(x, y)) return Zone.CLOSE;
            if (maximizeBox(window, client).contains(x, y)) return Zone.MAXIMIZE;
            if (minimizeBox(window, client).contains(x, y)) return Zone.MINIMIZE;
            if (titleBar(window, client).contains(x, y)) return Zone.TITLE;
            if (menubarBox(window, client).contains(x, y)) return Zone.MENUBAR;
            return Zone.EDGE;
        }
        return resizeEdges(window, x, y) != 0 ? Zone.EDGE : Zone.NONE;
    }

    /**
     * The edges a press at (x, y) resizes: on the outer 1 px edge or up to {@link #GRIP} pixels
     * outside it, both ways near a corner; none for a maximized window or a fixed axis.
     */
    static int resizeEdges(Frame window, int x, int y) {
        TkWindow w = window.window;
        TkRect client = window.bounds;
        if (client.empty() || w.zoomed() || !w.resizableX && !w.resizableY) return 0;
        TkRect o = outer(window, client);
        if (x < o.x - GRIP || y < o.y - GRIP || x >= o.x + o.width + GRIP || y >= o.y + o.height + GRIP) return 0;
        boolean left = x <= o.x, right = x >= o.x + o.width - 1, top = y <= o.y, bottom = y >= o.y + o.height - 1;
        if (!left && !right && !top && !bottom) return 0;
        if (left || right) { top |= y < o.y + CORNER; bottom |= y >= o.y + o.height - CORNER; }
        if (top || bottom) { left |= x < o.x + CORNER; right |= x >= o.x + o.width - CORNER; }
        // A frame smaller than two corners: only the nearer of two opposite edges.
        if (top && bottom) { if (y - o.y <= o.y + o.height - 1 - y) bottom = false; else top = false; }
        if (left && right) { if (x - o.x <= o.x + o.width - 1 - x) right = false; else left = false; }
        int edges = (w.resizableX ? (left ? LEFT : 0) | (right ? RIGHT : 0) : 0) | (w.resizableY ? (top ? TOP : 0) | (bottom ? BOTTOM : 0) : 0);
        return edges;
    }

    /**
     * Keep a window reachable: its title bar fully below the top edge and above the work
     * area's bottom, and at least {@link #MIN_VISIBLE} pixels of its draggable part (left of
     * the buttons) inside the display, so a window flung off either side can be dragged back.
     */
    static int clampX(Frame window, int x, int width, int screenWidth) {
        int title = decoratedWidth(window, width) - buttons(window) * BUTTON;
        return Math.max(-8192, Math.min(8192, Math.max(MIN_VISIBLE - title, Math.min(x, screenWidth - MIN_VISIBLE))));
    }
    static int clampY(Frame window, int y, int workHeight) {
        return Math.max(top(window), Math.min(y, Math.min(workHeight, 8192)));
    }

    /** A maximized window's placement: the title bar (and menubar) at the top, the client filling the work area. */
    static TkPlace zoomPlace(Frame window, int screenWidth, int workHeight) {
        int top = top(window);
        return place(0, top, Math.max(1, Math.min(8192, screenWidth)), Math.max(1, Math.min(8192, workHeight - top)));
    }

    /** Whether a saved place is one the window manager itself would make (see {@link #place}). */
    static boolean canonical(TkPlace p) {
        return p.relx == 0 && p.rely == 0 && p.relwidth == null && p.relheight == null
                && p.anchor == TkPack.Anchor.NW && p.bordermode.equals("ignore")
                && (p.width == null || p.width > 0) && (p.height == null || p.height > 0);
    }
    static boolean same(TkPlace a, TkPlace b) {
        return a.x == b.x && a.y == b.y && java.util.Objects.equals(a.width, b.width) && java.util.Objects.equals(a.height, b.height);
    }

    /** Default position of a new window: cascade from the top left. */
    static TkPlace cascade(Frame window, int index) {
        int step = 24 * (index % 8);
        return place(16 + step, top(window) + 16 + step, null, null);
    }

    static TkPlace place(int x, int y, Integer width, Integer height) {
        return new TkPlace(x, y, width, height, 0, 0, null, null, TkPack.Anchor.NW, "ignore");
    }

    /** Windows of {@code root}, bottom to top. */
    static List<Frame> windows(Frame root) {
        List<Frame> out = new ArrayList<>();
        for (TkWidget child : root.children) if (is(child)) out.add((Frame) child);
        return out;
    }

    /** Windows with a taskbar button, in creation order: shown or minimized, not withdrawn. */
    static List<Frame> tasks(Frame root) {
        List<Frame> out = new ArrayList<>();
        // A transient window (a dialog) has no button of its own: it comes and goes with its master.
        for (TkWidget child : root.children)
            if (is(child) && (child.place != null || ((Frame) child).window.iconic) && master((Frame) child) == null) out.add((Frame) child);
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    /** Taskbar buttons left to right, as {window, rect}; those that do not fit are left out. */
    static List<Object[]> taskButtons(TkTree tree) {
        List<Object[]> out = new ArrayList<>();
        List<Frame> tasks = tasks(tree.root());
        if (tasks.isEmpty()) return out;
        int top = tree.workHeight();
        int width = Math.max(TASK_MIN, Math.min(TASK_WIDTH, (tree.width - TASK_GAP) / tasks.size() - TASK_GAP));
        // Windows past the last button that fits have no minimize button either (see minimizable).
        int x = TASK_GAP;
        for (Frame task : tasks) {
            if (x + width > tree.width) break;
            out.add(new Object[] {task, new TkRect(x, top + 1, width, TASKBAR - 2)});
            x += width + TASK_GAP;
        }
        return out;
    }

    /**
     * Set the window's -menu (0 for none). The decorations above the client area change height with
     * it; the window's top stays where it is (Tk keeps the wrapper's place), so the client moves.
     */
    static void setMenu(Frame window, int id) {
        TkWindow w = window.window;
        int before = top(window);
        w.menu = id;
        int delta = top(window) - before;
        if (delta != 0) {
            if (window.place != null && !w.zoomed()) window.place = shifted(window.place, delta);
            if (w.restore != null) w.restore = shifted(w.restore, delta);
            if (w.normal != null) w.normal = shifted(w.normal, delta);
        }
        window.tree.changed(true);
    }
    private static TkPlace shifted(TkPlace p, int dy) { return place(p.x, p.y + dy, p.width, p.height); }

    /** Create a Toplevel on the desktop: placed by cascade, 200x200 until it has children. */
    static Frame create(Frame root) {
        if (root.parent != null) throw new IllegalArgumentException("a Toplevel belongs to the display root");
        int count = windows(root).size();
        if (count >= MAX_WINDOWS) throw new IllegalArgumentException("window limit reached");
        Frame frame = new Frame(root);
        frame.window = new TkWindow();
        frame.window.program = root.tree.caller;
        // Constructed as an ordinary child (before the windows); move it to the top.
        root.children.remove(frame); root.children.add(frame);
        frame.width = 200; frame.height = 200;
        frame.place = cascade(frame, count);
        frame.tree.changed(true);
        return frame;
    }

    /**
     * Window-manager requests from Python. Geometry positions are Tk's: the top left of the
     * whole window including the title bar; "right"/"bottom" measure x/y from the right edge
     * of the display and the bottom of the work area (Tk's -X-Y). Sizes are the client area;
     * "" (or 0) returns to the natural size.
     */
    static Object wm(TkTree tree, Frame window, String action, java.util.Map<String, Object> m) {
        TkWindow w = window.window;
        switch (action) {
            case "geometry": {
                TkGeometry.keys(m, "width", "height", "x", "y", "right", "bottom");
                // Validate everything before changing anything: a bad offset must not half-apply.
                boolean changing = !m.isEmpty();
                TkPlace p = changing && w.zoomed() ? w.normal : current(window);
                Integer width = m.containsKey("width") ? size(m.get("width")) : p.width;
                Integer height = m.containsKey("height") ? size(m.get("height")) : p.height;
                boolean moving = m.containsKey("x") || m.containsKey("y");
                int x = m.containsKey("x") ? TkOptions.integer(m.get("x"), -8192, 8192) : p.x;
                int top = top(window);
                int y = m.containsKey("y") ? TkOptions.integer(m.get("y"), -8192, 8192) : p.y - top;
                boolean right = m.containsKey("right") && TkOptions.bool(m.get("right"));
                boolean bottom = m.containsKey("bottom") && TkOptions.bool(m.get("bottom"));
                if (changing) {
                    // A new geometry ends maximization, as a window manager does.
                    w.normal = null;
                    set(window, place(p.x, p.y, width, height));
                    tree.changed(true);
                }
                if (moving) {
                    tree.render(); // the natural size, to anchor a window to the right or bottom edge
                    int cw = w.limitWidth(width != null ? width : window.requestedWidth);
                    int ch = w.limitHeight(height != null ? height : window.requestedHeight);
                    // -X: the title bar's right end (decorations can be wider than the client);
                    // -Y: the bottom of the work area the window has once shown (showing it brings the taskbar).
                    int work = Math.max(1, tree.height - TASKBAR);
                    if (right) x = tree.width - decoratedWidth(window, cw) - x;
                    if (bottom) y = work - ch - top - y;
                    set(window, place(clampX(window, x, cw, tree.width), clampY(window, y + top, work), width, height));
                    tree.changed(true);
                }
                tree.render();
                TkPlace now = current(window);
                int cw = window.bounds.empty() ? w.limitWidth(now.width != null ? now.width : window.requestedWidth) : window.bounds.width;
                int ch = window.bounds.empty() ? w.limitHeight(now.height != null ? now.height : window.requestedHeight) : window.bounds.height;
                return new Object[] {cw, ch, now.x, now.y - top};
            }
            case "title":
                TkGeometry.keys(m, "title");
                if (m.containsKey("title")) {
                    String title = TkOptions.text(m.get("title"));
                    if (!TkScene.validTitle(title)) throw new IllegalArgumentException("invalid window title");
                    w.title = title; tree.changed(false);
                }
                return w.title;
            case "transient": {
                TkGeometry.keys(m, "master");
                if (m.containsKey("master")) {
                    int id = TkOptions.integer(m.get("master"), 0, Integer.MAX_VALUE);
                    if (id != 0) {
                        TkWidget other = tree.owned(id);
                        if (!is(other)) throw new IllegalArgumentException("a transient's master must be a Toplevel");
                        for (Frame f = (Frame) other; f != null; f = master(f))
                            if (f == window) throw new IllegalArgumentException("setting \"" + "master\" would cause a cycle");
                    }
                    w.master = id;
                    tree.changed(true);
                    if (id != 0) tree.raise((Frame) tree.get(id));   // above its master
                }
                return w.master;
            }
            case "lift": case "lower": {
                TkGeometry.keys(m, "other");
                TkWidget other = null;
                if (m.containsKey("other")) {
                    other = tree.owned(TkOptions.integer(m.get("other"), 0, Integer.MAX_VALUE));
                    if (!is(other)) throw new IllegalArgumentException("can only stack a Toplevel relative to another Toplevel");
                }
                tree.restack(window, action.equals("lift"), other);
                return null;
            }
            case "withdraw":
                TkGeometry.keys(m);
                tree.hide(window, false);
                return null;
            case "iconify":
                TkGeometry.keys(m);
                tree.hide(window, true);
                return null;
            case "deiconify":
                TkGeometry.keys(m);
                tree.show(window, true);
                return null;
            case "state":
                TkGeometry.keys(m, "state");
                if (m.containsKey("state")) {
                    String state = TkOptions.text(m.get("state"));
                    switch (state) {
                        // Coming back from hidden, the window also gets the keyboard (as deiconify does).
                        case "normal": tree.show(window, window.place == null); if (w.zoomed()) tree.zoom(window, false); break;
                        // Only a window the player can restore (one with a maximize button) is maximized.
                        case "zoomed": tree.show(window, window.place == null); if (!w.zoomed() && w.maximizable()) tree.zoom(window, true); break;
                        case "iconic": tree.hide(window, true); break;
                        case "withdrawn": tree.hide(window, false); break;
                        default: throw new IllegalArgumentException("bad window state \"" + state + "\": must be normal, iconic, withdrawn, or zoomed");
                    }
                }
                return state(window);
            case "resizable":
                TkGeometry.keys(m, "width", "height");
                if (!m.isEmpty()) {
                    boolean x = m.containsKey("width") ? TkOptions.bool(m.get("width")) : w.resizableX;
                    boolean y = m.containsKey("height") ? TkOptions.bool(m.get("height")) : w.resizableY;
                    if (x != w.resizableX || y != w.resizableY) {
                        w.resizableX = x; w.resizableY = y;
                        // Not maximizable any more: back to the normal size.
                        if (!w.maximizable() && w.zoomed()) tree.zoom(window, false);
                        tree.changed(true);
                    }
                }
                return new Object[] {w.resizableX, w.resizableY};
            case "minsize": case "maxsize": {
                TkGeometry.keys(m, "width", "height");
                boolean min = action.equals("minsize");
                if (!m.isEmpty()) {
                    // Tk takes any minimum (0 or less is none, kept as 1); a maximum of 0 is no limit.
                    int width = m.containsKey("width") ? limit(m.get("width"), min) : min ? w.minWidth : w.maxWidth;
                    int height = m.containsKey("height") ? limit(m.get("height"), min) : min ? w.minHeight : w.maxHeight;
                    if (min) { w.minWidth = width; w.minHeight = height; } else { w.maxWidth = width; w.maxHeight = height; }
                    tree.changed(true);
                }
                // Tk's default maximum is the screen.
                return min ? new Object[] {w.minWidth, w.minHeight}
                        : new Object[] {w.maxWidth > 0 ? w.maxWidth : tree.width, w.maxHeight > 0 ? w.maxHeight : tree.height};
            }
            default: throw new IllegalArgumentException("unsupported window manager command: " + action);
        }
    }

    static String state(Frame window) {
        TkWindow w = window.window;
        return window.place == null ? (w.iconic ? "iconic" : "withdrawn") : w.zoomed() ? "zoomed" : "normal";
    }

    private static TkPlace current(Frame window) {
        return window.place != null ? window.place : window.window.restore != null ? window.window.restore : cascade(window, 0);
    }
    private static void set(Frame window, TkPlace place) {
        if (window.place != null) window.place = place; else window.window.restore = place;
    }
    private static int limit(Object value, boolean min) {
        return min ? Math.max(1, TkOptions.integer(value, -8192, 8192)) : TkOptions.integer(value, 0, 8192);
    }
    private static Integer size(Object value) {
        if ("".equals(value)) return null;
        int n = TkOptions.integer(value, 0, 8192);
        return n == 0 ? null : n;
    }

    // ---- paint --------------------------------------------------------------------------

    /**
     * Paint records of the decorations, in paint order before the client area: the edge
     * behind everything, the title bar and its buttons. Clipped to the work area.
     */
    static void paint(Frame window, boolean active, Zone pressed, int hint, int openBar, TkRect screen, List<TkDisplayList.Item> out) {
        TkRect client = window.bounds;
        if (client.empty()) return;
        TkWindow w = window.window;
        TkRect o = outer(window, client);
        add(out, window, PART_EDGE, TkWidget.Kind.FRAME, o, screen, EDGE, EDGE, 0, 0, "", 0, 0);
        hint(out, window, o, hint, screen);
        int bar = active ? ACTIVE : INACTIVE;
        TkRect title = titleBar(window, client);
        if (!title.empty()) add(out, window, PART_TITLE, TkWidget.Kind.LABEL, title, screen, bar, 0xFFFFFF, 0, 0, fit(w.title, title.width - 4), title.x + 4, title.y + 1);
        button(out, window, PART_MINIMIZE, minimizeBox(window, client), screen, bar, pressed == Zone.MINIMIZE, MINIMIZE_GLYPH);
        button(out, window, PART_MAXIMIZE, maximizeBox(window, client), screen, bar, pressed == Zone.MAXIMIZE, w.zoomed() ? RESTORE_GLYPH : MAXIMIZE_GLYPH);
        button(out, window, PART_CLOSE, closeBox(window, client), screen, CLOSE_BACKGROUND, pressed == Zone.CLOSE, CLOSE_GLYPH);
        Menu menu = menubar(window);
        if (menu != null) menu.paintBar(out, window.id, PART_MENUBAR, menubarBox(window, client), screen, openBar);
    }

    private static void button(List<TkDisplayList.Item> out, Frame window, int part, TkRect box, TkRect screen, int background, boolean pressed, String glyph) {
        if (box.empty()) return;
        add(out, window, part, TkWidget.Kind.BUTTON, box, screen, background, 0xFFFFFF, 1, pressed ? TkDisplayList.PRESSED : 0,
                glyph, box.x + (box.width - TkFontMetrics.width(glyph)) / 2, box.y + 1);
    }

    /** The taskbar: a strip below the work area and a button per window (the active one highlighted). */
    static void paintTaskbar(TkTree tree, Frame active, Frame pressed, List<TkDisplayList.Item> out) {
        TkRect screen = new TkRect(0, 0, tree.width, tree.height);
        TkRect strip = new TkRect(0, tree.workHeight(), tree.width, tree.height - tree.workHeight());
        TkRect clip = strip.intersect(screen);
        List<Frame> tasks = tasks(tree.root());
        if (clip.empty() || tasks.isEmpty()) return;
        out.add(new TkDisplayList.Item(tasks.get(0).id, PART_TASKBAR, TkWidget.Kind.FRAME, strip, clip, TASKBAR_BACKGROUND, TASKBAR_BACKGROUND,
                0, 0, 0, 0, "", -1, 0, 0));
        for (Object[] task : taskButtons(tree)) {
            Frame window = (Frame) task[0];
            TkRect box = (TkRect) task[1];
            boolean iconic = window.place == null;
            add(out, window, PART_TASK, TkWidget.Kind.BUTTON, box, screen, window == active ? ACTIVE : iconic ? TASK_ICONIC : TASK_BACKGROUND,
                    iconic ? TASK_ICONIC_TEXT : 0xFFFFFF, 1, window == pressed ? TkDisplayList.PRESSED : 0, fit(window.window.title, box.width - 4), box.x + 4, box.y);
        }
    }

    /** The start of {@code text} that fits in {@code pixels} (plus one partly visible glyph). */
    static String fit(String text, int pixels) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            if (width >= pixels) return text.substring(0, i);
            int cp = text.codePointAt(i);
            width += TkFontMetrics.advance(cp);
            i += Character.charCount(cp);
        }
        return text;
    }

    /**
     * The resize hint: the edges a press at the pointer would move (resizeEdges), lit so the
     * player sees where a drag resizes. A corner lights its two sides' corner lengths, an edge
     * its whole side.
     */
    private static void hint(List<TkDisplayList.Item> out, Frame window, TkRect o, int edges, TkRect screen) {
        if (edges == 0) return;
        boolean corner = (edges & (LEFT | RIGHT)) != 0 && (edges & (TOP | BOTTOM)) != 0;
        int outside = HINT_WIDTH - 1;
        int x0 = o.x - outside, y0 = o.y - outside, x1 = o.x + o.width + outside, y1 = o.y + o.height + outside;
        int length = CORNER + outside;
        int ys = !corner || (edges & TOP) != 0 ? y0 : y1 - length, ylen = corner ? length : y1 - y0;
        int xs = !corner || (edges & LEFT) != 0 ? x0 : x1 - length, xlen = corner ? length : x1 - x0;
        if ((edges & LEFT) != 0) add(out, window, PART_HINT, TkWidget.Kind.FRAME, new TkRect(x0, ys, HINT_WIDTH, ylen), screen, HINT, HINT, 0, 0, "", 0, 0);
        if ((edges & RIGHT) != 0) add(out, window, PART_HINT + 1, TkWidget.Kind.FRAME, new TkRect(x1 - HINT_WIDTH, ys, HINT_WIDTH, ylen), screen, HINT, HINT, 0, 0, "", 0, 0);
        if ((edges & TOP) != 0) add(out, window, PART_HINT + 2, TkWidget.Kind.FRAME, new TkRect(xs, y0, xlen, HINT_WIDTH), screen, HINT, HINT, 0, 0, "", 0, 0);
        if ((edges & BOTTOM) != 0) add(out, window, PART_HINT + 3, TkWidget.Kind.FRAME, new TkRect(xs, y1 - HINT_WIDTH, xlen, HINT_WIDTH), screen, HINT, HINT, 0, 0, "", 0, 0);
    }

    private static void add(List<TkDisplayList.Item> out, Frame window, int part, TkWidget.Kind kind, TkRect rect, TkRect screen,
                            int background, int foreground, int border, int state, String text, int tx, int ty) {
        TkRect clip = rect.intersect(screen);
        if (!clip.empty()) out.add(new TkDisplayList.Item(window.id, part, kind, rect, clip, background, foreground,
                border, state, tx, ty, text, -1, 0, 0));
    }
}
