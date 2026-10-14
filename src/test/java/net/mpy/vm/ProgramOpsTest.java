package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

class ProgramOpsTest {
    private Vm vm(String source) {
        var module = MpyLoader.load(MpyCross.compile(source, "ops-test.py"), new QstrPool());
        var vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        return vm;
    }

    private long total(Vm vm) { return (Long) vm.programOpsSnapshot().map.get("total"); }

    private void consistent(Vm vm) {
        var stats = vm.programOpsSnapshot();
        var programs = (PyObj.PyDict) stats.map.get("programs");
        long sum = (Long) stats.map.get("retired");
        for (Object value : programs.map.values()) sum += (Long) value;
        assertEquals(stats.map.get("total"), sum, "no instruction can be lost or counted twice");
    }

    @Test void countsExactDispatchesIncludingUnhandledRaise() {
        var vm = vm("x = 0\nfor i in range(12):\n    x += i\nraise ValueError('end')\n");
        int steps = 0;
        try {
            while (!vm.isFinished() && steps < 10000) { steps++; vm.step(); }
            fail("the final exception must escape");
        } catch (PyException expected) {
            assertEquals(steps, total(vm));
            consistent(vm);
        }
    }

    @Test void ownershipSwitchesAreIndependentOfInstructionSliceSize() {
        String source = """
                import _program
                _program.set_current('A')
                for i in range(30):
                    x = i * i
                _program.set_current('B')
                for i in range(70):
                    x = i * i
                _program.set_current(None)
                done = True
                """;
        var a = vm(source);
        var b = vm(source);
        for (int i = 0; i < 100000 && !a.isFinished(); i++) a.step();
        for (int i = 0; i < 1000 && !b.isFinished(); i++) b.step(new int[] {73});
        assertTrue(a.isFinished() && b.isFinished());
        assertEquals(total(a), total(b));
        var ac = (PyObj.PyDict) a.programOpsSnapshot().map.get("programs");
        var bc = (PyObj.PyDict) b.programOpsSnapshot().map.get("programs");
        assertEquals(ac.map, bc.map);
        assertTrue((Long) ac.map.get("B") > (Long) ac.map.get("A"));
        consistent(a);
    }

    @Test void inlinePythonCallbacksAreCountedWithoutDoubleChargingOuterCall() {
        var vm = vm("""
                import _program
                class Value:
                    def __str__(self):
                        total = 0
                        for i in range(100):
                            total += i
                        return str(total)
                _program.set_current('callback')
                result = str(Value())
                _program.set_current(None)
                """);
        int calls = 0;
        while (!vm.isFinished() && calls < 100000) { calls++; vm.step(); }
        assertTrue(vm.isFinished());
        assertEquals("4950", vm.globals.get("result"));
        assertTrue(total(vm) > calls + 300, "nested runLoop dispatches must be included");
        var counts = (PyObj.PyDict) vm.programOpsSnapshot().map.get("programs");
        assertTrue((Long) counts.map.get("callback") > 300);
        consistent(vm);
    }

    @Test void parkedAndRetriedOpcodeIsCountedEachTime() {
        var vm = vm("park()\nfinished = True\n");
        int[] attempts = {0};
        vm.globals.put("park", (HostFunction) args -> {
            if (attempts[0]++ == 0) vm.hostPause();
            return PyObj.NONE;
        });
        // hostPause's instruction rewind is the real async-component retry path.
        for (int i = 0; i < 20 && !vm.isFinished(); i++) vm.step();
        assertTrue(vm.isFinished());
        assertEquals(2, attempts[0]);
        consistent(vm);
    }

    @Test void countersSurviveSnapshotAndOldSnapshotWithoutCountersStillLoads() {
        var vm = vm("""
                import _program
                _program.set_current('saved')
                ready = True
                x = 0
                for i in range(50):
                    x += i
                _program.set_current(None)
                """);
        while (!Boolean.TRUE.equals(vm.globals.get("ready"))) vm.step();
        byte[] snapshot = vm.saveSnapshot();
        long before = total(vm);
        var restored = Vm.fromSnapshot(snapshot);
        assertEquals(before, total(restored));
        while (!restored.isFinished()) restored.step();
        assertTrue(total(restored) > before);
        assertEquals(1225L, restored.globals.get("x"));
        consistent(restored);
        int tail = -1;
        for (int i = snapshot.length - 4; i >= 0; i--)
            if (snapshot[i] == 'O' && snapshot[i + 1] == 'P' && snapshot[i + 2] == 'S' && snapshot[i + 3] == '1') { tail = i; break; }
        assertTrue(tail > 0);
        var legacy = Vm.fromSnapshot(Arrays.copyOf(snapshot, tail));
        assertEquals(0, total(legacy));
        while (!legacy.isFinished()) legacy.step();
        assertEquals(1225L, legacy.globals.get("x"));
        consistent(legacy);
    }
}
