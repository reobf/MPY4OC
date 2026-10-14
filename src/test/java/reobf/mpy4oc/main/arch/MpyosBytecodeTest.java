package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.mpy.compiler.MpyCross;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

/** Execute real compiled .mpy files through the normal MPYOS process lifecycle. */
class MpyosBytecodeTest {
    private static void bytecode(TkPythonTest.Host host, String name, String source) throws Exception {
        host.vm.globals.put(name, new PyObj.Bytes(MpyCross.compile(source, "compiled-input.py")));
    }

    private static void interpreters(TkPythonTest.Host host) throws Exception {
        Path bin = Path.of("src/main/resources/assets/mpy4oc/mpyos/bin");
        host.vm.globals.put("PYTHON", Files.readString(bin.resolve("python.py")));
        host.vm.globals.put("PYTHONW", Files.readString(bin.resolve("pythonw.py")));
    }

    @Test void pythonAndPythonwRunBytecodeWithArgvCwdPidAndBackgroundLifetime() throws Exception {
        var host = TkPythonTest.shellHost("""
                import sys, _process
                FAKE_VFS.files.update({'/bin/python.py': PYTHON, '/bin/pythonw.py': PYTHONW, '/home/child.mpy': BYTECODE})
                sh.run_command('cd', ['/home'])
                os.environ['MARK'] = 'inherited'
                assert sh.run_command('python', ['child.mpy', 'foreground']) == 7, tty.out
                assert SHARED['seen'] == (['/home/child.mpy', 'foreground'], '/home/child.mpy', '__main__', '/home', 'inherited', False)
                assert SHARED['pid'] > 0 and SHARED['finished']
                assert not _process.runtime().active and not sh.jobs
                assert 'private_value' not in globals()
                old_pid = SHARED['pid']
                SHARED.clear()
                sh.run_command('pythonw', ['child.mpy', 'background'])
                assert len(sh.jobs) == 1
                wait(lambda: 'seen' in SHARED)
                job = sh.jobs[0]
                assert SHARED['pid'] == job['pid'] and job['pid'] > old_pid
                assert SHARED['seen'][-1] and not job['done'] and not SHARED.get('finished')
                SHARED['release'] = True
                wait(lambda: job['done'])
                assert job['returncode'] == 7 and SHARED['finished'], job['error']
                sh._report_jobs()
                wait(lambda: not _process.runtime().active and not _program._tasks)
                assert sys.argv == [] and os.getcwd() == '/home' and not sys._contexts
                passed = True
                """);
        interpreters(host);
        bytecode(host, "BYTECODE", """
                import os, sys, time, _program
                private_value = 99
                SHARED['pid'] = os.getpid()
                SHARED['seen'] = (list(sys.argv), __file__, __name__, os.getcwd(), os.getenv('MARK'), _program.in_background())
                assert args == sys.argv[1:] and argv is sys.argv
                if args[0] == 'background':
                    while not SHARED.get('release'):
                        time.sleep(0.01)
                SHARED['finished'] = True
                raise SystemExit(7)
                """);
        List<String> compiled = new ArrayList<>();
        host.vm.setCompiler((source, filename, mode) -> {
            compiled.add(filename);
            return MpyCross.compile(source, filename);
        });
        host.runUntil("passed");
        assertTrue(compiled.isEmpty(), "Interpreter markers and .mpy must bypass source compilation: " + compiled);
    }

    @Test void commandLookupPrefersExactThenPyThenMpyWithinEachPathDirectory() throws Exception {
        var host = TkPythonTest.shellHost("""
                FAKE_VFS.dirs += ['/bin', '/tools', '/home/scripts']
                FAKE_VFS.files.update({'/bin/task.mpy': BYTECODE, '/bin/task.py': "SHARED['kind'] = 'source'", '/tools/task.py': 'pass'})
                os.environ['PATH'] = '/bin:/tools:.'
                sh.run_command('cd', ['/home'])
                assert sh.command_path('task') == '/bin/task.py'
                assert sh.run_command('task', []) == 0 and SHARED['kind'] == 'source'
                FAKE_VFS.files['/bin/task.py'] = 'def broken(:'
                assert sh.run_command('task', []) == 1 and SHARED['kind'] == 'source', 'no retry after source compile failure'
                assert sh.run_command('task.mpy', []) == 0 and SHARED['kind'] == 'bytecode'
                del FAKE_VFS.files['/bin/task.py']
                assert sh.command_path('task') == '/bin/task.mpy', 'PATH order precedes suffix preference'
                assert sh.find_command('task') == BYTECODE
                assert sh.run_command('task', []) == 0
                FAKE_VFS.files['/bin/task'] = "SHARED['kind'] = 'exact'"
                assert sh.run_command('task', []) == 0 and SHARED['kind'] == 'exact'
                FAKE_VFS.files['/home/scripts/task.mpy'] = BYTECODE
                assert sh.command_path('scripts/task') == '/home/scripts/task.mpy'
                assert sh.command_path('../bin/task.mpy') == '/bin/task.mpy'
                FAKE_VFS.files['/home/missing.mpy.py'] = 'pass'
                FAKE_VFS.files['/home/missing.py.mpy'] = BYTECODE
                assert sh.command_path('./missing.mpy') is None
                assert sh.command_path('./missing.py') is None
                candidates = sh._complete('tas', 3)
                assert len(candidates) == 1 and candidates[0][0] == 'task ', candidates
                FAKE_VFS.files['/bin/byteonly.mpy'] = BYTECODE
                assert sh._complete('byteo', 5)[0][0] == 'byteonly '
                assert sh._complete('python scripts/tas', 18)[0][0] == 'python scripts/task.mpy '
                passed = True
                """);
        bytecode(host, "BYTECODE", "SHARED['kind'] = 'bytecode'\n");
        host.runUntil("passed");
    }

    @Test void suffixAloneSelectsDecoderAndInvalidBytecodeNeverFallsBackToSource() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                engine = _process.runtime()
                for name in ('script', 'script.py', 'script.pyw', 'script.txt', 'script.MPY'):
                    path = '/home/' + name
                    FAKE_VFS.files[path] = "SHARED['source'] = __file__"
                    job = engine.foreground(engine.prepare([path], file=True))
                    assert job['returncode'] == 0 and SHARED['source'] == path, job['error']
                    FAKE_VFS.files[path] = BYTECODE
                    SHARED.clear()
                    try:
                        prepared = engine.prepare([path], file=True)
                    except UnicodeError:
                        continue
                    job = engine.foreground(prepared)
                    assert job['returncode'] == 1 and not SHARED, 'renamed bytecode must never execute'
                for bad in (b'', b'not mpy', b"SHARED['wrong'] = True", BAD_VERSION, NATIVE):
                    FAKE_VFS.files['/home/bad.mpy'] = bad
                    job = engine.foreground(engine.prepare(['/home/bad.mpy'], file=True))
                    assert job['returncode'] == 1 and not SHARED, job['error']
                    assert '/home/bad.mpy: invalid .mpy bytecode:' in job['error'], job['error']
                assert not engine.active
                passed = True
                """);
        bytecode(host, "BYTECODE", "SHARED['wrong'] = 'renamed bytecode executed'\n");
        byte[] raw = ((PyObj.Bytes) host.vm.globals.get("BYTECODE")).data;
        byte[] version = raw.clone(); version[1] = 99;
        byte[] nativeCode = raw.clone(); nativeCode[2] = 4;
        host.vm.globals.put("BAD_VERSION", new PyObj.Bytes(version));
        host.vm.globals.put("NATIVE", new PyObj.Bytes(nativeCode));
        List<String> compiled = new ArrayList<>();
        host.vm.setCompiler((source, filename, mode) -> {
            compiled.add(filename);
            return MpyCross.compile(source, filename);
        });
        host.runUntil("passed");
        assertFalse(compiled.stream().anyMatch(name -> name.endsWith(".mpy")), compiled.toString());
    }

    @Test void spawnSubprocessLegacyShellAndInterpreterFallbackShareBytecodeExecution() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, _process
                FAKE_VFS.files.update({'/bin/child.mpy': BYTECODE, '/bin/python.py': PYTHON, '/bin/pythonw.py': PYTHONW})
                SHARED['seen'] = []
                assert subprocess.run(['child', 'subprocess']).returncode == 5
                assert os.spawnv(os.P_WAIT, '/bin/child.mpy', ['custom', 'spawnv']) == 5
                assert os.spawnvp(os.P_WAIT, 'child', ['custom-p', 'spawnvp']) == 5
                assert os.spawnlp(os.P_WAIT, 'python', 'python', '/bin/child.mpy', 'interpreter') == 5
                FAKE_VFS.files['/home/launcher.py'] = '''
                import _process
                sh.run_command('/bin/child.mpy', ['legacy'])
                assert _process.python(['/bin/child.mpy', 'fallback-background'], background=True) > 0
                _process.python(['/bin/child.mpy', 'fallback-foreground'])
                '''
                assert sh.run_command('/home/launcher.py', []) == 5
                wait(lambda: len(SHARED['seen']) == 7 and not _process.runtime().active)
                records = {r[1]: r for r in SHARED['seen']}
                assert records['spawnv'][0] == 'custom' and records['spawnvp'][0] == 'custom-p'
                assert records['legacy'][2] == records['fallback-foreground'][2]
                assert records['fallback-background'][2] != records['legacy'][2]
                assert all(r[3] == '/bin/child.mpy' for r in SHARED['seen'])
                sh._report_jobs()
                passed = True
                """);
        interpreters(host);
        bytecode(host, "BYTECODE", """
                import os, sys
                SHARED['seen'].append((sys.argv[0], sys.argv[1], os.getpid(), __file__))
                raise SystemExit(5)
                """);
        host.runUntil("passed");
    }

    @Test void runningBytecodeAndUnexecutedCodeObjectSurviveSnapshotWithoutCompilerOrDisk() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, _process, time, sys, event
                FAKE_VFS.files['/home/saved.mpy'] = BYTECODE
                loader = __load_mpy
                saved_code = loader(CACHED, '/home/cached.mpy')
                process = subprocess.Popen(['/home/saved.mpy', 'argument'], cwd='/home', env={'MARK': 'saved'})
                wait(lambda: SHARED.get('ready'))
                del FAKE_VFS.files['/home/saved.mpy']
                armed = True
                wait(lambda: SHARED.get('finished'))
                assert process.wait() == 0
                scope = {}
                exec_sandbox(saved_code, scope)
                assert scope['value'] == 42
                exec_sandbox(loader(CACHED), scope)
                assert scope['value'] == 42
                wait(lambda: not _process.runtime().active and not _program._tasks)
                assert not sys._contexts and not osfs._cwds
                passed = True
                """);
        bytecode(host, "BYTECODE", """
                import os, sys, time
                def verify():
                    assert sys.argv == ['/home/saved.mpy', 'argument']
                    assert __file__ == '/home/saved.mpy' and os.getcwd() == '/home'
                    assert os.getenv('MARK') == 'saved' and os.getpid() == SHARED['pid']
                    SHARED['finished'] = True
                SHARED['pid'] = os.getpid()
                SHARED['ready'] = True
                while not SHARED.get('resume'):
                    time.sleep(0.01)
                verify()
                """);
        bytecode(host, "CACHED", "value = 42\n");
        host.runUntil("armed");
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300; restored.bootstrap = true;
        restored.start("pass");
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.vm.setModuleFinder(name -> null); // No disk modules or original program file available.
        ((PyObj.PyDict) restored.vm.globals.get("SHARED")).map.put("resume", true);
        restored.runUntil("passed");
    }
}
