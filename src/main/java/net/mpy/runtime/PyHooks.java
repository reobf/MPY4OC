package net.mpy.runtime;

/**
 * The conversions string formatting ({@code fmt % args}, {@code str.format},
 * {@code format()}) needs that may run user Python code: {@code __str__},
 * {@code __repr__}, {@code __format__} and the numeric dunders. Ops and Format
 * have no VM, so the VM passes one that calls back into Python;
 * {@link #PLAIN} handles built-in values (and built-in subclass instances, via
 * their native value) without running any user code.
 */
public interface PyHooks {
    /** {@code str(o)}. */
    String str(Object o);

    /** {@code repr(o)}. */
    String repr(Object o);

    /** {@code o.name(args...)} when o is a user instance whose class defines
     *  {@code name}, else null (a defined method returning None gives
     *  {@link PyObj#NONE}, never null). */
    Object callDunder(Object o, String name, Object... args);

    /** {@code o.name} ({@code "{0.name}"} fields), raising AttributeError when absent. */
    Object getAttr(Object o, String name);

    PyHooks PLAIN = new PyHooks() {
        @Override public String str(Object o) { return net.mpy.vm.Builtins.str(Ops.unwrapNative(o)); }
        @Override public String repr(Object o) { return PyObj.repr(Ops.unwrapNative(o)); }
        @Override public Object callDunder(Object o, String name, Object... args) { return null; }
        @Override public Object getAttr(Object o, String name) {
            throw PyException.attributeError("'" + net.mpy.vm.StdLib.typeName(o) + "' object has no attribute '" + name + "'");
        }
    };
}
