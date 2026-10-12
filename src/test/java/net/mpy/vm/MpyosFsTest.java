package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

/** MPYOS's fs.py against a fake OC filesystem component. */
class MpyosFsTest {
    @Test void readFileDecodesACharacterThatStraddlesTwoChunks() throws Exception {
        String fake = String.join("\n",
                "class FakeComponent:",
                "    def __init__(self):",
                "        self.files = {}",
                "        self.handles = {}",
                "    def invoke(self, address, method, *args):",
                "        if method == 'exists':",
                "            return args[0] in self.files",
                "        if method == 'open':",
                "            handle = len(self.handles) + 1",
                "            self.handles[handle] = [self.files[args[0]], 0]",
                "            return handle",
                "        if method == 'read':",
                "            state = self.handles[args[0]]",
                "            chunk = state[0][state[1]:state[1] + args[1]]",
                "            state[1] += len(chunk)",
                "            return chunk if chunk else None",
                "        if method == 'close':",
                "            return None",
                "        raise ValueError(method)",
                "component = FakeComponent()", "");
        String test = String.join("\n", "",
                "text = 'a' * 4095 + '中文' + 'b' * 5000 + '末'",
                "component.files['/x'] = text.encode('utf-8')",
                "disk = Filesystem('disk')",
                "assert disk.read_file('/x') == text, 'the 3-byte 中 spans bytes 4095..4097'",
                "assert disk.read_file('/missing') is None",
                "assert disk.read_bytes('/x') == text.encode('utf-8')",
                "passed = True", "");
        String fs = Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/fs.py"));
        run(fake + fs + test);
    }

    private static final String FAKE_COMPONENT = String.join("\n",
            "class FakeComponent:",
            "    def __init__(self, data):",
            "        self.data, self.at = data, 0",
            "    def invoke(self, address, method, *args):",
            "        if method == 'open':",
            "            return 1",
            "        if method == 'read':",
            "            chunk = self.data[self.at:self.at + min(args[1], 2048)]   # OC's maxReadBuffer",
            "            self.at += len(chunk)",
            "            return chunk if chunk else None",
            "        return None", "");

    /** Every BIOS copy reads /init.py whole before decoding it (reads are at most 2048 bytes). */
    @Test void biosReadsInitPyAcrossReadBoundaries() throws Exception {
        for (String file : new String[] {"bios/bios.py", "bios/bios_debug.py", "mpyos/bios.py"}) {
            String bios = Files.readString(Path.of("src/main/resources/assets/mpy4oc/" + file));
            int reader = bios.indexOf("def _read_file(");
            int end = bios.indexOf("\n\n", bios.indexOf("component.invoke(fs, \"close\", h)", reader));
            String function = bios.substring(reader, end) + "\n";
            run(FAKE_COMPONENT + function + String.join("\n", "",
                    "text = '# ' + 'a' * 2045 + '中文\\nx = 1\\n'",
                    "component = FakeComponent(text.encode('utf-8'))",
                    "assert _read_file('disk', '/init.py') == text, '" + file + "'",
                    "passed = True", ""));
        }
    }

    @Test void unquoteReplacesBadUtf8LikeCPython() throws Exception {
        String parse = Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/urllib/parse.py"));
        int start = parse.indexOf("def unquote(");
        int end = parse.indexOf("\n\n\n", start);
        run(parse.substring(start, end) + String.join("\n", "",
                "assert unquote('%ff') == '\\ufffd' and unquote('caf%E9') == 'caf\\ufffd' and unquote('%E4%B8%AD') == '中'",
                "passed = True", ""));
    }

    private static void run(String program) {
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "mpyos_test.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        for (int i = 0; i < 2_000_000 && !vm.isFinished(); i++) vm.step();
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
