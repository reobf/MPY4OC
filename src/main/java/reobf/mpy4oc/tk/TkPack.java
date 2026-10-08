package reobf.mpy4oc.tk;

import java.util.Objects;

/** Immutable pack options. Distances are canvas pixels, not GUI-scaled pixels. */
public final class TkPack {
    public enum Side { TOP, BOTTOM, LEFT, RIGHT;
        public boolean vertical() { return this == TOP || this == BOTTOM; }
    }
    public enum Fill { NONE, X, Y, BOTH;
        public boolean x() { return this == X || this == BOTH; }
        public boolean y() { return this == Y || this == BOTH; }
    }
    public enum Anchor {
        NW(0, 0), N(1, 0), NE(2, 0), W(0, 1), CENTER(1, 1), E(2, 1), SW(0, 2), S(1, 2), SE(2, 2);
        public final int x, y;
        Anchor(int x, int y) { this.x = x; this.y = y; }
    }

    public static final TkPack DEFAULT = new TkPack(Side.TOP, Fill.NONE, false, 0, 0);
    public final Side side;
    public final Fill fill;
    public final boolean expand;
    public final Anchor anchor;
    public final int left, right, top, bottom, ipadx, ipady;

    public TkPack(Side side, Fill fill, boolean expand, int padx, int pady) {
        this(side, fill, expand, Anchor.CENTER, padx, padx, pady, pady, 0, 0);
    }

    public TkPack(Side side, Fill fill, boolean expand, Anchor anchor,
                  int left, int right, int top, int bottom, int ipadx, int ipady) {
        this.side = Objects.requireNonNull(side); this.fill = Objects.requireNonNull(fill);
        this.anchor = Objects.requireNonNull(anchor); this.expand = expand;
        for (int pad : new int[] {left, right, top, bottom, ipadx, ipady})
            if (pad < 0 || pad > 1024) throw new IllegalArgumentException("padding must be 0..1024");
        this.left = left; this.right = right; this.top = top; this.bottom = bottom;
        this.ipadx = ipadx; this.ipady = ipady;
    }
}
