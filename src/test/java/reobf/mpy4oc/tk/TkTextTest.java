package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import io.netty.buffer.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkTextTest {
    Text text() { Text t=new Text(new TkTree().root());TkOptions.configure(t,Map.of("width",20,"height",6));t.pack();t.tree.render();return t; }
    @Test void editsAndIndicesMatchNativeTkGoldenCases() throws Exception {
        JsonObject data=new JsonParser().parse(Files.readString(Path.of("src/test/resources/tk-text-golden.json"))).getAsJsonObject();
        int number=0;
        for(JsonElement raw:data.getAsJsonArray("cases")) {
            JsonObject c=raw.getAsJsonObject();Text t=text(); t.insert(0,c.get("initial").getAsString());
            for(JsonElement operation:c.getAsJsonArray("operations")) {
                JsonArray a=operation.getAsJsonArray();int index=t.index(a.get(1).getAsString());
                if(a.get(0).getAsString().equals("insert"))t.insert(index,a.get(2).getAsString());else t.delete(index,t.index(a.get(2).getAsString()));
            }
            assertEquals(c.get("content").getAsString(),t.content(),"case "+number);
            for(Map.Entry<String,JsonElement> q:c.getAsJsonObject("indices").entrySet())assertEquals(q.getValue().getAsString(),t.index(t.index(q.getKey())),"case "+number+" "+q.getKey());
            number++;
        }
        assertEquals(100,number);
    }
    @Test void unicodeEditsPreserveFinalNewlineAndAreAtomicAtLimits() {
        Text t=text();t.insert(0,"中😀\n文");assertEquals("2.1",t.index(t.index("end-1c")));assertEquals("😀",t.get(t.index("1.1"),t.index("1.2")));
        t.selection(1,3);t.cursor(2);String before=t.content();
        assertThrows(IllegalArgumentException.class,()->t.paste("\uD800"));assertEquals(before,t.content());assertEquals(1,t.first);assertEquals(2,t.cursor);
        assertThrows(IllegalArgumentException.class,()->t.insert(0,"x".repeat(Text.MAX_CHARS)));assertEquals(before,t.content());
        t.delete(0,t.length());assertEquals("\n",t.content());assertEquals("2.0",t.index(t.index("end")));
        t.insert(t.length(),"尾\r\n");assertEquals("尾\n\n",t.content());
        assertThrows(IllegalArgumentException.class,()->t.index("end + 2 lines"));assertThrows(IllegalArgumentException.class,()->t.index("bad"));
    }
    @Test void keyboardSelectionAndDisabledCopyKeepDataSafe() {
        Text t=text();t.insert(0,"abc\n中文\n1234");t.tree.focus(t.id);t.cursor(2);
        t.tree.key(208,0,0);assertEquals("2.1",t.index(t.cursor));t.tree.key(208,0,0);assertEquals("3.2",t.index(t.cursor));
        t.tree.key(199,0,0);t.tree.key(205,1,0);assertEquals("1",t.selectedText());t.tree.paste("中\n文");assertTrue(t.content().contains("中\n文234"));
        t.tree.key(30,2,0);String before=t.content();t.enabled(false);
        assertEquals(before,t.tree.copySelection(true));t.tree.paste("bad");t.tree.key(14,0,0);assertEquals(before,t.content());assertEquals(t.id,t.tree.focused());
        t.enabled(true);t.selectionClear();t.cursor(0);t.tree.key(15,0,0);assertTrue(t.content().startsWith("\t"));t.tree.key(28,0,13);assertTrue(t.content().startsWith("\t\n"));
        Button button=new Button(t.parent,"next");button.pack();t.tree.key(15,2,0);assertEquals(button.id,t.tree.focused());
    }
    @Test void wrapsTabsAndHorizontalViewportProduceBoundedRows() {
        Text t=text();TkOptions.configure(t,Map.of("width",6,"wrap","word"));t.insert(0,"abc defgh\nA\tZ");
        List<TkDisplayList.Item> paint=t.tree.render().items;
        assertEquals("abc ",paint.get(1).text);assertEquals("defgh",paint.get(2).text);
        TkOptions.configure(t,Map.of("wrap","none"));t.insert(0,"x".repeat(5000));
        assertTrue(t.tree.render().items.stream().allMatch(i->i.text.length()<30));t.moveto(true,1);
        assertTrue((Double)t.view(true)[0]>.9);t.cursor(t.index("2.0"));t.see(t.cursor);assertEquals(0.0,t.view(true)[0]);
        TkOptions.configure(t,Map.of("wrap","char"));assertEquals(0.0,t.view(true)[0]);
        for(TkDisplayList.Item i:t.tree.render().items)assertEquals(i.clip,i.clip.intersect(i.bounds));
        t.delete(0,t.length());t.insert(0,"a"+"\u0301".repeat(5000));assertDoesNotThrow(t.tree::render);
        t.delete(0,t.length());t.insert(0,"x".repeat(5000));t.place(new TkPlace(0,0,8192,200,0,0,null,null,TkPack.Anchor.NW,"inside"));
        assertDoesNotThrow(t.tree::render);
    }
    @Test void mouseDragAcrossLinesClipsSelectionAndLatestPlayerOwnsIt() {
        Text t=text();t.insert(0,"abcd\nefgh\nijkl");TkRect b=t.bounds();
        t.tree.pointerDown(1,b.x+4,b.y+4);t.tree.pointerDrag(1,b.x+20,b.y+36);assertEquals("abcd\nefgh\nij",t.selectedText());
        t.tree.pointerDown(2,b.x+12,b.y+4);t.tree.pointerDrag(1,b.x+100,b.y+60);assertFalse(t.selectionPresent());
        t.tree.pointerDrag(2,b.x+28,b.y+20);assertEquals("bcd\nefg",t.selectedText());
        assertTrue(t.tree.render().items.stream().filter(i->i.selectionWidth>0).count()>=2);
    }
    @Test void tenThousandLinesSendOnlyViewportAndPatchOnlyChangedRow() {
        Text t=text();StringBuilder lines=new StringBuilder();for(int i=0;i<10000;i++)lines.append("日志 ").append(i).append('\n');t.insert(0,lines.toString());
        TkDisplayList before=t.tree.render();assertTrue(before.items.size()<10);
        t.insert(t.index("2.0"),"new ");TkDisplayList after=t.tree.render();TkDisplayPatch patch=TkDisplayPatch.between(before,after);
        assertEquals(after,patch.apply(before));assertEquals(1,patch.changed.items.size());
        t.yscrollcommand=42;t.moveto(false,1);t.tree.render();assertTrue((Double)t.view(false)[0]>.99);
        assertTrue(t.tree.takeCommands().stream().anyMatch(c->c.callback==42));t.scroll(false,Integer.MIN_VALUE,false);assertEquals(0.0,t.view(false)[0]);
    }
    @Test void nbtRestoresLargeTextMarksSelectionViewportAndCallbacks() throws Exception {
        Text t=text();TkOptions.configure(t,Map.of("wrap","none"));t.insert(0,"abcdefgh中\n".repeat(10000));t.cursor(1234);t.selection(1200,1250);t.see(1234);t.xscrollcommand=17;t.yscrollcommand=18;t.enabled(false);t.tree.focus(t.id);
        TkDisplayList before=t.tree.render();NBTTagCompound saved=TkTreePersistence.save(t.tree);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();CompressedStreamTools.writeCompressed(saved,bytes);
        TkTree restored=TkTreePersistence.load(CompressedStreamTools.readCompressed(new java.io.ByteArrayInputStream(bytes.toByteArray())),320,240,()->{});
        Text r=(Text)restored.get(t.id);assertEquals(t.content(),r.content());assertEquals(t.cursor,r.cursor);assertEquals(t.selectedText(),r.selectedText());assertEquals(before,restored.render());assertEquals(18,r.yscrollcommand);assertFalse(r.enabled);
        NBTTagCompound corrupt=t.save();corrupt.setByteArray("content",new byte[]{(byte)0xff});assertThrows(IllegalArgumentException.class,()->text().load(corrupt));
    }
    @Test void largeClipboardAndTextRowsRoundtripWithSelection() {
        Text t=text();t.insert(0,"中".repeat(30000)+"\n".repeat(4000));t.selection(0,t.length());t.tree.focus(t.id);
        TkTarget target=new TkTarget(0,1,2,3,"text",1);ByteBuf buf=Unpooled.buffer();
        try {
            new TkPackets.CopyResult(target,1,t.selectedText()).toBytes(buf);TkPackets.CopyResult copy=new TkPackets.CopyResult();copy.fromBytes(buf);assertEquals(t.content(),copy.text);
            buf.clear();TkScene scene=new TkScene(320,240,0,"Text",true,t.tree.render());new TkPackets.SceneSync(target,-1,1,TkPackets.ALL,scene).toBytes(buf);
            TkPackets.SceneSync read=new TkPackets.SceneSync();read.fromBytes(buf);assertEquals(scene,read.scene);
            buf.clear();
            assertThrows(IllegalArgumentException.class,()->new TkPackets.CopyResult(target,1,"x".repeat(Text.MAX_BYTES+1)).toBytes(buf));
        }finally{buf.release();}
    }
}
