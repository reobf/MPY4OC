package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

class CallableTest {
    @Test
    void closuresAndOtherCallablesAreCallable() {
        String program = String.join("\n",
                "def outer():",
                "    count = 0",
                "    def inner():",
                "        return count + 1",
                "    return inner, lambda: count",
                "inner, captured = outer()",
                "class C:",
                "    def m(self): pass",
                "    def __call__(self): pass",
                "class D:",
                "    pass",
                "assert callable(inner) and callable(captured), 'closures (functions capturing outer locals)'",
                "assert callable(lambda: 0) and callable(outer) and callable(len) and callable(int)",
                "assert callable(C) and callable(C().m) and callable(C()) and not callable(D())",
                "assert not callable(1) and not callable('x') and not callable(None)",
                "passed = True", "");
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "callable_test.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        for (int i = 0; i < 50_000 && !vm.isFinished(); i++) vm.step();
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
