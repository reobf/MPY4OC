package net.mpy.vm;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RandomTest {
    @Test void hostStepsMatchOriginalPythonGenerator() throws Exception {
        StdlibTestSupport.golden("random");
    }

    @Test void stateSurvivesSnapshotInTheMiddleOfASequence() throws Exception {
        Vm vm = StdlibTestSupport.start("""
                import random
                random.seed(-1)
                first = random.getrandbits(64)
                yieldJava()
                result = [random.getrandbits(64), random.randrange(100), random.random()]
                passed = True
                """);
        vm.step(new int[] {100_000});
        assertFalse(vm.isFinished());
        byte[] snapshot = vm.saveSnapshot();
        StdlibTestSupport.finish(vm);
        Vm restored = Vm.fromSnapshot(snapshot, new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(vm.globals.get("result").toString(), restored.globals.get("result").toString());
    }

    @Test void zeroAndWideBitRequestsAndConvenienceMethods() throws Exception {
        StdlibTestSupport.run("""
                import random
                random.seed(1)
                assert random.getrandbits(0) == 0
                assert random.getrandbits(64) == 1082269761
                random.seed(1)
                big = random.getrandbits(129)
                random.seed(1)
                assert big == (random.getrandbits(64) << 65) | (random.getrandbits(64) << 1) | random.getrandbits(1)
                random.seed(1)
                assert random.randint(3, 3) == 3
                assert random.choice(['a']) == 'a'
                assert random.uniform(4, 4) == 4
                items = [1, 2, 3, 4]
                random.shuffle(items)
                assert sorted(items) == [1, 2, 3, 4]
                for fn in (lambda: random.getrandbits(-1), lambda: random.randrange(0)):
                    try:
                        fn()
                        raise AssertionError('expected ValueError')
                    except ValueError:
                        pass
                passed = True
                """);
    }
}
