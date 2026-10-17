package net.mpy.vm;

import org.junit.jupiter.api.Test;
import net.mpy.runtime.Json;
import net.mpy.runtime.PyException;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void matchesCpythonGoldenValuesAndErrors() throws Exception {
        StdlibTestSupport.golden("json");
    }

    @Test void hooksKeepDuplicateKeysAndDefaultDetectsCycles() throws Exception {
        StdlibTestSupport.run("""
                import json, io
                assert json.loads('{"a":1,"a":2}', object_pairs_hook=lambda p: p) == [('a', 1), ('a', 2)]
                seen = []
                def hook(obj):
                    seen.append(obj)
                    return ('object', obj)
                result = json.loads('{"outer": [{"inner": 1}]}', object_hook=hook)
                assert len(seen) == 2 and seen[0] == {'inner': 1}
                assert result == ('object', {'outer': [('object', {'inner': 1})]})
                seen = []
                json.loads('{"a":{"v":1},"a":{"v":2}}', object_hook=hook)
                assert len(seen) == 3 and seen[0] == {'v': 1} and seen[1] == {'v': 2}
                assert json.loads('{"a":{"b":1}}', object_pairs_hook=lambda p: p, object_hook=lambda x: 99) == [('a', [('b', 1)])]
                class Item:
                    pass
                item = Item()
                assert json.dumps([item], default=lambda x: {'item': 3}) == '[{"item": 3}]'
                shared = [1]
                assert json.dumps([shared, shared], default=lambda x: None) == '[[1], [1]]'
                circular = []
                circular.append(circular)
                try:
                    json.dumps(circular, check_circular=False)
                    raise AssertionError('unchecked cycle accepted')
                except RecursionError:
                    pass
                for fn in (lambda: json.dumps(circular), lambda: json.dumps(circular, default=lambda x: None), lambda: json.dumps(item, default=lambda x: x)):
                    try:
                        fn()
                        raise AssertionError('cycle accepted')
                    except ValueError as e:
                        assert str(e) == 'Circular reference detected', str(e)
                assert json.dumps({(1, 2): item}, skipkeys=True, default=lambda x: 1) == '{}'
                fp = io.StringIO()
                assert json.dump({'answer': 42}, fp, separators=(',', ':')) is None
                fp.seek(0)
                assert json.load(fp) == {'answer': 42}
                assert json.loads(b'{"a":1}') == {'a': 1}
                assert json.loads(bytearray(b'[1]')) == [1]
                assert isinstance(bytearray(), bytearray) and not isinstance('text', bytearray)
                assert json.loads('"\\\\ud800"') == chr(0xd800)
                assert json.loads('"a\\nb"', strict=False) == 'a\\nb'
                assert json.dumps([float('nan'), float('inf'), -float('inf')]) == '[NaN, Infinity, -Infinity]'
                assert json.loads('NaN') != json.loads('NaN')
                assert json.loads('1e400') == float('inf')
                for fn in (lambda: json.dumps(float('nan'), allow_nan=False), lambda: json.dumps(float('inf'), allow_nan=False)):
                    try:
                        fn()
                        raise AssertionError('non-finite accepted')
                    except ValueError:
                        pass
                for fn in (lambda: json.dumps({1: 'a', '2': 'b'}, sort_keys=True), lambda: json.dumps(item), lambda: json.dumps({}, unknown=True), lambda: json.loads('{}', unknown=True)):
                    try:
                        fn()
                        raise AssertionError('invalid arguments accepted')
                    except TypeError:
                        pass
                passed = True
                """);
    }

    @Test void malformedOrDeepInputCannotEscapeAsJavaErrors() {
        PyException error = assertThrows(PyException.class, () -> Json.loads("[".repeat(2000) + "0" + "]".repeat(2000)));
        assertEquals("RecursionError", error.pyType);
    }
}
