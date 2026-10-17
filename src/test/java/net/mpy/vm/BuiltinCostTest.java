package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

/**
 * min/max/all/any/iter/next are Python functions when a call needs Python frames (generators,
 * user objects, key=/default=) and host functions otherwise. The host path must stay one call
 * instruction, not a frame per element.
 */
class BuiltinCostTest {
    /** Instructions one call of {@code body} costs inside a loop, with the loop's own cost taken out. */
    private static double perCall(String body) throws Exception {
        return (cost(body) - cost("pass")) / 2000.0;
    }

    private static long cost(String body) throws Exception {
        String source = "xs = [0] * 50\nys = list(range(100))\nit = iter(range(100000))\nfor i in range(2000):\n    " + body + "\n";
        var module = MpyLoader.load(MpyCross.compile(source, "cost.py"), new QstrPool());
        var vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        long spent = 0;
        while (!vm.isFinished()) {
            int[] remaining = {10_000};
            vm.step(remaining);
            spent += 10_000 - remaining[0];
            assertTrue(spent < 50_000_000L, "runaway: " + body);
        }
        return spent;
    }

    @Test void plainArgumentsStayOnTheHostPath() throws Exception {
        for (String call : new String[] {"max(1.5, 2.5)", "min(3, 4)", "max(ys)", "min('abc')", "any(xs)", "all(ys)", "iter(xs)", "next(it)"})
            assertTrue(perCall(call) <= 8, call + " costs " + perCall(call) + " instructions per call");
    }

    @Test void hostMinMaxFollowPythonComparisons() throws Exception {
        StdlibTestSupport.run("""
                nan = float('nan')
                assert max([nan, 1]) != max([nan, 1]) and max([1, nan]) == 1
                assert min([nan, 1]) != min([nan, 1]) and min([1, nan]) == 1
                assert str(max([-0.0, 0.0])) == '-0.0' and str(min([0.0, -0.0])) == '0.0'
                assert max(2, 2.5, True) == 2.5 and min(10**20, 1.5) == 1.5
                assert max(['b', 'a']) == 'b' and max([(1, 2), (1, 3)]) == (1, 3) and max(b'ab') == 98
                assert max({3: 'x', 7: 'y'}) == 7 and max({4, 9}) == 9 and max(range(5)) == 4
                class Item:
                    def __init__(self, n):
                        self.n = n
                    def __lt__(self, other):
                        return self.n < other.n
                    def __gt__(self, other):
                        return self.n > other.n
                assert max([Item(1), Item(5), Item(2)]).n == 5 and min(Item(4), Item(3)).n == 3
                assert max([(1, Item(2)), (1, Item(9))], key=lambda p: p[1].n)[1].n == 9
                for bad in (lambda: max([]), lambda: min(())):
                    try:
                        bad()
                        raise AssertionError('empty accepted')
                    except ValueError:
                        pass
                gen = (x for x in [3])
                assert iter(gen) is gen and next(gen) == 3
                assert any(x > 2 for x in [1, 3]) and not all(iter([1, 0]))
                passed = True
                """);
    }
}
