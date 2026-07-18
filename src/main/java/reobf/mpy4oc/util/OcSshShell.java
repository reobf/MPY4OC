package reobf.mpy4oc.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import li.cil.oc.api.internal.TextBuffer;

/**
 * An interactive SSH shell attached to an in-game OpenComputers screen: renders the
 * screen to the terminal and feeds keystrokes back to the computer as key signals.
 * Together with the SFTP subsystem on the same connection, a client like MobaXterm
 * gives you the machine's console on the right and its filesystem on the left.
 *
 * <h3>How input reaches the computer</h3>
 * A real keyboard sends {@code computer.checked_signal} — which carries an
 * EntityPlayer and is permission-checked. We have no player, so we send the plain
 * {@code computer.signal} message instead; OC's Machine accepts it and prepends the
 * source address exactly as it does for a keyboard. So the computer cannot tell the
 * difference between us and a keyboard block.
 *
 * <h3>Threading</h3>
 * SSHD calls us on its own network threads, but the TextBuffer and the OC network
 * belong to the server thread. Every touch of game state therefore goes through
 * {@link SessionBridge}, which the card pumps from its {@code update()} on the main
 * thread. Nothing here reaches into the game directly.
 */
public class OcSshShell implements Command, Runnable {

    /** What a session needs from the game side, all main-thread mediated. */
    public interface SessionBridge {
        /** The screen to mirror, or null if the card is not bound to one. */
        TextBuffer screen();

        /**
         * Queue one indivisible group of signals for delivery on the server thread.
         * A group is delivered whole or not at all -- see withControl(), where
         * splitting the group would strand OpenOS believing Ctrl is still held.
         * Each signal is {name, char, code}.
         */
        void keyStroke(Object[][] signals);

        /** Paste, delivered as OC's "clipboard" signal. */
        void clipboard(String text);

        /**
         * The SSH terminal is now cols x rows. For a virtual screen this resizes the
         * OC screen to match, so OpenOS reflows to the window; for a mirrored real
         * screen it is informational (the block's size is fixed). Returns true if the
         * underlying screen size changed, so the caller knows to repaint in full.
         */
        boolean resize(int cols, int rows);

        /** False once the card is gone/unloaded, which ends the session. */
        boolean alive();
    }

    private final SessionBridge bridge;

    private InputStream in;
    private OutputStream out;
    private ExitCallback exit;
    private Thread thread;
    private volatile boolean running;

    public OcSshShell(SessionBridge bridge) {
        this.bridge = bridge;
    }

    // ---- SSHD Command SPI -------------------------------------------------

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        // The screen is the only output; errors go to the same place or nowhere.
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        this.env = env;
        applyEnvSize();   // initial size from the pty request

        // Follow window resizes. A terminal that changes size and is not told to
        // repaint shows the previous frame at the old coordinates -- the misaligned
        // output you get after changing the font/zoom. On WINCH we re-read the size
        // and force a full repaint.
        try {
            env.addSignalListener((ch, sig) -> applyEnvSize(),
                    org.apache.sshd.server.Signal.WINCH);
        } catch (Throwable ignored) {
            // some clients never send WINCH; the initial size still applies
        }

        running = true;
        thread = new Thread(this, "mpy4oc-ssh-shell");
        thread.setDaemon(true);
        thread.start();
    }

    private volatile Environment env;
    private volatile boolean needsRepaint;

    /** Read COLUMNS/LINES from the environment and push the size to the bridge. */
    private void applyEnvSize() {
        if (env == null) return;
        try {
            java.util.Map<String, String> e = env.getEnv();
            int cols = parse(e.get(Environment.ENV_COLUMNS), 80);
            int rows = parse(e.get(Environment.ENV_LINES), 25);
            if (cols > 0 && rows > 0) {
                bridge.resize(cols, rows);
                needsRepaint = true;   // size (or the client) changed: repaint fully
            }
        } catch (Throwable ignored) {
        }
    }

    private static int parse(String s, int dflt) {
        if (s == null) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    @Override
    public void destroy(ChannelSession channel) throws Exception {
        running = false;
        if (thread != null) thread.interrupt();
    }

    // ---- the session ------------------------------------------------------

    @Override
    public void run() {
        ScreenRenderer.Frame frame = new ScreenRenderer.Frame();
        try {
            // Take over the terminal: alternate screen, hide cursor.
            write("\u001b[?1049h\u001b[?25l");

            Thread reader = new Thread(this::pumpInput, "mpy4oc-ssh-input");
            reader.setDaemon(true);
            reader.start();

            boolean firstPaint = true;
            boolean toldNoScreen = false;
            while (running && bridge.alive()) {
                TextBuffer screen = bridge.screen();
                if (screen == null) {
                    // Connected, authenticated, but the card has not been pointed at a
                    // screen yet. Say so once and idle -- redrawing this every poll
                    // would just make the terminal flicker.
                    if (!toldNoScreen) {
                        write("\u001b[2J\u001b[H"
                            + "No screen attached to this card.\r\n\r\n"
                            + "  sftp.term()          mirror the screen the GPU is bound to\r\n\r\n"
                            + "SFTP works regardless. This view will start as soon as a\r\n"
                            + "screen is attached.\r\n");
                        toldNoScreen = true;
                    }
                    sleep(500);
                    firstPaint = true;   // repaint in full once a screen shows up
                    continue;
                }
                toldNoScreen = false;
                boolean full = firstPaint || needsRepaint;
                needsRepaint = false;
                // The game writes this buffer from its own threads while we read it.
                // A torn read is harmless (the next diff heals it), but a concurrent
                // resolution shrink can make a read go out of bounds and throw. That
                // must cost us one frame, not the whole session -- so exceptions are
                // contained per frame and we retry next tick with a full repaint.
                try {
                    String delta = ScreenRenderer.render(screen, frame, full);
                    firstPaint = false;
                    if (delta != null) write(delta);
                } catch (RuntimeException e) {
                    needsRepaint = true;   // frame state may be inconsistent now
                }
                sleep(50);      // ~20 fps, matching the game tick
            }
        } catch (Exception ignored) {
            // client went away
        } finally {
            try {
                write("\u001b[?25h\u001b[?1049l");   // restore terminal
            } catch (Exception ignored) {
            }
            running = false;
            if (exit != null) exit.onExit(0);
        }
    }

    /** Read the client's keystrokes and translate them into OC key signals. */
    private void pumpInput() {
        try {
            while (running && bridge.alive()) {
                int b = in.read();
                if (b < 0) break;

                if (b == 0x1b) {                 // escape sequence: arrows, home, ...
                    handleEscape();
                    continue;
                }
                if (b == 0x03) {                 // Ctrl+C — send with control held
                    withControl('\u0003', Keys.C);
                    continue;
                }
                if (b == 0x04) {                 // Ctrl+D
                    withControl('\u0004', Keys.D);
                    continue;
                }
                if (b == '\r' || b == '\n') {
                    tap('\r', Keys.ENTER);
                    continue;
                }
                if (b == 0x7f || b == 0x08) {    // DEL / BS both mean backspace here
                    tap('\b', Keys.BACK);
                    continue;
                }
                if (b == '\t') {
                    tap('\t', Keys.TAB);
                    continue;
                }
                if (b < 0x20) {                  // other control chars: Ctrl+<letter>
                    char letter = (char) ('a' + b - 1);
                    withControl((char) b, Keys.ofChar(letter));
                    continue;
                }

                // Printable: decode UTF-8 so non-ASCII works.
                int cp = decodeUtf8(b);
                if (cp < 0) continue;
                tap((char) cp, Keys.ofChar(Character.toLowerCase((char) cp)));
            }
        } catch (Exception ignored) {
        } finally {
            running = false;
        }
    }

    /** ESC [ A ... — arrows and friends. */
    private void handleEscape() throws IOException {
        int b1 = in.read();
        if (b1 != '[' && b1 != 'O') return;
        int b2 = in.read();
        switch (b2) {
            case 'A': tap('\0', Keys.UP); return;
            case 'B': tap('\0', Keys.DOWN); return;
            case 'C': tap('\0', Keys.RIGHT); return;
            case 'D': tap('\0', Keys.LEFT); return;
            case 'H': tap('\0', Keys.HOME); return;
            case 'F': tap('\0', Keys.END); return;
            default: break;
        }
        // ESC [ <n> ~   (delete, page up/down, home/end on some terminals)
        if (b2 >= '0' && b2 <= '9') {
            int n = b2 - '0';
            int c;
            while ((c = in.read()) >= '0' && c <= '9') n = n * 10 + (c - '0');
            if (c != '~') return;
            switch (n) {
                case 1: tap('\0', Keys.HOME); break;
                case 3: tap('\0', Keys.DELETE); break;
                case 4: tap('\0', Keys.END); break;
                case 5: tap('\0', Keys.PAGE_UP); break;
                case 6: tap('\0', Keys.PAGE_DOWN); break;
                default: break;
            }
        }
    }

    /** A plain press+release, submitted as one group. */
    private void tap(char ch, int code) {
        bridge.keyStroke(new Object[][] {
            down(ch, code),
            up(ch, code),
        });
    }

    /**
     * A keystroke with Ctrl held. OpenOS decides "is control down" by looking at
     * pressedCodes[lcontrol], so we must actually bracket the key with a press and
     * release of left-control — sending only the control character is not enough.
     *
     * All four signals go in as one group precisely because they must not be split:
     * delivering the lcontrol press without its release would leave OpenOS treating
     * every later keystroke as a control chord, and the terminal would look frozen.
     */
    private void withControl(char ch, int code) {
        bridge.keyStroke(new Object[][] {
            down('\0', Keys.LCONTROL),
            down(ch, code),
            up(ch, code),
            up('\0', Keys.LCONTROL),
        });
    }

    /**
     * OC carries key signals as (char, code) where both are numbers; handing over a
     * java Character would surface as a string on the Lua/Python side.
     */
    private static Object[] down(char ch, int code) {
        return new Object[] { "key_down", Double.valueOf(ch), Double.valueOf(code) };
    }

    private static Object[] up(char ch, int code) {
        return new Object[] { "key_up", Double.valueOf(ch), Double.valueOf(code) };
    }

    /** Minimal UTF-8 continuation reader; b is the already-read lead byte. */
    private int decodeUtf8(int b) throws IOException {
        if (b < 0x80) return b;
        int extra, cp;
        if ((b & 0xE0) == 0xC0) { extra = 1; cp = b & 0x1F; }
        else if ((b & 0xF0) == 0xE0) { extra = 2; cp = b & 0x0F; }
        else if ((b & 0xF8) == 0xF0) { extra = 3; cp = b & 0x07; }
        else return -1;
        for (int i = 0; i < extra; i++) {
            int c = in.read();
            if (c < 0 || (c & 0xC0) != 0x80) return -1;
            cp = (cp << 6) | (c & 0x3F);
        }
        return cp;
    }

    private void write(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * LWJGL scancodes, which is what OC's key_down/key_up signals carry (see
     * OpenOS's lib/keyboard.lua).
     */
    public static final class Keys {
        public static final int BACK = 0x0E, TAB = 0x0F, ENTER = 0x1C, LCONTROL = 0x1D;
        public static final int SPACE = 0x39;
        public static final int C = 0x2E, D = 0x20;
        public static final int UP = 0xC8, LEFT = 0xCB, RIGHT = 0xCD, DOWN = 0xD0;
        public static final int HOME = 0xC7, END = 0xCF;
        public static final int PAGE_UP = 0xC9, PAGE_DOWN = 0xD1;
        public static final int DELETE = 0xD3;

        /** Scancode for a printable character, or 0 when we do not know one. */
        static int ofChar(char c) {
            if (c == ' ') return SPACE;
            Integer v = MAP.get(c);
            return v == null ? 0 : v.intValue();
        }

        private static final Map<Character, Integer> MAP = new java.util.HashMap<Character, Integer>();
        static {
            String row1 = "1234567890-=";
            int[] row1c = { 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D };
            String row2 = "qwertyuiop[]";
            int[] row2c = { 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B };
            String row3 = "asdfghjkl;'";
            int[] row3c = { 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28 };
            String row4 = "zxcvbnm,./";
            int[] row4c = { 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35 };
            put(row1, row1c);
            put(row2, row2c);
            put(row3, row3c);
            put(row4, row4c);
        }

        private static void put(String chars, int[] codes) {
            for (int i = 0; i < chars.length(); i++) {
                MAP.put(Character.valueOf(chars.charAt(i)), Integer.valueOf(codes[i]));
            }
        }
    }
}
