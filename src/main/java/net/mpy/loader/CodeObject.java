package net.mpy.loader;

import java.util.Arrays;
import java.util.List;

/**
 * One unit of loaded code (a module, function, class body, comprehension, or
 * lambda) - the Java analogue of MicroPython's mp_raw_code_t for the bytecode
 * case. Native/viper/asm kinds are recognised but not supported by this loader.
 */
public final class CodeObject {

    public enum Kind { BYTECODE, NATIVE_PY, NATIVE_VIPER, NATIVE_ASM }

    public final Kind kind;
    public final byte[] funData;         // the whole blob: prelude + bytecode
    public final Prelude prelude;        // decoded prelude (bytecode kind only)
    public final List<CodeObject> children;
    /** Back-reference to the module this code belongs to (set by MpyLoader);
     *  frames resolve qstrs/constants against it, so code from several loaded
     *  modules can execute in one VM. */
    public MpyModule module;

    /** Decode the prelude's compressed line-number table: the source line for a
     *  bytecode offset (mp_bytecode_get_source_line). Returns 1 with no info. */
    public int sourceLine(int bcOffset) {
        if (prelude == null) return 1;
        int i = prelude.lineInfoStart;
        int top = prelude.lineInfoStart + prelude.lineInfoLen;
        int line = 1;
        while (i < top) {
            int c = funData[i] & 0xff;
            int bcInc, lineInc;
            if ((c & 0x80) == 0) {          // 0b0LLBBBBB
                bcInc = c & 0x1f;
                lineInc = c >> 5;
                i += 1;
            } else {                        // 0b1LLLBBBB 0bLLLLLLLL
                bcInc = c & 0x0f;
                lineInc = ((c << 4) & 0x700) | (funData[i + 1] & 0xff);
                i += 2;
            }
            if (bcOffset >= bcInc) {
                bcOffset -= bcInc;
                line += lineInc;
            } else {
                break;
            }
        }
        return line;
    }

    public CodeObject(Kind kind, byte[] funData, Prelude prelude, List<CodeObject> children) {
        this.kind = kind;
        this.funData = funData;
        this.prelude = prelude;
        this.children = children;
    }

    /** The executable bytecode region (prelude stripped). */
    public byte[] bytecode() {
        return Arrays.copyOfRange(funData, prelude.bytecodeStart, funData.length);
    }

    /** Offset of the bytecode within {@link #funData}. */
    public int bytecodeStart() { return prelude.bytecodeStart; }

    /** Length of the executable bytecode. */
    public int bytecodeLength() { return funData.length - prelude.bytecodeStart; }
}
