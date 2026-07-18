package net.mpy.compiler.model;

/**
 * Small-integer boundary for the target we align to (Step 1 spec).
 *
 * mpy-cross runs with MICROPY_DYNAMIC_COMPILER and small_int_bits = 31
 * (main.c:317), which is what makes default .mpy files portable to 32-bit
 * targets. An integer literal is embedded directly in the bytecode via
 * LOAD_CONST_SMALL_INT iff it fits in a *signed 31-bit* value; otherwise the
 * compiler emits LOAD_CONST_OBJ and stores the number in the obj_table as text.
 *
 * Boundary confirmed empirically against the oracle (mpy-cross + mpy-tool.py):
 *   1073741823 ( 2^30 - 1)  -> LOAD_CONST_SMALL_INT
 *   1073741824 ( 2^30    )  -> obj_table ["1073741824"]
 *  -1073741824 (-2^30    )  -> LOAD_CONST_SMALL_INT
 *  -1073741825 (-2^30 - 1)  -> obj_table ["-1073741825"]
 *
 * So the inclusive range is [-2^30, 2^30 - 1].
 *
 * NOTE: This is the *value-fits* test only. The on-wire encoding of a small int
 * inside bytecode (a modified signed varint, e.g. 1073741823 -> ff ff ff 7f)
 * belongs to the emitter (Step 9), not here.
 */
public final class SmallInt {

    private SmallInt() {}

    /** Target small-int value bits, including sign. Fixed by Step 1 spec. */
    public static final int BITS = 31;

    /** Inclusive minimum small-int value: -2^30. */
    public static final long MIN = -(1L << (BITS - 1)); // -1073741824

    /** Inclusive maximum small-int value:  2^30 - 1. */
    public static final long MAX = (1L << (BITS - 1)) - 1; //  1073741823

    /**
     * @return true if {@code v} is emitted inline as LOAD_CONST_SMALL_INT;
     *         false if it must go to the obj_table as a big-int constant.
     */
    public static boolean fits(long v) {
        return v >= MIN && v <= MAX;
    }

    /**
     * @return true if the arbitrary-precision integer fits the small-int range.
     */
    public static boolean fits(java.math.BigInteger v) {
        return v.compareTo(java.math.BigInteger.valueOf(MIN)) >= 0
            && v.compareTo(java.math.BigInteger.valueOf(MAX)) <= 0;
    }
}
