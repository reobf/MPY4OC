package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

class MpyosReplTest {
    private static final Path BIN = Path.of("src/main/resources/assets/mpy4oc/mpyos/bin");

    // Execute the shipped shell command using the real compiler, VM and sandbox
    // builtins. Only terminal input/output and the shell's filesystem are mocked.
    private static final class Session {
        final List<String> output = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        final Map<String, Object> globals = new HashMap<>();
        final PyModule tty = new PyModule("tty", "<test>", null);
        final PyModule sh = new PyModule("sh", "<test>", null);
        int readIndex;

        Session(String... lines) {
            tty.ns.put("read", (HostFunction) args -> {
                prompts.add((String) args[0]);
                if (readIndex == lines.length) return PyObj.NONE;
                String line = lines[readIndex++];
                return line == null ? PyObj.NONE : line;
            });
            HostFunction print = args -> {
                output.add(String.join(" ", Arrays.stream(args).map(String::valueOf).toArray(String[]::new)));
                return PyObj.NONE;
            };
            tty.ns.put("print", print);
            sh.ns.put("g", new PyObj.PyDict());
            globals.put("tty", tty);
            globals.put("sh", sh);
            globals.put("args", new PyObj.PyList());
            globals.put("print", print);
        }

        Vm run() throws Exception {
            String source = Files.readString(BIN.resolve("python.py"), StandardCharsets.UTF_8);
            MpyModule module = MpyLoader.load(MpyCross.compile(source, "python.py"), new QstrPool());
            Vm vm = new Vm(module, globals);
            vm.setCompiler((text, filename, mode) -> MpyCross.compile(text, filename));
            vm.start(new Frame(module.root));
            for (int i = 0; i < 1_000_000 && !vm.isFinished(); i++) vm.step();
            assertTrue(vm.isFinished(), "REPL must finish within its instruction budget");
            return vm;
        }

        void assertOutput(String... expected) {
            assertEquals(Arrays.asList(expected), output.subList(1, output.size()), "output after banner");
        }
    }

    @Test
    void functionBodyKeepsIndentationAndExecutesAfterBlankLine() throws Exception {
        Session session = new Session("name = 'world'", "def greet():", "    print('Hello ' + name)",
                "", "greet()", "name", "exit");
        Vm vm = session.run();
        session.assertOutput("Hello world", "'world'");
        assertEquals(Arrays.asList(">>> ", ">>> ", "... ", "... ", ">>> ", ">>> ", ">>> "), session.prompts);
        assertFalse(vm.globals.containsKey("greet"), "definitions stay in the sandbox");
        PyObj.PyList history = (PyObj.PyList) vm.globals.get("history");
        assertTrue(history.items.contains("    print('Hello ' + name)"), "history preserves indentation");
    }

    @Test
    void nestedSuitesElseAndClassDefinitionsShareOneBlock() throws Exception {
        Session session = new Session("def choose(n):", "    if n:", "        return 'yes'", "    else:",
                "        return 'no'", "", "choose(0)", "choose(1)",
                "class Greeter:", "    def say(self):", "        return 'hi'", "", "Greeter().say()", "exit");
        session.run();
        session.assertOutput("'no'", "'yes'", "'hi'");
    }

    @Test
    void compoundLoopAndTryExceptWork() throws Exception {
        Session session = new Session("total = 0", "for i in range(3):", "    total += i", "", "total",
                "try:", "    raise ValueError('test')", "except ValueError:", "    print('caught')", "", "exit");
        session.run();
        session.assertOutput("3", "caught");
    }

    @Test
    void openDelimitersIgnoreQuotedCharactersAndComments() throws Exception {
        Session session = new Session("values = [", "    '(', # ]", "", "    ')',", "]",
                "len(", "    values", ")", "mapping = {", "    'key': [1, 2]", "}", "mapping['key'][1]", "exit");
        session.run();
        session.assertOutput("2", "2");
        assertEquals("... ", session.prompts.get(3), "blank line inside brackets must keep reading");
        assertEquals(">>> ", session.prompts.get(5), "closed brackets execute without a blank line");
    }

    @Test
    void tripleQuotedStringsAndBackslashContinuationsKeepWhitespace() throws Exception {
        Session session = new Session("text = '''first", "", "  last'''", "text",
                "value = 1 + \\", "    2", "value", "exit");
        session.run();
        session.assertOutput("'first\\n\\n  last'", "3");
        assertEquals(Arrays.asList(">>> ", "... ", "... ", ">>> ", ">>> ", "... ", ">>> ", ">>> "), session.prompts);
    }

    @Test
    void decoratorsCanPrecedeMultilineFunctionDefinition() throws Exception {
        Session session = new Session("decorate = lambda f: f", "@decorate", "def answer():",
                "    return 42", "", "answer()", "exit");
        session.run();
        session.assertOutput("42");
    }

    @Test
    void syntaxErrorsDiscardPendingInputAndLeaveReplUsable() throws Exception {
        Session session = new Session("def broken():", "", "1 +", "'unterminated", "[)", "6 * 7", "exit");
        session.run();
        assertEquals(6, session.output.size());
        for (int i = 1; i <= 4; i++) assertTrue(session.output.get(i).startsWith("error: "), session.output.toString());
        assertEquals("42", session.output.get(5));
        assertEquals(Arrays.asList(">>> ", "... ", ">>> ", ">>> ", ">>> ", ">>> ", ">>> "), session.prompts);
    }

    @Test
    void interruptCancelsBlockWithoutExitingOrExecutingIt() throws Exception {
        Session session = new Session("def abandoned():", "    print('must not run')", null, "2 + 3", "exit");
        session.run();
        session.assertOutput("KeyboardInterrupt", "5");
        assertEquals(Arrays.asList(">>> ", "... ", "... ", ">>> ", ">>> "), session.prompts);
    }

    @Test
    void ordinaryLinesAndKeywordPrefixesDoNotStartBlocks() throws Exception {
        Session session = new Session("# just a comment: ([", "'colon: ('", "if1 = 7", "if1",
                "label = 'colon:'", "7 + 1", "exit()");
        session.run();
        session.assertOutput("'colon: ('", "7", "8");
        assertTrue(session.prompts.stream().allMatch(">>> "::equals));
    }

    @Test
    void fileModeStillReceivesArgvAndDoesNotReadFromTerminal() throws Exception {
        Session session = new Session();
        session.globals.put("args", new PyObj.PyList(new ArrayList<>(List.of("hello.py", "world"))));
        session.sh.ns.put("resolve", (HostFunction) args -> "/home/" + args[0]);
        PyModule fs = new PyModule("fs", "<test>", null);
        fs.ns.put("read_file", (HostFunction) args -> "def greet():\n    print('Hello ' + argv[1])\ngreet()\n");
        session.sh.ns.put("fs", fs);
        session.run();
        assertEquals(List.of("Hello world"), session.output);
        assertTrue(session.prompts.isEmpty());
    }

    @Test
    void allCommandAliasesShipTheSameImplementation() throws Exception {
        byte[] canonical = Files.readAllBytes(BIN.resolve("python.py"));
        assertArrayEquals(canonical, Files.readAllBytes(BIN.resolve("python3.py")));
        assertArrayEquals(canonical, Files.readAllBytes(BIN.resolve("py.py")));
    }
}
