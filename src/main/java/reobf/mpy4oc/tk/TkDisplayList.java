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
    /** Widget text on one display (TkTree.checkTextBytes). */
    public static final int MAX_TEXT_BYTES = 16384;
    /** What one display draws: enough records for TkTree.MAX_WIDGETS widgets with their parts. A program's
     *  change that would leave something undrawn is an error (TkTree.drawsEverything). */
    public static final int MAX_RECORDS = 8192, MAX_PAINT_BYTES = 65536;
    public static final int PRESSED = 1, FOCUSED = 2, DISABLED = 4;
    /** Parts from this one up (of a record with no vector) belong to the popup layer: a Combobox's open list (TkPopup). */
    public static final int POPUP_PART = 1 << 20;
    public static final TkDisplayList EMPTY = new TkDisplayList(Collections.emptyList());
    public final List<Item> items;

    public static final class Item {
        public final int id, background, foreground, border, state, textX, textY, caretX;
        public final int selectionX, selectionWidth;
        public final int part;
        /** How the border is drawn: 0 the part's own look, else a TkRelief code (part 0 carries its widget's -relief). */
        public final int relief;
        /** A PhotoImage drawn with its top left at (textX, textY), or 0. */
        public final int image;
        public long key() { return ((long) id << 32) | part; }
        public final TkWidget.Kind kind;
        public final TkRect bounds, clip;
        public final String text;
        public final TkVector vector;
        public final TkIconDescriptor icon;
        public int payloadBytes() { return text.getBytes(StandardCharsets.UTF_8).length + (icon == null ? 0 : icon.bytes()); }

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
            this(id, part, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, vector, 0);
        }
        public Item(int id, int part, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX, int selectionX, int selectionWidth, TkVector vector, int image) {
            this(id, part, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, vector, image, 0);
        }
        public Item(int id, int part, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX, int selectionX, int selectionWidth, TkVector vector, int image,
                    int relief) {
            this(id, part, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, vector, image, relief, null);
        }
        public Item(int id, int part, TkWidget.Kind kind, TkRect bounds, TkRect clip, int background, int foreground,
                    int border, int state, int textX, int textY, String text, int caretX, int selectionX, int selectionWidth, TkVector vector, int image,
                    int relief, TkIconDescriptor icon) {
            boolean iconKind = kind == TkWidget.Kind.ITEMICON || kind == TkWidget.Kind.FLUIDICON;
            if (iconKind != (icon != null) || iconKind && (icon.fluid != (kind == TkWidget.Kind.FLUIDICON)
                    || part != 1 || vector != null || image != 0 || border != 0 || relief != 0 || !text.isEmpty()
                    || caretX != -1 || selectionX != 0 || selectionWidth != 0 || bounds.width != bounds.height || bounds.width > 32768))
                throw new IllegalArgumentException("invalid icon paint record");
            this.icon = icon;
            if (relief < 0 || relief > TkRelief.TAB || relief != 0 && vector != null) throw new IllegalArgumentException("invalid paint relief");
            this.relief = relief;
            if ((vector != null) != (kind == TkWidget.Kind.CANVAS && part > 0)) throw new IllegalArgumentException("invalid Canvas paint part");
            boolean imageShape = vector != null && vector.shape == TkVector.Shape.IMAGE;
            // Compound widgets (Treeview rows) also paint PhotoImages as label parts.
            boolean imageLabel = vector == null && (kind == TkWidget.Kind.LABEL || part == 0 && kind == TkWidget.Kind.BUTTON);
            if (image < 0 || imageShape && image == 0 || image != 0 && !(imageShape || imageLabel) || image != 0 && !text.isEmpty())
                throw new IllegalArgumentException("invalid image paint record");
            this.image = image;
            if (vector != null && (border != 0 || caretX != -1 || selectionWidth != 0 || vector.shape != TkVector.Shape.TEXT && !text.isEmpty()))
                throw new IllegalArgumentException("invalid vector paint record");
            this.vector = vector;
            if (part < 0) throw new IllegalArgumentException("invalid paint part");
            this.part = part;
            if (id < 1 || border < 0 || border > 16 || state < 0 || state > 7
                    || Math.abs((long) textX) > 16777216 || Math.abs((long) textY) > 16777216 || caretX < -1 || caretX > TileTkDisplay.MAX_SIZE)
                throw new IllegalArgumentException("invalid paint record");
            if (bounds.empty() || Math.abs((long) bounds.x) > 16777216 || Math.abs((long) bounds.y) > 16777216
                    || bounds.width > 2097152 || bounds.height > 2097152) throw new IllegalArgumentException("invalid paint bounds");
            checkRect(clip);
            if (selectionX < 0 || selectionWidth < 0 || selectionX + (long) selectionWidth > TileTkDisplay.MAX_SIZE
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
            if (rect.empty() || rect.x < 0 || rect.y < 0 || rect.x + (long) rect.width > TileTkDisplay.MAX_SIZE
                    || rect.y + (long) rect.height > TileTkDisplay.MAX_SIZE) throw new IllegalArgumentException("invalid paint rectangle");
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Item)) return false;
            Item i = (Item) other;
            return id == i.id && part == i.part && kind == i.kind && bounds.equals(i.bounds) && clip.equals(i.clip)
                    && background == i.background && foreground == i.foreground && border == i.border
                    && state == i.state && textX == i.textX && textY == i.textY && text.equals(i.text) && caretX == i.caretX
                    && selectionX == i.selectionX && selectionWidth == i.selectionWidth && Objects.equals(vector, i.vector) && image == i.image
                    && relief == i.relief && Objects.equals(icon, i.icon);
        }
        @Override public int hashCode() {
            return Objects.hash(id, part, kind, bounds, clip, background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, vector, image, relief, icon);
        }
    }

    /**
     * Drop the paint records that do not fit the list budgets, in paint order, so that
     * {@link #TkDisplayList(List)} accepts the rest. Returns whether anything was dropped.
     * The budgets are only checked when a frame is built, long after the Python call or
     * player input that grew the tree has returned, so a frame that is too big has to
     * degrade here: throwing would escape into the tile entity tick.
     */
    static boolean trimToBudget(List<Item> items) {
        int count = 0, bytes = 0, coords = 0;
        boolean dropped = false;
        for (java.util.Iterator<Item> it = items.iterator(); it.hasNext();) {
            Item item = it.next();
            int b = item.payloadBytes(), c = item.vector == null ? 0 : item.vector.count();
            if (count + 1 > MAX_RECORDS || bytes + b > MAX_PAINT_BYTES || coords + c > TkVector.MAX_TOTAL_COORDS) {
                it.remove(); dropped = true; continue;
            }
            count++; bytes += b; coords += c;
        }
        return dropped;
    }

    /**
     * Like {@link #trimToBudget(List)}, but keeps the topmost layers: {@code layerStarts}
     * are the indices where each window's records begin (bottom to top); the records before
     * the first are the desktop's. The records that fit, top layer first, keep paint order.
     */
    static boolean trimToBudget(List<Item> items, List<Integer> layerStarts) {
        if (layerStarts.isEmpty()) return trimToBudget(items);
        int layers = layerStarts.size() + 1;
        boolean[] keep = new boolean[items.size()];
        int count = 0, bytes = 0, coords = 0;
        boolean dropped = false;
        for (int layer = layers - 1; layer >= 0; layer--) {
            int start = layer == 0 ? 0 : layerStarts.get(layer - 1), end = layer == layers - 1 ? items.size() : layerStarts.get(layer);
            for (int i = start; i < end; i++) {
                Item item = items.get(i);
                int b = item.payloadBytes(), c = item.vector == null ? 0 : item.vector.count();
                if (count + 1 > MAX_RECORDS || bytes + b > MAX_PAINT_BYTES || coords + c > TkVector.MAX_TOTAL_COORDS) { dropped = true; continue; }
                keep[i] = true; count++; bytes += b; coords += c;
            }
        }
        if (!dropped) return false;
        List<Item> kept = new ArrayList<>(count);
        for (int i = 0; i < keep.length; i++) if (keep[i]) kept.add(items.get(i));
        items.clear(); items.addAll(kept);
        return true;
    }

    public TkDisplayList(List<Item> items) {
        if (items.size() > MAX_RECORDS) throw new IllegalArgumentException("too many paint records");
        Set<Long> ids = new HashSet<>();
        int bytes = 0, coords = 0;
        for (Item item : items) {
            if (!ids.add(item.key())) throw new IllegalArgumentException("duplicate paint key");
            bytes += item.payloadBytes();
            if (item.vector != null) coords += item.vector.count();
        }
        if (coords > TkVector.MAX_TOTAL_COORDS) throw new IllegalArgumentException("display coordinate budget exceeded");
        if (bytes > MAX_PAINT_BYTES) throw new IllegalArgumentException("display text/icon budget exceeded");
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    @Override public boolean equals(Object other) { return other instanceof TkDisplayList && items.equals(((TkDisplayList) other).items); }
    @Override public int hashCode() { return items.hashCode(); }
}
