package reobf.mpy4oc.tk.client;

import java.nio.charset.StandardCharsets;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import reobf.mpy4oc.tk.TkPointerInput;
import reobf.mpy4oc.tk.TkClientState;
import reobf.mpy4oc.tk.TkScene;
import reobf.mpy4oc.tk.net.TkNetwork;
import reobf.mpy4oc.tk.net.TkPackets;

/** A shared canvas painted from the server's display list. */
@SideOnly(Side.CLIENT)
public final class GuiTkDisplay extends GuiScreen {
    private final TkClientState state;
    private final TkCanvasRenderer renderer = new TkCanvasRenderer();
    private final TkPointerInput pointer;
    private boolean serverClosed, resyncPending;
    private int left, top, canvasWidth, canvasHeight;
    private int ticks;
    private char highSurrogate;
    private boolean repeatSaved, previousRepeat;

    public GuiTkDisplay(TkPackets.OpenGui open) {
        state = new TkClientState(open); pointer = new TkPointerInput(open.target, TkNetwork::sendToServer);
    }
    public TkClientState state() { return state; }

    @Override public void initGui() {
        if (!repeatSaved) { previousRepeat = Keyboard.areRepeatEventsEnabled(); repeatSaved = true; }
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        drawDefaultBackground();
        TkScene scene = state.scene();
        renderer.prepare(scene, state.revision());
        double scale = Math.min(1.0, Math.min(Math.max(1, width - 32) / (double) scene.width,
                Math.max(1, height - 54) / (double) scene.height));
        canvasWidth = Math.max(1, (int) (scene.width * scale));
        canvasHeight = Math.max(1, (int) (scene.height * scale));
        left = (width - canvasWidth) / 2;
        top = (height - canvasHeight + 18) / 2;
        samplePointer(mouseX, mouseY);
        drawRect(left - 2, top - 20, left + canvasWidth + 2, top + canvasHeight + 2, 0xFF555555);
        drawRect(left, top - 18, left + canvasWidth, top, 0xFF303030);
        drawRect(left, top, left + canvasWidth, top + canvasHeight, 0xFF000000 | scene.background);
        renderer.draw(mc, scene, left, top, canvasWidth, canvasHeight);
        renderer.title(scene.title, left + 4, top - 17, Math.max(0, canvasWidth - 8));
        super.drawScreen(mouseX, mouseY, partialTick);
    }

    public void sync(TkPackets.SceneSync update) {
        if (!state.target.equals(update.target)) return;
        if (!state.accept(update) && !resyncPending) {
            resyncPending = true;
            TkNetwork.sendToServer(new TkPackets.Resync(state.target));
        } else if (update.isFull()) resyncPending = false;
    }

    public void closeFromServer() {
        serverClosed = true;
        mc.displayGuiScreen(null);
        mc.setIngameFocus();
    }

    @Override
    public void onGuiClosed() {
        if (repeatSaved) Keyboard.enableRepeatEvents(previousRepeat);
        if (!serverClosed && mc.getNetHandler() != null) TkNetwork.sendToServer(new TkPackets.CloseRequest(state.target));
    }

    @Override
    public void updateScreen() {
        if (mc.theWorld == null || mc.theWorld.provider.dimensionId != state.target.dimension) closeFromServer();
        else {
            pointer.tick();
            if (++ticks % 20 == 0) TkNetwork.sendToServer(new TkPackets.Heartbeat(state.target));
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (!inside(x, y)) return;
        samplePointer(x, y);
        if (button == 2) paste();
        else pointer.press(button);
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (button < 0 || button == 2) return;
        samplePointer(x, y); pointer.release(button);
    }

    @Override protected void mouseClickMove(int x, int y, int button, long elapsed) { samplePointer(x, y); }

    @Override public void handleMouseInput() {
        super.handleMouseInput();
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        samplePointer(x, y); pointer.wheel(Mouse.getEventDWheel());
    }

    @Override public void handleKeyboardInput() {
        super.handleKeyboardInput();
        if (!Keyboard.getEventKeyState() && mc.currentScreen == this)
            TkNetwork.sendToServer(new TkPackets.InputEvent(state.target, TkPackets.InputEvent.KEY_UP,
                    0, 0, Keyboard.getEventKey(), 0, modifiers()));
    }

    @Override
    protected void keyTyped(char character, int code) {
        if (code == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
            return;
        }
        int point = character;
        if (Character.isHighSurrogate(character)) { highSurrogate = character; return; }
        if (Character.isLowSurrogate(character)) {
            if (highSurrogate == 0) return;
            point = Character.toCodePoint(highSurrogate, character);
        }
        highSurrogate = 0;
        TkNetwork.sendToServer(new TkPackets.InputEvent(state.target, TkPackets.InputEvent.KEY_DOWN, 0, 0, code, point, modifiers()));
        if (isCtrlKeyDown() && code == Keyboard.KEY_V || isShiftKeyDown() && code == Keyboard.KEY_INSERT) paste();
        else if (isCtrlKeyDown() && (code == Keyboard.KEY_C || code == Keyboard.KEY_X || code == Keyboard.KEY_INSERT)
                || isShiftKeyDown() && code == Keyboard.KEY_DELETE)
            TkNetwork.sendToServer(new TkPackets.CopyRequest(state.target, state.requestCopy(), code == Keyboard.KEY_X || code == Keyboard.KEY_DELETE));
    }

    public void copyResult(TkPackets.CopyResult reply) {
        String text = state.acceptCopy(reply);
        if (text != null && !text.isEmpty()) setClipboardString(text);
    }

    private boolean inside(int x, int y) {
        return x >= left && y >= top && x < left + canvasWidth && y < top + canvasHeight;
    }

    private void samplePointer(int x, int y) {
        if (canvasWidth == 0 || canvasHeight == 0) return;
        TkScene scene = state.scene();
        pointer.sample(Math.floorDiv((x - left) * scene.width, canvasWidth),
                Math.floorDiv((y - top) * scene.height, canvasHeight), inside(x, y), modifiers());
    }

    private int modifiers() {
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        return (isShiftKeyDown() ? 1 : 0) | (isCtrlKeyDown() ? 2 : 0) | (alt ? 4 : 0);
    }

    private void paste() {
        String text = getClipboardString();
        // Keep whole Unicode code points when limiting clipboard bytes.
        int length = 0, end = 0;
        while (end < text.length()) {
            int next = end + Character.charCount(text.codePointAt(end));
            int bytes = text.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
            if (length + bytes > TkPackets.MAX_CLIPBOARD_BYTES) break;
            length += bytes;
            end = next;
        }
        if (end > 0) TkNetwork.sendToServer(new TkPackets.Clipboard(state.target, text.substring(0, end)));
    }
}
