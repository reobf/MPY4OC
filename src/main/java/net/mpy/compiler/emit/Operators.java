package net.mpy.compiler.emit;

/**
 * Operator index -> dunder method name (GENERATED from tools/mpy-tool.py).
 *
 * The emitter encodes operators inline in the opcode byte:
 *   UNARY_OP  op -> (UNARY_OP_MULTI  + op),  op in 0..3
 *   BINARY_OP op -> (BINARY_OP_MULTI + op),  op in 0..34
 * where op is the raw mp_binary_op_t / mp_unary_op_t index. Verified against
 * sample.mpy: BINARY_OP 27 = __add__ -> 0xd7 + 27 = 0xf2.
 *
 * These name tables are the authoritative index<->operator mapping; the
 * compiler (Step 8) maps each source operator (e.g. "+") to its index.
 */
public final class Operators {

    private Operators() {}

    public static final int UNARY_OP_NUM_BYTECODE  = 4;
    public static final int BINARY_OP_NUM_BYTECODE = 35;

    /** Unary op index -> method name (index i is emitted as UNARY_OP i). */
    public static final String[] UNARY_METHOD = {
        "__pos__",     // 0
        "__neg__",     // 1
        "__invert__",  // 2
        "<not>",       // 3
    };

    /** Binary op index -> method name (index i is emitted as BINARY_OP i). */
    public static final String[] BINARY_METHOD = {
        "__lt__",        // 0
        "__gt__",        // 1
        "__eq__",        // 2
        "__le__",        // 3
        "__ge__",        // 4
        "__ne__",        // 5
        "<in>",          // 6
        "<is>",          // 7
        "<exception match>", // 8
        "__ior__",       // 9
        "__ixor__",      // 10
        "__iand__",      // 11
        "__ilshift__",   // 12
        "__irshift__",   // 13
        "__iadd__",      // 14
        "__isub__",      // 15
        "__imul__",      // 16
        "__imatmul__",   // 17
        "__ifloordiv__", // 18
        "__itruediv__",  // 19
        "__imod__",      // 20
        "__ipow__",      // 21
        "__or__",        // 22
        "__xor__",       // 23
        "__and__",       // 24
        "__lshift__",    // 25
        "__rshift__",    // 26
        "__add__",       // 27
        "__sub__",       // 28
        "__mul__",       // 29
        "__matmul__",    // 30
        "__floordiv__",  // 31
        "__truediv__",   // 32
        "__mod__",       // 33
        "__pow__",       // 34
    };
}
