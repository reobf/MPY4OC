package reobf.mpy4oc.tk.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.opengl.GL11;
import reobf.mpy4oc.tk.*;
import reobf.mpy4oc.tk.net.TkNetwork;
import reobf.mpy4oc.tk.net.TkPackets;

/**
 * Wrench clicks on displays and the overlay shown while holding a wrench. Clicks become a
 * {@link TkPackets.Wrench} packet instead of a vanilla block click, so every mod's wrench
 * behaves the same whatever its own item logic (sneak bypass, onItemUseFirst, rotation).
 */
@SideOnly(Side.CLIENT)
public final class TkWrenchClient {
    private static final int GREEN = 0x40FF40, RED = 0xFF4040, WHITE = 0xFFFFFF;
    /** The vanilla right-click falls through to "use item in air" after a cancelled block click. */
    private boolean suppressAir;
    private double px, py, pz;

    public static void init() {
        TkWrenchClient handler = new TkWrenchClient();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance().bus().register(handler);
    }

    @SubscribeEvent
    public void interact(PlayerInteractEvent event) {
        if (!event.world.isRemote) return;
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            if (suppressAir) { suppressAir = false; event.setCanceled(true); }
            return;
        }
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (event.entityPlayer != mc.thePlayer || !TkWrench.isWrench(event.entityPlayer.getHeldItem())) return;
        TileEntity entity = event.world.getTileEntity(event.x, event.y, event.z);
        if (!(entity instanceof TileTkDisplay)) return;
        event.setCanceled(true);
        suppressAir = true;
        TileTkDisplay tile = (TileTkDisplay) entity;
        int action;
        if (event.entityPlayer.isSneaking()) action = TkPackets.Wrench.SPLIT;
        else {
            TkGroup.Edge edge = edge(tile, mc.objectMouseOver);
            if (edge == null) return;
            action = edge.ordinal();
        }
        TkNetwork.sendToServer(new TkPackets.Wrench(event.x, event.y, event.z, action));
        mc.thePlayer.swingItem();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) suppressAir = false;
    }

    /** The edge of {@code tile}'s front face that {@code hit} aims at, or null. */
    static TkGroup.Edge edge(TileTkDisplay tile, MovingObjectPosition hit) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.hitVec == null
                || hit.blockX != tile.xCoord || hit.blockY != tile.yCoord || hit.blockZ != tile.zCoord
                || hit.sideHit != tile.face()) return null;
        double[] uv = TkSurfacePolicy.faceUv(tile.face(), hit.hitVec.xCoord - tile.xCoord,
                hit.hitVec.yCoord - tile.yCoord, hit.hitVec.zCoord - tile.zCoord);
        return TkGroup.edgeAt(uv[0], uv[1]);
    }

    /**
     * While a wrench is held: the group's outline; aiming at an edge shows the merged group in
     * green, or the edge in red when it cannot merge; sneaking shows the group in red (split).
     */
    @SubscribeEvent
    public void highlight(DrawBlockHighlightEvent event) {
        MovingObjectPosition hit = event.target;
        EntityPlayer player = event.player;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !TkWrench.isWrench(player.getHeldItem())) return;
        TileEntity entity = player.worldObj.getTileEntity(hit.blockX, hit.blockY, hit.blockZ);
        if (!(entity instanceof TileTkDisplay)) return;
        TileTkDisplay tile = (TileTkDisplay) entity;
        TileTkDisplay master = tile.master();
        float t = event.partialTicks;
        px = player.lastTickPosX + (player.posX - player.lastTickPosX) * t;
        py = player.lastTickPosY + (player.posY - player.lastTickPosY) * t;
        pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * t;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GL11.glDepthMask(false);
            if (player.isSneaking()) {
                if (master != null && !master.group().single()) {
                    GL11.glLineWidth(1.5F);
                    grid(master, master.group(), RED, .5F);
                    GL11.glLineWidth(3F);
                    rect(master, master.group(), RED, .9F);
                }
                return;
            }
            if (master != null) {
                GL11.glLineWidth(2F);
                rect(master, master.group(), WHITE, .6F);
            }
            TkGroup.Edge edge = edge(tile, hit);
            if (edge == null) return;
            TkGroups.Plan plan = TkGroups.plan(player.worldObj, tile, edge);
            GL11.glLineWidth(3.5F);
            if (plan.ok()) rect(plan.master, plan.merged, GREEN, .9F);
            else if (plan.problem != TkGroups.Problem.SAME_GROUP) edgeLine(tile, edge, RED, .9F);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void rect(TileTkDisplay origin, TkGroup g, int rgb, float alpha) {
        color(rgb, alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        vertex(origin, g.left, g.top);
        vertex(origin, g.left + g.width, g.top);
        vertex(origin, g.left + g.width, g.top + g.height);
        vertex(origin, g.left, g.top + g.height);
        GL11.glEnd();
    }

    private void grid(TileTkDisplay origin, TkGroup g, int rgb, float alpha) {
        color(rgb, alpha);
        GL11.glBegin(GL11.GL_LINES);
        for (int u = g.left + 1; u < g.left + g.width; u++) { vertex(origin, u, g.top); vertex(origin, u, g.top + g.height); }
        for (int v = g.top + 1; v < g.top + g.height; v++) { vertex(origin, g.left, v); vertex(origin, g.left + g.width, v); }
        GL11.glEnd();
    }

    private void edgeLine(TileTkDisplay origin, TkGroup.Edge edge, int rgb, float alpha) {
        color(rgb, alpha);
        GL11.glBegin(GL11.GL_LINES);
        switch (edge) {
            case LEFT: vertex(origin, 0, 0); vertex(origin, 0, 1); break;
            case RIGHT: vertex(origin, 1, 0); vertex(origin, 1, 1); break;
            case TOP: vertex(origin, 0, 0); vertex(origin, 1, 0); break;
            default: vertex(origin, 0, 1); vertex(origin, 1, 1);
        }
        GL11.glEnd();
    }

    private static void color(int rgb, float alpha) {
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, alpha);
    }

    /** A point (u right, v down; block units from {@code origin}) just in front of the screen plane. */
    private void vertex(TileTkDisplay origin, double u, double v) {
        int face = origin.face();
        double[] p = TkSurfacePolicy.point(face, u, v);
        int[] n = TkGroup.normal(face);
        GL11.glVertex3d(origin.xCoord + p[0] + n[0] * .004 - px, origin.yCoord + p[1] - py, origin.zCoord + p[2] + n[2] * .004 - pz);
    }
}
