package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NBTTagCompound;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.TileTkDisplay;
import reobf.mpy4oc.tk.TkTestRegistration;

/** Real compiler, VM, scheduler, MPYOS runtime and Tk; only disks and the display host are simulated. */
class MpyosProcessTest {
    @BeforeAll static void registerTile() { TkTestRegistration.ensure(); }

    @Test void standardLaunchUsesCwdPathEnvironmentArgvAndExitStatus() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, sys
                FAKE_VFS.dirs += ['/home/tools', '/home/work']
                FAKE_VFS.files['/home/tools/child.py'] = '''
                import sys, os, threading
                from sys import argv
                assert argv is sys.argv and isinstance(argv, list)
                assert sys.argv == ['/home/tools/child.py', 'two words', '']
                assert os.getcwd() == '/home/work' and os.getenv('MARK') == 'child'
                assert os.getenv('PARENT') is None
                os.chdir('..')
                os.environ['MARK'] = 'changed'
                sys.argv = ['assigned']
                def worker():
                    from sys import argv
                    assert argv is getattr(sys, 'argv')
                    argv.append('thread')
                    SHARED['argv'] = argv
                t = threading.Thread(target=worker)
                t.start()
                t.join()
                assert sys.argv == ['assigned', 'thread']
                setattr(sys, 'argv', ['setattr'])
                assert sys.argv == ['setattr']
                del sys.argv
                assert not hasattr(sys, 'argv')
                sys.argv = ['restored']
                SHARED['context'] = (os.getcwd(), os.getenv('MARK'), sys.argv, __file__)
                raise SystemExit(7)
                '''
                sys.argv = ['parent']
                os.environ['PARENT'] = 'yes'
                os.chdir('/home')
                environment = {'PATH': '../tools', 'MARK': 'child'}
                result = subprocess.run(['child', 'two words', ''], cwd='work', env=environment)
                assert result.returncode == 7 and result.args == ['child', 'two words', '']
                assert result.stdout is None and result.stderr is None
                assert environment == {'PATH': '../tools', 'MARK': 'child'}
                assert sys.argv == ['parent'] and os.getcwd() == '/home' and os.getenv('MARK') is None
                assert SHARED['argv'] == ['assigned', 'thread']
                assert SHARED['context'] == ('/home', 'changed', ['restored'], '/home/tools/child.py')
                try:
                    result.check_returncode()
                    raise AssertionError('expected nonzero exit')
                except subprocess.CalledProcessError as error:
                    assert error.returncode == 7 and error.cmd == result.args
                FAKE_VFS.files['/bin/ok.py'] = 'raise SystemExit(None)'
                FAKE_VFS.files['/bin/fail.py'] = 'raise SystemExit(3)'
                assert subprocess.call(['ok']) == 0 and subprocess.check_call(['ok']) == 0
                try:
                    subprocess.run(['fail'], check=True)
                    raise AssertionError('expected checked failure')
                except subprocess.CalledProcessError as error:
                    assert error.returncode == 3
                    # CPython 3.12's wording (the command with %s, a signal by name).
                    assert str(error) == "Command '['fail']' returned non-zero exit status 3.", str(error)
                assert str(subprocess.CalledProcessError(2, 'x y')) == "Command 'x y' returned non-zero exit status 2."
                assert str(subprocess.CalledProcessError(-9, ['a'])) == "Command '['a']' died with <Signals.SIGKILL: 9>."
                assert str(subprocess.CalledProcessError(-15, 'a')) == "Command 'a' died with <Signals.SIGTERM: 15>."
                assert str(subprocess.TimeoutExpired(['a', 'b'], 1.5)) == "Command '['a', 'b']' timed out after 1.5 seconds"
                assert repr(subprocess.CompletedProcess(['a'], 0)) == "CompletedProcess(args=['a'], returncode=0)"
                assert repr(subprocess.run(['ok'])) == "CompletedProcess(args=['ok'], returncode=0)"
                import _process
                wait(lambda: not _program._tasks and not _process.runtime().active)
                assert not _process.runtime().active and not _process.runtime().jobs
                assert not sys._contexts and not osfs._cwds and list(os._environments) == [None], (sys._contexts, osfs._cwds, os._environments)
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void childOutlivesLauncherAndThreadsKeepOnlyTheirOwnProgramAlive() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, sys
                FAKE_VFS.files['/bin/worker.py'] = '''
                import sys, os, time, threading
                assert sys.argv[1:] == ['frozen'] and os.getenv('MARK') == 'before'
                assert os.getcwd() == '/home'
                def work():
                    try:
                        SHARED['started'] = True
                        while not SHARED.get('finish'):
                            time.sleep(0.01)
                        SHARED['thread_argv'] = sys.argv
                    finally:
                        SHARED['finally'] = True
                threading.Thread(target=work).start()
                '''
                FAKE_VFS.files['/bin/launcher.py'] = '''
                import os, sys, subprocess
                os.chdir('/home')
                os.environ['MARK'] = 'before'
                arguments = ['worker', 'frozen']
                SHARED['child'] = subprocess.Popen(arguments)
                arguments[1] = 'after'
                os.environ['MARK'] = 'after'
                sys.argv = ['launcher only']
                '''
                sh.run_command('launcher', [])
                child = SHARED['child']
                assert child.pid > 0
                wait(lambda: SHARED.get('started'))
                assert child.poll() is None
                SHARED['finish'] = True
                assert child.wait() == 0 and child.returncode == 0
                assert SHARED['finally'] and SHARED['thread_argv'] == ['/bin/worker.py', 'frozen']
                assert sys.argv == [] and os.getcwd() == '/'
                import _process
                wait(lambda: not _program._tasks and not _process.runtime().active)
                assert not sys._contexts and not osfs._cwds, (sys._contexts, osfs._cwds)
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void independentProcessesCanStartWithoutAnyShellObject() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, _process
                _process.runtime().context = None
                del globals()['sh']
                FAKE_VFS.files['/bin/standalone.py'] = '''
                import sys, os
                SHARED['seen'] = (sys.argv, os.getcwd(), os.getenv('VALUE'))
                try:
                    sh
                    raise AssertionError('unexpected shell object')
                except NameError:
                    pass
                '''
                result = subprocess.run(['/bin/standalone.py', 'arg'], cwd='/home', env={'VALUE': 'ok'})
                assert result.returncode == 0
                assert SHARED['seen'] == (['/bin/standalone.py', 'arg'], '/home', 'ok')
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void missingFilesInvalidOptionsAndTimeoutsDoNotLeaveHiddenPrograms() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, _process, sys
                def raises(kind, fn):
                    try:
                        fn()
                    except kind:
                        return
                    raise AssertionError('expected ' + kind.__name__)
                FAKE_VFS.files['/bin/loop.py'] = 'import time\\nwhile True:\\n    time.sleep(0.01)'
                raises(FileNotFoundError, lambda: subprocess.Popen(['missing']))
                raises(OSError, lambda: subprocess.Popen(['loop'], cwd='/missing'))
                raises(ValueError, lambda: subprocess.Popen([]))
                raises(ValueError, lambda: subprocess.Popen(['loop'], env={'A=B': 'x'}))
                raises(TypeError, lambda: subprocess.Popen(['loop'], env={'A': 2}))
                raises(NotImplementedError, lambda: subprocess.Popen(['loop'], stdout=subprocess.PIPE))
                raises(NotImplementedError, lambda: subprocess.Popen('loop', shell=True))
                raises(NotImplementedError, lambda: subprocess.run(['loop'], capture_output=True))
                assert not _process.runtime().active
                p = subprocess.Popen('loop')
                raises(subprocess.TimeoutExpired, lambda: p.wait(timeout=0))
                assert p.poll() is None
                p.terminate()
                assert p.wait() == -15
                p = subprocess.Popen(['loop'])
                p.kill()
                assert p.wait() == -9
                raises(subprocess.TimeoutExpired, lambda: subprocess.run(['loop'], timeout=0))
                wait(lambda: not _program._tasks and not _process.runtime().active)
                assert not _process.runtime().active and not _process.runtime().jobs
                assert not sys._contexts and not osfs._cwds and list(os._environments) == [None], (sys._contexts, osfs._cwds, os._environments)
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void pythonwAndShebangStartOnceWhileExplicitPythonWaits() throws Exception {
        var tile = new TileTkDisplay();
        var host = TkPythonTest.shellHost("""
                import sys, subprocess
                FAKE_VFS.files['/bin/python.py'] = PYTHON
                FAKE_VFS.files['/bin/pythonw.py'] = PYTHONW
                FAKE_VFS.files['/home/app.py'] = '''#!/usr/bin/env pythonw
                import tkinter as tk, sys
                root = tk.Tk()
                SHARED['root'] = root
                SHARED['argv'] = sys.argv
                root.mainloop()
                SHARED['closed'] = True
                '''
                sh.run_command('cd', ['/home'])
                for command, arguments in [('pythonw', ['app.py', 'A']), ('./app', ['A'])]:
                    SHARED.clear()
                    sh.run_command(command, arguments)
                    assert len(sh.jobs) == 1
                    wait(lambda: 'root' in SHARED and SHARED['root']._token)
                    assert SHARED['argv'] == ['/home/app.py', 'A']
                    assert not SHARED.get('closed')
                    SHARED['root'].after_idle(SHARED['root'].destroy)
                    wait(lambda: sh.jobs[0]['done'])
                    assert SHARED['closed']
                    sh._report_jobs()
                SHARED.clear()
                sh.spawn('pythonw', ['app.py'])
                wait(lambda: 'root' in SHARED and SHARED['root']._token)
                assert len(sh.jobs) == 1, 'pythonw followed by & must not double-spawn'
                sh.run_command('kill', [str(sh.jobs[0]['id'])])
                wait(lambda: sh.jobs[0]['done'])
                sh._report_jobs()
                SHARED.clear()
                def close_foreground():
                    while 'root' not in SHARED or not SHARED['root']._token:
                        computer._yield_tick()
                    SHARED['root'].after_idle(SHARED['root'].destroy)
                closer = threading.Thread(target=close_foreground)
                closer.start()
                sh.run_command('python', ['app.py', 'foreground'])
                assert SHARED.get('closed') and not sh.jobs, 'explicit python ignores the background shebang'
                assert SHARED['argv'] == ['/home/app.py', 'foreground']
                closer.join()
                assert not tk._roots
                passed = True
                """, tile);
        Path bin = Path.of("src/main/resources/assets/mpy4oc/mpyos/bin");
        host.vm.globals.put("PYTHON", Files.readString(bin.resolve("python.py")));
        host.vm.globals.put("PYTHONW", Files.readString(bin.resolve("pythonw.py")));
        host.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty());
    }

    @Test void processArgvEnvironmentCwdAndTkSurviveJointSnapshot() throws Exception {
        var tile = new TileTkDisplay();
        var host = TkPythonTest.shellHost("""
                import subprocess, sys, _process
                FAKE_VFS.files['/bin/gui.py'] = '''
                import tkinter as tk, sys, os
                from sys import argv
                root = tk.Tk()
                SHARED['root'] = root
                def verify():
                    assert argv is sys.argv and argv == ['/bin/gui.py', 'saved']
                    assert os.getcwd() == '/home' and os.getenv('MARK') == 'snapshot'
                    SHARED['checked'] = True
                    root.destroy()
                tk.Button(root, text='check', command=verify).pack()
                SHARED['ready'] = True
                root.mainloop()
                '''
                p = subprocess.Popen(['/bin/gui.py', 'saved'], cwd='/home', env={'MARK': 'snapshot'})
                wait(lambda: SHARED.get('ready'))
                armed = True
                wait(lambda: SHARED.get('checked'))
                assert p.wait() == 0 and not tk._roots
                wait(lambda: not _program._tasks and not _process.runtime().active)
                assert not sys._contexts and not _process.runtime().active
                assert not osfs._cwds and list(os._environments) == [None]
                assert not sys.argv and os.getcwd() == '/'
                passed = True
                """, tile);
        host.runUntil("armed");
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        NBTTagCompound nbt = new NBTTagCompound(); tile.writeToNBT(nbt);
        var restored = new TkPythonTest.Host(); restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        var copy = new TileTkDisplay(); copy.readFromNBT(nbt); restored.tiles.put("screen-a", copy);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.click(copy);
        restored.runUntil("passed");
        assertTrue(copy.application().tokens().isEmpty());
    }

    @Test void concurrentLaunchAndShellReapingKeepUniqueIdentities() throws Exception {
        var host = TkPythonTest.shellHost("""
                import subprocess, sys, _process
                FAKE_VFS.files['/bin/child.py'] = '''
                import sys, time
                original = list(sys.argv)
                time.sleep(0.01)
                assert sys.argv == original
                SHARED.setdefault('children', []).append(sys.argv[1])
                '''
                processes = []
                def launch(number):
                    for i in range(4):
                        processes.append(subprocess.Popen(['child', str(number) + ':' + str(i)]))
                threads = [threading.Thread(target=launch, args=(n,)) for n in range(5)]
                for t in threads:
                    t.start()
                while any(t.is_alive() for t in threads) or _process.runtime().active:
                    sh._report_jobs()
                    computer._yield_tick()
                assert len(processes) == 20 and len(set(p.pid for p in processes)) == 20
                assert len(set(p._job['program'] for p in processes)) == 20
                assert all(p.wait() == 0 for p in processes), [(p.pid, p._job['error']) for p in processes]
                assert len(SHARED['children']) == 20 and len(set(SHARED['children'])) == 20
                assert not sh.jobs and not sys._contexts and not osfs._cwds
                passed = True
                """);
        host.stepBudget = 37;
        host.runUntil("passed");
    }
}
