package reobf.mpy4oc.tk;

final class TkText {
    private TkText() {}
    static String wrap(String text, int pixels) {
        StringBuilder out = new StringBuilder();
        boolean firstLine = true;
        for (String line : text.split("\n", -1)) {
            if (!firstLine) out.append('\n'); firstLine = false;
            int start = 0;
            while (start < line.length()) {
                int end = start, width = 0, space = -1;
                while (end < line.length()) {
                    int cp = line.codePointAt(end), advance = TkFontMetrics.advance(cp);
                    if (end > start && width + advance > pixels) break;
                    if (Character.isWhitespace(cp)) space = end;
                    width += advance; end += Character.charCount(cp);
                }
                if (end < line.length() && space > start) end = space;
                out.append(line, start, end); start = end;
                if (start < line.length()) {
                    while (start < line.length() && Character.isWhitespace(line.codePointAt(start))) start += Character.charCount(line.codePointAt(start));
                    if (start < line.length()) out.append('\n');
                }
            }
        }
        return out.toString();
    }
    /** Only the horizontal viewport's characters enter a paint record. */
    static String visible(String text, int scroll, int pixels) {
        int start = 0, width = 0, end;
        while (start < text.length()) { int cp = text.codePointAt(start), n = TkFontMetrics.advance(cp); if (width + n > scroll) break; width += n; start += Character.charCount(cp); }
        end = start;
        while (end < text.length() && width < scroll + pixels) { int cp = text.codePointAt(end); width += TkFontMetrics.advance(cp); end += Character.charCount(cp); }
        return text.substring(start, end);
    }
    static int visibleOffset(String text, int scroll) {
        int width = 0;
        for (int at = 0; at < text.length();) { int cp = text.codePointAt(at), n = TkFontMetrics.advance(cp); if (width + n > scroll) break; width += n; at += Character.charCount(cp); }
        return width - scroll;
    }
}
