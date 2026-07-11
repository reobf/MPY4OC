package net.mpy.vm;

/**
 * A Java function exposed to Python code (Phase 4, the host-API bridge). Put one
 * into a VM's {@code globals} under a name and Python can call it like any other
 * function: {@code LOAD_GLOBAL name} + {@code CALL_FUNCTION} routes straight to
 * {@link #call}, synchronously, inside a single VM step - no new frame is pushed.
 *
 * Arguments arrive as the VM's value objects (Long/Double/Boolean/String/None =
 * {@link PyObj#NONE}/PyList/.../). Return a value object (or {@code PyObj.NONE}
 * for a "void" API); {@code null} is treated as Python None on the stack too, but
 * returning {@code PyObj.NONE} is clearer.
 */
@FunctionalInterface
public interface HostFunction {
    Object call(Object[] args);
}
