package net.mpy.loader;

/**
 * Parses the bytecode function "prelude" that sits at the start of every
 * bytecode blob. Faithful port of the MP_BC_PRELUDE_SIG_DECODE and
 * MP_BC_PRELUDE_SIZE_DECODE macros (py/bc.h).
 *
 * Layout:
 *   SIG   var-uint, bit-interleaved  xSSSSEAA [xFSSKAED]...
 *          -> n_state, n_exc_stack, scope_flags, n_pos_args, n_kwonly_args, n_def_pos_args
 *   SIZE  var-uint, bit-interleaved  xIIIIIIC...
 *          -> n_info (source-info bytes), n_cell (closure bytes)
 *   source info : n_info bytes = simple_name(qstr) argname0..argnameN(qstr) then line-number info
 *                 (N = n_pos_args + n_kwonly_args - 1); qstrs are var-uint indices into the module qstr table
 *   closure     : n_cell bytes, one local-number byte per cell
 *   bytecode    : the remaining bytes of the blob
 */
public final class Prelude {

    public int nState;
    public int nExcStack;
    public int scopeFlags;
    public int nPosArgs;
    public int nKwonlyArgs;
    public int nDefPosArgs;

    public int nInfo;   // bytes in the source-info section
    public int nCell;   // bytes (cells) in the closure section

    public int simpleNameQstr;     // qstr-table index of the function's name
    public int[] argNameQstrs;     // qstr-table indices of the argument names

    public int sourceInfoStart;    // offset (within blob) where source info begins
    public int lineInfoStart;      // offset where line-number info begins (after the qstrs)
    public int lineInfoLen;        // number of line-info bytes
    public int cellStart;          // offset where the closure/cell section begins
    public int bytecodeStart;

    /** The local-variable numbers that must be wrapped in cells at frame entry
     *  (one byte each between the line info and the bytecode). */
    public int[] cellLocalNums(byte[] funData) {
        int n = bytecodeStart - cellStart;
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = funData[cellStart + i] & 0xff;
        return r;
    }      // offset where executable bytecode begins

    /** Parse the prelude at the start of {@code blob} (offset 0). */
    public static Prelude parse(byte[] blob) {
        Prelude p = new Prelude();
        int[] ip = { 0 }; // boxed cursor so helpers can advance it

        // --- SIG: xSSSSEAA [xFSSKAED]... ---
        int z = blob[ip[0]++] & 0xff;
        int S = (z >> 3) & 0xf;
        int E = (z >> 2) & 0x1;
        int F = 0;
        int A = z & 0x3;
        int K = 0;
        int D = 0;
        for (int n = 0; (z & 0x80) != 0; ++n) {
            z = blob[ip[0]++] & 0xff;
            S |= (z & 0x30) << (2 * n);
            E |= (z & 0x02) << n;
            F |= ((z & 0x40) >> 6) << n;
            A |= (z & 0x4) << n;
            K |= ((z & 0x08) >> 3) << n;
            D |= (z & 0x1) << n;
        }
        p.nState = S + 1;
        p.nExcStack = E;
        p.scopeFlags = F;
        p.nPosArgs = A;
        p.nKwonlyArgs = K;
        p.nDefPosArgs = D;

        // --- SIZE: xIIIIIIC... ---
        int I = 0, C = 0;
        for (int n = 0; ; ++n) {
            z = blob[ip[0]++] & 0xff;
            C |= (z & 1) << n;
            I |= ((z & 0x7e) >> 1) << (6 * n);
            if ((z & 0x80) == 0) break;
        }
        p.nInfo = I;
        p.nCell = C;

        // --- source info: qstrs then line info ---
        p.sourceInfoStart = ip[0];
        p.simpleNameQstr = decodeUint(blob, ip);
        int nArgs = p.nPosArgs + p.nKwonlyArgs;
        p.argNameQstrs = new int[nArgs];
        for (int i = 0; i < nArgs; i++) {
            p.argNameQstrs[i] = decodeUint(blob, ip);
        }
        p.lineInfoStart = ip[0];
        p.lineInfoLen = (p.sourceInfoStart + p.nInfo) - p.lineInfoStart;

        // --- closure cells then bytecode ---
        p.cellStart = p.sourceInfoStart + p.nInfo;
        p.bytecodeStart = p.cellStart + p.nCell;
        return p;
    }

    /** MSB-first var-uint decode against a boxed cursor (same rule as read_uint). */
    private static int decodeUint(byte[] b, int[] ip) {
        int n = 0;
        while (true) {
            int x = b[ip[0]++] & 0xff;
            n = (n << 7) | (x & 0x7f);
            if ((x & 0x80) == 0) break;
        }
        return n;
    }
}
