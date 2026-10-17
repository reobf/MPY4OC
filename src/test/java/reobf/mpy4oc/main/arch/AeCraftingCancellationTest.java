package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import li.cil.oc.api.machine.Value;
import net.minecraft.nbt.NBTTagCompound;
import net.mpy.vm.HostFunction;
import net.mpy.vm.PyModule;
import org.junit.jupiter.api.Test;

class AeCraftingCancellationTest {
    static Value status() throws Exception {
        var ctor=Class.forName("li.cil.oc.integration.appeng.NetworkControl$CraftingStatus").getDeclaredConstructor();
        ctor.setAccessible(true);return (Value)ctor.newInstance();
    }
    static void link(Value status, ICraftingLink link) throws Exception {
        status.getClass().getMethod("setLink",ICraftingLink.class).invoke(status,link);
    }
    static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()) {
            case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);default -> null;
        }));
    }
    static NBTTagCompound tag(boolean requester, boolean done) {
        var tag=new NBTTagCompound();tag.setBoolean("req",requester);tag.setBoolean("done",done);tag.setString("CraftID","job-one");return tag;
    }
    @Test void realAeLinkCancelsBothEndsOfOnlyItsOwnNexus() throws Exception {
        var request=new CraftingLink(tag(true,false),stub(ICraftingRequester.class));
        var cpu=new CraftingLink(tag(false,false),stub(ICraftingCPU.class));
        var other=new CraftingLink(tag(false,false),stub(ICraftingCPU.class));
        var nexus=new CraftingLinkNexus("job-one");request.setNexus(nexus);cpu.setNexus(nexus);
        var value=status();link(value,request);
        assertArrayEquals(new Object[]{true},AeCraftingCancellation.cancel(value));
        assertTrue(request.isCanceled());assertTrue(cpu.isCanceled());assertFalse(other.isCanceled());
        assertArrayEquals(new Object[]{true},AeCraftingCancellation.cancel(value));
    }
    @Test void calculatingCompletedFailedAndRestoredStatusesNeverCancelAnotherJob() throws Exception {
        var value=status();
        assertArrayEquals(new Object[]{false,"computing"},AeCraftingCancellation.cancel(value));
        var done=new CraftingLink(tag(true,true),stub(ICraftingRequester.class));link(value,done);
        assertArrayEquals(new Object[]{false,"done"},AeCraftingCancellation.cancel(value));assertFalse(done.isCanceled());
        var saved=new NBTTagCompound();value.save(saved);var restored=status();restored.load(saved);
        assertArrayEquals(new Object[]{false,"no link"},AeCraftingCancellation.cancel(restored));
        var failed=status();failed.getClass().getMethod("fail",String.class).invoke(failed,"missing materials");
        assertArrayEquals(new Object[]{false,"no link"},AeCraftingCancellation.cancel(failed));
    }
    @Test void pythonValueProxyAndRestoredHostResolverExposeExactLinkCancellation() throws Exception {
        var value=status();var request=new CraftingLink(tag(true,false),stub(ICraftingRequester.class));link(value,request);
        var architecture=new MpyArchitecture(null);
        var wrapped=(PyModule)architecture.toPy(value);
        assertTrue(wrapped.ns.get("cancel") instanceof HostFunction);
        var resolve=MpyArchitecture.class.getDeclaredMethod("resolveValueMethod",String.class);resolve.setAccessible(true);
        var cancel=(HostFunction)resolve.invoke(architecture,"value.0.cancel");
        assertEquals(true,cancel.call(new Object[0]));assertTrue(request.isCanceled());
        assertEquals(true,((HostFunction)wrapped.ns.get("cancel")).call(new Object[0]));
    }
    @Test void workerPhaseDefersCancellationUntilSynchronizedPhase() throws Exception {
        var value=status();int[] calls={0};
        var request=(ICraftingLink)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ICraftingLink.class},(p,m,a)->switch(m.getName()) {
            case "cancel" -> {calls[0]++;yield null;}
            case "isCanceled" -> calls[0]>0;case "isDone" -> false;default -> null;
        });
        link(value,request);
        var h=TkPythonTest.shellHost("assert status.cancel()\npassed = True\n");
        var architecture=new MpyArchitecture(null);
        h.vm.globals.put("status",architecture.toPy(value));
        for(var entry:java.util.Map.of("async",true,"inVmStep",true,"vm",h.vm).entrySet()) {
            var field=MpyArchitecture.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(architecture,entry.getValue());
        }
        h.vm.step(new int[]{100000});assertEquals(0,calls[0]);assertFalse(h.vm.isFinished());
        var phase=MpyArchitecture.class.getDeclaredField("phase");phase.setAccessible(true);
        phase.set(architecture,java.util.Arrays.stream(phase.getType().getEnumConstants()).filter(v->v.toString().equals("SYNC")).findFirst().orElseThrow());
        h.vm.step(new int[]{100000});assertTrue(h.vm.isFinished());assertEquals(true,h.vm.globals.get("passed"));assertEquals(1,calls[0]);
    }
}
