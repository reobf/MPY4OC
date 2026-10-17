package net.mpy.vm;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FinalStdlibTest {
    @Test void attributeDescriptorsAndReverseTies() throws Exception {
        StdlibTestSupport.run("""
            class Properties:
                @property
                def value(self):
                    return 42
                @property
                def absent(self):
                    raise AttributeError('missing')
                @property
                def failed(self):
                    raise ValueError('failure')
            obj = Properties()
            assert getattr(obj, 'value') == 42 and hasattr(obj, 'value')
            assert getattr(obj, 'absent', 7) == 7 and not hasattr(obj, 'absent')
            class Missing(AttributeError):
                pass
            class Derived:
                @property
                def absent(self):
                    raise Missing('missing')
            assert getattr(Derived(), 'absent', 9) == 9
            assert not hasattr(Derived(), 'absent')
            for operation in (lambda: getattr(obj, 'failed', None), lambda: hasattr(obj, 'failed')):
                try:
                    operation()
                except ValueError:
                    pass
                else:
                    raise AssertionError('property error swallowed')
            data = [('a',2),('b',1),('c',2),('d',1)]
            expected = [('a',2),('c',2),('b',1),('d',1)]
            assert sorted(data, key=lambda x:x[1], reverse=True) == expected
            data.sort(key=lambda x:x[1], reverse=True)
            assert data == expected
            passed = True
            """);
    }

    @Test void iteratorSharingAndCollectionErrors() throws Exception {
        StdlibTestSupport.run("""
            import itertools as it
            from collections import Counter, defaultdict
            def fail(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected failure')
            d = defaultdict(list)
            d['a'].append(1)
            assert dict(d.copy()) == {'a':[1]}
            assert isinstance(d | {'b':2}, defaultdict)
            assert dict({'b':2} | d) == {'b':2,'a':[1]}
            d.default_factory = None
            fail(KeyError, lambda: d['b'])
            fail(TypeError, lambda: defaultdict(123))
            c = Counter(a=1)
            saved = c
            c += Counter(a=2,b=-1)
            assert c is saved
            assert dict(c) == {'a':3}
            c |= Counter(a=2,b=4)
            assert dict(c) == {'a':3,'b':4}
            c &= Counter(a=2,b=9)
            assert dict(c) == {'a':2,'b':4}
            fail(TypeError, lambda: list(Counter(a=1.5).elements()))
            fail(NotImplementedError, lambda: Counter.fromkeys('ab'))
            source = iter(range(10))
            assert list(it.islice(source,5,2)) == [] and next(source) == 5
            first, second = it.tee(it.count())
            assert list(it.islice(first,4)) == [0,1,2,3]
            cloned, = it.tee(second,1)
            assert next(cloned) == 0 and next(second) == 0
            groups = it.groupby('AAABB')
            key, old = next(groups)
            assert next(old) == 'A'
            key, new = next(groups)
            assert key == 'B' and list(old) == [] and list(new) == ['B','B']
            fail(ValueError, lambda: it.islice([], -1))
            fail(ValueError, lambda: it.tee([], -1))
            fail(ValueError, lambda: it.batched([],0))
            fail(ValueError, lambda: list(it.batched('abc',2,strict=True)))
            passed = True
            """);
    }

    @Test void utcClockIsIndependentOfGuestUptime() throws Exception {
        Vm vm = StdlibTestSupport.start("""
            from datetime import datetime, date, time, timezone, timedelta
            import logging
            assert datetime.now().isoformat() == '2024-02-29T12:34:56.123456'
            assert date.today().isoformat() == '2024-02-29'
            assert datetime.now(timezone(timedelta(hours=8))).isoformat() == '2024-02-29T20:34:56.123456+08:00'
            record = logging.LogRecord('clock',20,'',0,'msg',(),None)
            assert logging.Formatter().formatTime(record) == '2024-02-29 12:34:56,123'
            assert logging.Formatter('{asctime} {message}', datefmt='%H:%M', style='{').format(record) == '12:34 msg'
            assert time(12,tzinfo=timezone(timedelta(hours=8))) == time(4,tzinfo=timezone.utc)
            assert datetime(2024,1,1) != datetime(2024,1,1,tzinfo=timezone.utc)
            def fail(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected failure')
            fail(TypeError, lambda: datetime(2024,1,1) < datetime(2024,1,1,tzinfo=timezone.utc))
            fail(ValueError, lambda: date(2023,2,29))
            fail(ValueError, lambda: time.fromisoformat('12:34+12:99'))
            fail(OverflowError, lambda: date.max + timedelta(days=1))
            fail(ValueError, lambda: timezone(timedelta(hours=24)))
            passed = True
            """);
        vm.globals.put("__datetime_now", (HostFunction) args -> 1709210096123456L);
        vm.globals.put("__time_ms", (HostFunction) args -> 17L);
        StdlibTestSupport.finish(vm);
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }

    @Test void parserActionsHelpErrorsAndLogExceptions() throws Exception {
        StdlibTestSupport.run("""
            import argparse, logging
            from io import StringIO
            def fail(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected failure')
            p = argparse.ArgumentParser(prog='test', description='A test parser', exit_on_error=False)
            p.add_argument('--pair', nargs=2, type=int)
            p.add_argument('--many', nargs='+', type=int)
            p.add_argument('--maybe', nargs='?', const='yes')
            p.add_argument('--ext', action='extend', nargs='+')
            p.add_argument('--hidden', default=argparse.SUPPRESS)
            p.add_argument('file')
            ns = p.parse_args(['--pair','1','-2','--maybe','--ext','a','b','--','input'])
            assert ns.pair == [1,-2] and ns.maybe == 'yes' and ns.ext == ['a','b'] and ns.file == 'input'
            assert not hasattr(ns,'hidden')
            fail(argparse.ArgumentError, lambda: p.parse_args(['--pair','1']))
            fail(argparse.ArgumentError, lambda: p.parse_args(['--many','x']))
            help = StringIO()
            p.print_help(help)
            assert 'A test parser' in help.getvalue() and '--pair' in help.getvalue()
            logger = logging.getLogger('exceptions')
            logger.propagate = False
            logger.setLevel(logging.DEBUG)
            stream = StringIO()
            handler = logging.StreamHandler(stream)
            logger.addHandler(handler)
            try:
                raise ValueError('broken')
            except ValueError:
                logger.exception('failed')
            assert 'failed' in stream.getvalue() and 'ValueError: broken' in stream.getvalue()
            fail(KeyError, lambda: logger.info('x',extra={'name':'bad'}))
            fail(ValueError, lambda: logging.Formatter(style='$'))
            handler.close()
            assert not stream.closed
            passed = True
            """);
    }
    @Test void dictionarySubclassUsesNativeContents() throws Exception {
        StdlibTestSupport.run("""
            from collections import Counter
            a, b = Counter(), Counter({'a':1,'d':2})
            assert not a and bool(b)
            assert repr(a) == 'Counter()'
            assert dict(b) == {'a':1,'d':2}, repr(dict(b))
            assert list(b) == ['a','d'], repr(list(b))
            assert b.get('a',0) == 1
            assert not a.__eq__(b), repr([a.get(k,0) == b.get(k,0) for k in list(a)+list(b)])
            assert not a == b
            passed = True
            """);
    }
    @ParameterizedTest @ValueSource(strings={"collections", "itertools", "datetime", "argparse", "logging"})
    void matchesCPython(String name) throws Exception { StdlibTestSupport.golden("final-" + name); }

    @Test void lazyIteratorsAndDefaultFactorySurviveSnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
            import itertools as it
            from collections import defaultdict, Counter
            a, b = it.tee(iter([1,2,3,4]))
            assert next(a) == 1 and next(a) == 2 and next(b) == 1
            groups = it.groupby('AAABB')
            key, group = next(groups)
            assert key == 'A' and next(group) == 'A'
            combos = it.combinations('abcd', 2)
            assert next(combos) == ('a','b')
            calls = []
            def factory():
                global armed
                calls.append(1)
                armed = True
                yieldJava()
                return []
            d = defaultdict(factory)
            assert d.get('missing') is None and calls == []
            d['x'].append(7)
            assert dict(d) == {'x':[7]} and calls == [1]
            assert list(a) == [3,4] and list(b) == [2,3,4]
            assert list(group) == ['A','A']
            assert [(k,list(v)) for k,v in groups] == [('B',['B','B'])]
            assert list(combos) == [('a','c'),('a','d'),('b','c'),('b','d'),('c','d')]
            c = Counter(a=2)
            assert c['absent'] == 0 and 'absent' not in c
            c.subtract('ab')
            assert dict(c) == {'a':1,'b':-1}
            passed = True
            """);
        for (int i=0;i<200000 && !Boolean.TRUE.equals(vm.globals.get("armed"));i++) vm.step(new int[]{1});
        assertEquals(Boolean.TRUE, vm.globals.get("armed"));
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(), new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }

    @Test void parsersLoggersAndDatesSurviveSnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
            import argparse, logging
            from datetime import datetime, timedelta, timezone
            from io import StringIO
            stream = StringIO()
            logger = logging.getLogger('saved')
            logger.setLevel(logging.INFO)
            logger.propagate = False
            handler = logging.StreamHandler(stream)
            handler.setFormatter(logging.Formatter('%(levelname)s:%(message)s'))
            logger.addHandler(handler)
            parser = argparse.ArgumentParser(prog='saved', exit_on_error=False)
            parser.add_argument('-n', type=int, required=True)
            group = parser.add_mutually_exclusive_group(required=True)
            group.add_argument('--yes', action='store_true')
            group.add_argument('--no', action='store_true')
            when = datetime(2024,2,29,23,59,59,tzinfo=timezone(timedelta(hours=8)))
            armed = True
            yieldJava()
            ns = parser.parse_args(['-n3','--yes'])
            assert ns.n == 3 and ns.yes and not ns.no
            logger.info('%s', (when+timedelta(seconds=2)).isoformat())
            assert stream.getvalue() == 'INFO:2024-03-01T00:00:01+08:00\\n'
            try:
                parser.parse_args(['-n','bad','--yes'])
            except argparse.ArgumentError:
                pass
            else:
                raise AssertionError('invalid integer accepted')
            passed = True
            """);
        for (int i=0;i<200000 && !Boolean.TRUE.equals(vm.globals.get("armed"));i++) vm.step(new int[]{1});
        assertEquals(Boolean.TRUE, vm.globals.get("armed"));
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(), new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }
}
