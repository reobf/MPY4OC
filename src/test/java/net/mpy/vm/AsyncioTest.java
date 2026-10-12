package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * asyncio follows CPython 3.12: each scenario in src/test/resources/asyncio runs here and in CPython
 * (tools/asyncio-golden.py wrote the .out files), and the lines it collects in OUT must be the same.
 *
 * The VM runs as in a game: one step per 50 ms tick on a clock of its own, and time.sleep yields
 * ticks until the clock passes its deadline (the architecture's bootstrap). The scenarios sleep in
 * multiples of 0.05 s, so the order of events does not depend on how fast either side runs.
 */
class AsyncioTest {
    static final Path DIR = Path.of("src/test/resources/asyncio");
    static final int TICK_MS = 50;

    @ParameterizedTest
    @ValueSource(strings = {"basics", "cancel", "errors", "locks", "loop", "queues", "threads", "wait"})
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
        Vm[] vm = new Vm[1];
        globals.put("__time_ms", (HostFunction) args -> clock[0]);
        globals.put("__tick", (HostFunction) args -> { vm[0].requestYield(); return PyObj.NONE; });
        vm[0] = new Vm(main, globals);
        vm[0].setCompiler((text, name, mode) -> MpyCross.compile(text, name));
        vm[0].execCell("test-bootstrap", MpyCross.compile(String.join("\n",
                "def _test_sleep(ms):",
                "    import _program",
                "    _d = __time_ms() + ms",
                "    while __time_ms() < _d:",
                "        _program.check()",
                "        __tick()",
                ""), "bootstrap.py"));
        globals.put("__time_sleep", globals.get("_test_sleep"));
        vm[0].start(new Frame(main.root));
        long giveUp = System.currentTimeMillis() + 60_000;
        while (!vm[0].isFinished()) {
            try {
                vm[0].step(new int[] {1 << 20});
            } catch (PyException error) {
                throw new AssertionError(error.getMessage() + "\n" + error.pyTraceback() + "\nOUT so far: " + out(vm[0]), error);
            }
            clock[0] += TICK_MS;
            if (System.currentTimeMillis() > giveUp) fail("timed out at " + clock[0] + " ms; OUT so far: " + out(vm[0]));
        }
        return out(vm[0]);
    }

    static List<String> out(Vm vm) {
        List<String> lines = new ArrayList<>();
        Object out = vm.globals.get("OUT");
        if (out instanceof PyObj.PyList) for (Object line : ((PyObj.PyList) out).items) lines.add(Builtins.str(line));
        return lines;
    }
}
