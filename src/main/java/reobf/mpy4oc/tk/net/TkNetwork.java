package reobf.mpy4oc.tk.net;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
import reobf.mpy4oc.tk.TkSurfaceBudget;
import reobf.mpy4oc.tk.TkTarget;

/** Packet handlers only enqueue work; block access and broadcasts happen on server ticks. */
public final class TkNetwork {
    public static final String CHANNEL = "mpy4oc_tk10";
    private static final AttributeKey<Boolean> SUPPORTED = new AttributeKey<>("mpy4oc:tk10");
    private static final AttributeKey<Connection> CONNECTION = new AttributeKey<>("mpy4oc:tk-connection");
    private static final ArrayBlockingQueue<Runnable> REQUESTS = new ArrayBlockingQueue<>(2048);
    /**
     * Requests one connection may have waiting in the shared queue. Without it a single
     * client can keep the queue full and silently drop everyone else's input, heartbeats
     * (closing their GUIs) and surface lease renewals.
     */
    static final int MAX_PENDING_PER_CONNECTION = 64;
    /**
     * Extra queue slots only close requests may use, so a client at its cap can still
     * close; bounded, because unbounded closes would reopen the starvation hole.
     */
    static final int CLOSE_RESERVE = 8;
    /** A player can (re)open a Tk GUI at most this often, in ticks. */
    static final int OPEN_INTERVAL = 10;

    /** Per-connection rate state, carried on the netty channel so it dies with it. */
    private static final class Connection {
        final AtomicInteger pending = new AtomicInteger();
        /** Outbound GUI bytes (OpenGui, SceneSync, CopyResult); surfaces have their own. */
        final TkSurfaceBudget gui = new TkSurfaceBudget(tick);
        /** Size of the last GUI packet the budget refused; skip re-encoding until it fits. */
        int refused;
        long lastOpen = Long.MIN_VALUE / 2;
    }

    private static Connection connection(io.netty.channel.Channel channel) {
        io.netty.util.Attribute<Connection> attribute = channel.attr(CONNECTION);
        Connection state = attribute.get();
        if (state == null) {
            Connection created = new Connection();
            state = attribute.setIfAbsent(created);
            if (state == null) state = created;
        }
        return state;
    }
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
        channel.registerMessage(new WindowCloseHandler(), TkPackets.WindowClose.class, 13, Side.SERVER);
        FMLCommonHandler.instance().bus().register(new TkNetwork());
    }

    public static boolean supports(EntityPlayerMP player) {
        return player.playerNetServerHandler != null && Boolean.TRUE.equals(
                player.playerNetServerHandler.netManager.channel().attr(SUPPORTED).get());
    }

    public static void send(EntityPlayerMP player, IMessage message) {
        if (channel != null && player.playerNetServerHandler.netManager.isChannelOpen()) channel.sendTo(message, player);
    }

    /**
     * Send a GUI payload (OpenGui, SceneSync, CopyResult) if the connection's outbound
     * budget allows it; returns whether it was sent. Every such packet can be requested by
     * the client (right-click, resync, copy) and may be hundreds of KB, while netty's
     * outbound buffer is unbounded: unmetered, one client could make the server queue
     * hundreds of MB. A single packet larger than the burst waits for a full bucket.
     */
    public static boolean trySend(EntityPlayerMP player, IMessage message) {
        if (channel == null || player.playerNetServerHandler == null
                || !player.playerNetServerHandler.netManager.isChannelOpen()) return false;
        io.netty.channel.Channel wire = player.playerNetServerHandler.netManager.channel();
        // Backpressure: a byte budget bounds the send rate, but a client that reads more
        // slowly than that would still let the outbound buffer grow. Callers retry.
        if (!wire.isWritable()) return false;
        Connection state = connection(wire);
        // A refused packet is usually retried every tick; do not encode it again until
        // the bucket can hold it.
        if (state.refused > 0 && state.gui.available(tick) < state.refused) return false;
        io.netty.buffer.ByteBuf encoded = io.netty.buffer.Unpooled.buffer();
        int size;
        try { message.toBytes(encoded); size = Math.min(encoded.readableBytes(), TkSurfaceBudget.BURST); }
        finally { encoded.release(); }
        if (!state.gui.take(tick, size)) { state.refused = size; return false; }
        state.refused = 0;
        channel.sendTo(message, player);
        return true;
    }

    /** Rate-limits GUI opening per connection; right-clicks are not throttled by vanilla. */
    public static boolean allowOpen(EntityPlayerMP player) {
        if (player.playerNetServerHandler == null) return false;
        Connection state = connection(player.playerNetServerHandler.netManager.channel());
        if (tick - state.lastOpen < OPEN_INTERVAL) return false;
        state.lastOpen = tick;
        return true;
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
        for (TileTkDisplay tile : new HashSet<>(WATCHED)) {
            try { tile.flushViewers(); }
            catch (RuntimeException e) {
                // Drop this display's viewers: retrying every tick would only repeat the
                // failure (and the log) while their GUIs stay frozen.
                MyMod.LOG.warn("Tk viewer update failed; closing its GUIs", e);
                tile.abortViewers();
            }
        }
        try { TkSurfaceServer.tick(tick); }
        catch (RuntimeException e) { MyMod.LOG.warn("Tk surface update failed", e); }
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
        boolean edit = request instanceof TkPackets.Clipboard
                || request instanceof TkPackets.CopyRequest && ((TkPackets.CopyRequest) request).cut
                || request instanceof TkPackets.InputEvent && editsText((TkPackets.InputEvent) request);
        if (!canUse(player, tile) || !tile.acceptInput(player, target.session, tick, cost, edit)) return;
        if (request instanceof TkPackets.Resync) { tile.requestResync(player, target.session, tick); return; }
        // Anyone may press the close button: it closes the application only for players
        // allowed to operate it, and otherwise just their own GUI.
        if (request instanceof TkPackets.WindowClose) { tile.windowClose(player, target.session); return; }
        // Viewing is open to anyone in range, like an OC screen; operating the UI is not:
        // it needs the same permission the owning computer applies to its own screens.
        if (!(request instanceof TkPackets.Heartbeat) && !tile.canControl(player)) return;
        if (request instanceof TkPackets.InputEvent) tile.input(player, (TkPackets.InputEvent) request);
        else if (request instanceof TkPackets.Clipboard) tile.clipboard(player, ((TkPackets.Clipboard) request).text);
        else if (request instanceof TkPackets.CopyRequest) tile.copy(player, (TkPackets.CopyRequest) request);
    }

    /**
     * Key presses that can change text (a printable character, Backspace, Delete, Enter,
     * Tab): only these re-validate and re-lay out a Text. Navigation and modifier keys
     * are cheap and stay outside the edit allowance.
     */
    public static boolean editsText(TkPackets.InputEvent event) {
        if (event.kind != TkPackets.InputEvent.KEY_DOWN) return false;
        if (event.character >= 32 && event.character != 127) return true;
        switch (event.code) { case 14: case 211: case 28: case 156: case 15: return true; default: return false; }
    }

    /** Queue work for the server tick, at most {@link #MAX_PENDING_PER_CONNECTION} per connection. */
    private static void enqueue(NetHandlerPlayServer connection, boolean always, Runnable work) {
        AtomicInteger pending = connection(connection.netManager.channel()).pending;
        // Closing must get through even from a client that is at its cap, within a small reserve.
        if (pending.get() >= MAX_PENDING_PER_CONNECTION + (always ? CLOSE_RESERVE : 0)) return;
        pending.incrementAndGet();
        if (!REQUESTS.offer(() -> { pending.decrementAndGet(); work.run(); })) pending.decrementAndGet();
    }

    private abstract static class ServerHandler<T extends TkPackets.Request> implements IMessageHandler<T, IMessage> {
        @Override public IMessage onMessage(T message, MessageContext context) {
            NetHandlerPlayServer connection = context.getServerHandler();
            boolean close = message instanceof TkPackets.CloseRequest && !(message instanceof TkPackets.Resync)
                    && !(message instanceof TkPackets.Heartbeat);
            enqueue(connection, close, () -> handle(message, connection));
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
    private static final class WindowCloseHandler extends ServerHandler<TkPackets.WindowClose> {}
    private static final class CopyResultHandler extends ClientHandler<TkPackets.CopyResult> {}
    private static final class SurfaceSyncHandler extends ClientHandler<TkPackets.SurfaceSync> {}
    private static final class SurfaceEndHandler extends ClientHandler<TkPackets.SurfaceEnd> {}
    private static final class SurfaceRequestHandler implements IMessageHandler<TkPackets.SurfaceRequest,IMessage> {
        @Override public IMessage onMessage(TkPackets.SurfaceRequest message,MessageContext context) {
            NetHandlerPlayServer connection=context.getServerHandler();
            enqueue(connection, message.action==TkPackets.SurfaceRequest.CLOSE, ()->TkSurfaceServer.request(message,connection,tick));return null;
        }
    }
}
