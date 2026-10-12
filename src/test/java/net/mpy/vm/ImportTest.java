package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

/** Import details CPython has: a failed submodule is not left on its package; star import follows __all__. */
class ImportTest {
    private static Vm run(String program, String... files) {
        Map<String, byte[]> compiled = new HashMap<>();
        for (int i = 0; i < files.length; i += 2) compiled.put(files[i], MpyCross.compile(files[i + 1], files[i] + ".py"));
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "import_test.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.setModuleFinder(compiled::get);
        vm.start(new Frame(module.root));
        for (int i = 0; i < 50_000 && !vm.isFinished(); i++) vm.step();
        return vm;
    }

    @Test void aFailedSubmoduleFailsAgainOnRetry() {
        Vm vm = run(String.join("\n",
                "for attempt in range(2):",
                "    try:",
                "        from pkg import child",
                "        raise AssertionError('got the half-initialized module')",
                "    except ValueError as error:",
                "        assert str(error) == 'boom', error",
                "import pkg",
                "assert not hasattr(pkg, 'child'), 'the failed child is not left on its package'",
                "try:",
                "    import pkg.child",
                "    raise AssertionError('imported')",
                "except ValueError:",
                "    pass",
                "assert not hasattr(pkg, 'child')",
                "passed = True", ""),
                "pkg/__init__", "", "pkg/child", "raise ValueError('boom')");
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }

    @Test void starImportFollowsAll() {
        Vm vm = run(String.join("\n",
                "from m import *",
                "assert a == 1",
                "try:", "    b", "    raise AssertionError('b is not in __all__')", "except NameError:", "    pass",
                "from n import *",
                "assert c == 3",
                "try:", "    _d", "    raise AssertionError('no __all__: private names stay out')", "except NameError:", "    pass",
                "from t import *",
                "assert _e == 5, '__all__ may name a private name, and be a tuple'",
                "try:",
                "    from bad import *",
                "    raise AssertionError('a missing name in __all__')",
                "except AttributeError:",
                "    pass",
                "passed = True", ""),
                "m", "__all__ = ['a']\na = 1\nb = 2\n",
                "n", "c = 3\n_d = 4\n",
                "t", "__all__ = ('_e',)\n_e = 5\n",
                "bad", "__all__ = ['nope']\n");
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
