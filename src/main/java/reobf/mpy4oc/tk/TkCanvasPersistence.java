package reobf.mpy4oc.tk;

import java.util.*;
import net.minecraft.nbt.*;

/** Canvas display order, item identity and Python callback IDs survive a world save. */
final class TkCanvasPersistence {
    static void save(Canvas c, NBTTagCompound n) {
        n.setInteger("canvasNext", c.nextItem); NBTTagList list = new NBTTagList();
        for (Canvas.Item item : c.items.values()) {
            NBTTagCompound row = new NBTTagCompound(); TkVector v = item.vector;
            row.setInteger("id", item.id); row.setString("type", v.shape.name());
            row.setInteger("fill",v.fill);row.setInteger("outline",v.outline);row.setDouble("stroke",v.width);
            row.setString("text",item.text);row.setString("state",item.state);row.setString("anchor",item.anchor.name());
            NBTTagList coords=new NBTTagList();for(double d:v.coords())coords.appendTag(new NBTTagDouble(d));row.setTag("coords",coords);
            NBTTagList tags=new NBTTagList();for(String t:item.tags)tags.appendTag(new NBTTagString(t));row.setTag("tags",tags);list.appendTag(row);
        }
        n.setTag("canvasItems",list); NBTTagList bindings=new NBTTagList();
        for(Map.Entry<String,Map<String,Integer>> tag:c.tagBindings.entrySet())for(Map.Entry<String,Integer> entry:tag.getValue().entrySet()) {
            NBTTagCompound b=new NBTTagCompound();b.setString("selector",tag.getKey());b.setString("sequence",entry.getKey());b.setInteger("callback",entry.getValue());bindings.appendTag(b);
        }
        n.setTag("canvasBindings",bindings);
    }
    static void load(Canvas c, NBTTagCompound n) {
        NBTTagList list=n.getTagList("canvasItems",10);
        if(list.tagCount()>Canvas.MAX_ITEMS)throw new IllegalArgumentException("too many Canvas items");
        int maximum=0;
        for(int j=0;j<list.tagCount();j++) {
            NBTTagCompound row=list.getCompoundTagAt(j);int id=TkOptions.integer(row.getInteger("id"),1,Integer.MAX_VALUE-1);
            if(c.items.containsKey(id))throw new IllegalArgumentException("duplicate Canvas ID");maximum=Math.max(maximum,id);
            NBTTagList coords=row.getTagList("coords",6);if(coords.tagCount()>TkVector.MAX_COORDS)throw new IllegalArgumentException("too many Canvas coordinates");
            double[] points=new double[coords.tagCount()];for(int k=0;k<points.length;k++)points[k]=coords.func_150309_d(k);
            NBTTagList tags=row.getTagList("tags",8);if(tags.tagCount()>16)throw new IllegalArgumentException("too many Canvas tags");
            List<String> names=new ArrayList<>();for(int k=0;k<tags.tagCount();k++){String t=Canvas.tag(tags.getStringTagAt(k));if(names.contains(t))throw new IllegalArgumentException("duplicate tag");names.add(t);}
            String state=row.getString("state"),text=row.getString("text");TkFontMetrics.checkText(text);
            if(!Arrays.asList("normal","disabled","hidden").contains(state))throw new IllegalArgumentException("invalid Canvas state");
            TkVector v=new TkVector(TkVector.Shape.valueOf(row.getString("type")),points,row.getInteger("fill"),row.getInteger("outline"),row.getDouble("stroke"));
            if(v.shape!=TkVector.Shape.TEXT&&!text.isEmpty())throw new IllegalArgumentException("non-text Canvas item has text");
            c.items.put(id,new Canvas.Item(id,v,text,state,names,TkOptions.anchor(row.getString("anchor"))));
        }
        c.nextItem=n.getInteger("canvasNext");if(c.nextItem<=maximum)throw new IllegalArgumentException("invalid next Canvas ID");c.checkBudget(c.items);
        NBTTagList binds=n.getTagList("canvasBindings",10);if(binds.tagCount()>Canvas.MAX_BINDINGS)throw new IllegalArgumentException("too many Canvas bindings");
        for(int j=0;j<binds.tagCount();j++) {
            NBTTagCompound b=binds.getCompoundTagAt(j);String tag=Canvas.selector(b.getString("selector")),seq=b.getString("sequence");Canvas.validateSequence(seq);
            int callback=TkOptions.integer(b.getInteger("callback"),1,Integer.MAX_VALUE);
            Map<String,Integer> entries=c.tagBindings.computeIfAbsent(tag,k->new LinkedHashMap<>());
            if(entries.put(seq,callback)!=null)throw new IllegalArgumentException("duplicate Canvas binding");
        }
    }
    private TkCanvasPersistence() {}
}
