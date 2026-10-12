package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

class OsEnvironmentTest {
    @Test void environmentIsAStringMappingWithIndependentCopies() {
        run("""
                import os
                assert len(os.environ) == 0, 'the host environment must not leak into a VM'
                assert os.getenv('missing') is None and os.getenv('missing', 42) == 42
                assert not os.supports_bytes_environ
                os.environ['HOME'] = '/home'
                os.environ.update({'PATH': '/bin'}, NAME='user')
                os.environ.update([('EMPTY', ''), ('lower', 'value')])
                assert os.getenv('HOME') == '/home' and os.getenv('EMPTY', 'fallback') == ''
                assert 'lower' in os.environ and 'LOWER' not in os.environ
                assert sorted(os.environ) == sorted(os.environ.keys())
                assert ('NAME', 'user') in os.environ.items() and '/home' in os.environ.values()
                assert len(os.environ) == 5
                clone = os.environ.copy()
                assert dict(os.environ) == clone
                assert dict(os.environ, NAME='override')['NAME'] == 'override'
                clone['HOME'] = '/different'
                assert os.getenv('HOME') == '/home'
                assert os.environ.setdefault('HOME', '/ignored') == '/home'
                assert os.environ.setdefault('NEW', 'x') == 'x'
                assert os.environ.pop('NEW') == 'x'
                assert os.environ.pop('NEW', 17) == 17
                del os.environ['EMPTY']
                assert os.getenv('EMPTY') is None
                before = os.environ.copy()
                key, value = os.environ.popitem()
                assert key not in os.environ and before[key] == value
                assert len(os.environ) == len(before) - 1
                os.environ.clear()
                assert not os.environ and dict(os.environ) == {}
                os.environ.update(before)
                assert os.environ.copy() == before
                passed = True
                """);
    }

    @Test void invalidEnvironmentWritesAndMissingKeysRaise() {
        run("""
                import os
                def raises(kind, fn):
                    try:
                        fn()
                    except kind:
                        return
                    raise AssertionError('expected ' + kind.__name__)
                def store(key, value):
                    os.environ[key] = value
                def remove(key):
                    del os.environ[key]
                for key, value in [(1, 'x'), ('A', 1), ('A', None), (b'A', 'x'), ('A', b'x')]:
                    raises(TypeError, lambda: store(key, value))
                for key, value in [('', 'x'), ('A=B', 'x'), ('A\\x00B', 'x'), ('A', 'x\\x00y')]:
                    raises(ValueError, lambda: store(key, value))
                raises(KeyError, lambda: os.environ['absent'])
                raises(KeyError, lambda: remove('absent'))
                raises(KeyError, lambda: os.environ.pop('absent'))
                raises(KeyError, lambda: os.environ.popitem())
                raises(TypeError, lambda: os.getenv(1))
                raises(TypeError, lambda: os.environ.setdefault('A'))
                assert os.environ.copy() == {}, 'failed writes must not change the environment'
                passed = True
                """);
    }

    @Test void environmentAndProgramIdentitySurviveSnapshot() {
        String source = """
                import os, _program
                os.environ['MARK'] = 'parent'
                os._inherit_environment('child')
                _program.set_current('child')
                os.environ['MARK'] = 'child'
                reference = os.environ
                armed = True
                while poll() is None:
                    pass
                assert reference['MARK'] == 'child' and os.getenv('MARK') == 'child'
                _program.set_current(None)
                assert reference['MARK'] == 'parent'
                _program.ended('child')
                assert 'child' not in os._environments
                assert os.environ.copy() == {'MARK': 'parent'}
                passed = True
                """;
        Map<String, Object> globals = new HashMap<>();
        globals.put("poll", (HostFunction) args -> PyObj.NONE);
        Vm original = start(source, globals);
        for (int i = 0; i < 100_000 && !Boolean.TRUE.equals(original.globals.get("armed")); i++) original.step();
        assertEquals(Boolean.TRUE, original.globals.get("armed"));
        byte[] snapshot = original.saveSnapshot();
        Map<String, Object> restoredGlobals = new HashMap<>();
        restoredGlobals.put("poll", (HostFunction) args -> Boolean.TRUE);
        Vm restored = Vm.fromSnapshot(snapshot, restoredGlobals, HostRegistry.fromGlobals(restoredGlobals));
        finish(restored);
        // Every new VM starts with its own empty environment.
        run("import os\nassert os.environ.copy() == {}\npassed = True\n");
    }

    private static Vm start(String source, Map<String, Object> globals) {
        MpyModule module = MpyLoader.load(MpyCross.compile(source, "environment_test.py"), new QstrPool());
        Vm vm = new Vm(module, globals);
        vm.start(new Frame(module.root));
        return vm;
    }

    private static void run(String source) { finish(start(source, new HashMap<>())); }

    private static void finish(Vm vm) {
        for (int i = 0; i < 1_000_000 && !vm.isFinished(); i++) vm.step();
        assertTrue(vm.isFinished(), "environment test must finish");
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
