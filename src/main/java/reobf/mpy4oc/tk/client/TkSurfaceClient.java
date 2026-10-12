package reobf.mpy4oc.tk.client;

import java.util.*;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.*;
import net.minecraft.world.World;
import reobf.mpy4oc.main.*;
import reobf.mpy4oc.tk.*;
import reobf.mpy4oc.tk.net.*;

/** Visible block leases and bounded GPU cache. All entry points run on the client thread. */
@SideOnly(Side.CLIENT)
public final class TkSurfaceClient implements IResourceManagerReloadListener {
    static final Map<TileTkDisplay,Entry> entries=new LinkedHashMap<>();
    private static World world;
    private static long tick, nonce;
    private static int generation;
    private static boolean failed;
    static final class Entry {
        final TileTkDisplay tile;
        long nonce=nextNonce(),seen=tick,lastRequest=-100,nextRequest,lastPaint=-100;
        double distance;
        TkClientState state;
        TkSurfaceTexture texture;
        /** Image textures, kept while the lease lives (the server sends each image once). */
        final TkImageCache images=new TkImageCache();
        boolean resync;
        Entry(TileTkDisplay tile,double distance){this.tile=tile;this.distance=distance;}
    }
    private static long nextNonce(){if(++nonce<=0)nonce=1;return nonce;}
    public static void init() {
        TkSurfaceClient handler=new TkSurfaceClient();
        FMLCommonHandler.instance().bus().register(handler);
        ((IReloadableResourceManager)Minecraft.getMinecraft().getResourceManager()).registerReloadListener(handler);
        ClientRegistry.bindTileEntitySpecialRenderer(TileTkDisplay.class,new TkSurfaceRenderer());
    }
    static boolean enabled(){return Config.tkSurfaceEnabled&&!failed&&OpenGlHelper.isFramebufferEnabled();}
    static Entry visible(TileTkDisplay tile,double distance) {
        if(!enabled()||!TkSurfacePolicy.within(distance,Config.tkSurfaceDistance))return null;
        if(world!=tile.getWorldObj())reset(tile.getWorldObj());
        Entry entry=entries.get(tile);
        if(entry==null) {
            if(entries.size()>=TkSurfacePolicy.MAX_WATCHES)return null;
            entry=new Entry(tile,distance);entries.put(tile,entry);
        }
        entry.seen=tick;entry.distance=distance;return entry;
    }
    private static void request(Entry e,int action) {
        Minecraft mc=Minecraft.getMinecraft();if(mc.getNetHandler()==null||world==null)return;
        TkNetwork.sendToServer(new TkPackets.SurfaceRequest(world.provider.dimensionId,e.tile.xCoord,e.tile.yCoord,e.tile.zCoord,e.nonce,action));
        e.lastRequest=tick;
    }
    private static Entry find(TkTarget target) {
        Minecraft mc=Minecraft.getMinecraft();
        if(mc.theWorld==null||mc.theWorld!=world||world.provider.dimensionId!=target.dimension||!world.blockExists(target.x,target.y,target.z))return null;
        Entry e=entries.get(world.getTileEntity(target.x,target.y,target.z));
        return e!=null&&e.nonce==target.session?e:null;
    }
    public static void receive(TkPackets.SurfaceSync message) {
        Entry e=find(message.target);if(e==null||!enabled())return;
        if(e.state==null) {
            if(!message.isFull()){e.resync=true;return;}
            e.state=new TkClientState(new TkPackets.OpenGui(message.target,message.revision,message.scene));
        } else if(!e.state.accept(message))e.resync=true;
        if(message.isFull())e.resync=false;
        e.images.prune(e.state.scene());
    }
    public static void image(TkPackets.SurfaceImageData message) {
        Entry e=find(message.target);if(e==null||!enabled())return;
        if(!e.images.accept(message))e.resync=true;
    }
    public static void end(TkPackets.SurfaceEnd message) {
        Entry e=find(message.target);if(e==null)return;
        dispose(e);e.images.dispose();e.state=null;e.resync=false;e.nonce=nextNonce();e.nextRequest=tick+100;
    }
    private static void dispose(Entry e){if(e.texture!=null){e.texture.dispose();e.texture=null;}}
    private static void reset(World next) {
        for(Entry e:entries.values()){dispose(e);e.images.dispose();}entries.clear();world=next;
    }
    @SubscribeEvent public void config(ConfigChangedEvent.OnConfigChangedEvent event) {
        if(MyMod.MODID.equals(event.modID))Config.loadFromConfig();
    }
    @Override public void onResourceManagerReload(IResourceManager resources){generation++;failed=false;}
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        tick++;Minecraft mc=Minecraft.getMinecraft();
        if(mc.theWorld!=world)reset(mc.theWorld);
        int requests=0;
        Iterator<Entry> iterator=entries.values().iterator();
        while(iterator.hasNext()) {
            Entry e=iterator.next();
            boolean valid=world!=null&&mc.thePlayer!=null&&!e.tile.isInvalid()&&!e.tile.isSlave()&&world.blockExists(e.tile.xCoord,e.tile.yCoord,e.tile.zCoord)
                    &&world.getTileEntity(e.tile.xCoord,e.tile.yCoord,e.tile.zCoord)==e.tile;
            double distance=valid?e.tile.distanceSq(mc.thePlayer):Double.POSITIVE_INFINITY;
            if(!enabled()||!valid||tick-e.seen>20||!TkSurfacePolicy.within(distance,Config.tkSurfaceDistance)) {
                request(e,TkPackets.SurfaceRequest.CLOSE);dispose(e);e.images.dispose();iterator.remove();continue;
            }
            e.distance=distance;
            if(requests<2&&tick>=e.nextRequest&&(e.resync&&tick-e.lastRequest>=20||tick-e.lastRequest>=TkSurfacePolicy.HEARTBEAT_TICKS)) {
                request(e,e.resync?TkPackets.SurfaceRequest.RESYNC:TkPackets.SurfaceRequest.WATCH);requests++;
            }
        }
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.START||!enabled())return;
        List<Entry> candidates=new ArrayList<>(entries.values());
        candidates.sort(Comparator.comparingDouble(e->e.distance));
        Set<Entry> keep=new HashSet<>();int paints=0;
        // Oldest paint first within the visible set avoids starving the last displays at low FPS.
        for(Entry e:candidates)if(e.state!=null&&e.state.scene().surfaceVisible&&tick-e.seen<=20&&keep.size()<TkSurfacePolicy.MAX_TEXTURES)keep.add(e);
        for(Entry e:candidates)if(!keep.contains(e))dispose(e);
        candidates.sort(Comparator.comparingLong(e->e.lastPaint));
        for(Entry e:candidates) {
            if(!keep.contains(e)||paints>=TkSurfacePolicy.UPDATES_PER_FRAME)continue;
            int size=TkSurfacePolicy.resolution(e.distance,Math.max(e.tile.group().width,e.tile.group().height));
            if(e.texture!=null&&e.texture.revision==e.state.revision()&&e.texture.size==size&&e.texture.generation==generation
                    &&e.texture.imageGeneration==e.images.generation)continue;
            if(tick-e.lastPaint<TkSurfacePolicy.interval(e.distance))continue;
            try {
                if(e.texture==null)e.texture=new TkSurfaceTexture();
                e.texture.update(e.state.scene(),e.state.revision(),size,generation,e.images);e.lastPaint=tick;paints++;
            } catch(RuntimeException error) {
                failed=true;MyMod.LOG.warn("Tk surface rendering disabled until resource reload; GUI remains available",error);
                for(Entry cached:entries.values())dispose(cached);return;
            }
        }
    }
}
