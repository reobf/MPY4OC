package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

class KeyboardInterruptTest {
    private final Vm[] running = new Vm[1];

    private Vm start(String body) {
        String source = "import _program\n_program.set_current('cmd:test')\nstate = {}\nns = {}\n"
                + "scope = __program_foreground(ns)\ntry:\n    exec_sandbox(" + quote(body)
                + ", ns)\nfinally:\n    __program_leave_foreground(scope)\ndone = True\n";
        var module = MpyLoader.load(MpyCross.compile(source, "interrupt-test.py"), new QstrPool());
        var globals = new HashMap<String, Object>();
        globals.put("arm", (HostFunction) args -> {
            assertTrue(running[0].requestKeyboardInterrupt(Boolean.TRUE.equals(args[0])));
            return PyObj.NONE;
        });
        Vm vm = running[0] = new Vm(module, globals);
        vm.setCompiler((text, file, mode) -> MpyCross.compile(text, file));
        vm.start(new Frame(module.root));
        return vm;
    }

    static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    private void finish(Vm vm) {
        for (int i = 0; i < 10000 && !vm.isFinished(); i++) vm.step(new int[] {1000});
        assertTrue(vm.isFinished(), "interrupt must break the loop without exhausting the test budget");
        assertEquals(true, vm.globals.get("done"));
        assertFalse(vm.requestKeyboardInterrupt(true), "finished scope must not receive later keys");
    }

    @Test void ordinaryInterruptWaitsForPollAndFinallyCanYield() {
        Vm vm = start("""
                try:
                    arm(False)
                    for i in range(40):
                        abs(i)
                    state['computed'] = True
                    _program.check()
                    assert False
                except KeyboardInterrupt as error:
                    assert error.args == () and str(error) == ''
                    state['caught'] = True
                finally:
                    yieldJava()
                    _program.check()
                    state['cleaned'] = True
                """);
        // Every opcode is a budget resume; that alone must NOT deliver ordinary Ctrl+C.
        for (int i = 0; i < 100000 && !vm.isFinished(); i++) vm.step();
        finish(vm);
        assertEquals(3, ((PyObj.PyDict) vm.globals.get("state")).map.size());
    }

    @Test void strongInterruptChecksBackEdgesAndAllCallFormsWithinOneBatch() {
        for (String point : new String[] {"while True:\n        pass", "abs(-1)",
                "[].append(1)", "abs(*[-1])", "[].extend(*[[]])"}) {
            Vm vm = start("try:\n    arm(True)\n    " + point + "\n    assert False\n"
                    + "except KeyboardInterrupt:\n    state['caught'] = True\n");
            finish(vm);
            assertEquals(true, ((PyObj.PyDict) vm.globals.get("state")).map.get("caught"), point);
        }
    }

    @Test void ordinaryInterruptChecksYieldAndYieldFrom() {
        for (String value : new String[] {"yield 1", "yield from [1, 2]"}) {
            Vm vm = start("def gen():\n    try:\n        arm(False)\n        " + value
                    + "\n    except KeyboardInterrupt:\n        state['caught'] = True\n    yield 7\n"
                    + "assert next(gen()) == 7\n");
            finish(vm);
            assertEquals(true, ((PyObj.PyDict) vm.globals.get("state")).map.get("caught"));
        }
    }

    @Test void irqDefersInterruptUntilAfterAtomicSection() {
        Vm vm = start("""
                import machine
                irq = machine.disable_irq()
                arm(True)
                _program.check()
                state['atomic'] = True
                try:
                    machine.enable_irq(irq)
                    abs(0)
                    assert False
                except KeyboardInterrupt:
                    state['caught'] = True
                """);
        finish(vm);
        assertEquals(2, ((PyObj.PyDict) vm.globals.get("state")).map.size());
    }

    @Test void pendingInterruptAndYieldResumeSurviveSnapshot() {
        Vm vm = start("""
                try:
                    state['ready'] = True
                    yieldJava()
                    assert False
                except KeyboardInterrupt:
                    state['caught'] = True
                """);
        for (int i = 0; i < 10000; i++) {
            vm.step(new int[] {1000});
            if (((PyObj.PyDict) vm.globals.get("state")).map.containsKey("ready")) break;
        }
        assertTrue(vm.requestKeyboardInterrupt(false));
        var restored = Vm.fromSnapshot(vm.saveSnapshot(), new HashMap<>(vm.globals), null);
        running[0] = restored;
        finish(restored);
        assertEquals(true, ((PyObj.PyDict) restored.globals.get("state")).map.get("caught"));
    }

    @Test void finishingWithoutAPollDropsTheOrdinaryRequest() {
        Vm vm = start("arm(False)\nstate['finished'] = True\n");
        finish(vm);
        assertEquals(true, ((PyObj.PyDict) vm.globals.get("state")).map.get("finished"));
    }
}
