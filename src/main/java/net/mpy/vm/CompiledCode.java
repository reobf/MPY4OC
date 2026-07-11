package net.mpy.vm;

import net.mpy.loader.MpyModule;

/**
 * The object returned by compile(): a loaded, ready-to-run code unit backed by an
 * {@link MpyModule}. exec() embeds this module (so it rides snapshots like any
 * REPL cell) and runs its root frame against a namespace. The original source is
 * kept only for repr/debugging.
 */
public final class CompiledCode {
    final MpyModule module;
    final String source;      // for repr; may be truncated by the caller
    final byte[] mpyBytes;    // the compiled bytes (needed to re-embed on exec)

    CompiledCode(MpyModule module, byte[] mpyBytes, String source) {
        this.module = module;
        this.mpyBytes = mpyBytes;
        this.source = source;
    }

    @Override public String toString() {
        return "<code object <string> at 0x0>";   // MicroPython-style repr
    }
}
