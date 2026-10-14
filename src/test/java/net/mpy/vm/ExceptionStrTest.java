package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

/**
 * A user exception class's own __str__ and __repr__ are what str(), repr(), print, formatting and
 * the traceback text show (subprocess.CalledProcessError keeps (returncode, cmd) as its args and
 * makes its message in __str__). Without them the args rule stays. Expected text from CPython 3.12.
 */
class ExceptionStrTest {
    static final String PROGRAM = String.join("\n",
            "class Base(Exception):",
            "    pass",
            "class Told(Base):",
            "    def __init__(self, code, cmd):",
            "        Exception.__init__(self, code, cmd)",
            "        self.code = code",
            "    def __str__(self):",
            "        return 'failed with %d' % self.code",
            "class Shown(Exception):",
            "    def __repr__(self):",
            "        return 'Shown!'",
            "class Bad(Exception):",
            "    def __str__(self):",
            "        return 5",
            "e = Told(3, ['a'])",
            "out = [str(e), e.args, '%s' % e, '{}'.format(e), f'{e}', f'{e!r}', str(Shown('x')), repr(Shown('x')),",
            "       str(Base('plain')), repr(Base('plain'))]",
            "try:",
            "    str(Bad())",
            "except TypeError as error:",
            "    out.append(str(error))",
            "try:",
            "    raise Told(4, 'x')",
            "except Base as caught:",
            "    out.append(__tb_format(caught).split(chr(10))[-1])",
            "");

    @Test void userExceptionStrAndReprAreUsed() {
        MpyModule module = MpyLoader.load(MpyCross.compile(PROGRAM, "case.py"), new QstrPool());
        Vm vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        for (int i = 0; i < 1_000_000 && !vm.isFinished(); i++) vm.step();
        assertTrue(vm.isFinished());
        assertEquals("['failed with 3', (3, ['a']), 'failed with 3', 'failed with 3', 'failed with 3',"
                        + " \"Told(3, ['a'])\", 'x', 'Shown!', 'plain', \"Base('plain')\", '__str__ returned non-string (type int)',"
                        + " 'Told: failed with 4']",
                vm.stringify(vm.globals.get("out"), true));
    }
}
