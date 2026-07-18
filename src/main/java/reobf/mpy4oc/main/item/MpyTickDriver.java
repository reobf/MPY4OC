package reobf.mpy4oc.main.item;

import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.MachineHost;
import li.cil.oc.api.network.EnvironmentHost;
import reobf.mpy4oc.main.arch.MpyArchitecture;

/**
 * Shared helper for driving the mpy VM from a component's per-tick update().
 *
 * A component's update() is called by the tile entity every tick on the main
 * server thread, unconditionally -- unlike the architecture's runThreaded, which
 * is thread-pool scheduled and can skip ticks under load. Driving the VM here gives
 * steady per-tick execution.
 *
 * Outside runSynchronized the machine's inSynchronizedCall flag is false, so direct
 * component calls would consume the call budget and throw LimitReachedException. We
 * flip that flag via reflection for the duration of the step, reproducing the
 * immunity we had inside runSynchronized. If the flag can't be found, we don't drive
 * from here and let the architecture's own runSynchronized path take over.
 */
public final class MpyTickDriver {

    private final Machine machine;
    private java.lang.reflect.Field syncFlag;
    private boolean resolved = false;

    public MpyTickDriver(EnvironmentHost host) {
        Machine m = null;
        if (host instanceof MachineHost) {
            try { m = ((MachineHost) host).machine(); } catch (Throwable ignored) {}
        }
        this.machine = m;
        // Tell the architecture a ticker exists as early as possible, so its
        // runSynchronized stands down from the very first tick (the tile entity runs
        // machine.update BEFORE component updates, so registering only when we first
        // tick would let tick 1 run twice).
        registerWithArch();
    }

    /** Mark our architecture as ticker-driven, if reachable. Safe to call repeatedly. */
    public void registerWithArch() {
        if (machine == null) return;
        try {
            Object a = machine.architecture();
            if (a instanceof MpyArchitecture) ((MpyArchitecture) a).registerTicker();
        } catch (Throwable ignored) {
        }
    }

    /** Drive the VM one tick, with call-budget immunity if we can secure it. */
    public void tick() {
        if (machine == null) return;
        MpyArchitecture arch;
        try {
            Object a = machine.architecture();
            if (!(a instanceof MpyArchitecture)) return;
            arch = (MpyArchitecture) a;
        } catch (Throwable t) {
            return;
        }
        java.lang.reflect.Field flag = syncFlag();
        if (flag == null) return;   // no immunity -> leave it to runSynchronized
        boolean prev;
        try {
            prev = flag.getBoolean(machine);
            flag.setBoolean(machine, true);
        } catch (Throwable t) {
            return;
        }
        try {
            arch.tickFromComponent();
        } finally {
            try { flag.setBoolean(machine, prev); } catch (Throwable ignored) {}
        }
    }

    /** Machine.inSynchronizedCall (Scala private var -> same-named private boolean
     *  field; scan fallback for name mangling across Scala versions). */
    private java.lang.reflect.Field syncFlag() {
        if (resolved) return syncFlag;
        resolved = true;
        Class<?> c = machine.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("inSynchronizedCall");
                f.setAccessible(true);
                syncFlag = f;
                return f;
            } catch (NoSuchFieldException e) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getName().contains("inSynchronizedCall")
                            && (f.getType() == boolean.class || f.getType() == Boolean.class)) {
                        f.setAccessible(true);
                        syncFlag = f;
                        return f;
                    }
                }
            }
            c = c.getSuperclass();
        }
        syncFlag = null;
        return null;
    }
}
