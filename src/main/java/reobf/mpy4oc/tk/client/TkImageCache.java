package reobf.mpy4oc.tk.client;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.TextureUtil;
import reobf.mpy4oc.tk.TkImageCodec;
import reobf.mpy4oc.tk.TkScene;
import reobf.mpy4oc.tk.net.TkPackets;

/**
 * One GUI's or surface's PhotoImage textures, assembled from {@link TkPackets.ImageData}
 * pieces. Each image keeps its newest complete texture until a newer one has fully arrived
 * (an image that changes often stays visible). Only images in the current scene table are
 * accepted, at the table's size, so there are never more textures than the table allows.
 * Pieces of one image arrive in order; anything else means the stream broke and the owner
 * asks for a resync. Client thread only.
 */
@SideOnly(Side.CLIENT)
final class TkImageCache {
    static final class Texture {
        final int texture, width, height;
        final long version;
        Texture(int texture, int width, int height, long version) { this.texture = texture; this.width = width; this.height = height; this.version = version; }
    }

    private final Map<Integer, Texture> textures = new HashMap<>();
    private Map<Integer, TkScene.ImageRef> table = Collections.emptyMap();
    private int image, width, height;
    private long version;
    private byte[] buffer;
    private int filled;
    /** Changes whenever a texture appears or goes, so cached pictures know to repaint. */
    int generation;

    /** False when the stream broke (a piece out of order, for an image the table lacks, or bad pixels): resync. */
    boolean accept(TkPackets.ImageData piece) {
        TkScene.ImageRef ref = table.get(piece.image);
        if (ref == null || ref.width != piece.width || ref.height != piece.height
                || piece.total > TkImageCodec.maxPacked(piece.width, piece.height)) { buffer = null; return false; }
        if (piece.offset == 0) {
            image = piece.image; version = piece.version; width = piece.width; height = piece.height;
            buffer = new byte[piece.total]; filled = 0;
        } else if (buffer == null || piece.image != image || piece.version != version || piece.width != width || piece.height != height
                || piece.offset != filled || piece.total != buffer.length) {
            buffer = null;
            return false;
        }
        if (piece.bytes.length > buffer.length - filled) { buffer = null; return false; }
        System.arraycopy(piece.bytes, 0, buffer, filled, piece.bytes.length);
        filled += piece.bytes.length;
        if (filled < buffer.length) return true;
        byte[] packed = buffer;
        buffer = null;
        int[] argb;
        try { argb = TkImageCodec.unpack(packed, width, height); }
        catch (IllegalArgumentException invalid) { return false; }
        Texture current = textures.get(image);
        if (current != null && current.version >= version) return true;
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        picture.setRGB(0, 0, width, height, argb, 0, width);
        int texture = TextureUtil.glGenTextures();
        TextureUtil.uploadTextureImageAllocate(texture, picture, true, true);
        Texture old = textures.put(image, new Texture(texture, width, height, version));
        if (old != null) TextureUtil.deleteTexture(old.texture);
        generation++;
        return true;
    }

    /** The newest texture of {@code image} there is (possibly an older version than the table's), or null. */
    Texture get(int image) { return textures.get(image); }

    /** A new scene arrived: drop textures of images it no longer has. */
    void prune(TkScene scene) {
        table = scene.images;
        for (Iterator<Map.Entry<Integer, Texture>> it = textures.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Texture> e = it.next();
            TkScene.ImageRef ref = table.get(e.getKey());
            if (ref == null || ref.width != e.getValue().width || ref.height != e.getValue().height) {
                TextureUtil.deleteTexture(e.getValue().texture); it.remove(); generation++;
            }
        }
        if (buffer != null && !table.containsKey(image)) buffer = null;
    }

    void dispose() {
        for (Texture t : textures.values()) TextureUtil.deleteTexture(t.texture);
        if (!textures.isEmpty()) generation++;
        textures.clear(); buffer = null; table = Collections.emptyMap();
    }
}
