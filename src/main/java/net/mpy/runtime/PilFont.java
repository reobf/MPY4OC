package net.mpy.runtime;

import static net.mpy.runtime.PilImage.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Java2D font backend. Python retains font bytes/size; no font handles enter snapshots. */
final class PilFont {
    private PilFont() {}
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private static Font font(Object spec) {
        Object[] values = seq(spec, 2);
        double size = number(values[1]);
        if (size <= 0 || size > 256) throw PyException.valueError("font size must be > 0 and <= 256");
        if (values[0] == PyObj.NONE) return new Font("Dialog", Font.PLAIN, 1).deriveFont((float) size);
        byte[] data = bytes(values[0]);
        if (data.length == 0 || data.length > 4 * 1024 * 1024) throw PyException.valueError("font file must be 1 byte to 4 MiB");
        Ops.checkAlloc(data.length, 1);
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(data)).deriveFont((float) size);
        } catch (FontFormatException | IOException e) { throw new PyException("OSError", "cannot load font: " + e.getMessage()); }
    }

    static Object call(Object[] a) {
        String op = string(a[1]); Font font = font(a[2]);
        if (op.equals("check")) return PyObj.NONE;
        LineMetrics metrics = font.getLineMetrics("Mg", CONTEXT);
        if (op.equals("metrics")) return tuple((long) Math.ceil(metrics.getAscent()), (long) Math.ceil(metrics.getDescent()));
        if (op.equals("name")) return tuple(font.getFamily(java.util.Locale.ROOT), font.isBold() ? "Bold" : font.isItalic() ? "Italic" : "Regular");
        Layout layout = layout(font, string(a[3]), a[4] == PyObj.NONE ? "la" : string(a[4]), number(a[5]), string(a[6]), number(a[7]));
        if (op.equals("length")) return layout.advance;
        if (op.equals("bbox")) return tuple((long) Math.floor(layout.bounds.getMinX()), (long) Math.floor(layout.bounds.getMinY()),
                (long) Math.ceil(layout.bounds.getMaxX()), (long) Math.ceil(layout.bounds.getMaxY()));
        throw PyException.valueError("unknown font operation");
    }

    static void draw(Object[] a) {
        Raster image = raster(a[1]); Font font = font(a[2]);
        Object[] xy = seq(a[4], 2); double x = number(xy[0]), y = number(xy[1]);
        if (Math.abs(x) > 1e6 || Math.abs(y) > 1e6) throw PyException.valueError("text coordinates out of range");
        double stroke = number(a[9]);
        Layout layout = layout(font, string(a[3]), a[6] == PyObj.NONE ? "la" : string(a[6]), number(a[7]), string(a[8]), stroke);
        if (layout.shape.getBounds2D().isEmpty()) return;
        int fill = color(a[5], image.mode), outline = a[10] == PyObj.NONE ? fill : color(a[10], image.mode);
        String mode = string(a[11]);
        if (!mode.equals("L") && !mode.equals("1")) throw PyException.valueError("fontmode must be L or 1");
        Shape shape = AffineTransform.getTranslateInstance(x, y).createTransformedShape(layout.shape);
        BufferedImage mask = new BufferedImage(image.w, image.h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, mode.equals("1") ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON);
            if (stroke > 0) {
                g.setColor(Color.WHITE);
                g.fill(new BasicStroke((float) (stroke * 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(shape));
                applyMask(image, mask, outline);
                g.setColor(Color.BLACK); g.fillRect(0, 0, image.w, image.h);
            }
            g.setColor(Color.WHITE); g.fill(shape);
            applyMask(image, mask, fill);
        } finally { g.dispose(); }
    }

    private static void applyMask(Raster image, BufferedImage mask, int ink) {
        int[] row = new int[image.w];
        for (int y = 0; y < image.h; y++) {
            mask.getRaster().getSamples(0, y, image.w, 1, 0, row);
            for (int x = 0; x < image.w; x++) {
                int m = row[x]; if (m == 0) continue;
                int at = y * image.w + x, previous = image.get(at), mixed = 0;
                for (int s = 0; s <= 24; s += 8) mixed |= (((previous >>> s & 255) * (255 - m) + (ink >>> s & 255) * m + 127) / 255) << s;
                image.set(at, mixed);
            }
        }
    }

    private static final class Layout {
        final Shape shape; final Rectangle2D bounds; final double advance;
        Layout(Shape shape, Rectangle2D bounds, double advance) { this.shape = shape; this.bounds = bounds; this.advance = advance; }
    }

    private static Layout layout(Font font, String text, String anchor, double spacing, String align, double stroke) {
        if (text.length() > 4096) throw PyException.valueError("text must contain at most 4096 UTF-16 units");
        if (spacing < 0 || spacing > 1024 || stroke < 0 || stroke > 64) throw PyException.valueError("text spacing/stroke out of range");
        if (anchor.length() != 2 || "lmr".indexOf(anchor.charAt(0)) < 0 || "atmsbd".indexOf(anchor.charAt(1)) < 0)
            throw PyException.valueError("invalid text anchor");
        if (!align.equals("left") && !align.equals("center") && !align.equals("right")) throw PyException.valueError("invalid text alignment");
        String[] lines = text.split("\n", -1);
        if (lines.length > 256) throw PyException.valueError("text must contain at most 256 lines");
        LineMetrics metrics = font.getLineMetrics("Mg", CONTEXT);
        double step = metrics.getHeight() + spacing, advance = 0;
        TextLayout[] layouts = new TextLayout[lines.length];
        for (int i = 0; i < lines.length; i++) if (!lines[i].isEmpty()) {
            layouts[i] = new TextLayout(lines[i], font, CONTEXT);
            advance = Math.max(advance, layouts[i].getAdvance());
        }
        Path2D.Double shapes = new Path2D.Double();
        for (int i = 0; i < layouts.length; i++) if (layouts[i] != null) {
            double shift = align.equals("right") ? advance - layouts[i].getAdvance() : align.equals("center") ? (advance - layouts[i].getAdvance()) / 2 : 0;
            shapes.append(layouts[i].getOutline(AffineTransform.getTranslateInstance(shift, i * step)), false);
        }
        Rectangle2D ink = shapes.getBounds2D();
        double dx = anchor.charAt(0) == 'm' ? -advance / 2 : anchor.charAt(0) == 'r' ? -advance : 0;
        double dy;
        switch (anchor.charAt(1)) {
            case 'a': dy = metrics.getAscent(); break;
            case 't': dy = -ink.getMinY(); break;
            case 'm': dy = (metrics.getAscent() - metrics.getDescent() - (lines.length - 1) * step) / 2; break;
            case 'b': dy = -ink.getMaxY(); break;
            case 'd': dy = -metrics.getDescent() - (lines.length - 1) * step; break;
            default: dy = 0;
        }
        Shape placed = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(shapes);
        Rectangle2D bounds = text.isEmpty() ? new Rectangle2D.Double() : new Rectangle2D.Double(
                Math.min(0, ink.getMinX()) + dx - stroke, ink.getMinY() + dy - stroke,
                Math.max(advance, ink.getMaxX()) - Math.min(0, ink.getMinX()) + stroke * 2, ink.getHeight() + stroke * 2);
        return new Layout(placed, bounds, advance);
    }

    private static PyObj.Tuple tuple(Object... values) { return new PyObj.Tuple(values); }
}
