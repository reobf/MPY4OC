package net.mpy.vm;

import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;

import java.math.BigInteger;

/**
 * Ergonomic, arity- and type-typed variants of {@link HostFunction} for the
 * common cases, so a host API can declare its real signature instead of hand-
 * unpacking {@code Object[]}:
 *
 * <pre>{@code
 *   globals.put("damage", (Host.L) amount -> { world.hurt(amount); return PyObj.NONE; });
 *   globals.put("dist",   (Host.DD) (x, y) -> Math.hypot(x, y));
 *   globals.put("spawn",  (Host.S) kind -> world.spawn(kind));
 * }</pre>
 *
 * Each variant extends {@code HostFunction} and implements {@code call(Object[])}
 * as an arity check + coercion + delegate, so the VM's dispatch path is
 * unchanged (still one {@code instanceof HostFunction}); this is purely a
 * convenience layer over the same call site, not a new fast path. Arguments are
 * coerced the same way the runtime would (int accepts Long/BigInteger/bool;
 * float additionally accepts int). Nothing here removes boxing: the VM's value
 * stack already holds boxed {@code Long}/{@code Double}, so the values arrive
 * boxed regardless.
 */
public final class Host {
    private Host() {}

    // ---- coercion (mirrors the runtime's own numeric argument rules) ----

    static long asLong(Object o, int i) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof BigInteger) return ((BigInteger) o).longValueExact();
        throw PyException.typeError("argument " + i + ": expected int, got " + typeName(o));
    }

    static double asDouble(Object o, int i) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Long) return (Long) o;
        if (o instanceof Boolean) return (Boolean) o ? 1.0 : 0.0;
        if (o instanceof BigInteger) return ((BigInteger) o).doubleValue();
        throw PyException.typeError("argument " + i + ": expected float, got " + typeName(o));
    }

    static String asStr(Object o, int i) {
        if (o instanceof String) return (String) o;
        throw PyException.typeError("argument " + i + ": expected str, got " + typeName(o));
    }

    static boolean asBool(Object o, int i) { return net.mpy.runtime.Ops.isTrue(o); }

    private static void arity(Object[] a, int n) {
        if (a.length != n) throw PyException.typeError(
                "expected " + n + " argument" + (n == 1 ? "" : "s") + ", got " + a.length);
    }

    private static String typeName(Object o) { return StdLib.typeName(o); }

    // ---- zero-arg ----
    /** {@code () -> result} */
    @FunctionalInterface public interface V extends HostFunction {
        Object apply();
        @Override default Object call(Object[] a) { arity(a, 0); return apply(); }
    }

    // ---- one-arg, typed ----
    /** {@code (long) -> result} */
    @FunctionalInterface public interface L extends HostFunction {
        Object apply(long x);
        @Override default Object call(Object[] a) { arity(a, 1); return apply(asLong(a[0], 0)); }
    }
    /** {@code (double) -> result} */
    @FunctionalInterface public interface D extends HostFunction {
        Object apply(double x);
        @Override default Object call(Object[] a) { arity(a, 1); return apply(asDouble(a[0], 0)); }
    }
    /** {@code (String) -> result} */
    @FunctionalInterface public interface S extends HostFunction {
        Object apply(String x);
        @Override default Object call(Object[] a) { arity(a, 1); return apply(asStr(a[0], 0)); }
    }
    /** {@code (boolean) -> result} */
    @FunctionalInterface public interface B extends HostFunction {
        Object apply(boolean x);
        @Override default Object call(Object[] a) { arity(a, 1); return apply(asBool(a[0], 0)); }
    }
    /** {@code (Object) -> result} - one arg, no coercion (any runtime value). */
    @FunctionalInterface public interface O extends HostFunction {
        Object apply(Object x);
        @Override default Object call(Object[] a) { arity(a, 1); return apply(a[0]); }
    }

    // ---- two-arg, typed (the common numeric/string pairs) ----
    /** {@code (long, long) -> result} */
    @FunctionalInterface public interface LL extends HostFunction {
        Object apply(long x, long y);
        @Override default Object call(Object[] a) { arity(a, 2); return apply(asLong(a[0], 0), asLong(a[1], 1)); }
    }
    /** {@code (double, double) -> result} */
    @FunctionalInterface public interface DD extends HostFunction {
        Object apply(double x, double y);
        @Override default Object call(Object[] a) { arity(a, 2); return apply(asDouble(a[0], 0), asDouble(a[1], 1)); }
    }
    /** {@code (String, Object) -> result} - e.g. set(name, value). */
    @FunctionalInterface public interface SO extends HostFunction {
        Object apply(String x, Object y);
        @Override default Object call(Object[] a) { arity(a, 2); return apply(asStr(a[0], 0), a[1]); }
    }
    /** {@code (Object, Object) -> result} - two args, no coercion. */
    @FunctionalInterface public interface OO extends HostFunction {
        Object apply(Object x, Object y);
        @Override default Object call(Object[] a) { arity(a, 2); return apply(a[0], a[1]); }
    }

    // ---- three-arg, uncoerced (e.g. 3D vectors as Objects, or mixed) ----
    /** {@code (Object, Object, Object) -> result} */
    @FunctionalInterface public interface OOO extends HostFunction {
        Object apply(Object x, Object y, Object z);
        @Override default Object call(Object[] a) { arity(a, 3); return apply(a[0], a[1], a[2]); }
    }
    /** {@code (double, double, double) -> result} - e.g. a 3D point. */
    @FunctionalInterface public interface DDD extends HostFunction {
        Object apply(double x, double y, double z);
        @Override default Object call(Object[] a) { arity(a, 3); return apply(asDouble(a[0], 0), asDouble(a[1], 1), asDouble(a[2], 2)); }
    }
}
