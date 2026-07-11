package net.mpy.bytecode;

import net.mpy.loader.CodeObject;
import net.mpy.loader.MpyModule;
import net.mpy.loader.Prelude;
import net.mpy.runtime.PyObj;

/**
 * Phase 1.5 - a disassembler whose output is byte-for-byte compatible with
 * MicroPython's tools/mpy-tool.py (`-d`), so it can be validated by diffing
 * against the golden .dis.txt files.
 *
 * Formatting mirrors mpy-tool.py exactly, including the quirks:
 *   - "multi" opcodes bake their operand/operator into the mnemonic and carry an
 *     empty argument field (yielding a trailing space on the line);
 *   - the "line info" span shown includes the closure-cell bytes.
 */
public final class Disassembler {

    /** mpy-tool.py prints constants with CPython repr, which keeps printable
     *  unicode RAW (unlike the runtime repr, which follows MicroPython's
     *  non-ASCII escaping). Mirror CPython here so diffs stay byte-exact. */
    private static String pyToolRepr(Object o) {
        if (!(o instanceof String)) return PyObj.repr(o);
        String s = (String) o;
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == q || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", (int) c));
            else sb.append(c);       // printable ASCII and all unicode: raw
        }
        return sb.append(q).toString();
    }

    private static final String[] ARCH_NAMES = {
        "NONE", "X86", "X64", "ARMV6", "ARMV6M", "ARMV7M", "ARMV7EM",
        "ARMV7EMSP", "ARMV7EMDP", "XTENSA", "XTENSAWIN", "RV32IMC", "RV64IMC", "DEBUG",
    };

    /** Expanded opcode mnemonics (multi ranges resolved), matching Opcode.mapping. */
    private static final String[] MAPPING = buildMapping();

    private final MpyModule mod;
    private final StringBuilder out = new StringBuilder();

    private Disassembler(MpyModule mod) { this.mod = mod; }

    /** Disassemble a module to a String matching `mpy-tool.py -d`. */
    public static String disassemble(MpyModule mod, String mpySourceFile) {
        Disassembler d = new Disassembler(mod);
        d.emitModule(mpySourceFile);
        return d.out.toString();
    }

    private void line(String s) { out.append(s).append('\n'); }

    private void emitModule(String mpySourceFile) {
        line("mpy_source_file: " + mpySourceFile);
        line("source_file: " + mod.qstr(0));
        line("header: " + hexlify(new byte[]{
                (byte) 'M', (byte) mod.version,
                (byte) (mod.subVersion | (mod.arch << 2)),
                (byte) mod.smallIntBits}));
        line("arch: " + (mod.arch < ARCH_NAMES.length ? ARCH_NAMES[mod.arch] : "UNKNOWN"));
        line("qstr_table[" + mod.qstrTable.length + "]:");
        for (String q : mod.qstrTable) line("    " + q);
        line("obj_table: " + objTableRepr());
        emitCode(mod.root);
    }

    private String objTableRepr() {
        if (mod.objTable.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < mod.objTable.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pyToolRepr(mod.objTable[i]));
        }
        return sb.append("]").toString();
    }

    private void emitCode(CodeObject code) {
        Prelude p = code.prelude;
        byte[] fun = code.funData;

        line("simple_name: " + mod.qstr(p.simpleNameQstr));
        line("  raw bytecode: " + fun.length + " " + hexlify(fun));
        line("  prelude: (" + p.nState + ", " + p.nExcStack + ", " + p.scopeFlags
                + ", " + p.nPosArgs + ", " + p.nKwonlyArgs + ", " + p.nDefPosArgs + ")");
        line("  args: " + argsRepr(p));
        // NOTE: matches mpy-tool - the shown span runs to the start of bytecode,
        // so it includes the closure-cell bytes.
        line("  line info: " + hexlify(slice(fun, p.lineInfoStart, p.bytecodeStart)));

        int ip = p.bytecodeStart;
        while (ip < fun.length) {
            Instruction ins = Instruction.decode(fun, ip);
            String name = MAPPING[ins.opcode];
            String arg = argPart(ins);
            String hex = hexlify(slice(fun, ip, ip + ins.size));
            // "  %-11s %s" % (hex, name), then print(..., arg) joins with a space
            line("  " + ljust(hex, 11) + " " + name + " " + arg);
            ip += ins.size;
        }

        emitChildrenList(code);
        for (CodeObject child : code.children) emitCode(child);
    }

    private String argPart(Instruction ins) {
        int op = ins.opcode;
        if (op == Opcodes.LOAD_CONST_OBJ) {
            int idx = (int) ins.arg;
            return (idx >= 0 && idx < mod.objTable.length) ? pyToolRepr(mod.objTable[idx]) : ("<obj?" + idx + ">");
        }
        if (ins.format == Opcodes.FORMAT_QSTR) {
            return mod.qstr((int) ins.arg);
        }
        if (ins.format == Opcodes.FORMAT_VAR_UINT || ins.format == Opcodes.FORMAT_OFFSET) {
            return Long.toString(ins.arg);
        }
        return ""; // byte-format opcodes (incl. all the multi ranges)
    }

    private String argsRepr(Prelude p) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < p.argNameQstrs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(mod.qstr(p.argNameQstrs[i])).append('\'');
        }
        return sb.append("]").toString();
    }

    private void emitChildrenList(CodeObject code) {
        StringBuilder sb = new StringBuilder("  children: [");
        for (int i = 0; i < code.children.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(mod.qstr(code.children.get(i).prelude.simpleNameQstr));
        }
        line(sb.append("]").toString());
    }

    // ---- helpers ------------------------------------------------------------

    private static String hexlify(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b[i] & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }

    private static String ljust(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String[] buildMapping() {
        String[] m = new String[256];
        for (int op = 0; op < 256; op++) m[op] = Opcodes.name(op);
        for (int i = 0; i < Opcodes.LOAD_CONST_SMALL_INT_MULTI_NUM; i++) {
            m[Opcodes.LOAD_CONST_SMALL_INT_MULTI + i] =
                    "LOAD_CONST_SMALL_INT " + (i - Opcodes.LOAD_CONST_SMALL_INT_MULTI_EXCESS);
        }
        for (int i = 0; i < Opcodes.LOAD_FAST_MULTI_NUM; i++) {
            m[Opcodes.LOAD_FAST_MULTI + i] = "LOAD_FAST " + i;
        }
        for (int i = 0; i < Opcodes.STORE_FAST_MULTI_NUM; i++) {
            m[Opcodes.STORE_FAST_MULTI + i] = "STORE_FAST " + i;
        }
        for (int i = 0; i < Opcodes.UNARY_OP_MULTI_NUM; i++) {
            m[Opcodes.UNARY_OP_MULTI + i] = "UNARY_OP " + i + " " + Operators.unary(i);
        }
        for (int i = 0; i < Opcodes.BINARY_OP_MULTI_NUM; i++) {
            m[Opcodes.BINARY_OP_MULTI + i] = "BINARY_OP " + i + " " + Operators.binary(i);
        }
        return m;
    }
}
