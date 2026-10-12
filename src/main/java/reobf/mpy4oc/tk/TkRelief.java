package reobf.mpy4oc.tk;

/**
 * How a border is drawn (Tk's -relief). The server puts a code in each paint record and the client
 * draws it with these colours: Tk's 3D border on X11, whose light and dark shades come from the
 * background (tkUnix3d.c, TkpGetShadows), checked against Tk 8.6.
 */
public final class TkRelief {
    private TkRelief() {}
    /** Record codes. LEGACY: the part's own look (raised; sunken for an Entry/Text or a pressed part, in fixed greys). */
    public static final int LEGACY = 0, FLAT = 1, RAISED = 2, SUNKEN = 3, GROOVE = 4, RIDGE = 5, SOLID = 6;
    /** A notebook tab: raised, open at the bottom. */
    public static final int TAB = 7;
    private static final int MAX = 65535;

    /** Tk's dark shade of a background colour (0xRRGGBB). */
    public static int dark(int rgb) {
        int r = channel(rgb, 16), g = channel(rgb, 8), b = channel(rgb, 0);
        boolean veryDark = r * 0.5 * r + g * 1.0 * g + b * 0.28 * b < MAX * 0.05 * MAX;
        return veryDark ? color((MAX + 3 * r) / 4, (MAX + 3 * g) / 4, (MAX + 3 * b) / 4) : color(60 * r / 100, 60 * g / 100, 60 * b / 100);
    }

    /** Tk's light shade of a background colour. */
    public static int light(int rgb) {
        int r = channel(rgb, 16), g = channel(rgb, 8), b = channel(rgb, 0);
        if (g > MAX * 0.95) return color(90 * r / 100, 90 * g / 100, 90 * b / 100);
        return color(lighter(r), lighter(g), lighter(b));
    }

    /**
     * The colour of each 1 px ring of a border, outermost first: [i][0] for the top and left sides,
     * [i][1] for the bottom and right. Empty when there is nothing to draw (flat, no border). A
     * groove is a sunken outer half and a raised inner half, a ridge the reverse; solid is black.
     */
    public static int[][] rings(int relief, int border, int background) {
        if (border <= 0 || relief == FLAT) return new int[0][];
        int light = light(background), dark = dark(background), half = border / 2;
        int[][] rings = new int[border][];
        for (int i = 0; i < border; i++) {
            boolean outer = i < half;
            switch (relief) {
                case SOLID: rings[i] = new int[] {0, 0}; break;
                case SUNKEN: rings[i] = new int[] {dark, light}; break;
                case GROOVE: rings[i] = outer ? new int[] {dark, light} : new int[] {light, dark}; break;
                case RIDGE: rings[i] = outer ? new int[] {light, dark} : new int[] {dark, light}; break;
                default: rings[i] = new int[] {light, dark};   // RAISED, TAB
            }
        }
        return rings;
    }

    private static int lighter(int c) { return Math.max(Math.min(14 * c / 10, MAX), (MAX + c) / 2); }
    private static int channel(int rgb, int shift) { return ((rgb >> shift) & 0xFF) * 257; }
    private static int color(int r, int g, int b) { return (r >> 8) << 16 | (g >> 8) << 8 | b >> 8; }
}
