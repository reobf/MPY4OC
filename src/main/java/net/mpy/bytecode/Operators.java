package net.mpy.bytecode;

/**
 * Method-name strings for the unary/binary operators packed into the
 * UNARY_OP_MULTI / BINARY_OP_MULTI opcode ranges. Order matches the bytecode
 * portion of mp_unary_op_t / mp_binary_op_t in py/runtime0.h, and the strings
 * match what mpy-tool.py prints (verified by disassembling every operator).
 */
public final class Operators {

    private Operators() {}

    /** UNARY_OP index -> name (indices 0..3 are the only ones in bytecode). */
    public static final String[] UNARY = {
        "__pos__",     // 0 MP_UNARY_OP_POSITIVE
        "__neg__",     // 1 MP_UNARY_OP_NEGATIVE
        "__invert__",  // 2 MP_UNARY_OP_INVERT
        "<not>",       // 3 MP_UNARY_OP_NOT
    };

    /** BINARY_OP index -> name (indices 0..34 appear in bytecode). */
    public static final String[] BINARY = {
        "__lt__",            // 0  LESS
        "__gt__",            // 1  MORE
        "__eq__",            // 2  EQUAL
        "__le__",            // 3  LESS_EQUAL
        "__ge__",            // 4  MORE_EQUAL
        "__ne__",            // 5  NOT_EQUAL
        "<in>",              // 6  IN
        "<is>",              // 7  IS
        "<exception match>", // 8  EXCEPTION_MATCH
        "__ior__",           // 9  INPLACE_OR
        "__ixor__",          // 10 INPLACE_XOR
        "__iand__",          // 11 INPLACE_AND
        "__ilshift__",       // 12 INPLACE_LSHIFT
        "__irshift__",       // 13 INPLACE_RSHIFT
        "__iadd__",          // 14 INPLACE_ADD
        "__isub__",          // 15 INPLACE_SUBTRACT
        "__imul__",          // 16 INPLACE_MULTIPLY
        "__imatmul__",       // 17 INPLACE_MAT_MULTIPLY
        "__ifloordiv__",     // 18 INPLACE_FLOOR_DIVIDE
        "__itruediv__",      // 19 INPLACE_TRUE_DIVIDE
        "__imod__",          // 20 INPLACE_MODULO
        "__ipow__",          // 21 INPLACE_POWER
        "__or__",            // 22 OR
        "__xor__",           // 23 XOR
        "__and__",           // 24 AND
        "__lshift__",        // 25 LSHIFT
        "__rshift__",        // 26 RSHIFT
        "__add__",           // 27 ADD
        "__sub__",           // 28 SUBTRACT
        "__mul__",           // 29 MULTIPLY
        "__matmul__",        // 30 MAT_MULTIPLY
        "__floordiv__",      // 31 FLOOR_DIVIDE
        "__truediv__",       // 32 TRUE_DIVIDE
        "__mod__",           // 33 MODULO
        "__pow__",           // 34 POWER
    };

    public static String unary(int i)  { return (i >= 0 && i < UNARY.length)  ? UNARY[i]  : "<unary?" + i + ">"; }
    public static String binary(int i) { return (i >= 0 && i < BINARY.length) ? BINARY[i] : "<binary?" + i + ">"; }
}
