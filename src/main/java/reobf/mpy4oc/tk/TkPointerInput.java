package reobf.mpy4oc.tk;

import java.util.function.Consumer;
import reobf.mpy4oc.tk.net.TkPackets;

/** Coalesces render-frame motion into ticks; presses and the final drag endpoint stay ordered. */
public final class TkPointerInput {
    private final TkTarget target;
    private final Consumer<TkPackets.InputEvent> send;
    private int x, y, modifiers, sentX, sentY, sentModifiers;
    private boolean seen, inside, down, sent, left;
    public TkPointerInput(TkTarget target, Consumer<TkPackets.InputEvent> send) { this.target = target; this.send = send; }
    public void sample(int x, int y, boolean inside, int modifiers) {
        this.x = Math.max(-TileTkDisplay.MAX_SIZE, Math.min(2 * TileTkDisplay.MAX_SIZE - 1, x)); this.y = Math.max(-TileTkDisplay.MAX_SIZE, Math.min(2 * TileTkDisplay.MAX_SIZE - 1, y));
        this.inside = inside; this.modifiers = modifiers; seen = true;
    }
    private void emit(int kind, int button) { send.accept(new TkPackets.InputEvent(target, kind, x, y, button, 0, modifiers)); }
    private boolean moved() { return !sent || x != sentX || y != sentY || modifiers != sentModifiers; }
    private void remember() { sentX = x; sentY = y; sentModifiers = modifiers; sent = true; }
    public void press(int button) {
        if (!seen || !inside) return;
        emit(TkPackets.InputEvent.MOUSE_DOWN, button);
        if (button == 0) { down = true; remember(); }
    }
    public void release(int button) {
        if (!seen) return;
        if (button == 0 && down) {
            if (moved()) emit(TkPackets.InputEvent.DRAG, 0);
            emit(TkPackets.InputEvent.MOUSE_UP, 0); down = false; remember();
        } else if (inside) emit(TkPackets.InputEvent.MOUSE_UP, button);
    }
    public void tick() {
        if (seen && (inside || down) && (moved() || down)) {
            emit(down ? TkPackets.InputEvent.DRAG : TkPackets.InputEvent.MOTION, 0); remember(); left = false;
        } else if (seen && !inside && !down && sent && !left) {
            // One motion outside as the pointer leaves: the server stops showing what it was over.
            emit(TkPackets.InputEvent.MOTION, 0); remember(); left = true;
        }
    }
    public void wheel(int delta) { if (seen && inside && delta != 0) emit(TkPackets.InputEvent.WHEEL, Math.max(-1200, Math.min(1200, delta))); }
}
