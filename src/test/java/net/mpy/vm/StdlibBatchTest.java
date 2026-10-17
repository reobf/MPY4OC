package net.mpy.vm;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StdlibBatchTest {
    @ParameterizedTest @ValueSource(strings = {"re", "csv", "statistics", "operator"})
    void commonLibrariesMatchCPython(String module) throws Exception { StdlibTestSupport.golden("batch-" + module); }

    @Test void minMaxGeneratorsKeywordsAndShortCircuit() throws Exception {
        StdlibTestSupport.run("""
            def fail(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected error')
            assert max(x*x for x in range(5)) == 16
            assert min((x for x in []), default=42) == 42
            assert max([], default=None) is None
            assert min(4, -7, 5, key=abs) == 4
            assert max(['aa', 'bb', 'c'], key=len) == 'aa'
            assert max([1, 2], key=None) == 2
            fail(TypeError, lambda: max(1, 2, default=0))
            fail(TypeError, lambda: min([1], wrong=0))
            fail(ValueError, lambda: min([]))
            fail(TypeError, lambda: max())
            seen = []
            def items():
                seen.append(1)
                yield True
                raise AssertionError('any consumed too far')
            assert any(items()) and seen == [1]
            def falses():
                yield False
                raise AssertionError('all consumed too far')
            assert not all(falses())
            assert all([]) and not any([])
            it = (x for x in [1])
            assert next(it, 99) == 1 and next(it, 99) == 99
            class It:
                def __init__(self):
                    self.n = 0
                def __iter__(self):
                    return self
                def __next__(self):
                    self.n += 1
                    if self.n > 2:
                        raise StopIteration
                    return self.n
            it = It()
            assert iter(it) is it
            assert next(it) == 1 and next(it) == 2 and next(it, None) is None
            it = It()
            for value in it:
                assert value == 1 and it.n == 1
                break
            assert next(it) == 2
            data = [1, 2, 0, 9]
            assert list(iter(lambda: data.pop(0), 0)) == [1, 2] and data == [9]
            fail(TypeError, lambda: next([1]))
            passed = True
            """);
    }

    @Test void generatorsKeysAndLibraryObjectsSurviveSnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
            import re, csv, operator, queue
            from io import StringIO
            pattern = re.compile(r'(?P<item>\\w+)')
            matches = pattern.finditer('a bb ccc')
            assert next(matches).group() == 'a'
            stream = StringIO('a,b\\n"c,d",e\\n')
            rows = csv.reader(stream)
            assert next(rows) == ['a', 'b']
            assert stream.tell() == 4
            getter = operator.itemgetter('n')
            q = queue.Queue(2)
            q.put({'n': 5})
            def key(value):
                global armed
                if value == 3:
                    armed = True
                    yieldJava()
                return -value
            result = min((n for n in [2, 3, 1]), key=key)
            assert result == 3
            assert next(matches).groupdict() == {'item': 'bb'}
            assert next(rows) == ['c,d', 'e']
            assert getter(q.get()) == 5
            q.task_done()
            q.join()
            passed = True
            """);
        for (int i = 0; i < 100000 && !Boolean.TRUE.equals(vm.globals.get("armed")); i++) vm.step(new int[] {1});
        assertEquals(Boolean.TRUE, vm.globals.get("armed"));
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(), new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }

    @Test void queueBlockingTimeoutsTaskTrackingAndOrdering() throws Exception {
        assertEquals(java.util.List.of("done"), ThreadingTest.run("queue.py", """
            import queue, threading, time
            OUT = []
            def fail(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected error')
            q = queue.Queue(1)
            q.put(1)
            fail(queue.Full, lambda: q.put_nowait(2))
            got = []
            def consume():
                time.sleep(0.1)
                got.append(q.get())
                q.task_done()
                got.append(q.get())
                q.task_done()
            worker = threading.Thread(target=consume)
            worker.start()
            q.put(2, timeout=1)
            q.join()
            worker.join()
            assert got == [1, 2] and q.empty()
            fail(queue.Empty, lambda: q.get(timeout=0.1))
            fail(ValueError, q.task_done)
            fail(ValueError, lambda: q.get(timeout=-1))
            q.put(1)
            fail(queue.Full, lambda: q.put(2, timeout=0.1))
            for cls, expected in ((queue.Queue, [3, 1, 2]), (queue.LifoQueue, [2, 1, 3]), (queue.PriorityQueue, [1, 2, 3])):
                items = cls()
                for value in [3, 1, 2]:
                    items.put(value)
                assert [items.get_nowait() for i in range(3)] == expected
            simple = queue.SimpleQueue()
            simple.put(4)
            assert simple.get() == 4
            fail(queue.Empty, simple.get_nowait)
            OUT.append('done')
            """));
    }

    @Test void blockedQueueConsumerSurvivesSnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
            import queue, threading, time
            q = queue.Queue(1)
            received = []
            waiting = False
            def consume():
                global waiting
                waiting = True
                received.append(q.get())
                q.task_done()
            worker = threading.Thread(target=consume)
            worker.start()
            while not waiting:
                time.sleep(0)
            time.sleep(0.1)
            armed = True
            yieldJava()
            q.put('restored')
            q.join()
            worker.join()
            assert received == ['restored']
            passed = True
            """);
        long[] clock = {0};
        HostFunction time = args -> clock[0];
        vm.globals.put("__time_ms", time);
        for (int i = 0; i < 1000 && !Boolean.TRUE.equals(vm.globals.get("armed")); i++) {
            vm.step(new int[] {200}); clock[0] += 50;
        }
        assertEquals(Boolean.TRUE, vm.globals.get("armed"));
        var globals = new HashMap<String, Object>();
        globals.put("__time_ms", time);
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(), globals, null);
        for (int i = 0; i < 1000 && !restored.isFinished(); i++) {
            restored.step(new int[] {10000}); clock[0] += 50;
        }
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }

    @Test void invalidLibraryInputsFailExplicitly() throws Exception {
        StdlibTestSupport.run("""
            import csv, re, statistics as s, operator as o
            from io import StringIO
            def fail(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected error')
            fail(re.error, lambda: re.compile('('))
            fail(ValueError, lambda: re.compile('a', re.L))
            fail(TypeError, lambda: re.compile(b'a'))
            fail(RuntimeError, lambda: re.fullmatch('(a+)+$', 'a' * 4000 + '!'))
            fail(IndexError, lambda: re.match('(a)', 'a').group(2))
            fail(csv.Error, lambda: list(csv.reader(['"broken'], strict=True)))
            fail(TypeError, lambda: csv.reader([], delimiter='xx'))
            old = csv.field_size_limit(2)
            fail(csv.Error, lambda: list(csv.reader(['abc'])))
            csv.field_size_limit(old)
            csv.register_dialect('semi', delimiter=';')
            assert list(csv.reader(['a;b'], 'semi')) == [['a', 'b']]
            csv.unregister_dialect('semi')
            fail(csv.Error, lambda: csv.get_dialect('semi'))
            fail(s.StatisticsError, lambda: s.mean([]))
            fail(s.StatisticsError, lambda: s.variance([1]))
            fail(s.StatisticsError, lambda: s.fmean([1], [0]))
            fail(s.StatisticsError, lambda: s.geometric_mean([0]))
            fail(TypeError, lambda: o.itemgetter())
            fail(TypeError, lambda: o.attrgetter(1))
            fail(TypeError, lambda: o.concat(1, 2))
            items = {1, 2}
            assert o.ior(items, {3}) is items and items == {1, 2, 3}
            assert o.isub(items, {2}) is items and items == {1, 3}
            values = [1, 2]
            assert o.imul(values, 2) is values and values == [1, 2, 1, 2]
            passed = True
            """);
    }
}
