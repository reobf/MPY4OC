package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * threading follows CPython 3.12: each scenario in src/test/resources/threading runs here and in
 * CPython (tools/asyncio-golden.py wrote the .out files), and the lines it collects in OUT must be
 * the same. Lock is the one wait built on the VM's park directly; RLock, Condition, Semaphore,
 * Event, Barrier and Timer are CPython's own code over it, and join() its _tstate_lock.
 *
 * The VM runs as in a game: one step per 50 ms tick on a clock of its own, with the frozen
 * time.sleep (a park until the deadline). A thread says nothing before it has slept, so the order
 * of events is the same under CPython's real threads and here.
 */
class ThreadingTest {
    static final Path DIR = Path.of("src/test/resources/threading");
    static final int TICK_MS = 50;

    @ParameterizedTest
    @ValueSource(strings = {"threads", "locks", "condition", "events"})
    void matchesCPython(String scenario) throws Exception {
        String source = Files.readString(DIR.resolve(scenario + ".py"), StandardCharsets.UTF_8);
        String golden = Files.readString(DIR.resolve(scenario + ".out"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> expected = Arrays.asList(golden.substring(0, golden.length() - (golden.endsWith("\n") ? 1 : 0)).split("\n", -1));
        assertEquals(expected, run(scenario + ".py", source));
    }

    static List<String> run(String filename, String source) throws Exception {
        MpyModule main = MpyLoader.load(MpyCross.compile(source, filename), new QstrPool());
        Map<String, Object> globals = new HashMap<>();
        long[] clock = {0};
        globals.put("__time_ms", (HostFunction) args -> clock[0]);
        Vm vm = new Vm(main, globals);
        vm.setCompiler((text, name, mode) -> MpyCross.compile(text, name));
        vm.start(new Frame(main.root));
        long giveUp = System.currentTimeMillis() + 60_000;
        while (!vm.isFinished()) {
            try {
                vm.step(new int[] {1 << 20});
            } catch (PyException error) {
                throw new AssertionError(error.getMessage() + "\n" + error.pyTraceback() + "\nOUT so far: " + AsyncioTest.out(vm), error);
            }
            clock[0] += TICK_MS;
            if (System.currentTimeMillis() > giveUp) fail("timed out at " + clock[0] + " ms; OUT so far: " + AsyncioTest.out(vm));
        }
        return AsyncioTest.out(vm);
    }
}
