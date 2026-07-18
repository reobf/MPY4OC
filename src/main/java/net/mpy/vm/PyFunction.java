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
    /** User-set function attributes (func.attr = x); lazily created. Enables
     *  functools.wraps (setting __name__ etc.) and attaching data to functions. */
    public java.util.Map<String, Object> attrs;
    public java.util.Map<String, Object> attrsOrNew() {
        if (attrs == null) attrs = new java.util.LinkedHashMap<>();
        return attrs;
    }
    public final Object[] defaults; // positional defaults (length == prelude.nDefPosArgs)
    public final java.util.Map<Object, Object> kwDefaults; // keyword-only defaults (nullable)

    /** The namespace the function was defined in, when that was a sandbox exec's
     *  ns rather than the module globals (exec(code, ns) / a REPL). Null means the
     *  ordinary case: resolve globals against the module. Captured so a function
     *  defined inside exec(src, sandbox) keeps reading/writing that sandbox, the
     *  way Lua carries _ENV -- reads fall through to real globals, writes stay
     *  local. */
    public java.util.Map<String, Object> defScope;

    /** Whether {@link #defScope} is a sandbox (read-through to all globals) rather
     *  than a standard exec ns (builtins-only fall-through). Mirrors Frame.sandboxScope. */
    public boolean defSandbox;

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
