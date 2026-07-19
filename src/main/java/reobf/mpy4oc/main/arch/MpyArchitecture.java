package reobf.mpy4oc.main.arch;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Frame;
import net.mpy.vm.HostFunction;
import net.mpy.vm.HostRegistry;
import net.mpy.vm.PyModule;
import net.mpy.vm.Vm;
import reobf.mpy4oc.main.MyMod;
import reobf.mpy4oc.main.item.ItemMpyAPU;
import reobf.mpy4oc.main.item.ItemMpyCPU;
import reobf.mpy4oc.main.item.ItemMpyCPUAsync;
import reobf.mpy4oc.main.item.ItemMpyAPUAsync;

/**
 * The "mpy" CPU architecture: runs MicroPython bytecode on the pure-Java MPY VM.
 *
 * <h3>Execution model — main thread, synchronously</h3>
 * OpenComputers normally runs {@code runThreaded()} on a background computer
 * thread (that is how Lua works), and only handles {@code SynchronizedCall} on
 * the main server thread. Component calls that are not {@code direct} therefore
 * need a round trip through the machine state machine, which is where Lua's
 * timing hiccups come from.
 *
 * This architecture instead executes the VM <em>entirely on the main server
 * thread</em>: {@link #runThreaded(boolean)} does no work and always asks for a
 * synchronized call, and the actual bytecode stepping happens in
 * {@link #runSynchronized()}, which OC invokes from {@code Machine.update()} on
 * the main thread. Because we are already on the main thread, every component
 * call can be performed directly and synchronously — no yield/resume dance, no
 * deferred calls, no timing surprises.
 *
 * The VM is a natural fit: {@code vm.step(budget)} runs a bounded number of
 * bytecodes and can suspend anywhere, so each tick simply spends one instruction
 * budget and returns.
 *
 * <h3>Booting</h3>
 * For now the boot is deliberately the simplest thing that works: scan every
 * attached filesystem component for {@code /init.py} (source) or {@code /init.mpy}
 * (pre-compiled bytecode), and run the first one found. An EEPROM-driven boot
 * chain comes later.
 */
@Architecture.Name("mpy")
@Architecture.NoMemoryRequirements
public class MpyArchitecture implements Architecture {

    private final Machine machine;

    private Vm vm;
    private HostRegistry hosts;

    /** Soft memory ceiling for the sandbox, in bytes. This is a rough figure, not
     *  a measurement of JVM heap: the VM estimates the size of script-reachable
     *  data (frames, globals, containers) and raises MemoryError past this. Sized
     *  like an OpenComputers Lua machine with a couple of RAM sticks (~1 MB) so
     *  runaway scripts fail cleanly instead of dragging the game down, and so
     *  computer.freeMemory()/totalMemory() report believable numbers. */
    static final long MEMORY_LIMIT_BYTES = 1L << 20;   // 1 MiB
    /** Set once the boot script has been loaded and the VM armed. */
    private boolean initialized;
    /** Set when the program ran to completion or crashed; stops further stepping. */
    private boolean ended;
    /** Pending shutdown/reboot requested from Python via computer.shutdown(). */
    private boolean wantShutdown, wantReboot;
    /** Error to surface to OC on the next runThreaded (crashes the computer). */
    private String pendingError;

    /** Bytecode budget per tick, by CPU tier. Tier 1/2/3 (0-based 0/1/2). These are
     *  a quarter of the old flat rate: unused ops are banked and roll into later ticks
     *  (up to {@link #BANK_MULTIPLIER}x this rate), so a script that yields when idle
     *  builds up headroom for later bursts -- it pays to call yieldJava()/sleep. */
    private static final int[] OPS_BY_TIER = { 500, 2000, 16000 };

    /** ASYNC CPU only: how many non-direct component calls the main-thread
     *  synchronized window may perform before parking, by tier (tier x4). A higher
     *  tier drains more of the round trip per synchronized call, so a call-heavy
     *  async program does not thrash the SynchronizedCall state machine one call at
     *  a time. Unused by the synchronous CPU (which is already on the main thread). */
    private static final int[] SYNC_CALLS_BY_TIER = { 4, 8, 12 };

    /** True when this architecture is running an ASYNC mpy CPU: the VM steps on the
     *  OC computer thread ({@link #runThreaded}) with a bounded budget, and a
     *  non-direct component call parks the VM and hands off to the main thread via
     *  {@link ExecutionResult.SynchronizedCall}. The synchronous CPU leaves this
     *  false and keeps running everything on the main thread. */
    private boolean async = false;

    /** ASYNC only: which execution phase we are in, so the shared component-call
     *  interception ({@link #invokeComponent}) knows what to do with a non-direct
     *  call. THREADED = on the computer thread: park and defer to a synchronized
     *  call. SYNC = in the main-thread window: execute in place and count it. */
    private enum Phase { THREADED, SYNC }
    private Phase phase = Phase.THREADED;

    /**
     * Directness lookup caches. Resolving a callback's @Callback annotation means
     * walking the network to the component (or rebuilding a method map for a Value)
     * on EVERY call, and the guards ask on every component call -- so it is worth
     * remembering. OpenComputers itself does the same thing (see machine.lua's
     * `directCache`, keyed address..":"..method).
     *
     * <p>Component cache is keyed address+method and dropped whenever the machine
     * sees components come or go, so a swapped-out device can never leave a stale
     * answer behind.
     *
     * <p>Value cache is keyed by CLASS, not by instance, and that is deliberate:
     * OpenComputers derives a Value's callbacks from its class
     * (Callbacks.cache is `Map[Class[_], Map[String, Callback]]`), so directness is
     * a per-class property. Keying on the instance would also be useless in practice
     * -- a Value is typically a short-lived wrapper handed back fresh from each call,
     * so an instance-keyed (even weak) map would miss every time. WeakHashMap on the
     * class so an unloaded mod's classes can still be collected.
     */
    private final Map<String, Boolean> directCache = new HashMap<String, Boolean>();
    private final Map<Class<?>, Map<String, Boolean>> valueDirectCache =
            new java.util.WeakHashMap<Class<?>, Map<String, Boolean>>();

    /** True only while inside vm.step() on the computer thread. Parking rewinds the
     *  currently executing instruction, so it is only meaningful there. Anything else
     *  that runs on the main thread but happens to see phase==THREADED (snapshot
     *  restore, for instance) must NOT park -- this flag is what tells them apart. */
    private boolean inVmStep = false;

    /** ASYNC only: non-direct calls performed so far in the current synchronized
     *  window; compared against {@link #SYNC_CALLS_BY_TIER} to decide when to park. */
    private int syncCallsThisWindow = 0;

    /** How many ticks' worth of ops may be banked (cap = ops/tick * this). */
    private static final int BANK_MULTIPLIER = 10;

    /** Ops carried over from earlier ticks (the bank), added to each tick's fresh
     *  allowance and capped at OPS_BY_TIER[tier] * BANK_MULTIPLIER. */
    private int bankedOps = 0;

    /** Set once a CPU/APU ticker component has registered itself (see
     *  {@link #registerTicker()}). While true, runSynchronized is a no-op: the
     *  ticker's per-tick update() drives the VM instead (reliably, every tick).
     *  The tile entity runs machine.update() -- hence runSynchronized -- BEFORE the
     *  component updates, so this must be known up front, not set mid-tick, or the
     *  first tick would drive twice. */
    private boolean hasTicker = false;

    /** The machine uptime (in ticks) at which the VM was last driven. Used to make
     *  {@link #driveOneTick()} idempotent within a single game tick: during the brief
     *  startup/load window before hasTicker is set, BOTH runSynchronized and the
     *  ticker can fire in the same tick, and without this the VM would take two op
     *  budgets that tick. -1 means "not driven yet". */
    private long lastDriveTick = -1;

    /** Monotonic id for wrapped OC Values, so their method host-functions get stable
     *  registry ids and a snapshot taken while one is live can be written and, on
     *  restore, reconnected to the same (re-persisted) userdata. */
    private int valueSeq = 0;

    /** Live wrapped OC Values by id ("value.N"). Values are Persistable, so these
     *  are saved (class name + NBT) with the arch and rebuilt on load, letting a
     *  wrapped value held in a Python variable survive a world save. */
    private final Map<String, li.cil.oc.api.machine.Value> wrappedValues =
            new java.util.HashMap<String, li.cil.oc.api.machine.Value>();

    /** Cached ops budget for this run, resolved at boot from the installed CPU. */
    private int opsPerTick = OPS_BY_TIER[0];

    /** Resolve the per-tick op budget from the installed mpy CPU/APU tier. Walks
     *  the machine's internal components for an ItemMpyCPU (or ItemMpyAPU) and maps
     *  its tier to {@link #OPS_BY_TIER}; defaults to tier 1 if none is found. */
    /** Configure the VM's sandbox limits from the machine's memory ceiling, in one
     *  place so fresh boot and snapshot restore stay consistent:
     *   - memoryLimit: the live-data ceiling (periodic MemoryError audit);
     *   - maxSeqLength: the largest a SINGLE allocation may be, in elements. Tied to
     *     the memory limit so one statement like {@code [0]*10**9} or {@code bytes(10**9)}
     *     is rejected BEFORE allocating (worst case 8 bytes/element must fit the
     *     ceiling), rather than only being caught after the fact by the audit;
     *   - the pickle allocation guard: same byte ceiling for pickle.loads(). */
    private void applySandboxLimits(Vm vm) {
        net.mpy.vm.Limits lim = vm.limits();
        lim.memoryLimit = MEMORY_LIMIT_BYTES;
        // elements, assuming the largest per-element cost we allocate (8-byte refs)
        lim.maxSeqLength = Math.max(1L, MEMORY_LIMIT_BYTES / 8);
        vm.setLimits(lim);   // re-applies to Ops.MAX_SEQ_LEN
        net.mpy.vm.Pickle.setAllocGuard(bytes -> bytes <= MEMORY_LIMIT_BYTES);
    }

    /**
     * Invoke a component method from a host API (computer.beep, setBootAddress, ...)
     * while honouring the ASYNC CPU's main-thread rule.
     *
     * <p>{@link #invokeComponent} does this for calls Python makes through
     * {@code component.invoke}, but a host API that reaches for {@code machine.invoke}
     * directly would sail past it and run a non-direct callback on the computer
     * thread -- exactly what the async design exists to prevent (those callbacks
     * touch world/network state the server thread owns). Routing through here parks
     * the VM instead and re-runs the call on the main thread, the same way a
     * component.invoke would.
     *
     * <p>Safe to re-run: the park rewinds to the start of the calling instruction, so
     * the host function is entered again from scratch on the main thread. Callers
     * must therefore be idempotent (setBootAddress/beep are).
     */
    /**
     * Guard for host APIs that reach for {@code machine.invoke} directly: if any of
     * {@code methods} on {@code address} is a non-direct callback and we are running
     * on the computer thread (ASYNC CPU), park the VM so the whole host function is
     * re-entered on the main thread.
     *
     * <p>Call this at the TOP of the host function -- before any side effect and
     * OUTSIDE any try/catch. Both matter:
     * <ul>
     *   <li>Parking throws {@link Vm.HostPause}, and callers here routinely wrap
     *       their bodies in {@code catch (Exception)}; caught there, the pause would
     *       be swallowed while the VM stayed rewound, and the instruction would
     *       retry forever.</li>
     *   <li>A park rewinds to the start of the calling instruction, so the host
     *       function runs again from scratch. Anything already done (a file opened,
     *       say) would be done a second time -- leaking the first handle.</li>
     * </ul>
     * Checking every method the function will use, up front, keeps the rewind clean.
     *
     * <p>Directness is looked up dynamically per call: only OpenComputers knows
     * whether a given component's method is direct, and a modded component may
     * differ from the stock one of the same name.
     */
    private void requireMainThreadFor(String address, String... methods) {
        if (!async || phase != Phase.THREADED || vm == null || !inVmStep) return;
        for (String m : methods) {
            if (!isDirectCall(address, m)) {
                needSyncCall = true;
                vm.hostPause();      // rewinds + unwinds; re-entered on the main thread
                throw new AssertionError("unreachable: hostPause must throw");
            }
        }
    }

    /** {@link li.cil.oc.api.machine.Value} flavour of {@link #requireMainThreadFor}.
     *  Values carry the same direct/non-direct annotations as components. */
    private void requireMainThreadForValue(li.cil.oc.api.machine.Value value, String... methods) {
        if (!async || phase != Phase.THREADED || vm == null || !inVmStep) return;
        for (String m : methods) {
            if (!isDirectValueCall(value, m)) {
                needSyncCall = true;
                vm.hostPause();
                throw new AssertionError("unreachable: hostPause must throw");
            }
        }
    }

    /** True if {@code method} on this Value is a direct callback. Unknown -> false
     *  (treated as non-direct, i.e. routed to the main thread: the safe default). */
    private boolean isDirectValueCall(li.cil.oc.api.machine.Value value, String method) {
        if (value == null) return false;
        Class<?> cls = value.getClass();
        Map<String, Boolean> byMethod = valueDirectCache.get(cls);
        if (byMethod != null) {
            Boolean cached = byMethod.get(method);
            if (cached != null) return cached.booleanValue();
        }
        boolean direct;
        try {
            java.util.Map<String, li.cil.oc.api.machine.Callback> m = machine.methods(value);
            li.cil.oc.api.machine.Callback cb = m == null ? null : m.get(method);
            direct = cb != null && cb.direct();
        } catch (Throwable ignored) {
            return false;      // unknown -> non-direct (safe), and do not cache a guess
        }
        if (byMethod == null) {
            byMethod = new HashMap<String, Boolean>();
            valueDirectCache.put(cls, byMethod);
        }
        byMethod.put(method, Boolean.valueOf(direct));
        return direct;
    }

    /**
     * Turn an exception thrown by a component callback into the Python exception a
     * script should see, following the classification OpenComputers' own Lua
     * architecture uses (NativeLuaArchitecture.invoke):
     *
     * <pre>
     *   IllegalArgumentException  -> false + message   (Lua error())
     *   IndexOutOfBoundsException -> "index out of bounds"
     *   NoSuchMethodException     -> "no such method"
     *   UnsupportedOperationException -> "unsupported operation"
     *   FileNotFoundException     -> nil, "file not found"
     *   SecurityException         -> nil, "access denied"
     *   IOException               -> nil, "i/o error"
     *   anything else             -> nil, message (or "unknown error")
     * </pre>
     *
     * <p>Lua splits these two ways: the first group is raised, the second is
     * returned as {@code nil, message} for the caller to test. Python has no
     * "return nil plus reason" idiom -- the equivalent is an exception you catch --
     * so we raise in both cases, but map each class to the Python exception type
     * that means the same thing. That keeps OpenComputers' distinctions usable
     * ({@code except OSError} for a missing file, {@code except ValueError} for a
     * bad argument) instead of flattening everything into one opaque error, and it
     * preserves OC's own wording for the messages it standardises.
     */
    private net.mpy.runtime.PyException componentError(Exception e) {
        String msg = e.getMessage();
        if (e instanceof IllegalArgumentException) {
            return new net.mpy.runtime.PyException("ValueError", msg != null ? msg : "bad argument");
        }
        if (e instanceof IndexOutOfBoundsException) {
            return new net.mpy.runtime.PyException("IndexError", "index out of bounds");
        }
        if (e instanceof NoSuchMethodException) {
            return new net.mpy.runtime.PyException("AttributeError", "no such method");
        }
        if (e instanceof UnsupportedOperationException) {
            return new net.mpy.runtime.PyException("NotImplementedError", "unsupported operation");
        }
        if (e instanceof java.io.FileNotFoundException) {
            return new net.mpy.runtime.PyException("OSError", "file not found");
        }
        if (e instanceof SecurityException) {
            return new net.mpy.runtime.PyException("OSError", "access denied");
        }
        if (e instanceof java.io.IOException) {
            return new net.mpy.runtime.PyException("OSError", msg != null ? msg : "i/o error");
        }
        return new net.mpy.runtime.PyException("RuntimeError", msg != null ? msg : "unknown error");
    }

    private int resolveOpsPerTick() {
        try {
            for (net.minecraft.item.ItemStack stack : machine.host().internalComponents()) {
                if (stack == null) continue;
                net.minecraft.item.Item item = stack.getItem();
                int tier = -1;
                if (item instanceof ItemMpyCPU) tier = ((ItemMpyCPU) item).tierOf(stack);
                else if (item instanceof ItemMpyAPU) tier = ((ItemMpyAPU) item).tierOf(stack);
                else if (item instanceof ItemMpyCPUAsync) { tier = ((ItemMpyCPUAsync) item).tierOf(stack); async = true; }
                else if (item instanceof ItemMpyAPUAsync) { tier = ((ItemMpyAPUAsync) item).tierOf(stack); async = true; }
                if (tier >= 0) {
                    if (tier >= OPS_BY_TIER.length) tier = OPS_BY_TIER.length - 1;
                    return OPS_BY_TIER[tier];
                }
            }
        } catch (Throwable ignored) {
            // host/inventory not available (e.g. some test harnesses) -> default
        }
        return OPS_BY_TIER[0];
    }

    /** Debug output, drained by whatever wants to show it (chat/log for now). */
    public final List<String> output = new ArrayList<String>();

    public MpyArchitecture(Machine machine) {
        this.machine = machine;
    }

    // ------------------------------------------------------------------ //
    // Architecture SPI
    // ------------------------------------------------------------------ //

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean recomputeMemory(Iterable<ItemStack> components) {
        return true;   // @NoMemoryRequirements: memory is not modelled yet
    }

    /**
     * Called by OC when the computer starts. We only build the VM here; the boot
     * script is located and loaded on the first main-thread step, because
     * component discovery wants the network to be fully connected.
     */
    @Override
    public boolean initialize() {
        vm = null;
        initialized = false;
        ended = false;
        wantShutdown = wantReboot = false;
        pendingError = null;
        output.clear();
        return true;
    }

    @Override
    public void close() {
        vm = null;
        hosts = null;
        initialized = false;
        ended = false;
        wantShutdown = wantReboot = false;
        pendingError = null;
        output.clear();
    }

    /**
     * Runs on the OC computer thread. We do no work here: returning
     * SynchronizedCall bounces the machine into its synchronized-call state,
     * whose handler ({@link #runSynchronized()}) OC invokes on the main server
     * thread — which is where we actually want to run the VM.
     */
    @Override
    public ExecutionResult runThreaded(boolean isSynchronizedReturn) {
        if (pendingError != null) {
            String msg = pendingError;
            pendingError = null;
            return new ExecutionResult.Error(msg);
        }
        if (wantShutdown) {
            boolean reboot = wantReboot;
            wantShutdown = wantReboot = false;
            return new ExecutionResult.Shutdown(reboot);
        }
        if (ended) {
            // Program finished: idle the machine but keep it alive.
            return new ExecutionResult.Sleep(Integer.MAX_VALUE);
        }
        if (async) return runThreadedAsync(isSynchronizedReturn);
        return new ExecutionResult.SynchronizedCall();
    }

    /**
     * ASYNC path: step the VM on the OC computer thread with a bounded budget
     * (fresh per-tier ops plus the bank). A non-direct component call parks the VM
     * (see {@link #invokeComponent}); we then ask OC for a synchronized call, which
     * re-runs the parked instruction on the main thread in {@link #runSynchronized}.
     * A direct call runs here; if it exhausts the tick call budget it parks too and
     * we simply sleep a tick (no synchronized call needed -- the retry is a normal
     * resume). OC serializes this with world save/load via a machine-wide lock, so
     * VM state is never touched concurrently.
     */
    private ExecutionResult runThreadedAsync(boolean isSynchronizedReturn) {
        try {
            if (vm == null) {
                // Boot on the MAIN thread, never here. Booting touches components
                // (read the EEPROM, read /init.py off a disk) and, on a modded
                // filesystem, any of those could be a non-direct callback -- which
                // must not run on the computer thread. There is also no VM yet, so
                // the usual park-and-retry has no instruction to rewind to. Handing
                // the whole boot to runSynchronized sidesteps both problems, and it
                // costs one tick once per machine start.
                needSyncCall = true;
                return new ExecutionResult.SynchronizedCall();
            }
            drainSignals();

            phase = Phase.THREADED;
            needSyncCall = false;
            int cap = opsPerTick * BANK_MULTIPLIER;
            int allowance = opsPerTick + bankedOps;
            if (allowance > cap) allowance = cap;
            int[] budget = { allowance };
            inVmStep = true;
            try {
                vm.step(budget);
            } finally {
                inVmStep = false;
            }
            bankedOps = clampBank(budget[0], cap);

            if (vm.isFinished()) {
                ended = true;
                print("[mpy] program finished");
                return new ExecutionResult.Sleep(Integer.MAX_VALUE);
            }
            // A non-direct call parked (from the main frame's HostPause, or from a
            // task via the hostPaused flag): hand off to the main thread.
            if (needSyncCall || vm.consumeHostPaused()) {
                return new ExecutionResult.SynchronizedCall();
            }
            // Otherwise a normal yield (sleep/pullSignal/budget-exhausted/direct-call
            // budget park): idle a tick and resume on the computer thread.
            return new ExecutionResult.Sleep(1);
        } catch (Throwable t) {
            crash(t);
            if (pendingError != null) { String m = pendingError; pendingError = null; return new ExecutionResult.Error(m); }
            return new ExecutionResult.Sleep(Integer.MAX_VALUE);
        }
    }

    private int clampBank(int remaining, int cap) {
        if (remaining > cap) return cap;
        if (remaining < 0) return 0;
        return remaining;
    }

    /**
     * Runs on the MAIN SERVER THREAD (from Machine.update()). This is where the
     * VM actually executes: one instruction budget per call. Component calls made
     * from Python during this window happen directly and synchronously, since we
     * are already on the main thread.
     */
    @Override
    public void runSynchronized() {
        if (async) { runSynchronizedAsync(); return; }
        // If a CPU/APU ticker component is present it drives the VM every tick from
        // its own update() (the reliable, thread-pool-independent path), so this is
        // a no-op -- the machine still cycles through its synchronized-call state,
        // but the work already happened. A machine without our ticker (or where the
        // ticker couldn't secure call-budget immunity) still runs here.
        if (hasTicker) return;
        driveOneTick();
    }

    /**
     * ASYNC path, MAIN SERVER THREAD. Re-runs the parked instruction (the non-direct
     * call now executes in place, since we are on the main thread) and keeps
     * stepping for a bounded slice -- {@code opsPerTick/10} drawn from the bank --
     * so several non-direct calls can be consumed per synchronized round instead of
     * one round trip per call. Stops when the tier's per-window call limit is hit
     * (the limiting call has already returned -- no pending call to stash), when the
     * VM yields for another reason, or when the slice runs out.
     */
    private void runSynchronizedAsync() {
        if (ended || pendingError != null) return;
        try {
            phase = Phase.SYNC;
            // First synchronized window after start: bring the VM up here, on the
            // main thread (see runThreadedAsync). Nothing else to do this tick.
            if (vm == null) {
                boot();
                return;
            }
            syncCallsThisWindow = 0;
            int tier = tierIndex();
            syncCallLimit = SYNC_CALLS_BY_TIER[tier >= SYNC_CALLS_BY_TIER.length ? SYNC_CALLS_BY_TIER.length - 1 : tier];
            // Draw the sync slice straight from the bank, never below zero. This one
            // step runs until: the slice is spent, the VM finishes, the tier call
            // limit is hit (invokeComponent requests a yield after that call), or the
            // VM yields for another reason (sleep/pullSignal/another non-direct...).
            // Size the window to actually DRAIN the parked calls rather than trickle
            // them out. Measured with three tasks parked at once: a tenth of a tick
            // cleared one per window, a quarter cleared two, a half cleared all three
            // and still handed back ~45% of the slice. Each resume costs a re-run of
            // the parked instruction plus the call itself, so the window has to be
            // worth several of those.
            //
            // This is not extra budget: the slice is drawn from the bank and the unused
            // part is returned, so it only moves ops to where the work is. The
            // per-window call cap (syncCallLimit) still bounds how much main-thread
            // time a single window can take.
            int slice = opsPerTick / 2;
            if (slice > bankedOps) slice = bankedOps;
            if (slice < 1) slice = 1;            // always make some progress
            int[] budget = { slice };
            // Async first: this window belongs to the parked tasks, not the main frame.
            vm.stepAsyncFirst(budget);
            int used = slice - budget[0];
            bankedOps -= used;
            if (bankedOps < 0) bankedOps = 0;
            if (vm.isFinished()) { ended = true; print("[mpy] program finished"); }
            // If a further non-direct call parked on the computer-thread side during
            // this window (can happen when the step yields then a task runs), OC will
            // issue another synchronized call on its own; nothing to do here.
            vm.consumeHostPaused();
        } catch (Vm.HostPause hp) {
            // A THREADED-style park should not occur in SYNC phase, but if a nested
            // path raised one, the instruction was rewound -- OC will re-run us.
        } catch (Throwable t) {
            crash(t);
        } finally {
            phase = Phase.THREADED;
        }
    }

    private int syncCallLimit = 4;

    /** 0-based tier of the installed mpy CPU/APU (mirrors resolveOpsPerTick). */
    private int tierIndex() {
        try {
            for (net.minecraft.item.ItemStack stack : machine.host().internalComponents()) {
                if (stack == null) continue;
                net.minecraft.item.Item item = stack.getItem();
                if (item instanceof ItemMpyCPU) return clampTier(((ItemMpyCPU) item).tierOf(stack));
                if (item instanceof ItemMpyAPU) return clampTier(((ItemMpyAPU) item).tierOf(stack));
                if (item instanceof ItemMpyCPUAsync) return clampTier(((ItemMpyCPUAsync) item).tierOf(stack));
                if (item instanceof ItemMpyAPUAsync) return clampTier(((ItemMpyAPUAsync) item).tierOf(stack));
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }
    private int clampTier(int t) { return t < 0 ? 0 : (t >= OPS_BY_TIER.length ? OPS_BY_TIER.length - 1 : t); }

    /** Called once by the MpyTicker/APU component when it first ticks, so the
     *  architecture knows to leave driving to it (and runSynchronized stands down).
     *  Idempotent. */
    public void registerTicker() {
        hasTicker = true;
    }

    /** Called by the ticker component from its per-tick update() (guaranteed every
     *  tick on the main thread). Registers the ticker on first call, then drives. */
    public void tickFromComponent() {
        hasTicker = true;
        driveOneTick();
    }

    /** One tick of VM execution: boot if needed, feed a signal, spend the banked
     *  op budget, re-bank the remainder, and surface finish/crash. Shared by the
     *  ticker path and the fallback runSynchronized path. */
    private void driveOneTick() {
        if (ended || pendingError != null) return;
        // Run at most once per game tick, no matter who calls (runSynchronized and
        // the ticker can both fire in the same tick during the startup/load handoff
        // window). Uptime advances by one per tick; if it hasn't advanced, we already
        // drove this tick.
        long nowTick;
        try {
            nowTick = Math.round(machine.upTime() * 20.0);
        } catch (Throwable t) {
            nowTick = -1;   // no clock available (tests) -> don't de-dup
        }
        if (nowTick >= 0 && nowTick == lastDriveTick) return;
        lastDriveTick = nowTick;
        try {
            if (vm == null) {
                if (!boot()) return;    // boot() sets pendingError / ended on failure
            }
            // Feed one pending OC signal into the VM's queue, if any.
            drainSignals();

            // This tick's allowance = the tier's fresh rate plus whatever was banked
            // from earlier ticks. A script that yields when it has nothing to do
            // leaves ops unspent; those roll into the bank (capped at 10x the rate)
            // so a later burst can run longer than a single tick would allow.
            int cap = opsPerTick * BANK_MULTIPLIER;
            int allowance = opsPerTick + bankedOps;
            if (allowance > cap) allowance = cap;
            int[] budget = { allowance };
            vm.step(budget);
            // Re-bank the unused remainder (budget[0]) for next tick, capped.
            bankedOps = budget[0];
            if (bankedOps > cap) bankedOps = cap;
            if (bankedOps < 0) bankedOps = 0;

            if (vm.isFinished()) {
                ended = true;
                print("[mpy] program finished");
            }
        } catch (Throwable t) {
            crash(t);
        }
    }

    @Override
    public void onSignal() {
        // Signals are pulled in runSynchronized(); nothing to do here.
    }

    @Override
    public void onConnect() {
        // Components are discovered lazily via machine.components().
    }

    // ------------------------------------------------------------------ //
    // Persistence — the VM's native whole-machine snapshot
    // ------------------------------------------------------------------ //

    @Override
    public void save(NBTTagCompound nbt) {
        nbt.setBoolean("initialized", initialized);
        nbt.setBoolean("ended", ended);
        // Persist any live wrapped OC Values so a value held in a Python variable
        // survives the save (Value is Persistable: class name + its own save(nbt)).
        try {
            if (!wrappedValues.isEmpty()) {
                net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
                for (Map.Entry<String, li.cil.oc.api.machine.Value> e : wrappedValues.entrySet()) {
                    NBTTagCompound vt = new NBTTagCompound();
                    vt.setString("id", e.getKey());
                    vt.setString("class", e.getValue().getClass().getName());
                    NBTTagCompound data = new NBTTagCompound();
                    e.getValue().save(data);
                    vt.setTag("data", data);
                    list.appendTag(vt);
                }
                nbt.setTag("wrappedValues", list);
                nbt.setInteger("valueSeq", valueSeq);
            }
        } catch (Throwable t) {
            MyMod.LOG.warn("mpy: failed to persist wrapped values", t);
        }
        try {
            if (vm != null && !ended) {
                byte[] snap = vm.saveSnapshot(hosts);
                nbt.setByteArray("vm", compress(snap));
                nbt.setBoolean("hasVm", true);
            }
        } catch (Throwable t) {
            MyMod.LOG.warn("mpy: failed to snapshot VM", t);
        }
    }

    @Override
    public void load(NBTTagCompound nbt) {
        initialized = nbt.getBoolean("initialized");
        ended = nbt.getBoolean("ended");
        // Rebuild any persisted wrapped OC Values first, so the snapshot resolver
        // below can reconnect their method host-functions to a live value.
        wrappedValues.clear();
        valueSeq = nbt.getInteger("valueSeq");
        if (nbt.hasKey("wrappedValues")) {
            net.minecraft.nbt.NBTTagList list =
                    nbt.getTagList("wrappedValues", 10 /* compound */);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound vt = list.getCompoundTagAt(i);
                try {
                    Class<?> cls = Class.forName(vt.getString("class"));
                    Object obj = cls.newInstance();
                    if (obj instanceof li.cil.oc.api.machine.Value) {
                        li.cil.oc.api.machine.Value v = (li.cil.oc.api.machine.Value) obj;
                        v.load(vt.getCompoundTag("data"));
                        wrappedValues.put(vt.getString("id"), v);
                    }
                } catch (Throwable t) {
                    MyMod.LOG.warn("mpy: could not restore wrapped value "
                            + vt.getString("class"), t);
                }
            }
        }
        if (!nbt.getBoolean("hasVm")) return;
        try {
            byte[] snap = decompress(nbt.getByteArray("vm"));
            Map<String, Object> globals = new LinkedHashMap<String, Object>();
            hosts = HostRegistry.fromGlobals(globals);
            installHostApi(globals);
            // proxies captured in the snapshot resolve their bound methods by id
            registerProxyMethods();
            // ...but at chunk-load time the component network may not be back yet,
            // so registerProxyMethods() can miss components that existed at save
            // time. Proxy ids are deterministic ("proxy.<address>.<method>"), so
            // install a resolver that rebuilds any such bound method on demand.
            // Without this, a snapshot suspended in a gpu/screen call (the common
            // case -- mpyos is almost always mid tty.read on the screen) fails to
            // restore and the machine reboots on load.
            hosts.withResolver(new HostRegistry.Resolver() {
                @Override
                public HostFunction resolve(String id) {
                    if (id.startsWith("value.")) return resolveValueMethod(id);
                    if (!id.startsWith("proxy.")) return null;
                    int dot = id.lastIndexOf('.');
                    if (dot <= "proxy.".length()) return null;
                    String address = id.substring("proxy.".length(), dot);
                    String method = id.substring(dot + 1);
                    return boundMethod(address, method);
                }
            });
            vm = Vm.fromSnapshot(snap, globals, hosts);
            applySandboxLimits(vm);
            opsPerTick = resolveOpsPerTick();
            vm.setCompiler(COMPILER);
            // The finder and import root are VM configuration, not part of the
            // snapshot, so they must be reinstalled or the restored system can no
            // longer import anything (running a command, import fs, ...) after a
            // save/load. bootFs is likewise rederived so imports prefer the boot disk.
            bootFs = deriveBootFs();
            vm.setModuleFinder(diskFinder());
            vm.addImportRoot("lib");
        } catch (Throwable t) {
            MyMod.LOG.warn("mpy: failed to restore VM snapshot", t);
            vm = null;
            initialized = false;
        }
    }

    // ------------------------------------------------------------------ //
    // Boot: find /init.py (or /init.mpy) on any filesystem and run it
    // ------------------------------------------------------------------ //

    private boolean boot() {
        // Resolve the per-tick op budget from the installed CPU/APU tier now that
        // the machine and its inventory are available.
        opsPerTick = resolveOpsPerTick();

        // The program the VM actually runs is the EEPROM's bios. It is the bios that
        // decides which disk to load /init.py from (honouring the boot address, or
        // scanning for a bootable medium) -- exactly like OpenComputers' bios.lua.
        // If there is no EEPROM at all we fall back to scanning for /init.py directly,
        // so the architecture still works on a machine without a flashed bios (dev,
        // tests).
        byte[] mpy = null;
        String from = null;

        String biosSource = readEepromCode();
        if (biosSource != null && !biosSource.isEmpty()) {
            try {
                mpy = MyMod.compile(biosSource);
                from = "eeprom";
            } catch (Exception ex) {
                fail("compile error in bios: " + ex.getMessage());
                return false;
            }
        } else {
            // No bios: scan filesystems for /init.mpy or /init.py directly.
            for (Map.Entry<String, String> e : machine.components().entrySet()) {
                if (!"filesystem".equals(e.getValue())) continue;
                String fs = e.getKey();
                byte[] compiled = readCompiled(fs, "/init.mpy");
                if (compiled != null) {
                    mpy = compiled;
                    from = fs + ":/init.mpy";
                    break;
                }
                String source = readText(fs, "/init.py");
                if (source != null) {
                    try {
                        mpy = MyMod.compile(source);
                        from = fs + ":/init.py";
                        break;
                    } catch (Exception ex) {
                        fail("compile error in /init.py: " + ex.getMessage());
                        return false;
                    }
                }
            }
        }

        if (mpy == null) {
            fail("no bootable medium found (need an EEPROM with a bios, or /init.py on a filesystem)");
            return false;
        }

        try {
            MpyModule module = MpyLoader.load(mpy, new QstrPool());
            Map<String, Object> globals = new LinkedHashMap<String, Object>();
            hosts = HostRegistry.fromGlobals(globals);
            installHostApi(globals);

            vm = new Vm(module, globals);
            applySandboxLimits(vm);
            vm.setCompiler(COMPILER);
            vm.setModuleFinder(diskFinder());
            vm.addImportRoot("lib");
            installComputerBootstrap(globals);
            vm.start(new Frame(module.root));

            initialized = true;
            print("[mpy] booted from " + from);
            return true;
        } catch (Throwable t) {
            fail("boot failed: " + t);
            return false;
        }
    }

    /** Read the EEPROM code section (the bios), or null if there is no EEPROM. */
    private String readEepromCode() {
        for (Map.Entry<String, String> e : machine.components().entrySet()) {
            if (!"eeprom".equals(e.getValue())) continue;
            try {
                Object[] r = machine.invoke(e.getKey(), "get", new Object[0]);
                if (r != null && r.length > 0 && r[0] != null) {
                    Object code = r[0];
                    if (code instanceof byte[]) return new String((byte[]) code, java.nio.charset.StandardCharsets.UTF_8);
                    return code.toString();
                }
            } catch (Throwable ignored) {
            }
            return null;   // found an eeprom but could not read it
        }
        return null;       // no eeprom
    }

    /** Read a whole file from a filesystem component as text, or null if absent. */
    private String readText(String fs, String path) {
        byte[] b = readBytes(fs, path);
        try {
            return b == null ? null : new String(b, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readCompiled(String fs, String path) {
        return readBytes(fs, path);
    }

    /**
     * Read a file from an OC filesystem component: open, read in chunks, close.
     * Returns null if the file cannot be opened (i.e. does not exist).
     */
    private byte[] readBytes(String fs, String path) {
        // One park point, before anything is opened: if this filesystem's read path
        // is non-direct we must do the whole open/read/close on the main thread.
        requireMainThreadFor(fs, "open", "read", "close");
        Object handle;
        try {
            Object[] r = machine.invoke(fs, "open", new Object[] { path, "r" });
            if (r == null || r.length == 0 || r[0] == null || Boolean.FALSE.equals(r[0])) return null;
            handle = r[0];
        } catch (Exception e) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            while (true) {
                Object[] r = machine.invoke(fs, "read", new Object[] { handle, Double.valueOf(4096) });
                if (r == null || r.length == 0 || r[0] == null) break;
                Object data = r[0];
                if (data instanceof byte[]) {
                    out.write((byte[]) data);
                } else if (data instanceof String) {
                    out.write(((String) data).getBytes("UTF-8"));
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            try {
                machine.invoke(fs, "close", new Object[] { handle });
            } catch (Exception ignored) {
            }
        }
        byte[] b = out.toByteArray();
        return b.length == 0 ? null : b;
    }

    // ------------------------------------------------------------------ //
    // Host API exposed to Python
    // ------------------------------------------------------------------ //

    /**
     * Install the Python-level signal API (pullSignal/pushSignal) onto the computer
     * module. These are written in Python on top of the _poll_signal / _yield_tick
     * primitives because a host function cannot re-run itself to implement a wait
     * loop, whereas a Python loop calling _yield_tick resumes cleanly on the next
     * tick. The deadline uses computer.uptime() (real server time that advances
     * across ticks), not the frozen time module.
     */
    /**
     * A module finder that resolves imports against the machine's filesystems, so
     * mpyos can be split across files (import fs, import event, ...). For an
     * import path like "lib/fs" or "fs" it looks for a matching .mpy (prebuilt) or
     * .py (compiled on the fly) on any filesystem, checking the boot disk first.
     *
     * The VFS path is extension-less; we try it as "/<path>.mpy" then "/<path>.py",
     * and also the bare "/<path>" under a couple of common roots.
     */
    private net.mpy.vm.ModuleFinder diskFinder() {
        return path -> {
            // Candidate on-disk locations for an extension-less import path.
            String[] candidates = {
                "/" + path + ".mpy",
                "/" + path + ".py",
                "/lib/" + path + ".mpy",
                "/lib/" + path + ".py",
            };
            // Boot filesystem first, then any other.
            java.util.List<String> order = new ArrayList<String>();
            if (bootFs != null) order.add(bootFs);
            for (Map.Entry<String, String> e : machine.components().entrySet()) {
                if ("filesystem".equals(e.getValue()) && !e.getKey().equals(bootFs)) {
                    order.add(e.getKey());
                }
            }
            for (String fs : order) {
                for (String cand : candidates) {
                    if (cand.endsWith(".mpy")) {
                        byte[] compiled = readCompiled(fs, cand);
                        if (compiled != null) return compiled;
                    } else {
                        String src = readText(fs, cand);
                        if (src != null) {
                            try {
                                return MyMod.compile(src);
                            } catch (Exception ex) {
                                print("[mpy] import compile error in " + cand + ": " + ex.getMessage());
                                return null;
                            }
                        }
                    }
                }
            }
            return null;
        };
    }

    /** The filesystem the bios booted from, set from the __boot_fs__ the bios exports. */
    private String bootFs;

    /** Re-derive the boot filesystem after a load (bootAddress, else first /init.py). */
    private String deriveBootFs() {
        try {
            for (Map.Entry<String, String> e : machine.components().entrySet()) {
                if (!"eeprom".equals(e.getValue())) continue;
                Object[] r = machine.invoke(e.getKey(), "getData", new Object[0]);
                if (r != null && r.length > 0 && r[0] != null) {
                    String addr = r[0] instanceof byte[]
                            ? new String((byte[]) r[0], java.nio.charset.StandardCharsets.UTF_8)
                            : r[0].toString();
                    if (!addr.isEmpty()) return addr;
                }
                break;
            }
        } catch (Throwable ignored) {
        }
        // no boot address: first filesystem that has /init.py
        for (Map.Entry<String, String> e : machine.components().entrySet()) {
            if (!"filesystem".equals(e.getValue())) continue;
            try {
                Object[] r = machine.invoke(e.getKey(), "exists", new Object[]{"/init.py"});
                if (r != null && r.length > 0 && Boolean.TRUE.equals(r[0])) return e.getKey();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void installComputerBootstrap(Map<String, Object> globals) {
        String boot = String.join("\n",
            "def _mpy_pull_signal(timeout=None):",
            "    _d = None if timeout is None else computer.uptime() + timeout",
            "    while True:",
            "        _s = computer._poll_signal()",
            "        if _s is not None:",
            "            return _s",
            "        if _d is not None and computer.uptime() >= _d:",
            "            return None",
            "        computer._yield_tick()",
            "",
            "def _mpy_time_sleep(ms):",
            "    # cooperative sleep: yield ticks until the server clock passes the",
            "    # deadline. The frozen time module's default __time_sleep is a no-op,",
            "    # which would make time.sleep(1) return instantly in a game OS.",
            "    _d = computer.uptime() + ms / 1000",
            "    while computer.uptime() < _d:",
            "        computer._yield_tick()",
            "",
            "def _mpy_eeprom():",
            "    for _addr in component.list('eeprom'):",
            "        return _addr",
            "    return None",
            "",
            "def _mpy_get_boot_address():",
            "    _e = _mpy_eeprom()",
            "    if _e is None:",
            "        return None",
            "    _v = component.invoke(_e, 'getData')",
            "    if _v is None:",
            "        return None",
            "    if isinstance(_v, bytes):",   // OC's getData hands back a byte array
            "        _v = _v.decode('utf-8')",  // decode so it matches str fs addresses
            "    if _v == '':",
            "        return None",
            "    return _v",
            "",
            "def _mpy_set_boot_address(address=''):",
            "    _e = _mpy_eeprom()",
            "    if _e is None:",
            "        return False",
            "    component.invoke(_e, 'setData', address)",
            "    return True",
            "");
        try {
            byte[] code = MyMod.compile(boot);
            vm.execCell("computer-bootstrap", code);
            // Move the freshly defined function into the computer module namespace,
            // so scripts call it as computer.pullSignal(...) like in OpenOS.
            Object fn = globals.get("_mpy_pull_signal");
            if (fn != null) {
                PyModule computer = (PyModule) globals.get("computer");
                if (computer != null) {
                    computer.ns.put("pullSignal", fn);   // a Python function; no host id
                    Object getBoot = globals.get("_mpy_get_boot_address");
                    Object setBoot = globals.get("_mpy_set_boot_address");
                    if (getBoot != null) computer.ns.put("getBootAddress", getBoot);
                    if (setBoot != null) computer.ns.put("setBootAddress", setBoot);
                }
            }
            // time.sleep goes through the late-bound __time_sleep primitive; replace
            // the no-op default with the cooperative tick-yielding version.
            Object sleepFn = globals.get("_mpy_time_sleep");
            if (sleepFn != null) {
                globals.put("__time_sleep", sleepFn);
            }
        } catch (Throwable t) {
            MyMod.LOG.warn("mpy: failed to install computer bootstrap", t);
        }
    }

    /** mpy-cross bridge, so exec()/eval()/compile() work inside the VM too. */
    private static final net.mpy.vm.Compiler COMPILER = new net.mpy.vm.Compiler() {
        @Override
        public byte[] compile(String source, String filename, String mode) throws Exception {
            return MyMod.compile(source);
        }
    };

    /**
     * Install the host API into {@code globals} AND register every host function
     * in {@link #hosts} under a stable id. The ids matter: host functions cannot
     * be serialized, so a snapshot stores the id and the restore resolves it back
     * to a (freshly created, VM-bound) function. Functions that live inside a
     * module namespace rather than in globals would otherwise have no id at all.
     */
    private void installHostApi(Map<String, Object> globals) {
        // --- debug print -------------------------------------------------
        HostFunction printFn = new HostFunction() {
            @Override
            public Object call(Object[] args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(str(args[i]));
                }
                print(sb.toString());
                return PyObj.NONE;
            }
        };
        globals.put("print", printFn);
        hosts.register("print", printFn);

        // --- component ---------------------------------------------------
        PyModule component = new PyModule("component", "<host>", null);

        reg(component, "list", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String filter = args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
                boolean exact = args.length > 1 && net.mpy.runtime.Ops.isTrue(args[1]);
                PyObj.PyDict out = new PyObj.PyDict();
                for (Map.Entry<String, String> e : machine.components().entrySet()) {
                    String name = e.getValue();
                    if (filter != null) {
                        if (exact ? !name.equals(filter) : !name.contains(filter)) continue;
                    }
                    out.map.put(e.getKey(), name);
                }
                return out;
            }
        });

        reg(component, "type", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String addr = reqStr(args, 0, "type");
                String name = machine.components().get(addr);
                return name == null ? PyObj.NONE : (Object) name;
            }
        });

        reg(component, "methods", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String addr = reqStr(args, 0, "methods");
                PyObj.PyDict out = new PyObj.PyDict();
                for (Map.Entry<String, li.cil.oc.api.machine.Callback> e : methodsOf(addr).entrySet()) {
                    PyObj.PyDict info = new PyObj.PyDict();
                    info.map.put("direct", Boolean.valueOf(e.getValue().direct()));
                    info.map.put("getter", Boolean.valueOf(e.getValue().getter()));
                    info.map.put("setter", Boolean.valueOf(e.getValue().setter()));
                    out.map.put(e.getKey(), info);
                }
                return out;
            }
        });

        reg(component, "doc", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String addr = reqStr(args, 0, "doc");
                String method = reqStr(args, 1, "doc");
                li.cil.oc.api.machine.Callback cb = methodsOf(addr).get(method);
                if (cb == null || cb.doc() == null || cb.doc().isEmpty()) return PyObj.NONE;
                return cb.doc();
            }
        });

        reg(component, "invoke", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                if (args.length < 2) throw net.mpy.runtime.PyException.typeError(
                        "component.invoke(address, method, *args)");
                String addr = reqStr(args, 0, "invoke");
                String method = reqStr(args, 1, "invoke");
                Object[] rest = Arrays.copyOfRange(args, 2, args.length);
                return invokeComponent(addr, method, rest);
            }
        });

        // component.proxy(address) -> an object whose attributes are bound methods
        reg(component, "proxy", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String addr = reqStr(args, 0, "proxy");
                String name = machine.components().get(addr);
                if (name == null) throw new net.mpy.runtime.PyException(
                        "ValueError", "no such component: " + addr);
                return makeProxy(addr, name);
            }
        });

        // OpenOS-style primaries: component.<type> resolves to a proxy of the first
        // attached component of that type (component.gpu, component.filesystem, ...).
        // Wired via the module __getattr__ hook (PEP 562), so it only kicks in for
        // names not already in the module (list/invoke/proxy/... stay themselves)
        // and always reflects the currently attached components.
        reg(component, "__getattr__", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String kind = reqStr(args, 0, "__getattr__");
                for (Map.Entry<String, String> e : machine.components().entrySet()) {
                    if (kind.equals(e.getValue())) {
                        return makeProxy(e.getKey(), e.getValue());
                    }
                }
                throw new net.mpy.runtime.PyException("AttributeError",
                        "no primary '" + kind + "' available");
            }
        });

        globals.put("component", component);

        // --- computer ----------------------------------------------------
        PyModule computer = new PyModule("computer", "<host>", null);

        reg(computer, "address", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                return machine.node().address();
            }
        });
        // The machine's built-in virtual temporary filesystem (a memory-backed
        // "tmpfs" component OC creates per computer, not tied to any item). Its
        // address is how an OS tells it apart from real disks -- OpenOS uses the
        // same call to mount it at /tmp and to keep install off of it.
        reg(computer, "tmpAddress", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String a = machine.tmpAddress();
                return a == null ? PyObj.NONE : a;
            }
        });
        reg(computer, "uptime", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                return Double.valueOf(machine.upTime());
            }
        });
        reg(computer, "tmpAddress", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                // The address of the machine's transient RAM filesystem (/tmp).
                // It looks empty and writable but is tiny and cleared on reboot, so
                // callers like install must exclude it when choosing a target disk.
                return machine.tmpAddress();
            }
        });
        reg(computer, "totalMemory", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                return Double.valueOf(MEMORY_LIMIT_BYTES);
            }
        });
        reg(computer, "freeMemory", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                // Headroom = ceiling minus the current rough estimate of live data.
                // Clamped at 0 so a brief overshoot never reports negative.
                long free = MEMORY_LIMIT_BYTES - (vm == null ? 0 : vm.estimatedMemory());
                return Double.valueOf(free < 0 ? 0 : free);
            }
        });
        reg(computer, "ops", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                // Instructions left in this tick's whole allowance (fresh + banked).
                return Double.valueOf(vm == null ? 0 : vm.opsRemaining());
            }
        });
        reg(computer, "ops_share", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                // Instructions left in the running frame's/coroutine's own slice.
                return Double.valueOf(vm == null ? 0 : vm.opsShareRemaining());
            }
        });
        reg(computer, "beep", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                requireMainThreadFor(machine.node().address(), "beep");
                try {
                    machine.invoke(machine.node().address(), "beep", args);
                } catch (Exception ignored) {
                }
                return PyObj.NONE;
            }
        });
        reg(computer, "shutdown", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                wantShutdown = true;
                wantReboot = args.length > 0 && net.mpy.runtime.Ops.isTrue(args[0]);
                // Stop stepping; runThreaded will report the shutdown to OC.
                vm.requestYield();
                return PyObj.NONE;
            }
        });
        reg(computer, "pushSignal", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                if (args.length == 0) return Boolean.FALSE;
                String name = reqStr(args, 0, "pushSignal");
                Object[] rest = new Object[args.length - 1];
                for (int i = 1; i < args.length; i++) rest[i - 1] = toJava(args[i]);
                return Boolean.valueOf(machine.signal(name, rest));
            }
        });
        // Signals delivered from OC land in this list; Python drains it.
        computer.ns.put("_signals", new PyObj.PyList());

        // Boot address, stored in the EEPROM's data area — same mechanism as Lua's
        // bios (getData/setData). This is what makes `install` stick: it writes the
        // target disk's address here, and on the next boot the bios reads it and
        // boots that disk directly instead of scanning for a LiveCD.
        reg(computer, "getBootAddress", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String eeprom = findComponent("eeprom");
                if (eeprom == null) return PyObj.NONE;
                requireMainThreadFor(eeprom, "getData");
                try {
                    Object[] r = machine.invoke(eeprom, "getData", new Object[0]);
                    if (r == null || r.length == 0 || r[0] == null) return PyObj.NONE;
                    String s = r[0] instanceof byte[]
                            ? new String((byte[]) r[0], "UTF-8")
                            : String.valueOf(r[0]);
                    // An empty data area means "no boot address set". Returning None
                    // (rather than "") keeps the Python check a simple `if addr:`.
                    return s.isEmpty() ? PyObj.NONE : (Object) s;
                } catch (Exception e) {
                    return PyObj.NONE;
                }
            }
        });
        reg(computer, "setBootAddress", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                String eeprom = findComponent("eeprom");
                if (eeprom == null) return Boolean.FALSE;
                String addr = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
                requireMainThreadFor(eeprom, "setData");
                try {
                    machine.invoke(eeprom, "setData", new Object[] { addr.getBytes("UTF-8") });
                    return Boolean.TRUE;
                } catch (Exception e) {
                    return Boolean.FALSE;
                }
            }
        });

        // pullSignal is built in Python (see BOOT_PY) on top of two primitives:
        //
        //   _poll_signal()  -- pop the oldest queued signal as a tuple, or None if
        //                      the queue is empty. Non-blocking.
        //   _yield_tick()   -- suspend the VM until the next server tick, so the
        //                      main thread can run, new OC signals can arrive, and
        //                      we are not burning CPU while waiting.
        //
        // Doing the wait loop in Python keeps the VM's host-call model simple: a
        // host function cannot re-run itself, but a Python loop calling _yield_tick
        // resumes exactly after the yield on the next step() (verified), which is a
        // real tick-yield, not a busy spin.
        reg(computer, "_poll_signal", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                // Resolve the computer module from the VM's globals at call time, not
                // the local captured here: after a snapshot restore, fromSnapshot
                // replaces globals["computer"] with the restored module, and
                // drainSignals() feeds *that* one. Reading the captured local would
                // poll a different, always-empty _signals list -- the machine would
                // sit in pullSignal forever and ignore all input after a load.
                Object mod = vm != null ? vm.globals.get("computer") : computer;
                if (!(mod instanceof PyModule)) mod = computer;
                Object sigs = ((PyModule) mod).ns.get("_signals");
                if (sigs instanceof PyObj.PyList) {
                    PyObj.PyList list = (PyObj.PyList) sigs;
                    if (!list.items.isEmpty()) {
                        return list.items.remove(0);
                    }
                }
                return PyObj.NONE;
            }
        });
        // _yield_tick suspends the VM until the next tick via the VM's yield flag.
        reg(computer, "_yield_tick", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                if (vm != null) vm.requestYield();
                return PyObj.NONE;
            }
        });

        globals.put("computer", computer);

        // The frozen time module is built on host-overridable primitives; wire its
        // clock to the server's, so time.time()/ticks_ms() advance with the game
        // (and stay consistent across saves and pauses), not with the JVM.
        globals.put("__time_ms", new HostFunction() {
            @Override
            public Object call(Object[] args) {
                return Long.valueOf((long) (machine.upTime() * 1000.0));
            }
        });   // snapshot id resolves by globals name (fromGlobals holds a live ref)
    }

    /**
     * Put {@code fn} into {@code module}'s namespace under {@code name} and give it
     * a stable snapshot id ("module.name"), so it survives save/restore.
     */
    private void reg(PyModule module, String name, HostFunction fn) {
        module.ns.put(name, fn);
        hosts.register(module.name + "." + name, fn);
    }

    /**
     * A component proxy: attribute access returns a bound, callable method, so
     * Python can write {@code gpu.set(1, 1, "x")} instead of component.invoke(...).
     *
     * Each bound method gets a deterministic snapshot id ("proxy.<address>.<method>")
     * and is registered on creation. Restoring a snapshot that holds a live proxy
     * resolves those ids through {@link #hosts}, which re-binds them to this VM —
     * see {@link #registerProxyMethods(String)}, called for every attached
     * component when a snapshot is loaded.
     */
    private Object makeProxy(final String address, String name) {
        PyModule proxy = new PyModule(name + "@" + address, "<component>", null);
        proxy.ns.put("address", address);
        proxy.ns.put("type", name);
        for (Map.Entry<String, li.cil.oc.api.machine.Callback> e : methodsOf(address).entrySet()) {
            proxy.ns.put(e.getKey(), boundMethod(address, e.getKey()));
        }
        return proxy;
    }

    /** A callable bound to one component method, registered under a stable id. Its
     *  {@code toString()} shows the method signature and the {@code @Callback(doc=...)}
     *  help text, so evaluating {@code component.filesystem.read} at the REPL prints
     *  the help (like OpenComputers Lua), instead of a bare {@code <function>}.
     *
     *  <p>The help is computed LAZILY, on each toString(), not captured at creation:
     *  a bound method is often rebuilt by the snapshot resolver while the component
     *  network is still offline (chunk load), when the annotation isn't available yet.
     *  Computing eagerly there would bake in a doc-less string that never recovers.
     *  Looking it up on demand means the doc appears as soon as the network is back. */
    private HostFunction boundMethod(final String address, final String method) {
        HostFunction fn = new HostFunction() {
            @Override
            public Object call(Object[] args) {
                return invokeComponent(address, method, args);
            }
            @Override
            public String toString() { return methodHelp(address, method); }
        };
        hosts.register("proxy." + address + "." + method, fn);
        return fn;
    }

    /** Build the REPL help line for a component method from its callback annotation:
     *  "function: <doc>" if a doc is present, else a plain signature. Mirrors how OC
     *  Lua surfaces the @Callback doc when you evaluate a method without calling it. */
    private String methodHelp(String address, String method) {
        String doc = null;
        boolean direct = false;
        try {
            li.cil.oc.api.network.Node node = machine.node().network().node(address);
            if (node instanceof li.cil.oc.api.network.Component) {
                li.cil.oc.api.machine.Callback cb =
                        ((li.cil.oc.api.network.Component) node).annotation(method);
                if (cb != null) {
                    direct = cb.direct();
                    if (cb.doc() != null && !cb.doc().isEmpty()) doc = cb.doc();
                }
            }
        } catch (Throwable ignored) {
        }
        String tag = direct ? " [direct]" : "";
        if (doc != null) return "function" + tag + " -- " + doc;
        // no doc annotation: at least show the method name so it isn't just <function>
        return "function" + tag + ": " + method + "(...)";
    }

    /**
     * Pre-register bound methods for every attached component, so a snapshot that
     * holds proxies created before the save can resolve their host functions on
     * restore (the ids are deterministic, the instances need not be).
     */
    private void registerProxyMethods() {
        for (Map.Entry<String, String> e : machine.components().entrySet()) {
            for (String method : methodsOf(e.getKey()).keySet()) {
                boundMethod(e.getKey(), method);
            }
        }
    }

    private Map<String, li.cil.oc.api.machine.Callback> methodsOf(String address) {
        try {
            li.cil.oc.api.network.Node node = machine.node().network().node(address);
            if (node instanceof li.cil.oc.api.network.Component) {
                Map<String, li.cil.oc.api.machine.Callback> m =
                        machine.methods(((li.cil.oc.api.network.Component) node).host());
                if (m != null) return m;
            }
        } catch (Throwable ignored) {
        }
        return new HashMap<String, li.cil.oc.api.machine.Callback>();
    }

    /**
     * Perform a component call. We are on the main server thread, so this is
     * always safe to do directly — both direct and synchronized callbacks.
     */
    /** True if {@code method} on the component at {@code address} is a direct
     *  callback (safe to run off the main thread). Unknown/missing -> treat as
     *  non-direct (the safe default: route through the main thread). */
    private boolean isDirectCall(String address, String method) {
        String key = address + '\u0000' + method;
        Boolean cached = directCache.get(key);
        if (cached != null) return cached.booleanValue();
        boolean direct = isDirectCallUncached(address, method);
        directCache.put(key, Boolean.valueOf(direct));
        return direct;
    }

    private boolean isDirectCallUncached(String address, String method) {
        try {
            li.cil.oc.api.network.Node node = machine.node();
            if (node == null || node.network() == null) return false;
            li.cil.oc.api.network.Node target = node.network().node(address);
            if (target instanceof li.cil.oc.api.network.Component) {
                li.cil.oc.api.machine.Callback ann =
                        ((li.cil.oc.api.network.Component) target).annotation(method);
                return ann != null && ann.direct();
            }
        } catch (Throwable ignored) {
            // network/component not resolvable (e.g. test harness) -> non-direct
        }
        return false;
    }

    private Object invokeComponent(String address, String method, Object[] pyArgs) {
        // ASYNC CPU: a non-direct call cannot run on the computer thread. In the
        // THREADED phase we park the VM (rewind this instruction, yield to Java) and
        // ask OC for a synchronized call, which re-runs this instruction on the main
        // thread. In the SYNC phase we are already on the main thread, so we run it
        // in place, but only up to the tier's per-window limit; hitting the limit
        // parks AFTER this call returns (no pending call to stash -- see the counter
        // check in the synchronized driver).
        if (async && !isDirectCall(address, method)) {
            if (phase == Phase.THREADED) {
                needSyncCall = true;
                vm.hostPause();                  // rewind + yield + unwind; re-runs on main thread
            }
            // SYNC phase: we are on the main thread, so run it in place and count it.
            syncCallsThisWindow++;
        }
        Object[] jargs = new Object[pyArgs.length];
        for (int i = 0; i < pyArgs.length; i++) jargs[i] = toJava(pyArgs[i]);
        Object pyResult;
        try {
            Object[] result = machine.invoke(address, method, jargs);
            if (result == null || result.length == 0) pyResult = PyObj.NONE;
            else if (result.length == 1) pyResult = toPy(result[0]);
            else {
                PyObj.PyList list = new PyObj.PyList();
                for (Object o : result) list.items.add(toPy(o));
                pyResult = new PyObj.Tuple(list.items.toArray());
            }
        } catch (li.cil.oc.api.machine.LimitReachedException limit) {
            // OpenComputers never shows this to the guest. Its Lua arch turns a
            // LimitReachedException into "zero results", and machine.lua reads that
            // as "retry this call through a synchronized call" -- the script never
            // sees an error, the call just happens a moment later. Mirror that:
            // park, so the call is re-run on the main thread (where the budget is
            // not charged at all) instead of raising into Python.
            if (vm != null) vm.hostPause();
            // Only if we somehow cannot park (no VM executing) does this surface.
            throw new net.mpy.runtime.PyException("RuntimeError", "component call budget exhausted");
        } catch (Exception e) {
            throw componentError(e);
        }
        // SYNC phase: if this call reached the tier's per-window limit, park AFTER
        // returning its result -- the instruction completes normally (result on the
        // stack), then the next instruction re-runs... no: we must let THIS
        // instruction finish. Instead we request a plain yield so the step stops at
        // the next instruction boundary, with this call's result already delivered.
        if (async && phase == Phase.SYNC && syncCallsThisWindow >= syncCallLimit) {
            vm.requestYield();                   // stop the step after this instruction completes
        }
        return pyResult;
    }

    /** Set inside a THREADED-phase non-direct call: tells runThreaded to return a
     *  SynchronizedCall so the main thread re-runs the parked instruction. */
    private boolean needSyncCall = false;

    // ------------------------------------------------------------------ //
    // Signals
    // ------------------------------------------------------------------ //

    /** Address of the first attached component of the given type, or null. */
    private String findComponent(String type) {
        for (Map.Entry<String, String> e : machine.components().entrySet()) {
            if (type.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** Move any queued OC signals into computer._signals as (name, *args) tuples. */
    private void drainSignals() {
        Object sigs = ((PyModule) vm.globals.get("computer")).ns.get("_signals");
        if (!(sigs instanceof PyObj.PyList)) return;
        PyObj.PyList list = (PyObj.PyList) sigs;
        Signal s;
        while ((s = machine.popSignal()) != null) {
            List<Object> parts = new ArrayList<Object>();
            parts.add(s.name());
            // A device came or went: any remembered directness for an address could
            // now describe a component that is no longer there.
            if ("component_added".equals(s.name()) || "component_removed".equals(s.name())) {
                directCache.clear();
            }
            for (Object a : s.args()) parts.add(toPy(a));
            list.items.add(new PyObj.Tuple(parts.toArray()));
            if (list.items.size() > 256) list.items.remove(0);   // bounded queue
        }
    }

    // ------------------------------------------------------------------ //
    // Value conversion: OC/Java <-> MPY VM values
    // ------------------------------------------------------------------ //

    /** MPY value -> Java value for handing to OC. */
    static Object toJava(Object v) {
        if (v == null || v == PyObj.NONE) return null;
        if (v instanceof String || v instanceof Boolean || v instanceof byte[]) return v;
        if (v instanceof Long) return Double.valueOf(((Long) v).doubleValue());   // OC speaks doubles
        if (v instanceof Double) return v;
        if (v instanceof java.math.BigInteger) return Double.valueOf(((java.math.BigInteger) v).doubleValue());
        if (v instanceof PyObj.Bytes) return ((PyObj.Bytes) v).data;
        if (v instanceof PyObj.PyList) {
            Map<Object, Object> m = new HashMap<Object, Object>();
            List<Object> items = ((PyObj.PyList) v).items;
            for (int i = 0; i < items.size(); i++) m.put(Double.valueOf(i + 1), toJava(items.get(i)));
            return m;
        }
        if (v instanceof PyObj.Tuple) {
            Map<Object, Object> m = new HashMap<Object, Object>();
            Object[] items = ((PyObj.Tuple) v).items;
            for (int i = 0; i < items.length; i++) m.put(Double.valueOf(i + 1), toJava(items[i]));
            return m;
        }
        if (v instanceof PyObj.PyDict) {
            Map<Object, Object> m = new HashMap<Object, Object>();
            for (Map.Entry<Object, Object> e : ((PyObj.PyDict) v).map.entrySet()) {
                m.put(toJava(e.getKey()), toJava(e.getValue()));
            }
            return m;
        }
        return v.toString();
    }

    /**
     * Wrap an OC Value (userdata) so Python can use it much like Lua does. Returns
     * a small object exposing:
     *   - every @Callback method of the value (count/getAll/reset/...), callable
     *     via machine.invoke(value, name, args);
     *   - to_list(): drains the value's call() iterator into a Python list, so
     *     `for x in v.to_list()` (or list(v.to_list())) walks all elements. This is
     *     how AE2's ItemStackArrayValue from getItemsInNetwork yields its stacks.
     * A file-handle-like Value never reaches here (it round-trips as an int above).
     */
    private Object wrapValue(final li.cil.oc.api.machine.Value value) {
        String id = "value." + (valueSeq++);
        wrappedValues.put(id, value);
        return buildValueProxy(id, value);
    }

    /** Build the Python proxy (a PyModule) for a wrapped Value with the given id:
     *  its @Callback methods and a to_list() drainer, each registered in the host
     *  registry under "&lt;id&gt;.&lt;name&gt;" so snapshots can serialize them and the
     *  restore resolver can rebuild them against the re-persisted value. */
    private Object buildValueProxy(final String id, final li.cil.oc.api.machine.Value value) {
        PyModule w = new PyModule("value", "<userdata>", null);
        try {
            Map<String, li.cil.oc.api.machine.Callback> ms = machine.methods(value);
            if (ms != null) {
                for (final String name : ms.keySet()) {
                    HostFunction fn = valueMethod(value, name);
                    if (hosts != null) hosts.register(id + "." + name, fn);
                    w.ns.put(name, fn);
                }
            }
        } catch (Throwable ignored) {
            // methods() unavailable -> just offer to_list()
        }
        HostFunction toList = valueToList(value);
        if (hosts != null) hosts.register(id + ".to_list", toList);
        w.ns.put("to_list", toList);
        // Lazy `for x in wrapper`: each loop resets the value's iterator (if it
        // supports reset) and then pulls one element per step, converting a single
        // ItemStack to Python at a time instead of materialising the whole network.
        w.iterFactory = new java.util.function.Supplier<java.util.function.Supplier<Object>>() {
            @Override
            public java.util.function.Supplier<Object> get() {
                requireMainThreadForValue(value, "reset");
                try { machine.invoke(value, "reset", new Object[0]); } catch (Throwable ignored) {}
                final li.cil.oc.api.machine.Arguments empty = emptyArgs();
                return new java.util.function.Supplier<Object>() {
                    @Override
                    public Object get() {
                        Object r;
                        try {
                            r = value.call(machine, empty);
                        } catch (Throwable t) {
                            return net.mpy.runtime.PyObj.STOP_ITERATION;
                        }
                        Object elem = unwrapSingle(r);
                        return elem == null ? net.mpy.runtime.PyObj.STOP_ITERATION : toPy(elem);
                    }
                };
            }
        };
        return w;
    }

    /** A host function calling one @Callback method on a wrapped Value. */
    private HostFunction valueMethod(final li.cil.oc.api.machine.Value value, final String name) {
        return new HostFunction() {
            @Override
            public Object call(Object[] args) {
                requireMainThreadForValue(value, name);
                Object[] jargs = new Object[args.length];
                for (int i = 0; i < args.length; i++) jargs[i] = toJava(args[i]);
                try {
                    Object[] r = machine.invoke(value, name, jargs);
                    if (r == null || r.length == 0) return PyObj.NONE;
                    if (r.length == 1) return toPy(r[0]);
                    PyObj.PyList l = new PyObj.PyList();
                    for (Object o : r) l.items.add(toPy(o));
                    return new PyObj.Tuple(l.items.toArray());
                } catch (Exception e) {
                    throw componentError(e);
                }
            }
        };
    }

    /** A host function draining a wrapped Value's call() iterator into a Python list
     *  (the Lua `for x in v` protocol: each call yields the next element, null at end). */
    private HostFunction valueToList(final li.cil.oc.api.machine.Value value) {
        return new HostFunction() {
            @Override
            public Object call(Object[] args) {
                // Start from the beginning (like `for x in v` does), so to_list()
                // returns the whole collection even if the value was iterated before.
                requireMainThreadForValue(value, "reset");
                try { machine.invoke(value, "reset", new Object[0]); } catch (Throwable ignored) {}
                PyObj.PyList out = new PyObj.PyList();
                li.cil.oc.api.machine.Arguments empty = emptyArgs();
                int guard = 0;
                while (guard++ < 1_000_000) {
                    Object r;
                    try {
                        r = value.call(machine, empty);
                    } catch (Throwable t) {
                        break;   // not callable / iteration unsupported
                    }
                    Object elem = unwrapSingle(r);
                    if (elem == null) break;   // exhausted
                    out.items.add(toPy(elem));
                }
                return out;
            }
        };
    }

    /** Rebuild a wrapped-Value method host function by its registry id
     *  ("value.N.method"), for snapshot restore. Looks up the value re-persisted by
     *  {@link #load} in {@link #wrappedValues}; if it isn't there (e.g. an older save),
     *  returns a function that raises a clear error. Returns null for foreign ids. */
    private HostFunction resolveValueMethod(final String id) {
        if (!id.startsWith("value.")) return null;
        int dot = id.lastIndexOf('.');
        if (dot < 0) return null;
        String vid = id.substring(0, dot);
        String method = id.substring(dot + 1);
        li.cil.oc.api.machine.Value value = wrappedValues.get(vid);
        if (value == null) {
            return new HostFunction() {
                @Override
                public Object call(Object[] args) {
                    throw new net.mpy.runtime.PyException("RuntimeError",
                            "this value could not be restored; fetch it again from the component");
                }
            };
        }
        return "to_list".equals(method) ? valueToList(value) : valueMethod(value, method);
    }

    /** call()/apply() return Object[] (or a bare value); pull out the single element,
     *  or null when the array is empty/null (iteration end). */
    private static Object unwrapSingle(Object r) {
        if (r == null) return null;
        if (r instanceof Object[]) {
            Object[] a = (Object[]) r;
            return a.length == 0 ? null : a[0];
        }
        return r;
    }

    /** An empty OC Arguments, enough to drive a Value's call() iterator. */
    private static li.cil.oc.api.machine.Arguments emptyArgs() {
        return new li.cil.oc.api.machine.Arguments() {
            public java.util.Iterator<Object> iterator() { return java.util.Collections.emptyList().iterator(); }
            public int count() { return 0; }
            public Object checkAny(int index) { return null; }
            public boolean checkBoolean(int index) { return false; }
            public int checkInteger(int index) { return 0; }
            public long checkLong(int index) { return 0L; }
            public double checkDouble(int index) { return 0; }
            public String checkString(int index) { return null; }
            public byte[] checkByteArray(int index) { return null; }
            public java.util.Map checkTable(int index) { return null; }
            public net.minecraft.item.ItemStack checkItemStack(int index) { return null; }
            public Object optAny(int index, Object def) { return def; }
            public boolean optBoolean(int index, boolean def) { return def; }
            public int optInteger(int index, int def) { return def; }
            public long optLong(int index, long def) { return def; }
            public double optDouble(int index, double def) { return def; }
            public String optString(int index, String def) { return def; }
            public byte[] optByteArray(int index, byte[] def) { return def; }
            public java.util.Map optTable(int index, java.util.Map def) { return def; }
            public net.minecraft.item.ItemStack optItemStack(int index, net.minecraft.item.ItemStack def) { return def; }
            public boolean isBoolean(int index) { return false; }
            public boolean isInteger(int index) { return false; }
            public boolean isLong(int index) { return false; }
            public boolean isDouble(int index) { return false; }
            public boolean isString(int index) { return false; }
            public boolean isByteArray(int index) { return false; }
            public boolean isTable(int index) { return false; }
            public boolean isItemStack(int index) { return false; }
            public Object[] toArray() { return new Object[0]; }
        };
    }

    /** Java/OC value -> MPY value. */
    Object toPy(Object v) {
        if (v == null) return PyObj.NONE;
        if (v instanceof String || v instanceof Boolean) return v;
        if (v instanceof byte[]) return new PyObj.Bytes((byte[]) v);
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
                return Long.valueOf((long) d);      // OC hands out whole numbers as doubles
            }
            return v;
        }
        if (v instanceof Float) return Double.valueOf(((Float) v).doubleValue());
        if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
        if (v instanceof Map) {
            PyObj.PyDict d = new PyObj.PyDict();
            for (Object o : ((Map<?, ?>) v).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                d.map.put(toPy(e.getKey()), toPy(e.getValue()));
            }
            return d;
        }
        if (v instanceof Object[]) {
            PyObj.PyList l = new PyObj.PyList();
            for (Object o : (Object[]) v) l.items.add(toPy(o));
            return l;
        }
        if (v instanceof Iterable) {
            // Some components (and other mods) hand back java.util.List instead of
            // an array; without this they would fall through to toString().
            PyObj.PyList l = new PyObj.PyList();
            for (Object o : (Iterable<?>) v) l.items.add(toPy(o));
            return l;
        }
        // OC Value objects (userdata) such as a file handle. checkHandle() on the
        // OC side accepts either an integer or a table with a "handle" field, and a
        // HandleValue's toString() is its integer handle. So a numeric-looking Value
        // round-trips correctly as a Python int -- open() -> read()/close() works
        // without fs.py ever seeing the opaque wrapper.
        String s = v.toString();
        if (looksLikeInt(s)) {
            try {
                return Long.valueOf(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }
        // Other OC Value userdata (e.g. AE2's ItemStackArrayValue from
        // getItemsInNetwork): expose it to Python as an object you can iterate,
        // index, len(), and whose @Callback methods (count/getAll/reset/...) you can
        // call -- mirroring what Lua can do with the same userdata.
        if (v instanceof li.cil.oc.api.machine.Value) {
            return wrapValue((li.cil.oc.api.machine.Value) v);
        }
        // Scala's Unit, anything else: show as text for now.
        return s;
    }

    private static boolean looksLikeInt(String s) {
        if (s == null || s.isEmpty()) return false;
        int i = (s.charAt(0) == '-') ? 1 : 0;
        if (i == s.length()) return false;
        for (; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ //
    // helpers
    // ------------------------------------------------------------------ //

    private static String reqStr(Object[] args, int i, String fn) {
        if (args.length <= i || !(args[i] instanceof String)) {
            throw net.mpy.runtime.PyException.typeError(
                    fn + "(): argument " + (i + 1) + " must be a string");
        }
        return (String) args[i];
    }

    private static String str(Object v) {
        if (v instanceof String) return (String) v;
        return PyObj.repr(v);
    }

    private void print(String line) {
        output.add(line);
        if (output.size() > 256) output.remove(0);
        MyMod.LOG.info("[mpy] " + line);
    }

    private void fail(String message) {
        print("[mpy] " + message);
        pendingError = message;
        ended = true;
    }

    private void crash(Throwable t) {
        MyMod.LOG.warn("mpy: VM crashed", t);
        // Surface the Python-level traceback (file/line/function frames), not just
        // the exception message -- that is what tells you where in the .py it blew
        // up. The message alone ("int('<uuid>')") does not say which line called it.
        if (t instanceof net.mpy.runtime.PyException) {
            String tb = ((net.mpy.runtime.PyException) t).pyTraceback();
            if (tb != null && !tb.isEmpty()) {
                for (String line : tb.split("\n")) {
                    print("[mpy] " + line);
                }
                fail(tb);
                return;
            }
        }
        String msg = t.getMessage();
        fail(msg != null ? msg : t.toString());
    }

    // ---- NBT payload compression (snapshots get large) ----

    private static byte[] compress(byte[] in) {
        Deflater def = new Deflater();
        def.setInput(in);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(buf);
            out.write(buf, 0, n);
        }
        def.end();
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] in) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0) break;
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }
}
