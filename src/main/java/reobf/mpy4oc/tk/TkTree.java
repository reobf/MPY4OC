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
    private final Map<Long, Button> pressed = new HashMap<>();
    private static final class Capture {
        final TkWidget widget; final int item; int x, y;
        Capture(TkWidget widget, int x, int y) { this.widget = widget; this.x = x; this.y = y; item = widget instanceof Canvas ? ((Canvas) widget).hitItem(x, y) : 0; }
    }
    private final Map<Long, Capture> captured = new HashMap<>();
    private final Map<Long, Command> queued = new LinkedHashMap<>();
    private Runnable onChange;
    private final Frame root;
    int nextId, width = 320, height = 240, focused;
    private boolean dirty = true, layoutDirty = true;
    private TkDisplayList display = TkDisplayList.EMPTY;

    public TkTree() { this(() -> {}); }
    public TkTree(Runnable onChange) { this.onChange = onChange; root = new Frame(this, null); }
    public Frame root() { return root; }
    public TkWidget get(int id) { return widgets.get(id); }
    public int focused() { render(); return focused; }
    void queue(TkWidget widget, int callback, Object... args) {
        if (callback > 0) queued.put(((long) widget.id << 32) | callback, new Command(widget.id, callback, args));
    }
    public List<Command> takeCommands() {
        render(); List<Command> result = new ArrayList<>();
        for (Command c : queued.values()) if (widgets.containsKey(c.widget)) result.add(c);
        queued.clear(); return result;
    }
    void setOnChange(Runnable callback) { onChange = callback; }

    int register(TkWidget widget) {
        if (widgets.size() > TkDisplayList.MAX_ITEMS || nextId == Integer.MAX_VALUE)
            throw new IllegalArgumentException("widget limit reached");
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

    void checkTextBudget(TkWidget replaced, String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        for (TkWidget widget : widgets.values())
            if (widget != replaced) bytes += widget.text().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > TkDisplayList.MAX_TEXT_BYTES) throw new IllegalArgumentException("widget text budget exceeded");
    }

    public void resize(int width, int height) {
        if (!TkScene.validSize(width) || !TkScene.validSize(height)) throw new IllegalArgumentException("invalid canvas size");
        if (this.width != width || this.height != height) {
            this.width = width; this.height = height; changed(true);
        }
    }

    public TkDisplayList render() {
        if (!dirty) return display;
        if (layoutDirty) { TkPackLayout.layout(root, width, height); layoutDirty = false; }
        cancelUnavailable();
        List<TkDisplayList.Item> items = new ArrayList<>();
        collect(root, items);
        display = new TkDisplayList(items);
        dirty = false;
        return display;
    }

    private void collect(Frame parent, List<TkDisplayList.Item> items) {
        for (TkWidget widget : parent.children) {
            if (widget.clip.empty()) continue;
            if (TkPaint.special(widget, focused == widget.id, items)) continue;
            int insetX = widget.padx + widget.border, insetY = widget.pady + widget.border;
            String paintText = widget instanceof Label ? ((Label) widget).paintText() : widget.text();
            int tx = widget.bounds.x + insetX
                    + (widget.bounds.width - 2 * insetX - TkFontMetrics.width(paintText)) * widget.textAnchor.x / 2;
            int ty = widget.bounds.y + insetY
                    + (widget.bounds.height - 2 * insetY - TkFontMetrics.height(paintText)) * widget.textAnchor.y / 2;
            if (widget instanceof Entry) tx = ((Entry) widget).textOriginX();
            int state = (pressed.containsValue(widget) ? TkDisplayList.PRESSED : 0)
                    | (focused == widget.id ? TkDisplayList.FOCUSED : 0)
                    | (!available(widget) ? TkDisplayList.DISABLED : 0);
            Entry entry = widget instanceof Entry ? (Entry) widget : null;
            int caret = entry != null && !entry.readonly && focused == widget.id ? tx + entry.prefixWidth() : -1;
            if (caret < widget.clip.x || caret >= widget.clip.x + widget.clip.width) caret = -1;
            int selectionX = 0, selectionWidth = 0;
            if (entry != null && available(entry) && entry.selectionPresent()) {
                selectionX = Math.max(widget.clip.x, tx + entry.prefixWidth(entry.selectionFirst()));
                int right = Math.min(widget.clip.x + widget.clip.width, tx + entry.prefixWidth(entry.selectionLast()));
                selectionWidth = Math.max(0, right - selectionX);
                if (selectionWidth == 0) selectionX = 0;
            }
            items.add(new TkDisplayList.Item(widget.id, widget.kind, widget.bounds, widget.clip,
                    widget.background, widget.foreground, widget.border, state, tx, ty, paintText,
                    caret, selectionX, selectionWidth));
            if (widget instanceof Frame) collect((Frame) widget, items);
        }
    }

    boolean available(TkWidget widget) {
        if (widgets.get(widget.id) != widget || widget.clip.empty()) return false;
        for (TkWidget current = widget; current != null; current = current.parent)
            if (!current.enabled && !(current == widget && current instanceof Text) || current.parent != null && current.manager().isEmpty()) return false;
        return true;
    }

    void cancelUnavailable() {
        boolean removed = pressed.values().removeIf(button -> !available(button));
        removed |= captured.values().removeIf(c -> !available(c.widget) || (c.widget instanceof Entry || c.widget instanceof Text) && c.widget.id != focused);
        TkWidget focus = widgets.get(focused);
        if (focused != 0 && (focus == null || !available(focus))) { focused = 0; removed = true; }
        if (removed) changed(false);
    }

    public TkWidget hit(int x, int y) { render(); return hit(root, x, y); }
    private TkWidget hit(TkWidget widget, int x, int y) {
        if (!widget.clip.contains(x, y)) return null;
        if (widget instanceof Frame) {
            List<TkWidget> children = ((Frame) widget).children;
            for (int i = children.size() - 1; i >= 0; i--) {
                TkWidget child = hit(children.get(i), x, y);
                if (child != null) return child;
            }
        }
        return widget;
    }

    public void focus(int id) {
        render();
        TkWidget widget = widgets.get(id);
        if (id != 0 && (!focusable(widget) || !available(widget)))
            throw new IllegalArgumentException("widget cannot take focus");
        if (focused != id) { focused = id; cancelUnavailable(); changed(false); }
    }

    public void focusNext(boolean reverse) {
        render();
        List<Integer> candidates = new ArrayList<>();
        for (TkWidget widget : widgets.values()) if (focusable(widget) && available(widget)) candidates.add(widget.id);
        if (candidates.isEmpty()) { focus(0); return; }
        int current = candidates.indexOf(focused);
        int next = current < 0 ? (reverse ? candidates.size() - 1 : 0)
                : Math.floorMod(current + (reverse ? -1 : 1), candidates.size());
        focus(candidates.get(next));
    }
    private boolean focusable(TkWidget w) { return w instanceof Button || w instanceof Entry || w instanceof Text || w instanceof Listbox || w instanceof Scale || w instanceof Scrollbar; }

    public void pointerDown(long session, int x, int y) {
        pointerDown(session, x, y, 0);
    }

    public void pointerDown(long session, int x, int y, int modifiers) {
        release(session);
        TkWidget hit = hit(x, y);
        if (hit instanceof Button && available(hit)) {
            pressed.put(session, (Button) hit); focus(hit.id); changed(false);
        } else if (hit instanceof Entry && available(hit)) {
            focus(hit.id); ((Entry) hit).click(x, modifiers);
            // The latest press owns this shared Entry selection; older drags stop.
            captured.values().removeIf(c -> c.widget == hit);
        } else if (hit != null && focusable(hit) && available(hit)) {
            focus(hit.id);
            captured.values().removeIf(c -> c.widget == hit);
            if (hit instanceof Text) ((Text) hit).press(x, y, modifiers);
            else if (hit instanceof Listbox) ((Listbox) hit).press(y, modifiers);
            else if (hit instanceof Scale) ((Scale) hit).drag(x, y);
            else if (hit instanceof Scrollbar) ((Scrollbar) hit).press(x, y);
        } else focus(0);
        if (hit != null && available(hit)) captured.put(session, new Capture(hit, x, y));
    }

    public TkWidget capture(long session) { render(); Capture c = captured.get(session); return c == null ? null : c.widget; }
    public int capturedItem(long session) { Capture c = captured.get(session); return c == null ? 0 : c.item; }
    public boolean pointerMoved(long session, int x, int y) {
        Capture c = captured.get(session); return c != null && (c.x != x || c.y != y);
    }
    public TkWidget pointerDrag(long session, int x, int y) {
        TkWidget target = capture(session);
        if (target instanceof Entry) {
            Entry entry = (Entry) target;
            if (pointerMoved(session, x, y) || x < entry.bounds.x + entry.padx + entry.border
                    || x >= entry.bounds.x + entry.bounds.width - entry.padx - entry.border) entry.drag(x);
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
        captured.remove(session);
        Button button = pressed.remove(session);
        if (button == null) return null;
        changed(false);
        return available(button) && hit(x, y) == button ? command(button) : null;
    }

    public void release(long session) {
        Capture old = captured.remove(session);
        if (old != null && old.widget instanceof Canvas) ((Canvas) old.widget).current = 0;
        if (pressed.remove(session) != null) changed(false);
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
        TkWidget target = hit(x, y);
        if (target instanceof Text && available(target)) ((Text) target).scroll(false, delta > 0 ? -3 : 3, false);
        if (target instanceof Entry && available(target)) ((Entry) target).scroll(delta > 0 ? -3 : 3, false);
        if (target instanceof Listbox && available(target)) ((Listbox) target).scroll(false, delta > 0 ? -3 : 3, false);
    }

    public String copySelection(boolean cut) {
        render(); TkWidget widget = widgets.get(focused);
        if (widget instanceof Text && available(widget)) {
            Text text = (Text) widget; String selected = text.selectedText();
            if (cut && text.selectionPresent()) text.delete(text.first, text.last);
            return selected;
        }
        if (!(widget instanceof Entry) || !available(widget)) return "";
        Entry entry = (Entry) widget; String selected = entry.selectedText();
        if (cut && entry.selectionPresent()) entry.delete(entry.selectionFirst(), entry.selectionLast());
        return selected;
    }

    public Command key(int code, int modifiers) {
        return key(code, modifiers, 0);
    }

    public Command key(int code, int modifiers, int character) {
        render();
        TkWidget widget = widgets.get(focused);
        if (code == 15 && (!(widget instanceof Text) || (modifiers & 3) != 0)) { focusNext((modifiers & 1) != 0); return null; }
        if (widget instanceof Text && available(widget)) ((Text) widget).key(code, character, modifiers);
        if (widget instanceof Entry && available(widget)) ((Entry) widget).key(code, character, modifiers);
        if (widget instanceof Listbox && available(widget)) ((Listbox) widget).key(code, modifiers);
        if (widget instanceof Scale && available(widget)) ((Scale) widget).key(code);
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
        if (widget instanceof Frame)
            for (TkWidget child : new ArrayList<>(((Frame) widget).children)) destroy(child);
        widget.parent.children.remove(widget); widget.parent.packed.remove(widget); widget.parent.gridded.remove(widget); widgets.remove(widget.id);
        cancelUnavailable(); changed(true);
    }

    public void clear() { for (TkWidget child : new ArrayList<>(root.children)) destroy(child); }
}
