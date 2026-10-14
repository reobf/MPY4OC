package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import net.mpy.bytecode.Opcodes;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.CodeObject;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import org.junit.jupiter.api.Test;

/**
 * .mpy files can come from a computer's disk, so bytecode that the loader accepts may still be
 * wrong when it runs. An operand past the constant table fails the program with a catchable
 * RuntimeError; the VM keeps running instead of throwing a Java exception out of step() (which
 * crashes the whole computer and every program on it).
 */
class MalformedBytecodeTest {
    static final String PROGRAM = String.join("\n",
            "def f():",
            "    return 1.5",
            "try:",
            "    f()",
            "    result = 'no error'",
            "except RuntimeError as e:",
            "    result = str(e)",
            "after = 'still running'",
            "");

    @Test void anOperandPastTheConstantTableIsAPythonError() {
        MpyModule module = MpyLoader.load(MpyCross.compile(PROGRAM, "case.py"), new QstrPool());
        CodeObject f = module.root.children.get(0);
        byte[] data = f.funData;
        int corrupted = 0;
        for (int i = f.bytecodeStart(); i + 1 < data.length; i++) {
            if ((data[i] & 0xFF) == Opcodes.LOAD_CONST_OBJ && data[i + 1] < module.objTable.length) {
                data[i + 1] = 0x7F;   // one-byte operand, far past the table
                corrupted++;
            }
        }
        assertEquals(1, corrupted, "f loads its float from the constant table once");
        Vm vm = new Vm(module, new HashMap<>());
        vm.start(new Frame(module.root));
        for (int i = 0; i < 100_000 && !vm.isFinished(); i++) vm.step();
        assertTrue(vm.isFinished());
        assertTrue(((String) vm.globals.get("result")).startsWith("invalid bytecode or VM state: "), (String) vm.globals.get("result"));
        assertEquals("still running", vm.globals.get("after"));
    }
}
