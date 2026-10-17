package net.mpy.runtime;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Headless PIL operations. Persistent pixels are ordinary VM bytearrays: no
 * native handles, registry, display ownership or extra snapshot representation. */
public final class PilImage {
    private PilImage() {}
    private static final int LIMIT = ImageCodec.MAX_PIXELS;

    public static Object call(Object[] a) {
        if (a.length == 0 || !(a[0] instanceof String)) throw PyException.typeError("image operation required");
        String op = (String) a[0];
        try {
            switch (op) {
                case "new": {
                    Object[] size = seq(a[2], 2);
                    Raster r = create(string(a[1]), integer(size[0]), integer(size[1]));
                    int c = color(a[3], r.mode);
                    for (int i = 0; i < r.w * r.h; i++) r.set(i, c);
                    return r.spec();
                }
                case "decode": {
                    byte[] bytes = bytes(a[1]);
                    ImageCodec.Decoded image;
                    try { image = ImageCodec.decode(bytes, 0, 0); }
                    catch (IllegalArgumentException e) { throw new PyException("OSError", e.getMessage()); }
                    String mode = "RGB";
                    // Preserve grayscale and explicit alpha, including fully opaque RGBA.
                    if (bytes.length > 25 && bytes[0] == (byte) 137 && bytes[1] == 'P') {
                        int kind = bytes[25] & 255;
                        if (kind == 0) mode = "L";
                        if (kind == 4 || kind == 6) mode = "RGBA";
                    }
                    for (int c : image.argb) if ((c >>> 24) != 255) { mode = "RGBA"; break; }
                    Raster r = create(mode, image.width, image.height);
                    for (int i = 0; i < image.argb.length; i++) r.set(i, image.argb[i]);
                    String format = bytes[0] == (byte) 137 ? "PNG" : bytes[0] == (byte) 255 ? "JPEG" : bytes[0] == 'G' ? "GIF" : "BMP";
                    return new PyObj.Tuple(new Object[] {r.spec(), format});
                }
                case "encode": return new PyObj.Bytes(encode(raster(a[1]), string(a[2]), integer(a[3])));
                case "frombytes": {
                    Object[] size = seq(a[2], 2);
                    Raster r = create(string(a[1]), integer(size[0]), integer(size[1]));
                    byte[] b = bytes(a[3]);
                    if (b.length < r.pixels.size) throw PyException.valueError("not enough image data");
                    System.arraycopy(b, 0, r.pixels.data, 0, r.pixels.size);
                    return r.spec();
                }
                case "copy": { Raster r = raster(a[1]); return new Raster(r.mode, r.w, r.h, new PyObj.ByteArray(r.pixels.toBytes())).spec(); }
                case "get": { Raster r = raster(a[1]); return pixel(r.get(index(r, a[2])), r.mode); }
                case "put": { Raster r = raster(a[1]); int index = index(r, a[2]), c = color(a[3], r.mode); r.set(index, c); return PyObj.NONE; }
                case "convert": {
                    Raster in = raster(a[1]), out = create(string(a[2]), in.w, in.h);
                    for (int i = 0; i < in.w * in.h; i++) out.set(i, in.get(i));
                    return out.spec();
                }
                case "crop": {
                    Raster in = raster(a[1]); int[] b = box(a[2]);
                    Raster out = create(in.mode, b[2] - b[0], b[3] - b[1]);
                    for (int y = 0; y < out.h; y++) for (int x = 0; x < out.w; x++) {
                        int sx = x + b[0], sy = y + b[1];
                        if (sx >= 0 && sy >= 0 && sx < in.w && sy < in.h) out.set(y * out.w + x, in.get(sy * in.w + sx));
                    }
                    return out.spec();
                }
                case "resize": {
                    Raster in = raster(a[1]); Object[] size = seq(a[2], 2);
                    return resize(in, integer(size[0]), integer(size[1]), integer(a[3])).spec();
                }
                case "transpose": return transpose(raster(a[1]), integer(a[2])).spec();
                case "paste": { paste(raster(a[1]), a[2], a[3], a[4]); return PyObj.NONE; }
                case "blend": {
                    Raster x = raster(a[1]), y = raster(a[2]); same(x, y);
                    float alpha = (float) number(a[3]);
                    Raster out = create(x.mode, x.w, x.h);
                    for (int i = 0; i < out.pixels.size; i++) {
                        int first = x.pixels.data[i] & 255, second = y.pixels.data[i] & 255;
                        out.pixels.data[i] = (byte) clamp((int) (first + alpha * (second - first)));
                    }
                    return out.spec();
                }
                case "alpha_composite": {
                    Raster x = raster(a[1]), y = raster(a[2]); same(x, y);
                    if (!x.mode.equals("RGBA")) throw PyException.valueError("image has wrong mode");
                    Raster out = create("RGBA", x.w, x.h);
                    for (int i = 0; i < x.w * x.h; i++) out.set(i, over(x.get(i), y.get(i)));
                    return out.spec();
                }
                case "point": {
                    Raster in = raster(a[1]); Object[] table = seq(a[2], -1);
                    if (table.length != 256 * in.channels) throw PyException.valueError("wrong number of lut entries");
                    byte[] lut = new byte[table.length];
                    for (int i = 0; i < lut.length; i++) lut[i] = (byte) clamp(integer(table[i]));
                    Raster out = create(in.mode, in.w, in.h);
                    for (int i = 0; i < in.pixels.size; i++) out.pixels.data[i] = lut[(i % in.channels) * 256 + (in.pixels.data[i] & 255)];
                    return out.spec();
                }
                case "channel": {
                    Raster in = raster(a[1]); int channel = integer(a[2]);
                    if (channel < 0 || channel >= in.channels) throw PyException.valueError("band index out of range");
                    Raster out = create("L", in.w, in.h);
                    for (int i = 0; i < in.w * in.h; i++) out.pixels.data[i] = in.pixels.data[i * in.channels + channel];
                    return out.spec();
                }
                case "putalpha": {
                    Raster in = raster(a[1]); Raster alpha = a[2] instanceof PyObj.Tuple ? raster(a[2]) : null;
                    if (alpha != null && (!alpha.mode.equals("L") || alpha.w != in.w || alpha.h != in.h)) throw PyException.valueError("images do not match");
                    int value = alpha == null ? channel(a[2]) : 0;
                    Raster out = create("RGBA", in.w, in.h);
                    for (int i = 0; i < in.w * in.h; i++) out.set(i, (in.get(i) & 0xffffff) | (alpha == null ? value : alpha.pixels.data[i] & 255) << 24);
                    return out.spec();
                }
                case "data": {
                    Raster r = raster(a[1]); PyObj.PyList out = new PyObj.PyList();
                    Ops.checkAlloc((long) r.w * r.h, 32);
                    for (int i = 0; i < r.w * r.h; i++) out.items.add(pixel(r.get(i), r.mode));
                    return out;
                }
                case "putdata": {
                    Raster r = raster(a[1]); Object[] values = seq(a[2], -1);
                    if (values.length > r.w * r.h) throw PyException.valueError("too many data entries");
                    int[] colors = new int[values.length];
                    for (int i = 0; i < colors.length; i++) colors[i] = color(values[i], r.mode);
                    for (int i = 0; i < colors.length; i++) r.set(i, colors[i]);
                    return PyObj.NONE;
                }
                case "histogram": {
                    Raster r = raster(a[1]); long[] counts = new long[256 * r.channels];
                    Raster mask = a.length < 3 || a[2] == PyObj.NONE ? null : raster(a[2]);
                    if (mask != null && (!mask.mode.equals("L") || mask.w != r.w || mask.h != r.h))
                        throw PyException.valueError("bad histogram mask");
                    for (int i = 0; i < r.pixels.size; i++)
                        if (mask == null || mask.pixels.data[i / r.channels] != 0)
                            counts[(i % r.channels) * 256 + (r.pixels.data[i] & 255)]++;
                    PyObj.PyList out = new PyObj.PyList(); for (long count : counts) out.items.add(count); return out;
                }
                case "chop": return chop(raster(a[1]), raster(a[2]), string(a[3]), number(a[4]), number(a[5])).spec();
                case "offset": {
                    Raster r = raster(a[1]), out = create(r.mode, r.w, r.h);
                    int dx = Math.floorMod(integer(a[2]), r.w), dy = Math.floorMod(integer(a[3]), r.h);
                    for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++)
                        out.set(y * r.w + x, r.get(((y - dy + r.h) % r.h) * r.w + (x - dx + r.w) % r.w));
                    return out.spec();
                }
                case "kernel": return convolve(raster(a[1]), integer(a[2]), seq(a[3], -1), number(a[4]), number(a[5])).spec();
                case "rank": return rank(raster(a[1]), integer(a[2]), integer(a[3])).spec();
                case "blur": return blur(raster(a[1]), number(a[2]), number(a[3]), integer(a[4])).spec();
                case "unsharp": {
                    int percent = integer(a[3]), threshold = integer(a[4]);
                    if (percent < 0 || percent > 1000 || threshold < 0 || threshold > 255) throw PyException.valueError("invalid unsharp parameters");
                    Raster r = raster(a[1]), low = blur(r, number(a[2]), number(a[2]), 3), out = create(r.mode, r.w, r.h);
                    for (int i = 0; i < r.pixels.size; i++) {
                        int v = r.pixels.data[i] & 255, diff = v - (low.pixels.data[i] & 255);
                        out.pixels.data[i] = (byte) (Math.abs(diff) > threshold ? clamp(v + diff * percent / 100) : v);
                    }
                    return out.spec();
                }
                case "draw": { draw(raster(a[1]), string(a[2]), a[3], a[4], a[5], integer(a[6])); return PyObj.NONE; }
                case "floodfill": { floodfill(raster(a[1]), a[2], a[3], a[4], number(a[5])); return PyObj.NONE; }
                case "font": return PilFont.call(a);
                case "text": { PilFont.draw(a); return PyObj.NONE; }
                default: throw new PyException("NotImplementedError", "unsupported image operation: " + op);
            }
        } catch (PyException e) { throw e; }
        catch (IllegalArgumentException e) { throw PyException.valueError(e.getMessage()); }
        catch (IndexOutOfBoundsException | ClassCastException e) { throw PyException.typeError("invalid image arguments for " + op); }
    }

    static final class Raster {
        final String mode; final int w, h, channels; final PyObj.ByteArray pixels;
        Raster(String mode, int w, int h, PyObj.ByteArray pixels) {
            this.mode = mode; this.w = w; this.h = h; this.channels = channels(mode); this.pixels = pixels;
        }
        Object spec() { return new PyObj.Tuple(new Object[] {mode, (long) w, (long) h, pixels}); }
        int get(int i) {
            i *= channels; byte[] p = pixels.data;
            if (channels == 1) { int v = p[i] & 255; return 0xff000000 | v << 16 | v << 8 | v; }
            return (channels == 4 ? (p[i + 3] & 255) << 24 : 0xff000000) | (p[i] & 255) << 16 | (p[i + 1] & 255) << 8 | (p[i + 2] & 255);
        }
        void set(int i, int argb) {
            i *= channels; byte[] p = pixels.data;
            if (channels == 1) { p[i] = (byte) gray(argb); return; }
            p[i] = (byte) (argb >>> 16); p[i + 1] = (byte) (argb >>> 8); p[i + 2] = (byte) argb;
            if (channels == 4) p[i + 3] = (byte) (argb >>> 24);
        }
    }

    static Raster raster(Object spec) {
        Object[] s = seq(spec, 4); String mode = string(s[0]); int w = integer(s[1]), h = integer(s[2]);
        int n = checked(mode, w, h);
        if (!(s[3] instanceof PyObj.ByteArray) || ((PyObj.ByteArray) s[3]).size != n) throw PyException.valueError("invalid or closed image buffer");
        return new Raster(mode, w, h, (PyObj.ByteArray) s[3]);
    }
    private static Raster create(String mode, int w, int h) { return new Raster(mode, w, h, new PyObj.ByteArray(new byte[checked(mode, w, h)])); }
    private static int checked(String mode, int w, int h) {
        int c = channels(mode);
        if (w < 1 || h < 1 || w > ImageCodec.MAX_SIDE || h > ImageCodec.MAX_SIDE || (long) w * h > LIMIT)
            throw PyException.valueError("image must be 1..1024 per side and at most 524288 pixels");
        Ops.checkAlloc((long) w * h * c, 1);
        return w * h * c;
    }
    private static int channels(String mode) {
        if (mode.equals("L")) return 1;
        if (mode.equals("RGB")) return 3;
        if (mode.equals("RGBA")) return 4;
        throw new PyException("NotImplementedError", "supported image modes: L, RGB, RGBA");
    }
    static String string(Object o) { if (!(o instanceof String)) throw PyException.typeError("expected a string"); return (String) o; }
    static int integer(Object o) {
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (!(o instanceof Long)) throw PyException.typeError("integer required");
        long n = (Long) o;
        if (n < -1_000_000 || n > 1_000_000) throw PyException.valueError("image argument out of range");
        return (int) n;
    }
    static double number(Object o) {
        if (!(o instanceof Number)) throw PyException.typeError("number required");
        double d = ((Number) o).doubleValue();
        if (!Double.isFinite(d)) throw PyException.valueError("finite number required");
        return d;
    }
    private static int channel(Object o) { int n = integer(o); if (n < 0 || n > 255) throw PyException.valueError("color channel must be 0..255"); return n; }
    private static int clamp(int n) { return Math.max(0, Math.min(255, n)); }
    private static int gray(int argb) { return (((argb >>> 16) & 255) * 19595 + ((argb >>> 8) & 255) * 38470 + (argb & 255) * 7471 + 32768) >>> 16; }
    static Object[] seq(Object o, int length) {
        Object[] a = o instanceof PyObj.Tuple ? ((PyObj.Tuple) o).items : o instanceof PyObj.PyList ? ((PyObj.PyList) o).items.toArray() : null;
        if (a == null || length >= 0 && a.length != length) throw PyException.typeError("image coordinates must be a sequence" + (length >= 0 ? " of " + length : ""));
        return a;
    }
    static byte[] bytes(Object o) {
        if (o instanceof PyObj.Bytes) return ((PyObj.Bytes) o).data;
        if (o instanceof PyObj.ByteArray) return ((PyObj.ByteArray) o).toBytes();
        throw PyException.typeError("bytes-like object required");
    }
    static int color(Object o, String mode) {
        if (o == PyObj.NONE) return 0;
        if (o instanceof Long) {
            if (mode.equals("L")) { int n = channel(o); return 0xff000000 | n << 16 | n << 8 | n; }
            long n = (Long) o;
            return ((int) n & 255) << 16 | ((int) n & 0xff00) | ((int) n >>> 16 & 255) | (mode.equals("RGB") ? 0xff000000 : (int) n & 0xff000000);
        }
        Object[] c = seq(o, -1);
        if (mode.equals("L") || c.length != 3 && c.length != 4) throw PyException.typeError("color must be an integer or RGB/RGBA tuple");
        int alpha = c.length == 4 ? channel(c[3]) : 255;
        return alpha << 24 | channel(c[0]) << 16 | channel(c[1]) << 8 | channel(c[2]);
    }
    private static Object pixel(int c, String mode) {
        if (mode.equals("L")) return (long) (c & 255);
        return new PyObj.Tuple(mode.equals("RGB") ? new Object[] {(long) (c >>> 16 & 255), (long) (c >>> 8 & 255), (long) (c & 255)}
                : new Object[] {(long) (c >>> 16 & 255), (long) (c >>> 8 & 255), (long) (c & 255), (long) (c >>> 24)});
    }
    private static int index(Raster r, Object xy) {
        Object[] p = seq(xy, 2); int x = integer(p[0]), y = integer(p[1]);
        if (x < 0) x += r.w; if (y < 0) y += r.h;
        if (x < 0 || y < 0 || x >= r.w || y >= r.h) throw new PyException("IndexError", "image index out of range");
        return y * r.w + x;
    }
    private static int[] box(Object value) {
        Object[] b = seq(value, 4); int[] out = new int[4];
        for (int i = 0; i < 4; i++) out[i] = integer(b[i]);
        if (out[2] < out[0] || out[3] < out[1]) throw PyException.valueError("box is reversed");
        return out;
    }
    private static void same(Raster x, Raster y) { if (x.w != y.w || x.h != y.h || !x.mode.equals(y.mode)) throw PyException.valueError("images do not match"); }

    private static Raster transpose(Raster in, int method) {
        if (method < 0 || method > 6) throw PyException.valueError("unknown transpose operation");
        boolean swap = method == 2 || method == 4 || method == 5 || method == 6;
        Raster out = create(in.mode, swap ? in.h : in.w, swap ? in.w : in.h);
        for (int y = 0; y < out.h; y++) for (int x = 0; x < out.w; x++) {
            int sx = x, sy = y;
            switch (method) {
                case 0: sx = in.w - 1 - x; break;
                case 1: sy = in.h - 1 - y; break;
                case 2: sx = in.w - 1 - y; sy = x; break;
                case 3: sx = in.w - 1 - x; sy = in.h - 1 - y; break;
                case 4: sx = y; sy = in.h - 1 - x; break;
                case 5: sx = y; sy = x; break;
                case 6: sx = in.w - 1 - y; sy = in.h - 1 - x; break;
                default: break;
            }
            out.set(y * out.w + x, in.get(sy * in.w + sx));
        }
        return out;
    }

    private static void paste(Raster dst, Object source, Object position, Object maskObject) {
        Raster src = source instanceof PyObj.Tuple && ((PyObj.Tuple) source).items.length == 4 && ((PyObj.Tuple) source).items[0] instanceof String ? raster(source) : null;
        int solid = src == null ? color(source, dst.mode) : 0;
        Object[] b = seq(position, -1);
        if (b.length != 2 && b.length != 4) throw PyException.valueError("paste box must have 2 or 4 coordinates");
        int left = integer(b[0]), top = integer(b[1]);
        int w = b.length == 4 ? integer(b[2]) - left : src == null ? -1 : src.w;
        int h = b.length == 4 ? integer(b[3]) - top : src == null ? -1 : src.h;
        if (w < 0 || h < 0 || src != null && (src.w != w || src.h != h)) throw PyException.valueError("images do not match");
        Raster mask = maskObject == PyObj.NONE ? null : raster(maskObject);
        if (mask != null && (mask.w != w || mask.h != h || mask.channels != 1 && mask.channels != 4)) throw PyException.valueError("bad transparency mask");
        // Copies make overlapping self-pastes independent of traversal direction.
        if (src != null && src.pixels == dst.pixels) src = new Raster(src.mode, src.w, src.h, new PyObj.ByteArray(src.pixels.toBytes()));
        if (mask != null && mask.pixels == dst.pixels) mask = new Raster(mask.mode, mask.w, mask.h, new PyObj.ByteArray(mask.pixels.toBytes()));
        for (int y = Math.max(0, -top); y < Math.min(h, dst.h - top); y++) for (int x = Math.max(0, -left); x < Math.min(w, dst.w - left); x++) {
            int at = (y + top) * dst.w + x + left, c = src == null ? solid : src.get(y * w + x);
            if (mask != null) {
                int m = mask.channels == 1 ? mask.get(y * w + x) & 255 : mask.get(y * w + x) >>> 24;
                int old = dst.get(at), mixed = 0;
                for (int shift = 0; shift <= 24; shift += 8) mixed |= (((old >>> shift & 255) * (255 - m) + (c >>> shift & 255) * m + 127) / 255) << shift;
                c = mixed;
            }
            dst.set(at, c);
        }
    }

    /**
     * Pillow's ImageDraw.floodfill in one pass: starting at the seed, 4-connected pixels whose
     * 1-norm distance from the seed colour (over the mode's channels) is within thresh are set to
     * the fill colour; with a border, every pixel that is neither the border nor the fill colour is.
     * Like Pillow, a seed outside the image fills nothing, a negative seed coordinate reads the pixel
     * counted from the far edge and spreads only to non-negative neighbours, and a fill colour within
     * thresh of the seed colour changes nothing. A filled pixel never qualifies again, so each pixel
     * is pushed at most once.
     */
    private static void floodfill(Raster r, Object xy, Object valueObject, Object borderObject, double thresh) {
        Object[] p = seq(xy, 2);
        int x = integer(p[0]), y = integer(p[1]);
        int sx = x < 0 ? x + r.w : x, sy = y < 0 ? y + r.h : y;
        if (sx < 0 || sy < 0 || sx >= r.w || sy >= r.h) return;
        int value = color(valueObject, r.mode);
        boolean bordered = borderObject != PyObj.NONE;
        int border = bordered ? color(borderObject, r.mode) : 0;
        int background = r.get(sy * r.w + sx);
        if (distance(r, value, background) <= thresh) return;
        r.set(sy * r.w + sx, value);
        // Coordinates are stacked as pairs: the seed may be negative, its neighbours are checked.
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = x;
        stack[top++] = y;
        while (top > 0) {
            int cy = stack[--top], cx = stack[--top];
            for (int k = 0; k < 4; k++) {
                int nx = k == 0 ? cx + 1 : k == 1 ? cx - 1 : cx, ny = k == 2 ? cy + 1 : k == 3 ? cy - 1 : cy;
                if (nx < 0 || ny < 0 || nx >= r.w || ny >= r.h) continue;
                int at = ny * r.w + nx, c = r.get(at);
                boolean fill = bordered ? distance(r, c, value) != 0 && distance(r, c, border) != 0 : distance(r, c, background) <= thresh;
                if (!fill) continue;
                r.set(at, value);
                if (top + 2 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                stack[top++] = nx;
                stack[top++] = ny;
            }
        }
    }

    /** Sum of absolute channel differences over the channels the mode stores (Pillow's _color_diff). */
    private static int distance(Raster r, int a, int b) {
        if (r.channels == 1) return Math.abs((a & 255) - (b & 255));
        int d = Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)) + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)) + Math.abs((a & 255) - (b & 255));
        return r.channels == 4 ? d + Math.abs((a >>> 24) - (b >>> 24)) : d;
    }

    private static int over(int dst, int src) {
        int a = src >>> 24, b = dst >>> 24, weight = a * 255 + b * (255 - a);
        if (a == 0) return dst;
        int out = ((weight + 127) / 255) << 24;
        for (int shift = 0; shift < 24; shift += 8)
            out |= (((src >>> shift & 255) * a * 255 + (dst >>> shift & 255) * b * (255 - a) + weight / 2) / weight) << shift;
        return out;
    }

    private static Raster chop(Raster x, Raster y, String op, double scale, double offset) {
        if (!x.mode.equals(y.mode)) throw PyException.valueError("images do not match");
        if (scale == 0) throw PyException.valueError("scale must not be zero");
        Raster out = create(x.mode, Math.min(x.w, y.w), Math.min(x.h, y.h));
        for (int row = 0; row < out.h; row++) for (int col = 0; col < out.w * out.channels; col++) {
            int a = x.pixels.data[row * x.w * x.channels + col] & 255;
            int b = y.pixels.data[row * y.w * y.channels + col] & 255;
            int v;
            switch (op) {
                case "difference": v = Math.abs(a - b); break;
                case "lighter": v = Math.max(a, b); break;
                case "darker": v = Math.min(a, b); break;
                case "multiply": v = a * b / 255; break;
                case "screen": v = 255 - (255 - a) * (255 - b) / 255; break;
                case "add": v = (int) ((a + b) / (float) scale + (float) offset); break;
                case "subtract": v = (int) ((a - b) / (float) scale + (float) offset); break;
                case "add_modulo": v = (a + b) & 255; break;
                case "subtract_modulo": v = (a - b) & 255; break;
                case "overlay": v = a < 128 ? a * b / 127 : 255 - (255 - a) * (255 - b) / 127; break;
                case "hard_light": v = b < 128 ? a * b / 127 : 255 - (255 - a) * (255 - b) / 127; break;
                case "soft_light": {
                    int dark = (255 - a) * a * b / 65536;
                    int light = a * (255 - (255 - a) * (255 - b) / 255) / 255;
                    v = dark + light; break;
                }
                default: throw PyException.valueError("unknown channel operation");
            }
            out.pixels.data[row * out.w * out.channels + col] = (byte) clamp(v);
        }
        return out;
    }

    private static Raster convolve(Raster in, int size, Object[] values, double scale, double offset) {
        if ((size != 3 && size != 5) || values.length != size * size) throw PyException.valueError("kernel must be 3x3 or 5x5");
        if (scale == 0) throw PyException.valueError("kernel scale must not be zero");
        float[] weights = new float[values.length];
        for (int i = 0; i < weights.length; i++) {
            double weight = number(values[i]) / scale;
            if (!Double.isFinite(weight) || Math.abs(weight) > 1e6) throw PyException.valueError("kernel weight out of range");
            weights[i] = (float) weight;
        }
        Raster out = create(in.mode, in.w, in.h);
        System.arraycopy(in.pixels.data, 0, out.pixels.data, 0, in.pixels.size);
        int radius = size / 2;
        for (int y = radius; y < in.h - radius; y++) for (int x = radius; x < in.w - radius; x++) for (int c = 0; c < in.channels; c++) {
            float v = (float) offset + .5f;
            for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++)
                v += (in.pixels.data[((y + dy) * in.w + x + dx) * in.channels + c] & 255) * weights[(radius - dy) * size + radius + dx];
            out.pixels.data[(y * in.w + x) * in.channels + c] = (byte) clamp((int) v);
        }
        return out;
    }

    private static Raster rank(Raster in, int size, int rank) {
        if (size < 1 || size > 31 || (size & 1) == 0 || rank < 0 || rank >= size * size)
            throw PyException.valueError("rank filter requires an odd size 1..31 and a valid rank");
        if ((long) in.w * in.h * size * size * in.channels > 32_000_000L)
            throw PyException.valueError("rank filter exceeds work limit; use a smaller window or image");
        Raster out = create(in.mode, in.w, in.h); int radius = size / 2;
        int[] histogram = new int[256];
        for (int y = 0; y < in.h; y++) for (int c = 0; c < in.channels; c++) {
            java.util.Arrays.fill(histogram, 0);
            for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++)
                histogram[sample(in, dx, y + dy, c)]++;
            for (int x = 0; x < in.w; x++) {
                int count = 0, v = 0;
                while (v < 255 && (count += histogram[v]) <= rank) v++;
                out.pixels.data[(y * in.w + x) * in.channels + c] = (byte) v;
                for (int dy = -radius; dy <= radius; dy++) {
                    histogram[sample(in, x - radius, y + dy, c)]--;
                    histogram[sample(in, x + radius + 1, y + dy, c)]++;
                }
            }
        }
        return out;
    }

    private static int sample(Raster in, int x, int y, int c) {
        return in.pixels.data[(Math.max(0, Math.min(in.h - 1, y)) * in.w + Math.max(0, Math.min(in.w - 1, x))) * in.channels + c] & 255;
    }

    private static Raster blur(Raster in, double rx, double ry, int passes) {
        if (rx < 0 || ry < 0 || rx > 256 || ry > 256 || passes != 1 && passes != 3)
            throw PyException.valueError("blur radius must be 0..256");
        // Match the variance of three extended box filters to the requested Gaussian.
        if (passes == 3) { rx = gaussianBox(rx); ry = gaussianBox(ry); }
        Raster out = create(in.mode, in.w, in.h);
        byte[] pixels = in.pixels.toBytes();
        for (int i = 0; i < passes && rx > 0; i++) pixels = boxPass(pixels, in.w, in.h, in.channels, rx, true);
        for (int i = 0; i < passes && ry > 0; i++) pixels = boxPass(pixels, in.w, in.h, in.channels, ry, false);
        System.arraycopy(pixels, 0, out.pixels.data, 0, pixels.length);
        return out;
    }

    private static double gaussianBox(double sigma) {
        // Pillow exposes a float radius; keep its rounding before deriving the box width.
        float radius = (float) sigma;
        float variance = radius * radius / 3;
        float length = (float) Math.sqrt(12.0 * variance + 1.0);
        float whole = (float) Math.floor((length - 1.0) / 2.0);
        return whole + (2 * whole + 1) * (whole * (whole + 1) - 3 * variance) / (6 * (variance - (whole + 1) * (whole + 1)));
    }

    private static byte[] boxPass(byte[] pixels, int w, int h, int channels, double radius, boolean horizontal) {
        float floatRadius = (float) radius;
        int n = horizontal ? w : h, lanes = horizontal ? h : w, whole = (int) floatRadius;
        // Quantize weights to 24 bits, including the residual on the two fringe samples.
        int weight = (int) ((1 << 24) / (floatRadius * 2 + 1));
        int fringe = ((1 << 24) - (whole * 2 + 1) * weight) / 2;
        byte[] out = new byte[pixels.length]; int[] row = new int[n], prefix = new int[n + 1];
        for (int lane = 0; lane < lanes; lane++) for (int c = 0; c < channels; c++) {
            for (int p = 0; p < n; p++) {
                row[p] = pixels[(horizontal ? lane * w + p : p * w + lane) * channels + c] & 255;
                prefix[p + 1] = prefix[p] + row[p];
            }
            for (int p = 0; p < n; p++) {
                int left = p - whole, right = p + whole;
                int sum = prefix[Math.min(n, right + 1)] - prefix[Math.max(0, left)]
                        + Math.max(0, -left) * row[0] + Math.max(0, right - n + 1) * row[n - 1];
                long value = (long) sum * weight + (long) (row[Math.max(0, left - 1)] + row[Math.min(n - 1, right + 1)]) * fringe;
                out[(horizontal ? lane * w + p : p * w + lane) * channels + c] = (byte) ((value + (1 << 23)) >> 24);
            }
        }
        return out;
    }

    private static double kernel(double x, int filter) {
        x = Math.abs(x);
        if (filter == 4) return x <= .5 ? 1 : 0;
        if (filter == 2) return x < 1 ? 1 - x : 0;
        if (filter == 3) return x < 1 ? ((1.5 * x - 2.5) * x) * x + 1 : x < 2 ? ((-.5 * x + 2.5) * x - 4) * x + 2 : 0;
        if (filter == 1) return x == 0 ? 1 : x < 3 ? Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 3) / (Math.PI * Math.PI * x * x / 3) : 0;
        if (filter == 5) return x == 0 ? 1 : x < 1 ? Math.sin(Math.PI * x) / (Math.PI * x) * (.54 + .46 * Math.cos(Math.PI * x)) : 0;
        throw PyException.valueError("unknown resampling filter");
    }
    private static Raster resize(Raster in, int w, int h, int filter) {
        Raster out = create(in.mode, w, h);
        if (filter < 0 || filter > 5) throw PyException.valueError("unknown resampling filter");
        if (in.w == w && in.h == h) {
            System.arraycopy(in.pixels.data, 0, out.pixels.data, 0, in.pixels.size);
            return out;
        }
        if (filter == 0) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.set(y * w + x, in.get(Math.min(in.h - 1, (int) ((y + .5) * in.h / h)) * in.w + Math.min(in.w - 1, (int) ((x + .5) * in.w / w))));
            return out;
        }
        Ops.checkAlloc((long) w * in.h * in.channels, 1);
        if ((long) w * in.h > LIMIT) throw PyException.valueError("resize intermediate exceeds image pixel limit");
        byte[] source = in.pixels.toBytes();
        if (in.channels == 4) for (int i = 0; i < source.length; i += 4) for (int c = 0; c < 3; c++) source[i + c] = (byte) (((source[i + c] & 255) * (source[i + 3] & 255) + 127) / 255);
        byte[] tmp = resample(source, in.w, in.h, w, in.channels, filter, true);
        byte[] result = resample(tmp, w, in.h, h, in.channels, filter, false);
        if (in.channels == 4) for (int i = 0; i < result.length; i += 4) for (int c = 0; c < 3; c++) result[i + c] = (byte) ((result[i + 3] & 255) == 0 ? 0 : clamp((result[i + c] & 255) * 255 / (result[i + 3] & 255)));
        System.arraycopy(result, 0, out.pixels.data, 0, result.length);
        return out;
    }
    private static byte[] resample(byte[] src, int w, int h, int size, int channels, int filter, boolean horizontal) {
        int old = horizontal ? w : h, lanes = horizontal ? h : w;
        byte[] out = new byte[size * lanes * channels];
        double scale = old / (double) size, stretch = Math.max(1, scale);
        double radius = (filter == 4 ? .5 : filter == 3 ? 2 : filter == 1 ? 3 : 1) * stretch;
        for (int dest = 0; dest < size; dest++) {
            double center = (dest + .5) * scale;
            int lo = Math.max(0, (int) (center - radius + .5)), hi = Math.min(old, (int) (center + radius + .5));
            double[] weights = new double[hi - lo]; double total = 0;
            for (int k = lo; k < hi; k++) total += weights[k - lo] = kernel((k + .5 - center) / stretch, filter);
            for (int lane = 0; lane < lanes; lane++) for (int c = 0; c < channels; c++) {
                double value = 0;
                for (int k = lo; k < hi; k++) value += (src[(horizontal ? lane * w + k : k * w + lane) * channels + c] & 255) * weights[k - lo];
                out[(horizontal ? lane * size + dest : dest * w + lane) * channels + c] = (byte) clamp((int) Math.floor(value / total + .5));
            }
        }
        return out;
    }

    private static byte[] encode(Raster r, String format, int quality) {
        format = format.toUpperCase(java.util.Locale.ROOT);
        if (format.equals("JPG")) format = "JPEG";
        if (!format.equals("PNG") && !format.equals("JPEG") && !format.equals("BMP")) throw new PyException("NotImplementedError", "save supports PNG, JPEG and BMP");
        if (!format.equals("PNG") && r.channels == 4) throw new PyException("OSError", "cannot write mode RGBA as " + format);
        BufferedImage image = buffered(r);
        ImageWriter writer = null;
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName(format); it.hasNext();) {
            ImageWriter candidate = it.next();
            if (candidate.getClass().getName().startsWith("com.sun.imageio.plugins.")) { writer = candidate; break; }
            candidate.dispose();
        }
        if (writer == null) throw new PyException("OSError", "image encoder unavailable");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream); ImageWriteParam params = writer.getDefaultWriteParam();
                if (format.equals("JPEG")) {
                    if (quality < 0 || quality > 100) throw PyException.valueError("quality must be 0..100");
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(quality / 100f);
                }
                writer.write(null, new IIOImage(image, null, null), params);
            }
            if (out.size() > ImageCodec.MAX_FILE_BYTES) throw PyException.valueError("encoded image exceeds 1 MiB file limit");
            return out.toByteArray();
        } catch (IOException e) { throw new PyException("OSError", e.getMessage()); }
        finally { writer.dispose(); }
    }
    private static BufferedImage buffered(Raster r) {
        BufferedImage image = new BufferedImage(r.w, r.h, r.channels == 1 ? BufferedImage.TYPE_BYTE_GRAY : r.channels == 4 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (r.channels == 1) { image.getRaster().setDataElements(0, 0, r.w, r.h, r.pixels.toBytes()); }
        else { for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) image.setRGB(x, y, r.get(y * r.w + x)); }
        return image;
    }

    private static void draw(Raster r, String shape, Object coords, Object fill, Object outline, int width) {
        if (width < 0 || width > 1024) throw PyException.valueError("draw width must be 0..1024");
        Object[] values = seq(coords, -1);
        if (values.length > 8192) throw PyException.valueError("too many drawing coordinates");
        int[] p = new int[values.length]; for (int i = 0; i < p.length; i++) p[i] = integer(values[i]);
        if (p.length % 2 != 0) throw PyException.valueError("coordinates must be pairs");
        int f = fill == PyObj.NONE ? 0 : color(fill, r.mode), o = outline == PyObj.NONE ? 0 : color(outline, r.mode);
        if (shape.equals("point")) {
            for (int i = 0; i < p.length; i += 2) if (p[i] >= 0 && p[i] < r.w && p[i + 1] >= 0 && p[i + 1] < r.h) r.set(p[i + 1] * r.w + p[i], f);
            return;
        }
        if (shape.equals("rectangle")) {
            if (p.length != 4 || p[2] < p[0] || p[3] < p[1]) throw PyException.valueError("invalid rectangle");
            for (int y = Math.max(0, p[1]); y <= Math.min(r.h - 1, p[3]); y++) for (int x = Math.max(0, p[0]); x <= Math.min(r.w - 1, p[2]); x++) {
                boolean edge = x - p[0] < width || p[2] - x < width || y - p[1] < width || p[3] - y < width;
                if (edge && outline != PyObj.NONE && width > 0) r.set(y * r.w + x, o);
                else if (fill != PyObj.NONE) r.set(y * r.w + x, f);
            }
            return;
        }
        // Java2D is headless; edge rasterization for curves/wide lines can differ
        // by one pixel from Pillow. Draw into ARGB even for L (no gamma conversion).
        BufferedImage image = new BufferedImage(r.w, r.h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) image.setRGB(x, y, r.get(y * r.w + x));
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setStroke(new BasicStroke(Math.max(1, width), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            if (shape.equals("line")) {
                g.setColor(new Color(f, true));
                for (int i = 2; i < p.length; i += 2) g.drawLine(p[i - 2], p[i - 1], p[i], p[i + 1]);
            } else if (shape.equals("ellipse")) {
                if (p.length != 4 || p[2] < p[0] || p[3] < p[1]) throw PyException.valueError("invalid ellipse");
                if (fill != PyObj.NONE) { g.setColor(new Color(f, true)); g.fillOval(p[0], p[1], p[2] - p[0] + 1, p[3] - p[1] + 1); }
                if (outline != PyObj.NONE && width > 0) { g.setColor(new Color(o, true)); g.drawOval(p[0] + width / 2, p[1] + width / 2, Math.max(0, p[2] - p[0] - width + 1), Math.max(0, p[3] - p[1] - width + 1)); }
            } else if (shape.equals("polygon")) {
                if (p.length < 4) throw PyException.valueError("polygon needs at least two points");
                Polygon polygon = new Polygon(); for (int i = 0; i < p.length; i += 2) polygon.addPoint(p[i], p[i + 1]);
                if (fill != PyObj.NONE) { g.setColor(new Color(f, true)); g.fillPolygon(polygon); }
                if (outline != PyObj.NONE && width > 0) { g.setColor(new Color(o, true)); g.drawPolygon(polygon); }
            } else throw new PyException("NotImplementedError", "unsupported draw operation: " + shape);
        } finally { g.dispose(); }
        for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) r.set(y * r.w + x, image.getRGB(x, y));
    }
}
