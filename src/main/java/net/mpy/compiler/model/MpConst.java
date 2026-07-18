package net.mpy.compiler.model;

import java.math.BigInteger;
import java.util.List;

/**
 * The complete "object model" a MicroPython *compiler* needs.
 *
 * Crucial simplification (Step 2 finding): we are writing a compiler, not a
 * runtime, so we do NOT need the tagged-pointer {@code mp_obj_t} scheme at all.
 * The only objects that ever get serialized are the constants that land in an
 * .mpy file's obj_table, and persistentcode.c#save_obj enumerates them exactly.
 *
 * The type bytes below are MP_PERSISTENT_OBJ_* from persistentcode.h:
 *   0 FUN_TABLE   (native only -- excluded from our bytecode-only build)
 *   1 NONE
 *   2 FALSE
 *   3 TRUE
 *   4 ELLIPSIS
 *   5 STR
 *   6 BYTES
 *   7 INT
 *   8 FLOAT
 *   9 COMPLEX
 *  10 TUPLE
 *
 * Serialization detail that shapes this model: INT / FLOAT / COMPLEX are stored
 * as their *textual repr* (save_obj calls mp_obj_print_helper(PRINT_REPR)), NOT
 * as MPZ binary. So we never implement arbitrary-precision binary encoding --
 * a BigInteger's decimal string and a double's repr string are what get written.
 *
 * WARNING (deferred to Step 10): the FLOAT/COMPLEX text must byte-match
 * MicroPython's float formatter, which is not identical to Java's
 * Double.toString(). That fidelity work lives in the serializer/const-folding
 * step and will be pinned against the oracle; here we only carry the value.
 *
 * Where does a given constant go -- qstr_table vs obj_table? That routing is a
 * compiler/emitter concern (Steps 4 & 9), not a property of the value:
 *   - short interned strings / names  -> qstr_table (LOAD_CONST_STRING)
 *   - long strings, bytes, numbers, tuples, singletons -> obj_table
 * This type carries the value; the routing decision is made elsewhere.
 */
public sealed interface MpConst
        permits MpConst.None, MpConst.Bool, MpConst.Ellipsis,
                MpConst.Str, MpConst.Bytes, MpConst.Int,
                MpConst.Float, MpConst.Complex, MpConst.Tuple {

    /** MP_PERSISTENT_OBJ_* type byte written as the first byte of the object. */
    int persistentType();

    // ----- singletons (no payload; just the type byte) -----

    /** MP_PERSISTENT_OBJ_NONE. */
    record None() implements MpConst {
        public static final None INSTANCE = new None();
        @Override public int persistentType() { return 1; }
    }

    /** MP_PERSISTENT_OBJ_FALSE / MP_PERSISTENT_OBJ_TRUE. */
    record Bool(boolean value) implements MpConst {
        public static final Bool FALSE = new Bool(false);
        public static final Bool TRUE  = new Bool(true);
        @Override public int persistentType() { return value ? 3 : 2; }
    }

    /** MP_PERSISTENT_OBJ_ELLIPSIS (the {@code ...} literal). */
    record Ellipsis() implements MpConst {
        public static final Ellipsis INSTANCE = new Ellipsis();
        @Override public int persistentType() { return 4; }
    }

    // ----- str / bytes: written as [type][uint len][bytes...][0x00] -----

    /**
     * MP_PERSISTENT_OBJ_STR. Value is the decoded string; the serializer emits
     * its UTF-8 bytes (unicode build) plus a trailing NUL. Length written is the
     * byte length, not the code-point count.
     */
    record Str(String value) implements MpConst {
        @Override public int persistentType() { return 5; }
    }

    /** MP_PERSISTENT_OBJ_BYTES. Raw bytes plus trailing NUL on the wire. */
    record Bytes(byte[] value) implements MpConst {
        @Override public int persistentType() { return 6; }

        // records compare arrays by reference by default, but two identical byte
        // literals must share one entry in the const object table
        @Override public boolean equals(Object o) {
            return o instanceof Bytes b && java.util.Arrays.equals(value, b.value);
        }

        @Override public int hashCode() { return java.util.Arrays.hashCode(value); }

        @Override public String toString() {
            return "Bytes(" + java.util.Arrays.toString(value) + ")";
        }
    }

    // ----- numbers: written as [type][uint textLen][ascii repr...] -----

    /**
     * MP_PERSISTENT_OBJ_INT -- only for integers that do NOT fit the small-int
     * range (see {@link SmallInt}). Fitting integers never reach the obj_table;
     * they are embedded in bytecode. Stored as the decimal repr string.
     */
    record Int(BigInteger value) implements MpConst {
        @Override public int persistentType() { return 7; }
    }

    /** MP_PERSISTENT_OBJ_FLOAT (64-bit, MICROPY_FLOAT_IMPL_DOUBLE). */
    record Float(double value) implements MpConst {
        @Override public int persistentType() { return 8; }
    }

    /** MP_PERSISTENT_OBJ_COMPLEX. Stored as its repr, e.g. "1+2j". */
    record Complex(double real, double imag) implements MpConst {
        @Override public int persistentType() { return 9; }
    }

    // ----- tuple: written as [type][uint len][child][child]... (recursive) ---

    /**
     * MP_PERSISTENT_OBJ_TUPLE. Constant tuples (e.g. from a folded literal) whose
     * elements are themselves MpConst. Recursion mirrors save_obj's recursion.
     */
    record Tuple(List<MpConst> items) implements MpConst {
        @Override public int persistentType() { return 10; }
    }
}
