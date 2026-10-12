package reobf.mpy4oc.tk.net;

import java.util.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.tileentity.TileEntity;
import reobf.mpy4oc.main.Config;
import reobf.mpy4oc.tk.*;

/** Independent read-only leases: these nonces can never authorize GUI input. Server thread only. */
final class TkSurfaceServer {
    private static final Map<NetHandlerPlayServer, Client> clients = new HashMap<>();
    private static final class Client {
        final Map<TileTkDisplay, Watch> watches = new LinkedHashMap<>();
        final TkSurfaceBudget bandwidth = new TkSurfaceBudget(TkNetwork.currentTick());
        long budgetTick = -1; int requests, rotation;
        boolean allow(long tick) { if (tick != budgetTick) { budgetTick=tick; requests=0; } return ++requests <= 8; }
    }
    private static final class Watch {
        final TkTarget target;
        TkScene previous;
        long revision, seen, sent = -100, resync = -100;
        final TkImageTransfer images = new TkImageTransfer();
        Watch(TkTarget target, long tick) { this.target=target; seen=tick; }
    }
    static boolean canSee(EntityPlayerMP player, TileTkDisplay tile) {
        return Config.tkSurfaceEnabled && !player.isDead && !tile.isInvalid() && !tile.isSlave() && player.worldObj == tile.getWorldObj()
                && TkSurfacePolicy.within(tile.distanceSq(player),Config.tkSurfaceDistance)
                && player.worldObj.blockExists(tile.xCoord,tile.yCoord,tile.zCoord)
                && player.worldObj.getTileEntity(tile.xCoord,tile.yCoord,tile.zCoord)==tile && TkNetwork.supports(player);
    }
    static void request(TkPackets.SurfaceRequest request, NetHandlerPlayServer connection, long tick) {
        EntityPlayerMP player=connection.playerEntity;
        if (player.playerNetServerHandler!=connection || !connection.netManager.isChannelOpen() || !TkNetwork.supports(player)) return;
        Client client=clients.get(connection);
        // Closing a lease does not require its old chunk to remain loaded.
        if (request.action==TkPackets.SurfaceRequest.CLOSE) {
            if(client!=null)client.watches.values().removeIf(w->matches(w.target,request)); return;
        }
        if(client==null){client=new Client();clients.put(connection,client);}
        if(!client.allow(tick))return;
        if (player.dimension!=request.dimension || !player.worldObj.blockExists(request.x,request.y,request.z)) return;
        TileEntity entity=player.worldObj.getTileEntity(request.x,request.y,request.z);
        if (!(entity instanceof TileTkDisplay)) return;
        TileTkDisplay tile=(TileTkDisplay)entity;
        if (!canSee(player,tile) || tile.node()==null || tile.node().address()==null) return;
        Watch watch=client.watches.get(tile);
        if (watch==null || !matches(watch.target,request)) {
            if (request.action!=TkPackets.SurfaceRequest.WATCH || watch==null && client.watches.size()>=TkSurfacePolicy.MAX_WATCHES) return;
            watch=new Watch(new TkTarget(request.dimension,request.x,request.y,request.z,tile.node().address(),request.nonce),tick);
            client.watches.put(tile,watch);
        }
        watch.seen=tick;
        if (request.action==TkPackets.SurfaceRequest.RESYNC && tick-watch.resync>=20) { watch.previous=null; watch.resync=tick; watch.images.reset(); }
    }
    private static boolean matches(TkTarget t,TkPackets.SurfaceRequest r) {
        return t.dimension==r.dimension && t.x==r.x && t.y==r.y && t.z==r.z && t.session==r.nonce;
    }
    static void tick(long tick) {
        Iterator<Map.Entry<NetHandlerPlayServer,Client>> connections=clients.entrySet().iterator();
        while(connections.hasNext()) {
            Map.Entry<NetHandlerPlayServer,Client> entry=connections.next(); NetHandlerPlayServer connection=entry.getKey(); Client client=entry.getValue();
            EntityPlayerMP player=connection.playerEntity;
            if (!connection.netManager.isChannelOpen() || player.playerNetServerHandler!=connection) { connections.remove(); continue; }
            List<Map.Entry<TileTkDisplay,Watch>> due=new ArrayList<>();
            Iterator<Map.Entry<TileTkDisplay,Watch>> watches=client.watches.entrySet().iterator();
            while(watches.hasNext()) {
                Map.Entry<TileTkDisplay,Watch> e=watches.next();TileTkDisplay tile=e.getKey();Watch w=e.getValue();
                if (tick-w.seen>TkSurfacePolicy.LEASE_TICKS || !canSee(player,tile) || !w.target.matches(tile)) {
                    TkNetwork.send(player,new TkPackets.SurfaceEnd(w.target)); watches.remove(); continue;
                }
                if (tick-w.sent<TkSurfacePolicy.interval(tile.distanceSq(player))) continue;
                w.sent=tick; due.add(e);
            }
            // Backpressure as in TkNetwork.trySend: a client that stops reading must not
            // grow the outbound buffer. What is skipped now goes out as a combined delta later.
            if (!due.isEmpty() && connection.netManager.channel().isWritable()) {
                // Rotate who goes first, so one display cannot take the whole connection budget.
                Collections.rotate(due, -(client.rotation++ % due.size()));
                // Scenes first: image pixels only get what the scene syncs leave.
                for (Map.Entry<TileTkDisplay,Watch> e:due) guarded(player,client,e,() -> sync(player,e.getKey(),e.getValue(),client.bandwidth,tick));
                for (Map.Entry<TileTkDisplay,Watch> e:due) guarded(player,client,e,() -> sendImages(player,e.getValue(),e.getKey(),client.bandwidth,tick));
            }
            if(client.watches.isEmpty()&&tick-client.budgetTick>200)connections.remove();
        }
    }
    /** One watch's failure ends that watch (the client asks again) instead of every update after it. */
    private static void guarded(EntityPlayerMP player, Client client, Map.Entry<TileTkDisplay,Watch> e, Runnable work) {
        if (client.watches.get(e.getKey())!=e.getValue()) return;
        try { work.run(); }
        catch (RuntimeException error) {
            reobf.mpy4oc.main.MyMod.LOG.warn("Ending a Tk surface lease after an error", error);
            client.watches.remove(e.getKey()); TkNetwork.send(player,new TkPackets.SurfaceEnd(e.getValue().target));
        }
    }
    private static void sync(EntityPlayerMP player, TileTkDisplay tile, Watch w, TkSurfaceBudget budget, long tick) {
        TkScene scene=tile.surfaceScene();long revision=tile.sceneRevision();
        int mask=w.previous==null ? TkPackets.ALL : TkPackets.changes(w.previous,scene);
        if(mask==0)return;
        TkPackets.SurfaceSync packet=new TkPackets.SurfaceSync(w.target,w.previous==null?-1:w.revision,revision,mask,scene);
        if(w.previous!=null&&(mask&TkPackets.DISPLAY)!=0)packet.displayPatch=TkDisplayPatch.between(w.previous.display,scene.display);
        io.netty.buffer.ByteBuf encoded=io.netty.buffer.Unpooled.buffer();
        boolean allowed;
        try {packet.toBytes(encoded);allowed=budget.take(tick,encoded.readableBytes());}
        finally {encoded.release();}
        if(!allowed)return;
        TkNetwork.send(player,packet);w.previous=scene;w.revision=revision;
    }
    /** Pixels of the images in the scene the client has, a few pieces per update, within the surface budget. */
    private static void sendImages(EntityPlayerMP player, Watch w, TileTkDisplay tile, TkSurfaceBudget budget, long tick) {
        for (int pieces = 0; pieces < 2; pieces++) {
            TkImageTransfer.Chunk chunk = w.images.next(w.previous, tile.widgets());
            if (chunk == null || !budget.take(tick, chunk.bytes.length + 64)) return;
            TkNetwork.send(player, new TkPackets.SurfaceImageData(w.target, chunk));
            w.images.sent(chunk);
        }
    }
    static void clear() { clients.clear(); }
    private TkSurfaceServer() {}
}
