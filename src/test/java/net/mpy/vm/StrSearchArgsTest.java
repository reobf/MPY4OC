package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

/** str search methods with CPython's optional start/end (golden values from CPython 3.12). */
class StrSearchArgsTest {
    @Test void searchMethodsTakeStartAndEnd() {
        String program = String.join("\n",
                "assert 'abcabc'.find('b', 2) == 4",
                "assert 'abcabc'.find('b', 2, 4) == -1",
                "assert 'abcabc'.find('b', 2, 5) == 4",
                "assert 'abcabc'.find('c', -2) == 5",
                "assert 'abcabc'.find('', 6) == 6",
                "assert 'abcabc'.find('', 7) == -1",
                "assert 'abcabc'.find('a', None, 3) == 0",
                "assert 'abcabc'.rfind('b') == 4",
                "assert 'abcabc'.rfind('b', 0, 4) == 1",
                "assert 'abcabc'.rfind('b', 2) == 4",
                "assert 'abcabc'.rfind('abc', 0, 5) == 0",
                "assert 'abcabc'.rfind('', 1, 2) == 2",
                "assert 'abcabc'.rfind('x') == -1",
                "assert 'abcabc'.index('c', 3) == 5",
                "assert 'abcabc'.rindex('a', 0, 3) == 0",
                "assert 'a\\nb\\nc\\nd'.count('\\n', 0, 4) == 2",
                "assert 'a\\nb\\nc\\nd'.count('\\n', 2) == 2",
                "assert 'aaaa'.count('aa') == 2",
                "assert 'aaaa'.count('aa', 1) == 1",
                "assert 'abc'.count('', 1) == 3",
                "assert 'abc'.count('', 4) == 0",
                "assert 'abc'.count('', 2, 1) == 0",
                "assert 'abc'.count('b', -2, -1) == 1",
                "assert 'hello.py'.endswith(('.txt', '.py')) == True",
                "assert 'hello.py'.endswith('.py', 0, 7) == False",
                "assert 'hello.py'.endswith('hello', 0, 5) == True",
                "assert 'hello'.startswith('ll', 2) == True",
                "assert 'hello'.startswith(('x', 'he')) == True",
                "assert 'hello'.startswith('', 5) == True",
                "assert 'hello'.startswith('', 6) == False",
                "assert 'hello'.endswith('', 6) == False",
                "assert 'hello'.startswith('lo', -2) == True",
                "assert 'aXbXc'.replace('X', '-', 1) == 'a-bXc'",
                "assert 'aXbXc'.replace('X', '-', 0) == 'aXbXc'",
                "assert 'aXbXc'.replace('X', '-', -1) == 'a-b-c'",
                "assert 'abc'.replace('', '-', 2) == '-a-bc'",
                "assert 'ab'.replace('', '-', 10) == '-a-b-'",
                "assert 'ab'.replace('', '-') == '-a-b-'",
                "for bad, kind in ((lambda: 'abc'.index('x', 1), ValueError), (lambda: 'abc'.rindex('a', 1), ValueError),",
                "                  (lambda: 'abc'.startswith(1), TypeError), (lambda: 'abc'.find(1), TypeError)):",
                "    try:",
                "        bad()",
                "        raise AssertionError('accepted')",
                "    except kind:",
                "        pass",
                "passed = True", "");
        run(program);
    }

    @Test void decodingIsStrictUtf8UnlessAskedOtherwise() {
        run(String.join("\n",
                "cases = ((b'\\xff\\xfe', \"'utf-8' codec can't decode byte 0xff in position 0: invalid start byte\", '\\ufffd\\ufffd', ''),",
                "         (b'a\\xe4\\xb8', \"'utf-8' codec can't decode bytes in position 1-2: unexpected end of data\", 'a\\ufffd', 'a'),",
                "         (b'a\\xe4Ab', \"'utf-8' codec can't decode byte 0xe4 in position 1: invalid continuation byte\", 'a\\ufffdAb', 'aAb'),",
                "         (b'\\xc0\\x80', \"'utf-8' codec can't decode byte 0xc0 in position 0: invalid start byte\", '\\ufffd\\ufffd', ''),",
                "         (b'ok\\xf4\\x90\\x80\\x80', \"'utf-8' codec can't decode byte 0xf4 in position 2: invalid continuation byte\", None, 'ok'))",
                "for data, message, replaced, ignored in cases:",
                "    for decode in (lambda: data.decode('utf-8'), lambda: bytearray(data).decode(), lambda: str(data, 'utf-8')):",
                "        try:",
                "            decode()",
                "            raise AssertionError(data)",
                "        except UnicodeDecodeError as error:",
                "            assert str(error) == message, (data, str(error))",
                "    assert data.decode('utf-8', 'ignore') == ignored, data",
                "    if replaced is not None:",
                "        assert data.decode('utf-8', 'replace') == replaced and str(data, 'utf-8', 'replace') == replaced, data",
                "assert issubclass(UnicodeDecodeError, ValueError)",
                "assert '中文'.encode('utf-8').decode() == '中文'",
                "assert b'abc'.decode('utf-8', 'bogus') == 'abc', 'the handler is looked up only when needed'",
                "assert b'\\xe9'.decode('latin-1') == '\\xe9' and str(b'\\xe9t\\xe9', 'ISO-8859-1') == '\\xe9t\\xe9'",
                "assert b'caf\\xc3\\xa9'.decode('UTF_8') == 'caf\\xe9' and b'ok'.decode('ascii') == 'ok'",
                "assert b'a\\xffb'.decode('ascii', 'replace') == 'a\\ufffdb' and b'a\\xffb'.decode('ascii', 'ignore') == 'ab'",
                "assert b'a\\xffb'.decode('utf-8', 'backslashreplace') == 'a\\\\xffb'",
                "assert b'a\\xe4\\xb8'.decode('utf-8', 'backslashreplace') == 'a\\\\xe4\\\\xb8'",
                "assert b'a\\xffb'.decode('utf-8', 'surrogateescape') == 'a' + chr(0xdcff) + 'b'",
                "for data, count in ((b'\\xed\\xa0\\x80', 3), (b'\\xed\\xa0\\x80\\xed\\xb0\\x80', 6), (b'\\xed\\xa0', 2)):",
                "    assert data.decode('utf-8', 'replace') == '\\ufffd' * count, data",
                "assert b'\\xe2\\x82A'.decode('utf-8', 'replace') == '\\ufffdA'",
                "errors = ((b'\\xc2', \"'utf-8' codec can't decode byte 0xc2 in position 0: unexpected end of data\"),",
                "          (b'a\\xc2', \"'utf-8' codec can't decode byte 0xc2 in position 1: unexpected end of data\"),",
                "          (b'\\xe2\\x82\\xac\\xe2', \"'utf-8' codec can't decode byte 0xe2 in position 3: unexpected end of data\"),",
                "          (b'\\xe0\\x80', \"'utf-8' codec can't decode byte 0xe0 in position 0: invalid continuation byte\"),",
                "          (b'\\xf4\\x90', \"'utf-8' codec can't decode byte 0xf4 in position 0: invalid continuation byte\"),",
                "          (b'\\xe2\\x82A', \"'utf-8' codec can't decode bytes in position 0-1: invalid continuation byte\"),",
                "          (b'\\xf0\\x9f\\x98A', \"'utf-8' codec can't decode bytes in position 0-2: invalid continuation byte\"))",
                "for data, message in errors:",
                "    try:",
                "        data.decode()",
                "        raise AssertionError(data)",
                "    except UnicodeDecodeError as error:",
                "        assert str(error) == message, (data, str(error))",
                "try:",
                "    b'\\xc3\\xa9'.decode('ascii')",
                "    raise AssertionError('ascii accepted')",
                "except UnicodeDecodeError as error:",
                "    assert str(error) == \"'ascii' codec can't decode byte 0xc3 in position 0: ordinal not in range(128)\"",
                "try:",
                "    b'a\\xffb'.decode()",
                "except UnicodeDecodeError as error:",
                "    assert (error.encoding, error.object, error.start, error.end, error.reason) == ('utf-8', b'a\\xffb', 1, 2, 'invalid start byte')",
                "    assert error.args == ('utf-8', b'a\\xffb', 1, 2, 'invalid start byte')",
                "assert str(UnicodeDecodeError('utf-8', b'\\xff', 0, 1, 'boom')) == \"'utf-8' codec can't decode byte 0xff in position 0: boom\"",
                "for bad, kind, message in ((lambda: b'abc'.decode('bogus'), LookupError, 'unknown encoding: bogus'),",
                "                           (lambda: b'\\xff'.decode('utf-8', 'bogus'), LookupError, \"unknown error handler name 'bogus'\"),",
                "                           (lambda: b'abc'.decode(None), TypeError, \"decode() argument 'encoding' must be str, not None\"),",
                "                           (lambda: b'abc'.decode('utf-8', None), TypeError, \"decode() argument 'errors' must be str, not None\"),",
                "                           (lambda: 'abc'.startswith(('x', 1)), TypeError, 'tuple for startswith must only contain str, not int'),",
                "                           (lambda: 'abc'.endswith(('x', None)), TypeError, 'tuple for endswith must only contain str, not NoneType')):",
                "    try:",
                "        bad()",
                "        raise AssertionError(message)",
                "    except kind as error:",
                "        assert str(error) == message, str(error)",
                "passed = True", ""));
    }

    @Test void boundsPastTheLongRangeWork() {
        run(String.join("\n",
                "import sys",
                "assert 'abc'.find('c', 0, sys.maxsize) == 2 and 'abc'.startswith('a', 0, sys.maxsize)",
                "assert 'aaa'.replace('a', 'b', sys.maxsize) == 'bbb' and 'abcabc'.find('a', 10**30) == -1 and 'abc'.find('c', -10**30) == 2",
                "assert 'abc'[0:sys.maxsize] == 'abc' and [1, 2, 3][0:sys.maxsize] == [1, 2, 3]",
                "assert 'abc'[:2**32] == 'abc' and 'abc'[:2**31] == 'abc' and 'abc'[-10**30:] == 'abc'",
                "assert 'abc'[::2**32] == 'a' and 'abc'[1::2**40] == 'b' and 'abc'[::-2**40] == 'c'",
                "assert list(range(10)[::20]) == [0] and (1, 2, 3)[::10**20] == (1,)",
                "passed = True", ""));
    }

    private static void run(String program) {
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "str_search_test.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        for (int i = 0; i < 50_000 && !vm.isFinished(); i++) vm.step();
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
