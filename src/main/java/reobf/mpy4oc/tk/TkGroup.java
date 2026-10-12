package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of a multiblock Tk display: a rectangle of display blocks sharing one facing and
 * one plane, run by a single master block. Pure logic, shared by the server (merge, split,
 * routing) and the client (surface rendering, wrench overlay).
 *
 * <p>Coordinates are block units in the master's frame as seen by a viewer in front of the
 * screen: {@code u} grows to the viewer's right, {@code v} grows downwards; the master sits
 * at (0, 0). A group is the rectangle {@code [left, left+width) x [top, top+height)}, which
 * must contain (0, 0). This matches {@link TkSurfacePolicy#point}, whose u/v are the same
 * axes inside one block.
 */
public final class TkGroup {
    /** Largest group per axis. Each block provides 320 pixels, so the canvas cap is 2560. */
    public static final int MAX_BLOCKS = 8;
    /** Resolution each block adds to the cap, per axis. */
    public static final int PIXELS_PER_BLOCK = 320;

    public final int left, top, width, height;

    public static final TkGroup SINGLE = new TkGroup(0, 0, 1, 1);

    public TkGroup(int left, int top, int width, int height) {
        if (width < 1 || height < 1 || width > MAX_BLOCKS || height > MAX_BLOCKS
                || left > 0 || top > 0 || left + width <= 0 || top + height <= 0)
            throw new IllegalArgumentException("invalid display group");
        this.left = left; this.top = top; this.width = width; this.height = height;
    }

    public boolean single() { return width == 1 && height == 1; }
    public boolean contains(int u, int v) { return u >= left && u < left + width && v >= top && v < top + height; }
    public int maxWidth() { return PIXELS_PER_BLOCK * width; }
    public int maxHeight() { return PIXELS_PER_BLOCK * height; }

    /** Edge of a block face, as seen by a viewer in front of the screen. */
    public enum Edge {
        LEFT(-1, 0), RIGHT(1, 0), TOP(0, -1), BOTTOM(0, 1);
        public final int du, dv;
        Edge(int du, int dv) { this.du = du; this.dv = dv; }
    }

    /** Fraction of a face, from each edge, that counts as aiming at that edge. */
    public static final double EDGE_ZONE = 0.3;

    /**
     * The edge a wrench click at face-local (s right, t down; both 0..1) aims at, or null for
     * the centre of the face.
     */
    public static Edge edgeAt(double s, double t) {
        double left = s, right = 1 - s, top = t, bottom = 1 - t;
        double nearest = Math.min(Math.min(left, right), Math.min(top, bottom));
        if (nearest >= EDGE_ZONE) return null;
        if (nearest == left) return Edge.LEFT;
        if (nearest == right) return Edge.RIGHT;
        return nearest == top ? Edge.TOP : Edge.BOTTOM;
    }

    /** Unit vector of +u (the viewer's right) for a front face (block metadata 2..5). */
    public static int[] right(int face) {
        switch (TkSurfacePolicy.face(face)) {
            case 2: return new int[] {-1, 0, 0};
            case 3: return new int[] {1, 0, 0};
            case 4: return new int[] {0, 0, 1};
            default: return new int[] {0, 0, -1};
        }
    }

    /** Unit vector the front face points to (towards the viewer). */
    public static int[] normal(int face) {
        switch (TkSurfacePolicy.face(face)) {
            case 2: return new int[] {0, 0, -1};
            case 3: return new int[] {0, 0, 1};
            case 4: return new int[] {-1, 0, 0};
            default: return new int[] {1, 0, 0};
        }
    }

    /** Block position of (u, v) in the frame of a master at {@code master}. */
    public static int[] position(int[] master, int face, int u, int v) {
        int[] r = right(face);
        return new int[] {master[0] + r[0] * u, master[1] - v, master[2] + r[2] * u};
    }

    /**
     * (u, v) of {@code pos} in the frame of a master at {@code master}, or null when the
     * block is not in the master's screen plane.
     */
    public static int[] uv(int[] master, int face, int[] pos) {
        int[] r = right(face), n = normal(face);
        int dx = pos[0] - master[0], dy = pos[1] - master[1], dz = pos[2] - master[2];
        if (dx * n[0] + dz * n[2] != 0) return null;
        return new int[] {dx * r[0] + dz * r[2], -dy};
    }

    /** Every block position of this group, for a master at {@code master}. */
    public List<int[]> members(int[] master, int face) {
        List<int[]> out = new ArrayList<>(width * height);
        for (int v = top; v < top + height; v++)
            for (int u = left; u < left + width; u++) out.add(position(master, face, u, v));
        return out;
    }

    /**
     * The group formed by merging {@code other} (whose master sits at {@code otherMaster} in
     * this group's frame) onto this group across {@code edge}; null when the result would not
     * be a rectangle (rows or columns differ or are offset), the groups do not touch along that
     * edge, or it would exceed {@link #MAX_BLOCKS}.
     */
    public TkGroup merge(TkGroup other, int[] otherMaster, Edge edge) {
        int oLeft = otherMaster[0] + other.left, oTop = otherMaster[1] + other.top;
        boolean fits;
        switch (edge) {
            case RIGHT: fits = oLeft == left + width && oTop == top && other.height == height; break;
            case LEFT: fits = oLeft + other.width == left && oTop == top && other.height == height; break;
            case BOTTOM: fits = oTop == top + height && oLeft == left && other.width == width; break;
            default: fits = oTop + other.height == top && oLeft == left && other.width == width;
        }
        if (!fits) return null;
        int l = Math.min(left, oLeft), t = Math.min(top, oTop);
        int w = Math.max(left + width, oLeft + other.width) - l, h = Math.max(top + height, oTop + other.height) - t;
        return w > MAX_BLOCKS || h > MAX_BLOCKS ? null : new TkGroup(l, t, w, h);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof TkGroup)) return false;
        TkGroup g = (TkGroup) o;
        return left == g.left && top == g.top && width == g.width && height == g.height;
    }
    @Override public int hashCode() { return ((left * 31 + top) * 31 + width) * 31 + height; }
    @Override public String toString() { return width + "x" + height + "@" + left + "," + top; }
}
