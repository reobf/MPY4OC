package reobf.mpy4oc.tk;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * Server side of wrench handling. Clients send wrench clicks on a display as a packet
 * (TkWrenchClient) and cancel the vanilla click, so a wrench never rotates, dismantles or
 * opens a display through its own item logic. A vanilla click that still arrives is ignored.
 */
public final class TkWrenchEvents {
    @SubscribeEvent
    public void interact(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || event.world.isRemote) return;
        if (event.world.getBlock(event.x, event.y, event.z) instanceof BlockTkDisplay
                && TkWrench.isWrench(event.entityPlayer.getHeldItem())) event.setCanceled(true);
    }
}
