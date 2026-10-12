package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.netty.buffer.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkCanvasTest {
    Canvas canvas() { Canvas c=new Canvas(new TkTree().root());c.size(320,240);c.pack();c.tree.render();return c; }
    int create(Canvas c,String type,Object[] coords,Map<String,Object> options) {
        return (Integer)c.call("create",Map.of("type",type,"coords",coords,"options",options));
    }
    void config(Canvas c,Object selector,Map<String,Object> options){c.call("configure",Map.of("selector",selector,"options",options));}
    @Test void geometryHitTestingUsesShapeZOrderClippingAndStates() {
        Canvas c=canvas();
        int rect=create(c,"rectangle",new Object[]{10,10,100,100},Map.of("fill",0x123456));
        int oval=create(c,"oval",new Object[]{10,10,100,100},Map.of("fill",0xffffff));
        assertEquals(oval,c.hitItem(55,55));assertEquals(rect,c.hitItem(12,12));
        config(c,oval,Map.of("fill",-1));assertEquals(rect,c.hitItem(55,55));assertEquals(oval,c.hitItem(55,10));
        config(c,oval,Map.of("state","hidden"));assertEquals(rect,c.hitItem(55,10));
        int line=create(c,"line",new Object[]{120,10,200,90},Map.of("width",6));
        assertEquals(line,c.hitItem(160,50));assertEquals(0,c.hitItem(160,20));
        int concave=create(c,"polygon",new Object[]{10,120,90,120,90,140,30,140,30,200,10,200},Map.of("fill",0));
        assertEquals(concave,c.hitItem(20,190));assertEquals(0,c.hitItem(70,180));
        config(c,concave,Map.of("fill",-1,"outline",-1));assertEquals(concave,c.hitItem(20,190),"Tk polygons retain interior hits when transparent");
        int text=create(c,"text",new Object[]{120,180},Map.of("text","中文","anchor","nw"));
        assertEquals(text,c.hitItem(125,185));config(c,text,Map.of("state","disabled"));assertEquals(0,c.hitItem(125,185));
        create(c,"rectangle",new Object[]{-20,0,20,20},Map.of("fill",0));assertEquals(0,c.hitItem(-1,2));
    }
    @Test void tagsIdsStackingAndMovesRemainStableAndAtomic() {
        Canvas c=canvas();int a=create(c,"line",new Object[]{0,0,5,5},Map.of("tags",new Object[]{"group"}));
        int b=create(c,"line",new Object[]{32760,0,32767,5},Map.of("tags",new Object[]{"group"}));
        assertThrows(IllegalArgumentException.class,()->c.call("move",Map.of("selector","group","dx",10,"dy",0)));
        assertEquals(0,c.items.get(a).vector.at(0));assertEquals(32760,c.items.get(b).vector.at(0));
        assertThrows(IllegalArgumentException.class,()->config(c,"group",Map.of("fill",2,"width",Double.NaN)));
        assertEquals(0,c.items.get(a).vector.fill);
        c.call("coords",Map.of("selector","group","coords",new Object[]{1,2,3,4}));assertEquals(32760,c.items.get(b).vector.at(0));
        c.call("raise",Map.of("selector",a));assertArrayEquals(new Object[]{b,a},(Object[])c.call("find",Map.of()));
        c.call("delete",Map.of("selector",b));assertEquals(3,create(c,"text",new Object[]{0,0},Map.of()));
        c.nextItem=40000;int high=create(c,"text",new Object[]{1,1},Map.of("text","high"));assertEquals(40000,high);
        TkDisplayList before=c.tree.render();config(c,high,Map.of("text","changed"));assertEquals(c.tree.render(),TkDisplayPatch.between(before,c.tree.render()).apply(before));
    }
    @Test void bindingsAreDynamicOrderedAndCaptureDoesNotSwitchDuringDrag() {
        Canvas c=canvas();int a=create(c,"rectangle",new Object[]{10,10,40,40},Map.of("fill",1,"tags",new Object[]{"a","b"}));
        int b=create(c,"rectangle",new Object[]{70,10,100,40},Map.of("fill",2));
        int callback=1;for(Object selector:new Object[]{"all","a","b",a})c.call("bind",Map.of("selector",selector,"sequence","<Button-1>","callback",callback++));
        assertEquals(List.of(1,2,3,4),c.callbacks(a,"<Button-1>"));
        c.call("addtag",Map.of("selector",b,"tag","a"));assertEquals(List.of(1,2),c.callbacks(b,"<Button-1>"));
        c.tree.pointerDown(12,20,20);assertEquals(a,c.tree.capturedItem(12));c.tree.pointerDrag(12,80,20);assertEquals(a,c.tree.capturedItem(12));
        c.tree.pointerDown(13,80,20);assertEquals(b,c.tree.capturedItem(13));assertEquals(a,c.tree.capturedItem(12));
        c.tree.pointerUp(12,80,20);assertEquals(0,c.tree.capturedItem(12));c.tree.release(13);assertEquals(0,c.tree.capturedItem(13));
        c.tree.updateCanvasCurrent(12,80,20);assertEquals(b,c.current);
        c.tree.updateCanvasCurrent(12,-1,-1);assertEquals(0,c.current);
        c.call("delete",Map.of("selector",a));assertFalse(c.tagBindings.containsKey(Integer.toString(a)));assertTrue(c.tagBindings.containsKey("a"));
        assertThrows(IllegalArgumentException.class,()->c.call("bind",Map.of("selector","a","sequence","<Return>","callback",9)));
    }
    @Test void nbtPreservesDisplayOrderCallbacksAndNextIdWithoutSavingCurrent() {
        Canvas c=canvas();int a=create(c,"text",new Object[]{20.5,30.25},Map.of("text","汉字\nnext","tags",new Object[]{"label"}));
        create(c,"oval",new Object[]{100,100,20,20},Map.of("fill",0x123456,"state","disabled"));
        c.call("raise",Map.of("selector",a));c.call("bind",Map.of("selector","label","sequence","<Button-1>","callback",9));c.current=a;
        NBTTagCompound nbt=TkTreePersistence.save(c.tree);TkTree loaded=TkTreePersistence.load(nbt,320,240,()->{});Canvas d=(Canvas)loaded.get(c.id);
        assertEquals(c.tree.render(),loaded.render());assertEquals(c.tagBindings,d.tagBindings);assertEquals(0,d.current);assertEquals(c.nextItem,d.nextItem);
        NBTTagCompound record=nbt.getTagList("nodes",10).getCompoundTagAt(1);record.setInteger("canvasNext",1);
        assertThrows(IllegalArgumentException.class,()->TkTreePersistence.load(nbt,320,240,()->{}));
    }
    @Test void coordinateAndTextBudgetsIncludeHiddenItemsAndRejectBeforeMutation() {
        Canvas c=canvas();Object[] points=new Object[512];Arrays.fill(points,0);
        for(int i=0;i<64;i++)create(c,"line",points,Map.of("state","hidden"));
        assertThrows(IllegalArgumentException.class,()->create(c,"text",new Object[]{0,0},Map.of()));assertEquals(64,c.items.size());
        Canvas d=canvas();for(int i=0;i<16;i++)create(d,"text",new Object[]{0,0},Map.of("text","x".repeat(1024)));
        assertThrows(IllegalArgumentException.class,()->create(d,"text",new Object[]{0,0},Map.of("text","x")));assertEquals(16,d.items.size());
        assertThrows(IllegalArgumentException.class,()->create(d,"line",new Object[]{0,0,Double.NaN,5},Map.of()));
    }
    @Test void fullAndIncrementalWireRoundtripAndMalformedVectorsAreRejected() {
        Canvas c=canvas();int line=create(c,"line",new Object[]{0.5,10.25,100.75,80},Map.of("width",2.5));
        create(c,"text",new Object[]{120,20},Map.of("text","Canvas"));TkScene initial=scene(c);
        TkTarget target=new TkTarget(0,0,0,0,"screen",1);ByteBuf wire=Unpooled.buffer();
        try {
            new TkPackets.OpenGui(target,1,initial).toBytes(wire);TkPackets.OpenGui decoded=new TkPackets.OpenGui();decoded.fromBytes(wire);assertEquals(initial,decoded.scene);
            c.call("move",Map.of("selector",line,"dx",.5,"dy",2));TkScene next=scene(c);TkDisplayPatch patch=TkDisplayPatch.between(initial.display,next.display);assertEquals(1,patch.changed.items.size());
            TkPackets.SceneSync delta=new TkPackets.SceneSync(target,1,2,TkPackets.DISPLAY,next);delta.displayPatch=patch;wire.clear();delta.toBytes(wire);
            TkPackets.SceneSync result=new TkPackets.SceneSync();result.fromBytes(wire);assertEquals(next,result.apply(initial));
            wire.clear();delta.toBytes(wire);wire.setDouble(wire.writerIndex()-13,Double.NaN);assertThrows(IllegalArgumentException.class,()->new TkPackets.SceneSync().fromBytes(wire));
            wire.clear();delta.toBytes(wire);wire.setShort(wire.writerIndex()-39,65535);assertThrows(IllegalArgumentException.class,()->new TkPackets.SceneSync().fromBytes(wire));
        }finally{wire.release();}
    }
    private TkScene scene(Canvas c){return new TkScene(320,240,0,true,c.tree.render());}
}
