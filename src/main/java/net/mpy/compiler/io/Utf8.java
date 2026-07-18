package net.mpy.compiler.io;

import java.nio.charset.StandardCharsets;

/**
 * Lossless conversion between the compiler's byte strings and Java {@code String}.
 *
 * <p>MicroPython treats string payloads as opaque bytes: {@code vstr_add_char}
 * encodes each code point directly and never validates surrogates, so a literal
 * like {@code "\ud800"} is stored as the plain three-byte form. Routing such bytes
 * through Java's UTF-8 conversion would replace them with U+FFFD, and a
 * surrogate-pair round trip cannot tell {@code "\U0001F600"} (four bytes) from
 * {@code "\ud800\udc00"} (three plus three).
 *
 * <p>Using ISO-8859-1 makes byte and char a one-to-one mapping, so any byte
 * sequence survives unchanged. Strings are therefore carried as byte strings
 * internally; only the encoder in the lexer decides what those bytes mean.
 */
public final class Utf8 {
    private Utf8() {}

    /** Bytes -> String, one char per byte. */
    public static String decode(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    public static String decode(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    /** String -> bytes, the exact inverse of {@link #decode}. */
    public static byte[] encode(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Append one code point using MicroPython's vstr_add_char encoding. */
    public static void writeCodePoint(java.io.ByteArrayOutputStream out, int cp) {
        if (cp < 0x80) {
            out.write(cp);
        } else if (cp < 0x800) {
            out.write((cp >> 6) | 0xC0);
            out.write((cp & 0x3F) | 0x80);
        } else if (cp < 0x10000) {
            out.write((cp >> 12) | 0xE0);
            out.write(((cp >> 6) & 0x3F) | 0x80);
            out.write((cp & 0x3F) | 0x80);
        } else {
            out.write((cp >> 18) | 0xF0);
            out.write(((cp >> 12) & 0x3F) | 0x80);
            out.write(((cp >> 6) & 0x3F) | 0x80);
            out.write((cp & 0x3F) | 0x80);
        }
    }
}
