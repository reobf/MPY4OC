package reobf.mpy4oc.tk;

/** Server-owned single-line editing. Indices are Unicode code points, not UTF-16 offsets. */
public final class Entry extends Label {
    int cursor, anchor, selectionFirst = -1, selectionLast = -1, scrollX;
    private boolean followCursor = true;
    boolean readonly;
    public Entry(Frame parent) {
        super(parent, "", Kind.ENTRY);
        columns = 20; lines = 1; padx = pady = 2; border = 1;
        background = 0xFFFFFF; textAnchor = TkPack.Anchor.W;
    }
    @Override public Entry text(String value) {
        TkFontMetrics.checkText(value);
        if (value.indexOf('\n') >= 0) throw new IllegalArgumentException("Entry accepts one line");
        boolean changed = !value.equals(text());
        super.text(value); cursor = clamp(cursor); anchor = clamp(anchor);
        if (changed) { selectionFirst = selectionLast = -1; followCursor = true; }
        return this;
    }
    public int length() { return text().codePointCount(0, text().length()); }
    public int cursor() { return cursor; }
    public void cursor(int value) { alive(); cursor = clamp(value); followCursor = true; tree.changed(false); }
    int clamp(int index) { return Math.max(0, Math.min(index, length())); }
    private int offset(int index) { return text().offsetByCodePoints(0, clamp(index)); }
    public boolean selectionPresent() { return selectionFirst >= 0 && selectionLast > selectionFirst; }
    public int selectionFirst() { if (!selectionPresent()) throw new IllegalArgumentException("no selection"); return selectionFirst; }
    public int selectionLast() { if (!selectionPresent()) throw new IllegalArgumentException("no selection"); return selectionLast; }
    public String selectedText() { return selectionPresent() ? text().substring(offset(selectionFirst), offset(selectionLast)) : ""; }
    public void selectionClear() { alive(); selectionFirst = selectionLast = -1; tree.changed(false); }
    public void selectionFrom(int index) { alive(); anchor = clamp(index); tree.changed(false); }
    public void selectionRange(int first, int last) {
        alive(); if (!enabled) return;
        selectionFirst = clamp(first); selectionLast = clamp(last);
        if (selectionLast <= selectionFirst) selectionFirst = selectionLast = -1;
        tree.changed(false);
    }
    public void selectionTo(int index) {
        int at = clamp(index);
        if (at != anchor) selectionRange(Math.min(at, anchor), Math.max(at, anchor));
    }
    public void selectionAdjust(int index) {
        int at = clamp(index);
        if (selectionPresent()) anchor = at - selectionFirst < selectionLast - at ? selectionLast : selectionFirst;
        selectionTo(at);
    }
    private int afterDelete(int position, int first, int last) {
        return position <= first ? position : position < last ? first : position - last + first;
    }
    public void insert(int index, String value) {
        alive();
        if (!enabled || readonly) return;
        int at = clamp(index), count = value.codePointCount(0, value.length());
        int oldCursor = cursor, oldAnchor = anchor, first = selectionFirst, last = selectionLast;
        text(text().substring(0, offset(at)) + value + text().substring(offset(at)));
        cursor(oldCursor >= at ? oldCursor + count : oldCursor);
        anchor = oldAnchor >= at ? oldAnchor + count : oldAnchor;
        if (first >= 0) selectionRange(first >= at ? first + count : first, last > at ? last + count : last);
    }
    public void delete(int first, int last) {
        alive();
        if (!enabled || readonly) return;
        int start = clamp(first), end = Math.max(start, clamp(last));
        int oldCursor = cursor, oldAnchor = anchor, a = selectionFirst, b = selectionLast;
        text(text().substring(0, offset(start)) + text().substring(offset(end)));
        cursor(afterDelete(oldCursor, start, end)); anchor = afterDelete(oldAnchor, start, end);
        if (a >= 0) selectionRange(afterDelete(a, start, end), afterDelete(b, start, end));
    }
    int prefixWidth() { return prefixWidth(cursor); }
    int prefixWidth(int index) { return TkFontMetrics.width(text().substring(0, offset(index))); }
    private int usable() { return Math.max(1, bounds.width - 2 * (padx + border)); }
    int textOriginX() {
        if (followCursor) {
            int caret = prefixWidth();
            if (caret < scrollX) scrollX = caret;
            if (caret >= scrollX + usable()) scrollX = caret - usable() + 1;
        }
        scrollX = Math.max(0, Math.min(scrollX, Math.max(0, TkFontMetrics.width(text()) - usable() + 1)));
        return bounds.x + padx + border - scrollX;
    }
    int indexAt(int x, boolean nearest) {
        int start = textOriginX(), at = 0;
        for (int i = 0; i < text().length();) {
            int cp = text().codePointAt(i), advance = TkFontMetrics.advance(cp);
            if (x < start + (nearest ? advance / 2 : advance)) break;
            start += advance; at++; i += Character.charCount(cp);
        }
        return at;
    }
    void click(int x, int modifiers) {
        int at = indexAt(x, true);
        if ((modifiers & 1) != 0) {
            selectionAdjust(at); cursor(at);
        } else {
            cursor(at);
            if ((modifiers & 2) == 0) { selectionClear(); selectionFrom(at); }
        }
    }
    void drag(int x) {
        if (x < bounds.x + padx + border) scroll(-2, false);
        else if (x >= bounds.x + bounds.width - padx - border) scroll(2, false);
        int at = indexAt(x, true);
        selectionRange(Math.min(anchor, at), Math.max(anchor, at)); cursor(at);
    }
    void scroll(int amount, boolean pages) {
        alive();
        long next = scrollX + (long) amount * (pages ? usable() : TkFontMetrics.CELL_WIDTH);
        scrollX = (int) Math.max(0, Math.min(next, Math.max(0, TkFontMetrics.width(text()) - usable() + 1)));
        followCursor = false; tree.changed(false);
    }
    public Object[] xview() {
        bounds(); textOriginX(); int total = TkFontMetrics.width(text());
        return total == 0 ? new Object[] {0.0, 1.0} : new Object[] {scrollX / (double) total, Math.min(1.0, (scrollX + usable()) / (double) total)};
    }
    public void xviewTo(double fraction) {
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("finite fraction expected");
        bounds(); scrollX = (int) (Math.max(0, Math.min(1, fraction)) * TkFontMetrics.width(text()));
        followCursor = false; textOriginX(); tree.changed(false);
    }
    void restoreScroll(int pixels) { scrollX = pixels; followCursor = false; }
    private int word(int position, boolean right) {
        int at = clamp(position);
        if (right && at < length()) {
            boolean word = wordChar(at++);
            while (word && at < length() && wordChar(at)) at++;
            while (at < length() && Character.isWhitespace(text().codePointAt(offset(at)))) at++;
        } else if (!right) {
            while (at > 0 && Character.isWhitespace(text().codePointAt(offset(at - 1)))) at--;
            if (at > 0) { boolean word = wordChar(--at); while (word && at > 0 && wordChar(at - 1)) at--; }
        }
        return at;
    }
    private boolean wordChar(int at) { int cp = text().codePointAt(offset(at)); return Character.isLetterOrDigit(cp) || cp == '_'; }
    private void move(int at, boolean extend) {
        if (extend) {
            if (!selectionPresent()) anchor = cursor;
            selectionRange(Math.min(anchor, clamp(at)), Math.max(anchor, clamp(at)));
        } else { selectionClear(); selectionFrom(at); }
        cursor(at);
    }
    void key(int code, int character, int modifiers) {
        if ((modifiers & 4) != 0) return;
        boolean shift = (modifiers & 1) != 0, ctrl = (modifiers & 2) != 0;
        if (ctrl && code == 30) { selectionFrom(0); selectionRange(0, length()); cursor(length()); return; }
        switch (code) {
            case 203: move(ctrl ? word(cursor, false) : !shift && selectionPresent() ? selectionFirst : cursor - 1, shift); return;
            case 205: move(ctrl ? word(cursor, true) : !shift && selectionPresent() ? selectionLast : cursor + 1, shift); return;
            case 199: move(0, shift); return;
            case 207: move(length(), shift); return;
            case 14:
                if (selectionPresent()) delete(selectionFirst, selectionLast);
                else if (cursor > 0) delete(ctrl ? word(cursor, false) : cursor - 1, cursor);
                return;
            case 211:
                if (shift) return; // Shift-Delete is a server-authoritative cut request.
                if (selectionPresent()) delete(selectionFirst, selectionLast);
                else delete(cursor, ctrl ? word(cursor, true) : cursor + 1);
                return;
            default:
                if (!ctrl && character >= 32 && character <= 0x10FFFF && character != 127 && (character < 0xD800 || character > 0xDFFF))
                    paste(new String(Character.toChars(character)));
        }
    }
    void paste(String value) {
        alive(); if (!enabled || readonly) return;
        value = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        int first = selectionPresent() ? selectionFirst : cursor, last = selectionPresent() ? selectionLast : cursor;
        String before = text().substring(0, offset(first)), after = text().substring(offset(last));
        StringBuilder accepted = new StringBuilder();
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (cp < 32 || cp == 127 || cp >= 0xD800 && cp <= 0xDFFF) continue;
            String candidate = before + accepted.toString() + new String(Character.toChars(cp)) + after;
            try { TkFontMetrics.checkText(candidate); tree.checkTextBudget(this, candidate); accepted.appendCodePoint(cp); }
            catch (IllegalArgumentException full) { break; }
        }
        if (accepted.length() > 0) {
            text(before + accepted.toString() + after); cursor(first + accepted.codePointCount(0, accepted.length()));
            selectionClear(); selectionFrom(cursor);
        }
    }
}
