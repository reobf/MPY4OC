package net.mpy.compiler.io;

import java.util.Arrays;

/**
 * Growable byte output buffer — the Java stand-in for the C emitter's manual
 * {@code byte *p; *p++ = ...} pointer walking plus {@code m_renew} growth.
 * Allocation/growth is handled here (and ultimately by the JVM GC), so the whole
 * manual-memory layer of the C code collapses into this one class.
 *
 * The important primitive is {@link #vuint(long)}: the variable-length unsigned
 * integer used throughout the .mpy container (qstr/obj table counts, string
 * lengths, prelude fields, ...). Its encoding is taken verbatim from
 * persistentcode.c#mp_print_uint and is:
 *
 *   big-endian base-128 — most-significant 7-bit group first, every byte except
 *   the last carries a 0x80 continuation bit.
 *
 * (Note: this is NOT the little-endian "LSB-first" varint some docs describe;
 * the persistentcode container uses the big-endian form implemented here. The
 * decoder read_uint confirms it: unum = (unum << 7) | (b & 0x7f).)
 */
public final class ByteBuf {

    private byte[] buf;
    private int len;

    public ByteBuf() { this(64); }

    public ByteBuf(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
        this.len = 0;
    }

    private void ensure(int extra) {
        int need = len + extra;
        if (need > buf.length) {
            int n = buf.length;
            while (n < need) n <<= 1;
            buf = Arrays.copyOf(buf, n);
        }
    }

    /** Append one byte (low 8 bits of {@code b}). */
    public ByteBuf u8(int b) {
        ensure(1);
        buf[len++] = (byte) (b & 0xff);
        return this;
    }

    /** Append a raw byte array. */
    public ByteBuf bytes(byte[] src) {
        return bytes(src, 0, src.length);
    }

    /** Append {@code n} bytes of {@code src} starting at {@code off}. */
    public ByteBuf bytes(byte[] src, int off, int n) {
        ensure(n);
        System.arraycopy(src, off, buf, len, n);
        len += n;
        return this;
    }

    /**
     * Append an unsigned varint, exactly as persistentcode.c#mp_print_uint emits.
     * @param n non-negative value.
     */
    public ByteBuf vuint(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("vuint must be non-negative: " + n);
        }
        // 64-bit value needs at most ceil(64/7) = 10 groups.
        byte[] tmp = new byte[10];
        int p = tmp.length;
        tmp[--p] = (byte) (n & 0x7f);   // least-significant group, no continuation
        n >>>= 7;
        while (n != 0) {
            tmp[--p] = (byte) (0x80 | (n & 0x7f)); // earlier groups carry continuation
            n >>>= 7;
        }
        return bytes(tmp, p, tmp.length - p);
    }

    public int size() { return len; }

    /** Reset the buffer to empty (reuse the backing array). */
    public void reset() { len = 0; }

    /** Snapshot the written bytes. */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    // --- decode side, for the test harness / round-tripping (not on the hot path) ---

    /** Holder for {@link #readVuint}. */
    public static final class Vuint {
        public final long value;
        public final int nextOffset;
        Vuint(long v, int next) { this.value = v; this.nextOffset = next; }
    }

    /** Decode a vuint from {@code data} at {@code off}; inverse of {@link #vuint}. */
    public static Vuint readVuint(byte[] data, int off) {
        long unum = 0;
        int i = off;
        for (;;) {
            int b = data[i++] & 0xff;
            unum = (unum << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) break;
        }
        return new Vuint(unum, i);
    }
}
