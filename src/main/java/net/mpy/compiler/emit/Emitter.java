package net.mpy.compiler.emit;

import net.mpy.compiler.io.ByteBuf;
import net.mpy.compiler.qstr.QstrTable;

/**
 * Minimal bytecode emitter (py/emitbc.c subset), enough for straight-line
 * module code: name loads/stores, small-int / None constants, return, pop.
 *
 * Tracks stack depth (current + max, for n_state) exactly like
 * mp_emit_bc_adjust_stack_size, and accumulates code-info line deltas as the
 * bytecode grows (emit_write_code_info_bytes_lines). Jump-free, so a single
 * pass suffices — no label/offset stabilisation needed yet.
 *
 * bytecode qstr operands are the LOCAL qstr-table index encoded as a var-uint
 * (mp_encode_uint), NOT the (id<<1) table form.
 */
public final class Emitter {

    private final QstrTable qstrs;
    private final ByteBuf bytecode = new ByteBuf();
    private final ByteBuf lineInfo = new ByteBuf();

    private int stackSize = 0;
    private int maxStackSize = 0;
    private boolean suppress = false; // after RETURN_VALUE, dead code is suppressed

    private int lastLine = 1;
    private int lastLineByteOffset = 0;

    // --- multi-pass label machinery ---
    private int[] labelOffsets = new int[8]; // persists across passes
    private int passIndex = 0;               // 0 = sizing pass (offsets unknown)
    private int numLabels = 0;               // total labels allocated (stable across passes)
    private int nextLabel = 0;               // reset each pass

    public Emitter(QstrTable qstrs) { this.qstrs = qstrs; }

    /** Allocate a fresh label id (call during the walk; deterministic per pass). */
    public int newLabel() {
        int l = nextLabel++;
        if (l >= labelOffsets.length) labelOffsets = java.util.Arrays.copyOf(labelOffsets, labelOffsets.length * 2);
        if (nextLabel > numLabels) numLabels = nextLabel;
        return l;
    }

    /** Record that {@code label} is at the current bytecode offset. */
    public void labelAssign(int label) {
        suppress = false; // a jump target ends any dead-code region
        labelOffsets[label] = bytecode.size();
    }

    /** Reset per-pass emit state, preserving label offsets from the previous pass. */
    /** Advance to the next emit pass (label offsets from the previous one are usable). */
    public void nextPass() { passIndex++; }

    public void resetForPass() {
        bytecode.reset();
        lineInfo.reset();
        stackSize = 0;
        maxStackSize = 0;
        suppress = false;
        lastLine = 1;
        lastLineByteOffset = 0;
        nextLabel = 0;
        excStackSize = 0;
        maxExcStackSize = 0;
    }

    /** Snapshot of label offsets, to detect convergence between passes. */
    public int[] labelSnapshot() { return java.util.Arrays.copyOf(labelOffsets, numLabels); }

    public int maxStackSize() { return maxStackSize; }
    public byte[] bytecode() { return bytecode.toByteArray(); }
    public byte[] lineInfo() { return lineInfo.toByteArray(); }
    public int bytecodeLen() { return bytecode.size(); }

    private void adjustStack(int delta) {
        stackSize += delta;
        if (stackSize > maxStackSize) maxStackSize = stackSize;
    }

    /** set_source_line: emit a line delta if the line advanced. */
    public void setSourceLine(int line) {
        if (line > lastLine) {
            int bytesToSkip = bytecode.size() - lastLineByteOffset;
            Prelude.encodeLine(lineInfo, bytesToSkip, line - lastLine);
            lastLine = line;
            lastLineByteOffset = bytecode.size();
        }
    }

    // --- opcodes (subset) ---

    public void loadName(String qst) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_NAME).vuint(qstrs.intern(qst));
    }

    public void storeName(String qst) {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_NAME).vuint(qstrs.intern(qst));
    }

    public void loadConstNone() {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_CONST_NONE);
    }

    /** LOAD_CONST_STRING <qstr>. */
    public void loadConstString(String s) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_CONST_STRING).vuint(qstrs.intern(s));
    }

    /** LOAD_CONST_FALSE/NONE/TRUE via LOAD_CONST_FALSE + (tok - KW_FALSE). */
    public void loadConstTok(int delta) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_CONST_FALSE + delta);
    }

    public void loadConstSmallInt(long n) {
        adjustStack(+1);
        if (suppress) return;
        int excess = Opcodes.LOAD_CONST_SMALL_INT_MULTI_EXCESS;
        int num = Opcodes.LOAD_CONST_SMALL_INT_MULTI_NUM;
        if (-excess <= n && n < num - excess) {
            bytecode.u8(Opcodes.LOAD_CONST_SMALL_INT_MULTI + excess + (int) n);
        } else {
            bytecode.u8(Opcodes.LOAD_CONST_SMALL_INT);
            writeSignedVarint(n);
        }
    }

    public void popTop() {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.POP_TOP);
    }

    /** LOAD_FAST local (kind 0 = fast local). */
    public void loadFast(int localNum) {
        adjustStack(+1);
        if (suppress) return;
        if (localNum < 16) bytecode.u8(Opcodes.LOAD_FAST_MULTI + localNum);
        else bytecode.u8(Opcodes.LOAD_FAST_N).vuint(localNum);
    }

    public void storeFast(int localNum) {
        adjustStack(-1);
        if (suppress) return;
        if (localNum < 16) bytecode.u8(Opcodes.STORE_FAST_MULTI + localNum);
        else bytecode.u8(Opcodes.STORE_FAST_N).vuint(localNum);
    }

    /** LOAD_NAME (kind 0) / LOAD_GLOBAL (kind 1). */
    public void loadGlobalKind(String qst, int kind) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_NAME + kind).vuint(qstrs.intern(qst));
    }

    public void storeGlobalKind(String qst, int kind) {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_NAME + kind).vuint(qstrs.intern(qst));
    }

    /** MAKE_FUNCTION <child index>. */
    public void makeFunction(int childIndex) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.MAKE_FUNCTION).vuint(childIndex);
    }

    /**
     * Emit a jump-family opcode targeting {@code label}, with the 1/2-byte offset
     * encoding from emit_write_bytecode_byte_label. Uses labelOffsets from the
     * running pass (forward targets carry the previous pass's estimate, which
     * converges as jump sizes only shrink).
     */
    public void jump(int opcode, int label, int stackAdj) {
        adjustStack(stackAdj);
        if (suppress) return;
        boolean isSigned = opcode <= Opcodes.POP_JUMP_IF_FALSE;
        int here = bytecode.size();
        // Default to the largest (2-byte) encoding. Label offsets are only
        // meaningful once a full pass has measured the code, so the first pass
        // must be conservative, exactly as emit_write_bytecode_byte_label is
        // before MP_PASS_CODE_SIZE.
        int encSize = 1;
        long off = 0;
        if (passIndex > 0) {
            off = (long) labelOffsets[label] - here - 2;
            if ((isSigned && -64 <= off && off <= 63) || (!isSigned && off >= 0 && off <= 127)) {
                encSize = 0;
            }
            off -= encSize;
        }
        bytecode.u8(opcode);
        if (encSize == 0) {
            long b = isSigned ? off + 0x40 : off;
            bytecode.u8((int) (b & 0xff));
        } else {
            long b = isSigned ? off + 0x4000 : off;
            bytecode.u8((int) (0x80 | (b & 0x7f)));
            bytecode.u8((int) ((b >> 7) & 0xff));
        }
    }

    /** POP_JUMP_IF_FALSE label (pops the condition). */
    public void popJumpIfFalse(int label) { jump(Opcodes.POP_JUMP_IF_FALSE, label, -1); }
    /** POP_JUMP_IF_TRUE label. */
    public void popJumpIfTrue(int label) { jump(Opcodes.POP_JUMP_IF_TRUE, label, -1); }
    /** Unconditional JUMP label. Code after it is dead, so emission is suppressed. */
    public void jumpTo(int label) {
        jump(Opcodes.JUMP, label, 0);
        suppress = true;
    }
    /** JUMP_IF_TRUE_OR_POP label (for `or` value short-circuit). */
    public void jumpIfTrueOrPop(int label) { jump(Opcodes.JUMP_IF_TRUE_OR_POP, label, -1); }
    /** JUMP_IF_FALSE_OR_POP label (for `and` value short-circuit). */
    public void jumpIfFalseOrPop(int label) { jump(Opcodes.JUMP_IF_FALSE_OR_POP, label, -1); }

    // --- for-loop iterator protocol ---
    private static final int ITER_NSLOTS = 4; // MP_OBJ_ITER_BUF_NSLOTS

    /** GET_ITER_STACK: replace the iterable with the iterator buffer (stack +3). */
    public void getIterStack() {
        adjustStack(ITER_NSLOTS - 1);
        if (suppress) return;
        bytecode.u8(Opcodes.GET_ITER_STACK);
    }

    /** GET_ITER (non-stack): replace iterable with iterator (stack 0). */
    public void getIter() {
        adjustStack(0);
        if (suppress) return;
        bytecode.u8(Opcodes.GET_ITER);
    }

    /** STORE_COMP: append/insert into the comprehension collection. */
    public void storeComp(net.mpy.compiler.compile.Scope.Kind kind, int collectionStackIndex) {
        int n, t;
        if (kind == net.mpy.compiler.compile.Scope.Kind.LIST_COMP) { n = 0; t = 0; }
        else if (kind == net.mpy.compiler.compile.Scope.Kind.DICT_COMP) { n = 1; t = 1; }
        else { n = 0; t = 2; } // SET_COMP
        adjustStack(-1 - n);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_COMP).vuint(((long) (collectionStackIndex + n) << 2) | t);
    }

    /** FOR_ITER label: push next value (+1) or jump to {@code label} at exhaustion. */
    public void forIter(int label) { jump(Opcodes.FOR_ITER, label, +1); }

    /** for_iter_end: pop the iterator buffer (stack -4; adjust only, no bytecode). */
    public void forIterEnd() { adjustStack(-ITER_NSLOTS); }

    // --- exception handling (with / try) ---
    private int excStackSize = 0;
    private int maxExcStackSize = 0;
    public int maxExcStackSize() { return maxExcStackSize; }

    public void pushExcept() { excStackSize++; if (excStackSize > maxExcStackSize) maxExcStackSize = excStackSize; }
    public void popExcept() { excStackSize--; }

    // SETUP_BLOCK kinds
    public static final int SETUP_WITH = 0, SETUP_EXCEPT = 1, SETUP_FINALLY = 2;

    /** SETUP_WITH/EXCEPT/FINALLY <label> (unsigned fwd jump). */
    public void setupBlock(int label, int kind) {
        jump(Opcodes.SETUP_WITH + kind, label, kind == SETUP_WITH ? 2 : 0);
    }

    /** WITH_CLEANUP: LOAD_CONST_NONE, label, WITH_CLEANUP byte (+2), then -4. */
    public void withCleanup(int label) {
        loadConstNone();
        labelAssign(label);
        if (!suppress) { adjustStack(+2); bytecode.u8(Opcodes.WITH_CLEANUP); }
        adjustStack(-4);
    }

    public void endFinally() {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.END_FINALLY);
    }

    public void popExceptJump(int label) {
        jump(Opcodes.POP_EXCEPT_JUMP, label, 0);
        suppress = true;
    }

    /** start_except_handler: stack +4 (exception instance + unwind state), no bytecode. */
    public void startExceptHandler() { adjustStack(+4); }

    /** LOAD_CONST_OBJ <obj index> (const object table entry). */
    public void loadConstObj(int objIndex) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_CONST_OBJ).vuint(objIndex);
    }

    /** LOAD_BUILD_CLASS. */
    public void loadBuildClass() {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_BUILD_CLASS);
    }

    /** end_except_handler: stack -3. */
    public void endExceptHandler() { adjustStack(-3); }

    /**
     * STORE_COMP for a comprehension: kindType 0=list, 1=dict, 2=set;
     * operand = ((collectionStackIndex + n) << 2) | t where n=1 for dict else 0.
     */
    public void storeComp(int kindType, int collectionStackIndex) {
        int n = (kindType == 1) ? 1 : 0;
        adjustStack(-1 - n);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_COMP).vuint(((long) (collectionStackIndex + n) << 2) | kindType);
    }


    /** Public stack adjust (for exception-handler bookkeeping). */
    public void adjustStackSize(int delta) { adjustStack(delta); }

    /** DELETE_DEREF <local_num> (cell/free variable). */
    public void deleteDeref(int localNum) {
        adjustStack(0);
        if (suppress) return;
        bytecode.u8(Opcodes.DELETE_DEREF).vuint(localNum);
    }

    public void deleteFast(int localNum) {
        adjustStack(0);
        if (suppress) return;
        bytecode.u8(Opcodes.DELETE_FAST).vuint(localNum);
    }

    public void deleteGlobalKind(String name, int kind) {
        adjustStack(0);
        if (suppress) return;
        bytecode.u8(kind == 1 ? Opcodes.DELETE_GLOBAL : Opcodes.DELETE_NAME).vuint(qstrs.intern(name));
    }

    /**
     * break/continue with except_depth 0: for a for-loop break, pop the iterator
     * buffer (NSLOTS raw POP_TOPs) first, then JUMP to the label. Sets suppress.
     */
    public void unwindJump(int label, int exceptDepth, boolean breakFromFor) {
        if (suppress) return;
        if (exceptDepth == 0) {
            if (breakFromFor) {
                // pop the iterator + iter_buf slots before jumping out of a for loop
                for (int i = 0; i < ITER_NSLOTS; i++) bytecode.u8(Opcodes.POP_TOP);
            }
            jump(Opcodes.JUMP, label, 0);
        } else {
            jump(Opcodes.UNWIND_JUMP, label, 0);
            bytecode.u8((breakFromFor ? 0x80 : 0) | exceptDepth); // raw depth byte
        }
        suppress = true;
    }

    /** Current exception-block nesting level (mirrors comp->cur_except_level). */
    public int excLevel() { return excStackSize; }

    public void returnValue() {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.RETURN_VALUE);
        suppress = true;
    }

    /** CALL_FUNCTION with n positional + n keyword args (no star flags). */
    public void callFunction(int nPositional, int nKeyword) {
        adjustStack(-(nPositional + 2 * nKeyword));
        if (suppress) return;
        bytecode.u8(Opcodes.CALL_FUNCTION).vuint(((long) nKeyword << 8) | nPositional);
    }

    /** LOAD_METHOD <qstr> (leaves method + self on the stack). */
    public void loadMethod(String qst) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_METHOD).vuint(qstrs.intern(qst));
    }

    /** LOAD_SUPER_METHOD <qstr> (stack -1: pops class+self, pushes method+self). */
    public void loadSuperMethod(String qst) {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_SUPER_METHOD).vuint(qstrs.intern(qst));
    }

    /** CALL_METHOD n positional + n keyword. */
    public void callMethod(int nPositional, int nKeyword) {
        adjustStack(-1 - (nPositional + 2 * nKeyword));
        if (suppress) return;
        bytecode.u8(Opcodes.CALL_METHOD).vuint(((long) nKeyword << 8) | nPositional);
    }

    /** CALL_FUNCTION_VAR_KW (star args): pops the extra star-bitmap slot too. */
    public void callFunctionVarKw(int nPositional, int nKeyword) {
        adjustStack(-(nPositional + 2 * nKeyword + 1));
        if (suppress) return;
        bytecode.u8(Opcodes.CALL_FUNCTION_VAR_KW).vuint(((long) nKeyword << 8) | nPositional);
    }

    public void callMethodVarKw(int nPositional, int nKeyword) {
        adjustStack(-1 - (nPositional + 2 * nKeyword + 1));
        if (suppress) return;
        bytecode.u8(Opcodes.CALL_METHOD_VAR_KW).vuint(((long) nKeyword << 8) | nPositional);
    }

    public void binaryOp(int opIndex) {
        adjustStack(-1);
        if (suppress) return; // pop 2, push 1
        bytecode.u8(Opcodes.BINARY_OP_MULTI + opIndex);
    }

    /** UNARY_OP (net stack 0). */
    public void unaryOp(int opIndex) {
        if (suppress) return;
        bytecode.u8(Opcodes.UNARY_OP_MULTI + opIndex);
    }

    /** DUP_TOP (chained assignment). */
    public void dupTop() {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.DUP_TOP);
    }

    /** RAISE_LAST/OBJ/FROM for n_args 0/1/2; sets suppress. */
    public void raiseVarargs(int nArgs) {
        adjustStack(-nArgs);
        if (!suppress) bytecode.u8(Opcodes.RAISE_LAST + nArgs);
        suppress = true;
    }

    /** IMPORT_NAME <qstr> (stack -1). */
    public void importName(String qst) {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.IMPORT_NAME).vuint(qstrs.intern(qst));
    }

    /** IMPORT_FROM <qstr> (stack +1). */
    public void importFrom(String qst) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.IMPORT_FROM).vuint(qstrs.intern(qst));
    }

    /** IMPORT_STAR (stack -1). */
    public void importStar() {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.IMPORT_STAR);
    }

    /** YIELD_VALUE (net stack 0: pops the value, pushes what was sent in). */
    public void yieldValue() {
        if (suppress) return;
        bytecode.u8(Opcodes.YIELD_VALUE);
    }

    /** YIELD_FROM (stack -1). */
    public void yieldFrom() {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.YIELD_FROM);
    }

    /** DUP_TOP_TWO (stack +2). */
    public void dupTopTwo() {
        adjustStack(+2);
        if (suppress) return;
        bytecode.u8(Opcodes.DUP_TOP_TWO);
    }

    /** ROT_TWO (swap top two; net stack 0). */
    public void rotTwo() {
        if (suppress) return;
        bytecode.u8(Opcodes.ROT_TWO);
    }

    /** ROT_THREE (net stack 0). */
    public void rotThree() {
        if (suppress) return;
        bytecode.u8(Opcodes.ROT_THREE);
    }

    /** UNPACK_EX n_left, n_right (starred assignment target). */
    public void unpackEx(int nLeft, int nRight) {
        adjustStack(-1 + nLeft + nRight + 1);
        if (suppress) return;
        bytecode.u8(Opcodes.UNPACK_EX).vuint(nLeft | ((long) nRight << 8));
    }

    /** UNPACK_SEQUENCE n (pop 1 sequence, push n elements). */
    public void unpackSequence(int n) {
        adjustStack(n - 1);
        if (suppress) return;
        bytecode.u8(Opcodes.UNPACK_SEQUENCE).vuint(n);
    }

    // --- containers / attribute / subscript ---

    public void buildTuple(int n) { build(Opcodes.BUILD_TUPLE, n, 1 - n); }
    public void buildList(int n)  { build(Opcodes.BUILD_LIST, n, 1 - n); }
    public void buildMap(int n)   { build(Opcodes.BUILD_MAP, n, 1); }
    public void buildSet(int n)   { build(Opcodes.BUILD_SET, n, 1 - n); }
    public void buildSlice(int n) { build(Opcodes.BUILD_SLICE, n, 1 - n); }

    /** LOAD_NULL (sentinel, e.g. empty kw-defaults for MAKE_FUNCTION_DEFARGS). */
    public void loadNull() {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_NULL);
    }

    /** LOAD_DEREF <local_num> (cell/free variable). */
    public void loadDeref(int num) {
        adjustStack(+1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_DEREF).vuint(num);
    }

    /** STORE_DEREF <local_num> (cell/free variable). */
    public void storeDeref(int num) {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_DEREF).vuint(num);
    }

    /** MAKE_CLOSURE <child> <n_closed_over> (pops n cells, pushes closure). */
    public void makeClosure(int childIndex, int nClosedOver) {
        adjustStack(-nClosedOver + 1);
        if (suppress) return;
        bytecode.u8(Opcodes.MAKE_CLOSURE).vuint(childIndex).u8(nClosedOver);
    }

    /** MAKE_CLOSURE_DEFARGS <child> <n_closed_over> (also pops default tuple + kw map). */
    public void makeClosureDefargs(int childIndex, int nClosedOver) {
        adjustStack(-2 - nClosedOver + 1);
        if (suppress) return;
        bytecode.u8(Opcodes.MAKE_CLOSURE_DEFARGS).vuint(childIndex).u8(nClosedOver);
    }

    /** MAKE_FUNCTION_DEFARGS <child index> (pops default tuple + kw map). */
    public void makeFunctionDefargs(int childIndex) {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.MAKE_FUNCTION_DEFARGS).vuint(childIndex);
    }

    private void build(int opcode, int n, int stackAdj) {
        adjustStack(stackAdj);
        if (suppress) return;
        bytecode.u8(opcode).vuint(n);
    }

    public void storeMap() {
        adjustStack(-2);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_MAP);
    }

    public void loadAttr(String qst) {
        adjustStack(0);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_ATTR).vuint(qstrs.intern(qst));
    }

    public void storeAttr(String qst) {
        adjustStack(-2);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_ATTR).vuint(qstrs.intern(qst));
    }

    public void loadSubscr() {
        adjustStack(-1);
        if (suppress) return;
        bytecode.u8(Opcodes.LOAD_SUBSCR);
    }

    public void storeSubscr() {
        adjustStack(-3);
        if (suppress) return;
        bytecode.u8(Opcodes.STORE_SUBSCR);
    }

    /** Signed varint for LOAD_CONST_SMALL_INT (emit_write_bytecode_byte_int). */
    private void writeSignedVarint(long num) {
        byte[] buf = new byte[10];
        int p = buf.length;
        do {
            buf[--p] = (byte) (num & 0x7f);
            num >>= 7;
        } while (num != 0 && num != -1);
        if (num == -1 && (buf[p] & 0x40) == 0) {
            buf[--p] = 0x7f;
        } else if (num == 0 && (buf[p] & 0x40) != 0) {
            buf[--p] = 0;
        }
        // store big-endian, continuation bit on all but last
        for (int i = p; i < buf.length - 1; i++) bytecode.u8((buf[i] & 0x7f) | 0x80);
        bytecode.u8(buf[buf.length - 1] & 0x7f);
    }
}
