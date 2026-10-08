package reobf.mpy4oc.tk.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.opengl.GL11;
import reobf.mpy4oc.tk.TkDisplayList;
import reobf.mpy4oc.tk.TkFontMetrics;
import reobf.mpy4oc.tk.TkRect;
import reobf.mpy4oc.tk.TkScene;
import reobf.mpy4oc.tk.TkWidget;

/** Only paints server-provided geometry; no layout or hit-testing on the client. */
@SideOnly(Side.CLIENT)
final class TkCanvasRenderer {
    private static TkFontRenderer font;
    private long preparedRevision = -1;
    private int preparedGeneration = -1;
    private TkScene preparedScene;

    void prepare(TkScene scene, long revision) {
        if (font == null) font = new TkFontRenderer();
        if (preparedScene == scene && preparedRevision == revision && preparedGeneration == font.generation()) return;
        font.generateChars(scene.title.codePoints().toArray());
        for (TkDisplayList.Item item : scene.display.items) font.generateChars(item.text.codePoints().toArray());
        preparedRevision = revision; preparedGeneration = font.generation();
        preparedScene = scene;
    }

    void title(String text, int x, int y, int available) { font.drawString(fit(text, available), x, y, 0xFFFFFF); }

    private String fit(String text, int available) {
        int end = 0, width = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end), nextWidth = width + TkFontMetrics.advance(cp);
            if (nextWidth > available) break;
            width = nextWidth; end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    void draw(Minecraft mc, TkScene scene, int left, int top, int width, int height) {
        int guiScale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        draw(scene, left, top, width, height, mc.displayHeight, guiScale);
    }

    void drawTexture(TkScene scene, int width, int height) {
        Gui.drawRect(0, 0, width, height, 0xFF000000 | scene.background);
        draw(scene, 0, 0, width, height, height, 1);
    }

    private void draw(TkScene scene, int left, int top, int width, int height, int viewportHeight, int guiScale) {
        float sx = width / (float) scene.width, sy = height / (float) scene.height;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(left, top, 0);
            GL11.glScalef(sx, sy, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            for (TkDisplayList.Item item : scene.display.items) {
                scissor(viewportHeight, item.clip, left, top, sx, sy, guiScale);
                if (item.vector != null) {
                    if (item.vector.shape == reobf.mpy4oc.tk.TkVector.Shape.TEXT) {
                        if (item.vector.fill >= 0) paintText(item, false);
                    } else TkVectorRenderer.draw(item.vector, item.bounds.x, item.bounds.y);
                    continue;
                }
                paintFrame(item);
                TkRect content = item.clip;
                if (item.kind == TkWidget.Kind.ENTRY) {
                    int inset = Math.min(item.border, Math.min(item.bounds.width, item.bounds.height) / 2);
                    content = content.intersect(new TkRect(item.bounds.x + inset, item.bounds.y + inset,
                            item.bounds.width - 2 * inset, item.bounds.height - 2 * inset));
                }
                scissor(viewportHeight, content, left, top, sx, sy, guiScale);
                if (item.selectionWidth > 0) Gui.drawRect(item.selectionX, item.textY,
                        item.selectionX + item.selectionWidth, item.textY + TkFontMetrics.LINE_HEIGHT, 0xFF2456A6);
                paintText(item, false);
                if (item.selectionWidth > 0) {
                    scissor(viewportHeight, content.intersect(new TkRect(item.selectionX, item.textY, item.selectionWidth, TkFontMetrics.LINE_HEIGHT)), left, top, sx, sy, guiScale);
                    paintText(item, true);
                }
            }
        } finally { GL11.glPopMatrix(); GL11.glPopAttrib(); }
    }

    private void scissor(int viewportHeight, TkRect r, int left, int top, float sx, float sy, int scale) {
        int x0 = (int) Math.ceil((left + r.x * sx) * scale), x1 = (int) Math.floor((left + (r.x + r.width) * sx) * scale);
        int y0 = (int) Math.ceil((top + r.y * sy) * scale), y1 = (int) Math.floor((top + (r.y + r.height) * sy) * scale);
        GL11.glScissor(x0, viewportHeight - y1, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private void paintFrame(TkDisplayList.Item item) {
        TkRect r = item.bounds;
        boolean pressed = (item.state & TkDisplayList.PRESSED) != 0;
        boolean insetBorder = pressed || item.kind == TkWidget.Kind.ENTRY || item.kind == TkWidget.Kind.TEXT;
        Gui.drawRect(r.x, r.y, r.x + r.width, r.y + r.height, 0xFF000000 | item.background);
        int edge = Math.min(item.border, Math.min(r.width, r.height) / 2);
        for (int i = 0; i < edge; i++) {
            int light = insetBorder ? 0xFF555555 : 0xFFF0F0F0, dark = insetBorder ? 0xFFF0F0F0 : 0xFF555555;
            Gui.drawRect(r.x + i, r.y + i, r.x + r.width - i, r.y + i + 1, light);
            Gui.drawRect(r.x + i, r.y + i, r.x + i + 1, r.y + r.height - i, light);
            Gui.drawRect(r.x + i, r.y + r.height - i - 1, r.x + r.width - i, r.y + r.height - i, dark);
            Gui.drawRect(r.x + r.width - i - 1, r.y + i, r.x + r.width - i, r.y + r.height - i, dark);
        }
        if ((item.state & TkDisplayList.FOCUSED) != 0 && item.kind == TkWidget.Kind.BUTTON
                && r.width > 2 * (edge + 1) && r.height > 2 * (edge + 1)) {
            int inset = edge + 1;
            Gui.drawRect(r.x + inset, r.y + inset, r.x + r.width - inset, r.y + inset + 1, 0xFF3377CC);
            Gui.drawRect(r.x + inset, r.y + r.height - inset - 1, r.x + r.width - inset, r.y + r.height - inset, 0xFF3377CC);
            Gui.drawRect(r.x + inset, r.y + inset, r.x + inset + 1, r.y + r.height - inset, 0xFF3377CC);
            Gui.drawRect(r.x + r.width - inset - 1, r.y + inset, r.x + r.width - inset, r.y + r.height - inset, 0xFF3377CC);
        }
    }

    private void paintText(TkDisplayList.Item item, boolean selected) {
        int y = item.textY, offset = (item.state & TkDisplayList.PRESSED) != 0 ? 1 : 0;
        int color = selected ? 0xFFFFFF : (item.state & TkDisplayList.DISABLED) != 0 ? 0x777777 : item.foreground;
        for (String line : item.text.split("\n", -1)) {
            if (!line.isEmpty() && y + TkFontMetrics.LINE_HEIGHT > item.clip.y && y < item.clip.y + item.clip.height)
                font.drawString(line, item.textX + offset, y + offset, color);
            y += TkFontMetrics.LINE_HEIGHT;
        }
        if (item.caretX >= 0) Gui.drawRect(item.caretX, item.textY, item.caretX + 1,
                item.textY + TkFontMetrics.LINE_HEIGHT, 0xFF000000 | color);
    }
}
