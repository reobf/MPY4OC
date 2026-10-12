package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A with statement's __exit__ written in Python runs as a frame of its own, so it can wait across
 * ticks (asyncio.run's `with Runner()` closes its loop there, and closing may wait for threads or
 * for cancelled tasks to clean up). Each way out of the block is covered: falling out, return and
 * break (an unwind), an exception it lets through and one it swallows -- and a snapshot taken while
 * __exit__ waits resumes the rest of the instruction.
 */
class WithExitTest {
    static final String PROGRAM = String.join("\n",
            "log = []",
            "class Waits:",
            "    def __init__(self, name, swallow=False):",
            "        self.name = name",
            "        self.swallow = swallow",
            "    def __enter__(self):",
            "        return self",
            "    def __exit__(self, kind, error, trace):",
            "        log.append(self.name + ' waits')",
            "        deadline = now() + 3",
            "        while now() < deadline:",
            "            tick()",
            "        log.append(self.name + ' ' + ('-' if kind is None else repr(error)))",
            "        return self.swallow",
            "def returns():",
            "    with Waits('return'):",
            "        return 'returned'",
            "def breaks():",
            "    for i in range(5):",
            "        with Waits('break ' + str(i)):",
            "            if i == 1:",
            "                break",
            "    return i",
            "with Waits('plain'):",
            "    log.append('inside')",
            "log.append(returns())",
            "log.append(breaks())",
            "try:",
            "    with Waits('raise'):",
            "        raise KeyError('k')",
            "except KeyError:",
            "    log.append('re-raised')",
            "with Waits('swallow', True):",
            "    raise ValueError('v')",
            "log.append('after swallow')",
            "");

    static final List<String> EXPECTED = Arrays.asList(
            "inside", "plain waits", "plain -",
            "return waits", "return -", "returned",
            "break 0 waits", "break 0 -", "break 1 waits", "break 1 -", "1",
            "raise waits", "raise KeyError('k')", "re-raised",
            "swallow waits", "swallow ValueError('v')", "after swallow");

    /** A VM whose clock moves one unit per step; tick() ends the step. */
    static final class Host {
        final Map<String, Object> globals = new HashMap<>();
        final Vm[] vm = new Vm[1];
        long clock;
        int steps;
        Host() {
            globals.put("now", (HostFunction) args -> clock);
            globals.put("tick", (HostFunction) args -> { vm[0].requestYield(); return PyObj.NONE; });
        }
        void step() {
            vm[0].step(new int[] {1 << 16});     // runs until tick() ends the step
            clock++;
            steps++;
        }
        List<String> log() {
            List<String> lines = new ArrayList<>();
            Object log = vm[0].globals.get("log");      // not there before the first step
            if (log != null) for (Object item : ((PyObj.PyList) log).items) lines.add(Builtins.str(item));
            return lines;
        }
    }

    static Host start() {
        Host host = new Host();
        MpyModule module = MpyLoader.load(MpyCross.compile(PROGRAM, "with_exit.py"), new QstrPool());
        host.vm[0] = new Vm(module, host.globals);
        host.vm[0].start(new Frame(module.root));
        return host;
    }

    @Test
    void exitWaitsAcrossTicksOnEveryWayOut() {
        Host host = start();
        while (!host.vm[0].isFinished() && host.steps < 10_000) host.step();
        assertTrue(host.vm[0].isFinished());
        assertEquals(EXPECTED, host.log());
        assertEquals(6 * 3 + 1, host.steps, "each of the 6 exits waited 3 ticks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain waits", "return waits", "break 1 waits", "raise waits", "swallow waits"})
    void snapshotTakenInsideExitResumesTheInstruction(String waiting) {
        Host host = start();
        while (!host.log().contains(waiting) && host.steps < 10_000) host.step();
        assertTrue(host.log().contains(waiting));
        assertFalse(host.vm[0].isFinished());
        byte[] saved = host.vm[0].saveSnapshot();

        Host restored = new Host();
        restored.clock = host.clock;
        restored.vm[0] = Vm.fromSnapshot(saved, restored.globals, HostRegistry.fromGlobals(restored.globals));
        while (!restored.vm[0].isFinished() && restored.steps < 10_000) restored.step();
        assertTrue(restored.vm[0].isFinished());
        assertEquals(EXPECTED, restored.log());
    }
}
