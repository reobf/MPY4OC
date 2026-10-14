package reobf.mpy4oc.tk.client;

import cpw.mods.fml.relauncher.*;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import reobf.mpy4oc.tk.TkScene;

/** Cached offscreen image. Updated before world rendering, never from inside a TESR. */
@SideOnly(Side.CLIENT)
final class TkSurfaceTexture {
    private Framebuffer buffer;
    private final TkCanvasRenderer renderer=new TkCanvasRenderer();
    long revision=-1;
    int generation=-1;
    int size;
    /** The image cache generation this picture was painted with. */
    int imageGeneration=-1;

    void update(TkScene scene,long revision,int size,int generation,TkImageCache images) {
        int width=scene.width>=scene.height?size:Math.max(1,size*scene.width/scene.height);
        int height=scene.height>=scene.width?size:Math.max(1,size*scene.height/scene.width);
        int previousBuffer=GL11.glGetInteger(0x8CA6); // FRAMEBUFFER_BINDING (core/ARB/EXT share the value).
        int matrixMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE), activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();
        try {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if(buffer==null||buffer.framebufferWidth!=width||buffer.framebufferHeight!=height) {
                dispose();buffer=new Framebuffer(width,height,true);buffer.setFramebufferFilter(GL11.GL_LINEAR);
            }
            buffer.bindFramebuffer(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glColorMask(true,true,true,true);GL11.glColor4f(1,1,1,1);
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glOrtho(0,width,height,0,-100,100);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
            renderer.prepare(scene,revision);renderer.drawTexture(scene,images,width,height);
            this.revision=revision;this.size=size;this.generation=generation;this.imageGeneration=images.generation;
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(matrixMode);
            OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e,previousBuffer);
            OpenGlHelper.setActiveTexture(activeTexture);GL11.glPopAttrib();
        }
    }
    int texture() {return buffer==null?-1:buffer.framebufferTexture;}
    void dispose() {
        if(buffer!=null) {
            // MC's deleteFramebuffer leaks when the user turns fboEnable off after allocation.
            if(buffer.framebufferTexture>=0)net.minecraft.client.renderer.texture.TextureUtil.deleteTexture(buffer.framebufferTexture);
            if(buffer.depthBuffer>=0)OpenGlHelper.func_153184_g(buffer.depthBuffer);
            if(buffer.framebufferObject>=0)OpenGlHelper.func_153174_h(buffer.framebufferObject);
            buffer=null;
        }
        revision=-1;
    }
}
