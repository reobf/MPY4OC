package net.mpy.vm;

/**
 * A builtin host function that Python defers to a dunder on user instances, as
 * {@code round(x[, n])} calls {@code x.__round__([n])}: when the first argument
 * is an instance whose class defines {@link #dunder}, the VM calls that method
 * with the remaining arguments instead (and otherwise converts user instances
 * among the remaining arguments with __index__ before calling). Still a plain
 * HostFunction to everything else, so it snapshots by its globals name.
 */
final class DunderHostFunction implements HostFunction {
    final String dunder;
    private final HostFunction impl;

    DunderHostFunction(String dunder, HostFunction impl) {
        this.dunder = dunder;
        this.impl = impl;
    }

    @Override public Object call(Object[] args) { return impl.call(args); }
}
