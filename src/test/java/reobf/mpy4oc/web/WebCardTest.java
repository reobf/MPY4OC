package reobf.mpy4oc.web;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import li.cil.oc.api.API;
import li.cil.oc.api.machine.*;
import li.cil.oc.api.network.*;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.main.Config;
import reobf.mpy4oc.main.item.ItemWebCard;

class WebCardTest {
    static Object stub(Class<?> type,String address) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getName().equals("address"))return address;
            if(m.getName().equals("equals"))return p==a[0];
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("toString"))return address;
            if(m.getReturnType().isInterface())return stub(m.getReturnType(),address);
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==int.class)return 0;
            if(m.getReturnType()==double.class)return 0d;
            return null;
        });
    }
    static Arguments args(Object... values) {
        return (Arguments)Proxy.newProxyInstance(Arguments.class.getClassLoader(),new Class<?>[]{Arguments.class},(p,m,a)->{
            if(m.getName().equals("count"))return values.length;
            if(m.getName().equals("toArray"))return values;
            if(m.getName().startsWith("check"))return values[(Integer)a[0]];
            if(m.getName().startsWith("opt"))return (Integer)a[0]<values.length?values[(Integer)a[0]]:a[1];
            return false;
        });
    }
    @Test void credentialsPersistStopReleasesAndRestoreReportsConflict() throws Exception {
        Field field=API.class.getField("network");Object previous=field.get(null);
        int port=Config.webPort;Config.webPort=0;
        field.set(null,stub(field.getType(),"card"));
        try {
            Context context=(Context)stub(Context.class,"computer");
            ItemWebCard.APIEnv card=new ItemWebCard.APIEnv(null);
            card.start(context,args("custom-id","plain-password"));
            assertArrayEquals(new Object[]{"custom-id","plain-password"},card.getCredentials(context,args()));
            NBTTagCompound saved=new NBTTagCompound();card.save(saved);
            assertEquals("plain-password",saved.getString("password"));assertTrue(saved.getBoolean("active"));
            card.stop(context,args());assertNull(WebServices.authenticate(WebGatewayTest.basic("custom-id:plain-password")));
            NBTTagCompound stopped=new NBTTagCompound();card.save(stopped);assertFalse(stopped.getBoolean("active"));assertEquals("custom-id",stopped.getString("user"));
            WebServices.Service occupied=WebServices.register("custom-id","other");
            ItemWebCard.APIEnv restored=new ItemWebCard.APIEnv(null);restored.load(saved);restored.update();
            Map<?,?> state=(Map<?,?>)restored.status(context,args())[0];assertEquals(false,state.get("active"));assertTrue(state.get("error").toString().contains("already in use"));
            occupied.close();restored.start(context,args());assertNotNull(WebServices.authenticate(WebGatewayTest.basic("custom-id:plain-password")));
            Message shutdown=(Message)Proxy.newProxyInstance(Message.class.getClassLoader(),new Class<?>[]{Message.class},(p,m,a)->m.getName().equals("name")?"computer.stopped":m.getName().equals("source")?context.node():null);
            restored.onMessage(shutdown);assertNull(WebServices.authenticate(WebGatewayTest.basic("custom-id:plain-password")));
            assertArrayEquals(new Object[]{"custom-id","plain-password"},restored.getCredentials(context,args()));
            ItemWebCard.APIEnv early=new ItemWebCard.APIEnv(null);early.load(saved);
            assertNotNull(early.open(context,args("/"))[0]); // Python resumed before first update().
            early.onDisconnect(early.node());assertNull(WebServices.authenticate(WebGatewayTest.basic("custom-id:plain-password")));
        } finally {ItemWebCard.shutdown();Config.webPort=port;field.set(null,previous);}
    }
}
