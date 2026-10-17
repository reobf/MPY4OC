package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

/**
 * for loops, comprehensions and generator expressions walk a range in place: range(10**12) allocates
 * nothing, each value costs a FOR_ITER instruction, and a snapshot stores the range and position.
 * Builtins that drain an iterable on the host keep refusing ranges too large to hold.
 */
class RangeIterTest {
    @Test void loopsOverHugeRangesWithoutBuildingThem() throws Exception {
        StdlibTestSupport.run("""
                hits = []
                for i in range(10**12):
                    if i == 3:
                        break
                    hits.append(i)
                assert hits == [0, 1, 2]
                for i in range(5, -10**15, -4):
                    assert i == 5
                    break
                assert sum(1 for _ in range(10**15, 10**15 + 5)) == 5
                assert next(x for x in range(10**12) if x * x > 50) == 8
                assert [x for x in range(0)] == [] and [x for x in range(3, 0, -1)] == [3, 2, 1]
                assert list(range(-3, 3, 2)) == [-3, -1, 1] and list(range(3, -3, -2)) == [3, 1, -1]
                it = iter(range(2, 11, 4))
                assert list(it) == [2, 6, 10] and next(it, 'end') == 'end'
                def gen():
                    yield from range(10**12)
                g = gen()
                assert [next(g), next(g), next(g)] == [0, 1, 2]
                for build in (lambda: list(range(10**12)), lambda: sum(range(10**12)), lambda: sorted(range(10**12))):
                    try:
                        build()
                        raise AssertionError('a host-side drain accepted range(10**12)')
                    except MemoryError:
                        pass
                passed = True
                """);
    }

    @Test void aRangeLoopAndASuspendedGeneratorResumeFromASnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
                seen = []
                pending = (x for x in range(10**12, 0, -7))
                assert next(pending) == 10**12
                for i in range(7, 10**13, 5):
                    seen.append(i)
                    if len(seen) == 3:
                        armed = True
                        yieldJava()
                    if len(seen) == 5:
                        break
                assert seen == [7, 12, 17, 22, 27], seen
                assert next(pending) == 10**12 - 7 and next(pending) == 10**12 - 14
                passed = True
                """);
        while (!Boolean.TRUE.equals(vm.globals.get("armed"))) vm.step(new int[] {1});
        assertTrue(vm.estimatedMemory() < 1_000_000, "range iterators hold no values: " + vm.estimatedMemory());
        byte[] snapshot = vm.saveSnapshot();
        assertTrue(snapshot.length < 1_000_000, "snapshot holds the ranges, not their values: " + snapshot.length);
        StdlibTestSupport.finish(vm);
        Vm restored = Vm.fromSnapshot(snapshot, new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }
}
