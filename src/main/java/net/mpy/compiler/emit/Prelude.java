package net.mpy.compiler.emit;

import net.mpy.compiler.io.ByteBuf;

/**
 * Encoders for the bytecode function-data prelude and code-info, ported from the
 * MP_BC_PRELUDE_*_ENCODE macros (py/bc.h) and emit_write_code_info_bytes_lines
 * (py/emitbc.c). These build the head of a raw-code's fun_data:
 *
 *   fun_data = code_info + bytecode
 *   code_info =
 *     prelude signature   (n_state, n_exc_stack, scope_flags&0xf, A, K, D)
 *     prelude size        (n_info, n_cell)
 *     simple_name qstr    (var-uint of local qstr index)
 *     arg-name qstrs      (num_pos_args + num_kwonly_args of them)
 *     line-number info    (bytes_to_skip / lines_to_skip entries)
 *     cell info           (n_cell bytes of local_num)
 *
 * Verified against sample.mpy's module fun_data: prelude sig 0x10 = (n_state=3,
 * rest 0); size 0x06 = (n_info=3, n_cell=0); line info 0x64 0x28 = (skip 4 bytes
 * +3 lines: line 1->4) then (skip 8 bytes +1 line: 4->5).
 */
public final class Prelude {

    private Prelude() {}

    /**
     * MP_BC_PRELUDE_SIG_ENCODE. Packs the prelude signature.
     * @param nState     num_locals + stack_size (min 1)
     * @param nExc       exception-stack size
     * @param scopeFlags scope_flags (only low 4 bits, MP_SCOPE_FLAG_ALL_SIG, used)
     * @param nPos       num_pos_args
     * @param nKwonly    num_kwonly_args
     * @param nDef       num_def_pos_args
     */
    public static void encodeSig(ByteBuf out, int nState, int nExc, int scopeFlags,
                                 int nPos, int nKwonly, int nDef) {
        long S = nState - 1; // shrink range to compress
        long E = nExc;
        long F = scopeFlags & 0x0f;
        long A = nPos;
        long K = nKwonly;
        long D = nDef;

        // first byte: xSSSSEAA
        int z = (int) (((S & 0xf) << 3) | ((E & 1) << 2) | (A & 3));
        S >>= 4; E >>= 1; A >>= 2;
        while ((S | E | F | A | K | D) != 0) {
            out.u8(0x80 | z);
            // xFSSKAED
            z = (int) (((F & 1) << 6) | ((S & 3) << 4) | ((K & 1) << 3)
                    | ((A & 1) << 2) | ((E & 1) << 1) | (D & 1));
            S >>= 2; E >>= 1; F >>= 1; A >>= 1; K >>= 1; D >>= 1;
        }
        out.u8(z);
    }

    /**
     * MP_BC_PRELUDE_SIZE_ENCODE. Packs n_info and n_cell as xIIIIIIC bytes.
     */
    public static void encodeSize(ByteBuf out, int nInfo, int nCell) {
        long I = nInfo;
        long C = nCell;
        int z;
        do {
            z = (int) (((I & 0x3f) << 1) | (C & 1));
            C >>= 1;
            I >>= 6;
            if ((C | I) != 0) z |= 0x80;
            out.u8(z);
        } while ((C | I) != 0);
    }

    /**
     * emit_write_code_info_bytes_lines: encode a (bytes_to_skip, lines_to_skip)
     * delta into the code-info line table. Both must not be simultaneously 0.
     */
    public static void encodeLine(ByteBuf out, int bytesToSkip, int linesToSkip) {
        while (bytesToSkip > 0 || linesToSkip > 0) {
            int b, l;
            if (linesToSkip <= 6 || bytesToSkip > 0xf) {
                // 0b0LLBBBBB
                b = Math.min(bytesToSkip, 0x1f);
                l = (b < bytesToSkip) ? 0 : Math.min(linesToSkip, 0x3);
                out.u8(b | (l << 5));
            } else {
                // 0b1LLLBBBB 0bLLLLLLLL (l's low 8 bits in second byte)
                b = Math.min(bytesToSkip, 0xf);
                l = Math.min(linesToSkip, 0x7ff);
                out.u8(0x80 | b | ((l >> 4) & 0x70));
                out.u8(l & 0xff);
            }
            bytesToSkip -= b;
            linesToSkip -= l;
        }
    }
}
