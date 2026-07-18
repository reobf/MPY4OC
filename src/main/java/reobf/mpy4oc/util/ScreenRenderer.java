package reobf.mpy4oc.util;

import li.cil.oc.api.internal.TextBuffer;

/**
 * Renders an OpenComputers screen (a {@link TextBuffer}) as ANSI escape sequences
 * for a remote terminal.
 *
 * <h3>Why read the TextBuffer instead of intercepting the GPU</h3>
 * The obvious approach — subclass GraphicsCard and mirror every {@code set()} call
 * into a private char array — cannot work: it misses {@code fill}, {@code copy} and
 * {@code bitblt}, which OpenOS uses constantly (every scroll is a copy, every clear
 * is a fill). The mirror desyncs from the real screen almost immediately.
 *
 * The TextBuffer *is* the screen's authoritative state, and it exposes per-cell
 * reads. So we just read it — whatever the GPU did, however it did it, we see the
 * result. No interception, nothing to keep in sync.
 */
public final class ScreenRenderer {

    private ScreenRenderer() {}

    /** The previous frame, so we can send only what changed. */
    public static final class Frame {
        int width, height;
        int[] chars;      // code points
        int[] fg, bg;     // packed rgb

        boolean sameShapeAs(TextBuffer b) {
            return width == b.getWidth() && height == b.getHeight();
        }
    }

    /**
     * Produce the ANSI needed to bring the terminal from {@code prev} to the
     * buffer's current contents. Returns null if nothing changed.
     *
     * On the first call (or after a resize) this emits a full repaint; afterwards
     * it emits only the cells that actually differ, which keeps a busy screen from
     * flooding the SSH connection.
     */
    public static String render(TextBuffer buffer, Frame prev, boolean forceFull) {
        final int w = buffer.getWidth();
        final int h = buffer.getHeight();

        boolean full = forceFull || prev.chars == null || !prev.sameShapeAs(buffer);
        if (full) {
            prev.width = w;
            prev.height = h;
            prev.chars = new int[w * h];
            prev.fg = new int[w * h];
            prev.bg = new int[w * h];
            java.util.Arrays.fill(prev.chars, -1);   // force every cell to differ
        }

        StringBuilder out = new StringBuilder();
        if (full) {
            out.append("\u001b[2J");     // clear
            out.append("\u001b[H");      // home
        }

        int lastFg = -1, lastBg = -1;
        int cursorRow = -1, cursorCol = -1;
        boolean wrote = false;

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                int cp = buffer.getCodePoint(col, row);
                int fg = buffer.getForegroundColor(col, row);
                int bg = buffer.getBackgroundColor(col, row);

                if (!full && cp == prev.chars[i] && fg == prev.fg[i] && bg == prev.bg[i]) {
                    continue;   // unchanged, skip
                }
                prev.chars[i] = cp;
                prev.fg[i] = fg;
                prev.bg[i] = bg;

                // Move the cursor only when we are not already in the right place.
                if (cursorRow != row || cursorCol != col) {
                    out.append("\u001b[").append(row + 1).append(';').append(col + 1).append('H');
                    cursorRow = row;
                    cursorCol = col;
                }
                if (fg != lastFg) {
                    appendColor(out, fg, true);
                    lastFg = fg;
                }
                if (bg != lastBg) {
                    appendColor(out, bg, false);
                    lastBg = bg;
                }
                out.appendCodePoint(cp == 0 ? ' ' : cp);
                cursorCol++;   // the terminal advances on its own
                wrote = true;
            }
        }

        if (!wrote && !full) return null;
        out.append("\u001b[0m");
        return out.toString();
    }

    /** 24-bit ANSI colour. Every terminal worth using (incl. MobaXterm) speaks this. */
    private static void appendColor(StringBuilder out, int rgb, boolean foreground) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        out.append("\u001b[").append(foreground ? "38" : "48").append(";2;")
           .append(r).append(';').append(g).append(';').append(b).append('m');
    }
}
