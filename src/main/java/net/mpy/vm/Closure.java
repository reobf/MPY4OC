package net.mpy.vm;

import net.mpy.runtime.PyObj;

/**
 * A closure (Phase 7): a bytecode function plus its captured cells. Calling it
 * prepends the cells as leading positional arguments (the callee's prelude
 * declares matching '*' parameters and wraps them per its cell list) — exactly
 * MicroPython's mp_obj_closure_t / closure_call.
 */
public final class Closure {
    public final PyFunction fun;
    public final PyObj.Cell[] closed;
    public Closure(PyFunction fun, PyObj.Cell[] closed) { this.fun = fun; this.closed = closed; }
    @Override public String toString() { return "<closure " + fun.name() + ">"; }
}
