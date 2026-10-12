package reobf.mpy4oc.tk;

/** Shared bounds and level-of-detail rules for read-only world displays. */
public final class TkSurfacePolicy {
    public static final int MAX_WATCHES = 32, LEASE_TICKS = 100, HEARTBEAT_TICKS = 40;
    public static final int MAX_TEXTURES = 16, UPDATES_PER_FRAME = 2;
    public static int interval(double distanceSquared) { return distanceSquared <= 64 ? 2 : distanceSquared <= 256 ? 4 : 10; }
    public static int resolution(double distanceSquared) { return distanceSquared <= 64 ? 512 : distanceSquared <= 256 ? 256 : 128; }
    /** Largest surface texture side, for multiblock groups. */
    public static final int MAX_TEXTURE = 1024;
    /** Texture side for a group whose longer side spans {@code blocks} blocks. */
    public static int resolution(double distanceSquared, int blocks) { return Math.min(MAX_TEXTURE, resolution(distanceSquared) * Math.max(1, blocks)); }
    public static boolean within(double distanceSquared, int range) { return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= range * (double) range; }
    public static int face(int metadata) { return metadata >= 2 && metadata <= 5 ? metadata : 2; }
    public static boolean inFront(int face, double dx, double dz) {
        switch (face(face)) { case 2: return dz < -.5; case 3: return dz > .5; case 4: return dx < -.5; default: return dx > .5; }
    }
    /** u/v are the visible screen's left-to-right/top-to-bottom block coordinates. */
    public static double[] point(int face, double u, double v) {
        switch (face(face)) {
            case 2: return new double[] {1-u, 1-v, -.002};
            case 3: return new double[] {u, 1-v, 1.002};
            case 4: return new double[] {-.002, 1-v, u};
            default: return new double[] {1.002, 1-v, 1-u};
        }
    }
    /**
     * Inverse of {@link #point} on the front face: block-local hit coordinates (0..1) to
     * {u right, v down} on the visible screen.
     */
    public static double[] faceUv(int face, double x, double y, double z) {
        switch (face(face)) {
            case 2: return new double[] {1-x, 1-y};
            case 3: return new double[] {x, 1-y};
            case 4: return new double[] {z, 1-y};
            default: return new double[] {1-z, 1-y};
        }
    }
    private TkSurfacePolicy() {}
}
