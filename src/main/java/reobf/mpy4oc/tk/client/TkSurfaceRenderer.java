package reobf.mpy4oc.tk.client;

import cpw.mods.fml.relauncher.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import reobf.mpy4oc.tk.*;

/** One cached textured quad on the block's front, with normal world depth testing. */
@SideOnly(Side.CLIENT)
public final class TkSurfaceRenderer extends TileEntitySpecialRenderer {
    @Override public void renderTileEntityAt(TileEntity raw,double x,double y,double z,float partialTick) {
        if(!(raw instanceof TileTkDisplay)||!TkSurfaceClient.enabled())return;
        Minecraft mc=Minecraft.getMinecraft();if(mc.renderViewEntity==null)return;
        TileTkDisplay tile=(TileTkDisplay)raw;if(tile.isSlave())return;
        int face=TkSurfacePolicy.face(tile.getBlockMetadata());TkGroup group=tile.group();
        double dx=mc.renderViewEntity.posX-tile.xCoord-.5,dz=mc.renderViewEntity.posZ-tile.zCoord-.5;
        if(!TkSurfacePolicy.inFront(face,dx,dz))return;
        double distance=tile.distanceSq(mc.renderViewEntity);
        TkSurfaceClient.Entry e=TkSurfaceClient.visible(tile,distance);
        if(e==null||e.state==null||!e.state.scene().surfaceVisible||e.texture==null||e.texture.texture()<0)return;
        // A 1/16 block margin around the whole group, then fit the canvas' aspect ratio.
        TkScene scene=e.state.scene();double w=group.width-.125,h=group.height-.125;
        double aspect=scene.width/(double)scene.height;
        if(aspect>w/h)h=w/aspect;else w=h*aspect;
        double left=group.left+(group.width-w)/2,top=group.top+(group.height-h)/2;
        int activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL11.glPushMatrix();
        try {
            GL11.glTranslated(x,y,z);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);
            // The picture is opaque: no alpha test, which would cut holes where its alpha is low.
            GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,e.texture.texture());GL11.glColor4f(1,1,1,1);
            GL11.glBegin(GL11.GL_QUADS);
            vertex(face,left,top,0,1);vertex(face,left,top+h,0,0);vertex(face,left+w,top+h,1,0);vertex(face,left+w,top,1,1);
            GL11.glEnd();
        } finally {GL11.glPopMatrix();OpenGlHelper.setActiveTexture(activeTexture);GL11.glPopAttrib();}
    }
    private void vertex(int face,double u,double v,double s,double t) {double[] p=TkSurfacePolicy.point(face,u,v);GL11.glTexCoord2d(s,t);GL11.glVertex3d(p[0],p[1],p[2]);}
}
