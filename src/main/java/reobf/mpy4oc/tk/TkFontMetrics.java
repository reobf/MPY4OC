package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import li.cil.oc.util.FontUtils;

/** The same OC glyph-width cache on both sides; no client or AWT dependency. */
public final class TkFontMetrics {
    public static final int CELL_WIDTH = 8, LINE_HEIGHT = 16, MAX_TEXT_BYTES = 1024;
    private TkFontMetrics() {}

    public static int advance(int codePoint) { return CELL_WIDTH * FontUtils.wcwidth(codePoint); }

    public static int width(String text) {
        int widest = 0, line = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\n') { widest = Math.max(widest, line); line = 0; }
            else line += advance(cp);
        }
        return Math.max(widest, line);
    }

    public static int height(String text) {
        return LINE_HEIGHT * (1 + (int) text.chars().filter(c -> c == '\n').count());
    }

    public static void checkText(String text) {
        checkText(text, MAX_TEXT_BYTES);
    }
    public static void checkPaintText(String text) { checkText(text, 4096); }
    private static void checkText(String text, int maxBytes) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || text.codePoints().anyMatch(c -> c < 32 && c != '\n' || c == 127 || c >= 0xD800 && c <= 0xDFFF))
            throw new IllegalArgumentException("text must be Unicode, at most 1024 UTF-8 bytes (newline allowed)");
    }
}
