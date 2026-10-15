package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Builtins;
import net.mpy.vm.Frame;
import net.mpy.vm.HostFunction;
import net.mpy.vm.PyModule;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

/**
 * A VM with every thread parked costs the server nothing: the ticker path skips the tick (banking
 * its allowance), the async path sleeps the machine until the earliest deadline, and a queued
 * signal (which wakes the parked pullers) or that deadline drives it again.
 */
class IdleTickTest {
    static final class Host {
        int ticks;
        final ArrayDeque<Signal> signals = new ArrayDeque<>();
        final Machine machine = (Machine) Proxy.newProxyInstance(Machine.class.getClassLoader(),
                new Class<?>[] {Machine.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isRunning": return true;
                        case "isPaused": return false;
                        case "upTime": return ticks / 20.0;
                        case "components": return Map.of();
                        case "popSignal": return signals.poll();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        final MpyArchitecture architecture = new MpyArchitecture(machine);
        final Map<String, Object> globals = new HashMap<>();
        final Vm vm;

        Host(String source, boolean async) throws Exception {
            MpyModule code = MpyLoader.load(MpyCross.compile(source, "idle.py"), new QstrPool());
            PyModule computer = new PyModule("computer", "<host>", null);
            computer.ns.put("_signals", new PyObj.PyList());
            computer.ns.put("uptime", (HostFunction) args -> ticks / 20.0);
            computer.ns.put("_poll_signal", (HostFunction) args ->
                    MpyArchitecture.pollSignal((PyObj.PyList) computer.ns.get("_signals"), false, false, false));
            globals.put("computer", computer);
            // The architecture's clock: the machine's uptime in milliseconds.
            globals.put("__time_ms", (HostFunction) args -> (long) (ticks / 20.0 * 1000.0));
            vm = new Vm(code, globals);
            field("vm").set(architecture, vm);
            field("async").setBoolean(architecture, async);
            field("opsPerTick").setInt(architecture, 500);
            var install = MpyArchitecture.class.getDeclaredMethod("installComputerBootstrap", Map.class);
            install.setAccessible(true);
            install.invoke(architecture, globals);
            vm.start(new Frame(code.root));
        }

        /** The next game tick on the ticker path. */
        void tick() {
            ticks++;
            architecture.tickFromComponent();
        }

        long clockMs() {
            return (long) (ticks / 20.0 * 1000.0);
        }

        int banked() throws Exception {
            return field("bankedOps").getInt(architecture);
        }

        void signal(String name, Object... args) {
            signals.add(new Signal() {
                @Override public String name() { return name; }
                @Override public Object[] args() { return args; }
            });
            architecture.onSignal();
        }

        List<String> out() {
            List<String> lines = new ArrayList<>();
            for (Object line : ((PyObj.PyList) vm.globals.get("OUT")).items) lines.add(Builtins.str(line));
            return lines;
        }
    }

    @Test void aParkedVmSkipsTicksAndBanksTheirAllowance() throws Exception {
        Host host = new Host("""
                import time
                OUT = []
                time.sleep(0.5)
                OUT.append('woke')
                """, false);
        host.tick();
        assertTrue(host.vm.idle());
        long wake = host.vm.nextWakeMs();
        assertEquals(host.clockMs() + 500, wake);
        int banked = host.banked();
        int skipped = 0;
        while (host.clockMs() + 50 < wake) {
            host.tick();
            skipped++;
            assertTrue(host.vm.idle(), "tick " + host.ticks + " is skipped");
            assertEquals(Math.min(banked + 500 * skipped, 5000), host.banked(), "a skipped tick banks its allowance");
            assertEquals(List.of(), host.out());
        }
        assertEquals(9, skipped);
        host.tick();
        assertEquals(List.of("woke"), host.out());
        assertTrue(host.vm.isFinished());
    }

    @Test void aQueuedSignalDrivesAParkedPuller() throws Exception {
        Host host = new Host("""
                OUT = []
                OUT.append(computer.pullSignal(10))
                OUT.append(computer.pullSignal(0.1))
                OUT.append('done')
                """, false);
        host.tick();
        assertTrue(host.vm.idle());
        assertEquals(host.clockMs() + 10_000, host.vm.nextWakeMs());
        for (int i = 0; i < 3; i++) {
            host.tick();
            assertTrue(host.vm.idle());
        }
        host.signal("ping", "pong");
        host.tick();
        assertEquals(List.of("('ping', 'pong')"), host.out(), "the signal woke the puller");
        assertTrue(host.vm.idle(), "parked again for the second pull");
        assertEquals(host.clockMs() + 100, host.vm.nextWakeMs());
        for (int i = 0; i < 3; i++) host.tick();
        assertEquals(List.of("('ping', 'pong')", "None", "done"), host.out(), "the second pull timed out");
        assertTrue(host.vm.isFinished());
    }

    @Test void anAsyncVmSleepsTheMachineUntilItsEarliestDeadline() throws Exception {
        Host host = new Host("""
                import time
                OUT = []
                time.sleep(0.5)
                OUT.append('woke')
                computer.pullSignal()
                OUT.append('pulled')
                """, true);
        host.ticks = 1;
        ExecutionResult result = host.architecture.runThreaded(false);
        assertEquals(10, ((ExecutionResult.Sleep) result).ticks, "sleep until the deadline, 500 ms away");
        host.ticks = 11;
        result = host.architecture.runThreaded(false);
        assertEquals(List.of("woke"), host.out());
        assertEquals(Integer.MAX_VALUE, ((ExecutionResult.Sleep) result).ticks, "only a signal can wake the puller");
        assertTrue(host.banked() >= 4500, "the ticks slept through banked their allowance");
        host.signal("ping");
        host.ticks = 12;
        result = host.architecture.runThreaded(false);
        assertEquals(List.of("woke", "pulled"), host.out());
        assertTrue(host.vm.isFinished());
    }

    @Test void aRunnableThreadKeepsTheMachineAwake() throws Exception {
        Host host = new Host("""
                import time
                OUT = []
                def spinner():
                    yield
                    while True:
                        OUT.append('spin')
                        if len(OUT) > 3:
                            return
                __thread_start(spinner())
                time.sleep(10)
                """, true);
        host.ticks = 1;
        ExecutionResult result = host.architecture.runThreaded(false);
        assertEquals(1, ((ExecutionResult.Sleep) result).ticks, "a thread that computes keeps the machine stepping");
        host.ticks = 2;
        result = host.architecture.runThreaded(false);
        assertEquals(List.of("spin", "spin", "spin", "spin"), host.out());
        assertTrue(((ExecutionResult.Sleep) result).ticks > 1, "once it ended, the sleeper's deadline sets the pace");
    }

    private static Field field(String name) throws Exception {
        Field field = MpyArchitecture.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
