package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

/**
 * The VM's park/unpark pair, what every wait in the frozen library (time.sleep, a lock, pullSignal,
 * an idle event loop) is built on: the counterpart of the kernel wait CPython blocks in. A parked
 * thread holds no seat and spends no instruction until its deadline on the __time_ms clock, an
 * unpark, or (when it asked) a queued signal; an unpark before the park leaves a permit; the park
 * state is saved with the VM; a program being stopped or interrupted wakes its parked threads.
 */
class ParkTest {
    static final class Host {
        final Map<String, Object> globals = new HashMap<>();
        long clock;
        final Vm vm;

        Host(String source) {
            MpyModule main = MpyLoader.load(MpyCross.compile(source, "park_test.py"), new QstrPool());
            globals.put("__time_ms", (HostFunction) args -> clock);
            vm = new Vm(main, globals);
            vm.setCompiler((text, name, mode) -> MpyCross.compile(text, name));
            vm.start(new Frame(main.root));
        }

        /** One step with a budget of 1000 instructions: how many of them were spent. */
        int step() {
            int[] budget = {1000};
            vm.step(budget);
            return 1000 - budget[0];
        }

        void steps(int n) {
            for (int i = 0; i < n && !vm.isFinished(); i++) step();
        }

        List<String> out() {
            return out(vm);
        }

        static List<String> out(Vm vm) {
            List<String> lines = new ArrayList<>();
            for (Object line : ((PyObj.PyList) vm.globals.get("OUT")).items) lines.add(Builtins.str(line));
            return lines;
        }
    }

    @Test void theMainFrameParksUntilItsDeadlineSpendingNothing() {
        Host host = new Host("""
                OUT = []
                __park(__time_ms() + 100)
                OUT.append(__time_ms())
                """);
        host.step();
        assertTrue(host.vm.idle());
        assertEquals(100, host.vm.nextWakeMs());
        assertEquals(List.of(), host.out());
        host.clock = 50;
        assertEquals(0, host.step(), "a parked VM spends no instruction");
        assertTrue(host.vm.idle());
        host.clock = 100;
        assertTrue(host.step() > 0);
        assertEquals(List.of("100"), host.out());
        assertTrue(host.vm.isFinished());
        assertFalse(host.vm.idle(), "a finished VM has nothing to wait for");
    }

    @Test void anUnparkBeforeTheParkLeavesAPermit() {
        Host host = new Host("""
                OUT = []
                OUT.append(__unpark(None))
                __park(None)
                OUT.append('through')
                __park(None)
                OUT.append('woken')
                """);
        host.step();
        assertEquals(List.of("True", "through"), host.out());
        assertTrue(host.vm.idle());
        assertEquals(Long.MAX_VALUE, host.vm.nextWakeMs(), "no deadline: only an unpark or a signal ends the wait");
        host.clock = 1_000_000;
        assertEquals(0, host.step());
        assertTrue(host.vm.unpark(null));
        assertFalse(host.vm.idle());
        host.step();
        assertEquals(List.of("True", "through", "woken"), host.out());
        assertTrue(host.vm.isFinished());
    }

    @Test void threadsParkAndUnparkOneAnother() {
        Host host = new Host("""
                OUT = []
                def worker():
                    yield
                    OUT.append('worker parks')
                    __park(None)
                    OUT.append('worker woke')
                    __unpark(None)
                gen = worker()
                __thread_start(gen)
                OUT.append('main parks')
                __park(__time_ms() + 1000)
                OUT.append('main woke at ' + str(__time_ms()))
                """);
        host.steps(5);
        assertEquals(List.of("main parks", "worker parks"), host.out());
        assertTrue(host.vm.idle());
        assertEquals(1000, host.vm.nextWakeMs());
        host.clock = 50;
        assertEquals(0, host.step());
        assertTrue(host.vm.unpark(host.vm.globals.get("gen")));
        assertFalse(host.vm.idle());
        host.steps(5);
        assertEquals(List.of("main parks", "worker parks", "worker woke", "main woke at 50"), host.out());
        assertTrue(host.vm.isFinished());
        assertFalse(host.vm.unpark(host.vm.globals.get("gen")), "a finished thread cannot be woken");
    }

    @Test void aSignalWakesOnlyTheThreadsThatAskedForOne() {
        Host host = new Host("""
                OUT = []
                def worker():
                    yield
                    __park(None)
                    OUT.append('worker woke')
                    __unpark(None)
                gen = worker()
                __thread_start(gen)
                __park(None, True)
                OUT.append('main woke on a signal')
                __park(None)
                OUT.append('main woke again')
                """);
        host.steps(5);
        assertTrue(host.vm.idle());
        host.vm.wakeSignalWaiters();
        assertFalse(host.vm.idle());
        host.steps(5);
        assertEquals(List.of("main woke on a signal"), host.out());
        assertTrue(host.vm.idle());
        host.vm.wakeSignalWaiters();
        assertTrue(host.vm.idle(), "neither thread asked for a signal now");
        assertEquals(0, host.step());
        assertTrue(host.vm.unpark(host.vm.globals.get("gen")));
        host.steps(5);
        assertEquals(List.of("main woke on a signal", "worker woke", "main woke again"), host.out());
        assertTrue(host.vm.isFinished());
    }

    @Test void parkStateSurvivesASnapshot() {
        Host host = new Host("""
                OUT = []
                def worker():
                    yield
                    __park(None)
                    OUT.append('worker woke')
                    __unpark(None)
                gen = worker()
                __thread_start(gen)
                __park(__time_ms() + 100)
                OUT.append('main woke at ' + str(__time_ms()))
                __park(None)
                OUT.append('done')
                """);
        host.steps(5);
        assertTrue(host.vm.idle());
        assertEquals(100, host.vm.nextWakeMs());
        byte[] snapshot = host.vm.saveSnapshot();
        Map<String, Object> globals = new HashMap<>();
        long[] clock = {0};
        globals.put("__time_ms", (HostFunction) args -> clock[0]);
        Vm restored = Vm.fromSnapshot(snapshot, globals, HostRegistry.fromGlobals(globals));
        assertTrue(restored.idle(), "both threads are still parked");
        assertEquals(100, restored.nextWakeMs(), "the deadline is saved");
        assertEquals(0, 1000 - step(restored, 1000));
        clock[0] = 100;
        for (int i = 0; i < 5; i++) step(restored, 1000);
        assertEquals(List.of("main woke at 100"), Host.out(restored));
        assertTrue(restored.idle());
        assertEquals(Long.MAX_VALUE, restored.nextWakeMs());
        assertTrue(restored.unpark(restored.globals.get("gen")));
        for (int i = 0; i < 5; i++) step(restored, 1000);
        assertEquals(List.of("main woke at 100", "worker woke", "done"), Host.out(restored));
        assertTrue(restored.isFinished());
        assertEquals(List.of(), host.out(), "the original VM was left alone");
    }

    private static int step(Vm vm, int budget) {
        int[] remaining = {budget};
        vm.step(remaining);
        return remaining[0];
    }

    @Test void timeSleepParksUntilItsDeadline() {
        Host host = new Host("""
                import time
                OUT = []
                time.sleep(0.1)
                OUT.append(time.ticks_ms())
                time.sleep(0)
                OUT.append('zero')
                try:
                    time.sleep(-1)
                except ValueError as error:
                    OUT.append(str(error))
                """);
        host.step();
        assertTrue(host.vm.idle());
        assertEquals(100, host.vm.nextWakeMs());
        host.clock = 99;
        assertEquals(0, host.step());
        host.clock = 100;
        host.steps(5);
        assertEquals(List.of("100", "zero", "sleep length must be non-negative"), host.out());
        assertTrue(host.vm.isFinished());
    }

    @Test void aKeyboardInterruptWakesTheParkedForegroundRunner() {
        Host host = new Host("""
                import _program
                OUT = []
                _program.set_current('cmd:test')
                ns = {'OUT': OUT, '_program': _program}
                scope = __program_foreground(ns)
                try:
                    exec_sandbox('try:\\n    _program.park(None)\\nexcept KeyboardInterrupt:\\n    OUT.append("interrupted")\\n', ns)
                finally:
                    __program_leave_foreground(scope)
                OUT.append('done')
                """);
        host.steps(5);
        assertTrue(host.vm.idle());
        assertEquals(List.of(), host.out());
        assertTrue(host.vm.requestKeyboardInterrupt(false));
        assertFalse(host.vm.idle(), "the interrupt wakes the parked runner");
        host.steps(5);
        assertEquals(List.of("interrupted", "done"), host.out());
        assertTrue(host.vm.isFinished());
    }

    @Test void stoppingAProgramWakesItsParkedThreads() {
        Host host = new Host("""
                import _program, threading
                OUT = []
                _program.set_current('job')
                def worker():
                    try:
                        _program.park(None)
                        OUT.append('not here')
                    except SystemExit:
                        OUT.append('stopped')
                thread = threading.Thread(target=worker)
                thread.start()
                __park(__time_ms() + 100)      # the test moves the clock once the worker is parked
                _program.set_current(None)
                _program.stop('job')
                thread.join()
                OUT.append('joined ' + str(thread.is_alive()))
                """);
        host.steps(20);
        assertEquals(List.of(), host.out());
        assertTrue(host.vm.idle());
        host.clock = 100;
        host.steps(50);
        assertEquals(List.of("stopped", "joined False"), host.out());
        assertTrue(host.vm.isFinished());
    }
}
