package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.util.*;
import appeng.crafting.CraftingLink;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.*;
import com.google.common.collect.ImmutableSet;
import li.cil.oc.api.machine.Value;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.mpy.vm.*;
import org.junit.jupiter.api.Test;

class AeCpuCancellationTest {
    public static class Cpu extends CraftingCPUCluster {
        int canceled;
        boolean busy=true, active=true;
        Cpu() { super(new WorldCoord(0,0,0),new WorldCoord(0,0,0)); job("one"); }
        void job(String id) {
            var tag=AeCraftingCancellationTest.tag(false,false);tag.setString("CraftID",id);
            myLastLink=new CraftingLink(tag,this);busy=true;
        }
        @Override public boolean isBusy() {return busy;}
        @Override public boolean isActive() {return active;}
        @Override public void cancel() {canceled++;myLastLink.cancel();myLastLink=null;busy=false;}
    }
    static Cpu cpu() throws Exception {
        // The cluster constructor boots AE's item/recipe registries, requiring a
        // Forge LaunchClassLoader. Exercise real OC wrappers/AE links without a world.
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var cpu=(Cpu)((sun.misc.Unsafe)field.get(null)).allocateInstance(Cpu.class);
        cpu.active=true;cpu.job("one");return cpu;
    }
    public static class Controller extends TileEntity implements IGridProxyable {
        boolean connected=true;
        final Cpu cpu;
        Controller(Cpu cpu) {this.cpu=cpu;}
        @Override public AENetworkProxy getProxy() {
            var grid=(ICraftingGrid)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ICraftingGrid.class},(p,m,a)->m.getName().equals("getCpus")?(connected?ImmutableSet.of(cpu):ImmutableSet.of()):null);
            return new AENetworkProxy(this,"test",null,false) {
                @Override public ICraftingGrid getCrafting() {return grid;}
            };
        }
        @Override public DimensionalCoord getLocation() {return null;}
        @Override public void gridChanged() {}
        @Override public IGridNode getGridNode(ForgeDirection direction) {return null;}
        @Override public AECableType getCableConnectionType(ForgeDirection direction) {return AECableType.NONE;}
        @Override public void securityBreak() {}
    }
    static Value value(Cpu cpu,Controller controller) throws Exception {
        var type=Class.forName("li.cil.oc.integration.appeng.NetworkControl$Cpu");
        var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);var value=(Value)ctor.newInstance();
        for(var entry:java.util.Map.of("cpu",cpu,"controller",controller).entrySet()) {
            var field=type.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(value,entry.getValue());
        }
        return value;
    }
    @Test void cancelsOnlyTheConfirmedLiveLinkIncludingExternalJobs() throws Exception {
        var cpu=cpu();var controller=new Controller(cpu);var value=value(cpu,controller);
        assertArrayEquals(new Object[]{"one"},AeCpuCancellation.invoke(value,"getJobId",new Object[0]));
        var old=cpu.getLastCraftingLink();cpu.job("two");
        assertEquals(false,AeCpuCancellation.invoke(value,"cancelJob",new Object[]{"one"})[0]);
        assertEquals(0,cpu.canceled);assertFalse(old.isCanceled());
        var current=cpu.getLastCraftingLink();
        assertArrayEquals(new Object[]{true},AeCpuCancellation.invoke(value,"cancelJob",new Object[]{"two"}));
        assertEquals(1,cpu.canceled);assertTrue(current.isCanceled());
        assertEquals(false,AeCpuCancellation.invoke(value,"cancelJob",new Object[]{"two"})[0]);
        assertArrayEquals(new Object[]{null},AeCpuCancellation.invoke(value,"getJobId",new Object[0]));
    }
    @Test void inactiveDisconnectedFinishedAndUnresolvedSavedHandlesFailClosed() throws Exception {
        var cpu=cpu();var controller=new Controller(cpu);var value=value(cpu,controller);
        controller.connected=false;assertThrows(IllegalStateException.class,()->AeCpuCancellation.invoke(value,"cancelJob",new Object[]{"one"}));
        controller.connected=true;cpu.active=false;assertThrows(IllegalStateException.class,()->AeCpuCancellation.invoke(value,"cancelJob",new Object[]{"one"}));
        cpu.active=true;cpu.busy=false;assertEquals(false,AeCpuCancellation.invoke(value,"cancelJob",new Object[]{"one"})[0]);
        var field=value.getClass().getDeclaredField("cpu");field.setAccessible(true);field.set(value,null);
        assertThrows(IllegalStateException.class,()->AeCpuCancellation.invoke(value,"getJobId",new Object[0]));
        assertEquals(0,cpu.canceled);
    }
    @Test void proxyResolverAndSynchronizedCheckpointExposeBothMethods() throws Exception {
        var cpu=cpu();var value=value(cpu,new Controller(cpu));var arch=new MpyArchitecture(null);
        var proxy=(PyModule)arch.toPy(value);
        assertEquals("one",((HostFunction)proxy.ns.get("getJobId")).call(new Object[0]));
        var resolver=MpyArchitecture.class.getDeclaredMethod("resolveValueMethod",String.class);resolver.setAccessible(true);
        assertNotNull(resolver.invoke(arch,"value.0.cancelJob"));
        var h=TkPythonTest.shellHost("assert cpu.cancelJob('one')\npassed = True\n");h.vm.globals.put("cpu",proxy);
        for(var entry:java.util.Map.of("async",true,"inVmStep",true,"vm",h.vm).entrySet()) {
            var field=MpyArchitecture.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(arch,entry.getValue());
        }
        h.vm.step(new int[]{100000});assertEquals(0,cpu.canceled);assertFalse(h.vm.isFinished());
        var phase=MpyArchitecture.class.getDeclaredField("phase");phase.setAccessible(true);
        phase.set(arch,java.util.Arrays.stream(phase.getType().getEnumConstants()).filter(v->v.toString().equals("SYNC")).findFirst().orElseThrow());
        h.vm.step(new int[]{100000});assertTrue(h.vm.isFinished());assertEquals(true,h.vm.globals.get("passed"));assertEquals(1,cpu.canceled);
    }
}
