package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.TkTestRegistration;

class MpyosInterruptTest {
    @BeforeAll static void register() { TkTestRegistration.ensure(); }

    /** Feed the real architecture drainSignals path, including its persistent modifier state. */
    private static final class Input {
        final ArrayDeque<Signal> signals = new ArrayDeque<>();
        final MpyArchitecture arch;
        Input(TkPythonTest.Host host) throws Exception {
            arch = new MpyArchitecture((Machine) Proxy.newProxyInstance(Machine.class.getClassLoader(),
                    new Class<?>[] {Machine.class}, (proxy, method, args) -> {
                        if (method.getName().equals("popSignal")) return signals.poll();
                        throw new UnsupportedOperationException(method.getName());
                    }));
            var field = MpyArchitecture.class.getDeclaredField("vm");
            field.setAccessible(true); field.set(arch, host.vm);
        }
        void key(String kind, String keyboard, int character, int code) throws Exception {
            signals.add(new Signal() {
                @Override public String name() { return kind; }
                @Override public Object[] args() { return new Object[] {keyboard, character, code}; }
            });
            var drain = MpyArchitecture.class.getDeclaredMethod("drainSignals");
            drain.setAccessible(true); drain.invoke(arch);
        }
        void press(boolean strong) throws Exception {
            key("key_down", "kb", 0, 29);
            if (strong) key("key_down", "kb", 0, 56);
            key("key_down", "kb", 3, 46);
        }
        void release() throws Exception {
            key("key_up", "kb", 3, 46);
            key("key_up", "kb", 0, 56);
            key("key_up", "kb", 0, 29);
        }
    }

    private static PyObj.PyDict shared(TkPythonTest.Host host) { return (PyObj.PyDict) host.vm.globals.get("SHARED"); }
    private static void until(TkPythonTest.Host host, String key) {
        for (int i = 0; i < 30000 && !host.vm.isFinished(); i++) {
            host.vm.step(new int[] {300});
            if (shared(host) != null && Boolean.TRUE.equals(shared(host).map.get(key))) return;
        }
        fail("did not reach " + key);
    }

    @Test void ordinaryKeyWaitsForPollStrongUpgradeBreaksLoopAndRunsFinallyOnce() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                FAKE_VFS.files['/bin/app.py'] = '''
                import time
                try:
                    SHARED['ready'] = True
                    while True:
                        pass
                finally:
                    time.sleep(0)
                    SHARED['cleaned'] = True
                '''
                engine = _process.runtime()
                job = engine.foreground(engine.prepare(['app']))
                assert job['returncode'] == 130 and job['error'] == 'KeyboardInterrupt', job
                assert SHARED['cleaned'] and not _program._foreground
                FAKE_VFS.files['/bin/next.py'] = 'import time\\ntime.sleep(0)\\nSHARED["next"] = True'
                sh.run_command('next', [])
                assert SHARED['next'] and not engine.active
                passed = True
                """);
        until(host, "ready");
        Input input = new Input(host);
        input.press(false);
        for (int i = 0; i < 40; i++) host.vm.step(new int[] {300});
        assertFalse(shared(host).map.containsKey("cleaned"));
        input.release();
        input.press(true);
        host.runUntil("passed");
    }

    @Test void sleepZeroAndEventWaitUseRealBootstrapAndDoNotRequireAnotherKey() throws Exception {
        for (String wait : new String[] {"time.sleep(0)", "time.sleep(10000)", "computer.pullSignal()", "computer._yield_tick()"}) {
            var host = TkPythonTest.shellHost("""
                    import time
                    globals().pop('__time_sleep')    # the real sleep: a park until the deadline
                    FAKE_VFS.files['/bin/app.py'] = %s
                    sh.run_command('app', [])
                    assert SHARED['caught'] and SHARED['cleaned'] and not _program._foreground
                    passed = True
                    """.formatted(quote("import time\ntry:\n    SHARED['ready'] = True\n    while True:\n        " + wait
                            + "\nexcept KeyboardInterrupt:\n    SHARED['caught'] = True\nfinally:\n    time.sleep(0)\n    SHARED['cleaned'] = True\n")));
            until(host, "ready");
            new Input(host).press(false);
            host.runUntil("passed");
        }
    }

    @Test void joinInterruptTargetsMainAndLeavesBackgroundJobRunning() throws Exception {
        var host = TkPythonTest.shellHost("""
                import time
                FAKE_VFS.files['/bin/background.py'] = '''
                import time
                while True:
                    SHARED['ticks'] = SHARED.get('ticks', 0) + 1
                    time.sleep(0)
                '''
                FAKE_VFS.files['/bin/app.py'] = '''
                import threading, time
                def work():
                    try:
                        while True:
                            time.sleep(0)
                    except KeyboardInterrupt:
                        SHARED['wrong_thread'] = True
                    finally:
                        SHARED['worker_cleaned'] = True
                t = threading.Thread(target=work)
                t.start()
                SHARED['ready'] = True
                try:
                    t.join()
                finally:
                    SHARED['main_cleaned'] = True
                '''
                sh.spawn('background', [])
                bg = sh.jobs[0]
                wait(lambda: SHARED.get('ticks', 0) > 2)
                sh.run_command('app', [])
                wait(lambda: SHARED.get('worker_cleaned'))
                assert SHARED['main_cleaned'] and not SHARED.get('wrong_thread')
                before = SHARED['ticks']
                wait(lambda: SHARED['ticks'] > before + 2)
                assert not bg['done'] and not _program._foreground
                sh.run_command('kill', [str(bg['id'])])
                wait(lambda: bg['done'])
                passed = True
                """);
        until(host, "ready");
        new Input(host).press(false);
        host.runUntil("passed");
    }

    @Test void foregroundSpawnAndPendingStrongInterruptSurviveSnapshot() throws Exception {
        var host = TkPythonTest.shellHost("""
                FAKE_VFS.files['/bin/child.py'] = '''
                try:
                    SHARED['ready'] = True
                    while True:
                        pass
                finally:
                    SHARED['cleaned'] = True
                '''
                status = os.spawnvp(os.P_WAIT, 'child', ['child'])
                assert status == 130 and SHARED['cleaned']
                assert not _program._foreground
                passed = True
                """);
        until(host, "ready");
        new Input(host).press(true);
        host.vm = Vm.fromSnapshot(host.vm.saveSnapshot(host.registry), new HashMap<>(host.vm.globals), host.registry);
        host.runUntil("passed");
    }

    @Test void keyboardModifiersArePerDeviceAndRepeatDoesNotInterruptCleanupTwice() throws Exception {
        var host = TkPythonTest.shellHost("""
                FAKE_VFS.files['/bin/app.py'] = '''
                try:
                    SHARED['ready'] = True
                    while True:
                        pass
                except KeyboardInterrupt:
                    SHARED['caught'] = True
                finally:
                    while not SHARED.get('release'):
                        computer._yield_tick()
                    SHARED['cleaned'] = True
                '''
                sh.run_command('app', [])
                assert SHARED['cleaned']
                passed = True
                """);
        until(host, "ready");
        Input input = new Input(host);
        input.key("key_down", "other", 0, 56);
        input.press(false);
        for (int i = 0; i < 20; i++) host.vm.step(new int[] {300});
        assertFalse(shared(host).map.containsKey("caught"));
        input.release();
        input.press(true);
        until(host, "caught");
        for (int i = 0; i < 4; i++) input.key("key_down", "kb", 3, 46);
        shared(host).map.put("release", true);
        host.runUntil("passed");
    }

    @Test void replCanContinueAfterInterruptingAnExecutingLoop() throws Exception {
        var host = TkPythonTest.shellHost("""
                FAKE_VFS.files['/bin/python.py'] = PYTHON_SOURCE
                lines = ['while True:', '    SHARED["ready"] = True', '', '6 * 7', 'exit']
                def read(prompt, *args):
                    return lines.pop(0)
                tty.read = read
                sh.run_command('python', [])
                assert 'KeyboardInterrupt' in tty.out and '42' in tty.out, tty.out
                assert not _program._foreground
                passed = True
                """);
        host.vm.globals.put("PYTHON_SOURCE", Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/python.py")));
        until(host, "ready");
        new Input(host).press(true);
        host.runUntil("passed");
    }

    @Test void asyncioInterruptRunsCoroutineCleanupAndReturnsToShell() throws Exception {
        var host = TkPythonTest.shellHost("""
                FAKE_VFS.files['/bin/app.py'] = '''
                import asyncio
                async def main():
                    try:
                        SHARED['ready'] = True
                        while True:
                            await asyncio.sleep(0)
                    finally:
                        SHARED['cleaned'] = True
                asyncio.run(main())
                '''
                sh.run_command('app', [])
                assert SHARED['cleaned'] and not _program._foreground
                passed = True
                """);
        until(host, "ready");
        new Input(host).press(false);
        host.runUntil("passed");
    }

    @Test void backgroundParentCannotGiveItsChildTheForegroundKeyboard() throws Exception {
        var host = TkPythonTest.shellHost("""
                FAKE_VFS.files['/bin/parent.py'] = "import os\\nos.spawnvp(os.P_WAIT, 'child', ['child'])"
                FAKE_VFS.files['/bin/child.py'] = '''
                import time
                SHARED['ready'] = True
                while not SHARED.get('release'):
                    time.sleep(0)
                '''
                sh.spawn('parent', [])
                bg = sh.jobs[0]
                wait(lambda: SHARED.get('ready'))
                assert not _program._foreground
                wait(lambda: SHARED.get('release'))
                wait(lambda: bg['done'])
                passed = True
                """);
        until(host, "ready");
        assertFalse(host.vm.requestKeyboardInterrupt(true));
        shared(host).map.put("release", true);
        host.runUntil("passed");
    }

    private static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }
}
