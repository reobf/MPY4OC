package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

/** os.spawn against the real MPY compiler, cooperative scheduler and MPYOS runtime. */
class MpyosSpawnTest {
    @Test void allEightVariantsPreserveArgvAndReplaceOrInheritEnvironment() throws Exception {
        var host = TkPythonTest.shellHost("""
                import sys, _process
                FAKE_VFS.dirs += ['/home/tools']
                FAKE_VFS.files['/home/tools/child.py'] = '''
                import os, sys, threading
                assert sys.argv == ['alias', 'two words', '']
                assert args == ['two words', ''] and argv is sys.argv
                assert __file__ == '/home/tools/child.py' and os.getcwd() == '/home'
                before = (os.getenv('MARK'), os.getenv('PARENT'))
                own = os.getpid()
                assert own > 0 and os.getppid() == 0
                def check_thread():
                    SHARED['thread_pid'] = os.getpid()
                t = threading.Thread(target=check_thread)
                t.start()
                t.join()
                assert SHARED['thread_pid'] == own
                SHARED.setdefault('seen', []).append(before)
                os.chdir('/')
                os.environ['MARK'] = 'changed'
                sys.argv.append('changed')
                raise SystemExit(7)
                '''
                os.chdir('/home')
                os.environ.update({'PATH': 'tools', 'MARK': 'inherited', 'PARENT': 'yes'})
                sys.argv = ['parent']
                arguments = ['alias', 'two words', '']
                environment = {'PATH': 'tools', 'MARK': 'replacement'}
                assert os.spawnv(os.P_WAIT, 'tools/child.py', arguments) == 7
                assert os.spawnve(os.P_WAIT, './tools/child.py', arguments, environment) == 7
                assert os.spawnvp(os.P_WAIT, 'child', arguments) == 7
                assert os.spawnvpe(os.P_WAIT, 'child', arguments, environment) == 7
                assert os.spawnl(os.P_WAIT, 'tools/child.py', *arguments) == 7
                assert os.spawnle(os.P_WAIT, 'tools/child.py', *arguments, environment) == 7
                assert os.spawnlp(os.P_WAIT, 'child', *arguments) == 7
                assert os.spawnlpe(os.P_WAIT, 'child', *arguments, environment) == 7
                assert SHARED['seen'] == [('inherited', 'yes'), ('replacement', None)] * 4, SHARED
                assert sys.argv == ['parent'] and os.getcwd() == '/home'
                assert arguments == ['alias', 'two words', ''] and environment == {'PATH': 'tools', 'MARK': 'replacement'}
                assert os.getenv('MARK') == 'inherited' and os.getpid() == 0 and os.getppid() == 0
                wait(lambda: not _process.runtime().active and not _program._tasks)
                assert not _process.runtime().children and not sys._contexts and not osfs._cwds
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void exactPathsPathOrderAndEmptyOrMissingPathHaveDefinedSemantics() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                FAKE_VFS.files['/home/task.py'] = 'raise SystemExit(1)'
                FAKE_VFS.files['/bin/task.py'] = 'raise SystemExit(2)'
                FAKE_VFS.files['/bin/task'] = 'raise SystemExit(3)'
                FAKE_VFS.files['/usr/bin/task.py'] = 'raise SystemExit(4)'
                os.chdir('/home')
                os.environ['PATH'] = '/bin:/usr/bin:.'
                assert os.spawnv(os.P_WAIT, 'task.py', ['named']) == 1
                assert os.spawnv(os.P_WAIT, 'task', ['named']) == 127, 'non-p must not add .py or search PATH'
                assert os.spawnvp(os.P_WAIT, 'task', ['named']) == 3, 'exact file before .py'
                del FAKE_VFS.files['/bin/task']
                assert os.spawnvp(os.P_WAIT, 'task', ['named']) == 2
                assert os.spawnvp(os.P_WAIT, './task', ['named']) == 1, 'explicit path bypasses PATH'
                assert os.spawnvpe(os.P_WAIT, 'task', ['named'], {'PATH': '/usr/bin:/bin'}) == 4
                assert os.spawnvpe(os.P_WAIT, 'task', ['named'], {'PATH': ''}) == 1
                assert os.spawnvpe(os.P_WAIT, 'task', ['named'], {'PATH': '/missing::/bin'}) == 1
                assert os.spawnvpe(os.P_WAIT, 'task', ['named'], {}) == 2, 'missing PATH uses os.defpath'
                FAKE_VFS.files['/bin/tkinter/gui.py'] = 'raise SystemExit(6)'
                assert os.spawnvpe(os.P_WAIT, 'gui', ['named'], {}) == 6, 'default search includes Tk commands'
                assert os.spawnvpe(os.P_WAIT, 'gui', ['named'], {'PATH': '/bin'}) == 127
                assert os.spawnv(os.P_WAIT, '../bin/task.py', ['named']) == 2
                assert os.spawnv(os.P_WAIT, '/home', ['named']) == 127
                assert os.spawnv(os.P_WAIT, 'task.py/', ['named']) == 127
                FAKE_VFS.files['/home/unreadable.py'] = None
                assert os.spawnv(os.P_WAIT, 'unreadable.py', ['named']) == 127
                # Shell and subprocess retain their existing empty-PATH policy.
                os.environ['PATH'] = ''
                assert sh.command_path('task') is None
                assert not _process.runtime().children
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void nowaitFreezesContextAndWaitpidCollectsAfterShellReaping() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process, sys
                FAKE_VFS.files['/home/child.py'] = '''
                import os, sys, time
                while not SHARED.get('finish'):
                    time.sleep(0.01)
                SHARED['context'] = (os.getcwd(), os.getenv('VALUE'), list(sys.argv), os.getpid())
                raise SystemExit(23)
                '''
                os.chdir('/home')
                environment = {'VALUE': 'before'}
                arguments = ['custom name', 'before']
                pid = os.spawnve(os.P_NOWAITO, 'child.py', arguments, environment)
                assert pid > 0 and os.P_NOWAITO == os.P_NOWAIT
                environment['VALUE'] = 'after'
                arguments[1] = 'after'
                os.chdir('/')
                os.kill(pid, 0)
                assert os.waitpid(pid, os.WNOHANG) == (0, 0)
                assert os.waitpid(-1, os.WNOHANG) == (0, 0)
                SHARED['finish'] = True
                wait(lambda: not _process.runtime().active and not _program._tasks)
                sh._report_jobs()
                assert not sh.jobs and not sys._contexts and not osfs._cwds
                assert SHARED['context'] == ('/home', 'before', ['custom name', 'before'], pid)
                record = _process.runtime().children[pid]
                assert set(record) == {'ppid', 'status'}, record
                os.kill(pid, 0)
                os.kill(pid, 9)
                assert os.waitpid(pid, 0) == (pid, 23 << 8)
                assert not _process.runtime().children
                try:
                    os.waitpid(pid, 0)
                    raise AssertionError('exit status must be consumed once')
                except ChildProcessError:
                    pass
                try:
                    os.kill(pid, 0)
                    raise AssertionError('collected PID must disappear')
                except ProcessLookupError:
                    pass
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void statusEncodingFailuresSignalsAndFinallyCleanup() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                FAKE_VFS.files['/bin/loop.py'] = '''
                import time, os
                SHARED['started'] = os.getpid()
                try:
                    while True:
                        time.sleep(0.01)
                finally:
                    SHARED['cleaned'] = os.getpid()
                '''
                for signal in (9, 15):
                    pid = os.spawnvp(os.P_NOWAIT, 'loop', ['loop'])
                    wait(lambda: SHARED.get('started') == pid)
                    os.kill(pid, signal)
                    child, status = os.waitpid(pid, 0)
                    assert child == pid and status == signal
                    assert os.waitstatus_to_exitcode(status) == -signal
                    assert SHARED['cleaned'] == pid
                def kill_waited_child():
                    wait(lambda: SHARED.get('started') != pid)
                    os.kill(SHARED['started'], 15)
                killer = threading.Thread(target=kill_waited_child)
                killer.start()
                assert os.spawnvp(os.P_WAIT, 'loop', ['loop']) == -15
                killer.join()
                for code, expected in [(0, 0), (261, 5), (-1, 255)]:
                    FAKE_VFS.files['/bin/exit.py'] = 'raise SystemExit(' + str(code) + ')'
                    assert os.spawnvp(os.P_WAIT, 'exit', ['exit']) == expected
                FAKE_VFS.files['/bin/fail.py'] = 'raise RuntimeError("failed")'
                FAKE_VFS.files['/bin/syntax.py'] = 'def :'
                assert os.spawnvp(os.P_WAIT, 'fail', ['fail']) == 1
                assert os.spawnvp(os.P_WAIT, 'syntax', ['syntax']) == 1
                assert os.spawnvp(os.P_WAIT, 'absent', ['absent']) == 127
                missing = os.spawnvp(os.P_NOWAIT, 'absent', ['absent'])
                assert os.waitpid(missing, 0) == (missing, 127 << 8)
                assert os.waitstatus_to_exitcode(7 << 8) == 7
                wait(lambda: not _process.runtime().active and not _program._tasks)
                assert not _process.runtime().children
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void waitRequiresParentAndOrphansContinueWithoutLeakingExitRecords() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                FAKE_VFS.files['/bin/orphan.py'] = '''
                import os, time
                SHARED['parent_before'] = os.getppid()
                SHARED['orphan_started'] = True
                while not SHARED.get('finish'):
                    time.sleep(0.01)
                SHARED['parent_after'] = os.getppid()
                SHARED['orphan_finished'] = True
                '''
                FAKE_VFS.files['/bin/quick.py'] = 'raise SystemExit(4)'
                FAKE_VFS.files['/bin/launcher.py'] = '''
                import os, time
                SHARED['orphan'] = os.spawnvp(os.P_NOWAIT, 'orphan', ['orphan'])
                SHARED['quick'] = os.spawnvp(os.P_NOWAIT, 'quick', ['quick'])
                while not SHARED.get('parent_exit'):
                    time.sleep(0.01)
                '''
                parent = os.spawnvp(os.P_NOWAIT, 'launcher', ['launcher'])
                wait(lambda: SHARED.get('orphan_started') and 'quick' in SHARED and SHARED['quick'] not in _process.runtime().active)
                assert SHARED['parent_before'] == parent
                try:
                    os.waitpid(SHARED['orphan'], 0)
                    raise AssertionError('cannot wait for a grandchild')
                except ChildProcessError:
                    pass
                SHARED['parent_exit'] = True
                assert os.waitpid(parent, 0) == (parent, 0)
                assert not _process.runtime().children, _process.runtime().children
                assert SHARED['orphan'] in _process.runtime().active
                SHARED['finish'] = True
                wait(lambda: not _process.runtime().active and not _program._tasks)
                assert SHARED['orphan_finished'] and SHARED['parent_after'] == 0
                assert not _process.runtime().children
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void invalidArgumentsDoNotCreatePrograms() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                def raises(kind, fn):
                    try:
                        fn()
                    except kind:
                        return
                    raise AssertionError('expected ' + kind.__name__)
                raises(TypeError, lambda: os.spawnv(0, 'a', 'a'))
                raises(ValueError, lambda: os.spawnv(0, 'a', []))
                raises(ValueError, lambda: os.spawnv(0, 'a', ['']))
                raises(TypeError, lambda: os.spawnv(0, 'a', ['a', 4]))
                raises(TypeError, lambda: os.spawnv(0, 4, ['a']))
                raises(ValueError, lambda: os.spawnv(0, 'a' + chr(0), ['a']))
                raises(TypeError, lambda: os.spawnv('0', 'a', ['a']))
                raises(ValueError, lambda: os.spawnv(2, 'a', ['a']))
                raises(TypeError, lambda: os.spawnve(0, 'a', ['a'], None))
                raises(TypeError, lambda: os.spawnve(0, 'a', ['a'], [('A', 'b')]))
                raises(TypeError, lambda: os.spawnve(0, 'a', ['a'], {'A': 4}))
                raises(ValueError, lambda: os.spawnve(0, 'a', ['a'], {'A=B': 'x'}))
                raises(TypeError, lambda: os.spawnle(0, 'a'))
                raises(ValueError, lambda: os.spawnlpe(0, 'a', {}))
                raises(ChildProcessError, lambda: os.waitpid(-1, os.WNOHANG))
                raises(ChildProcessError, lambda: os.waitpid(999, 0))
                raises(NotImplementedError, lambda: os.waitpid(0, 0))
                raises(NotImplementedError, lambda: os.waitpid(-2, 0))
                raises(ValueError, lambda: os.waitpid(-1, 2))
                raises(TypeError, lambda: os.waitpid(1.5, 0))
                raises(ProcessLookupError, lambda: os.kill(999, 9))
                raises(NotImplementedError, lambda: os.kill(0, 9))
                raises(ValueError, lambda: os.kill(999, 2))
                raises(TypeError, lambda: os.kill('1', 9))
                raises(ValueError, lambda: os.waitstatus_to_exitcode(127))
                raises(ValueError, lambda: os.waitstatus_to_exitcode(-1))
                raises(TypeError, lambda: os.waitstatus_to_exitcode('0'))
                assert issubclass(ChildProcessError, OSError) and issubclass(ProcessLookupError, OSError)
                assert not _process.runtime().active and not _process.runtime().children
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void concurrentLaunchAndWaitersConsumeEachExitOnlyOnce() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process, time
                FAKE_VFS.files['/bin/child.py'] = 'import sys\\nraise SystemExit(int(sys.argv[1]))'
                pids, collected = [], []
                def launch(number):
                    for i in range(4):
                        pids.append(os.spawnvp(os.P_NOWAIT, 'child', ['child', str(number * 4 + i)]))
                threads = [threading.Thread(target=launch, args=(n,)) for n in range(5)]
                for t in threads:
                    t.start()
                for t in threads:
                    t.join()
                assert len(pids) == 20 and len(set(pids)) == 20
                def collect():
                    while True:
                        try:
                            collected.append(os.waitpid(-1, 0))
                        except ChildProcessError:
                            return
                waiters = [threading.Thread(target=collect) for i in range(4)]
                for t in waiters:
                    t.start()
                while any(t.is_alive() for t in waiters):
                    sh._report_jobs()
                    time.sleep(0.01)
                assert len(collected) == 20 and set(p for p, s in collected) == set(pids), collected
                assert sorted(os.waitstatus_to_exitcode(s) for p, s in collected) == list(range(20))
                assert not _process.runtime().children and not _process.runtime().active
                passed = True
                """);
        host.stepBudget = 37;
        host.runUntil("passed");
    }

    @Test void pythonInterpretersStartOnceAndWaitOverridesPythonwBackgroundDefault() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                FAKE_VFS.files['/bin/python.py'] = '# MPYOS interpreter: python'
                FAKE_VFS.files['/bin/pythonw.py'] = '# MPYOS interpreter: pythonw'
                FAKE_VFS.files['/home/app.py'] = '''#!/usr/bin/env pythonw
                import sys, os, time, threading
                assert sys.argv == ['/home/app.py', 'arg']
                assert os.getpid() > 0
                SHARED['runs'] = SHARED.get('runs', 0) + 1
                def work():
                    time.sleep(0.01)
                    SHARED['finished'] = SHARED.get('finished', 0) + 1
                threading.Thread(target=work).start()
                raise SystemExit(8)
                '''
                os.chdir('/home')
                for command in ('python', 'pythonw'):
                    assert os.spawnlp(os.P_WAIT, command, 'custom interpreter', 'app.py', 'arg') == 8
                    assert SHARED['runs'] == SHARED['finished']
                assert SHARED['runs'] == 2 and not _process.runtime().children
                pid = os.spawnlp(os.P_NOWAIT, 'python', 'python', 'app.py', 'arg')
                assert os.waitpid(pid, 0) == (pid, 8 << 8)
                assert SHARED['runs'] == SHARED['finished'] == 3
                assert not _process.runtime().active and not _process.runtime().children
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void liveAndCompletedWaitableChildrenSurviveSnapshotRestore() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process, sys
                FAKE_VFS.files['/bin/quick.py'] = 'raise SystemExit(6)'
                FAKE_VFS.files['/bin/live.py'] = '''
                import os, sys, time
                SHARED['ready'] = True
                while not SHARED.get('resume'):
                    time.sleep(0.01)
                assert sys.argv == ['snapshot name', 'saved']
                assert os.getenv('MARK') == 'saved' and os.getcwd() == '/home'
                assert os.getppid() == 0
                raise SystemExit(9)
                '''
                os.chdir('/home')
                quick = os.spawnvp(os.P_NOWAIT, 'quick', ['quick'])
                live = os.spawnve(os.P_NOWAIT, '/bin/live.py', ['snapshot name', 'saved'], {'MARK': 'saved'})
                wait(lambda: SHARED.get('ready') and quick not in _process.runtime().active)
                armed = True
                wait(lambda: globals().get('RESTORED'))
                assert os.waitpid(quick, 0) == (quick, 6 << 8)
                assert os.waitpid(live, os.WNOHANG) == (0, 0)
                SHARED['resume'] = True
                assert os.waitpid(live, 0) == (live, 9 << 8)
                wait(lambda: not _process.runtime().active and not _program._tasks)
                assert not _process.runtime().children and not sys._contexts and not osfs._cwds
                passed = True
                """);
        host.runUntil("armed");
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.vm.globals.put("RESTORED", true);
        restored.runUntil("passed");
        assertEquals(Boolean.TRUE, restored.vm.globals.get("passed"));
    }

    @Test void parentBlockedInsideWaitSpawnResumesAfterSnapshot() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                FAKE_VFS.files['/bin/child.py'] = '''
                import os, time
                SHARED['child_parent'] = os.getppid()
                SHARED['ready'] = True
                while not SHARED.get('resume'):
                    time.sleep(0.01)
                raise SystemExit(11)
                '''
                FAKE_VFS.files['/bin/parent.py'] = '''
                import os
                code = os.spawnlp(os.P_WAIT, 'child', 'child')
                SHARED['parent_resumed'] = code
                raise SystemExit(code + 1)
                '''
                parent = os.spawnlp(os.P_NOWAIT, 'parent', 'parent')
                wait(lambda: SHARED.get('ready'))
                assert SHARED['child_parent'] == parent
                armed = True
                wait(lambda: globals().get('RESTORED'))
                SHARED['resume'] = True
                assert os.waitpid(parent, 0) == (parent, 12 << 8)
                assert SHARED['parent_resumed'] == 11
                assert not _process.runtime().children and not _process.runtime().active
                passed = True
                """);
        host.runUntil("armed");
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.vm.globals.put("RESTORED", true);
        restored.runUntil("passed");
    }
}
