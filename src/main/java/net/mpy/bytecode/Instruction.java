package net.mpy.bytecode;

/**
 * One decoded bytecode instruction. {@link #decode} is a faithful port of
 * mp_opcode_decode() in py/persistentcode.c and returns the opcode, its argument
 * format, the decoded (already sign-adjusted where relevant) argument, an
 * optional trailing extra byte, and the total instruction size in bytes.
 */
public final class Instruction {

    public final int offset;   // byte offset within the bytecode blob
    public final int opcode;   // the opcode byte
    public final int format;   // FORMAT_BYTE / QSTR / VAR_UINT / OFFSET
    public final long arg;      // decoded argument (0 if none); signed for offsets and small-ints
    public final int extraArg; // trailing extra byte (0 if none)
    public final int size;     // total bytes consumed by this instruction

    private Instruction(int offset, int opcode, int format, long arg, int extraArg, int size) {
        this.offset = offset;
        this.opcode = opcode;
        this.format = format;
        this.arg = arg;
        this.extraArg = extraArg;
        this.size = size;
    }

    /**
     * Decode the instruction at {@code code[ip]}. Mirror of mp_opcode_decode().
     */
    public static Instruction decode(byte[] code, int ip) {
        final int start = ip;
        final int opcode = code[ip++] & 0xff;
        final int format = Opcodes.format(opcode);
        long arg = 0;
        int extraArg = 0;

        if (format == Opcodes.FORMAT_QSTR || format == Opcodes.FORMAT_VAR_UINT) {
            // var-uint, MSB-first; sign-extended only for LOAD_CONST_SMALL_INT
            arg = code[ip] & 0x7f;
            if (opcode == Opcodes.LOAD_CONST_SMALL_INT && (arg & 0x40) != 0) {
                arg |= ~0L << 7; // sign-extend the 7-bit value
            }
            while ((code[ip] & 0x80) != 0) {
                arg = (arg << 7) | (code[++ip] & 0x7f);
            }
            ip++;
        } else if (format == Opcodes.FORMAT_OFFSET) {
            if ((code[ip] & 0x80) == 0) {
                arg = code[ip++] & 0xff;
                if (Opcodes.hasSignedOffset(opcode)) arg -= 0x40;
            } else {
                arg = (code[ip] & 0x7f) | ((code[ip + 1] & 0xff) << 7);
                ip += 2;
                if (Opcodes.hasSignedOffset(opcode)) arg -= 0x4000;
            }
        }

        if ((opcode & Opcodes.MASK_EXTRA_BYTE) == 0) {
            extraArg = code[ip++] & 0xff;
        }

        return new Instruction(start, opcode, format, arg, extraArg, ip - start);
    }

    /**
     * The small-int value encoded by a LOAD_CONST_SMALL_INT_MULTI opcode
     * (value = opcode - 0x80, giving the range -16..47).
     */
    public static int smallIntMultiValue(int opcode) {
        return opcode - (Opcodes.LOAD_CONST_SMALL_INT_MULTI + Opcodes.LOAD_CONST_SMALL_INT_MULTI_EXCESS);
    }

    @Override public String toString() {
        return String.format("@%d %s(0x%02x) arg=%d size=%d", offset, Opcodes.name(opcode), opcode, arg, size);
    }
}
