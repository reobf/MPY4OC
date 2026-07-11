package net.mpy.vm;

import net.mpy.runtime.Ops;

/**
 * Sandbox limits (Phase 8). Attached to a {@link Vm}; every limit surfaces as a
 * normal Python exception (RuntimeError / MemoryError, matching MicroPython's
 * types and texts), so scripts can catch them and the VM stays usable after.
 *
 * The step budget itself is the embedder's via {@link Vm#step(int[])} /
 * {@link Vm#resume(int)} — these limits guard everything the budget can't:
 * recursion depth, live memory, single-op allocations, and the bounded nested
 * executions used by {@code list(generator)} and the with-protocol.
 */
public final class Limits {

    /** Max frames in a call chain (RuntimeError "maximum recursion depth exceeded"). */
    public int maxFrameDepth = 256;

    /** Approximate max bytes of live script-reachable data (frames + globals);
     *  exceeded -> MemoryError. Checked every {@link #memoryCheckInterval} ops. */
    public long memoryLimit = 16L << 20;

    /** Instructions between reachable-memory audits (0 disables the audit). */
    public int memoryCheckInterval = 1024;

    /** Cap on elements/chars a single operation may allocate (repeat/concat/
     *  range materialisation) - process-wide, applied to {@link Ops#MAX_SEQ_LEN}. */
    public long maxSeqLength = 1L << 22;

    /** Total instruction fuel for one nested synchronous execution -
     *  {@code list(infinite_gen)} or a runaway __enter__/__exit__ - exceeded ->
     *  RuntimeError instead of a hang. */
    public long syncFuel = 8_000_000;
    public int maxIterItems = 10_000_000;

    /** An effectively-unlimited configuration (for trusted scripts). */
    public static Limits unlimited() {
        Limits l = new Limits();
        l.maxFrameDepth = Integer.MAX_VALUE;
        l.memoryLimit = Long.MAX_VALUE;
        l.memoryCheckInterval = 0;
        l.maxSeqLength = Long.MAX_VALUE / 16;
        l.syncFuel = Long.MAX_VALUE;
        return l;
    }

    /** Push the process-wide single-op cap into Ops. Called by the Vm. */
    void apply() { Ops.MAX_SEQ_LEN = maxSeqLength; }
}
