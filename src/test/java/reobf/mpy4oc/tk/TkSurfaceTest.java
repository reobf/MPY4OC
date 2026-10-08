package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.*;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;
import java.util.*;

class TkSurfaceTest {
    @Test void surfacesHaveBoundedDistanceFrequencyAndTextureResolution() {
        assertTrue(TkSurfacePolicy.within(1024,32));assertFalse(TkSurfacePolicy.within(1024.001,32));
        assertFalse(TkSurfacePolicy.within(Double.NaN,32));assertFalse(TkSurfacePolicy.within(-1,32));
        assertEquals(2,TkSurfacePolicy.interval(64));assertEquals(4,TkSurfacePolicy.interval(65));assertEquals(10,TkSurfacePolicy.interval(257));
        assertEquals(512,TkSurfacePolicy.resolution(64));assertEquals(256,TkSurfacePolicy.resolution(65));assertEquals(128,TkSurfacePolicy.resolution(257));
        assertTrue(TkSurfacePolicy.MAX_TEXTURES>=10);assertTrue(TkSurfacePolicy.MAX_WATCHES>=TkSurfacePolicy.MAX_TEXTURES);
    }
    @Test void allFourFacesHaveUprightNonMirroredGeometryOutsideTheBlock() {
        for(int face=2;face<=5;face++) {
            double[] left=TkSurfacePolicy.point(face,0,0),right=TkSurfacePolicy.point(face,1,0),bottom=TkSurfacePolicy.point(face,0,1);
            assertEquals(1,left[1]);assertEquals(1,right[1]);assertEquals(0,bottom[1]);
            if(face==2){assertTrue(left[2]<0);assertTrue(left[0]>right[0]);assertTrue(TkSurfacePolicy.inFront(face,0,-1));}
            if(face==3){assertTrue(left[2]>1);assertTrue(left[0]<right[0]);assertTrue(TkSurfacePolicy.inFront(face,0,1));}
            if(face==4){assertTrue(left[0]<0);assertTrue(left[2]<right[2]);assertTrue(TkSurfacePolicy.inFront(face,-1,0));}
            if(face==5){assertTrue(left[0]>1);assertTrue(left[2]>right[2]);assertTrue(TkSurfacePolicy.inFront(face,1,0));}
            assertFalse(TkSurfacePolicy.inFront(face,0,0));
        }
        assertEquals(2,TkSurfacePolicy.face(0));assertEquals(5,TkSurfacePolicy.face(5));
    }
    @Test void boundedBurstPreventsUnboundedFullResyncTrafficAndRecovers() {
        TkSurfaceBudget budget=new TkSurfaceBudget(50);
        assertTrue(budget.take(50,TkSurfaceBudget.BURST));assertFalse(budget.take(50,1));
        assertTrue(budget.take(51,TkSurfaceBudget.PER_TICK));assertFalse(budget.take(51,1));
        assertFalse(budget.take(50,1),"clock rollback cannot refill");
        assertTrue(budget.take(Long.MAX_VALUE,TkSurfaceBudget.BURST));assertFalse(budget.take(Long.MAX_VALUE,1));
        assertFalse(budget.take(Long.MAX_VALUE,-1));
    }
    @Test void requestsRejectInvalidPositionsNoncesActionsAndTrailingBytes() {
        assertThrows(IllegalArgumentException.class,()->new TkPackets.SurfaceRequest(0,0,256,0,1,0));
        assertThrows(IllegalArgumentException.class,()->new TkPackets.SurfaceRequest(0,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new TkPackets.SurfaceRequest(0,0,0,0,1,3));
        ByteBuf b=Unpooled.buffer();try {
            new TkPackets.SurfaceRequest(-1,123,24,-456,99,TkPackets.SurfaceRequest.RESYNC).toBytes(b);
            TkPackets.SurfaceRequest read=new TkPackets.SurfaceRequest();read.fromBytes(b);
            assertEquals(99,read.nonce);assertEquals(-456,read.z);assertEquals(TkPackets.SurfaceRequest.RESYNC,read.action);
            b.clear();read.toBytes(b);b.writeByte(1);assertThrows(IllegalArgumentException.class,()->new TkPackets.SurfaceRequest().fromBytes(b));
        }finally{b.release();}
    }
    @Test void surfaceSceneRoundtripsPatchesAndRejectsOldBlockOrOldLease() {
        TkTree tree=new TkTree();Label label=new Label(tree.root(),"old");label.pack();
        TkTarget target=new TkTarget(0,1,2,3,"screen",55);TkScene initial=scene(tree.render(),true);
        TkPackets.SurfaceSync full=roundtrip(new TkPackets.SurfaceSync(target,-1,1,TkPackets.ALL,initial));
        TkClientState state=new TkClientState(new TkPackets.OpenGui(full.target,full.revision,full.scene));
        label.text("new");TkScene changed=scene(tree.render(),true);TkPackets.SurfaceSync delta=new TkPackets.SurfaceSync(target,1,2,TkPackets.DISPLAY,changed);
        delta.displayPatch=TkDisplayPatch.between(initial.display,changed.display);
        assertTrue(state.accept(roundtrip(delta)));assertEquals(changed,state.scene());
        TkPackets.SurfaceSync stale=new TkPackets.SurfaceSync(new TkTarget(0,1,2,3,"replaced",55),-1,100,TkPackets.ALL,initial);
        assertTrue(state.accept(roundtrip(stale)));assertEquals(changed,state.scene());
        stale=new TkPackets.SurfaceSync(new TkTarget(0,1,2,3,"screen",54),-1,100,TkPackets.ALL,initial);
        assertTrue(state.accept(roundtrip(stale)));assertEquals(changed,state.scene());
        assertFalse(state.accept(new TkPackets.SurfaceSync(target,3,4,TkPackets.DISPLAY,changed)));
        TkScene hidden=scene(TkDisplayList.EMPTY,false);
        assertTrue(state.accept(roundtrip(new TkPackets.SurfaceSync(target,-1,5,TkPackets.ALL,hidden))));assertFalse(state.scene().surfaceVisible);
        assertTrue(state.accept(roundtrip(new TkPackets.SurfaceSync(target,-1,6,TkPackets.ALL,changed))));assertEquals(changed,state.scene());
    }
    @Test void surfaceVisibilityDoesNotExposeOrEraseWidgetsAndLeasesDoNotGrantInput() throws Exception {
        TileTkDisplay tile=new TileTkDisplay();Label label=new Label(tile.widgets().root(),"still in GUI");label.pack();
        assertEquals(1,tile.surfaceScene().display.items.size());
        li.cil.oc.api.machine.Arguments args=(li.cil.oc.api.machine.Arguments)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{li.cil.oc.api.machine.Arguments.class},(p,m,a)->false);
        tile.setSurfaceVisible(null,args);
        assertTrue(tile.surfaceScene().display.items.isEmpty());assertEquals("still in GUI",tile.scene().display.items.get(0).text);
        java.lang.reflect.Field viewers=TileTkDisplay.class.getDeclaredField("viewers");viewers.setAccessible(true);
        assertTrue(((TkViewers<?>)viewers.get(tile)).isEmpty(),"surface access must not create GUI viewer authority");
    }
    private TkScene scene(TkDisplayList display,boolean visible){return new TkScene(320,240,0,"surface",visible,display);}
    private TkPackets.SurfaceSync roundtrip(TkPackets.SurfaceSync packet){ByteBuf b=Unpooled.buffer();try{packet.toBytes(b);TkPackets.SurfaceSync result=new TkPackets.SurfaceSync();result.fromBytes(b);return result;}finally{b.release();}}
}
