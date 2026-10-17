package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

class IoBuiltinTest {
    @Test void snapshotWithoutIoBuiltinsCanBeRestoredAndSavedAgain() throws Exception {
        var code = MpyLoader.load(MpyCross.compile("value = 42", "old.py"), new QstrPool());
        var old = new Vm(code, new HashMap<>());
        old.globals.remove("open");
        old.globals.remove("input");
        old.globals.put("next", Vm.NEXT_BUILTIN); // older snapshots predate its Python trampoline
        old.loadedModules.remove("_builtin_io");
        old.start(new Frame(code.root));
        while (!old.isFinished()) old.step();
        var restored = Vm.fromSnapshot(old.saveSnapshot(), new HashMap<>(), null);
        assertNotNull(restored.globals.get("open"));
        assertNotNull(restored.globals.get("input"));
        assertDoesNotThrow(() -> restored.saveSnapshot());
    }

    /** A raw stream that hands out at most {@code k} bytes per read, so every buffer refill
     *  can split a UTF-8 sequence or a CRLF pair; {@code buffering=k} makes the file ask for
     *  that little at a time. Expected values come from CPython 3.12's TextIOWrapper. */
    static final String RAW = """
            import io
            class Raw:
                def __init__(self, data, k):
                    self.data, self.k, self.pos = data, k, 0
                def read(self, n):
                    chunk = self.data[self.pos:self.pos + min(n, self.k)]
                    self.pos += len(chunk)
                    return chunk
                def write(self, data):
                    raise OSError('read only')
                def seek(self, offset, whence):
                    self.pos = max(0, (0, self.pos, len(self.data))[whence] + offset)
                    return self.pos
                def tell(self):
                    return self.pos
                def close(self):
                    pass
            FILES = {}
            K = [4096]
            io.install(opener=lambda path, mode: Raw(FILES[path], K[0]))
            def raises(kind, fn):
                try:
                    fn()
                except kind:
                    return
                raise AssertionError('expected ' + kind.__name__)
            """;

    @Test void textReadsDecodeWholeBuffersAndMatchCPythonAtEveryChunkBoundary() throws Exception {
        String source = RAW + """
                FILES['t'] = 'A\\u00e9\\u6c34\\U0001f600\\r\\nb\\rc\\n\\r'.encode('utf-8')
                FILES['bad'] = b'A\\xe2(B\\xffC\\xf0\\x9f\\x98'
                expect = {
                    None: ('A\\u00e9\\u6c34\\U0001f600\\nb\\nc\\n\\n', ['A\\u00e9\\u6c34\\U0001f600\\n', 'b\\n', 'c\\n', '\\n'], ['A\\u00e9', 3, '\\u6c34\\U0001f600\\n', 'b\\n', 'c\\n\\n']),
                    '': ('A\\u00e9\\u6c34\\U0001f600\\r\\nb\\rc\\n\\r', ['A\\u00e9\\u6c34\\U0001f600\\r\\n', 'b\\r', 'c\\n', '\\r'], ['A\\u00e9', 3, '\\u6c34\\U0001f600\\r\\n', 'b\\r', 'c\\n\\r']),
                    '\\n': ('A\\u00e9\\u6c34\\U0001f600\\r\\nb\\rc\\n\\r', ['A\\u00e9\\u6c34\\U0001f600\\r\\n', 'b\\rc\\n', '\\r'], ['A\\u00e9', 3, '\\u6c34\\U0001f600\\r\\n', 'b\\rc', '\\n\\r']),
                    '\\r': ('A\\u00e9\\u6c34\\U0001f600\\r\\nb\\rc\\n\\r', ['A\\u00e9\\u6c34\\U0001f600\\r', '\\nb\\r', 'c\\n\\r'], ['A\\u00e9', 3, '\\u6c34\\U0001f600\\r', '\\nb\\r', 'c\\n\\r']),
                    '\\r\\n': ('A\\u00e9\\u6c34\\U0001f600\\r\\nb\\rc\\n\\r', ['A\\u00e9\\u6c34\\U0001f600\\r\\n', 'b\\rc\\n\\r'], ['A\\u00e9', 3, '\\u6c34\\U0001f600\\r\\n', 'b\\rc', '\\n\\r']),
                }
                for k in (1, 2, 3, 5, 4096):
                    K[0] = k
                    for newline, (whole, lines, seq) in expect.items():
                        with open('t', newline=newline, buffering=max(k, 2)) as f:
                            assert f.read() == whole, (k, newline, f.read())
                        with open('t', newline=newline, buffering=max(k, 2)) as f:
                            assert f.readlines() == lines, (k, newline)
                            assert list(f) == []
                        with open('t', newline=newline, buffering=max(k, 2)) as f:
                            got = [f.read(2), f.tell(), f.readline(), f.readline(3), f.read()]
                            assert got == seq, (k, newline, got)
                    with open('t', buffering=max(k, 2)) as f:
                        assert f.read(2) == 'A\\u00e9' and f.tell() == 3
                        assert f.read(2) == '\\u6c34\\U0001f600' and f.tell() == 10
                        assert f.read(1) == '\\n' and f.tell() == 12
                        assert f.readline() == 'b\\n' and f.tell() == 14
                        f.seek(3)
                        assert f.read(2) == '\\u6c34\\U0001f600' and f.tell() == 10
                        assert f.read() == '\\nb\\nc\\n\\n'
                    with open('bad', buffering=max(k, 2)) as f:
                        raises(UnicodeDecodeError, f.read)
                    with open('bad', errors='replace', buffering=max(k, 2)) as f:
                        assert f.read() == 'A\\ufffd(B\\ufffdC\\ufffd', (k, f.read())
                    with open('bad', errors='ignore', buffering=max(k, 2)) as f:
                        assert f.read() == 'A(BC'
                passed = True
                """;
        assertEquals(true, run(source, 2_000_000).globals.get("passed"));
    }

    @Test void readingALargeTextFileCostsFewInstructionsPerKilobyte() throws Exception {
        String source = RAW + """
                line = 'line of text \\u6c34 ' + 'x' * 60 + '\\r\\n'
                FILES['big'] = (line * 3000).encode('utf-8')     # about 240 KB
                with open('big') as f:
                    text = f.read()
                assert len(text) == 3000 * (len(line) - 1), len(text)
                with open('big') as f:
                    lines = f.readlines()
                assert len(lines) == 3000 and lines[-1] == line[:-2] + '\\n'
                with open('big') as f:
                    count = 0
                    for row in f:
                        count += 1
                assert count == 3000
                passed = True
                """;
        // Streaming file iteration now charges readline's Python work to the caller's
        // budget. The old eager callSync drain hid that work from this counter. Keep a
        // per-line ceiling well below the ~36M needed for a per-character decoder.
        Vm vm = run(source, 2_000_000);
        assertEquals(true, vm.globals.get("passed"), "three passes over 240 KB must fit in 2M instructions");
    }

    private static Vm run(String source, int budget) throws Exception {
        var module = MpyLoader.load(MpyCross.compile(source, "chunks.py"), new QstrPool());
        var vm = new Vm(module, new HashMap<>());
        vm.setCompiler((text, name, mode) -> MpyCross.compile(text, name));
        vm.start(new Frame(module.root));
        int spent = 0;
        while (!vm.isFinished()) {
            int[] remaining = {10_000};
            vm.step(remaining);
            spent += 10_000 - remaining[0];
            assertTrue(spent <= budget, "instruction budget exceeded: " + spent);
        }
        System.out.println("instructions spent: " + spent);
        return vm;
    }

    @Test void standaloneVmHasBuiltinsAndAcceptsHostAdaptersWithoutMpyos() throws Exception {
        String source = """
                import io
                for operation in (lambda: open('/missing'), lambda: input()):
                    try:
                        operation()
                        raise AssertionError('missing backend must fail')
                    except OSError:
                        pass
                prompts = []
                def reader(prompt):
                    prompts.append(prompt)
                    return 'answer'
                io.install(opener=lambda path, mode: io.BytesIO(b'hello'), reader=reader)
                with open('virtual', **{'mode': 'rb'}) as f:
                    assert f.read(2) == b'he'
                    assert f.read() == b'llo'
                assert input('> ') == 'answer' and prompts == ['> ']
                ns = {}
                exec('result = open("virtual", "rb").read()', ns)
                assert ns['result'] == b'hello'
                passed = True
                """;
        var module = MpyLoader.load(MpyCross.compile(source, "builtin_io.py"), new QstrPool());
        var vm = new Vm(module, new HashMap<>());
        vm.setCompiler((text, name, mode) -> MpyCross.compile(text, name));
        vm.start(new Frame(module.root));
        for (int i = 0; i < 100000 && !vm.isFinished(); i++) vm.step(new int[] {300});
        assertEquals(true, vm.globals.get("passed"));
    }
}
