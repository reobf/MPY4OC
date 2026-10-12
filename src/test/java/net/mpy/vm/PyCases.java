package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.function.Executable;

/**
 * Runs Python expressions, each in a fresh VM, and compares what they give --
 * {@code "type repr(value)"} or {@code "ExceptionType: message"} -- with the
 * expected text (CPython's output, so the cases can be re-checked there).
 */
final class PyCases {
    private PyCases() {}

    private static final String PRELUDE = String.join("\n",
            "import math",
            "class F(float):",
            "    pass",
            "class I(int):",
            "    pass",
            "class T(tuple):",
            "    pass",
            "class C:",
            "    def __str__(self):",
            "        return 'C-str'",
            "    def __repr__(self):",
            "        return 'C-repr'",
            "class D:",
            "    def __int__(self):",
            "        return 7",
            "    def __index__(self):",
            "        return 7",
            "    def __float__(self):",
            "        return 7.5",
            "class R:",
            "    def __round__(self, n=None):",
            "        return ('rounded', n)",
            "class M:",
            "    def __getitem__(self, k):",
            "        return k * 2",
            "class Fm:",
            "    def __format__(self, spec):",
            "        return 'F' + spec",
            "def run(f):",
            "    try:",
            "        r = f()",
            "        return type(r).__name__ + ' ' + repr(r)",
            "    except Exception as e:",
            "        return type(e).__name__ + ': ' + str(e)",
            "");

    static String eval(String expr) {
        String program = PRELUDE + "result = run(lambda: " + expr + ")\n";
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "case.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        for (int i = 0; i < 1_000_000 && !vm.isFinished(); i++) vm.step();
        assertTrue(vm.isFinished(), expr + " must finish within its instruction budget");
        return (String) vm.globals.get("result");
    }

    /** {expression, expected} pairs; every mismatch is reported, not just the first. */
    static void check(String[][] cases) {
        List<Executable> checks = new ArrayList<>();
        for (String[] c : cases) checks.add(() -> assertEquals(c[1], eval(c[0]), c[0]));
        assertAll(checks);
    }
}
