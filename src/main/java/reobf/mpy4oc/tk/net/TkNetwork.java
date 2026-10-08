package reobf.mpy4oc.tk.net;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.util.AttributeKey;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.tileentity.TileEntity;
import reobf.mpy4oc.main.MyMod;
import reobf.mpy4oc.tk.TileTkDisplay;
import reobf.mpy4oc.tk.TkTarget;

/** Packet handlers only enqueue work; block access and broadcasts happen on server ticks. */
public final class TkNetwork {
    public static final String CHANNEL = "mpy4oc_tk9";
    private static final AttributeKey<Boolean> SUPPORTED = new AttributeKey<>("mpy4oc:tk9");
    private static final ArrayBlockingQueue<Runnable> REQUESTS = new ArrayBlockingQueue<>(2048);
    private static final Set<TileTkDisplay> WATCHED = new HashSet<>();
    private static SimpleNetworkWrapper channel;
    private static long tick;

    private TkNetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        channel.registerMessage(new OpenHandler(), TkPackets.OpenGui.class, 0, Side.CLIENT);
        channel.registerMessage(new SyncHandler(), TkPackets.SceneSync.class, 1, Side.CLIENT);
        channel.registerMessage(new CloseRequestHandler(), TkPackets.CloseRequest.class, 2, Side.SERVER);
        channel.registerMessage(new CloseGuiHandler(), TkPackets.CloseGui.class, 3, Side.CLIENT);
        channel.registerMessage(new InputHandler(), TkPackets.InputEvent.class, 4, Side.SERVER);
        channel.registerMessage(new ClipboardHandler(), TkPackets.Clipboard.class, 5, Side.SERVER);
        channel.registerMessage(new ResyncHandler(), TkPackets.Resync.class, 6, Side.SERVER);
        channel.registerMessage(new HeartbeatHandler(), TkPackets.Heartbeat.class, 7, Side.SERVER);
        channel.registerMessage(new CopyRequestHandler(), TkPackets.CopyRequest.class, 8, Side.SERVER);
        channel.registerMessage(new CopyResultHandler(), TkPackets.CopyResult.class, 9, Side.CLIENT);
        channel.registerMessage(new SurfaceRequestHandler(), TkPackets.SurfaceRequest.class, 10, Side.SERVER);
        channel.registerMessage(new SurfaceSyncHandler(), TkPackets.SurfaceSync.class, 11, Side.CLIENT);
        channel.registerMessage(new SurfaceEndHandler(), TkPackets.SurfaceEnd.class, 12, Side.CLIENT);
        FMLCommonHandler.instance().bus().register(new TkNetwork());
    }

    public static boolean supports(EntityPlayerMP player) {
        return player.playerNetServerHandler != null && Boolean.TRUE.equals(
                player.playerNetServerHandler.netManager.channel().attr(SUPPORTED).get());
    }

    public static void send(EntityPlayerMP player, IMessage message) {
        if (channel != null && player.playerNetServerHandler.netManager.isChannelOpen()) channel.sendTo(message, player);
    }

    public static void sendToServer(IMessage message) { if (channel != null) channel.sendToServer(message); }
    public static void watch(TileTkDisplay tile) { WATCHED.add(tile); }
    public static void unwatch(TileTkDisplay tile) { WATCHED.remove(tile); }
    public static long currentTick() { return tick; }

    public static void beginViewing(EntityPlayerMP player) {
        for (TileTkDisplay tile : new HashSet<>(WATCHED)) tile.closePlayer(player);
    }

    public static boolean canUse(EntityPlayerMP player, TileTkDisplay tile) {
        return !player.isDead && !tile.isInvalid() && player.worldObj == tile.getWorldObj()
                && player.getDistanceSq(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5) <= 64
                && player.worldObj.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
                && player.worldObj.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile
                && player.playerNetServerHandler != null && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    @SubscribeEvent
    public void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.side == Side.SERVER && event.registrations.contains(CHANNEL))
            event.manager.channel().attr(SUPPORTED).set("REGISTER".equals(event.operation));
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        for (int i = 0; i < 256; i++) {
            Runnable request = REQUESTS.poll();
            if (request == null) break;
            try { request.run(); }
            catch (Exception e) { MyMod.LOG.warn("Tk request failed", e); }
        }
        for (TileTkDisplay tile : new HashSet<>(WATCHED)) tile.flushViewers();
        TkSurfaceServer.tick(tick);
    }

    public static void serverStopped() {
        REQUESTS.clear();
        for (TileTkDisplay tile : new HashSet<>(WATCHED)) tile.discardViewers();
        WATCHED.clear();
        TkSurfaceServer.clear();
    }

    private static void handle(TkPackets.Request request, NetHandlerPlayServer connection) {
        EntityPlayerMP player = connection.playerEntity;
        if (player.playerNetServerHandler != connection || !connection.netManager.isChannelOpen()) return;
        TkTarget target = request.target;
        if (player.dimension != target.dimension || !player.worldObj.blockExists(target.x, target.y, target.z)) return;
        TileEntity entity = player.worldObj.getTileEntity(target.x, target.y, target.z);
        if (!(entity instanceof TileTkDisplay)) return;
        TileTkDisplay tile = (TileTkDisplay) entity;
        if (!target.matches(tile)) return;
        // Closing is always permitted for an existing session, including after moving away.
        if (request instanceof TkPackets.CloseRequest && !(request instanceof TkPackets.Resync)
                && !(request instanceof TkPackets.Heartbeat)) {
            tile.closeFor(player, target.session, false);
            return;
        }
        int cost = request instanceof TkPackets.Clipboard
                ? ((TkPackets.Clipboard) request).text.getBytes(StandardCharsets.UTF_8).length : request instanceof TkPackets.CopyRequest ? 4096 : 32;
        if (!canUse(player, tile) || !tile.acceptInput(player, target.session, tick, cost)) return;
        if (request instanceof TkPackets.Resync) tile.sendFullScene(player, target.session);
        else if (request instanceof TkPackets.InputEvent) tile.input(player, (TkPackets.InputEvent) request);
        else if (request instanceof TkPackets.Clipboard) tile.clipboard(player, ((TkPackets.Clipboard) request).text);
        else if (request instanceof TkPackets.CopyRequest) tile.copy(player, (TkPackets.CopyRequest) request);
    }

    private abstract static class ServerHandler<T extends TkPackets.Request> implements IMessageHandler<T, IMessage> {
        @Override public IMessage onMessage(T message, MessageContext context) {
            NetHandlerPlayServer connection = context.getServerHandler();
            REQUESTS.offer(() -> handle(message, connection));
            return null;
        }
    }
    private abstract static class ClientHandler<T extends IMessage> implements IMessageHandler<T, IMessage> {
        @Override public IMessage onMessage(T message, MessageContext context) {
            MyMod.tkProxy.receive(message, context.netHandler);
            return null;
        }
    }
    // Distinct classes also give FML's channel pipeline distinct handler names.
    private static final class OpenHandler extends ClientHandler<TkPackets.OpenGui> {}
    private static final class SyncHandler extends ClientHandler<TkPackets.SceneSync> {}
    private static final class CloseGuiHandler extends ClientHandler<TkPackets.CloseGui> {}
    private static final class CloseRequestHandler extends ServerHandler<TkPackets.CloseRequest> {}
    private static final class InputHandler extends ServerHandler<TkPackets.InputEvent> {}
    private static final class ClipboardHandler extends ServerHandler<TkPackets.Clipboard> {}
    private static final class ResyncHandler extends ServerHandler<TkPackets.Resync> {}
    private static final class HeartbeatHandler extends ServerHandler<TkPackets.Heartbeat> {}
    private static final class CopyRequestHandler extends ServerHandler<TkPackets.CopyRequest> {}
    private static final class CopyResultHandler extends ClientHandler<TkPackets.CopyResult> {}
    private static final class SurfaceSyncHandler extends ClientHandler<TkPackets.SurfaceSync> {}
    private static final class SurfaceEndHandler extends ClientHandler<TkPackets.SurfaceEnd> {}
    private static final class SurfaceRequestHandler implements IMessageHandler<TkPackets.SurfaceRequest,IMessage> {
        @Override public IMessage onMessage(TkPackets.SurfaceRequest message,MessageContext context) {
            NetHandlerPlayServer connection=context.getServerHandler();
            REQUESTS.offer(()->TkSurfaceServer.request(message,connection,tick));return null;
        }
    }
}
