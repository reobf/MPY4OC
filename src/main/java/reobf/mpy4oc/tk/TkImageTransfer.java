package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which image pixels one client (a GUI session or a surface lease) still needs, sent in
 * {@link #CHUNK}-byte pieces in order. Only images in the scene the client already has are
 * sent (so it can check each piece against its table), the newest packed version of each.
 * An image in flight is finished even if it changed meanwhile: restarting on every edit
 * would starve an image that changes faster than it can be sent. Server thread only.
 */
public final class TkImageTransfer {
    public static final int CHUNK = 32768;

    public static final class Chunk {
        public final int image, width, height, total, offset;
        public final long version;
        public final byte[] bytes;
        Chunk(int image, long version, int width, int height, int total, int offset, byte[] bytes) {
            this.image = image; this.version = version; this.width = width; this.height = height;
            this.total = total; this.offset = offset; this.bytes = bytes;
        }
    }

    /** What the client holds, by image id: which image (ids restart in a replaced tree) and version. */
    private static final class Held {
        final TkImage source; final long version;
        Held(TkImage source, long version) { this.source = source; this.version = version; }
    }

    private final Map<Integer, Held> held = new HashMap<>();
    private TkImage source;
    private TkImage.Packed data;
    private int offset, last;

    /** The client lost track (resync): send everything again. */
    public void reset() { held.clear(); source = null; data = null; }

    /**
     * The next piece towards the images of {@code scene} (the scene this client was last
     * sent, or null before it has one), or null when there is nothing to send now.
     */
    public Chunk next(TkScene scene, TkTree tree) {
        if (scene == null) return null;
        held.entrySet().removeIf(e -> !scene.images.containsKey(e.getKey()) || tree.image(e.getKey()) != e.getValue().source);
        if (data != null && (!scene.images.containsKey(source.id) || tree.image(source.id) != source)) data = null;
        if (data == null) {
            // Round robin from the image after the last one sent, so one busy image cannot starve the rest.
            List<Integer> ids = new ArrayList<>(scene.images.keySet());
            int start = 0;
            for (int i = 0; i < ids.size(); i++) if (ids.get(i) > last) { start = i; break; }
            for (int n = 0; n < ids.size(); n++) {
                TkImage image = tree.image(ids.get((start + n) % ids.size()));
                TkScene.ImageRef ref = image == null ? null : scene.images.get(image.id);
                if (ref == null || ref.width != image.width || ref.height != image.height) continue;
                Held h = held.get(image.id);
                if (h != null && h.version >= image.version) continue;
                TkImage.Packed packed = image.snapshot();
                if (packed == null || h != null && h.version >= packed.version) continue;
                source = image; data = packed; offset = 0; last = image.id;
                break;
            }
            if (data == null) return null;
        }
        int length = Math.min(CHUNK, data.bytes.length - offset);
        byte[] piece = new byte[length];
        System.arraycopy(data.bytes, offset, piece, 0, length);
        return new Chunk(source.id, data.version, data.width, data.height, data.bytes.length, offset, piece);
    }

    /** {@code chunk} (the last {@link #next} result) went out. */
    public void sent(Chunk chunk) {
        if (data == null || chunk.image != source.id || chunk.version != data.version || chunk.offset != offset) return;
        offset += chunk.bytes.length;
        if (offset >= data.bytes.length) { held.put(source.id, new Held(source, data.version)); data = null; }
    }
}
