package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable paint records: the client receives final rectangles and text origins. */
public final class TkDisplayList {
    public static final int MAX_ITEMS = 256, MAX_TEXT_BYTES = 16384;
    public static final int MAX_RECORDS = 4096, MAX_PAINT_BYTES = 65536;
    public static final int PRESSED = 1, FOCUSED = 2, DISABLED = 4;
    public static final TkDisplayList EMPTY = new TkDisplayList(Collections.emptyList());
    public final List<Item> items;

    public static final class Item {
        public final int id, background, foreground, border, state, textX, textY, caretX;
        public final int selectionX, selectionWidth;
        public final int part;
        public long key() { return ((long) id << 32) | part; }
        public final TkWidget.Kind kind;
        public final TkRect bounds, clip;
        public final String text;
        public final TkVector vector;

        public Item(int id, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text) {
            this(id, kind, bounds, clip, background, foreground, border, state, textX, textY, text, -1);
        }
        public Item(int id, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX) {
            this(id, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, 0, 0);
        }
        public Item(int id, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX, int selectionX, int selectionWidth) {
            this(id, 0, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth);
        }
        public Item(int id, int part, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX, int selectionX, int selectionWidth) {
            this(id, part, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, null);
        }
        public Item(int id, int part, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX, int selectionX, int selectionWidth, TkVector vector) {
            if ((vector != null) != (kind == TkWidget.Kind.CANVAS && part > 0)) throw new IllegalArgumentException("invalid Canvas paint part");
            if (vector != null && (border != 0 || caretX != -1 || selectionWidth != 0 || vector.shape != TkVector.Shape.TEXT && !text.isEmpty()))
                throw new IllegalArgumentException("invalid vector paint record");
            this.vector = vector;
            if (part < 0) throw new IllegalArgumentException("invalid paint part");
            this.part = part;
            if (id < 1 || border < 0 || border > 16 || state < 0 || state > 7
                    || Math.abs((long) textX) > 16777216 || Math.abs((long) textY) > 16777216 || caretX < -1 || caretX > 2048)
                throw new IllegalArgumentException("invalid paint record");
            if (bounds.empty() || Math.abs((long) bounds.x) > 16777216 || Math.abs((long) bounds.y) > 16777216
                    || bounds.width > 2097152 || bounds.height > 2097152) throw new IllegalArgumentException("invalid paint bounds");
            checkRect(clip);
            if (selectionX < 0 || selectionWidth < 0 || selectionX + (long) selectionWidth > 1024
                    || selectionWidth > 0 && (kind != TkWidget.Kind.ENTRY && kind != TkWidget.Kind.TEXT || selectionX < clip.x || selectionX + selectionWidth > clip.x + clip.width))
                throw new IllegalArgumentException("invalid selection rectangle");
            if (!clip.equals(clip.intersect(bounds))) throw new IllegalArgumentException("clip outside widget");
            TkWidget.checkColor(background); TkWidget.checkColor(foreground); TkFontMetrics.checkPaintText(text);
            this.id = id; this.kind = Objects.requireNonNull(kind); this.bounds = bounds; this.clip = clip;
            this.background = background; this.foreground = foreground; this.border = border; this.state = state;
            this.textX = textX; this.textY = textY; this.text = text;
            this.caretX = caretX;
            this.selectionX = selectionX; this.selectionWidth = selectionWidth;
        }

        private static void checkRect(TkRect rect) {
            if (rect.empty() || rect.x < 0 || rect.y < 0 || rect.x + (long) rect.width > 1024
                    || rect.y + (long) rect.height > 1024) throw new IllegalArgumentException("invalid paint rectangle");
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Item)) return false;
            Item i = (Item) other;
            return id == i.id && part == i.part && kind == i.kind && bounds.equals(i.bounds) && clip.equals(i.clip)
                    && background == i.background && foreground == i.foreground && border == i.border
                    && state == i.state && textX == i.textX && textY == i.textY && text.equals(i.text) && caretX == i.caretX
                    && selectionX == i.selectionX && selectionWidth == i.selectionWidth && Objects.equals(vector, i.vector);
        }
        @Override public int hashCode() {
            return Objects.hash(id, part, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, vector);
        }
    }

    public TkDisplayList(List<Item> items) {
        if (items.size() > MAX_RECORDS) throw new IllegalArgumentException("too many paint records");
        Set<Long> ids = new HashSet<>();
        int bytes = 0, coords = 0;
        for (Item item : items) {
            if (!ids.add(item.key())) throw new IllegalArgumentException("duplicate paint key");
            bytes += item.text.getBytes(StandardCharsets.UTF_8).length;
            if (item.vector != null) coords += item.vector.count();
        }
        if (coords > TkVector.MAX_TOTAL_COORDS) throw new IllegalArgumentException("display coordinate budget exceeded");
        if (bytes > MAX_PAINT_BYTES) throw new IllegalArgumentException("display text budget exceeded");
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    @Override public boolean equals(Object other) { return other instanceof TkDisplayList && items.equals(((TkDisplayList) other).items); }
    @Override public int hashCode() { return items.hashCode(); }
}
