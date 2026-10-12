package reobf.mpy4oc.tk;

/** Server-owned single-line editing. Indices are Unicode code points, not UTF-16 offsets. */
public class Entry extends Label {
    int cursor, anchor, selectionFirst = -1, selectionLast = -1, scrollX;
    private boolean followCursor = true;
    boolean readonly;
    /** Tk's -show: every character drawn (and copied) as the first one of this; "" shows the text itself. */
    String show = "";
    /** Tk's -justify: where the text sits while it fits (LEFT, CENTER, RIGHT). */
    int justify;
    static final int LEFT = 0, CENTER = 1, RIGHT = 2;
    static final String[] JUSTIFY = {"left", "center", "right"};
    /**
     * Tk's -validate as far as the server acts on it: the program sends "none" while it has no
     * -validatecommand. {@link #notify} is the program's callback for this widget's notices
     * (TkTree.notice): validation, focus, spins, the drop-down list.
     */
    String validate = "none";
    int notify;

    public Entry(Frame parent) { this(parent, Kind.ENTRY); }
    Entry(Frame parent, Kind kind) {
        super(parent, "", kind);
        columns = 20; lines = 1; padLeft = padTop = padRight = padBottom = 2; border = 1; relief = Relief.SUNKEN;
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
    /**
     * A new value as Tk's EntrySetValue stores it (a Spinbox step, ttk's set, a Combobox
     * choice): the cursor, the anchor and the selection keep their indices, cut to the new length.
     */
    void value(String value) {
        TkFontMetrics.checkText(value);
        if (value.indexOf('\n') >= 0) throw new IllegalArgumentException("Entry accepts one line");
        super.text(value);
        cursor = clamp(cursor); anchor = clamp(anchor);
        if (selectionFirst >= length()) selectionFirst = selectionLast = -1;
        else if (selectionLast > length()) selectionLast = length();
        followCursor = true;
    }
    public int length() { return text().codePointCount(0, text().length()); }
    public int cursor() { return cursor; }
    public void cursor(int value) { alive(); cursor = clamp(value); followCursor = true; tree.changed(false); }
    int clamp(int index) { return Math.max(0, Math.min(index, length())); }
    private int offset(int index) { return text().offsetByCodePoints(0, clamp(index)); }

    /** The code point -show draws in place of every character, or -1. */
    private int mask() { return show.isEmpty() ? -1 : show.codePointAt(0); }
    @Override String paintText() {
        int mask = mask();
        if (mask < 0) return text();
        StringBuilder masked = new StringBuilder();
        for (int i = length(); i > 0; i--) masked.appendCodePoint(mask);
        return masked.toString();
    }
    /** UTF-8 bytes of what is drawn (with -show, the mask's bytes for every character). */
    int paintBytes() {
        int mask = mask();
        return mask < 0 ? textBytes() : length() * new String(Character.toChars(mask)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    /** -justify by name (Python gives a full one; Tk's message for anything else). */
    static int justify(String value) {
        for (int i = 0; i < JUSTIFY.length; i++) if (JUSTIFY[i].equals(value)) return i;
        throw new IllegalArgumentException("bad justification \"" + value + "\": must be left, right, or center");
    }

    public boolean selectionPresent() { return selectionFirst >= 0 && selectionLast > selectionFirst; }
    public int selectionFirst() { if (!selectionPresent()) throw new IllegalArgumentException("no selection"); return selectionFirst; }
    public int selectionLast() { if (!selectionPresent()) throw new IllegalArgumentException("no selection"); return selectionLast; }
    /** The selected text as it is drawn: with -show, masked (Tk copies the mask, never the text). */
    public String selectedText() {
        if (!selectionPresent()) return "";
        if (mask() < 0) return text().substring(offset(selectionFirst), offset(selectionLast));
        StringBuilder masked = new StringBuilder();
        for (int i = selectionFirst; i < selectionLast; i++) masked.appendCodePoint(mask());
        return masked.toString();
    }
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
    int prefixWidth(int index) {
        int mask = mask();
        return mask < 0 ? TkFontMetrics.width(text().substring(0, offset(index))) : clamp(index) * TkFontMetrics.advance(mask);
    }
    private int usable() { return Math.max(1, bounds.width - insetWidth()); }
    int textOriginX() {
        int total = TkFontMetrics.width(paintText()), room = usable();
        if (justify != LEFT && total < room) {
            // It fits (with the caret's pixel after it): -justify places it, as Tk's EntryComputeGeometry.
            scrollX = 0;
            return bounds.x + insetLeft() + (room - 1 - total) / (justify == CENTER ? 2 : 1);
        }
        if (followCursor) {
            int caret = prefixWidth();
            if (caret < scrollX) scrollX = caret;
            if (caret >= scrollX + room) scrollX = caret - room + 1;
        }
        scrollX = Math.max(0, Math.min(scrollX, Math.max(0, total - room + 1)));
        return bounds.x + insetLeft() - scrollX;
    }
    int indexAt(int x, boolean nearest) {
        int start = textOriginX(), at = 0, mask = mask();
        for (int i = 0; i < text().length();) {
            int cp = text().codePointAt(i), advance = TkFontMetrics.advance(mask < 0 ? cp : mask);
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
        if (x < bounds.x + insetLeft()) scroll(-2, false);
        else if (x >= bounds.x + bounds.width - insetRight()) scroll(2, false);
        int at = indexAt(x, true);
        selectionRange(Math.min(anchor, at), Math.max(anchor, at)); cursor(at);
    }
    void scroll(int amount, boolean pages) {
        alive();
        long next = scrollX + (long) amount * (pages ? usable() : TkFontMetrics.CELL_WIDTH);
        scrollX = (int) Math.max(0, Math.min(next, Math.max(0, TkFontMetrics.width(paintText()) - usable() + 1)));
        followCursor = false; tree.changed(false);
    }
    public Object[] xview() {
        bounds(); textOriginX(); int total = TkFontMetrics.width(paintText());
        return total == 0 ? new Object[] {0.0, 1.0} : new Object[] {scrollX / (double) total, Math.min(1.0, (scrollX + usable()) / (double) total)};
    }
    public void xviewTo(double fraction) {
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("finite fraction expected");
        bounds(); scrollX = (int) (Math.max(0, Math.min(1, fraction)) * TkFontMetrics.width(paintText()));
        followCursor = false; textOriginX(); tree.changed(false);
    }
    void restoreScroll(int pixels) { scrollX = pixels; followCursor = false; }
    private int word(int position, boolean right) {
        // Tk's EntryNextWord/PreviousWord: an Entry (not a Spinbox or Combobox) showing a mask has no words.
        if (mask() >= 0 && kind == Kind.ENTRY) return right ? length() : 0;
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

    // ---- a player's input ------------------------------------------------------------------
    // Edits run here. While the program validates keys (-validate key or all, with a
    // -validatecommand), each change first goes to the program, which answers (validated):
    // Tk validates before it changes anything. The player's later input waits behind it, in order.

    /** Player input waiting behind a change the program is validating: int[] {code, character, modifiers}, a String to paste, or CUT. */
    private final java.util.ArrayDeque<Object> waiting = new java.util.ArrayDeque<>();
    private static final Object CUT = new Object();
    static final int MAX_WAITING = 64;
    /** The change the program is validating, or null: {first, last}, the text it inserts (null: it deletes), the text it was proposed against. */
    private int[] held;
    private String heldText, heldBase;
    /** Text to type at the cursor once the held change is settled (it removed a selection being typed over). */
    private String heldThen;
    private int heldSeq, requests;

    /** Whether the program validates the player's changes: they wait for its answer. */
    boolean validatesKeys() { return notify > 0 && (validate.equals("key") || validate.equals("all")); }
    /** Whether the program hears of focus going in or out (-validate focus, focusin, focusout or all). */
    boolean validatesFocus(boolean in) {
        return notify > 0 && (validate.equals("all") || validate.equals("focus") || validate.equals(in ? "focusin" : "focusout"));
    }
    private boolean busy() { return held != null || !waiting.isEmpty(); }
    private void hold(Object input) { if (waiting.size() < MAX_WAITING) waiting.add(input); }

    void key(int code, int character, int modifiers) {
        if (busy()) hold(new int[] {code, character, modifiers});
        else keyNow(code, character, modifiers);
    }
    void paste(String value) {
        alive(); if (!enabled || readonly) return;
        if (busy()) hold(value); else pasteNow(value);
    }
    /** A player's cut (Ctrl+X, Shift+Delete): the selection goes, once TkTree.copySelection has copied it. */
    void cut() {
        alive();
        if (busy()) hold(CUT); else cutNow();
    }
    private void keyNow(int code, int character, int modifiers) {
        // Windows reports AltGr as Ctrl+Alt, and macOS Option sets Alt: both type
        // characters (@, {, ą, € ...). Only Ctrl without Alt is a shortcut modifier.
        boolean shift = (modifiers & 1) != 0, ctrl = (modifiers & 2) != 0 && (modifiers & 4) == 0;
        if (ctrl && code == 30) { selectionFrom(0); selectionRange(0, length()); cursor(length()); return; }
        switch (code) {
            case 203: move(ctrl ? word(cursor, false) : !shift && selectionPresent() ? selectionFirst : cursor - 1, shift); return;
            case 205: move(ctrl ? word(cursor, true) : !shift && selectionPresent() ? selectionLast : cursor + 1, shift); return;
            case 199: move(0, shift); return;
            case 207: move(length(), shift); return;
            case 14:
                if (selectionPresent()) change(selectionFirst, selectionLast, null, null);
                else if (cursor > 0) change(ctrl ? word(cursor, false) : cursor - 1, cursor, null, null);
                return;
            case 211:
                if (shift) return; // Shift-Delete is a server-authoritative cut request.
                if (selectionPresent()) change(selectionFirst, selectionLast, null, null);
                else change(cursor, ctrl ? word(cursor, true) : cursor + 1, null, null);
                return;
            default:
                if (!ctrl && character >= 32 && character <= 0x10FFFF && character != 127 && (character < 0xD800 || character > 0xDFFF))
                    type(new String(Character.toChars(character)), false);
        }
    }
    private void pasteNow(String value) {
        if (enabled && !readonly) type(value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '), true);
    }
    private void cutNow() {
        if (selectionPresent()) change(selectionFirst, selectionLast, null, null);
    }
    /**
     * Typing (tk::EntryInsert) replaces the selection the cursor is in; pasting replaces any
     * selection (Tk's <<Paste>> on Windows). Two changes, each validated on its own as in Tk.
     */
    private void type(String value, boolean paste) {
        boolean replace = selectionPresent() && (paste || selectionFirst <= cursor && cursor <= selectionLast);
        // Nothing of it fits the text budgets (or nothing is printable): the selection stays.
        String accepted = fitting(value, replace ? text().substring(0, offset(selectionFirst)) + text().substring(offset(selectionLast)) : text());
        if (accepted.isEmpty() || replace && !change(selectionFirst, selectionLast, null, accepted)) return;
        insertTyped(accepted);
    }
    private void insertTyped(String value) {
        String accepted = fitting(value, text());
        if (!accepted.isEmpty()) change(cursor, cursor, accepted, null);
    }
    /**
     * The longest start of {@code value} (control characters dropped) that fits both the
     * per-widget and the display's text budget beside {@code kept}. One pass: re-checking the
     * whole candidate per code point was quadratic in the paste and re-encoded every widget's
     * text each time.
     */
    private String fitting(String value, String kept) {
        int bytes = kept.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int room = Math.min(TkFontMetrics.MAX_TEXT_BYTES - bytes, TkDisplayList.MAX_TEXT_BYTES - tree.otherTextBytes(this) - bytes);
        StringBuilder accepted = new StringBuilder();
        for (int i = 0; i < value.length() && room > 0;) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (cp < 32 || cp == 127 || cp >= 0xD800 && cp <= 0xDFFF) continue;
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (size > room) break;
            accepted.appendCodePoint(cp); room -= size;
        }
        return accepted.toString();
    }
    /**
     * A change a player's input makes: delete [first, last), or insert {@code inserted} at first.
     * Made now, or (validating) sent to the program; {@code then} is typed at the cursor once
     * that is settled. Returns false while the program has it.
     */
    private boolean change(int first, int last, String inserted, String then) {
        if (!enabled || readonly || (inserted != null ? inserted.isEmpty() : clamp(last) <= clamp(first))) return true;
        if (!validatesKeys()) { apply(first, last, inserted); return true; }
        String before = text(), after, changed;
        int a = clamp(first), b = inserted != null ? a : clamp(last);
        if (inserted != null) { after = before.substring(0, offset(a)) + inserted + before.substring(offset(a)); changed = inserted; }
        else { after = before.substring(0, offset(a)) + before.substring(offset(b)); changed = before.substring(offset(a), offset(b)); }
        held = new int[] {a, b}; heldText = inserted; heldBase = before; heldThen = then; heldSeq = ++requests;
        // Tk's %d %i %s %P %S; the program adds %v %V %W.
        tree.notice(this, notify, "validate", heldSeq, inserted != null ? 1 : 0, a, before, after, changed);
        return false;
    }
    private void apply(int first, int last, String inserted) {
        if (inserted == null) { delete(first, last); return; }
        insert(first, inserted);
        selectionClear(); selectionFrom(cursor);
    }
    /**
     * The program's answer to validation request {@code seq}: the change is made (if the text is
     * still the one it was proposed against: a -validatecommand that changes the text rejects
     * it, as in Tk), then the input behind it runs.
     */
    void validated(int seq, boolean ok) {
        if (held == null || seq != heldSeq) return;
        int[] range = held; String inserted = heldText, base = heldBase, then = heldThen;
        held = null; heldText = heldBase = heldThen = null;
        if (ok && text().equals(base) && (inserted == null || fitting(inserted, base).equals(inserted))) apply(range[0], range[1], inserted);
        if (then != null) insertTyped(then);
        while (held == null && !waiting.isEmpty()) {
            Object input = waiting.poll();
            if (input instanceof int[]) keyNow(((int[]) input)[0], ((int[]) input)[1], ((int[]) input)[2]);
            else if (input == CUT) cutNow();
            else pasteNow((String) input);
        }
    }
}
