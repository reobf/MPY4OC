package net.mpy.bytecode;

/**
 * MicroPython bytecode opcode table for .mpy v6.x, transcribed from py/bc0.h.
 *
 * Opcodes are one byte with an optional argument whose encoding is determined by
 * the opcode's "format", and the format is a function of the opcode's high nibble
 * via a packed 32-bit magic number. See {@link #format(int)}.
 */
public final class Opcodes {

    private Opcodes() {}

    // ---- argument formats (py/bc0.h) ---------------------------------------
    public static final int FORMAT_BYTE     = 0; // opcode only (may carry an extra trailing byte)
    public static final int FORMAT_QSTR     = 1; // + qstr index (var-uint)
    public static final int FORMAT_VAR_UINT = 2; // + unsigned var-int (signed for LOAD_CONST_SMALL_INT)
    public static final int FORMAT_OFFSET   = 3; // + 1/2-byte relative bytecode offset

    public static final int MASK_EXTRA_BYTE = 0x9e;

    // ---- opcode group bases (py/bc0.h) -------------------------------------
    public static final int BASE_RESERVED = 0x00;
    public static final int BASE_QSTR_O   = 0x10;
    public static final int BASE_VINT_E   = 0x20;
    public static final int BASE_VINT_O   = 0x30;
    public static final int BASE_JUMP_E   = 0x40;
    public static final int BASE_BYTE_O   = 0x50;
    public static final int BASE_BYTE_E   = 0x60;

    // ---- "multi" (packed operation + small operand) ranges -----------------
    public static final int LOAD_CONST_SMALL_INT_MULTI       = 0x70;
    public static final int LOAD_CONST_SMALL_INT_MULTI_NUM   = 64;
    public static final int LOAD_CONST_SMALL_INT_MULTI_EXCESS = 16;
    public static final int LOAD_FAST_MULTI      = 0xb0;
    public static final int LOAD_FAST_MULTI_NUM  = 16;
    public static final int STORE_FAST_MULTI     = 0xc0;
    public static final int STORE_FAST_MULTI_NUM = 16;
    public static final int UNARY_OP_MULTI       = 0xd0;
    public static final int UNARY_OP_MULTI_NUM   = 4;   // MP_UNARY_OP_NUM_BYTECODE
    public static final int BINARY_OP_MULTI      = 0xd7;
    public static final int BINARY_OP_MULTI_NUM  = 35;  // MP_BINARY_OP_NUM_BYTECODE

    // ---- individual opcodes (values verified against disassembly) -----------
    // QSTR_O group
    public static final int LOAD_CONST_STRING   = BASE_QSTR_O + 0x00;
    public static final int LOAD_NAME           = BASE_QSTR_O + 0x01;
    public static final int LOAD_GLOBAL         = BASE_QSTR_O + 0x02;
    public static final int LOAD_ATTR           = BASE_QSTR_O + 0x03;
    public static final int LOAD_METHOD         = BASE_QSTR_O + 0x04;
    public static final int LOAD_SUPER_METHOD   = BASE_QSTR_O + 0x05;
    public static final int STORE_NAME          = BASE_QSTR_O + 0x06;
    public static final int STORE_GLOBAL        = BASE_QSTR_O + 0x07;
    public static final int STORE_ATTR          = BASE_QSTR_O + 0x08;
    public static final int DELETE_NAME         = BASE_QSTR_O + 0x09;
    public static final int DELETE_GLOBAL       = BASE_QSTR_O + 0x0a;
    public static final int IMPORT_NAME         = BASE_QSTR_O + 0x0b;
    public static final int IMPORT_FROM         = BASE_QSTR_O + 0x0c;
    // VINT_E group
    public static final int MAKE_CLOSURE        = BASE_VINT_E + 0x00; // + extra byte
    public static final int MAKE_CLOSURE_DEFARGS = BASE_VINT_E + 0x01; // + extra byte
    public static final int LOAD_CONST_SMALL_INT = BASE_VINT_E + 0x02; // signed var-int
    public static final int LOAD_CONST_OBJ      = BASE_VINT_E + 0x03;
    public static final int LOAD_FAST_N         = BASE_VINT_E + 0x04;
    public static final int LOAD_DEREF          = BASE_VINT_E + 0x05;
    public static final int STORE_FAST_N        = BASE_VINT_E + 0x06;
    public static final int STORE_DEREF         = BASE_VINT_E + 0x07;
    public static final int DELETE_FAST         = BASE_VINT_E + 0x08;
    public static final int DELETE_DEREF        = BASE_VINT_E + 0x09;
    public static final int BUILD_TUPLE         = BASE_VINT_E + 0x0a;
    public static final int BUILD_LIST          = BASE_VINT_E + 0x0b;
    public static final int BUILD_MAP           = BASE_VINT_E + 0x0c;
    public static final int BUILD_SET           = BASE_VINT_E + 0x0d;
    public static final int BUILD_SLICE         = BASE_VINT_E + 0x0e;
    public static final int STORE_COMP          = BASE_VINT_E + 0x0f;
    // VINT_O group
    public static final int UNPACK_SEQUENCE     = BASE_VINT_O + 0x00;
    public static final int UNPACK_EX           = BASE_VINT_O + 0x01;
    public static final int MAKE_FUNCTION       = BASE_VINT_O + 0x02;
    public static final int MAKE_FUNCTION_DEFARGS = BASE_VINT_O + 0x03;
    public static final int CALL_FUNCTION       = BASE_VINT_O + 0x04;
    public static final int CALL_FUNCTION_VAR_KW = BASE_VINT_O + 0x05;
    public static final int CALL_METHOD         = BASE_VINT_O + 0x06;
    public static final int CALL_METHOD_VAR_KW  = BASE_VINT_O + 0x07;
    // JUMP_E group
    public static final int UNWIND_JUMP         = BASE_JUMP_E + 0x00; // signed offset + extra byte
    public static final int JUMP                = BASE_JUMP_E + 0x02; // signed
    public static final int POP_JUMP_IF_TRUE    = BASE_JUMP_E + 0x03; // signed
    public static final int POP_JUMP_IF_FALSE   = BASE_JUMP_E + 0x04; // signed
    public static final int JUMP_IF_TRUE_OR_POP = BASE_JUMP_E + 0x05; // unsigned
    public static final int JUMP_IF_FALSE_OR_POP = BASE_JUMP_E + 0x06; // unsigned
    public static final int SETUP_WITH          = BASE_JUMP_E + 0x07;
    public static final int SETUP_EXCEPT        = BASE_JUMP_E + 0x08;
    public static final int SETUP_FINALLY       = BASE_JUMP_E + 0x09;
    public static final int POP_EXCEPT_JUMP     = BASE_JUMP_E + 0x0a;
    public static final int FOR_ITER            = BASE_JUMP_E + 0x0b;
    // BYTE_O group
    public static final int LOAD_CONST_FALSE    = BASE_BYTE_O + 0x00;
    public static final int LOAD_CONST_NONE     = BASE_BYTE_O + 0x01;
    public static final int LOAD_CONST_TRUE     = BASE_BYTE_O + 0x02;
    public static final int LOAD_NULL           = BASE_BYTE_O + 0x03;
    public static final int LOAD_BUILD_CLASS    = BASE_BYTE_O + 0x04;
    public static final int LOAD_SUBSCR         = BASE_BYTE_O + 0x05;
    public static final int STORE_SUBSCR        = BASE_BYTE_O + 0x06;
    public static final int DUP_TOP             = BASE_BYTE_O + 0x07;
    public static final int DUP_TOP_TWO         = BASE_BYTE_O + 0x08;
    public static final int POP_TOP             = BASE_BYTE_O + 0x09;
    public static final int ROT_TWO             = BASE_BYTE_O + 0x0a;
    public static final int ROT_THREE           = BASE_BYTE_O + 0x0b;
    public static final int WITH_CLEANUP        = BASE_BYTE_O + 0x0c;
    public static final int END_FINALLY         = BASE_BYTE_O + 0x0d;
    public static final int GET_ITER            = BASE_BYTE_O + 0x0e;
    public static final int GET_ITER_STACK      = BASE_BYTE_O + 0x0f;
    // BYTE_E group
    public static final int STORE_MAP           = BASE_BYTE_E + 0x02;
    public static final int RETURN_VALUE        = BASE_BYTE_E + 0x03;
    public static final int RAISE_LAST          = BASE_BYTE_E + 0x04;
    public static final int RAISE_OBJ           = BASE_BYTE_E + 0x05;
    public static final int RAISE_FROM          = BASE_BYTE_E + 0x06;
    public static final int YIELD_VALUE         = BASE_BYTE_E + 0x07;
    public static final int YIELD_FROM          = BASE_BYTE_E + 0x08;
    public static final int IMPORT_STAR         = BASE_BYTE_E + 0x09;

    /**
     * Argument format for an opcode, from its high nibble.
     * MP_BC_FORMAT(op) = (0x000003a4 >> (2 * (op >> 4))) & 3 .
     */
    public static int format(int op) {
        return (0x000003a4 >> (2 * (op >> 4))) & 3;
    }

    /** True if a byte-format opcode carries an extra trailing operand byte. */
    public static boolean hasExtraByte(int op) {
        return (op & MASK_EXTRA_BYTE) == 0;
    }

    /** True if an OFFSET-format opcode uses a signed (vs unsigned) offset. */
    public static boolean hasSignedOffset(int op) {
        return UNWIND_JUMP <= op && op <= POP_JUMP_IF_FALSE;
    }

    // ---- opcode name table --------------------------------------------------
    private static final String[] NAMES = new String[256];

    static {
        n(LOAD_CONST_STRING, "LOAD_CONST_STRING"); n(LOAD_NAME, "LOAD_NAME");
        n(LOAD_GLOBAL, "LOAD_GLOBAL"); n(LOAD_ATTR, "LOAD_ATTR");
        n(LOAD_METHOD, "LOAD_METHOD"); n(LOAD_SUPER_METHOD, "LOAD_SUPER_METHOD");
        n(STORE_NAME, "STORE_NAME"); n(STORE_GLOBAL, "STORE_GLOBAL");
        n(STORE_ATTR, "STORE_ATTR"); n(DELETE_NAME, "DELETE_NAME");
        n(DELETE_GLOBAL, "DELETE_GLOBAL"); n(IMPORT_NAME, "IMPORT_NAME");
        n(IMPORT_FROM, "IMPORT_FROM");
        n(MAKE_CLOSURE, "MAKE_CLOSURE"); n(MAKE_CLOSURE_DEFARGS, "MAKE_CLOSURE_DEFARGS");
        n(LOAD_CONST_SMALL_INT, "LOAD_CONST_SMALL_INT"); n(LOAD_CONST_OBJ, "LOAD_CONST_OBJ");
        n(LOAD_FAST_N, "LOAD_FAST_N"); n(LOAD_DEREF, "LOAD_DEREF");
        n(STORE_FAST_N, "STORE_FAST_N"); n(STORE_DEREF, "STORE_DEREF");
        n(DELETE_FAST, "DELETE_FAST"); n(DELETE_DEREF, "DELETE_DEREF");
        n(BUILD_TUPLE, "BUILD_TUPLE"); n(BUILD_LIST, "BUILD_LIST");
        n(BUILD_MAP, "BUILD_MAP"); n(BUILD_SET, "BUILD_SET");
        n(BUILD_SLICE, "BUILD_SLICE"); n(STORE_COMP, "STORE_COMP");
        n(UNPACK_SEQUENCE, "UNPACK_SEQUENCE"); n(UNPACK_EX, "UNPACK_EX");
        n(MAKE_FUNCTION, "MAKE_FUNCTION"); n(MAKE_FUNCTION_DEFARGS, "MAKE_FUNCTION_DEFARGS");
        n(CALL_FUNCTION, "CALL_FUNCTION"); n(CALL_FUNCTION_VAR_KW, "CALL_FUNCTION_VAR_KW");
        n(CALL_METHOD, "CALL_METHOD"); n(CALL_METHOD_VAR_KW, "CALL_METHOD_VAR_KW");
        n(UNWIND_JUMP, "UNWIND_JUMP"); n(JUMP, "JUMP");
        n(POP_JUMP_IF_TRUE, "POP_JUMP_IF_TRUE"); n(POP_JUMP_IF_FALSE, "POP_JUMP_IF_FALSE");
        n(JUMP_IF_TRUE_OR_POP, "JUMP_IF_TRUE_OR_POP"); n(JUMP_IF_FALSE_OR_POP, "JUMP_IF_FALSE_OR_POP");
        n(SETUP_WITH, "SETUP_WITH"); n(SETUP_EXCEPT, "SETUP_EXCEPT");
        n(SETUP_FINALLY, "SETUP_FINALLY"); n(POP_EXCEPT_JUMP, "POP_EXCEPT_JUMP");
        n(FOR_ITER, "FOR_ITER");
        n(LOAD_CONST_FALSE, "LOAD_CONST_FALSE"); n(LOAD_CONST_NONE, "LOAD_CONST_NONE");
        n(LOAD_CONST_TRUE, "LOAD_CONST_TRUE"); n(LOAD_NULL, "LOAD_NULL");
        n(LOAD_BUILD_CLASS, "LOAD_BUILD_CLASS"); n(LOAD_SUBSCR, "LOAD_SUBSCR");
        n(STORE_SUBSCR, "STORE_SUBSCR"); n(DUP_TOP, "DUP_TOP");
        n(DUP_TOP_TWO, "DUP_TOP_TWO"); n(POP_TOP, "POP_TOP");
        n(ROT_TWO, "ROT_TWO"); n(ROT_THREE, "ROT_THREE");
        n(WITH_CLEANUP, "WITH_CLEANUP"); n(END_FINALLY, "END_FINALLY");
        n(GET_ITER, "GET_ITER"); n(GET_ITER_STACK, "GET_ITER_STACK");
        n(STORE_MAP, "STORE_MAP"); n(RETURN_VALUE, "RETURN_VALUE");
        n(RAISE_LAST, "RAISE_LAST"); n(RAISE_OBJ, "RAISE_OBJ");
        n(RAISE_FROM, "RAISE_FROM"); n(YIELD_VALUE, "YIELD_VALUE");
        n(YIELD_FROM, "YIELD_FROM"); n(IMPORT_STAR, "IMPORT_STAR");
    }

    private static void n(int op, String name) { NAMES[op] = name; }

    /** Family/mnemonic name of an opcode, resolving the packed "multi" ranges. */
    public static String name(int op) {
        if (NAMES[op] != null) return NAMES[op];
        if (op >= LOAD_CONST_SMALL_INT_MULTI
                && op < LOAD_CONST_SMALL_INT_MULTI + LOAD_CONST_SMALL_INT_MULTI_NUM) return "LOAD_CONST_SMALL_INT";
        if (op >= LOAD_FAST_MULTI && op < LOAD_FAST_MULTI + LOAD_FAST_MULTI_NUM) return "LOAD_FAST";
        if (op >= STORE_FAST_MULTI && op < STORE_FAST_MULTI + STORE_FAST_MULTI_NUM) return "STORE_FAST";
        if (op >= UNARY_OP_MULTI && op < UNARY_OP_MULTI + UNARY_OP_MULTI_NUM) return "UNARY_OP";
        if (op >= BINARY_OP_MULTI && op < BINARY_OP_MULTI + BINARY_OP_MULTI_NUM) return "BINARY_OP";
        return String.format("UNKNOWN_%02x", op);
    }
}
