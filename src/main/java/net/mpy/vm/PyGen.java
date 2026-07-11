package net.mpy.vm;

/**
 * A generator instance (Phase 7): a suspended {@link Frame} of a generator
 * function, resumed by for-loops / next() / send(). Mirrors
 * mp_obj_gen_instance_t: the frame holds all execution state; {@code done}
 * corresponds to code_state.ip == 0 (exhausted).
 */
public final class PyGen {
    /** How the generator was most recently resumed (where results are delivered). */
    public static final int MODE_NONE = 0, MODE_FOR_ITER = 1, MODE_CALL = 2, MODE_YIELD_FROM = 3;

    public Frame frame;
    public boolean started;           // false until first resume (no send allowed)
    public boolean done;
    public int resumeMode = MODE_NONE;
    public int forIterExhaustIp;      // FOR_ITER's jump target on exhaustion

    public PyGen(Frame frame) {
        this.frame = frame;
        if (frame != null) frame.genOwner = this;
    }

    @Override public String toString() { return "<generator " + frame.code + ">"; }
}
