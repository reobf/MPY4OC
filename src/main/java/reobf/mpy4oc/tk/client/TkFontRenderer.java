package reobf.mpy4oc.tk.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import li.cil.oc.client.renderer.font.DynamicFontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.IResourceManager;
import org.lwjgl.opengl.GL11;
import reobf.mpy4oc.tk.TkFontMetrics;

/** Reuses OC's glyphs, texture cache and reload listener at native 8x16 pixels. */
@SideOnly(Side.CLIENT)
final class TkFontRenderer extends DynamicFontRenderer {
    private int generation;
    int generation() { return generation; }

    @Override public void onResourceManagerReload(IResourceManager manager) {
        super.onResourceManagerReload(manager);
        generation++;
    }

    @Override public void drawString(String text, int x, int y, int color) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            Tessellator tessellator = Tessellator.instance;
            for (int texture = 0; texture < textureCount(); texture++) {
                bindTexture(texture);
                tessellator.startDrawingQuads();
                tessellator.setColorOpaque_I(color);
                int advance = 0;
                for (int offset = 0; offset < text.length();) {
                    int cp = text.codePointAt(offset);
                    offset += Character.charCount(cp);
                    if (cp != ' ') drawChar(advance, 0, cp);
                    advance += TkFontMetrics.advance(cp);
                }
                tessellator.draw();
            }
        } finally { GL11.glPopMatrix(); GL11.glPopAttrib(); }
    }
}
