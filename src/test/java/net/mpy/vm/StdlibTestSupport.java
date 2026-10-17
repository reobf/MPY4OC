package net.mpy.vm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import static org.junit.jupiter.api.Assertions.*;

final class StdlibTestSupport {
    static Vm start(String source, String... libraries) throws Exception {
        Map<String, byte[]> modules = new HashMap<>();
        Path lib = Path.of("src/main/resources/assets/mpy4oc/mpyos/lib");
        for (String name : libraries)
            modules.put(name, MpyCross.compile(Files.readString(lib.resolve(name + ".py")), name + ".py"));
        var module = MpyLoader.load(MpyCross.compile(source, "stdlib_test.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.setModuleFinder(modules::get);
        vm.start(new Frame(module.root));
        return vm;
    }

    static void finish(Vm vm) {
        try {
            for (int i = 0; i < 1000 && !vm.isFinished(); i++) vm.step(new int[] {10_000});
        } catch (PyException error) {
            throw new AssertionError(error.getMessage() + "\n" + error.pyTraceback(), error);
        }
        assertTrue(vm.isFinished(), "script exceeded 10M instructions");
    }

    static Vm run(String source, String... libraries) throws Exception {
        Vm vm = start(source, libraries);
        finish(vm);
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
        return vm;
    }

    static void golden(String name) throws Exception {
        run(Files.readString(Path.of("src/test/resources/stdlib-" + name + "-golden.py")));
    }
}
