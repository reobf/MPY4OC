package net.mpy.compiler.persist;

import net.mpy.compiler.MpCompileException;
import net.mpy.compiler.io.ByteBuf;
import net.mpy.compiler.model.MpConst;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * The .mpy container serializer — the Java port of persistentcode.c's save side
 * (mp_raw_code_save + save_qstr + save_obj + save_raw_code).
 *
 * Aligned to Step 1 spec: mpy v6, no native code, so:
 *   - header feature-flag byte is always 0,
 *   - no arch_flags integer follows the header,
 *   - every raw-code has kind == bytecode (header kind bits = 0).
 *
 * Top-level layout (mp_raw_code_save):
 *   header[4] = { 'M', 6, 0, small_int_bits }
 *   vuint(n_qstr)
 *   vuint(n_obj)
 *   qstr entries          (QstrTable.serializeEntries)
 *   obj entries           (saveObj per constant)
 *   raw-code (recursive)  (saveRawCode)
 */
public final class MpySerializer {

    // Step 1 spec constants.
    private static final int MPY_VERSION = 6;
    private static final int SMALL_INT_BITS = 31;

    private MpySerializer() {}

    /** Serialize a compiled module to a complete .mpy byte array. */
    public static byte[] serialize(CompiledModule cm) {
        ByteBuf out = new ByteBuf(128);

        // Header: 'M', version, feature flags (0 for pure bytecode), small-int bits.
        out.u8('M').u8(MPY_VERSION).u8(0x00).u8(SMALL_INT_BITS);

        // Constant-table counts.
        out.vuint(cm.qstrs.size());
        out.vuint(cm.objs.size());

        // qstr table then obj table.
        cm.qstrs.serializeEntries(out);
        for (MpConst c : cm.objs) {
            saveObj(out, c);
        }

        // Outer raw-code (recurses through children).
        saveRawCode(out, cm.top);

        return out.toByteArray();
    }

    /**
     * save_raw_code: vuint((fun_data_len << 3) | (has_children << 2) | kind),
     * then the opaque fun_data, then (if any) child count + children recursively.
     * kind is always 0 (bytecode) for us.
     */
    private static void saveRawCode(ByteBuf out, RawCode rc) {
        boolean hasChildren = !rc.children.isEmpty();
        long head = ((long) rc.funData.length << 3) | ((hasChildren ? 1L : 0L) << 2) | 0L;
        out.vuint(head);
        out.bytes(rc.funData);
        if (hasChildren) {
            out.vuint(rc.children.size());
            for (RawCode child : rc.children) {
                saveRawCode(out, child);
            }
        }
    }

    /**
     * save_obj: one type byte, then a payload depending on the kind.
     *   None/False/True/Ellipsis : type byte only
     *   Str  : type + vuint(byteLen) + bytes + NUL
     *   Bytes: type + vuint(len)     + bytes + NUL
     *   Int  : type + vuint(textLen) + decimal-repr bytes         (no NUL)
     *   Float/Complex : type + vuint(textLen) + repr bytes        (no NUL)
     *   Tuple: type + vuint(len) + saveObj(item) ...              (recursive)
     *
     * Note the asymmetry: str/bytes store `len` but write len+1 bytes (trailing
     * NUL); numbers write exactly their text length with no NUL.
     */
    static void saveObj(ByteBuf out, MpConst c) {
        int type = c.persistentType();
        if (c instanceof MpConst.None
                || c instanceof MpConst.Bool
                || c instanceof MpConst.Ellipsis) {
            out.u8(type);
        } else if (c instanceof MpConst.Str s) {
            byte[] b = net.mpy.compiler.io.Utf8.encode(s.value());
            out.u8(type).vuint(b.length).bytes(b).u8(0);
        } else if (c instanceof MpConst.Bytes b) {
            out.u8(type).vuint(b.value().length).bytes(b.value()).u8(0);
        } else if (c instanceof MpConst.Int i) {
            byte[] t = intRepr(i.value()).getBytes(StandardCharsets.US_ASCII);
            out.u8(type).vuint(t.length).bytes(t);
        } else if (c instanceof MpConst.Float f) {
            byte[] t = floatRepr(f.value()).getBytes(StandardCharsets.US_ASCII);
            out.u8(type).vuint(t.length).bytes(t);
        } else if (c instanceof MpConst.Complex cx) {
            byte[] t = complexRepr(cx.real(), cx.imag()).getBytes(StandardCharsets.US_ASCII);
            out.u8(type).vuint(t.length).bytes(t);
        } else if (c instanceof MpConst.Tuple tup) {
            out.u8(type).vuint(tup.items().size());
            for (MpConst item : tup.items()) {
                saveObj(out, item);
            }
        } else {
            throw new MpCompileException("unknown constant kind: " + c.getClass());
        }
    }

    /** Big-int repr: decimal string. Matches MicroPython PRINT_REPR for ints. */
    static String intRepr(BigInteger v) {
        return v.toString();
    }

    /**
     * Float repr. mpy-cross stores floats as TEXT using MicroPython's 'g' format
     * with MP_FLOAT_REPR_PREC, which produces exactly CPython's repr(float):
     * shortest round-tripping decimal, exponent form outside [1e-4, 1e16).
     */
    static String floatRepr(double v) {
        return net.mpy.compiler.model.FloatRepr.repr(v);
    }

    /**
     * Complex repr. MicroPython prints the parts with plain 'g' formatting (no
     * forced decimal point), so 2.0j is written as "2j"; a zero real part is
     * omitted entirely.
     */
    static String complexRepr(double re, double im) {
        String imStr = net.mpy.compiler.model.FloatRepr.reprNoForcedDecimal(im);
        if (re == 0.0 && !(1 / re < 0)) {
            return imStr + "j";
        }
        String reStr = net.mpy.compiler.model.FloatRepr.reprNoForcedDecimal(re);
        String sign = imStr.startsWith("-") ? "" : "+";
        return "(" + reStr + sign + imStr + "j)";
    }
}
