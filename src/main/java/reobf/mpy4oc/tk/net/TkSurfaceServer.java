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
        long budgetTick = -1; int requests;
        boolean allow(long tick) { if (tick != budgetTick) { budgetTick=tick; requests=0; } return ++requests <= 8; }
    }
    private static final class Watch {
        final TkTarget target;
        TkScene previous;
        long revision, seen, sent = -100, resync = -100;
        Watch(TkTarget target, long tick) { this.target=target; seen=tick; }
    }
    static boolean canSee(EntityPlayerMP player, TileTkDisplay tile) {
        return Config.tkSurfaceEnabled && !player.isDead && !tile.isInvalid() && player.worldObj == tile.getWorldObj()
                && TkSurfacePolicy.within(player.getDistanceSq(tile.xCoord+.5,tile.yCoord+.5,tile.zCoord+.5),Config.tkSurfaceDistance)
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
        if (request.action==TkPackets.SurfaceRequest.RESYNC && tick-watch.resync>=20) { watch.previous=null; watch.resync=tick; }
    }
    private static boolean matches(TkTarget t,TkPackets.SurfaceRequest r) {
        return t.dimension==r.dimension && t.x==r.x && t.y==r.y && t.z==r.z && t.session==r.nonce;
    }
    static void tick(long tick) {
        Iterator<Map.Entry<NetHandlerPlayServer,Client>> connections=clients.entrySet().iterator();
        while(connections.hasNext()) {
            Map.Entry<NetHandlerPlayServer,Client> entry=connections.next(); NetHandlerPlayServer connection=entry.getKey();
            EntityPlayerMP player=connection.playerEntity;
            if (!connection.netManager.isChannelOpen() || player.playerNetServerHandler!=connection) { connections.remove(); continue; }
            Iterator<Map.Entry<TileTkDisplay,Watch>> watches=entry.getValue().watches.entrySet().iterator();
            while(watches.hasNext()) {
                Map.Entry<TileTkDisplay,Watch> e=watches.next();TileTkDisplay tile=e.getKey();Watch w=e.getValue();
                if (tick-w.seen>TkSurfacePolicy.LEASE_TICKS || !canSee(player,tile) || !w.target.matches(tile)) {
                    TkNetwork.send(player,new TkPackets.SurfaceEnd(w.target)); watches.remove(); continue;
                }
                double distance=player.getDistanceSq(tile.xCoord+.5,tile.yCoord+.5,tile.zCoord+.5);
                if (tick-w.sent<TkSurfacePolicy.interval(distance)) continue;
                w.sent=tick;TkScene scene=tile.surfaceScene();long revision=tile.sceneRevision();
                int mask=w.previous==null ? TkPackets.ALL : TkPackets.changes(w.previous,scene);
                if(mask==0)continue;
                TkPackets.SurfaceSync packet=new TkPackets.SurfaceSync(w.target,w.previous==null?-1:w.revision,revision,mask,scene);
                if(w.previous!=null&&(mask&TkPackets.DISPLAY)!=0)packet.displayPatch=TkDisplayPatch.between(w.previous.display,scene.display);
                io.netty.buffer.ByteBuf encoded=io.netty.buffer.Unpooled.buffer();
                boolean allowed;
                try {packet.toBytes(encoded);allowed=entry.getValue().bandwidth.take(tick,encoded.readableBytes());}
                finally {encoded.release();}
                if(!allowed)continue;
                TkNetwork.send(player,packet);w.previous=scene;w.revision=revision;
            }
            if(entry.getValue().watches.isEmpty()&&tick-entry.getValue().budgetTick>200)connections.remove();
        }
    }
    static void clear() { clients.clear(); }
    private TkSurfaceServer() {}
}
