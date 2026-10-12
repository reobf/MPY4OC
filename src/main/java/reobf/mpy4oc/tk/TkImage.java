package reobf.mpy4oc.tk;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A PhotoImage owned by one display: ARGB pixels plus a version that changes with every
 * edit, so clients and saves can tell which pixels they already have. Server thread only.
 */
public final class TkImage {
    /** Largest side of one image, images per display and their total pixels. */
    public static final int MAX_SIDE = 1024, MAX_IMAGES = 64, MAX_PIXELS = 524_288;
    /** Largest encoded file (PNG, JPEG, GIF, BMP) accepted for decoding. */
    public static final int MAX_FILE_BYTES = 1 << 20;
    /** Images up to this many pixels are packed on the spot; larger ones off the server thread. */
    static final int INLINE_PACK_PIXELS = 16384;
    /** A large image that keeps changing is re-packed at most this often. */
    static final long REPACK_NANOS = 200_000_000L;

    private static final ExecutorService PACKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MPY4OC Tk image packer");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /** One version's pixels in the transfer and storage format (see {@link TkImageCodec#pack}). */
    public static final class Packed {
        public final long version;
        public final int width, height;
        public final byte[] bytes;
        Packed(long version, int width, int height, byte[] bytes) { this.version = version; this.width = width; this.height = height; this.bytes = bytes; }
    }

    public final int id;
    int width, height;
    int[] argb;
    long version;
    /** Version last written to storage; saving skips unchanged images. */
    long savedVersion = -1;
    /** The program that made it (see TkTree.programOf); only it may use or change it. */
    int program;
    private Packed packed;
    private Future<Packed> packing;
    private long packingVersion = -1, packStarted;

    TkImage(int id, int width, int height, int[] argb, long version) {
        check(width, height);
        if (argb.length != width * height) throw new IllegalArgumentException("pixel count does not match image size");
        this.id = id; this.width = width; this.height = height; this.argb = argb; this.version = version;
    }

    public int width() { return width; }
    public int height() { return height; }
    public long version() { return version; }

    static void check(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE)
            throw new IllegalArgumentException("image size must be 1..1024 on each side");
    }

    /**
     * The newest packed pixels there are, which may be an older version than the image while
     * a pack runs in the background (started here when the current version has none), or
     * null before the first one is done. Transfers send whatever this returns, so an image
     * edited every tick still reaches clients, a little behind, and the server thread never
     * deflates a large image.
     */
    public Packed snapshot() {
        collect();
        if (packed != null && packed.version == version) return packed;
        if (width * height <= INLINE_PACK_PIXELS) return packed = new Packed(version, width, height, TkImageCodec.pack(width, height, argb));
        long now = System.nanoTime();
        if (packing == null && (packed == null || now - packStarted >= REPACK_NANOS)) {
            int[] copy = argb.clone();
            long v = version; int w = width, h = height;
            packing = PACKER.submit(() -> new Packed(v, w, h, TkImageCodec.pack(w, h, copy)));
            packingVersion = v; packStarted = now;
        }
        return packed;
    }

    /** The current version's packed pixels, now (saving): waits for a pack of this version already under way. */
    public byte[] packed() {
        collect();
        if (packed != null && packed.version == version) return packed.bytes;
        if (packing != null && packingVersion == version) {
            try { packed = packing.get(); packing = null; return packed.bytes; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ExecutionException failed) { packing = null; }
        }
        packed = new Packed(version, width, height, TkImageCodec.pack(width, height, argb));
        return packed.bytes;
    }

    private void collect() {
        if (packing == null || !packing.isDone()) return;
        try { Packed done = packing.get(); if (packed == null || done.version > packed.version) packed = done; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException failed) { reobf.mpy4oc.main.MyMod.LOG.warn("Could not pack Tk image " + id, failed.getCause()); }
        packing = null;
    }

    int pixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) throw new IllegalArgumentException("pixel outside the image");
        return argb[y * width + x];
    }

    /**
     * Tk's put: {@code rows} of RGB colors (-1 = transparent) at (x, y); or, with a target
     * rectangle, the block tiled over it.
     */
    void put(int[][] rows, int x, int y, int x2, int y2) {
        if (rows.length == 0 || rows[0].length == 0) throw new IllegalArgumentException("put needs at least one pixel");
        int blockWidth = rows[0].length;
        for (int[] row : rows) if (row.length != blockWidth) throw new IllegalArgumentException("put rows must have the same length");
        boolean tile = x2 >= 0;
        int right = tile ? x2 : x + blockWidth, bottom = tile ? y2 : y + rows.length;
        if (x < 0 || y < 0 || right > width || bottom > height || right <= x || bottom <= y)
            throw new IllegalArgumentException("put region outside the image");
        for (int py = y; py < bottom; py++) {
            int[] row = rows[(py - y) % rows.length];
            for (int px = x; px < right; px++) {
                int c = row[(px - x) % blockWidth];
                argb[py * width + px] = c < 0 ? 0 : 0xFF000000 | c;
            }
        }
        version++;
    }

    void blank() { Arrays.fill(argb, 0); version++; }

    void transparency(int x, int y, boolean transparent) {
        int i = y * width + x;
        pixel(x, y);
        argb[i] = transparent ? argb[i] & 0x00FFFFFF : argb[i] | 0xFF000000;
        version++;
    }
}
