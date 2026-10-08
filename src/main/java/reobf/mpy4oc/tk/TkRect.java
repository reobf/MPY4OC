package reobf.mpy4oc.tk;

import java.util.Objects;

/** Integer canvas coordinates, with an exclusive right and bottom edge. */
public final class TkRect {
    public static final TkRect EMPTY = new TkRect(0, 0, 0, 0);
    public final int x, y, width, height;

    public TkRect(int x, int y, int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("negative rectangle size");
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    public boolean contains(int px, int py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    public boolean empty() { return width == 0 || height == 0; }

    public TkRect intersect(TkRect other) {
        int left = Math.max(x, other.x), top = Math.max(y, other.y);
        return new TkRect(left, top, Math.max(0, Math.min(x + width, other.x + other.width) - left),
                Math.max(0, Math.min(y + height, other.y + other.height) - top));
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TkRect)) return false;
        TkRect r = (TkRect) other;
        return x == r.x && y == r.y && width == r.width && height == r.height;
    }
    @Override public int hashCode() { return Objects.hash(x, y, width, height); }
    @Override public String toString() { return "(" + x + "," + y + "," + width + "," + height + ")"; }
}
