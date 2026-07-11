package net.mpy.vm;

import net.mpy.loader.CodeObject;
import net.mpy.loader.MpyModule;

/**
 * A Python bytecode function object, created by MAKE_FUNCTION / MAKE_FUNCTION_DEFARGS.
 * Wraps the child {@link CodeObject} to execute, the module it belongs to (its
 * "context", used to resolve globals / qstrs / constants), and any positional
 * default arguments.
 *
 * The Java analogue of {@code mp_obj_fun_bc_t}.
 */
public final class PyFunction {
    public final CodeObject code;
    public final MpyModule module;
    public final Object[] defaults; // positional defaults (length == prelude.nDefPosArgs)
    public final java.util.Map<Object, Object> kwDefaults; // keyword-only defaults (nullable)

    private static final Object[] NO_DEFAULTS = new Object[0];

    public PyFunction(CodeObject code, MpyModule module, Object[] defaults) {
        this(code, module, defaults, null);
    }

    public PyFunction(CodeObject code, MpyModule module, Object[] defaults,
                      java.util.Map<Object, Object> kwDefaults) {
        this.code = code;
        this.module = module;
        this.defaults = defaults == null ? NO_DEFAULTS : defaults;
        this.kwDefaults = kwDefaults;
    }

    public String name() {
        return module.qstr(code.prelude.simpleNameQstr);
    }

    @Override public String toString() {
        return "<function " + name() + ">";
    }
}
