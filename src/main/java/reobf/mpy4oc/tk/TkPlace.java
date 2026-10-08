package reobf.mpy4oc.tk;

/** A null dimension uses the requested size; absolute and relative dimensions add.
 * Placement rounding follows tkPlace.c:
 * Copyright (c) 1992-1994 The Regents of the University of California.
 * Copyright (c) 1994-1997 Sun Microsystems, Inc.
 * See docs/licenses/tk.txt for usage, redistribution and warranty terms.
 */
public final class TkPlace {
    public final int x, y;
    public final Integer width, height;
    public final double relx, rely;
    public final Double relwidth, relheight;
    public final TkPack.Anchor anchor;
    public final String bordermode;
    public static final TkPlace DEFAULT = new TkPlace(0, 0, null, null, 0, 0, null, null, TkPack.Anchor.NW, "inside");
    public TkPlace(int x, int y, Integer width, Integer height, double relx, double rely,
                   Double relwidth, Double relheight, TkPack.Anchor anchor, String bordermode) {
        this.x = pixel(x); this.y = pixel(y);
        this.width = width == null ? null : pixel(width); this.height = height == null ? null : pixel(height);
        this.relx = fraction(relx); this.rely = fraction(rely);
        this.relwidth = relwidth == null ? null : fraction(relwidth);
        this.relheight = relheight == null ? null : fraction(relheight);
        this.anchor = java.util.Objects.requireNonNull(anchor);
        if (!java.util.Arrays.asList("inside", "outside", "ignore").contains(bordermode))
            throw new IllegalArgumentException("invalid place bordermode");
        this.bordermode = bordermode;
    }
    private static int pixel(int n) { return TkOptions.integer(n, -8192, 8192); }
    private static double fraction(double n) {
        if (!Double.isFinite(n) || Math.abs(n) > 16) throw new IllegalArgumentException("place fraction must be finite and within -16..16");
        return n;
    }
    static int round(double n) { return (int) (n + (n > 0 ? .5 : -.5)); }
    static void layout(Frame frame) {
        for (TkWidget child : frame.children) if (child.place != null) {
            TkPlace p = child.place;
            int bx = p.bordermode.equals("inside") ? frame.border + frame.padx : 0;
            int by = p.bordermode.equals("inside") ? frame.border + frame.pady : 0;
            int fw = frame.bounds.width - 2 * bx, fh = frame.bounds.height - 2 * by;
            double x1 = p.x + bx + p.relx * fw, y1 = p.y + by + p.rely * fh;
            int x = round(x1), y = round(y1);
            int w = p.width == null && p.relwidth == null ? child.requestedWidth :
                    (p.width == null ? 0 : p.width) + (p.relwidth == null ? 0 : round(x1 + p.relwidth * fw) - x);
            int h = p.height == null && p.relheight == null ? child.requestedHeight :
                    (p.height == null ? 0 : p.height) + (p.relheight == null ? 0 : round(y1 + p.relheight * fh) - y);
            x -= w * p.anchor.x / 2; y -= h * p.anchor.y / 2;
            // Bound recursive relative growth on the server, independently of the viewport.
            w = Math.max(1, Math.min(w, 32768)); h = Math.max(1, Math.min(h, 32768));
            TkPackLayout.place(child, new TkRect(frame.bounds.x + x, frame.bounds.y + y, w, h), frame.clip);
        }
    }
}
