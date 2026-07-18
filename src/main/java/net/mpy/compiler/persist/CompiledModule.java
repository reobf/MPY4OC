package net.mpy.compiler.persist;

import net.mpy.compiler.model.MpConst;
import net.mpy.compiler.qstr.QstrTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the serializer needs to emit a complete .mpy file — the output of
 * the compiler/emitter pipeline (Steps 8-10), consumed by {@link MpySerializer}.
 *
 * Mirrors mp_compiled_module_t's serialized fields for the bytecode-only case:
 * a single global qstr table, a single global constant-object table, and the
 * outer module's raw-code (which recursively carries all nested scopes).
 */
public final class CompiledModule {

    public final QstrTable qstrs;
    public final List<MpConst> objs;
    public final RawCode top;

    public CompiledModule(QstrTable qstrs, List<MpConst> objs, RawCode top) {
        this.qstrs = qstrs;
        this.objs = objs;
        this.top = top;
    }

    public CompiledModule(QstrTable qstrs, RawCode top) {
        this(qstrs, new ArrayList<>(), top);
    }
}
