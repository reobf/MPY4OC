package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Headless widget model. All mutations and input are performed on the server thread. */
public final class TkTree {
    final Map<Integer, TkWidget> widgets = new LinkedHashMap<>();
    /** PhotoImages of this display; ids are never reused. */
    final Map<Integer, TkImage> images = new LinkedHashMap<>();
    int nextImage = 1;
    private final Map<Long, Button> pressed = new HashMap<>();
    private static final class Capture {
        final TkWidget widget; final int item; int x, y;
        /**
         * A window-manager drag: {@link #widget} is the window, pressed at (grabX, grabY) while its
         * client area was {@link #start}; it moves (no edges) or resizes by those edges.
         */
        final boolean windowDrag; final int edges, grabX, grabY; final TkRect start;
        Capture(TkWidget widget, int x, int y) {
            this.widget = widget; this.x = x; this.y = y; item = widget instanceof Canvas ? ((Canvas) widget).hitItem(x, y) : 0;
            windowDrag = false; edges = grabX = grabY = 0; start = null;
        }
        Capture(Frame window, int x, int y, int edges) {
            widget = window; this.x = x; this.y = y; item = 0; windowDrag = true; this.edges = edges; grabX = x; grabY = y;
            start = new TkRect(window.place.x, window.place.y, window.bounds.width, window.bounds.height);
        }
    }
    private final Map<Long, Capture> captured = new HashMap<>();
    /** Sashes players drag: {panedwindow id, sash index, sash start minus the pointer along the panes}. */
    private final Map<Long, int[]> sashDrags = new HashMap<>();
    /** The panedwindow whose sash is lit (under the pointer or dragged), or null; never saved. */
    private Panedwindow litPaned;
    /** A title bar button or taskbar button held down; released over the same one, it acts. */
    private static final class WmPress {
        final Frame window; final TkWindow.Zone zone;
        WmPress(Frame window, TkWindow.Zone zone) { this.window = window; this.zone = zone; }
    }
    private final Map<Long, WmPress> wmPressed = new HashMap<>();
    /** Presses the window manager took without anything to do on release (a maximized title bar, a double click, the bare taskbar). */
    private final java.util.Set<Long> swallowed = new java.util.HashSet<>();
    /** Right-button presses by session: the widget that got {@code <Button-3>}, or -1 when the window manager had it. */
    private final Map<Long, Integer> rightPresses = new HashMap<>();
    private final List<Integer> closeRequests = new ArrayList<>();
    /** The last title bar press (session, window, x, y, time) for double clicks, which maximize. */
    private long[] lastTitlePress;
    static final int DOUBLE_CLICK_MS = 500, DOUBLE_CLICK_SLOP = 4;
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** {@code <Configure>} events waiting for the program: widget id -> {x, y, width, height}. */
    private final Map<Integer, int[]> configures = new LinkedHashMap<>();
    /** Paint list index where each window's records start, bottom to top (paint priority). */
    private final List<Integer> layerStarts = new ArrayList<>();
    private final Map<Long, Command> queued = new LinkedHashMap<>();
    private Runnable onChange;
    private final Frame root;
    int nextId, width = 320, height = 240, focused;
    private boolean dirty = true, layoutDirty = true, overflowed, failed;
    private TkDisplayList display = TkDisplayList.EMPTY;

    public TkTree() { this(() -> {}); }
    public TkTree(Runnable onChange) { this.onChange = onChange; root = new Frame(this, null); }
    public Frame root() { return root; }
    public TkWidget get(int id) { return widgets.get(id); }

    // ---- programs ---------------------------------------------------------------------
    // Several programs of one computer share a display: each window belongs to one of them
    // (TkWindow.program), and so does everything in it. The desktop (the root) is nobody's.

    /** The program making the current call (TkApplication sets it around each one); 0 for none. */
    int caller;

    /** The program {@code widget} belongs to: its window's; 0 on the desktop. */
    static int programOf(TkWidget widget) {
        Frame window = TkWindow.of(widget);
        return window == null ? 0 : window.window.program;
    }

    /** The caller's widget {@code id}: one another program owns is not the caller's to use. */
    TkWidget owned(int id) {
        TkWidget widget = widgets.get(id);
        if (widget == null) throw new IllegalArgumentException("widget was destroyed");
        if (caller != 0 && programOf(widget) != caller) throw new IllegalArgumentException("widget belongs to another program");
        return widget;
    }

    /** The caller's image {@code id}, or null (deleted, or another program's). */
    TkImage ownedImage(int id) {
        TkImage image = images.get(id);
        return image == null || caller != 0 && image.program != caller ? null : image;
    }

    /** Close {@code program}'s windows and delete its images (it released the display or went away). */
    void releaseProgram(int program) {
        for (TkWidget child : new ArrayList<>(root.children))
            if (TkWindow.is(child) && ((Frame) child).window.program == program) destroy(child);
        for (TkImage image : new ArrayList<>(images.values())) if (image.program == program) deleteImage(image.id);
        uploads.remove(program);
    }
    public int focused() { render(); return focused; }
    /** A callback for the program; a later one for the same widget and callback replaces it (scroll positions and the like). */
    void queue(TkWidget widget, int callback, Object... args) {
        if (callback > 0) queued.put(((long) widget.id << 32) | callback, new Command(widget.id, callback, args));
    }
    /** Notices that each reach the program, in order and never merged: validation, focus, steps, the drop-down list. */
    private final List<Command> notices = new ArrayList<>();
    static final int MAX_NOTICES = 1024;
    void notice(TkWidget widget, int callback, Object... args) {
        if (callback > 0 && notices.size() < MAX_NOTICES) notices.add(new Command(widget.id, callback, args));
    }
    public List<Command> takeCommands() {
        render(); List<Command> result = new ArrayList<>();
        for (Command c : notices) if (widgets.containsKey(c.widget)) result.add(c);
        for (Command c : queued.values()) if (widgets.containsKey(c.widget)) result.add(c);
        notices.clear(); queued.clear(); return result;
    }
    void setOnChange(Runnable callback) { onChange = callback; }

    /** Widgets on one display, all programs together (the desktop root not counted). */
    public static final int MAX_WIDGETS = 4096;

    int register(TkWidget widget) {
        if (widgets.size() > MAX_WIDGETS || nextId == Integer.MAX_VALUE)
            throw new IllegalArgumentException("widget limit reached (" + MAX_WIDGETS + " per display)");
        int depth = 0;
        for (Frame parent = widget.parent; parent != null; parent = parent.parent) {
            require(parent);
            if (++depth > 32) throw new IllegalArgumentException("widget nesting limit reached");
        }
        int id = nextId++;
        widgets.put(id, widget);
        return id;
    }

    void require(TkWidget widget) {
        if (widgets.get(widget.id) != widget) throw new IllegalArgumentException("widget was destroyed");
    }
    void changed(boolean layout) {
        dirty = true; layoutDirty |= layout;
        // The empty root is initial state, not a mutation of the owning tile.
        if (root != null) onChange.run();
    }

    void checkTextBudget(TkWidget replaced, String text) { checkTextBytes(replaced, text.getBytes(StandardCharsets.UTF_8).length); }
    /** Whether replaced may have bytes of text, with every other widget's. */
    void checkTextBytes(TkWidget replaced, int bytes) {
        if (bytes + otherTextBytes(replaced) > TkDisplayList.MAX_TEXT_BYTES) throw new IllegalArgumentException("widget text budget exceeded");
    }
    /** UTF-8 bytes of every widget text except {@code replaced}'s, towards MAX_TEXT_BYTES. */
    int otherTextBytes(TkWidget replaced) {
        int bytes = 0;
        for (TkWidget widget : widgets.values())
            if (widget != replaced) bytes += widget.textBytes();
        return bytes;
    }

    public void resize(int width, int height) {
        if (!TkScene.validSize(width) || !TkScene.validSize(height)) throw new IllegalArgumentException("invalid canvas size");
        if (this.width != width || this.height != height) {
            this.width = width; this.height = height; changed(true);
        }
    }

    /**
     * Never throws. Rendering runs from the tile entity tick, the viewer flush and NBT
     * saving, none of which can handle an exception: one escaping a tile entity tick
     * crashes the server. A frame over the paint budgets is truncated (see
     * {@link #overflowed()}); any other failure keeps the last good frame.
     */
    public TkDisplayList render() {
        if (!dirty) return display;
        try {
            if (layoutDirty) {
                // The desktop's work area ends above the taskbar; windows are clipped to it too.
                TkPackLayout.layout(root, width, workHeight());
                if (clampWindows()) TkPackLayout.layout(root, width, workHeight());
                layoutDirty = false;
            }
            cancelUnavailable();
            List<TkDisplayList.Item> items = new ArrayList<>();
            layerStarts.clear();
            collect(root, items);
            if (taskbarShown()) {
                layerStarts.add(items.size());
                TkWindow.paintTaskbar(this, activeWindow(), pressedTask(), items);
            }
            if (popup != null) {
                layerStarts.add(items.size()); // the topmost layer: a Combobox's open list
                popup.paint(items, new TkRect(0, 0, width, height));
            }
            if (menus != null) {
                layerStarts.add(items.size()); // or the posted menus
                menus.paint(items, new TkRect(0, 0, width, height));
            }
            configureEvents();
            // Over budget, the lowest windows lose their records first, then the desktop.
            boolean trimmed = TkDisplayList.trimToBudget(items, layerStarts);
            display = new TkDisplayList(items);
            if (trimmed && !overflowed)
                reobf.mpy4oc.main.MyMod.LOG.warn("Tk display exceeds its paint budget; trailing widgets are not drawn");
            overflowed = trimmed; failed = false;
        } catch (RuntimeException error) {
            if (!failed) reobf.mpy4oc.main.MyMod.LOG.warn("Tk display could not be rendered; keeping the last frame", error);
            failed = true;
        }
        dirty = false;
        return display;
    }

    /** Whether the current frame had to drop paint records to fit the display budgets. */
    public boolean overflowed() { render(); return overflowed; }

    /**
     * Whether the display could need more paint records or text than it draws
     * (TkDisplayList.MAX_RECORDS, MAX_PAINT_BYTES), whatever the layout and the window sizes:
     * TkPaint's bounds added up, nothing laid out. While it could not, everything is drawn.
     */
    boolean mayOverflow() {
        int rows = height / TkFontMetrics.LINE_HEIGHT + 2, rowBytes = 4 * (width / TkFontMetrics.CELL_WIDTH + 2);
        long records = 1, bytes = 0;   // the taskbar strip
        for (TkWidget w : widgets.values()) {
            if (w == root) continue;
            records += TkPaint.maxRecords(w, rows);
            bytes += TkPaint.maxBytes(w, rows, rowBytes);
        }
        return records > TkDisplayList.MAX_RECORDS || bytes > TkDisplayList.MAX_PAINT_BYTES;
    }

    /** After a program's change: whether everything on the display is still drawn (laid out to find out only when it might not be). */
    boolean drawsEverything() {
        if (!mayOverflow()) return true;
        render();
        return !overflowed;
    }

    private void collect(Frame parent, List<TkDisplayList.Item> items) {
        Frame active = parent == root ? activeWindow() : null;
        for (TkWidget widget : parent.children) {
            if (TkWindow.is(widget)) {
                Frame window = (Frame) widget;
                layerStarts.add(items.size());
                TkWindow.paint(window, window == active, pressedZone(window), hintFor(window),
                        menus != null && menus.bar == window ? menus.barIndex : -1, root.clip, items);
            }
            if (widget.clip.empty()) continue;
            if (TkPaint.special(widget, focused == widget.id, items)) {
                if (widget instanceof Frame) collect((Frame) widget, items);   // a Labelframe's children
                continue;
            }
            // A Label or Button showing a PhotoImage draws the image instead of its text, as Tk does.
            TkImage image = widget instanceof Label ? images.get(((Label) widget).image) : null;
            String paintText = image != null ? "" : widget instanceof Label ? ((Label) widget).paintText() : widget.text();
            int contentWidth = image != null ? image.width : TkFontMetrics.width(paintText);
            int contentHeight = image != null ? image.height : TkFontMetrics.height(paintText);
            int tx = widget.bounds.x + widget.insetLeft()
                    + (widget.bounds.width - widget.insetWidth() - contentWidth) * widget.textAnchor.x / 2;
            int ty = widget.bounds.y + widget.insetTop()
                    + (widget.bounds.height - widget.insetHeight() - contentHeight) * widget.textAnchor.y / 2;
            if (widget instanceof Entry) tx = ((Entry) widget).textOriginX();
            int state = (pressed.containsValue(widget) ? TkDisplayList.PRESSED : 0)
                    | (focused == widget.id ? TkDisplayList.FOCUSED : 0)
                    | (!available(widget) ? TkDisplayList.DISABLED : 0);
            Entry entry = widget instanceof Entry ? (Entry) widget : null;
            // The text area: a Spinbox's or Combobox's arrows are painted over the rest.
            int textRight = widget.clip.x + widget.clip.width;
            if (widget instanceof Spinbox || widget instanceof Combobox)
                textRight = Math.min(textRight, widget.bounds.x + widget.bounds.width - widget.insetRight() + widget.padRight);
            int caret = entry != null && !entry.readonly && focused == widget.id ? tx + entry.prefixWidth() : -1;
            if (caret < widget.clip.x || caret >= textRight) caret = -1;
            int selectionX = 0, selectionWidth = 0;
            if (entry != null && available(entry) && entry.selectionPresent()) {
                selectionX = Math.max(widget.clip.x, tx + entry.prefixWidth(entry.selectionFirst()));
                int right = Math.min(textRight, tx + entry.prefixWidth(entry.selectionLast()));
                selectionWidth = Math.max(0, right - selectionX);
                if (selectionWidth == 0) selectionX = 0;
            }
            // A ttk widget's colours and relief come from its style, in its state (TkLook).
            int bg = widget.background, fg = widget.foreground;
            TkWidget.Relief relief = widget.relief;
            if (widget.look != null) {
                int s = state(widget);
                bg = widget.look.color(entry != null ? "fieldbackground" : "background", s, bg);
                fg = widget.look.color("foreground", s, fg);
                relief = widget.look.relief("relief", s, relief);
            }
            // A classic Menubutton whose menu is posted looks pushed in (tk::MbPost: raised becomes sunken).
            boolean posted = widget instanceof Menubutton && menus != null && menus.button == widget;
            if (posted && widget.look == null && relief == TkWidget.Relief.RAISED) relief = TkWidget.Relief.SUNKEN;
            // A Spinbox or Combobox paints as an Entry, a Menubutton as a Label (records keep the older kinds).
            items.add(new TkDisplayList.Item(widget.id, 0, entry != null ? TkWidget.Kind.ENTRY : widget instanceof Menubutton ? TkWidget.Kind.LABEL : widget.kind, widget.bounds, widget.clip,
                    bg, fg, widget.border, state, tx, ty, paintText,
                    caret, selectionX, selectionWidth, null, image != null ? image.id : 0, relief.code()));
            if (widget instanceof Spinbox || widget instanceof Combobox) TkPaint.arrows((Entry) widget, available(widget), popup != null && popup.owner == widget, items);
            if (widget instanceof Menubutton) TkPaint.indicator((Menubutton) widget, bg, fg, available(widget), items);
            if (widget instanceof Frame) collect((Frame) widget, items);
        }
    }

    // ---- windows -------------------------------------------------------------------------

    /** The topmost shown window, which receives keys while nothing has focus; null if none. */
    public Frame activeWindow() {
        for (int i = root.children.size() - 1; i >= 0; i--) {
            TkWidget child = root.children.get(i);
            if (!TkWindow.is(child)) break;
            if (!child.bounds.empty()) return (Frame) child;
        }
        return null;
    }

    /** The window-manager part under (x, y): a window decoration, the taskbar, or NONE (client areas and the desktop). */
    public TkWindow.Zone zoneAt(int x, int y) {
        if (inTaskbar(x, y)) return TkWindow.Zone.TASKBAR;
        TkWidget target = hit(x, y);
        if (target instanceof Sizegrip && available(target) && ((Sizegrip) target).edges() != 0) return TkWindow.Zone.EDGE;
        return TkWindow.is(target) ? TkWindow.zone((Frame) target, x, y) : TkWindow.Zone.NONE;
    }

    /** Whether the taskbar is on: some window is shown or minimized. */
    public boolean taskbarShown() { return !TkWindow.tasks(root).isEmpty(); }
    /** Height of the desktop's work area: the display above the taskbar. */
    public int workHeight() { return taskbarShown() ? Math.max(1, height - TkWindow.TASKBAR) : height; }

    private boolean inTaskbar(int x, int y) {
        render();
        return taskbarShown() && x >= 0 && x < width && y >= workHeight() && y < height;
    }
    /** The window whose taskbar button is at (x, y), or null. */
    private Frame taskAt(int x, int y) {
        if (!inTaskbar(x, y)) return null;
        for (Object[] task : TkWindow.taskButtons(this)) if (((TkRect) task[1]).contains(x, y)) return (Frame) task[0];
        return null;
    }
    private TkWindow.Zone pressedZone(Frame window) {
        for (WmPress press : wmPressed.values()) if (press.window == window && press.zone != TkWindow.Zone.TASKBAR) return press.zone;
        return TkWindow.Zone.NONE;
    }
    private Frame pressedTask() {
        for (WmPress press : wmPressed.values()) if (press.zone == TkWindow.Zone.TASKBAR) return press.window;
        return null;
    }

    /** Windows that may have been placed out of reach (display shrunk, programmatic geometry); maximized ones fill the work area. */
    private boolean clampWindows() {
        boolean moved = false;
        int work = workHeight();
        for (TkWidget child : root.children) {
            if (!TkWindow.is(child) || child.place == null) continue;
            Frame window = (Frame) child;
            TkPlace p = child.place, next = p;
            if (window.window.zoomed()) next = TkWindow.zoomPlace(window, width, work);
            else if (!child.bounds.empty()) {
                int x = TkWindow.clampX(window, p.x, child.bounds.width, width), y = TkWindow.clampY(window, p.y, work);
                if (x != p.x || y != p.y) next = TkWindow.place(x, y, p.width, p.height);
            }
            if (!TkWindow.same(p, next)) { child.place = next; moved = true; }
        }
        return moved;
    }

    void moveWindow(Frame window, int x, int y) {
        TkPlace p = window.place;
        if (p == null) return;
        x = TkWindow.clampX(window, x, window.bounds.width, width); y = TkWindow.clampY(window, y, workHeight());
        if (x != p.x || y != p.y) { window.place = TkWindow.place(x, y, p.width, p.height); changed(true); }
    }

    /**
     * A resize drag: the edges move by (dx, dy) from the client area {@code start}; the
     * opposite edges stay put. Only the dragged axis gets a fixed size (the other keeps its
     * geometry, natural or not). Sizes keep to minsize/maxsize and, without a maxsize, to the
     * display (or the window's size when the drag began, if larger); the title bar stays
     * below the top of the display. Horizontally the visible frame follows the pointer: a
     * client narrower than its title bar keeps its width until the frame outgrows the bar.
     */
    void resizeWindow(Frame window, TkRect start, int edges, int dx, int dy) {
        TkPlace p = window.place;
        if (p == null) return;
        TkWindow w = window.window;
        int minW = w.minWidth, minH = w.minHeight;
        int maxW = Math.max(minW, w.maxWidth > 0 ? w.maxWidth : Math.max(width, start.width));
        int maxH = Math.max(minH, w.maxHeight > 0 ? w.maxHeight : Math.max(workHeight() - TkWindow.top(window), start.height));
        int x = p.x, y = p.y;
        Integer newWidth = p.width, newHeight = p.height;
        if ((edges & (TkWindow.LEFT | TkWindow.RIGHT)) != 0) {
            int bar = TkWindow.minBar(window), visible = Math.max(start.width, bar);
            int frame = (edges & TkWindow.RIGHT) != 0 ? visible + dx : visible - dx;
            int client = clamp(frame > bar ? frame : Math.min(start.width, bar), minW, maxW);
            // The left edge moves; the frame's right end stays where it was.
            if ((edges & TkWindow.LEFT) != 0) x = start.x + visible - TkWindow.decoratedWidth(window, client);
            newWidth = client;
        }
        if ((edges & (TkWindow.TOP | TkWindow.BOTTOM)) != 0) {
            int top = start.y, bottom = start.y + start.height;
            if ((edges & TkWindow.TOP) != 0) top = bottom - clamp(bottom - Math.max(TkWindow.top(window), start.y + dy), minH, maxH);
            else bottom = top + clamp(start.height + dy, minH, maxH);
            y = top;
            newHeight = bottom - top;
        }
        int clientWidth = newWidth != null ? newWidth : start.width;
        TkPlace next = TkWindow.place(TkWindow.clampX(window, x, clientWidth, width), TkWindow.clampY(window, y, workHeight()), newWidth, newHeight);
        if (!TkWindow.same(p, next)) { window.place = next; changed(true); }
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Hide a window: minimized (iconic, on the taskbar) or withdrawn. The next window down becomes active. */
    void hide(Frame window, boolean iconic) {
        TkWindow w = window.window;
        if (window.place == null && w.iconic == iconic) return;
        render();
        boolean wasActive = activeWindow() == window;
        if (window.place != null) { w.restore = window.place; window.place = null; }
        w.iconic = iconic;
        changed(true);
        // Its transient windows go with it, and come back with it.
        for (Frame t : TkWindow.transients(window))
            if (t.place != null) { hide(t, false); t.window.hiddenWithMaster = true; }
        if (wasActive) {
            render();
            Frame next = activeWindow();
            if (next != null) focusWindow(next);
        }
    }

    /** Show a withdrawn or minimized window where it was, on top; with {@code focus}, also give it the keyboard. */
    void show(Frame window, boolean focus) {
        TkWindow w = window.window;
        if (window.place == null) {
            window.place = w.restore != null ? w.restore : TkWindow.cascade(window, TkWindow.windows(root).size());
            w.restore = null;
            changed(true);
        }
        if (w.iconic) { w.iconic = false; changed(true); }
        for (Frame t : TkWindow.transients(window))
            if (t.window.hiddenWithMaster) { t.window.hiddenWithMaster = false; show(t, false); }
        raise(window);
        if (focus) { render(); focusWindow(window); }
    }

    /** Maximize (fill the work area) or restore a window. A hidden window changes what it comes back as. */
    void zoom(Frame window, boolean on) {
        TkWindow w = window.window;
        if (on == w.zoomed()) return;
        if (window.place == null) {
            TkPlace back = w.restore != null ? w.restore : TkWindow.cascade(window, TkWindow.windows(root).size());
            if (on) { w.normal = back; w.restore = TkWindow.zoomPlace(window, width, workHeight()); }
            else { w.restore = w.normal; w.normal = null; }
        } else if (on) {
            w.normal = window.place; window.place = TkWindow.zoomPlace(window, width, workHeight());
        } else {
            window.place = w.normal; w.normal = null;
        }
        changed(true);
    }

    /** A taskbar button: restores a minimized window, minimizes the active one, otherwise activates. */
    private void taskClicked(Frame window) {
        render();
        if (window.place == null) show(window, true);
        else if (activeWindow() == window) hide(window, true);
        else { raise(window); render(); focusWindow(window); }
    }

    private boolean doubleClick(long session, Frame window, int x, int y) {
        long now = clock.getAsLong();
        long[] last = lastTitlePress;
        boolean twice = last != null && last[0] == session && last[1] == window.id && now >= last[4] && now - last[4] <= DOUBLE_CLICK_MS
                && Math.abs(last[2] - x) <= DOUBLE_CLICK_SLOP && Math.abs(last[3] - y) <= DOUBLE_CLICK_SLOP;
        lastTitlePress = twice ? null : new long[] {session, window.id, x, y, now};
        return twice;
    }

    /** The session whose latest press was the second click of a double click (Long.MIN_VALUE: none). */
    private long doubled = Long.MIN_VALUE;
    /** The last press on a widget (session, widget, x, y, time), for {@code <Double-Button-1>}. */
    private long[] lastWidgetPress;

    private boolean widgetDoubleClick(long session, TkWidget widget, int x, int y) {
        long now = clock.getAsLong();
        long[] last = lastWidgetPress;
        boolean twice = last != null && last[0] == session && last[1] == widget.id && now >= last[4] && now - last[4] <= DOUBLE_CLICK_MS
                && Math.abs(last[2] - x) <= DOUBLE_CLICK_SLOP && Math.abs(last[3] - y) <= DOUBLE_CLICK_SLOP;
        // A third click starts over, as a new first click.
        lastWidgetPress = twice ? null : new long[] {session, widget.id, x, y, now};
        return twice;
    }

    /** Whether the session's latest press on a widget was the second of a double click. */
    public boolean doubleClicked(long session) { return doubled == session; }

    /** A right press or a key (not a modifier) between two clicks: they are no double click, as in Tk. */
    public void interruptClick(long session) { if (lastWidgetPress != null && lastWidgetPress[0] == session) lastWidgetPress = null; }

    /** The widget the pointer is over (ttk's hover, and active for buttons), 0 for none; never saved. */
    int hovered;
    /** The window whose edges the pointer is on and the edges a press there would resize (the lit hint); never saved. */
    int hintWindow, hintEdges;

    /** The edges lit on window: the ones a resize drag of it moves, else the ones under the pointer. */
    private int hintFor(Frame window) {
        for (Capture c : captured.values()) if (c.windowDrag && c.widget == window && c.edges != 0) return c.edges;
        return hintWindow == window.id ? hintEdges : 0;
    }

    /**
     * A widget's state in Tk's bits (TkLook) for its look, as it is now (no layout): what the
     * server knows, and what the program holds (held).
     */
    int state(TkWidget w) {
        int s = w.held;
        if (!w.enabled) s |= TkLook.DISABLED;
        if (w instanceof Entry && ((Entry) w).readonly) s |= TkLook.READONLY;
        if (w instanceof Checkbutton && ((Checkbutton) w).selected) s |= TkLook.SELECTED;
        if (focused == w.id && w.id != 0) s |= TkLook.FOCUS;
        if (pressed.containsValue(w) || popup != null && popup.owner == w || menus != null && menus.button == w) s |= TkLook.PRESSED;
        for (Capture c : captured.values()) if (c.widget == w && !c.windowDrag && (w instanceof Scale || w instanceof Scrollbar)) s |= TkLook.PRESSED;
        if (hovered == w.id && w.id != 0) {
            s |= TkLook.HOVER;
            if (w instanceof Button && w.enabled) s |= TkLook.ACTIVE;   // the button classes' <Enter> binding
        }
        Frame active = activeWindow();
        if (active != null && TkWindow.of(w) != active) s |= TkLook.BACKGROUND;
        return s;
    }

    /** ttk state flags the server knows: 1 disabled, 2 readonly, 4 selected, 8 focus, 16 pressed, 32 background, 64 hover, 128 active. */
    int flags(TkWidget w) {
        render();
        int more = state(w), bits = legacyFlags(w);
        if ((more & TkLook.HOVER) != 0) bits |= 64;
        if ((more & TkLook.ACTIVE) != 0 && (w.held & TkLook.ACTIVE) == 0) bits |= 128;
        return bits;
    }

    private int legacyFlags(TkWidget w) {
        int flags = 0;
        if (!w.enabled) flags |= 1;
        if (w instanceof Entry && ((Entry) w).readonly) flags |= 2;
        if (w instanceof Checkbutton && ((Checkbutton) w).selected) flags |= 4;
        if (focused == w.id && w.id != 0) flags |= 8;
        if (pressed.containsValue(w)) flags |= 16;
        for (Capture c : captured.values()) if (c.widget == w && !c.windowDrag && (w instanceof Scale || w instanceof Scrollbar)) flags |= 16;
        if (popup != null && popup.owner == w) flags |= 16;   // ttk: a Combobox is 'pressed' while its list is open
        if (menus != null && menus.button == w) flags |= 16;   // and a Menubutton while its menu is posted
        // Tk's 'background': the widget's toplevel is not the active one (the desktop counts while a window is).
        Frame active = activeWindow();
        if (active != null && TkWindow.of(w) != active) flags |= 32;
        return flags;
    }

    /** Remember where a right-button press went (a widget id, or -1 for a decoration), for its release. */
    public void rightPress(long session, int widget) { interruptClick(session); rightPresses.put(session, widget); }
    /** Where the session's right-button press went (see {@link #rightPress}), or null if none is known. */
    public Integer rightRelease(long session) { return rightPresses.remove(session); }

    /** Whether the session's press belongs to the window manager (a drag, a title bar or taskbar button) or an open list, not the program. */
    public boolean managing(long session) {
        Capture c = captured.get(session);
        return c != null && c.windowDrag || wmPressed.containsKey(session) || swallowed.contains(session) || popup != null && popup.holds(session)
                || menus != null && menus.holds(session);
    }

    /**
     * Widgets bound to {@code <Configure>} whose size changed (or that were just bound or
     * mapped) since the last frame: Tk's Configure event, for the size only.
     */
    private void configureEvents() {
        for (TkWidget widget : widgets.values()) {
            if (!widget.bindings.containsKey("<Configure>")) continue;
            TkRect b = widget.bounds;
            long size = b.empty() ? 0 : ((long) b.width << 32) | b.height;
            if (size == widget.configured) continue;
            widget.configured = size;
            if (size == 0) continue;
            TkRect parent = widget.parent == null ? new TkRect(0, 0, 0, 0) : widget.parent.bounds;
            configures.put(widget.id, new int[] {b.x - parent.x, b.y - parent.y, b.width, b.height});
        }
    }

    /** Pending {@code <Configure>} events as {widget, x, y, width, height}. */
    public List<int[]> takeConfigures() {
        render();
        List<int[]> result = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : configures.entrySet()) {
            int[] c = e.getValue();
            if (widgets.containsKey(e.getKey())) result.add(new int[] {e.getKey(), c[0], c[1], c[2], c[3]});
        }
        configures.clear();
        return result;
    }

    /**
     * Move {@code widget} to the top or bottom of its siblings, or just above/below
     * {@code other}. On the root, windows always stay above the desktop's other children.
     */
    void restack(TkWidget widget, boolean top, TkWidget other) {
        widget.alive();
        Frame parent = widget.parent;
        if (parent == null) return;
        if (other != null && (other.parent != parent || other == widget)) throw new IllegalArgumentException("not a sibling");
        List<TkWidget> siblings = parent.children;
        int before = siblings.indexOf(widget);
        siblings.remove(widget);
        int index;
        if (other != null) index = siblings.indexOf(other) + (top ? 1 : 0);
        else index = top ? siblings.size() : 0;
        if (parent == root) {
            int firstWindow = 0;
            while (firstWindow < siblings.size() && !TkWindow.is(siblings.get(firstWindow))) firstWindow++;
            index = TkWindow.is(widget) ? Math.max(index, firstWindow) : Math.min(index, firstWindow);
        }
        siblings.add(index, widget);
        if (index != before) changed(false);
    }

    void raise(Frame window) {
        if (root.children.get(root.children.size() - 1) != window) restack(window, true, null);
        // Its transient windows (dialogs) stay above it.
        for (Frame t : TkWindow.transients(window)) if (t != window) raise(t);
    }

    /** A click that does not focus its widget still activates its window (the keyboard leaves another one). */
    private void activate(Frame window) {
        if (window != null) focusWindow(window);
        else if (focused != 0 && TkWindow.of(widgets.get(focused)) != null) focus(0);
    }

    /** Give the keyboard to {@code window}: its last focused widget, if still focusable. */
    private void focusWindow(Frame window) {
        TkWidget current = widgets.get(focused);
        if (current != null && TkWindow.of(current) == window) return;
        TkWidget last = widgets.get(window.window.lastFocus);
        if (last != null && TkWindow.of(last) == window && focusable(last) && available(last)) focus(last.id);
        else focus(0);
    }

    /** Close boxes clicked since the last call (window ids); the application decides. */
    public List<Integer> takeCloseRequests() {
        List<Integer> result = new ArrayList<>();
        for (int id : closeRequests) if (TkWindow.is(widgets.get(id))) result.add(id);
        closeRequests.clear();
        return result;
    }

    boolean available(TkWidget widget) {
        if (widgets.get(widget.id) != widget || widget.clip.empty()) return false;
        for (TkWidget current = widget; current != null; current = current.parent)
            if (!current.enabled && !(current == widget && current instanceof Text) || current.parent != null && current.manager().isEmpty()) return false;
        return true;
    }

    void cancelUnavailable() {
        boolean removed = pressed.values().removeIf(button -> !available(button));
        removed |= wmPressed.values().removeIf(press -> widgets.get(press.window.id) != press.window
                || (press.zone == TkWindow.Zone.TASKBAR ? !taskbarShown() || !TkWindow.tasks(root).contains(press.window) : press.window.bounds.empty()));
        removed |= captured.values().removeIf(c -> c.windowDrag
                ? widgets.get(c.widget.id) != c.widget || c.widget.place == null || ((Frame) c.widget).window.zoomed()
                : !available(c.widget) || (c.widget instanceof Entry || c.widget instanceof Text) && c.widget.id != focused
                        // a classic Spinbox's arrow is held without the keyboard
                        && !(c.widget instanceof Spinbox && ((Spinbox) c.widget).pressed != 0));
        TkWidget focus = widgets.get(focused);
        if (focused != 0 && (focus == null || !available(focus))) { focused = 0; removed = true; }
        // A list goes with its Combobox (destroyed, disabled, hidden).
        if (popup != null && !available(popup.owner)) { popup = null; removed = true; }
        // Posted menus go with what they came from (a window, a Menubutton) or once all of them are destroyed.
        if (menus != null && !menus.validate()) { menus.close(0); menus = null; removed = true; }
        if (removed) changed(false);
    }

    public TkWidget hit(int x, int y) { render(); return hit(root, x, y); }
    private TkWidget hit(TkWidget widget, int x, int y) {
        if (!widget.clip.contains(x, y)) return null;
        if (widget instanceof Frame) {
            List<TkWidget> children = ((Frame) widget).children;
            for (int i = children.size() - 1; i >= 0; i--) {
                TkWidget candidate = children.get(i);
                // A window's title bar, edge and resize band lie outside its client area.
                if (TkWindow.is(candidate) && TkWindow.zone((Frame) candidate, x, y) != TkWindow.Zone.NONE) return candidate;
                TkWidget child = hit(candidate, x, y);
                if (child != null) return child;
            }
        }
        return widget;
    }

    public void focus(int id) {
        render();
        TkWidget widget = widgets.get(id);
        // focus_set() on a Toplevel: raise it and restore its last focus, as a window manager does.
        if (TkWindow.is(widget)) { raise((Frame) widget); focusWindow((Frame) widget); return; }
        if (id != 0 && (!focusable(widget) || !available(widget)))
            throw new IllegalArgumentException("widget cannot take focus");
        Frame window = TkWindow.of(widget);
        if (id != 0 && window != null) window.window.lastFocus = id;
        if (focused != id) {
            TkWidget old = widgets.get(focused);
            focused = id; cancelUnavailable(); changed(false);
            // Focus validation (-validate focus, focusin, focusout, all) runs in the program.
            if (old instanceof Entry && ((Entry) old).validatesFocus(false)) notice(old, ((Entry) old).notify, "focusout", old.text());
            if (widget instanceof Entry && focused == id && ((Entry) widget).validatesFocus(true)) notice(widget, ((Entry) widget).notify, "focusin", widget.text());
        }
    }

    /**
     * focus_set() from {@code program}. While another program's window is active it only
     * becomes what the widget's own window gives the keyboard to when activated (Tk's focus
     * without -force), so a program in the background does not take the keys from the one
     * the player is typing into, nor raise its window over it.
     */
    void focusFor(int program, int id) {
        render();
        Frame active = activeWindow();
        TkWidget widget = widgets.get(id);
        if (active == null || active.window.program == program || widget == null) { focus(id); return; }
        if (TkWindow.is(widget)) return;
        if (!focusable(widget) || !available(widget)) throw new IllegalArgumentException("widget cannot take focus");
        Frame window = TkWindow.of(widget);
        if (window != null) window.window.lastFocus = id;
    }

    public void focusNext(boolean reverse) {
        render();
        // Tab cycles within one window: the focused widget's, else the active one.
        Frame scope = focused != 0 ? TkWindow.of(widgets.get(focused)) : activeWindow();
        List<Integer> candidates = new ArrayList<>();
        // The focused widget keeps its place even when Tab would skip it (takefocus=0, focused
        // by a click), so Tab moves on from there. A window itself is no stop.
        for (TkWidget widget : widgets.values())
            if (focused != 0 && widget.id == focused
                    || !TkWindow.is(widget) && traversable(widget) && available(widget) && TkWindow.of(widget) == scope) candidates.add(widget.id);
        if (candidates.isEmpty()) { focus(0); return; }
        int current = candidates.indexOf(focused);
        int next = current < 0 ? (reverse ? candidates.size() - 1 : 0)
                : Math.floorMod(current + (reverse ? -1 : 1), candidates.size());
        focus(candidates.get(next));
    }
    /** Widgets that take the keyboard by default (Tk's '' takefocus). */
    private static boolean focusByDefault(TkWidget w) {
        return w instanceof Button || w instanceof Entry || w instanceof Text || w instanceof Listbox || w instanceof Scale || w instanceof Scrollbar || w instanceof Notebook
                || w instanceof Treeview;
    }
    /** May hold focus (focus_set): those by default, and any widget with takefocus=1. */
    private static boolean focusable(TkWidget w) { return focusByDefault(w) || w.takefocus == 1; }
    /** Visited by Tab, and focused by a click on it: takefocus=1, or the default unless takefocus=0. */
    private static boolean traversable(TkWidget w) { return w.takefocus == 1 || w.takefocus < 0 && focusByDefault(w); }

    public void pointerDown(long session, int x, int y) {
        pointerDown(session, x, y, 0);
    }

    /** The first widget Tab would reach inside page (ttk::focusFirst), laid out as it is now; null if none. */
    private TkWidget firstFocus(TkWidget page) {
        render();
        for (TkWidget w : widgets.values()) {
            boolean inside = false;
            for (TkWidget c = w; c != null && !inside; c = c.parent) inside = c == page;
            if (inside && !TkWindow.is(w) && traversable(w) && available(w)) return w;
        }
        return null;
    }

    public void pointerDown(long session, int x, int y, int modifiers) {
        release(session);
        if (listPress(session, x, y, 0)) return;
        if (inTaskbar(x, y)) {
            Frame task = taskAt(x, y);
            if (task != null) { wmPressed.put(session, new WmPress(task, TkWindow.Zone.TASKBAR)); changed(false); }
            else swallowed.add(session);
            return;
        }
        TkWidget hit = hit(x, y);
        Frame window = TkWindow.of(hit);
        if (window != null) {
            TkWindow.Zone zone = hit == window ? TkWindow.zone(window, x, y) : TkWindow.Zone.NONE;
            raise(window);
            if (zone == TkWindow.Zone.CLOSE || zone == TkWindow.Zone.MINIMIZE || zone == TkWindow.Zone.MAXIMIZE) {
                wmPressed.put(session, new WmPress(window, zone)); focusWindow(window); changed(false); return;
            }
            if (zone == TkWindow.Zone.MENUBAR) {
                focusWindow(window);
                pressBar(session, window, TkWindow.barEntryAt(window, x, y));
                return;
            }
            if (zone == TkWindow.Zone.TITLE && window.window.maximizable() && doubleClick(session, window, x, y)) {
                swallowed.add(session);
                focusWindow(window);
                if (window.window.maximizable()) zoom(window, !window.window.zoomed());
                return;
            }
            if (zone != TkWindow.Zone.NONE && window.place != null) {
                // A maximized window stays where it is; its title bar does not drag it.
                int edges = TkWindow.resizeEdges(window, x, y);
                if (edges != 0 || !window.window.zoomed()) captured.put(session, new Capture(window, x, y, edges));
                else swallowed.add(session);
                focusWindow(window); return;
            }
        }
        if (hit instanceof Menubutton && available(hit)) { pressMenubutton(session, (Menubutton) hit); return; }
        if (hit instanceof Panedwindow && available(hit)) {
            // A sash (or a classic handle): the player drags it (ttk::panedwindow::Press, tk::panedwindow::MarkSash).
            Panedwindow paned = (Panedwindow) hit;
            int sash = paned.sashAt(x, y);
            if (sash != -1) {
                int index = sash >= 0 ? sash : -2 - sash;
                sashDrags.put(session, new int[] {paned.id, index, paned.sashStart(index) - (paned.horizontal ? x : y)});
                light(paned, index);
                return;
            }
        }
        if (hit instanceof Sizegrip && available(hit) && window != null && ((Sizegrip) hit).edges() != 0) {
            // As dragging the window's bottom right corner (the grip's own axes per wm resizable).
            raise(window);
            captured.put(session, new Capture(window, x, y, ((Sizegrip) hit).edges()));
            focusWindow(window); return;
        }
        doubled = hit != null && widgetDoubleClick(session, hit, x, y) ? session : Long.MIN_VALUE;
        if (hit instanceof Notebook && available(hit)) {
            Notebook book = (Notebook) hit; int tab = book.tabAt(x, y);
            if (tab >= 0) {
                // ttk::notebook::ActivateTab: select the tab (not a disabled one); then, unless the notebook
                // has the keyboard already, it goes to the new page's first widget, or to the notebook.
                int old = book.current;
                if (book.select(tab)) queue(book, book.command, "tab");
                if (focused != book.id) {
                    TkWidget first = book.current == old || book.current < 0 ? null : firstFocus(book.tabs.get(book.current).page);
                    focus(first != null ? first.id : book.id);
                }
            } else activate(window);
            captured.put(session, new Capture(hit, x, y));
            return;
        }
        if (hit instanceof Spinbox && available(hit) && ((Spinbox) hit).arrowAt(x, y) != 0) {
            Spinbox spin = (Spinbox) hit;
            spin.pressed = spin.arrowAt(x, y);
            captured.values().removeIf(c -> c.widget == hit);
            captured.put(session, new Capture(hit, x, y));
            changed(false);
            // ttk::spinbox::Press takes the keyboard and steps now; classic Tk steps on the release and leaves the focus.
            if (spin.ttk) { focus(spin.id); stepFromPlayer(spin, spin.pressed == Spinbox.UP ? 1 : -1); }
            else activate(window);
            return;
        }
        if (hit instanceof Combobox && available(hit) && (((Combobox) hit).readonly || ((Combobox) hit).onArrow(x, y))) {
            // ttk::combobox::Press: the Combobox takes the keyboard; readonly, or on the arrow, its list opens.
            focus(hit.id); post((Combobox) hit);
            return;
        }
        if (hit instanceof Treeview && available(hit)) {
            // ttk::treeview::Press: the keyboard, then a separator, a heading or a row (Double-1 toggles).
            Treeview view = (Treeview) hit;
            focus(view.id);
            captured.values().removeIf(c -> c.widget == hit);
            captured.put(session, new Capture(hit, x, y));
            view.press(x - view.bounds.x, y - view.bounds.y, modifiers, doubled == session);
            return;
        }
        if (hit instanceof Button && available(hit)) {
            pressed.put(session, (Button) hit); if (traversable(hit)) focus(hit.id); else activate(window); changed(false);
        } else if (hit instanceof Entry && available(hit)) {
            focus(hit.id); ((Entry) hit).click(x, modifiers);
            // The latest press owns this shared Entry selection; older drags stop.
            captured.values().removeIf(c -> c.widget == hit);
        } else if (hit != null && focusByDefault(hit) && available(hit)) {
            // Entry, Text and Listbox take the keyboard on a click whatever their takefocus (Tk's class bindings do).
            if (traversable(hit) || hit instanceof Text || hit instanceof Listbox) focus(hit.id);
            else activate(window);
            captured.values().removeIf(c -> c.widget == hit);
            if (hit instanceof Text) ((Text) hit).press(x, y, modifiers);
            else if (hit instanceof Listbox) ((Listbox) hit).press(y, modifiers);
            else if (hit instanceof Scale) ((Scale) hit).drag(x, y);
            else if (hit instanceof Scrollbar) ((Scrollbar) hit).press(x, y);
        } else if (window != null) focusWindow(window);
        else focus(0);
        if (hit != null && available(hit)) captured.put(session, new Capture(hit, x, y));
    }

    /** The widget a press captured, or null (none, or a window move or resize). */
    public TkWidget capture(long session) { render(); Capture c = captured.get(session); return c == null || c.windowDrag ? null : c.widget; }
    public int capturedItem(long session) { Capture c = captured.get(session); return c == null ? 0 : c.item; }
    public boolean pointerMoved(long session, int x, int y) {
        Capture c = captured.get(session); return c != null && (c.x != x || c.y != y);
    }
    public TkWidget pointerDrag(long session, int x, int y) {
        render();
        if (popup != null && popup.drag(session, x, y)) return null;
        if (menus != null && menus.drag(session, x, y)) return null;
        int[] sash = sashDrags.get(session);
        if (sash != null) {
            TkWidget paned = widgets.get(sash[0]);
            if (paned instanceof Panedwindow && available(paned)) ((Panedwindow) paned).dragSash(sash[1], (((Panedwindow) paned).horizontal ? x : y) + sash[2]);
            return null;
        }
        trackHover(x, y);
        Capture drag = captured.get(session);
        if (drag != null && drag.windowDrag) {
            render();
            Frame window = (Frame) drag.widget;
            if (drag.edges == 0) moveWindow(window, drag.start.x + x - drag.grabX, drag.start.y + y - drag.grabY);
            else resizeWindow(window, drag.start, drag.edges, x - drag.grabX, y - drag.grabY);
            drag.x = x; drag.y = y;
            return null;
        }
        TkWidget target = capture(session);
        if (target instanceof Spinbox && ((Spinbox) target).pressed != 0) return target;   // an arrow held down
        if (target instanceof Treeview) ((Treeview) target).drag(x - target.bounds.x, y - target.bounds.y);
        if (target instanceof Entry) {
            Entry entry = (Entry) target;
            if (pointerMoved(session, x, y) || x < entry.bounds.x + entry.insetLeft()
                    || x >= entry.bounds.x + entry.bounds.width - entry.insetRight()) entry.drag(x);
        }
        if (target instanceof Text) {
            Text text = (Text) target;
            if (pointerMoved(session, x, y) || !text.bounds.contains(x, y)) text.drag(x, y);
        } else if (target instanceof Listbox) ((Listbox) target).drag(y);
        else if (target instanceof Scale) ((Scale) target).drag(x, y);
        else if (target instanceof Scrollbar) ((Scrollbar) target).drag(x, y);
        Capture c = captured.get(session); if (c != null) { c.x = x; c.y = y; }
        return target;
    }

    public Command pointerUp(long session, int x, int y) {
        render();
        Capture capture = captured.remove(session);
        if (capture != null && capture.windowDrag && capture.edges != 0) {
            // The resize hint goes back to what is under the pointer now.
            changed(false);
            trackHover(x, y);
        }
        if (swallowed.remove(session)) return null;
        if (popup != null && popup.release(session)) return null;
        if (menus != null && menus.release(session, x, y)) return null;
        if (sashDrags.remove(session) != null) { trackHover(x, y); return null; }
        if (capture != null && capture.widget instanceof Spinbox && ((Spinbox) capture.widget).pressed != 0) {
            Spinbox spin = (Spinbox) capture.widget;
            int arrow = spin.pressed;
            spin.pressed = 0; changed(false);
            // Classic Tk steps on the release (tk::spinbox::ButtonUp), wherever the pointer is.
            if (!spin.ttk && available(spin)) stepFromPlayer(spin, arrow == Spinbox.UP ? 1 : -1);
            return null;
        }
        if (capture != null && capture.widget instanceof Treeview) {
            Treeview view = (Treeview) capture.widget;
            if (available(view)) view.release(x - view.bounds.x, y - view.bounds.y);
            return null;
        }
        WmPress press = wmPressed.remove(session);
        if (press != null) {
            changed(false);
            Frame window = press.window;
            if (widgets.get(window.id) != window) return null;
            if (press.zone == TkWindow.Zone.TASKBAR) {
                if (taskAt(x, y) == window) taskClicked(window);
            } else if (hit(x, y) == window && TkWindow.zone(window, x, y) == press.zone) {
                if (press.zone == TkWindow.Zone.CLOSE) closeRequests.add(window.id);
                else if (press.zone == TkWindow.Zone.MINIMIZE) hide(window, true);
                else if (press.zone == TkWindow.Zone.MAXIMIZE) zoom(window, !window.window.zoomed());
            }
            return null;
        }
        Button button = pressed.remove(session);
        if (button == null) return null;
        changed(false);
        return available(button) && hit(x, y) == button ? command(button) : null;
    }

    public void release(long session) {
        Capture old = captured.remove(session);
        if (old != null && old.windowDrag && old.edges != 0) changed(false);   // its edges were lit
        if (old != null && old.widget instanceof Canvas) ((Canvas) old.widget).current = 0;
        if (old != null && old.widget instanceof Spinbox && ((Spinbox) old.widget).pressed != 0) { ((Spinbox) old.widget).pressed = 0; changed(false); }
        if (old != null && old.widget instanceof Treeview) { ((Treeview) old.widget).pressedHeading = ((Treeview) old.widget).dragging = -1; changed(false); }
        if (popup != null) popup.forget(session);
        if (menus != null) menus.forget(session);
        sashDrags.remove(session);
        if (pressed.remove(session) != null) changed(false);
        if (wmPressed.remove(session) != null) changed(false);
        swallowed.remove(session);
        rightPresses.remove(session);
    }

    /** Ephemeral shared current item follows the latest pointer; never stored in NBT. */
    void updateCanvasCurrent(long session, int x, int y) {
        TkWidget target = capture(session);
        int item = capturedItem(session);
        if (target == null) {
            target = hit(x, y);
            item = target instanceof Canvas ? ((Canvas) target).hitItem(x, y) : 0;
        }
        for (TkWidget widget : widgets.values()) if (widget instanceof Canvas) {
            Canvas canvas = (Canvas) widget;
            Canvas.Item selected = canvas.items.get(item);
            canvas.current = widget == target && selected != null && selected.state.equals("normal") ? item : 0;
        }
    }

    public void wheel(int x, int y, int delta) {
        render();
        if (popup != null) { if (popup.box().contains(x, y)) popup.wheel(delta); return; }
        if (menus != null) return;   // the menus have the pointer
        TkWidget target = hit(x, y);
        if (target instanceof Combobox && available(target)) {
            // ttk::combobox::Scroll: the value before or after the current one, not past the ends.
            Combobox box = (Combobox) target;
            int max = box.values.size(), next = box.current() + (delta > 0 ? -Math.max(1, delta / 120) : Math.max(1, -delta / 120));
            if (max != 0 && next == Math.floorMod(next, max)) box.choose(next);
            return;
        }
        if (target instanceof Treeview && available(target)) {
            ((Treeview) target).scroll(false, delta > 0 ? -Math.max(1, delta / 120) : Math.max(1, -delta / 120), false);
            return;
        }
        if (target instanceof Spinbox && ((Spinbox) target).ttk && available(target)) {
            stepFromPlayer((Spinbox) target, delta > 0 ? 1 : -1);   // ttk::spinbox::MouseWheel
            return;
        }
        if (target instanceof Text && available(target)) ((Text) target).scroll(false, delta > 0 ? -3 : 3, false);
        if (target instanceof Entry && available(target)) ((Entry) target).scroll(delta > 0 ? -3 : 3, false);
        if (target instanceof Listbox && available(target)) ((Listbox) target).scroll(false, delta > 0 ? -3 : 3, false);
        if (target instanceof Canvas && available(target)) ((Canvas) target).wheel(delta);
    }

    public String copySelection(boolean cut) {
        render(); TkWidget widget = widgets.get(focused);
        if (popup != null || menus != null) return "";   // the open list or menu has the keyboard
        if (widget instanceof Text && available(widget)) {
            Text text = (Text) widget; String selected = text.selectedText();
            if (cut && text.selectionPresent()) text.delete(text.first, text.last);
            return selected;
        }
        if (!(widget instanceof Entry) || !available(widget)) return "";
        Entry entry = (Entry) widget; String selected = entry.selectedText();
        if (cut && entry.selectionPresent()) entry.cut();
        return selected;
    }

    public Command key(int code, int modifiers) {
        return key(code, modifiers, 0);
    }

    public Command key(int code, int modifiers, int character) {
        render();
        if (popup != null) { popup.key(code, modifiers); return null; }   // the open list has the keyboard
        if (menus != null) { menus.key(code, modifiers, character); return null; }   // so do posted menus
        if (code == 68 && (modifiers & 7) == 0 && keyboardBar()) return null;   // F10: the menubar (tk::FirstMenu)
        TkWidget widget = widgets.get(focused);
        if (code == 15 && (!(widget instanceof Text) || (modifiers & 3) != 0)) { focusNext((modifiers & 1) != 0); return null; }
        // Up and Down step a Spinbox (classic: invoke; ttk: <<Increment>>/<<Decrement>>); Down opens a Combobox's list.
        if (widget instanceof Spinbox && available(widget) && (code == 200 || code == 208)) { stepFromPlayer((Spinbox) widget, code == 200 ? 1 : -1); return null; }
        if (widget instanceof Combobox && available(widget) && code == 208) { post((Combobox) widget); return null; }
        if (widget instanceof Treeview && available(widget)) { ((Treeview) widget).key(code); return null; }
        if (widget instanceof Text && available(widget)) ((Text) widget).key(code, character, modifiers);
        if (widget instanceof Entry && available(widget)) ((Entry) widget).key(code, character, modifiers);
        if (widget instanceof Listbox && available(widget)) ((Listbox) widget).key(code, modifiers);
        if (widget instanceof Scale && available(widget)) ((Scale) widget).key(code);
        if (widget instanceof Notebook && available(widget) && (code == 203 || code == 205)) {
            Notebook book = (Notebook) widget;   // Left/Right: the previous or next tab (ttk::notebook::CycleTab)
            if (book.cycle(code == 205 ? 1 : -1)) queue(book, book.command, "tab");
        }
        if (widget instanceof Scrollbar && available(widget)) {
            Scrollbar bar = (Scrollbar) widget;
            if (code == 200 || code == 203 || code == 208 || code == 205)
                queue(bar, bar.command, "scroll", code == 200 || code == 203 ? -1 : 1, "units");
        }
        return (code == 28 || code == 156 || code == 57) && (modifiers & 6) == 0
                && widget instanceof Button && available(widget) ? command((Button) widget) : null;
    }

    public void paste(String text) {
        render();
        if (popup != null || menus != null) return;   // the open list or menu has the keyboard
        TkWidget widget = widgets.get(focused);
        if (widget instanceof Text && available(widget)) ((Text) widget).paste(text);
        if (widget instanceof Entry && available(widget)) ((Entry) widget).paste(text);
    }

    private Command command(Button button) {
        if (button instanceof Checkbutton) ((Checkbutton) button).activate();
        return button.command() == 0 ? null : new Command(button.id, button.command());
    }
    public static final class Command {
        public final int widget, callback;
        public final Object[] args;
        Command(int widget, int callback, Object... args) { this.widget = widget; this.callback = callback; this.args = args; }
    }

    void destroy(TkWidget widget) {
        if (widget == root) throw new IllegalArgumentException("cannot destroy the display root");
        if (widget.container != null) widget.container.release(widget);
        if (widget instanceof Frame)
            for (TkWidget child : new ArrayList<>(((Frame) widget).children)) destroy(child);
        widget.parent.children.remove(widget); widget.parent.packed.remove(widget); widget.parent.gridded.remove(widget); widgets.remove(widget.id);
        cancelUnavailable(); changed(true);
    }

    public void clear() {
        for (TkWidget child : new ArrayList<>(root.children)) destroy(child);
        if (!images.isEmpty()) { images.clear(); changed(true); }
        uploads.clear();
    }

    // ---- Spinbox steps and a Combobox's list ------------------------------------------------

    /**
     * A player's step on a Spinbox (an arrow, Up/Down, ttk's wheel): made now and the program
     * told ("stepped": its -command, the variable, validation); a ttk one whose program binds
     * <<Increment>>/<<Decrement>> leaves it to the program ("step"), whose binding may stop it.
     */
    private void stepFromPlayer(Spinbox spin, int dir) {
        if (spin.ttk && spin.delegate) { notice(spin, spin.notify, "step", dir); return; }
        String before = spin.step(dir);
        if (before != null) notice(spin, spin.notify, "stepped", dir, before, spin.text());
    }

    // ---- grabs ---------------------------------------------------------------------------

    /** Each program's grab (Tk's grab set): the widget its windows' input is confined to. Never saved. */
    private final Map<Integer, Integer> grabs = new HashMap<>();

    void grab(TkWidget w) { grabs.put(programOf(w), w.id); changed(false); }
    void releaseGrab(TkWidget w) { if (grabs.remove(programOf(w), w.id)) changed(false); }
    /** The widget that holds program's grab, or null. */
    TkWidget grabOf(int program) {
        Integer id = grabs.get(program);
        TkWidget w = id == null ? null : widgets.get(id);
        if (id != null && (w == null || programOf(w) != program)) grabs.remove(program);
        return w == null || programOf(w) != program ? null : w;
    }

    /** Whether input to target is held back by its program's grab: it is outside the grab widget. */
    public boolean blocked(TkWidget target) {
        if (target == null) return false;
        TkWidget grab = grabOf(programOf(target));
        if (grab == null) return false;
        for (TkWidget w = target; w != null; w = w.parent) if (w == grab) return false;
        return true;
    }

    /** A press on a window held back by a grab brings the grabbing one up instead. */
    public void raiseGrab(TkWidget target) {
        TkWidget grab = grabOf(programOf(target));
        Frame window = grab == null ? null : TkWindow.of(grab);
        if (window != null && window.place != null) { raise(window); focusWindow(window); }
    }

    /** The open list (one per display), or null. */
    TkPopup popup;

    /** Whether a Combobox's list is open: it has the pointer and the keyboard, the program hears neither. */
    public boolean listOpen() { render(); return popup != null || menus != null; }

    /**
     * A press while a list is open (Tk's grab): a left one in the list is the list's; any press
     * elsewhere closes it and goes no further (its release neither). Whether a list was open.
     */
    public boolean listPress(long session, int x, int y, int button) {
        render();
        if (menus != null) {
            if (button == 1) rightPresses.put(session, -1);
            TkMenus posted = menus;
            posted.press(session, x, y, button);
            if (!posted.holds(session)) swallowed.add(session);
            return true;
        }
        if (popup == null) return false;
        if (button == 1) rightPresses.put(session, -1);
        if (!popup.box().contains(x, y)) { unpost(); swallowed.add(session); }
        else if (button == 0) popup.press(session, x, y);
        return true;
    }

    /** The pointer moved with no button down: an open list's highlight follows it. */
    public void pointerHover(int x, int y) {
        render();
        if (menus != null) menus.hover(x, y);
        else if (popup != null) popup.hover(x, y);
        else trackHover(x, y);
    }

    /** Light sash index of paned (null: none). */
    private void light(Panedwindow paned, int index) {
        if (litPaned == paned && (paned == null || paned.litSash == index)) return;
        if (litPaned != null) litPaned.litSash = -1;
        litPaned = paned;
        if (paned != null) paned.litSash = index;
        changed(false);
    }

    /** The pointer left the display (or its view closed): nothing is hovered, no edge is lit. */
    public void pointerLeft() {
        TkWidget old = widgets.get(hovered);
        if (old instanceof Text) notice(old, ((Text) old).notify, "leave");
        boolean repaint = hintWindow != 0 || old != null && old.look != null;
        hovered = hintWindow = hintEdges = 0;
        if (sashDrags.isEmpty()) light(null, -1);
        if (repaint) changed(false);
    }

    /** The widget under the pointer becomes the hovered one (its look may change: active, hover). */
    private void trackHover(int x, int y) {
        TkWidget at = hit(x, y);
        // A Text's current mark follows the pointer (Tk sets it as the mouse moves).
        if (at instanceof Text) ((Text) at).hover(x, y);
        // The edges a press here would resize: lit while the pointer is on them.
        int edges = TkWindow.is(at) ? TkWindow.resizeEdges((Frame) at, x, y) : 0;
        int window = edges == 0 ? 0 : at.id;
        if (window != hintWindow || edges != hintEdges) { hintWindow = window; hintEdges = edges; changed(false); }
        // A panedwindow's sash under the pointer is lit, as a window's edges are.
        if (sashDrags.isEmpty()) {
            int sash = at instanceof Panedwindow && available(at) ? ((Panedwindow) at).sashAt(x, y) : -1;
            if (sash == -1) light(null, -1); else light((Panedwindow) at, sash >= 0 ? sash : -2 - sash);
        }
        int id = at == null || at == root ? 0 : at.id;
        if (id == hovered) return;
        TkWidget old = widgets.get(hovered);
        hovered = id;
        if (old instanceof Text) notice(old, ((Text) old).notify, "leave");
        if (old != null && old.look != null || at != null && at.look != null) changed(false);
    }

    /** A player asks for a Combobox's list (its arrow, Down, a press on a readonly one): with a -postcommand the program runs it, then opens it. */
    private void post(Combobox box) {
        if (box.postcommand && box.notify > 0) notice(box, box.notify, "post");
        else open(box);
    }

    /** Open box's list (ttk::combobox::Post), in place of any other. */
    void open(Combobox box) {
        render();
        if (!available(box)) return;
        unpostMenus();
        popup = new TkPopup(box);
        changed(false);
    }

    void unpost() { if (popup != null) { popup = null; changed(false); } }

    // ---- menus ---------------------------------------------------------------------------

    /** The posted menus (one chain per display), or null. */
    TkMenus menus;

    /** Unpost every menu (their highlights go, as Tk's unpost resets the active entry). */
    void unpostMenus() { if (menus != null) { TkMenus old = menus; menus = null; old.close(0); changed(false); } }

    private void postMenus(TkMenus chain) { popup = null; menus = chain; changed(false); }

    /** A player's press on window's menubar entry index: its menu drops down (held until released). */
    private void pressBar(long session, Frame window, int index) {
        Menu bar = TkWindow.menubar(window);
        if (bar == null || index < 0 || bar.entries.get(index).disabled) { swallowed.add(session); return; }
        unpostMenus(); postMenus(TkMenus.fromBar(this, window, index));
        menus.hold(session);
    }

    /** F10 in a window with a menubar: its first menu drops down with its first entry lit. */
    private boolean keyboardBar() {
        Frame window = activeWindow();
        Menu bar = window == null ? null : TkWindow.menubar(window);
        if (bar == null) return false;
        for (int[] slot : TkWindow.barEntries(window)) {
            Menu.Entry e = bar.entries.get(slot[0]);
            if (!e.selectable()) continue;
            unpostMenus(); postMenus(TkMenus.fromBar(this, window, slot[0]));
            if (!menus.levels.isEmpty()) { Menu sub = menus.levels.get(0).menu; sub.activate(sub.next(-1, 1)); }
            return true;
        }
        return false;
    }

    /** A player's press on a Menubutton: its menu is posted (held, so a drag to an entry and release runs it). */
    private void pressMenubutton(long session, Menubutton button) {
        Menu menu = button.menu();
        if (menu == null) { swallowed.add(session); return; }
        unpostMenus(); postMenus(TkMenus.fromButton(this, button, menu));
        menus.hold(session);
    }

    /** The program's post or tk_popup of menu at (x, y) (entry >= 0: that entry under the point, lit). */
    void postMenu(Menu menu, int x, int y, int entry) {
        render();
        unpostMenus(); postMenus(TkMenus.fromProgram(this, menu, x, y, entry));
    }

    /** The program's unpost of menu (and its cascades); nothing if it is not posted. */
    void unpostMenu(Menu menu) { render(); if (menus != null) menus.unpost(menu); }

    boolean posted(Menu menu) { render(); return menus != null && menus.posted(menu); }

    /** LBSelected: the list closes, the value goes in (the program hears of it) and the Combobox has the keyboard. */
    void chooseFromList(Combobox box, int index) {
        unpost();
        box.choose(index);
        if (available(box)) focus(box.id);
    }

    /** LBTab: the highlighted value goes in (if there is one), the list closes and the keyboard moves on from the Combobox. */
    void tabFromList(Combobox box, boolean back, int index) {
        unpost();
        if (index >= 0) box.choose(index);
        if (!available(box)) return;
        focus(box.id);
        focusNext(back);
    }

    // ---- images ------------------------------------------------------------------------

    public TkImage image(int id) { return images.get(id); }
    public java.util.Collection<TkImage> images() { return java.util.Collections.unmodifiableCollection(images.values()); }

    /** The image table clients need (see TkScene.images). */
    public Map<Integer, TkScene.ImageRef> imageTable() {
        Map<Integer, TkScene.ImageRef> table = new LinkedHashMap<>();
        for (TkImage i : images.values()) table.put(i.id, new TkScene.ImageRef(i.version, i.width, i.height));
        return table;
    }

    /** Pixels still free for new images; throws when the display already has its maximum number of images. */
    long imageRoom() {
        if (images.size() >= TkImage.MAX_IMAGES) throw new IllegalArgumentException("image limit reached (" + TkImage.MAX_IMAGES + " per display)");
        long used = 0;
        for (TkImage i : images.values()) used += (long) i.width * i.height;
        return TkImage.MAX_PIXELS - used;
    }

    TkImage addImage(int width, int height, int[] argb) {
        TkImage.check(width, height);
        long room = imageRoom();
        if ((long) width * height > room)
            throw new IllegalArgumentException("not enough image room: " + width + "x" + height + " needs " + (long) width * height
                    + " pixels, " + room + " of " + TkImage.MAX_PIXELS + " are free on this display");
        if (nextImage == Integer.MAX_VALUE) throw new IllegalArgumentException("image IDs exhausted");
        TkImage image = new TkImage(nextImage++, width, height, argb, 1);
        image.program = caller;
        images.put(image.id, image);
        changed(true);
        return image;
    }

    /** As in Tk, widgets showing a deleted image go blank: they drop the reference (ids are never reused). */
    void deleteImage(int id) {
        if (images.remove(id) == null) return;
        for (TkWidget w : widgets.values()) {
            if (w instanceof Label && ((Label) w).image == id) ((Label) w).image = 0;
            else if (w instanceof Canvas) ((Canvas) w).items.replaceAll((key, item) -> item.image == id ? item.withImage(0) : item);
            else if (w instanceof Treeview) for (Treeview.Item item : ((Treeview) w).items.values())
                if (item.image == id) item.image = 0;
        }
        changed(true);
    }
    void imageChanged() { changed(true); }

    /** Files on their way in pieces for PhotoImage(file=...), one per program (see TkImageApi "upload"); never saved. */
    final Map<Integer, java.io.ByteArrayOutputStream> uploads = new HashMap<>();

    /** A tree replacing this one (a new program) keeps counting image ids, so clients never mistake a new image for an old one. */
    void continueImagesFrom(TkTree previous) { nextImage = Math.max(nextImage, previous.nextImage); }
}
