package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import li.cil.oc.api.machine.Machine;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Frame;
import net.mpy.vm.PyModule;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class TickerLifecycleTest {
    static final class Host {
        boolean running, paused;
        int componentReads;
        final Machine machine = (Machine) Proxy.newProxyInstance(Machine.class.getClassLoader(),
                new Class<?>[] {Machine.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isRunning": return running;
                        case "isPaused": return paused;
                        case "upTime": return 0.0;
                        case "components": componentReads++; return Map.of();
                        case "popSignal": return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        final MpyArchitecture architecture = new MpyArchitecture(machine);
    }

    @Test void inventoryTicksCannotBootAnOffOrClosedMachine() throws Exception {
        Host host = new Host();
        host.architecture.tickFromComponent();
        host.architecture.close();
        host.architecture.tickFromComponent();
        assertEquals(0, host.componentReads, "an off case must not read BIOS/disks");
        assertNull(field("vm").get(host.architecture));
        assertNull(field("pendingError").get(host.architecture));
    }

    @Test void pausedVmDoesNotAdvanceOrConsumeTheResumeTick() throws Exception {
        Host host = new Host(); host.running = true; host.paused = true;
        MpyModule code = MpyLoader.load(MpyCross.compile("ran = True\n", "ticker.py"), new QstrPool());
        Map<String, Object> globals = new HashMap<>();
        PyModule computer = new PyModule("computer", "<host>", null);
        computer.ns.put("_signals", new PyObj.PyList()); globals.put("computer", computer);
        Vm vm = new Vm(code, globals); vm.start(new Frame(code.root));
        field("vm").set(host.architecture, vm);
        host.architecture.tickFromComponent();
        assertFalse(vm.globals.containsKey("ran"));
        host.paused = false;
        host.architecture.tickFromComponent();
        assertEquals(true, vm.globals.get("ran"));
        assertTrue(vm.isFinished());
    }

    private static Field field(String name) throws Exception {
        Field field = MpyArchitecture.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
