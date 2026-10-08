package reobf.mpy4oc.tk;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import net.minecraft.network.INetHandler;

/** Common-side entry point. No GUI or Minecraft client types may appear here. */
public class TkProxy {
    public void init() {}
    public void receive(IMessage message, INetHandler connection) {}
}
