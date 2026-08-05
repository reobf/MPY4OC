package net.mpy.vm;

import net.mpy.loader.CodeObject;

/**
 * A reified execution frame - the Java analogue of MicroPython's
 * {@code mp_code_state_t}. All execution context lives in fields (not on the Java
 * call stack), so a frame can be stepped one bytecode at a time and suspended /
 * resumed between instructions.
 *
 * Layout of {@link #state} (size {@code n_state}), matching the C VM:
 *   - the Python value stack grows upward from index 0 ({@link #sp} = top, -1 when empty);
 *   - local variables occupy the high end: local {@code k} is at {@code state[n_state-1-k]}
 *     (so locals and the value stack grow toward each other in one array).
 */
public final class Frame {

    public final CodeObject code;
    public final byte[] bc;        // executable bytecode (prelude stripped)
    public final Object[] state;   // locals (high end) + value stack (low end)
    public final int nState;

    public int ip;                 // instruction pointer into bc
    public int sp = -1;            // value-stack top index (-1 == empty)

    /** The frame that called this one (null for the root frame). */
    public Frame caller;

    // ---- exception stack (mirror of mp_exc_stack_t), sized prelude.nExcStack ----
    public final int[] excHandler;       // handler ip
    public final int[] excValSp;         // saved value-stack sp at SETUP time
    public final boolean[] excIsFinally; // SETUP_FINALLY/WITH vs SETUP_EXCEPT
    public final Object[] excPrevExc;    // saved exception (for RAISE_LAST)
    public int excSp = -1;               // top index (-1 == empty)

    // ---- Phase 7 ----
    /** Class-body frames: LOAD_NAME/STORE_NAME target this namespace (else globals). */
    public java.util.Map<String, Object> names;
    /** Set when this frame is a generator's body (owned by that generator). */
    public PyGen genOwner;
    /** If non-null, RETURN_VALUE delivers this instead of the popped value
     *  (used so __init__ calls return the new instance). */
    public Object returnOverride;
    /** Set on class-body frames: on return, the body's returned __class__ cell (if
     *  any) is filled with this class, and the class is what the call yields. */
    public PyClass buildingClass;
    /** Set on module-top frames pushed by an import: on return, the importer's ip
     *  rewinds here so the (idempotent, cache-driven) import instruction re-runs
     *  and proceeds to the next module in the chain. -1 when unused. */
    public int importRetryIp = -1;
    /** Set on an __enter__ subframe pushed by SETUP_WITH: on return, its result is
     *  delivered to the caller and the with-block (exception handler) is installed
     *  on the caller. Holds the SETUP_WITH arg (handler offset); -1 when unused.
     *  This makes __enter__ a real, suspendable subframe so a cooperative busy-wait
     *  inside it (e.g. a Lock.acquire spin) yields to the scheduler correctly,
     *  instead of being driven synchronously by callSync. */
    public int withEnterArg = -1;
    /** Set on a property-getter subframe pushed by LOAD_METHOD: on return, the
     *  getter's result is delivered to the caller's slot and the extra `null`
     *  self-slot that LOAD_METHOD always pushes is appended. (LOAD_ATTR needs no
     *  flag -- its result simply replaces the slot.) Like withEnterArg, this is
     *  what lets a getter that waits cooperatively (a network read polling with
     *  time.sleep) yield instead of burning callSync's fuel. */
    public boolean propPushNull;
    /** For frames created by exec()/eval(): the namespace their global name ops
     *  target (null = the VM's globals). When set, doReturn delivers the frame's
     *  result to the caller (exec -> None already on stack; eval -> the value). */
    public java.util.Map<String, Object> execGlobals;
    /** When execGlobals is set: false = standard exec/eval semantics (the ns is the
     *  whole global namespace; only builtins fall through to the VM globals); true =
     *  sandbox semantics (reads also fall through to the VM's user globals, writes
     *  stay in the ns), used by exec_sandbox(). Ignored when execGlobals is null. */
    public boolean sandboxScope;
    /** True for the root frame of an exec()/eval() unit, so doReturn delivers its
     *  result to the caller instead of ending the VM. */
    public boolean isExecRoot;
    /** True for eval's root frame: its result is the captured expression value. */
    public boolean isEvalRoot;

    private static final int[] EMPTY_INT = new int[0];
    private static final boolean[] EMPTY_BOOL = new boolean[0];
    private static final Object[] EMPTY_OBJ = new Object[0];

    public Frame(CodeObject code) {
        this.code = code;
        this.bc = code.bytecode();
        this.nState = code.prelude.nState;
        this.state = new Object[nState];
        this.ip = 0;
        int nExc = code.prelude.nExcStack;
        if (nExc == 0) {
            this.excHandler = EMPTY_INT; this.excValSp = EMPTY_INT;
            this.excIsFinally = EMPTY_BOOL; this.excPrevExc = EMPTY_OBJ;
        } else {
            this.excHandler = new int[nExc];
            this.excValSp = new int[nExc];
            this.excIsFinally = new boolean[nExc];
            this.excPrevExc = new Object[nExc];
        }
    }

    // ---- value stack --------------------------------------------------------
    public void push(Object v) { state[++sp] = v; }
    // Nulling on pop keeps popped slots invisible to BOTH the JVM GC and the
    // memory audit (MemEstimator walks the whole state array): a value that has
    // left the stack is garbage and must not be retained -- or billed --
    // through a stale slot. A suspended generator/task frame otherwise pins
    // whatever last passed through its operand stack for as long as it sleeps.
    public Object pop() { Object v = state[sp]; state[sp--] = null; return v; }
    public Object top() { return state[sp]; }
    public void setTop(Object v) { state[sp] = v; }
    /** Peek n items below the top (peek(0) == top). */
    public Object peek(int n) { return state[sp - n]; }
    public void set(int depth, Object v) { state[sp - depth] = v; }
    /** Discard the top n slots, nulling them: every caller is a pure discard
     *  (POP_TOP, jump-pops, cleanup), and a discarded value must not be retained
     *  -- or billed by the memory audit -- through a stale slot. */
    public void drop(int n) { while (n-- > 0) state[sp--] = null; }

    /** Lower sp to exactly newSp, nulling the vacated slots. No-op if newSp >= sp.
     *  For unwind paths (exception handler entry, iterator-buffer teardown) where
     *  the region above newSp is dead. NOT for hostPause rewind -- that path
     *  RAISES sp to resurrect the operand stack and depends on slots surviving. */
    public void dropTo(int newSp) { while (sp > newSp) state[sp--] = null; }

    // ---- locals -------------------------------------------------------------
    public Object local(int k) { return state[nState - 1 - k]; }
    public void setLocal(int k, Object v) { state[nState - 1 - k] = v; }

    /** Place positional arguments into locals 0..n-1 before execution. */
    public void setArgs(Object[] args) {
        for (int i = 0; i < args.length; i++) setLocal(i, args[i]);
    }
}
