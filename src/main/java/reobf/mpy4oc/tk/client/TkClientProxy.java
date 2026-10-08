package reobf.mpy4oc.tk.client;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.network.INetHandler;
import reobf.mpy4oc.tk.TkProxy;
import reobf.mpy4oc.tk.net.TkPackets;

@SideOnly(Side.CLIENT)
public final class TkClientProxy extends TkProxy {
    @Override public void init() { TkSurfaceClient.init(); }
    @Override
    public void receive(IMessage message, INetHandler connection) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (mc.getNetHandler() != connection || mc.theWorld == null) return;
            if (message instanceof TkPackets.SurfaceSync) {
                TkSurfaceClient.receive((TkPackets.SurfaceSync) message);
            } else if (message instanceof TkPackets.SurfaceEnd) {
                TkSurfaceClient.end((TkPackets.SurfaceEnd) message);
            } else if (message instanceof TkPackets.OpenGui) {
                TkPackets.OpenGui open = (TkPackets.OpenGui) message;
                if (mc.theWorld.provider.dimensionId == open.target.dimension) mc.displayGuiScreen(new GuiTkDisplay(open));
            } else if (mc.currentScreen instanceof GuiTkDisplay) {
                GuiTkDisplay gui = (GuiTkDisplay) mc.currentScreen;
                if (message instanceof TkPackets.SceneSync) gui.sync((TkPackets.SceneSync) message);
                else if (message instanceof TkPackets.CopyResult) gui.copyResult((TkPackets.CopyResult) message);
                else if (message instanceof TkPackets.CloseGui
                        && gui.state().target.equals(((TkPackets.CloseGui) message).target)) gui.closeFromServer();
            }
        });
    }
}
