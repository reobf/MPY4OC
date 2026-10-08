package reobf.mpy4oc.tk;

import java.util.Arrays;
import java.util.Objects;

/** Immutable, bounded Canvas geometry shared by the model and the wire protocol. */
public final class TkVector {
    public enum Shape { LINE, RECTANGLE, OVAL, POLYGON, TEXT }
    public static final int MAX_COORDS = 512, MAX_TOTAL_COORDS = 32768;
    public final Shape shape;
    public final int fill, outline;
    public final double width;
    private final double[] points;

    public TkVector(Shape shape, double[] points, int fill, int outline, double width) {
        this.shape = Objects.requireNonNull(shape);
        int n = points.length;
        if (n > MAX_COORDS || (n & 1) != 0 || (shape == Shape.TEXT ? n != 2
                : shape == Shape.LINE ? n < 4 : shape == Shape.POLYGON ? n < 6 : n != 4))
            throw new IllegalArgumentException("invalid Canvas coordinates");
        for (double p : points) if (!Double.isFinite(p) || Math.abs(p) > 32768)
            throw new IllegalArgumentException("Canvas coordinates must be finite and within -32768..32768");
        if (fill < -1 || fill > 0xffffff || outline < -1 || outline > 0xffffff
                || !Double.isFinite(width) || width < 0 || width > 64)
            throw new IllegalArgumentException("invalid Canvas style");
        this.points = points.clone(); this.fill = fill; this.outline = outline; this.width = width;
        if (shape == Shape.RECTANGLE || shape == Shape.OVAL) {
            this.points[0] = Math.min(points[0], points[2]); this.points[2] = Math.max(points[0], points[2]);
            this.points[1] = Math.min(points[1], points[3]); this.points[3] = Math.max(points[1], points[3]);
        }
    }
    public int count() { return points.length; }
    public double at(int i) { return points[i]; }
    public double[] coords() { return points.clone(); }
    public boolean hit(double x, double y, double tolerance, TkRect textBounds) {
        if (shape == Shape.TEXT) return !textBounds.empty() && x >= textBounds.x - tolerance
                && y >= textBounds.y - tolerance && x <= textBounds.x + textBounds.width + tolerance
                && y <= textBounds.y + textBounds.height + tolerance;
        double stroke = Math.max(1, width) / 2 + tolerance;
        if (shape == Shape.LINE) return fill >= 0 && nearEdges(x, y, stroke, false);
        if (shape == Shape.POLYGON) {
            boolean inside = false;
            for (int i = 0, j = points.length - 2; i < points.length; j = i, i += 2)
                if ((points[i+1] > y) != (points[j+1] > y)
                        && x < (points[j] - points[i]) * (y - points[i+1]) / (points[j+1] - points[i+1]) + points[i]) inside = !inside;
            return inside || nearEdges(x, y, outline >= 0 ? stroke : tolerance, true);
        }
        double x0 = Math.min(points[0], points[2]), x1 = Math.max(points[0], points[2]);
        double y0 = Math.min(points[1], points[3]), y1 = Math.max(points[1], points[3]);
        if (shape == Shape.RECTANGLE) {
            double pad = outline >= 0 ? stroke : tolerance;
            boolean outer = x >= x0-pad && x <= x1+pad && y >= y0-pad && y <= y1+pad;
            return outer && (fill >= 0 || outline < 0 || !(x > x0+stroke && x < x1-stroke && y > y0+stroke && y < y1-stroke));
        }
        double rx = (x1-x0)/2, ry = (y1-y0)/2, dx = x-(x0+x1)/2, dy = y-(y0+y1)/2;
        double pad = outline >= 0 ? stroke : tolerance;
        boolean outer = ellipse(dx, dy, rx+pad, ry+pad);
        return outer && (fill >= 0 || outline < 0 || !ellipse(dx, dy, rx-stroke, ry-stroke));
    }
    private boolean nearEdges(double x, double y, double radius, boolean closed) {
        int edges = points.length/2 - (closed ? 0 : 1);
        for (int i = 0; i < edges; i++) {
            int a = i*2, b = (a+2) % points.length;
            double dx = points[b]-points[a], dy = points[b+1]-points[a+1], norm = dx*dx+dy*dy;
            double t = norm == 0 ? 0 : Math.max(0, Math.min(1, ((x-points[a])*dx+(y-points[a+1])*dy)/norm));
            double ex = x-points[a]-t*dx, ey = y-points[a+1]-t*dy;
            if (ex*ex+ey*ey <= radius*radius) return true;
        }
        return false;
    }
    private static boolean ellipse(double x, double y, double rx, double ry) {
        return rx > 0 && ry > 0 && x*x/(rx*rx)+y*y/(ry*ry) <= 1;
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof TkVector)) return false;
        TkVector v = (TkVector) o;
        return shape == v.shape && fill == v.fill && outline == v.outline && width == v.width && Arrays.equals(points, v.points);
    }
    @Override public int hashCode() { return Objects.hash(shape, fill, outline, width, Arrays.hashCode(points)); }
}
