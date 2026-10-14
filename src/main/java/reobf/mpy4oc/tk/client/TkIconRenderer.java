package reobf.mpy4oc.tk.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import reobf.mpy4oc.tk.TkDisplayList;
import reobf.mpy4oc.tk.TkIconDescriptor;
import reobf.mpy4oc.tk.TkRect;

/** The same inventory/fluid drawing in GUI windows and world-surface framebuffers. */
@SideOnly(Side.CLIENT)
final class TkIconRenderer {
    private static final int MAX_CACHED = 128;
    private final Map<TkIconDescriptor, Resolved> cache = new LinkedHashMap<TkIconDescriptor, Resolved>(16, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<TkIconDescriptor, Resolved> entry) { return size() > MAX_CACHED; }
    };
    private RenderItem items = new RenderItem();

    void clear() { cache.clear(); items = new RenderItem(); }

    void draw(TkDisplayList.Item record) {
        Resolved resolved = cache.get(record.icon);
        if (resolved == null) {
            resolved = new Resolved();
            try {
                if (record.icon.fluid) resolved.fluid = FluidRegistry.getFluid(record.icon.name);
                else {
                    Item item = (Item) Item.itemRegistry.getObject(record.icon.name);
                    if (item != null) {
                        resolved.item = new ItemStack(item, 1, record.icon.damage);
                        resolved.item.setTagCompound(record.icon.copyTag());
                    }
                }
            } catch (RuntimeException | LinkageError error) { resolved.failed = true; }
            cache.put(record.icon, resolved);
        }
        boolean painted = false;
        // Mod inventory renderers may throw with GL matrices pushed. Restore matrices and
        // attributes before drawing the fallback or any later widget, including in a TESR FBO.
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE), active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        Matrix model = new Matrix(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_STACK_DEPTH, GL11.GL_MODELVIEW_MATRIX);
        Matrix projection = new Matrix(GL11.GL_PROJECTION, GL11.GL_PROJECTION_STACK_DEPTH, GL11.GL_PROJECTION_MATRIX);
        Matrix texture = new Matrix(GL11.GL_TEXTURE, GL11.GL_TEXTURE_STACK_DEPTH, GL11.GL_TEXTURE_MATRIX);
        try {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glTranslatef(record.bounds.x, record.bounds.y, 0);
            GL11.glScalef(record.bounds.width / 16F, record.bounds.height / 16F, 1);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (!resolved.failed && resolved.item != null) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                // TkCanvasRenderer already scissors to this icon; clear only its depth region.
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                // Canvas/icon scaling is non-uniform in XYZ. Without normalization,
                // inventory block normals shrink and their faces become nearly black.
                GL11.glEnable(GL11.GL_NORMALIZE);
                RenderHelper.enableGUIStandardItemLighting();
                // RenderItem adds 50 itself. Keep z near zero under the world surface projection.
                items.zLevel = -50;
                Minecraft mc = Minecraft.getMinecraft();
                // Renderers receive a disposable stack, never a real inventory or our cached tag.
                items.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), resolved.item.copy(), 0, 0);
                painted = true;
            } else if (!resolved.failed && resolved.fluid != null) {
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                FluidStack stack = new FluidStack(resolved.fluid, 1000);
                IIcon icon = resolved.fluid.getIcon(stack);
                if (icon != null) {
                    int color = resolved.fluid.getColor(stack);
                    GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
                    Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                    Tessellator t = Tessellator.instance;
                    t.startDrawingQuads();
                    t.addVertexWithUV(0, 16, 0, icon.getMinU(), icon.getMaxV());
                    t.addVertexWithUV(16, 16, 0, icon.getMaxU(), icon.getMaxV());
                    t.addVertexWithUV(16, 0, 0, icon.getMaxU(), icon.getMinV());
                    t.addVertexWithUV(0, 0, 0, icon.getMinU(), icon.getMinV());
                    t.draw(); painted = true;
                }
            }
        } catch (RuntimeException | LinkageError error) {
            resolved.failed = true; // Avoid retrying a broken mod renderer every frame.
            items = new RenderItem();
            // A failing renderer can also leave a batch open. End it inside the same scissor.
            try { Tessellator.instance.draw(); } catch (RuntimeException ignored) { /* no open batch */ }
        } finally {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            texture.restore(); projection.restore(); model.restore();
            GL11.glMatrixMode(mode); OpenGlHelper.setActiveTexture(active); GL11.glPopAttrib();
        }
        if (!painted) missing(record.bounds);
        // Minecraft's multi-pass inventory drawing writes framebuffer alpha directly. A Tk
        // surface is opaque; restore alpha in this scissored rectangle so it cannot make holes.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            if ((record.state & TkDisplayList.DISABLED) != 0)
                Gui.drawRect(record.bounds.x, record.bounds.y, record.bounds.x + record.bounds.width, record.bounds.y + record.bounds.height,
                        0x80000000 | record.background);
            GL11.glColorMask(false, false, false, true);
            Gui.drawRect(record.bounds.x, record.bounds.y, record.bounds.x + record.bounds.width, record.bounds.y + record.bounds.height, 0xFF000000);
        } finally { GL11.glPopAttrib(); }
    }

    private static void missing(TkRect r) {
        int half = Math.max(1, r.width / 2);
        Gui.drawRect(r.x, r.y, r.x + r.width, r.y + r.height, 0xFF242424);
        Gui.drawRect(r.x, r.y, r.x + half, r.y + half, 0xFFB044B0);
        Gui.drawRect(r.x + half, r.y + half, r.x + r.width, r.y + r.height, 0xFFB044B0);
    }

    private static final class Resolved { ItemStack item; Fluid fluid; boolean failed; }

    private static final class Matrix {
        final int mode, query, depth;
        final FloatBuffer value = BufferUtils.createFloatBuffer(16);
        Matrix(int mode, int query, int matrix) {
            this.mode = mode; this.query = query;
            GL11.glMatrixMode(mode); depth = GL11.glGetInteger(query); GL11.glGetFloat(matrix, value); GL11.glPushMatrix();
        }
        void restore() {
            GL11.glMatrixMode(mode);
            int current = GL11.glGetInteger(query);
            while (current > depth) { GL11.glPopMatrix(); current--; }
            while (current < depth) { GL11.glPushMatrix(); current++; }
            GL11.glLoadMatrix(value);
        }
    }
}
