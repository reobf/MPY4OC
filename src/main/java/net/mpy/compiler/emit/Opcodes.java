package net.mpy.compiler.emit;

/**
 * MicroPython bytecode opcodes (GENERATED from py/bc0.h).
 *
 * Values resolved by expanding the BASE + offset arithmetic; operand format
 * computed from the MP_BC_FORMAT magic number (0x000003a4 >> (2*(op>>4)) & 3).
 * Spot-checked against real sample.mpy bytes (RETURN_VALUE=0x63, STORE_NAME=0x16,
 * MAKE_FUNCTION=0x32, LOAD_CONST_STRING=0x10, ...).
 *
 * Operand format determines how the emitter (Step 9) encodes the argument:
 *   BYTE     - no variable operand (may still have a fixed extra byte)
 *   QSTR     - a qstr, encoded as its LOCAL qstr-table index (var-uint)
 *   VAR_UINT - an unsigned var-int operand
 *   OFFSET   - a relative bytecode jump offset
 *
 * Do not hand-edit; regenerate against the pinned upstream commit.
 */
public final class Opcodes {

    private Opcodes() {}

    /** Operand encoding class for an opcode. */
    public enum Format { BYTE, QSTR, VAR_UINT, OFFSET }

    // --- single opcodes: value and operand format ---
    public static final int LOAD_CONST_STRING = 0x10; // fmt=QSTR
    public static final int LOAD_NAME = 0x11; // fmt=QSTR
    public static final int LOAD_GLOBAL = 0x12; // fmt=QSTR
    public static final int LOAD_ATTR = 0x13; // fmt=QSTR
    public static final int LOAD_METHOD = 0x14; // fmt=QSTR
    public static final int LOAD_SUPER_METHOD = 0x15; // fmt=QSTR
    public static final int STORE_NAME = 0x16; // fmt=QSTR
    public static final int STORE_GLOBAL = 0x17; // fmt=QSTR
    public static final int STORE_ATTR = 0x18; // fmt=QSTR
    public static final int DELETE_NAME = 0x19; // fmt=QSTR
    public static final int DELETE_GLOBAL = 0x1a; // fmt=QSTR
    public static final int IMPORT_NAME = 0x1b; // fmt=QSTR
    public static final int IMPORT_FROM = 0x1c; // fmt=QSTR
    public static final int MAKE_CLOSURE = 0x20; // fmt=VAR_UINT
    public static final int MAKE_CLOSURE_DEFARGS = 0x21; // fmt=VAR_UINT
    public static final int LOAD_CONST_SMALL_INT = 0x22; // fmt=VAR_UINT
    public static final int LOAD_CONST_OBJ = 0x23; // fmt=VAR_UINT
    public static final int LOAD_FAST_N = 0x24; // fmt=VAR_UINT
    public static final int LOAD_DEREF = 0x25; // fmt=VAR_UINT
    public static final int STORE_FAST_N = 0x26; // fmt=VAR_UINT
    public static final int STORE_DEREF = 0x27; // fmt=VAR_UINT
    public static final int DELETE_FAST = 0x28; // fmt=VAR_UINT
    public static final int DELETE_DEREF = 0x29; // fmt=VAR_UINT
    public static final int BUILD_TUPLE = 0x2a; // fmt=VAR_UINT
    public static final int BUILD_LIST = 0x2b; // fmt=VAR_UINT
    public static final int BUILD_MAP = 0x2c; // fmt=VAR_UINT
    public static final int BUILD_SET = 0x2d; // fmt=VAR_UINT
    public static final int BUILD_SLICE = 0x2e; // fmt=VAR_UINT
    public static final int STORE_COMP = 0x2f; // fmt=VAR_UINT
    public static final int UNPACK_SEQUENCE = 0x30; // fmt=VAR_UINT
    public static final int UNPACK_EX = 0x31; // fmt=VAR_UINT
    public static final int MAKE_FUNCTION = 0x32; // fmt=VAR_UINT
    public static final int MAKE_FUNCTION_DEFARGS = 0x33; // fmt=VAR_UINT
    public static final int CALL_FUNCTION = 0x34; // fmt=VAR_UINT
    public static final int CALL_FUNCTION_VAR_KW = 0x35; // fmt=VAR_UINT
    public static final int CALL_METHOD = 0x36; // fmt=VAR_UINT
    public static final int CALL_METHOD_VAR_KW = 0x37; // fmt=VAR_UINT
    public static final int UNWIND_JUMP = 0x40; // fmt=OFFSET
    public static final int JUMP = 0x42; // fmt=OFFSET
    public static final int POP_JUMP_IF_TRUE = 0x43; // fmt=OFFSET
    public static final int POP_JUMP_IF_FALSE = 0x44; // fmt=OFFSET
    public static final int JUMP_IF_TRUE_OR_POP = 0x45; // fmt=OFFSET
    public static final int JUMP_IF_FALSE_OR_POP = 0x46; // fmt=OFFSET
    public static final int SETUP_WITH = 0x47; // fmt=OFFSET
    public static final int SETUP_EXCEPT = 0x48; // fmt=OFFSET
    public static final int SETUP_FINALLY = 0x49; // fmt=OFFSET
    public static final int POP_EXCEPT_JUMP = 0x4a; // fmt=OFFSET
    public static final int FOR_ITER = 0x4b; // fmt=OFFSET
    public static final int LOAD_CONST_FALSE = 0x50; // fmt=BYTE
    public static final int LOAD_CONST_NONE = 0x51; // fmt=BYTE
    public static final int LOAD_CONST_TRUE = 0x52; // fmt=BYTE
    public static final int LOAD_NULL = 0x53; // fmt=BYTE
    public static final int LOAD_BUILD_CLASS = 0x54; // fmt=BYTE
    public static final int LOAD_SUBSCR = 0x55; // fmt=BYTE
    public static final int STORE_SUBSCR = 0x56; // fmt=BYTE
    public static final int DUP_TOP = 0x57; // fmt=BYTE
    public static final int DUP_TOP_TWO = 0x58; // fmt=BYTE
    public static final int POP_TOP = 0x59; // fmt=BYTE
    public static final int ROT_TWO = 0x5a; // fmt=BYTE
    public static final int ROT_THREE = 0x5b; // fmt=BYTE
    public static final int WITH_CLEANUP = 0x5c; // fmt=BYTE
    public static final int END_FINALLY = 0x5d; // fmt=BYTE
    public static final int GET_ITER = 0x5e; // fmt=BYTE
    public static final int GET_ITER_STACK = 0x5f; // fmt=BYTE
    public static final int STORE_MAP = 0x62; // fmt=BYTE
    public static final int RETURN_VALUE = 0x63; // fmt=BYTE
    public static final int RAISE_LAST = 0x64; // fmt=BYTE
    public static final int RAISE_OBJ = 0x65; // fmt=BYTE
    public static final int RAISE_FROM = 0x66; // fmt=BYTE
    public static final int YIELD_VALUE = 0x67; // fmt=BYTE
    public static final int YIELD_FROM = 0x68; // fmt=BYTE
    public static final int IMPORT_STAR = 0x69; // fmt=BYTE

    // --- multi-opcode ranges: the operand is folded into the opcode byte ---
    public static final int LOAD_CONST_SMALL_INT_MULTI = 0x70;
    public static final int LOAD_CONST_SMALL_INT_MULTI_NUM = 64;
    public static final int LOAD_CONST_SMALL_INT_MULTI_EXCESS = 16;
    public static final int LOAD_FAST_MULTI  = 0xb0;
    public static final int LOAD_FAST_MULTI_NUM  = 16;
    public static final int STORE_FAST_MULTI = 0xc0;
    public static final int STORE_FAST_MULTI_NUM = 16;
    public static final int UNARY_OP_MULTI   = 0xd0;
    public static final int BINARY_OP_MULTI  = 0xd7;

    /** MP_BC_FORMAT: operand format from opcode byte (matches bc0.h magic number). */
    public static Format formatOf(int op) {
        int f = (0x000003a4 >> (2 * ((op >> 4) & 0xf))) & 3;
        return Format.values()[f];
    }
}
