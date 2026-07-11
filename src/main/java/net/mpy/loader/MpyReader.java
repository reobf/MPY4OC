package net.mpy.loader;

/**
 * Phase 1.1 - the low-level .mpy byte cursor.
 *
 * Wraps the raw file bytes and provides sequential reads plus the variable-length
 * unsigned integer ("vuint") decoding used pervasively in .mpy files.
 *
 * IMPORTANT (matches py/persistentcode.c read_uint): the vuint is MSB-first -
 * the FIRST byte holds the most-significant 7 bits, each byte contributes 7 bits,
 * and the high bit (0x80) means "another byte follows". This is the opposite
 * endianness of a protobuf/LEB128 varint, so do not swap it.
 */
public final class MpyReader {

    private final byte[] buf;
    private int pos;

    public MpyReader(byte[] buf) {
        this.buf = buf;
        this.pos = 0;
    }

    /** Current read position. */
    public int pos() { return pos; }

    /** Total length of the underlying buffer. */
    public int length() { return buf.length; }

    /** True if there are no more bytes to read. */
    public boolean eof() { return pos >= buf.length; }

    /** Read one unsigned byte (0..255). */
    public int readByte() {
        if (pos >= buf.length) throw new MpyFormatException("unexpected end of .mpy at byte " + pos);
        return buf[pos++] & 0xff;
    }

    /** Peek the next unsigned byte without consuming it. */
    public int peekByte() {
        if (pos >= buf.length) throw new MpyFormatException("unexpected end of .mpy at byte " + pos);
        return buf[pos] & 0xff;
    }

    /** Read {@code n} raw bytes. */
    public byte[] readBytes(int n) {
        if (pos + n > buf.length) {
            throw new MpyFormatException("unexpected end of .mpy: wanted " + n
                    + " bytes at " + pos + " but only " + (buf.length - pos) + " remain");
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Skip {@code n} bytes. */
    public void skip(int n) {
        if (pos + n > buf.length) throw new MpyFormatException("skip past end of .mpy");
        pos += n;
    }

    /**
     * Read a variable-length unsigned integer (MSB-first, 7 bits per byte,
     * high bit = continue). Mirror of read_uint() in persistentcode.c.
     */
    public long readUint() {
        long n = 0;
        while (true) {
            int b = readByte();
            n = (n << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) break;
        }
        return n;
    }

    /** Same as {@link #readUint()} but bounded to an int, with a range check. */
    public int readUintInt() {
        long n = readUint();
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new MpyFormatException("vuint out of int range: " + n);
        }
        return (int) n;
    }

    /** Thrown when the byte stream does not match the expected .mpy format. */
    public static final class MpyFormatException extends RuntimeException {
        public MpyFormatException(String msg) { super(msg); }
    }
}
