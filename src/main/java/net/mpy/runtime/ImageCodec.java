package net.mpy.runtime;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Image decoding for PhotoImage (Java's own ImageIO readers for PNG, JPEG, GIF and BMP) and
 * the compact pixel format used on the wire and in storage. Decoding runs on the server
 * thread, so every way a small file can make it slow or large is closed before decoding:
 * the header is read first (size limits, the display's free image room), big images are
 * subsampled while decoding, PNG ancillary chunks (compressed text, ICC profiles) are
 * dropped, and JPEG scan counts and BMP compression modes are checked.
 */
public class ImageCodec {
    protected ImageCodec() {}

    public static final int MAX_SIDE = 1024, MAX_PIXELS = 524288, MAX_FILE_BYTES = 1 << 20;

    /** A refusal of ours, passed through the decoder's catch-all unchanged. */
    private static final class Refused extends IllegalArgumentException {
        Refused(String message) { super(message); }
    }

    /** Source limits: anything larger is refused even with fit, before decoding. */
    static final long MAX_SOURCE_PIXELS = 1L << 24;
    static final int MAX_SOURCE_SIDE = 16384;
    /** Progressive JPEGs have about ten scans; each scan is another pass over the whole image. */
    static final int MAX_JPEG_SCANS = 32;

    public static final class Decoded {
        public final int width, height;
        public final int[] argb;
        Decoded(int width, int height, int[] argb) { this.width = width; this.height = height; this.argb = argb; }
    }

    public static Decoded decode(byte[] data, int fitWidth, int fitHeight) { return decode(data, fitWidth, fitHeight, MAX_PIXELS); }

    /**
     * Decode an image file's bytes; with {@code fitWidth/fitHeight} > 0 the result is scaled
     * down (never up) to fit that box, keeping its aspect ratio. The result may have at most
     * {@code room} pixels (the display's free image budget).
     */
    public static Decoded decode(byte[] data, int fitWidth, int fitHeight, long room) {
        if (data == null || data.length == 0 || data.length > MAX_FILE_BYTES)
            throw new IllegalArgumentException("image data must be 1 byte to 1 MiB");
        if (fitWidth < 0 || fitHeight < 0 || (fitWidth == 0) != (fitHeight == 0) || fitWidth > MAX_SIDE || fitHeight > MAX_SIDE)
            throw new IllegalArgumentException("fit must be (width, height), each 1..1024");
        String format = format(data);
        if (format.equals("png")) data = pngEssentials(data);
        else if (format.equals("jpeg")) checkJpeg(data);
        else if (format.equals("bmp")) checkBmp(data);
        ImageReader reader = reader(format);
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE || (long) width * height > MAX_SOURCE_PIXELS)
                throw new Refused("image is " + width + "x" + height + ", too large to decode (at most 16 million pixels, 16384 on a side)");
            int targetWidth = width, targetHeight = height;
            if (fitWidth > 0) {
                double scale = Math.min(1.0, Math.min(fitWidth / (double) width, fitHeight / (double) height));
                targetWidth = Math.max(1, (int) Math.round(width * scale));
                targetHeight = Math.max(1, (int) Math.round(height * scale));
            }
            if (targetWidth > MAX_SIDE || targetHeight > MAX_SIDE)
                throw new Refused("image is " + width + "x" + height + "; at most 1024 on each side -- give fit=(width, height) to scale it down");
            if ((long) targetWidth * targetHeight > room)
                throw new Refused("not enough image room: " + targetWidth + "x" + targetHeight + " needs " + (long) targetWidth * targetHeight
                        + " pixels, " + Math.max(0, room) + " of " + MAX_PIXELS + " are free on this display"
                        + (fitWidth > 0 ? "" : " -- give fit=(width, height) to scale it down"));
            // Skip rows and columns while decoding (each axis on its own: a very long, thin
            // image must not decode its long side in full); the box filter below finishes
            // the at most 2x reduction left on each axis.
            int stepX = Math.max(1, width / targetWidth), stepY = Math.max(1, height / targetHeight);
            ImageReadParam param = reader.getDefaultReadParam();
            if (stepX > 1 || stepY > 1) param.setSourceSubsampling(stepX, stepY, 0, 0);
            BufferedImage image = reader.read(0, param);
            int w = image.getWidth(), h = image.getHeight();
            int[] argb = argb(image);
            if (w != targetWidth || h != targetHeight) argb = shrink(argb, w, h, targetWidth, targetHeight);
            return new Decoded(targetWidth, targetHeight, argb);
        } catch (Refused refused) {
            throw refused;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("cannot decode image: " + error.getMessage(), error);
        } catch (OutOfMemoryError | StackOverflowError error) {
            // A damaged file deep inside a reader: an error for the program, not a crash of its computer.
            throw new IllegalArgumentException("cannot decode image: it needs too much memory");
        } finally {
            reader.dispose();
        }
    }

    /** The format, from the file's first bytes; only these four are decoded. */
    private static String format(byte[] d) {
        if (d.length >= 8 && (d[0] & 255) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G' && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) return "png";
        if (d.length >= 3 && (d[0] & 255) == 0xFF && (d[1] & 255) == 0xD8 && (d[2] & 255) == 0xFF) return "jpeg";
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8' && (d[4] == '7' || d[4] == '9') && d[5] == 'a') return "gif";
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') return "bmp";
        throw new Refused("unsupported image format (PNG, JPEG, GIF or BMP expected)");
    }

    /** Java's own reader for the format, not one another mod's ImageIO plugin may have put first. */
    private static ImageReader reader(String format) {
        ImageReader fallback = null;
        for (Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(format); it.hasNext();) {
            ImageReader r = it.next();
            if (r.getClass().getName().startsWith("com.sun.imageio.plugins.")) { if (fallback != null) fallback.dispose(); return r; }
            if (fallback == null) fallback = r; else r.dispose();
        }
        if (fallback == null) throw new Refused("no " + format.toUpperCase() + " decoder available");
        return fallback;
    }

    /**
     * The PNG with only the chunks that make up its pixels (IHDR, PLTE, tRNS, IDAT, IEND).
     * The rest (compressed text, ICC profiles) can be inflated far beyond the file's size by
     * the reader, and none of it changes the pixels ImageIO produces.
     */
    public static byte[] pngEssentials(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 8);
        int p = 8;
        while (true) {
            if (d.length - p < 12) throw new Refused("damaged PNG file");
            long length = ((d[p] & 255L) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
            if (length > d.length - p - 12) throw new Refused("damaged PNG file");
            String type = new String(d, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int total = (int) length + 12;
            if (type.equals("IHDR") || type.equals("PLTE") || type.equals("tRNS") || type.equals("IDAT") || type.equals("IEND")) out.write(d, p, total);
            p += total;
            if (type.equals("IEND")) return out.toByteArray();
        }
    }

    /** Refuses a JPEG with more scans than any real one has (each is a full pass in the decoder). */
    public static void checkJpeg(byte[] d) {
        int p = 2, scans = 0;
        while (p + 1 < d.length) {
            if ((d[p] & 255) != 0xFF) { p++; continue; }
            int marker = d[p + 1] & 255;
            if (marker == 0xFF) { p++; continue; }
            if (marker == 0xD9) return;
            if (marker == 0x00 || marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) { p += 2; continue; }
            if (p + 3 >= d.length) return;
            int length = (d[p + 2] & 255) << 8 | (d[p + 3] & 255);
            if (length < 2) return;
            p += 2 + length;
            if (marker == 0xDA) {
                if (++scans > MAX_JPEG_SCANS) throw new Refused("JPEG file has too many scans");
                // Entropy-coded data runs to the next marker that is not a stuffed byte or restart.
                while (p + 1 < d.length) {
                    int next = d[p + 1] & 255;
                    if ((d[p] & 255) == 0xFF && next != 0 && !(next >= 0xD0 && next <= 0xD7)) break;
                    p++;
                }
            }
        }
    }

    /** Only uncompressed BMPs: the RLE and embedded JPEG/PNG modes of Java's reader allocate by header values. */
    public static void checkBmp(byte[] d) {
        if (d.length < 34) return;
        int header = (d[14] & 255) | (d[15] & 255) << 8 | (d[16] & 255) << 16 | (d[17] & 255) << 24;
        if (header < 40) return; // OS/2 headers have no compression field
        int compression = (d[30] & 255) | (d[31] & 255) << 8 | (d[32] & 255) << 16 | (d[33] & 255) << 24;
        if (compression != 0 && compression != 3 && compression != 6)
            throw new Refused("compressed BMP files are not supported (save it as PNG)");
    }

    /**
     * ARGB pixels. Gray images are read from their samples: getRGB treats ImageIO's gray
     * as linear light and brightens it (128 comes back as 188), but the samples are already
     * the display values.
     */
    public static int[] argb(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        ColorModel model = image.getColorModel();
        if (model instanceof ComponentColorModel && model.getColorSpace().getType() == ColorSpace.TYPE_GRAY && !model.isAlphaPremultiplied()) {
            Raster raster = image.getRaster();
            boolean alpha = model.hasAlpha() && raster.getNumBands() > 1;
            int grayMax = (1 << model.getComponentSize(0)) - 1, alphaMax = alpha ? (1 << model.getComponentSize(1)) - 1 : 1;
            int[] gray = raster.getSamples(0, 0, w, h, 0, (int[]) null);
            int[] opacity = alpha ? raster.getSamples(0, 0, w, h, 1, (int[]) null) : null;
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) {
                int v = (int) ((gray[i] * 255L + grayMax / 2) / grayMax);
                int a = alpha ? (int) ((opacity[i] * 255L + alphaMax / 2) / alphaMax) : 255;
                out[i] = a << 24 | v << 16 | v << 8 | v;
            }
            return out;
        }
        return image.getRGB(0, 0, w, h, null, 0, w);
    }

    /** Box filter with premultiplied alpha; also handles a target larger on one axis (by 1 px rounding). */
    public static int[] shrink(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int y0 = (int) ((long) y * sh / dh), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / dh));
            for (int x = 0; x < dw; x++) {
                int x0 = (int) ((long) x * sw / dw), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / dw));
                long a = 0, r = 0, g = 0, b = 0; int n = 0;
                for (int sy = y0; sy < y1; sy++) for (int sx = x0; sx < x1; sx++) {
                    int c = src[sy * sw + sx], alpha = c >>> 24;
                    a += alpha; r += ((c >> 16) & 255) * alpha; g += ((c >> 8) & 255) * alpha; b += (c & 255) * alpha; n++;
                }
                if (a == 0) { out[y * dw + x] = 0; continue; }
                out[y * dw + x] = (int) ((a + n / 2) / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }

    private static final byte[] MAGIC = {'T', 'K', 'I', '1'};

    /**
     * "TKI1", width, height, then Deflate of RGBA rows with PNG's Sub filter (each byte minus
     * its left neighbour's). Fastest Deflate level: images are re-packed after every edit.
     */
    public static byte[] pack(int width, int height, int[] argb) {
        byte[] raw = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            int prev = 0;
            for (int x = 0; x < width; x++) {
                int c = argb[y * width + x], i = (y * width + x) * 4;
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, a = c >>> 24;
                raw[i] = (byte) (r - ((prev >> 16) & 255)); raw[i + 1] = (byte) (g - ((prev >> 8) & 255));
                raw[i + 2] = (byte) (b - (prev & 255)); raw[i + 3] = (byte) (a - (prev >>> 24));
                prev = c;
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw); deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
            out.write(MAGIC, 0, 4);
            out.write(width >>> 8); out.write(width); out.write(height >>> 8); out.write(height);
            byte[] buffer = new byte[16384];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Largest packed size accepted for an image of this size (Deflate never grows data by much). */
    public static int maxPacked(int width, int height) { return width * height * 4 + (width * height * 4) / 1000 + 1024; }

    /** Inverse of {@link #pack}; checks the size against what the caller expects. */
    public static int[] unpack(byte[] packed, int width, int height) {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE)
            throw new IllegalArgumentException("image size must be 1..1024 on each side");
        if (packed.length < 8 || packed.length > maxPacked(width, height) || packed[0] != 'T' || packed[1] != 'K' || packed[2] != 'I' || packed[3] != '1'
                || ((packed[4] & 255) << 8 | (packed[5] & 255)) != width || ((packed[6] & 255) << 8 | (packed[7] & 255)) != height)
            throw new IllegalArgumentException("invalid packed image");
        byte[] raw = new byte[width * height * 4];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed, 8, packed.length - 8);
            int filled = 0;
            while (filled < raw.length) {
                int n = inflater.inflate(raw, filled, raw.length - filled);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                filled += n;
            }
            if (filled != raw.length || !inflater.finished()) throw new IllegalArgumentException("invalid packed image data");
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("invalid packed image data", e);
        } finally {
            inflater.end();
        }
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int r = 0, g = 0, b = 0, a = 0;
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                r = (r + raw[i]) & 255; g = (g + raw[i + 1]) & 255; b = (b + raw[i + 2]) & 255; a = (a + raw[i + 3]) & 255;
                argb[y * width + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return argb;
    }
}
