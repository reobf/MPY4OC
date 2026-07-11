package net.mpy.vm;

import net.mpy.bytecode.Opcodes;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.Methods;
import net.mpy.runtime.PyExc;
import net.mpy.runtime.Ops;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phases 2-3 - the step VM with a frame stack.
 *
 * {@link #step} executes exactly one bytecode against the current frame. Calls
 * push a new frame ({@code current.caller} chains back), returns pop one and
 * deliver the result into the caller's stack, and the root frame returning ends
 * the run. Because every frame's context lives in fields (not the Java call
 * stack), execution can be suspended and resumed between instructions, across
 * calls - the basis for generators and cooperative scheduling. Dispatch and the
 * call/return frame handoff mirror {@code mp_execute_bytecode} (stackless path).
 *
 * Convention: Java {@code null} is MicroPython's MP_OBJ_NULL (unset local /
 * LOAD_NULL); Python {@code None} is {@link PyObj#NONE}.
 *
 * Not yet implemented (later phases), each raising a clear NotImplementedError:
 * keyword / var-args in calls and CALL_METHOD (partial here), attribute access
 * (5), exceptions and the SETUP / END_FINALLY / RAISE family (6), yield and
 * closures / MAKE_CLOSURE (7).
 */
public final class Vm {

    private static final int NSLOTS = 4; // MP_OBJ_ITER_BUF_NSLOTS

    // scope flags (py/runtime0.h)
    private static final int SCOPE_GENERATOR = 0x01, SCOPE_VARKEYWORDS = 0x02,
            SCOPE_VARARGS = 0x04, SCOPE_DEFKWARGS = 0x08;

    public enum Status { RETURNED, SUSPENDED }

    public static final class Result {
        public final Status status;
        public final Object value;
        public final int steps;
        public Result(Status status, Object value, int steps) {
            this.status = status; this.value = value; this.steps = steps;
        }
    }

    public MpyModule module;   // replaced by the embedded main module on restore
    public final Map<String, Object> globals;
    /** Keys present in globals after builtins/primitives are installed, i.e. the
     *  names that globals()/locals() must hide (MicroPython keeps builtins in a
     *  separate namespace not exposed by globals()). */
    private java.util.Set<String> builtinKeys;
    /** Host-supplied Python->.mpy compiler enabling compile()/exec()/eval();
     *  null means those builtins raise NotImplementedError. */
    private Compiler compiler;
    /** The built-in cooperative event loop's task list (uasyncio). Coroutines
     *  registered here are driven by {@link #stepAsync(int[])}. This is the "hole"
     *  through which uasyncio hooks its scheduler into the VM. */
    private final java.util.List<AsyncTask> asyncTasks = new java.util.ArrayList<>();
    /** When non-null, a coroutine that just finished/raised delivers its outcome
     *  here instead of raising StopIteration into a resumer (task-driver mode). */
    private AsyncTask drivingTask;
    /** Set by deliverYield when a driven task yields cooperatively (top-level yield),
     *  so runLoop/driveTask can end the batch without finishing the whole VM. */
    private boolean taskYielded;
    /** Side table: globals map identity -> its builtin key set. Weak + identity so
     *  it neither leaks memory nor ends up serialized inside globals. */
    private static final java.util.Map<Map<String, Object>, java.util.Set<String>>
        BUILTIN_KEYS_BY_GLOBALS = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    /** Reserved global that eval() assigns its expression value to, then reads back. */
    static final String EVAL_RESULT_NAME = "__mpyj_eval_result__";

    private Frame current;
    private boolean finished;
    /** True once the root (main) frame has returned. Distinct from {@link #finished}:
     *  when {@link #daemonFinishOnExit} is on and async tasks remain, the main frame
     *  can be done while the VM is not yet finished (it keeps draining tasks). */
    private boolean mainReturned;
    /** When jasyncio.run(coro) blocks the main frame on a task, this is that task.
     *  While non-null, step() drives only async; once the task settles, its result
     *  is delivered to the main frame's TOS (or its exception raised) and this clears. */
    private AsyncTask mainWaitingTask;
    /** When on, the VM does not report finished at main-frame exit while async tasks
     *  remain — it keeps running them to completion (all step budget going to async).
     *  Off by default: at main exit the VM finishes and pending tasks are abandoned.
     *  Toggled from mpy code via the finishDaemonOnExit() builtin. */
    private boolean daemonFinishOnExit;
    private Object returnValue;
    private boolean yielding;   // set by the yield host function to abort a batch early
    private int raiseIp;        // start ip of the executing instruction (C: code_state->ip)
    private Limits limits = new Limits();
    private int memCheckCountdown;
    /** The most recently handled exception (set when an except block is entered);
     *  backs the frozen traceback module's format_exc(). */
    private PyExc.Instance handledException;

    // ---- imports & virtual filesystem (Phase 10/11) ----
    private ModuleFinder moduleFinder;                       // the virtual filesystem
    final java.util.List<String> importRoots = new ArrayList<>(java.util.List.of("")); // "" always
    final Map<String, PyModule> loadedModules = new HashMap<>();          // dotted name -> module
    private final java.util.IdentityHashMap<MpyModule, PyModule> byCode = new java.util.IdentityHashMap<>();
    String mainRoot = "";           // package identity of the main module
    String mainDotted = "__main__"; // (path-derived when started via startPath)
    // REPL cells / retired mains, kept for snapshot embedding. Identity-deduped
    // (a module is added at most once); labels are display-only, so callers may
    // reuse a label freely without losing an earlier cell's code.
    private final java.util.List<Map.Entry<String, MpyModule>> cellModules = new ArrayList<>();

    private void embedCell(String label, MpyModule m) {
        for (Map.Entry<String, MpyModule> e : cellModules) if (e.getValue() == m) return;
        cellModules.add(Map.entry(label == null ? "" : label, m));   // labels are display-only
    }

    public Vm(MpyModule module) { this(module, new HashMap<>()); }

    /**
     * Create a VM. Both parameters may be null: with no module, drive execution
     * via {@link #execCell}/{@link #startCell}/{@link #startPath}; with no
     * globals, a fresh map is created. Either way the VM is batteries-included:
     * builtins, the frozen-stdlib primitives, {@code __name__} and
     * {@code yieldJava} are filled in - but only into EMPTY slots, so anything
     * the host pre-put into its own globals map wins over the defaults.
     */
    public Vm(MpyModule module, Map<String, Object> globals) {
        this.module = module;
        this.globals = globals != null ? globals : new HashMap<>();
        limits.apply();
        memCheckCountdown = limits.memoryCheckInterval;
        Builtins.install(this.globals);
        StdLib.installPrimitives(this.globals);
        this.globals.putIfAbsent("__name__", "__main__");
        this.globals.putIfAbsent("yieldJava", yieldHost());
        this.globals.putIfAbsent("__tb_current", (HostFunction) a ->
                handledException != null ? handledException : PyObj.NONE);
        this.globals.putIfAbsent("__tb_format", (HostFunction) a -> {
            if (!(a[0] instanceof PyExc.Instance)) throw PyException.typeError("expected an exception instance");
            return PyExc.formatTraceback((PyExc.Instance) a[0]);
        });
        // remember which names are builtins so globals()/locals() can hide them
        // (__name__ and __file__ are user-visible module attributes, so keep them).
        // Kept in a side table keyed by the globals map's identity so a reused
        // globals map (e.g. the conformance harness constructing several VMs over
        // one map) captures it only once, before any user names are added — and so
        // the set itself never ends up inside globals (which would break snapshots).
        java.util.Set<String> prior = BUILTIN_KEYS_BY_GLOBALS.get(this.globals);
        if (prior != null) {
            this.builtinKeys = prior;
        } else {
            this.builtinKeys = new java.util.HashSet<>(this.globals.keySet());
            this.builtinKeys.remove("__name__");
            BUILTIN_KEYS_BY_GLOBALS.put(this.globals, this.builtinKeys);
        }
    }

    /** A live view of the module globals with builtin names hidden, matching what
     *  MicroPython's globals()/locals() expose. Writes propagate to real globals. */
    private java.util.Map<Object, Object> userGlobalsView() {
        return new FilteredGlobals(globals, builtinKeys != null ? builtinKeys : new java.util.HashSet<>());
    }

    private static long asLong(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof java.math.BigInteger) return ((java.math.BigInteger) o).longValue();
        if (o instanceof Double) return (long) (double) (Double) o;
        throw PyException.typeError("expected an integer");
    }
    private static String asStr(Object[] args, int i, String fn) {
        if (i >= args.length || !(args[i] instanceof String))
            throw PyException.typeError(fn + "() argument " + (i + 1) + " must be str");
        return (String) args[i];
    }

    /** Compile Python source to a CompiledCode via the host compiler. The loaded
     *  module is embedded immediately so a compiled code object held across a
     *  snapshot (before or without exec) serializes by module reference. */
    private CompiledCode compileSource(String source, String filename, String mode) {
        byte[] mpy;
        try {
            mpy = compiler.compile(source, filename, mode);
        } catch (PyException pe) {
            throw pe;
        } catch (Exception e) {
            throw new PyException("SyntaxError", e.getMessage() == null ? "compile failed" : e.getMessage());
        }
        MpyModule m = MpyLoader.load(mpy, new net.mpy.qstr.QstrPool());
        embedCell("<compile:" + cellModules.size() + ">", m);
        return new CompiledCode(m, mpy, source);
    }

    /** A dict used as an exec/eval namespace: its String keys back a live globals
     *  map so name stores/loads in the exec'd code read and write the dict. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> dictAsGlobals(PyObj.PyDict d) {
        // adapt the Object-keyed dict map to a String-keyed view (keys are strs)
        return new DictGlobals(d);
    }

    /** Run compiled code against a namespace (null = current globals). The exec'd
     *  module's root frame is pushed onto the frame stack and driven by the main
     *  step loop, so yieldJava() inside it suspends to the host and mid-run
     *  snapshots capture it. The module is embedded so it rides snapshots. On
     *  return, doReturn delivers the result (eval: the expression value; exec:
     *  None) to the caller via the isExecRoot path. */
    private void runCompiled(CompiledCode code, Map<String, Object> ns, boolean isEval) {
        embedCell("<exec:" + cellModules.size() + ">", code.module);
        checkDepth();
        Frame top = new Frame(code.module.root);
        top.execGlobals = ns;            // null -> plain globals
        top.isExecRoot = true;
        top.isEvalRoot = isEval;         // eval reads back EVAL_RESULT_NAME on return
        top.caller = current;
        current = top;
    }

    /** When true (the default), the frozen stdlib (math/json/random/heapq/time/
     *  re/struct) is the VFS's built-in first layer: imports check it before the
     *  {@link ModuleFinder}. Disable to take full control of module resolution. */
    public void setStdLibEnabled(boolean enabled) { this.stdLibEnabled = enabled; }

    /** Install the host's Python->.mpy compiler, enabling compile()/exec()/eval(). */
    public void setCompiler(Compiler c) { this.compiler = c; }

    // ==== built-in cooperative event loop (uasyncio) =========================

    /** Register a coroutine as a task on the event loop. Returns the task handle. */
    public AsyncTask registerTask(PyGen coro) {
        AsyncTask t = new AsyncTask(coro);
        asyncTasks.add(t);
        return t;
    }

    /** True while any task remains unfinished. */
    public boolean hasPendingTasks() {
        for (AsyncTask t : asyncTasks) if (!t.done) return true;
        return false;
    }

    /**
     * Advance the event loop by {@code ops[0]} bytecode instructions, split fairly
     * across all runnable tasks. Scheduling policy:
     * <ul>
     *   <li>The op budget is divided evenly among the currently-runnable tasks.
     *   <li>A task that <em>yields</em> (ordinary suspend) keeps its seat and is
     *       eligible again on the next round.
     *   <li>A task that goes to the host via {@code yieldJava()} is blacklisted for
     *       the rest of this {@code stepAsync} call (removed from further rounds
     *       here) — the host is expected to service it before the next call.
     *   <li>A finished task is removed (its result/exception recorded).
     *   <li>If budget remains after a full round (tasks finished early or yielded
     *       without consuming their slice), the leftover is re-divided among the
     *       still-runnable, non-blacklisted tasks and another round runs. This
     *       repeats until the budget is exhausted or no runnable task remains.
     * </ul>
     * {@code ops[0]} is updated in place to the number of instructions left unused.
     *
     * <p>Internal: async is driven automatically by {@link #step(int[])}; this is no
     * longer a public entry point.
     */
    /**
     * Explicitly drive the async event loop for up to {@code ops[0]} instructions,
     * independent of the main frame. This is the entry point for a host that manages
     * async directly (e.g. after registering tasks with no main program running, or
     * to service tasks the main frame spawned). Normal execution does not need it —
     * {@link #step(int[])} already gives async a third of each budget automatically.
     * {@code ops[0]} is updated in place to the number of instructions left unused.
     */
    public void drainAsync(int[] ops) { driveAsync(ops); }

    private void driveAsync(int[] ops) {
        // clear per-call blacklist
        for (AsyncTask t : asyncTasks) t.blacklistedThisRound = false;
        int budget = ops[0];

        // advance sleep timers by the real time elapsed since each sleeping task's
        // clock base; wake those whose remaining time has run out. Time spent while
        // a snapshot was at rest is NOT counted, because restore reset sleepClockBase
        // to the restore instant.
        long now = System.currentTimeMillis();
        for (AsyncTask t : asyncTasks) {
            if (t.done || t.sleepRemainingMs <= 0) continue;
            long elapsed = now - t.sleepClockBase;
            if (elapsed < 0) elapsed = 0;
            if (elapsed >= t.sleepRemainingMs) {
                t.sleepRemainingMs = 0;          // slept long enough — runnable again
            } else {
                t.sleepRemainingMs -= elapsed;   // still sleeping; carry the remainder
                t.sleepClockBase = now;
            }
        }

        while (budget > 0) {
            // runnable = not done, not blacklisted this call, not currently sleeping
            java.util.List<AsyncTask> runnable = new java.util.ArrayList<>();
            for (AsyncTask t : asyncTasks)
                if (!t.done && !t.blacklistedThisRound && t.sleepRemainingMs <= 0) runnable.add(t);
            if (runnable.isEmpty()) break;

            int n = runnable.size();
            int slice = budget / n;
            if (slice <= 0) slice = 1;          // ensure forward progress with tiny budgets

            int consumedThisRound = 0;
            for (AsyncTask t : runnable) {
                if (budget <= 0) break;
                int give = Math.min(slice, budget);
                int used = driveTask(t, give);   // returns ops actually consumed
                budget -= used;
                consumedThisRound += used;
            }
            if (consumedThisRound == 0) break;   // no progress possible; avoid spin
        }
        // purge finished tasks from the live list
        asyncTasks.removeIf(t -> t.done);
        ops[0] = Math.max(0, budget);
    }

    /**
     * Run one task's coroutine for up to {@code give} ops. Returns the number of
     * ops actually consumed. Sets {@code t.done} (with result/error) on completion,
     * or {@code t.blacklistedThisRound} if it went to the host via yieldJava().
     */
    private int driveTask(AsyncTask t, int give) {
        if (t.done || t.coro.done) { t.done = true; return 0; }
        // save the interpreter's top-level state; the scheduler runs "outside" it
        Frame savedCurrent = current;
        boolean savedFinished = finished;
        boolean savedYielding = yielding;
        Object savedReturn = returnValue;
        AsyncTask savedDriving = drivingTask;

        drivingTask = t;
        finished = false;
        yielding = false;

        // Two resume paths:
        //  - A fresh coroutine, or one suspended AT A YIELD point, resumes via
        //    resumeGen (which replaces the yielded value at TOS with the sent value).
        //  - A coroutine suspended by BUDGET EXHAUSTION mid-instruction-sequence is
        //    not at a yield: its stack holds live operands, so we must simply make
        //    its frame current and continue — using resumeGen here would clobber a
        //    real operand via setTop and corrupt the stack.
        boolean freshOrYielded = !t.coro.started || t.atYieldPoint;
        int leftover = give;
        try {
            if (freshOrYielded) {
                resumeGen(t.coro, PyGen.MODE_CALL, 0, PyObj.NONE);
            } else {
                // budget-suspended mid-call-chain: resume from the DEEPEST active frame
                // (where execution really stopped), whose caller links reach back up to
                // the coroutine's top frame. Using t.coro.frame here would abandon any
                // sub-call/sub-coroutine progress and skip past the delegating opcode.
                Frame resumeAt = t.suspendedFrame != null ? t.suspendedFrame : t.coro.frame;
                t.coro.resumeMode = PyGen.MODE_CALL;   // completion delivers to this driver
                current = resumeAt;
            }
            leftover = runLoop(give);
        } catch (PyException pe) {
            t.done = true;
            t.error = PyExc.from(pe);
            restoreTop(savedCurrent, savedFinished, savedYielding, savedReturn, savedDriving);
            return give;
        }

        int consumed = give - leftover;
        boolean wentToHost = yielding;         // yieldJava() aborted the batch
        boolean coroDone = t.coro.done;
        Frame deepestAtStop = current;         // where execution actually stopped

        restoreTop(savedCurrent, savedFinished, savedYielding, savedReturn, savedDriving);

        if (coroDone) {
            t.done = true;                     // result captured by genFinished hook
            t.suspendedFrame = null;
        } else if (wentToHost) {
            t.blacklistedThisRound = true;     // host services it before next round
            t.atYieldPoint = false;            // yieldJava aborts mid-sequence: plain continue
            t.suspendedFrame = deepestAtStop;  // resume from the exact stop frame
        } else if (taskYielded) {
            // suspended at a cooperative yield: deliverYield already collapsed the
            // delegation chain (each frame detached), so resume via resumeGen on the
            // coro's top frame.
            t.atYieldPoint = true;
            t.suspendedFrame = null;
            t.coro.frame.caller = null;
            // a sleep_ms() request: begin the real-time timer for this task
            if (t.lastYield instanceof SleepRequest) {
                long ms = ((SleepRequest) t.lastYield).ms;
                if (ms > 0) {
                    t.sleepRemainingMs = ms;
                    t.sleepClockBase = System.currentTimeMillis();
                }
            }
        } else {
            // budget exhausted mid-instruction/mid-call-chain: the frame chain is
            // intact (caller links live); resume from the deepest frame next time.
            t.atYieldPoint = false;
            t.suspendedFrame = deepestAtStop;
        }
        return Math.max(1, consumed);
    }

    private void restoreTop(Frame c, boolean fin, boolean y, Object ret, AsyncTask dt) {
        current = c; finished = fin; yielding = y; returnValue = ret; drivingTask = dt;
    }

    private boolean stdLibEnabled = true;

    /** The sandbox limits for this VM (see {@link Limits}); replace to reconfigure. */
    public Limits limits() { return limits; }

    public void setLimits(Limits limits) {
        this.limits = limits;
        limits.apply();
        memCheckCountdown = limits.memoryCheckInterval;
    }

    /** Install the virtual filesystem: a hook from extension-less paths to .mpy
     *  bytes (a plain {@code Map<String,byte[]>} works: {@code setModuleFinder(fs::get)}).
     *  Snapshots embed every module actually loaded (plus cells and main), so a
     *  restore resumes without the finder - but imports of modules never loaded
     *  before the snapshot need it re-provided (else ImportError). */
    public void setModuleFinder(ModuleFinder finder) { this.moduleFinder = finder; }

    /** Add an import root: absolute imports search each root in order ("" is
     *  always present). {@link #startPath} auto-adds the entry's root. */
    public void addImportRoot(String dir) {
        if (!importRoots.contains(dir)) importRoots.add(dir);
    }

    /**
     * Execute a REPL cell: run these .mpy bytes' top level in the VM's SHARED
     * globals, so successive cells see each other's assignments, functions and
     * classes (like lines typed into a REPL). The cell's code is registered for
     * snapshot embedding, so cell-defined functions survive save/restore.
     * Returns the cell's completion (imports inside cells work normally).
     */
    public void execCell(String label, byte[] mpyBytes) {
        startCell(label, mpyBytes);
        while (!finished) runLoop(1 << 16);
        // note: synchronous - blocks until the cell completes, and yieldJava()
        // inside the cell does NOT suspend to the host. Use startCell for that.
    }

    /**
     * The step-compatible sibling of {@link #execCell}: registers and ARMS the
     * cell (like {@link #start}) but executes nothing - drive it with
     * {@link #step(int[])} / {@link #resume(int)}, so per-tick budgets apply,
     * {@code yieldJava()} inside the cell suspends to the host, and snapshots
     * can be taken mid-cell. Same shared-globals scoping as execCell.
     */
    public Frame startCell(String label, byte[] mpyBytes) {
        MpyModule m = MpyLoader.load(mpyBytes, new QstrPool());
        // no module namespace: frames from this code scope straight into globals
        embedCell(label, m);
        if (this.module == null) this.module = m;   // the first cell doubles as main
        Frame top = new Frame(m.root);
        start(top);
        return top;
    }

    /** Read the VFS: the built-in frozen stdlib first (unless disabled), then
     *  the host's finder. Extension-less path. */
    byte[] vfsGet(String path) {
        if (stdLibEnabled) {
            byte[] b = StdLib.finder().find(path);
            if (b != null) return b;
        }
        return moduleFinder == null ? null : moduleFinder.find(path);
    }

    /**
     * Load and start the module at a VFS path (extension-less), e.g.
     * {@code vm.startPath("<v>/game/main")}. The longest registered import root
     * prefixing the path determines the module's package identity (enabling
     * relative imports from main); with no matching root the path's parent
     * directory becomes one automatically.
     */
    public Frame startPath(String path) {
        byte[] bytes = vfsGet(path);
        if (bytes == null) throw new IllegalArgumentException("no module in the VFS at '" + path + "'");
        String root = null;
        for (String r : importRoots) {
            if (!r.isEmpty() && path.startsWith(r + "/") && (root == null || r.length() > root.length())) root = r;
        }
        if (root == null) {
            int slash = path.lastIndexOf('/');
            root = slash < 0 ? "" : path.substring(0, slash);
            addImportRoot(root);
        }
        String rest = root.isEmpty() ? path : path.substring(root.length() + 1);
        this.mainRoot = root;
        this.mainDotted = rest.replace('/', '.');
        // retire the previous main into the snapshot-embedding set: functions it
        // defined may still live in globals, and their code must stay embeddable
        if (this.module != null) {
            embedCell("<main:" + cellModules.size() + ">", this.module);
        }
        this.module = MpyLoader.load(bytes, new QstrPool());
        Frame top = new Frame(this.module.root);
        start(top);
        return top;
    }

    /** The dotted-name context (root, dotted) of the module a frame belongs to. */
    private String[] importContextOf(Frame f) {
        if (f.code.module == this.module) return new String[]{mainRoot, mainDotted};
        PyModule pm = byCode.get(f.code.module);
        if (pm != null) return new String[]{pm.root, pm.name};
        return new String[]{"", "__main__"};   // detached code: behave like top-level
    }

    /** For an absolute import, pick the first root where the leaf resolves as a
     *  file/package; failing that, the first root where any chain prefix does;
     *  failing that, the first root (the leaf error surfaces naturally). */
    private String pickRoot(String[] tparts) {
        String full = String.join(".", tparts);
        for (String r : importRoots) if (resolveDotted(r, full) != null) return r;
        for (String r : importRoots) {
            for (int i = 1; i < tparts.length; i++) {
                String prefix = String.join(".", java.util.Arrays.copyOfRange(tparts, 0, i));
                if (resolveDotted(r, prefix) != null) return r;
            }
        }
        return importRoots.get(0);
    }

    /** Resolve a dotted name to VFS bytes under a root: module file, else package
     *  __init__. Returns {bytes, pathKind} with kind "m" or "p", or null. */
    private Object[] resolveDotted(String root, String dotted) {
        String rel = dotted.replace('.', '/');
        String base = root.isEmpty() ? rel : root + "/" + rel;
        byte[] b = vfsGet(base);
        if (b != null) return new Object[]{b, "m"};
        b = vfsGet(base + "/__init__");
        if (b != null) return new Object[]{b, "p"};
        return null;
    }

    // ---- drive --------------------------------------------------------------

    /** Begin executing {@code root} as the top-level frame. */
    public void start(Frame root) {
        current = root;
        finished = false;
        mainReturned = false;
        mainWaitingTask = null;
        returnValue = null;
    }

    /** Resume the current frame for up to {@code budget} instructions. */
    public Result resume(int budget) {
        int left = runLoop(budget);
        return new Result(finished ? Status.RETURNED : Status.SUSPENDED, returnValue, budget - left);
    }

    /** Start {@code root} and run for up to {@code budget} instructions. */
    public Result run(Frame root, int budget) {
        start(root);
        return resume(budget);
    }

    /** Start {@code root} and run to completion. */
    public Object runToCompletion(Frame root) {
        start(root);
        while (!finished) runLoop(1 << 16);
        // daemon-finish mode: after the main frame returns, keep draining async tasks.
        // isFinished() reflects the daemon rule; drive async until it reports done.
        while (!isFinished()) {
            int[] a = { 1 << 16 };
            driveAsync(a);
        }
        return returnValue;
    }

    /**
     * Call a Python function from Java: bind {@code args} (applying defaults) into
     * a fresh frame and run it to completion. This is how a top-level caller (and,
     * in Phase 4, a host API) enters bytecode; recursive/other calls made inside
     * resolve through {@link #globals}.
     */
    public Object invoke(PyFunction fn, Object... args) {
        return runToCompletion(prepareFrame(fn, args));
    }

    /**
     * Build (but don't run) a frame for calling {@code fn} with {@code args} bound
     * into its locals - pass it to {@link #start} to drive the call yourself with
     * {@link #step(int[])} / {@link #resume}, e.g. to honour {@code yieldJava()}.
     */
    public Frame frameForCall(PyFunction fn, Object... args) {
        return prepareFrame(fn, args);
    }

    // ---- snapshot (save / restore whole execution state) --------------------

    /**
     * Serialize the entire execution state - the frame stack (each frame's code,
     * locals + value stack, ip, sp), the finished/return/yield flags, and all
     * non-host globals - into a byte[].
     *
     * The snapshot is relative to the current module: code objects are stored by
     * path, and host functions are <em>not</em> written (they are Java objects).
     * To restore, load the same {@code .mpy}, build a {@link Vm} whose globals hold
     * the re-registered host functions, and call {@link #restoreSnapshot}.
     */
    public byte[] saveSnapshot() {
        return saveSnapshot(HostRegistry.fromGlobals(globals));
    }

    /**
     * Like {@link #saveSnapshot()}, but with a {@link HostRegistry} so that live
     * host functions - on the stack or in globals - are written by their stable id
     * instead of being skipped/rejected. Restore with the id-matching registry.
     */
    public byte[] saveSnapshot(HostRegistry hosts) {
        if (hosts != null) hosts.fallbackTo(globals);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            Snapshot.Writer w = new Snapshot.Writer(out, module, hosts); // one identity space for everything
            // embedded module table: raw .mpy bytes of main (index 0) + every
            // loaded module - a restore is fully self-contained.
            w.moduleIndex = new java.util.IdentityHashMap<>();
            List<Map.Entry<String, MpyModule>> table = new ArrayList<>();
            table.add(Map.entry("", module));
            w.moduleIndex.put(module, 0);
            for (Map.Entry<String, PyModule> e : loadedModules.entrySet()) {
                if (e.getValue().code == null || w.moduleIndex.containsKey(e.getValue().code)) continue;
                w.moduleIndex.put(e.getValue().code, table.size());
                table.add(Map.entry(e.getKey(), e.getValue().code));
            }
            for (Map.Entry<String, MpyModule> e : cellModules) {
                if (w.moduleIndex.containsKey(e.getValue())) continue;
                w.moduleIndex.put(e.getValue(), table.size());
                table.add(Map.entry(e.getKey(), e.getValue()));
            }
            out.writeInt(table.size());
            for (Map.Entry<String, MpyModule> e : table) {
                out.writeUTF(e.getKey());
                byte[] raw = e.getValue().rawBytes;
                out.writeInt(raw.length);
                out.write(raw);
            }
            out.writeInt(importRoots.size());
            for (String r : importRoots) out.writeUTF(r);
            out.writeUTF(mainRoot);
            out.writeUTF(mainDotted);
            List<Frame> frames = new ArrayList<>();
            for (Frame f = current; f != null; f = f.caller) frames.add(f);
            Collections.reverse(frames); // root first, current last
            out.writeInt(frames.size());
            for (Frame f : frames) w.writeFrameRef(f);

            out.writeBoolean(finished);
            out.writeBoolean(mainReturned);
            out.writeBoolean(daemonFinishOnExit);
            out.writeBoolean(yielding);
            w.writeValue(returnValue);
            // main frame blocked in run(): index of the awaited task (-1 if none).
            // Written before the task list; resolved to the AsyncTask on restore.
            int waitIdx = -1;
            if (mainWaitingTask != null) waitIdx = asyncTasks.indexOf(mainWaitingTask);
            out.writeInt(waitIdx);

            // globals: with a registry, host functions are stored by id; without one
            // they are skipped (and re-registered by name on restore).
            List<Map.Entry<String, Object>> data = new ArrayList<>();
            for (Map.Entry<String, Object> e : globals.entrySet()) {
                if (e.getValue() instanceof HostFunction && hosts == null) continue;
                data.add(e);
            }
            out.writeInt(data.size());
            for (Map.Entry<String, Object> e : data) {
                w.writeValue(e.getKey());   // name (a String)
                w.writeValue(e.getValue()); // value
            }

            w.writeValue(handledException);   // traceback.format_exc() register

            // the import cache, so restored code doesn't re-execute module tops
            out.writeInt(loadedModules.size());
            for (Map.Entry<String, PyModule> e : loadedModules.entrySet()) {
                w.writeValue(e.getKey());
                w.writeValue(e.getValue());
            }

            // the cooperative event loop's task list: each task's coroutine (a
            // PyGen, already serializable) plus its done/result/error state.
            out.writeInt(asyncTasks.size());
            long snapNow = System.currentTimeMillis();
            for (AsyncTask t : asyncTasks) {
                w.writeValue(t.coro);
                out.writeBoolean(t.done);
                w.writeValue(t.result);
                w.writeValue(t.error);
                out.writeBoolean(t.atYieldPoint);
                w.writeValue(t.lastYield);
                // store the REMAINING sleep time (subtracting any already-elapsed since
                // the clock base), so time spent at rest after this snapshot is ignored.
                long remaining = t.sleepRemainingMs;
                if (remaining > 0) {
                    long elapsed = snapNow - t.sleepClockBase;
                    remaining = Math.max(0, remaining - Math.max(0, elapsed));
                }
                out.writeLong(remaining);
                // For a task budget-suspended mid-call-chain, serialize the chain of
                // frames from the deepest active frame up to (but excluding) the coro's
                // top frame, which is already captured via t.coro above. On restore the
                // caller links are re-established so execution resumes at the deepest.
                if (t.suspendedFrame != null && t.suspendedFrame != t.coro.frame) {
                    java.util.List<Frame> chain = new java.util.ArrayList<>();
                    for (Frame f = t.suspendedFrame; f != null && f != t.coro.frame; f = f.caller) chain.add(f);
                    out.writeInt(chain.size());
                    // deepest-first; each frame written by ref (its own caller is relinked below)
                    for (Frame f : chain) w.writeFrameRef(f);
                } else {
                    out.writeInt(0);   // no separate chain: suspended at coro top or at a yield
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e); // never thrown by ByteArrayOutputStream
        }
        return bos.toByteArray();
    }

    public void restoreSnapshot(byte[] data) {
        restoreSnapshot(data, HostRegistry.fromGlobals(globals));
    }

    /** Build a whole VM from a self-contained snapshot: all module code comes from
     *  the bytes embedded at save time (the main module passed to the constructor
     *  is unnecessary). Provide globals with builtins/host functions installed and
     *  a registry resolving the snapshot's host-function ids. */
    public static Vm fromSnapshot(byte[] snapshot, Map<String, Object> globals, HostRegistry hosts) {
        Vm vm = new Vm(null, globals);
        vm.restoreSnapshot(snapshot, hosts != null ? hosts : HostRegistry.fromGlobals(vm.globals));
        return vm;
    }

    /** Fully turnkey restore: fresh batteries-included globals, host functions
     *  resolved from them. Equivalent to {@code fromSnapshot(snap, null, null)}. */
    public static Vm fromSnapshot(byte[] snapshot) {
        return fromSnapshot(snapshot, null, null);
    }

    /**
     * Restore a snapshot, resolving any host-function ids through {@code hosts}.
     * The module must match; data globals are merged into this VM's globals.
     */
    public void restoreSnapshot(byte[] data, HostRegistry hosts) {
        if (hosts == null) hosts = HostRegistry.fromGlobals(globals);
        else hosts.fallbackTo(globals);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            Snapshot.Reader r = new Snapshot.Reader(in, module, hosts); // one identity space
            // read the embedded module table and load each module from its bytes;
            // the snapshot carries its own code, so nothing external can mismatch.
            int nm = in.readInt();
            MpyModule[] mtable = new MpyModule[nm];
            cellModules.clear();
            for (int i = 0; i < nm; i++) {
                String mname = in.readUTF();
                byte[] raw = new byte[in.readInt()];
                in.readFully(raw);
                mtable[i] = MpyLoader.load(raw, new QstrPool());
                if (i > 0) embedCell(mname.isEmpty() ? ("#" + i) : mname, mtable[i]);
            }
            r.moduleTable = mtable;
            this.module = mtable[0];                  // the embedded main module
            importRoots.clear();
            int nr = in.readInt();
            for (int i = 0; i < nr; i++) importRoots.add(in.readUTF());
            mainRoot = in.readUTF();
            mainDotted = in.readUTF();
            int n = in.readInt();
            Frame[] frames = new Frame[n];
            for (int i = 0; i < n; i++) {
                frames[i] = r.readFrameRef();
                if (i > 0) frames[i].caller = frames[i - 1];
            }
            current = n > 0 ? frames[n - 1] : null;
            finished = in.readBoolean();
            mainReturned = in.readBoolean();
            daemonFinishOnExit = in.readBoolean();
            yielding = in.readBoolean();
            returnValue = r.readValue();
            int waitIdx = in.readInt();   // main-frame run() block: task index or -1
            int g = in.readInt();
            for (int i = 0; i < g; i++) {
                String name = (String) r.readValue();
                Object value = r.readValue();
                globals.put(name, value);
            }

            handledException = (PyExc.Instance) r.readValue();

            loadedModules.clear();
            byCode.clear();
            int lm = in.readInt();
            for (int i = 0; i < lm; i++) {
                String name = (String) r.readValue();
                PyModule pm = (PyModule) r.readValue();
                loadedModules.put(name, pm);
                if (pm.code != null) byCode.put(pm.code, pm);
            }

            // restore the cooperative event loop's task list
            asyncTasks.clear();
            int nt = in.readInt();
            long restoreNow = System.currentTimeMillis();
            for (int i = 0; i < nt; i++) {
                PyGen coro = (PyGen) r.readValue();
                AsyncTask t = new AsyncTask(coro);
                t.done = in.readBoolean();
                t.result = r.readValue();
                t.error = (PyExc.Instance) r.readValue();
                t.atYieldPoint = in.readBoolean();
                t.lastYield = r.readValue();
                t.sleepRemainingMs = in.readLong();
                // reset the clock base to the restore instant: the remaining time
                // starts counting fresh now, so the interval the snapshot spent at
                // rest does not shorten the sleep.
                t.sleepClockBase = restoreNow;
                // reconstruct the budget-suspended call chain (deepest-first) and relink
                // callers up to the coroutine's top frame, so execution resumes exactly
                // where it stopped rather than at the coro top.
                int chainLen = in.readInt();
                if (chainLen > 0) {
                    Frame[] chain = new Frame[chainLen];
                    for (int j = 0; j < chainLen; j++) chain[j] = r.readFrameRef();
                    // chain[0] is deepest; link each up: chain[k].caller = chain[k+1],
                    // and the shallowest (last) links to the coro's top frame.
                    for (int j = 0; j < chainLen - 1; j++) chain[j].caller = chain[j + 1];
                    chain[chainLen - 1].caller = t.coro.frame;
                    t.suspendedFrame = chain[0];
                }
                asyncTasks.add(t);
            }
            // resolve the main-frame run() block, if any, to the restored task
            mainWaitingTask = (waitIdx >= 0 && waitIdx < asyncTasks.size())
                              ? asyncTasks.get(waitIdx) : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Execute exactly one bytecode instruction on the current frame. Call
     * {@link #start} first to set the frame to run.
     *
     * @return {@code true} once execution has finished (the root frame returned);
     *         after it returns {@code true} it stays finished and further calls are
     *         no-ops. The result is then available from {@link #result()}.
     */
    public boolean step() {
        if (!isFinished()) step(new int[]{1});
        return isFinished();
    }

    /**
     * Execute up to {@code remops[0]} bytecode instructions as one batched run,
     * driving both the main frame and the cooperative async event loop.
     *
     * <p>Budget split: if async tasks are pending and the main frame has not yet
     * returned, async runs <em>first</em> with {@code floor(budget/3)} — and whatever
     * async does not consume falls through, so the main frame then runs with the
     * remaining budget (its own two-thirds plus any async left unused). Running async
     * first avoids re-dividing the budget within a single call. If there are no async
     * tasks, the whole budget goes to the main frame.
     *
     * <p>Once the main frame has returned, behaviour depends on daemon-finish mode
     * (see the finishDaemonOnExit() builtin): when on, the entire budget goes to
     * draining the remaining async tasks and {@link #isFinished()} stays false until
     * they complete; when off, the VM is already finished and this is a no-op.
     *
     * <p>{@code remops[0]} is updated in place to the number of instructions left
     * over ({@code > 0} if everything runnable finished early, else {@code 0}).
     */
    public void step(int[] remops) {
        int budget = remops[0];
        if (budget <= 0) { remops[0] = 0; return; }

        boolean haveTasks = hasPendingTasks();

        // jasyncio.run(coro): the main frame is blocked on mainWaitingTask. Drive only
        // async until that task settles, then hand its result (or exception) to the
        // main frame and let it resume on a subsequent step.
        if (mainWaitingTask != null) {
            if (!mainWaitingTask.done && haveTasks) {
                int[] a = { budget };
                driveAsync(a);
                remops[0] = a[0];
            }
            if (mainWaitingTask.done) {
                AsyncTask t = mainWaitingTask;
                mainWaitingTask = null;
                if (t.error != null) {
                    raiseIp = current.ip;
                    raiseInto(t.error, null);
                } else {
                    current.setTop(t.result);   // run() returns the coroutine's value
                }
                if (!finished && remops[0] > 0) remops[0] = runLoop(remops[0]);
            } else {
                remops[0] = 0;   // still waiting; async consumed the budget
            }
            return;
        }

        if (mainReturned) {
            // The main frame is done (finished == true). In daemon mode keep draining
            // async with the whole budget; isFinished() stays false until they clear.
            // Off daemon mode (or no tasks): nothing left to run.
            if (daemonFinishOnExit && haveTasks) {
                int[] a = { budget };
                driveAsync(a);
                remops[0] = a[0];
            } else {
                remops[0] = budget;
            }
            return;
        }

        if (!haveTasks) {
            // no async work: the main frame takes everything
            remops[0] = runLoop(budget);
            return;
        }

        // async first with a third of the budget; its unused remainder rolls into main
        int asyncShare = budget / 3;
        int mainShare = budget - asyncShare;
        if (asyncShare > 0) {
            int[] a = { asyncShare };
            driveAsync(a);
            mainShare += a[0];        // hand async's leftover to the main frame
        }
        remops[0] = runLoop(mainShare);
    }

    /** True once execution is complete. Normally that is when the root frame returns;
     *  in daemon-finish mode (finishDaemonOnExit()), the VM is not finished until the
     *  main frame has returned AND all async tasks have drained. */
    public boolean isFinished() {
        if (mainWaitingTask != null) return false;   // main frame blocked in run()
        if (daemonFinishOnExit && mainReturned && hasPendingTasks()) return false;
        return finished;
    }

    /** The value returned by the root frame (valid once {@link #isFinished()}). */
    public Object result() { return returnValue; }

    /**
     * Ask the batch currently running (via {@link #step(int[])} / {@link #resume} /
     * {@link #step()}) to suspend immediately, <em>keeping</em> the unused step
     * budget. Called by the {@link #yieldHost() yield host function}; the VM is left
     * runnable (not finished) and the next run continues right after the yield point.
     */
    public void requestYield() { yielding = true; }

    /**
     * Whether the most recent batch stopped because the script yielded (rather than
     * finishing or exhausting its budget). Valid until the next run/step/resume.
     */
    public boolean isYielding() { return yielding; }

    /**
     * A host function that suspends the VM when the script calls it (e.g. register it
     * as {@code yieldJava}). It consumes only its own call instruction; the remaining
     * {@code step(int[])} budget is preserved (so {@code remops[0]} may stay &gt; 0),
     * and a plain {@link #step()} sees no conflict because that one call instruction is
     * the single op it runs.
     */
    public HostFunction yieldHost() {
        return args -> { yielding = true; return PyObj.NONE; };
    }

    // ---- one instruction ----------------------------------------------------

    /**
     * The core interpreter loop: execute up to {@code budget} instructions on the
     * current frame chain and return the number of instructions NOT used (i.e.
     * {@code budget - executed}), which is > 0 only if execution finished early.
     *
     * This is the single place the dispatch lives. The current frame and its
     * bytecode are hoisted into locals and only re-read when a call or return
     * switches frames, and each instruction is decoded straight into primitive
     * locals - so batching N instructions costs one loop, with no per-instruction
     * method re-entry and no per-instruction object allocation.
     */
    private int runLoop(int budget) {
        Frame f = current;
        byte[] bc = f.bc;
        int remaining = budget;
        yielding = false;   // clear any prior yield request; a fresh batch continues past it
        taskYielded = false;

        while (remaining > 0 && !finished && !taskYielded) {
            try {
            if (limits.memoryCheckInterval > 0 && --memCheckCountdown <= 0) {
                memCheckCountdown = limits.memoryCheckInterval;
                long used = MemEstimator.estimate(current, globals);
                if (used > limits.memoryLimit) {
                    throw new PyException("MemoryError",
                            "memory allocation failed, allocating " + used + " bytes");
                }
            }
            // ---- decode inline (mirror of Instruction.decode; no allocation) ----
            raiseIp = f.ip;   // C's code_state->ip: the START of this instruction,
                              // used by the unwind's handler<=ip test (a handler
                              // placed right after a trailing `raise` must survive)
            int ip = f.ip;
            final int op = bc[ip++] & 0xff;
            final int format = Opcodes.format(op);
            long arg = 0;
            if (format == Opcodes.FORMAT_QSTR || format == Opcodes.FORMAT_VAR_UINT) {
                arg = bc[ip] & 0x7f;
                if (op == Opcodes.LOAD_CONST_SMALL_INT && (arg & 0x40) != 0) arg |= ~0L << 7;
                while ((bc[ip] & 0x80) != 0) arg = (arg << 7) | (bc[++ip] & 0x7f);
                ip++;
            } else if (format == Opcodes.FORMAT_OFFSET) {
                if ((bc[ip] & 0x80) == 0) {
                    arg = bc[ip++] & 0xff;
                    if (Opcodes.hasSignedOffset(op)) arg -= 0x40;
                } else {
                    arg = (bc[ip] & 0x7f) | ((bc[ip + 1] & 0xff) << 7);
                    ip += 2;
                    if (Opcodes.hasSignedOffset(op)) arg -= 0x4000;
                }
            }
            if ((op & Opcodes.MASK_EXTRA_BYTE) == 0) ip++; // trailing extra byte (unused pre-phase-7)
            f.ip = ip; // advance past the instruction; jumps adjust f.ip below

            // ---- dispatch one instruction ----
            dispatch:
            {
                // packed "multi" ranges (hot path)
                if (op >= Opcodes.LOAD_CONST_SMALL_INT_MULTI
                        && op < Opcodes.LOAD_CONST_SMALL_INT_MULTI + Opcodes.LOAD_CONST_SMALL_INT_MULTI_NUM) {
                    f.push((long) (op - (Opcodes.LOAD_CONST_SMALL_INT_MULTI + Opcodes.LOAD_CONST_SMALL_INT_MULTI_EXCESS)));
                    break dispatch;
                }
                if (op >= Opcodes.LOAD_FAST_MULTI && op < Opcodes.LOAD_FAST_MULTI + Opcodes.LOAD_FAST_MULTI_NUM) {
                    f.push(loadLocal(f, op - Opcodes.LOAD_FAST_MULTI));
                    break dispatch;
                }
                if (op >= Opcodes.STORE_FAST_MULTI && op < Opcodes.STORE_FAST_MULTI + Opcodes.STORE_FAST_MULTI_NUM) {
                    f.setLocal(op - Opcodes.STORE_FAST_MULTI, f.pop());
                    break dispatch;
                }
                if (op >= Opcodes.UNARY_OP_MULTI && op < Opcodes.UNARY_OP_MULTI + Opcodes.UNARY_OP_MULTI_NUM) {
                    int uop = op - Opcodes.UNARY_OP_MULTI;
                    Object v = f.top();
                    if (v instanceof PyInstance) {
                        String d = unaryDunder(uop);
                        if (d != null) {
                            Object m = ((PyInstance) v).cls.lookup(d);
                            if (m != null) { f.setTop(callSync(new BoundPyMethod(m, v), NO_ARGS)); break dispatch; }
                        }
                        Object nv = nativeValueOf(v);   // -MyInt(5) operates on native int
                        if (nv != null) { f.setTop(Ops.unaryOp(uop, nv)); break dispatch; }
                    }
                    f.setTop(Ops.unaryOp(uop, v));
                    break dispatch;
                }
                if (op >= Opcodes.BINARY_OP_MULTI && op < Opcodes.BINARY_OP_MULTI + Opcodes.BINARY_OP_MULTI_NUM) {
                    Object rhs = f.pop();
                    Object lhs = f.top();
                    f.setTop(binaryOpDispatch(op - Opcodes.BINARY_OP_MULTI, lhs, rhs));
                    break dispatch;
                }

                switch (op) {
                    case Opcodes.LOAD_CONST_FALSE: f.push(Boolean.FALSE); break dispatch;
                    case Opcodes.LOAD_CONST_TRUE:  f.push(Boolean.TRUE);  break dispatch;
                    case Opcodes.LOAD_CONST_NONE:  f.push(PyObj.NONE);    break dispatch;
                    case Opcodes.LOAD_NULL:        f.push(null);          break dispatch;

                    case Opcodes.LOAD_CONST_SMALL_INT: f.push(arg); break dispatch;
                    case Opcodes.LOAD_CONST_STRING:    f.push(f.code.module.qstr((int) arg)); break dispatch;
                    case Opcodes.LOAD_CONST_OBJ:       f.push(f.code.module.objTable[(int) arg]); break dispatch;

                    case Opcodes.LOAD_FAST_N:   f.push(loadLocal(f, (int) arg)); break dispatch;
                    case Opcodes.LOAD_DEREF: {
                        Object v = ((PyObj.Cell) f.local((int) arg)).value;
                        if (v == null) throw PyException.nameError("local variable referenced before assignment");
                        f.push(v);
                        break dispatch;
                    }
                    case Opcodes.STORE_DEREF:
                        ((PyObj.Cell) f.local((int) arg)).value = f.pop();
                        break dispatch;
                    case Opcodes.DELETE_DEREF:
                        ((PyObj.Cell) f.local((int) arg)).value = null;
                        break dispatch;
                    case Opcodes.STORE_FAST_N:  f.setLocal((int) arg, f.pop());  break dispatch;
                    case Opcodes.DELETE_FAST:   f.setLocal((int) arg, null);     break dispatch;

                    case Opcodes.LOAD_NAME: {
                        String name = f.code.module.qstr((int) arg);
                        if (f.names != null && f.names.containsKey(name)) { f.push(f.names.get(name)); break dispatch; }
                        Map<String, Object> scope = scopeOf(f);
                        if (scope.containsKey(name)) { f.push(scope.get(name)); break dispatch; }
                        if (scope != globals && globals.containsKey(name)) { f.push(globals.get(name)); break dispatch; } // builtins
                        throw PyException.nameError("name '" + name + "' is not defined");
                    }
                    case Opcodes.LOAD_GLOBAL: {
                        String name = f.code.module.qstr((int) arg);
                        Map<String, Object> scope = scopeOf(f);
                        if (scope.containsKey(name)) { f.push(scope.get(name)); break dispatch; }
                        if (scope != globals && globals.containsKey(name)) { f.push(globals.get(name)); break dispatch; } // builtins
                        throw PyException.nameError("name '" + name + "' is not defined");
                    }
                    case Opcodes.STORE_NAME: {
                        String name = f.code.module.qstr((int) arg);
                        if (f.names != null) f.names.put(name, f.pop());
                        else scopeOf(f).put(name, f.pop());
                        break dispatch;
                    }
                    case Opcodes.STORE_GLOBAL:
                        scopeOf(f).put(f.code.module.qstr((int) arg), f.pop());
                        break dispatch;
                    case Opcodes.DELETE_NAME: case Opcodes.DELETE_GLOBAL:
                        globals.remove(f.code.module.qstr((int) arg));
                        break dispatch;

                    case Opcodes.POP_TOP:      f.drop(1); break dispatch;
                    case Opcodes.DUP_TOP:      f.push(f.top()); break dispatch;
                    case Opcodes.DUP_TOP_TWO: {
                        Object x = f.peek(1), y = f.peek(0);
                        f.push(x); f.push(y); break dispatch;
                    }
                    case Opcodes.ROT_TWO: {
                        Object a = f.peek(0), b = f.peek(1);
                        f.set(0, b); f.set(1, a); break dispatch;
                    }
                    case Opcodes.ROT_THREE: {
                        Object c = f.peek(0), b = f.peek(1), a = f.peek(2);
                        f.set(0, b); f.set(1, a); f.set(2, c); break dispatch;
                    }

                    case Opcodes.LOAD_SUBSCR: {
                        Object index = f.pop();
                        Object obj = f.top();
                        if (obj instanceof PyInstance) {
                            Object m = ((PyInstance) obj).cls.lookup("__getitem__");
                            if (m != null) { f.setTop(callSync(new BoundPyMethod(m, obj), new Object[]{index})); break dispatch; }
                            Object nv = nativeValueOf(obj);   // MyStr('hi')[0] / MyList[i]
                            if (nv != null) { f.setTop(Ops.subscrGet(nv, index)); break dispatch; }
                            throw PyException.typeError("'" + ((PyInstance) obj).cls.name + "' object is not subscriptable");
                        }
                        f.setTop(Ops.subscrGet(obj, index));
                        break dispatch;
                    }
                    case Opcodes.STORE_SUBSCR: {
                        Object obj = f.peek(1), index = f.peek(0), value = f.peek(2);
                        if (obj instanceof PyInstance) {
                            Object m = ((PyInstance) obj).cls.lookup("__setitem__");
                            if (m != null) { callSync(new BoundPyMethod(m, obj), new Object[]{index, value}); f.drop(3); break dispatch; }
                            Object nv = nativeValueOf(obj);
                            if (nv != null) { Ops.subscrStore(nv, index, value); f.drop(3); break dispatch; }
                            throw PyException.typeError("'" + ((PyInstance) obj).cls.name + "' object does not support item assignment");
                        }
                        Ops.subscrStore(obj, index, value);
                        f.drop(3);
                        break dispatch;
                    }

                    case Opcodes.JUMP: f.ip += arg; break dispatch;
                    case Opcodes.POP_JUMP_IF_TRUE:  if (truthy(f.pop())) f.ip += arg; break dispatch;
                    case Opcodes.POP_JUMP_IF_FALSE: if (!truthy(f.pop())) f.ip += arg; break dispatch;
                    case Opcodes.JUMP_IF_TRUE_OR_POP:
                        if (truthy(f.top())) f.ip += arg; else f.drop(1);
                        break dispatch;
                    case Opcodes.JUMP_IF_FALSE_OR_POP:
                        if (truthy(f.top())) f.drop(1); else f.ip += arg;
                        break dispatch;

                    case Opcodes.BUILD_TUPLE: {
                        int n = (int) arg;
                        Object[] items = new Object[n];
                        for (int k = n - 1; k >= 0; k--) items[k] = f.pop();
                        f.push(new PyObj.Tuple(items));
                        break dispatch;
                    }
                    case Opcodes.BUILD_LIST: {
                        int n = (int) arg;
                        Object[] tmp = new Object[n];
                        for (int k = n - 1; k >= 0; k--) tmp[k] = f.pop();
                        PyObj.PyList list = new PyObj.PyList();
                        for (Object o : tmp) list.items.add(o);
                        f.push(list);
                        break dispatch;
                    }
                    case Opcodes.BUILD_MAP: f.push(new PyObj.PyDict()); break dispatch;
                    case Opcodes.STORE_MAP: {
                        PyObj.PyDict d = (PyObj.PyDict) f.peek(2);
                        Object key = f.peek(0), value = f.peek(1);
                        Ops.subscrStore(d, key, value);
                        f.drop(2);
                        break dispatch;
                    }
                    case Opcodes.BUILD_SET: {
                        int n = (int) arg;
                        Object[] tmp = new Object[n];
                        for (int k = n - 1; k >= 0; k--) tmp[k] = f.pop();
                        PyObj.PySet set = new PyObj.PySet();
                        for (Object o : tmp) set.items.add(o);
                        f.push(set);
                        break dispatch;
                    }
                    case Opcodes.BUILD_SLICE: {
                        int n = (int) arg;
                        Object step = (n == 3) ? f.pop() : PyObj.NONE;
                        Object stop = f.pop();
                        Object start = f.top();
                        f.setTop(new PyObj.Slice(start, stop, step));
                        break dispatch;
                    }
                    case Opcodes.UNPACK_SEQUENCE: {
                        int n = (int) arg;
                        Object[] elems = Ops.unpackSeq(f.pop(), n);
                        for (int k = n - 1; k >= 0; k--) f.push(elems[k]);
                        break dispatch;
                    }

                    case Opcodes.STORE_COMP: {
                        int unum = (int) arg;
                        Object container = f.state[f.sp - (unum >> 2)];
                        switch (unum & 3) {
                            case 0:  // list comprehension: append TOS
                                ((PyObj.PyList) container).items.add(f.pop());
                                break;
                            case 1: { // dict comprehension: key at TOS, value below
                                Object key = f.pop();
                                Object val = f.pop();
                                Ops.subscrStore(container, key, val);
                                break;
                            }
                            default: { // set comprehension
                                PyObj.PySet s = (PyObj.PySet) container;
                                Object v = f.pop();
                                boolean dup = false;
                                for (Object x : s.items) if (Ops.pyEquals(x, v)) { dup = true; break; }
                                if (!dup) s.items.add(v);
                                break;
                            }
                        }
                        break dispatch;
                    }
                    case Opcodes.GET_ITER: {
                        Object o = f.top();
                        if (!(o instanceof PyGen)) f.setTop(iterOf(o)); // a gen is its own iterator
                        break dispatch;
                    }
                    case Opcodes.GET_ITER_STACK: {
                        int base = f.sp;
                        Object src = f.state[base];
                        Object it = (src instanceof PyGen) ? src : iterOf(src); // a gen is its own iterator
                        f.sp = base + (NSLOTS - 1);
                        f.state[base] = null;      // MP_OBJ_NULL marker (slot 0)
                        f.state[base + 1] = it;    // iterator object (slot 1)
                        break dispatch;
                    }
                    case Opcodes.FOR_ITER: {
                        Object itObj = f.state[f.sp - NSLOTS + 2];
                        if (itObj instanceof PyGen) {
                            resumeGen((PyGen) itObj, PyGen.MODE_FOR_ITER, (int) (f.ip + arg), PyObj.NONE);
                            break dispatch;
                        }
                        PyObj.Iter it = (PyObj.Iter) itObj;
                        Object v = it.next();
                        if (v == PyObj.STOP_ITERATION) { f.drop(NSLOTS); f.ip += arg; }
                        else f.push(v);
                        break dispatch;
                    }

                    // ---- functions & calls (Phase 3) ---------------------------
                    case Opcodes.MAKE_CLOSURE: {
                        int nClosed = bc[f.ip - 1] & 0xff;   // extra byte
                        PyObj.Cell[] closed = new PyObj.Cell[nClosed];
                        for (int k = nClosed - 1; k >= 0; k--) closed[k] = (PyObj.Cell) f.pop();
                        PyFunction fn = new PyFunction(f.code.children.get((int) arg), f.code.module, null);
                        f.push(new Closure(fn, closed));
                        break dispatch;
                    }
                    case Opcodes.MAKE_CLOSURE_DEFARGS: {
                        int nClosed = bc[f.ip - 1] & 0xff;   // extra byte
                        PyObj.Cell[] closed = new PyObj.Cell[nClosed];
                        for (int k = nClosed - 1; k >= 0; k--) closed[k] = (PyObj.Cell) f.pop();
                        Object defDict = f.peek(0);           // (def_tuple, def_dict) below the cells
                        Object defTuple = f.peek(1);
                        Object[] defs = (defTuple instanceof PyObj.Tuple) ? ((PyObj.Tuple) defTuple).items : null;
                        java.util.Map<Object, Object> kwd = (defDict instanceof PyObj.PyDict) ? ((PyObj.PyDict) defDict).map : null;
                        PyFunction fn = new PyFunction(f.code.children.get((int) arg), f.code.module, defs, kwd);
                        f.drop(1);
                        f.setTop(new Closure(fn, closed));
                        break dispatch;
                    }
                    case Opcodes.MAKE_FUNCTION: {
                        f.push(new PyFunction(f.code.children.get((int) arg), f.code.module, null));
                        break dispatch;
                    }
                    case Opcodes.MAKE_FUNCTION_DEFARGS: {
                        Object defDict = f.peek(0);   // keyword-only defaults
                        Object defTuple = f.peek(1);  // positional defaults
                        Object[] defs = (defTuple instanceof PyObj.Tuple) ? ((PyObj.Tuple) defTuple).items : null;
                        java.util.Map<Object, Object> kwd = (defDict instanceof PyObj.PyDict) ? ((PyObj.PyDict) defDict).map : null;
                        PyFunction fn = new PyFunction(f.code.children.get((int) arg), f.code.module, defs, kwd);
                        f.drop(1);       // remove def_dict
                        f.setTop(fn);    // replace def_tuple with the function
                        break dispatch;
                    }
                    // ---- attributes & methods (Phase 5) ------------------------
                    case Opcodes.LOAD_ATTR: {
                        String name = f.code.module.qstr((int) arg);
                        Object obj = f.top();
                        f.setTop(loadAttr(obj, name));
                        break dispatch;
                    }
                    case Opcodes.STORE_ATTR: {
                        String name = f.code.module.qstr((int) arg);
                        Object obj = f.peek(0);          // value at peek(1)
                        Object value = f.peek(1);
                        f.drop(2);
                        if (obj instanceof PyInstance) { ((PyInstance) obj).attrs.put(name, value); break dispatch; }
                        if (obj instanceof PyExc.Instance) { ((PyExc.Instance) obj).userAttrs.put(name, value); break dispatch; }
                        if (obj instanceof PyClass) { ((PyClass) obj).ns.put(name, value); break dispatch; }
                        if (obj instanceof PyModule) { ((PyModule) obj).ns.put(name, value); break dispatch; }
                        throw new PyException("AttributeError", "'" + Methods.typeName(obj)
                                + "' object has no attribute '" + name + "'");
                    }
                    case Opcodes.LOAD_METHOD: {
                        String name = f.code.module.qstr((int) arg);
                        Object obj = f.top();
                        if (obj instanceof PyInstance) {
                            PyInstance inst = (PyInstance) obj;
                            Object v = inst.attrs.get(name);
                            if (v != null) { f.setTop(v); f.push(null); break dispatch; } // plain attr
                            Object m = inst.cls.lookup(name);
                            if (m == null) {
                                // native subclass: fall through to str/list methods on the native value
                                if (inst.nativeValue != null && Methods.lookup(inst.nativeValue, name) != null) {
                                    f.setTop(new Methods.BoundMethod(inst.nativeValue, name)); f.push(null);
                                    break dispatch;
                                }
                                throw new PyException("AttributeError",
                                        "'" + inst.cls.name + "' object has no attribute '" + name + "'");
                            }
                            if (m instanceof Descriptors.Property) {
                                f.setTop(callSync(((Descriptors.Property) m).getter, new Object[]{inst})); f.push(null);
                            } else if (m instanceof Descriptors.StaticMethod) {
                                f.setTop(((Descriptors.StaticMethod) m).fn); f.push(null);
                            } else if (m instanceof Descriptors.ClassMethod) {
                                f.setTop(((Descriptors.ClassMethod) m).fn); f.push(inst.cls); // cls prepended
                            } else if (m instanceof PyFunction || m instanceof Closure) {
                                f.setTop(m); f.push(inst);          // bound: self prepended at call
                            } else { f.setTop(m); f.push(null); }   // class attr, not a method
                            break dispatch;
                        }
                        if (obj instanceof PyExc.Instance) {
                            PyExc.Instance e = (PyExc.Instance) obj;
                            Object ua = e.userAttrs.get(name);
                            if (ua != null) { f.setTop(ua); f.push(null); break dispatch; }
                            if (e.type.userClass != null) {
                                Object m = ((PyClass) e.type.userClass).lookup(name);
                                if (m instanceof PyFunction || m instanceof Closure) { f.setTop(m); f.push(e); break dispatch; }
                                if (m != null) { f.setTop(m); f.push(null); break dispatch; }
                            }
                            // fall through to args/value pseudo-attrs via loadAttr
                            f.setTop(loadAttr(e, name)); f.push(null);
                            break dispatch;
                        }
                        if (obj instanceof PyClass) {
                            Object m = ((PyClass) obj).lookup(name);
                            if (m == null) throw new PyException("AttributeError",
                                    "type object '" + ((PyClass) obj).name + "' has no attribute '" + name + "'");
                            if (m instanceof Descriptors.StaticMethod) {
                                f.setTop(((Descriptors.StaticMethod) m).fn); f.push(null);
                            } else if (m instanceof Descriptors.ClassMethod) {
                                f.setTop(((Descriptors.ClassMethod) m).fn); f.push(obj); // cls prepended
                            } else {
                                f.setTop(m); f.push(null);
                            }
                            break dispatch;
                        }
                        if (obj instanceof PyModule) {              // module.fn(...)
                            f.setTop(loadAttr(obj, name));
                            f.push(null);
                            break dispatch;
                        }
                        if (obj instanceof PyGen) {                 // gen.send(v) etc.
                            f.setTop(new Methods.Ref(name));
                            f.push(obj);
                            break dispatch;
                        }
                        if (Methods.lookup(obj, name) == null) {
                            throw new PyException("AttributeError", "'" + Methods.typeName(obj)
                                    + "' object has no attribute '" + name + "'");
                        }
                        // two-slot convention: [method, self], sp += 1
                        f.setTop(new Methods.Ref(name));
                        f.push(obj);
                        break dispatch;
                    }
                    case Opcodes.CALL_METHOD: {
                        int unum = (int) arg;
                        int nPos = unum & 0xff;
                        int nKw = (unum >> 8) & 0xff;
                        int base = f.sp - (nPos + 2 * nKw + 1); // method slot
                        Object callable = f.state[base];
                        Object self = f.state[base + 1];
                        Object[] pos;
                        if (nPos == 0) pos = NO_ARGS;
                        else { pos = new Object[nPos]; for (int k = 0; k < nPos; k++) pos[k] = f.state[base + 2 + k]; }
                        String[] kwN; Object[] kwV;
                        if (nKw == 0) { kwN = NO_KW_NAMES; kwV = NO_KW_VALUES; }
                        else {
                            kwN = new String[nKw]; kwV = new Object[nKw];
                            for (int k = 0; k < nKw; k++) {
                                kwN[k] = (String) f.state[base + 2 + nPos + 2 * k];
                                kwV[k] = f.state[base + 2 + nPos + 2 * k + 1];
                            }
                        }
                        f.sp = base; // result lands in the method slot
                        if (callable instanceof Methods.Ref && self instanceof PyGen) {
                            String mname = ((Methods.Ref) callable).name;
                            if (mname.equals("send")) {
                                if (nPos != 1) throw PyException.typeError("send() takes exactly one argument");
                                resumeGen((PyGen) self, PyGen.MODE_CALL, 0, pos[0]);
                                break dispatch;
                            }
                            throw new PyException("AttributeError", "'generator' object has no attribute '" + mname + "'");
                        }
                        if (callable instanceof Methods.Ref) {
                            if (nKw != 0) throw PyException.typeError("built-in methods do not accept keyword arguments");
                            Methods.Impl impl = Methods.lookup(self, ((Methods.Ref) callable).name);
                            f.setTop(impl.call(self, pos));
                        } else {
                            // fast path: a bound method that is a simple-signature
                            // Python function binds self+args straight into locals,
                            // with no (self-prepended) args array materialised.
                            int nEff = nPos + (self != null ? 1 : 0);
                            if (nKw == 0 && callable instanceof PyFunction && isSimpleSig((PyFunction) callable, nEff)) {
                                checkDepth();
                                Frame nf = prepareSimpleFrame((PyFunction) callable, self, f, base + 2, nPos);
                                nf.caller = current;
                                current = nf;
                            } else {
                                // generic path (self != null means prepend it)
                                Object[] callPos = pos;
                                if (self != null) {
                                    callPos = new Object[nPos + 1];
                                    callPos[0] = self;
                                    System.arraycopy(pos, 0, callPos, 1, nPos);
                                }
                                callInto(callable, callPos, kwN, kwV);
                            }
                        }
                        break dispatch;
                    }

                    case Opcodes.CALL_FUNCTION: {
                        int unum = (int) arg;
                        int nPos = unum & 0xff;
                        int nKw = (unum >> 8) & 0xff;
                        int base = f.sp - (nPos + 2 * nKw);   // slot holding the callable
                        Object callable = f.state[base];
                        // Fast path: a plain Python function with a simple signature
                        // (exactly nPos positional params, no *args/**kw/kwonly/
                        // defaults, no kwargs at the call) can take its arguments
                        // straight from this frame's stack into the new frame's
                        // locals - no args array is materialised.
                        if (nKw == 0 && callable instanceof PyFunction) {
                            PyFunction pf = (PyFunction) callable;
                            net.mpy.loader.CodeObject cc = pf.code;
                            int sf = cc.prelude.scopeFlags;
                            if (isSimpleSig(pf, nPos)) {
                                // simple signature: bind args straight from this
                                // stack into the callee's locals, no args array.
                                checkDepth();
                                Frame nf = prepareSimpleFrame(pf, null, f, base + 1, nPos);
                                f.sp = base;
                                nf.caller = current;
                                current = nf;
                                break dispatch;
                            }
                        }
                        Object[] callArgs;
                        if (nPos == 0) callArgs = NO_ARGS;
                        else { callArgs = new Object[nPos]; for (int k = 0; k < nPos; k++) callArgs[k] = f.state[base + 1 + k]; }
                        String[] kwN; Object[] kwV;
                        if (nKw == 0) { kwN = NO_KW_NAMES; kwV = NO_KW_VALUES; }
                        else {
                            kwN = new String[nKw]; kwV = new Object[nKw];
                            for (int k = 0; k < nKw; k++) {
                                kwN[k] = (String) f.state[base + 1 + nPos + 2 * k];
                                kwV[k] = f.state[base + 1 + nPos + 2 * k + 1];
                            }
                        }
                        f.sp = base;                          // leave callable slot as TOS (result lands here)
                        callInto(callable, callArgs, kwN, kwV); // may switch `current` to a new frame
                        break dispatch;
                    }

                    case Opcodes.CALL_FUNCTION_VAR_KW: {
                        int unum = (int) arg;
                        int nPos = unum & 0xff;
                        int nKw = (unum >> 8) & 0xff;
                        int base = f.sp - (nPos + 2 * nKw + 1);  // callable slot (bitmap at TOS)
                        Object[] parts = expandVarKw(f, base + 1, nPos, nKw, (Long) f.state[f.sp]);
                        Object callable = f.state[base];
                        f.sp = base;
                        callInto(callable, (Object[]) parts[0], (String[]) parts[1], (Object[]) parts[2]);
                        break dispatch;
                    }
                    case Opcodes.CALL_METHOD_VAR_KW: {
                        int unum = (int) arg;
                        int nPos = unum & 0xff;
                        int nKw = (unum >> 8) & 0xff;
                        int base = f.sp - (nPos + 2 * nKw + 2);  // method slot; self at base+1; bitmap TOS
                        Object[] parts = expandVarKw(f, base + 2, nPos, nKw, (Long) f.state[f.sp]);
                        Object callable = f.state[base];
                        Object self = f.state[base + 1];
                        Object[] pos = (Object[]) parts[0];
                        f.sp = base;
                        if (callable instanceof Methods.Ref) {
                            if (((String[]) parts[1]).length != 0)
                                throw PyException.typeError("built-in methods do not accept keyword arguments");
                            Methods.Impl impl = Methods.lookup(self, ((Methods.Ref) callable).name);
                            f.setTop(impl.call(self, pos));
                        } else {
                            Object[] callPos = pos;
                            if (self != null) {
                                callPos = new Object[pos.length + 1];
                                callPos[0] = self;
                                System.arraycopy(pos, 0, callPos, 1, pos.length);
                            }
                            callInto(callable, callPos, (String[]) parts[1], (Object[]) parts[2]);
                        }
                        break dispatch;
                    }

                    case Opcodes.LOAD_SUPER_METHOD: {
                        // stack: (..., super, __class__, self); result: (..., method, self)
                        String name = f.code.module.qstr((int) arg);
                        Object self = f.pop();
                        Object clsObj = f.pop();
                        if (!(clsObj instanceof PyClass)) throw PyException.typeError("super(): bad __class__");
                        Object m = ((PyClass) clsObj).lookupInBases(name);
                        if (m == null) {
                            // super().__init__(...) on a user exception subclass: the
                            // built-in Exception base's __init__ just records args.
                            if (name.equals("__init__") && ((PyClass) clsObj).excType != null && self instanceof PyExc.Instance) {
                                f.setTop(EXC_INIT);   // synthetic native init
                                f.push(self);
                                break dispatch;
                            }
                            throw new PyException("AttributeError",
                                "'super' object has no attribute '" + name + "'");
                        }
                        f.setTop(m);       // replaces the super builtin
                        f.push(self);
                        break dispatch;
                    }

                    case Opcodes.YIELD_FROM: {
                        // stack: (..., delegate, send_value)
                        PyGen gen = f.genOwner;
                        if (gen == null) throw PyException.typeError("yield outside generator");
                        Object send = f.pop();
                        Object delegate = f.top();
                        if (delegate instanceof PyGen) {
                            PyGen sub = (PyGen) delegate;
                            if (sub.done) { f.setTop(PyObj.NONE); break dispatch; }
                            resumeGen(sub, PyGen.MODE_YIELD_FROM, 0, send);
                            break dispatch;
                        }
                        // plain iterable path: iterate synchronously, one value per pass
                        PyObj.Iter it;
                        if (delegate instanceof PyObj.Iter) it = (PyObj.Iter) delegate;
                        else { it = Ops.getIter(delegate); f.setTop(it); }
                        Object v = it.next();
                        if (v == PyObj.STOP_ITERATION) { f.setTop(PyObj.NONE); break dispatch; }
                        f.ip -= 1;          // re-execute YIELD_FROM on next resume
                        f.push(v);
                        deliverYield(f);    // yield v out of this generator
                        break dispatch;
                    }

                    case Opcodes.SETUP_WITH: {
                        // stack in: (..., ctx_mgr); out: (..., __exit__, ctx_mgr, as_value) + finally block
                        // __enter__ is invoked as a real subframe (not callSync) so a
                        // cooperative busy-wait inside it (a contended Lock spin) yields
                        // to the scheduler; the with-block setup is completed when that
                        // subframe returns (see doReturn / withEnterArg).
                        Object obj = f.top();
                        Object exitFn = loadAttr(obj, "__exit__");
                        Object enterFn = loadAttr(obj, "__enter__");
                        f.setTop(exitFn);
                        f.push(obj);            // (..., __exit__, ctx)
                        f.push(enterFn);        // callable slot; result lands here on return
                        int base = f.sp;
                        f.sp = base;            // callable stays as TOS to receive the result
                        callInto(enterFn, NO_ARGS);
                        if (current != f) {
                            // a real Python subframe was pushed: finish SETUP_WITH when
                            // it returns (install the with-block, keep enter result on TOS)
                            current.withEnterArg = (int) arg;
                        } else {
                            // __enter__ resolved in-place (native/host): result now on TOS.
                            installWithBlock(f, (int) arg);
                        }
                        break dispatch;
                    }
                    case Opcodes.WITH_CLEANUP: {
                        Object tos = f.top();
                        if (tos == PyObj.NONE) {
                            // (..., __exit__, ctx, None) -> (..., None)
                            f.drop(2);
                            Object exitFn = f.top();
                            callSync(exitFn, new Object[]{PyObj.NONE, PyObj.NONE, PyObj.NONE});
                            f.setTop(PyObj.NONE);
                        } else if (tos instanceof Long) {
                            // unwind return/jump: (..., __exit__, ctx, data, cause) -> (..., data, cause)
                            Object cause = f.pop();
                            Object data = f.pop();
                            f.drop(1);           // ctx
                            Object exitFn = f.top();
                            callSync(exitFn, new Object[]{PyObj.NONE, PyObj.NONE, PyObj.NONE});
                            f.setTop(data);
                            f.push(cause);
                        } else if (tos instanceof PyExc.Instance) {
                            // (..., __exit__, ctx, exc) -> None (swallow) or exc (re-raise)
                            PyExc.Instance exc = (PyExc.Instance) f.pop();
                            f.drop(1);           // ctx
                            Object exitFn = f.top();
                            Object r = callSync(exitFn, new Object[]{exc.type, exc, PyObj.NONE});
                            f.setTop(Ops.isTrue(r) ? PyObj.NONE : exc);
                        } else {
                            throw PyException.typeError("bad value on stack at WITH_CLEANUP");
                        }
                        break dispatch;
                    }

                    case Opcodes.RETURN_VALUE:
                        doReturn();          // runs pending finally handlers first
                        break dispatch;

                    case Opcodes.YIELD_VALUE: {
                        if (f.genOwner == null) throw PyException.typeError("yield outside generator");
                        deliverYield(f);   // value stays at the gen's TOS (C convention)
                        break dispatch;
                    }

                    case Opcodes.IMPORT_NAME: {
                        // stack: (level, fromlist); result (over level): the top
                        // package for plain imports, the leaf for from-imports.
                        // Executes at most ONE uncached module per pass: its frame
                        // rewinds our ip here on return, and the cache makes the
                        // re-run idempotent until the whole chain is loaded.
                        String name = f.code.module.qstr((int) arg);   // "", "util", "game.ai.brain"
                        long level = (Long) f.peek(1);
                        String root;
                        String target;
                        if (level == 0) {
                            root = null;                               // search all roots
                            target = name;
                        } else {
                            String[] ctx = importContextOf(f);
                            String[] parts = ctx[1].isEmpty() ? new String[0] : ctx[1].split("\\.");
                            int keep = parts.length - (int) level;     // module itself + (level-1)
                            if (keep < 0) {
                                throw new PyException("ImportError", "attempted relative import beyond top-level package");
                            }
                            StringBuilder b = new StringBuilder();
                            for (int i = 0; i < keep; i++) { if (i > 0) b.append('.'); b.append(parts[i]); }
                            String base = b.toString();
                            target = base.isEmpty() ? name : (name.isEmpty() ? base : base + "." + name);
                            root = ctx[0];
                        }
                        if (target.isEmpty()) {
                            throw new PyException("ImportError", "cannot import the top-level package itself");
                        }
                        String[] tparts = target.split("\\.");
                        if (root == null) root = pickRoot(tparts);     // absolute: choose a root
                        PyModule parent = null, first = null, leaf = null;
                        boolean pushed = false;
                        for (int i = 1; i <= tparts.length; i++) {
                            StringBuilder db = new StringBuilder();
                            for (int k = 0; k < i; k++) { if (k > 0) db.append('.'); db.append(tparts[k]); }
                            String dotted = db.toString();
                            PyModule pm = loadedModules.get(dotted);
                            if (pm == null) {
                                Object[] hit = resolveDotted(root, dotted);
                                if (hit == null) {
                                    if (i < tparts.length) {
                                        pm = new PyModule(dotted, root, null); // synthetic package
                                        loadedModules.put(dotted, pm);
                                    } else {
                                        throw new PyException("ImportError", "no module named '" + target + "'");
                                    }
                                } else {
                                    MpyModule code = MpyLoader.load((byte[]) hit[0], new QstrPool());
                                    pm = new PyModule(dotted, root, code);
                                    loadedModules.put(dotted, pm);     // cache before executing
                                    byCode.put(code, pm);
                                    if (parent != null) parent.ns.put(tparts[i - 1], pm);
                                    checkDepth();
                                    Frame top = new Frame(code.root);
                                    top.names = pm.ns;
                                    top.returnOverride = pm;           // inert at runtime (retry path
                                                                       // returns first); lets snapshots
                                                                       // relink names -> module ns
                                    top.importRetryIp = raiseIp;       // rewind us on return
                                    top.caller = f;
                                    current = top;
                                    pushed = true;
                                    break;
                                }
                            }
                            if (parent != null) parent.ns.put(tparts[i - 1], pm);
                            parent = pm;
                            if (first == null) first = pm;
                            leaf = pm;
                        }
                        if (pushed) break dispatch;                    // resume via retry
                        Object fromlist = f.pop();
                        f.setTop(fromlist instanceof PyObj.Tuple ? leaf : first);
                        break dispatch;
                    }
                    case Opcodes.IMPORT_FROM: {
                        String name = f.code.module.qstr((int) arg);
                        Object m = f.top();
                        if (!(m instanceof PyModule)) throw PyException.typeError("import from requires a module");
                        PyModule pm = (PyModule) m;
                        Object v = pm.ns.get(name);
                        if (v != null) { f.push(v); break dispatch; }
                        // submodule fallback: `from pkg import child` where child is a
                        // module file, not an attribute of pkg's __init__
                        String childDotted = pm.name + "." + name;
                        PyModule child = loadedModules.get(childDotted);
                        if (child == null) {
                            Object[] hit = resolveDotted(pm.root, childDotted);
                            if (hit == null) {
                                throw new PyException("ImportError", "can't import name " + name);
                            }
                            MpyModule code = MpyLoader.load((byte[]) hit[0], new QstrPool());
                            child = new PyModule(childDotted, pm.root, code);
                            loadedModules.put(childDotted, child);
                            byCode.put(code, child);
                            pm.ns.put(name, child);
                            checkDepth();
                            Frame top = new Frame(code.root);
                            top.names = child.ns;
                            top.returnOverride = child;                // snapshot names relink (see above)
                            top.importRetryIp = raiseIp;               // re-run IMPORT_FROM after
                            top.caller = f;
                            current = top;
                            break dispatch;
                        }
                        pm.ns.put(name, child);
                        f.push(child);
                        break dispatch;
                    }
                    case Opcodes.IMPORT_STAR: {
                        Object m = f.pop();
                        if (!(m instanceof PyModule)) throw PyException.typeError("import * requires a module");
                        Map<String, Object> dest = (f.names != null) ? f.names : globals;
                        for (Map.Entry<String, Object> e : ((PyModule) m).ns.entrySet()) {
                            if (!e.getKey().startsWith("_")) dest.put(e.getKey(), e.getValue());
                        }
                        break dispatch;
                    }

                    case Opcodes.LOAD_BUILD_CLASS:
                        f.push(BUILD_CLASS);
                        break dispatch;

                    // ---- exceptions (Phase 6) ----------------------------------
                    case Opcodes.SETUP_EXCEPT:
                    case Opcodes.SETUP_FINALLY: {
                        int i = ++f.excSp;
                        f.excHandler[i] = (int) (f.ip + arg); // labels are forward
                        f.excValSp[i] = f.sp;
                        f.excIsFinally[i] = (op == Opcodes.SETUP_FINALLY);
                        f.excPrevExc[i] = null;
                        break dispatch;
                    }
                    case Opcodes.POP_EXCEPT_JUMP: {
                        f.excSp--;
                        f.ip += arg;
                        break dispatch;
                    }
                    case Opcodes.END_FINALLY: {
                        f.excSp--;           // POP_EXC_BLOCK
                        Object tos = f.top();
                        if (tos == PyObj.NONE) {
                            f.drop(1);       // finally completed normally
                        } else if (tos instanceof Long) {
                            long cause = (Long) f.pop();
                            if (cause < 0) doReturn();          // resume an unwind-return
                            else { f.push(cause); unwindJump(f); } // resume an unwind-jump
                        } else if (tos instanceof PyExc.Instance) {
                            raiseInto((PyExc.Instance) f.top(), null); // re-raise
                        } else {
                            throw PyException.typeError("bad value on stack at END_FINALLY");
                        }
                        break dispatch;
                    }
                    case Opcodes.RAISE_LAST: {
                        // re-raise the innermost saved exception (bare `raise`)
                        PyExc.Instance excObj = null;
                        for (Frame fr = f; fr != null && excObj == null; fr = fr.caller) {
                            for (int e = fr.excSp; e >= 0; e--) {
                                if (fr.excPrevExc[e] != null) { excObj = (PyExc.Instance) fr.excPrevExc[e]; break; }
                            }
                        }
                        if (excObj == null) {
                            excObj = PyExc.RUNTIME_ERROR.make(new Object[]{"no active exception to reraise"});
                        }
                        raiseInto(excObj, null);
                        break dispatch;
                    }
                    case Opcodes.RAISE_OBJ: {
                        raiseInto(toExcInstance(f.pop()), null);
                        break dispatch;
                    }
                    case Opcodes.RAISE_FROM: {
                        f.drop(1);           // discard the `from` cause (not tracked)
                        raiseInto(toExcInstance(f.pop()), null);
                        break dispatch;
                    }
                    case Opcodes.UNWIND_JUMP: {
                        // ip is already past the extra byte; C computes the target
                        // relative to the extra-byte position.
                        long dest = (f.ip - 1) + arg;
                        int extra = bc[f.ip - 1] & 0xff; // handlers to unwind (+0x80: pop iter)
                        f.push(dest);
                        f.push((long) extra);
                        unwindJump(f);
                        break dispatch;
                    }

                    default:
                        throw PyException.notImpl("opcode " + Opcodes.name(op)
                                + String.format(" (0x%02x)", op) + " — arrives in a later phase");
                }
            }

            } catch (PyException pe) {
                // A Java-side runtime error becomes a catchable Python exception;
                // raiseInto rethrows `pe` if no handler exists anywhere. A pe that
                // already escaped a fully-unwound root passes through untouched;
                // one crossing a nested-execution boundary keeps its instance so
                // the traceback continues seamlessly in the outer frames.
                if (pe.escaped) throw pe;
                PyExc.Instance inst = pe.instance != null ? pe.instance : PyExc.from(pe);
                raiseInto(inst, pe);
            }

            // ---- post-instruction bookkeeping ----
            remaining--;
            if (current != f) {          // a call or (non-root) return switched frames
                f = current;
                bc = f.bc;
            }
            if (yielding) break;         // cooperative yield: stop now, keep `remaining`
        }
        return remaining;
    }

    // ---- call handling ------------------------------------------------------

    private static final String[] NO_KW_NAMES = new String[0];
    private static final Object[] NO_KW_VALUES = new Object[0];
    private static final Object[] NO_ARGS = new Object[0];

    /** What LOAD_BUILD_CLASS pushes; calling it builds a class. */
    /** Marker for the VM-native builtin function singletons (len/sorted/... and
     *  BUILD_CLASS etc.) so type()/isinstance can recognize them as functions. */
    public interface NativeBuiltin {}

    /** Synthetic Exception.__init__(self, *args): records args on the instance. */
    public static final Object EXC_INIT = new NativeBuiltin() {
        @Override public String toString() { return "<slot wrapper '__init__' of 'Exception'>"; }
    };
    public static final Object BUILD_CLASS = new NativeBuiltin() {
        @Override public String toString() { return "<build_class>"; }
    };
    /** The next() builtin (needs frame pushes for generators, so not a HostFunction). */
    public static final Object NEXT_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function next>"; }
    };
    /** The list() builtin (drains generators, so not a HostFunction). */
    public static final Object LIST_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function list>"; }
    };
    /** The sum() builtin (drains generators, so not a HostFunction). */
    public static final Object SUM_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function sum>"; }
    };
    /** The super builtin (only consumed by LOAD_SUPER_METHOD's compiled form). */
    public static final Object SUPER_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function super>"; }
    };
    /** sorted()/map()/filter(): may call a user key/func, so VM-native (callSync). */
    public static final Object SORTED_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function sorted>"; }
    };
    public static final Object MAP_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function map>"; }
    };
    public static final Object FILTER_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function filter>"; }
    };
    /** dict(): supports dict(pairs) and dict(**kwargs), so VM-native for kwargs. */
    public static final Object DICT_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function dict>"; }
    };
    /** len(): VM-native so it can dispatch __len__ on user instances. */
    public static final Object LEN_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function len>"; }
    };
    /** bool(): VM-native so it can dispatch __bool__/__len__ on user instances. */
    public static final Object BOOL_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function bool>"; }
    };
    /** all()/any(): VM-native so a generator-expression argument can be drained. */
    public static final Object ALL_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function all>"; }
    };
    public static final Object ANY_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function any>"; }
    };
    /** repr()/print: VM-native so they can dispatch __repr__/__str__. */
    public static final Object REPR_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function repr>"; }
    };
    public static final Object PRINT_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function print>"; }
    };
    /** globals()/locals(): VM-native so they can see the current frame. In
     *  MicroPython a function's locals() returns the globals (locals are stack
     *  slots with no name dict); only a class body has a real local namespace. */
    public static final Object GLOBALS_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function globals>"; }
    };
    public static final Object LOCALS_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function locals>"; }
    };
    public static final Object COMPILE_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function compile>"; }
    };
    public static final Object EXEC_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function exec>"; }
    };
    public static final Object EVAL_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function eval>"; }
    };
    /** uasyncio bridge: register a coroutine on the VM's cooperative event loop. */
    public static final Object CREATE_TASK_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function create_task>"; }
    };
    /** jasyncio bridge: jasyncio.run(coro) — register the coroutine as a task and
     *  BLOCK the main frame until it completes, then return its value (or raise its
     *  exception). Unlike create_task (which returns immediately), run advances the
     *  event loop via the host's step() driver until the awaited task settles. */
    public static final Object RUN_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function run>"; }
    };
    /** Cooperative-lock primitive: atomic test-and-set on a single-element list used
     *  as a lock's flag holder. Executes entirely within one native call (no bytecode
     *  boundaries inside), so it cannot be split by the scheduler's op budget — which
     *  is exactly what makes a busy-wait acquire race-free in this preemptive-at-
     *  instruction-boundaries model. Returns True if it took the lock (was free), else
     *  False. __thread_release resets it. */
    public static final Object TEST_AND_SET_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function _test_and_set>"; }
    };
    /** jasyncio bridge: build a SleepRequest(ms) for sleep_ms() to yield. */
    public static final Object MAKE_SLEEP_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function _make_sleep>"; }
    };
    /** jasyncio bridge: turn on daemon-finish mode — after the main frame returns,
     *  keep running async tasks to completion instead of finishing immediately. */
    public static final Object FINISH_DAEMON_BUILTIN = new NativeBuiltin() {
        @Override public String toString() { return "<built-in function finishDaemonOnExit>"; }
    };

    /** Synchronously exhaust a generator into a list by running its frame as the
     *  root of nested mini-executions (one per yield). Used by list(gen). */
    /** Any iterable -> a Java list, draining generators and __iter__ via the VM. */
    java.util.List<Object> materialize(Object iterable) {
        if (iterable instanceof PyGen) return drainGen((PyGen) iterable).items;
        if (iterable instanceof PyInstance) {
            Object o = iterOf(iterable);
            if (o instanceof PyObj.Iter) {
                java.util.List<Object> out = new java.util.ArrayList<>();
                PyObj.Iter it = (PyObj.Iter) o;
                Object v; while ((v = it.next()) != PyObj.STOP_ITERATION) out.add(v);
                return out;
            }
            if (o instanceof PyGen) return drainGen((PyGen) o).items;
        }
        java.util.List<Object> out = new java.util.ArrayList<>();
        PyObj.Iter it = Ops.getIter(iterable);
        Object v; while ((v = it.next()) != PyObj.STOP_ITERATION) out.add(v);
        return out;
    }

    /** getiter with __iter__ dispatch: an instance's __iter__ result becomes the
     *  iterator (a PyObj.Iter, a PyGen, or another instance with __next__). For a
     *  __next__-driven instance we eagerly drain into a PyObj.Iter. */
    private Object iterOf(Object o) {
        if (o instanceof PyInstance) {
            PyInstance inst = (PyInstance) o;
            // await protocol: `await obj` compiles to GET_ITER + YIELD_FROM, and
            // GET_ITER must honour __await__ when present. __await__ is typically a
            // generator function; calling it must produce a (not-yet-started) generator
            // — never run its body synchronously (its `yield` would misbehave). Mirror
            // callInto's generator-function handling. The generator is then driven live
            // by YIELD_FROM.
            Object aw = inst.cls.lookup("__await__");
            if (aw != null) {
                PyFunction awFn = aw instanceof PyFunction ? (PyFunction) aw
                                : aw instanceof Closure ? ((Closure) aw).fun : null;
                if (awFn != null && (awFn.code.prelude.scopeFlags & SCOPE_GENERATOR) != 0) {
                    Object[] self1 = { inst };
                    return new PyGen(prepareFrame(awFn, self1));
                }
                // non-generator __await__: call it; it returns an iterator/generator
                return callSync(new BoundPyMethod(aw, inst), NO_ARGS);
            }
            Object im = inst.cls.lookup("__iter__");
            if (im != null) {
                Object it = callSync(new BoundPyMethod(im, inst), NO_ARGS);
                if (it instanceof PyObj.Iter || it instanceof PyGen) return it;
                if (it instanceof PyInstance) {          // iterator protocol via __next__
                    PyInstance iit = (PyInstance) it;
                    Object nx = iit.cls.lookup("__next__");
                    if (nx != null) {
                        java.util.List<Object> buf = new java.util.ArrayList<>();
                        while (true) {
                            Object v;
                            try { v = callSync(new BoundPyMethod(nx, iit), NO_ARGS); }
                            catch (PyException e) {
                                if (PyExc.isSub(exFrom(e).type, PyExc.STOP_ITERATION)) break;
                                throw e;
                            }
                            buf.add(v);
                            if (buf.size() > limits.maxIterItems) throw new PyException("RuntimeError", "iterator too long");
                        }
                        return Ops.getIter(new PyObj.PyList(buf));
                    }
                }
                return it;
            }
            Object gm = inst.cls.lookup("__getitem__");
            if (gm != null) {                            // old-style: index 0,1,2,... until IndexError
                java.util.List<Object> buf = new java.util.ArrayList<>();
                long i = 0;
                while (true) {
                    Object v;
                    try { v = callSync(new BoundPyMethod(gm, inst), new Object[]{i++}); }
                    catch (PyException e) {
                        if (PyExc.isSub(exFrom(e).type, PyExc.INDEX_ERROR)) break;
                        throw e;
                    }
                    buf.add(v);
                    if (buf.size() > limits.maxIterItems) throw new PyException("RuntimeError", "iterator too long");
                }
                return Ops.getIter(new PyObj.PyList(buf));
            }
            if (inst.nativeValue != null) return Ops.getIter(inst.nativeValue);  // iterate native str/list
            throw PyException.typeError("'" + inst.cls.name + "' object is not iterable");
        }
        return Ops.getIter(o);
    }
    private static PyExc.Instance exFrom(PyException e) {
        return e.instance != null ? e.instance : PyExc.from(e);
    }

    private PyObj.PyList drainGen(PyGen gen) {
        Frame sc = current; boolean sf = finished; boolean smr = mainReturned; Object sr = returnValue;
        boolean sy = yielding; int sri = raiseIp;
        PyObj.PyList out = new PyObj.PyList();
        long fuel = limits.syncFuel;                 // spans the WHOLE drain
        try {
            while (!gen.done) {
                if (!gen.started) gen.started = true;
                else gen.frame.setTop(PyObj.NONE);
                gen.resumeMode = PyGen.MODE_CALL;
                current = gen.frame;
                finished = false;
                returnValue = null;
                try {
                    while (!finished) {
                        int batch = (int) Math.min(1 << 20, fuel);
                        if (batch <= 0) throw new PyException("RuntimeError",
                                "runaway generator in list(): sync fuel exhausted");
                        fuel -= batch - runLoop(batch);
                    }
                } catch (PyException pe) {
                    pe.escaped = false;   // let the outer frame's handlers see it
                    throw pe;
                }
                if (!gen.done) out.items.add(returnValue);   // the yielded value
            }
        } finally {
            current = sc; finished = sf; mainReturned = smr; returnValue = sr; yielding = sy; raiseIp = sri;
        }
        return out;
    }

    /** Resume a generator: attach its frame to the current one and continue there.
     *  mode says where yields/returns deliver; exhaustIp is FOR_ITER's jump target. */
    private void resumeGen(PyGen gen, int mode, int exhaustIp, Object sendValue) {
        gen.resumeMode = mode;
        gen.forIterExhaustIp = exhaustIp;
        if (gen.done) {
            genFinished(gen, PyObj.NONE);
            return;
        }
        if (current == gen.frame || gen.frame.caller != null) {
            throw PyException.valueError("generator already executing");
        }
        if (!gen.started) {
            if (sendValue != PyObj.NONE) {
                throw PyException.typeError("can't send non-None value to a just-started generator");
            }
            gen.started = true;
        } else {
            gen.frame.setTop(sendValue);   // replaces the yielded value at the gen's TOS
        }
        checkDepth();
        gen.frame.caller = current;
        current = gen.frame;
    }

    /** Deliver a yielded value (at genFrame's TOS) out through any chain of
     *  delegating (yield from) generators to the ultimate resumer. */
    private void deliverYield(Frame genFrame) {
        while (true) {
            Object v = genFrame.top();          // stays on the gen's stack (C convention)
            PyGen gen = genFrame.genOwner;
            // task-driver mode: the scheduled coroutine itself (not a sub-generator
            // it delegates to) suspending cooperatively. Record the yielded value
            // and end the drive batch without finishing the whole VM; the task keeps
            // its frame state and its seat in the scheduler.
            if (drivingTask != null && drivingTask.coro == gen) {
                genFrame.caller = null;
                drivingTask.lastYield = v;
                taskYielded = true;
                return;
            }
            Frame resumer = genFrame.caller;
            genFrame.caller = null;             // detach while suspended
            if (resumer == null) { returnValue = v; finished = true; return; }
            if (gen.resumeMode == PyGen.MODE_FOR_ITER) { current = resumer; resumer.push(v); return; }
            if (gen.resumeMode == PyGen.MODE_CALL) { current = resumer; resumer.setTop(v); return; }
            // MODE_YIELD_FROM: the resumer is a gen frame suspended at YIELD_FROM
            resumer.ip -= 1;    // re-execute YIELD_FROM on its next resume
            resumer.push(v);    // the outer gen's own yielded value (its new TOS)
            genFrame = resumer; // and yield out of IT too
        }
    }

    /** Deliver generator exhaustion to the resumer (FOR_ITER exhaust or StopIteration). */
    private void genFinished(PyGen gen, Object value) {
        gen.done = true;
        // task-driver mode: a scheduled coroutine finished — hand its result to the
        // task instead of raising StopIteration into a (nonexistent) resumer.
        if (drivingTask != null && drivingTask.coro == gen) {
            drivingTask.result = value;
            drivingTask.done = true;
            finished = true;    // stop this drive batch
            return;
        }
        if (gen.resumeMode == PyGen.MODE_FOR_ITER) {
            current.sp -= NSLOTS;          // pop the iterator buffer
            current.ip = gen.forIterExhaustIp;
        } else if (gen.resumeMode == PyGen.MODE_YIELD_FROM) {
            current.setTop(value);         // the yield-from expression's value
        } else {
            Object[] a = (value == PyObj.NONE) ? new Object[0] : new Object[]{value};
            // StopIteration is raised into the *resumer* (the send()/next() call
            // site), so the handler search must test against the resumer's ip, not
            // the finished generator's stale raiseIp — otherwise a resumer whose
            // try/except sits before its own current ip would be skipped (seen when
            // a coroutine driven by send() finishes after delegating via await).
            raiseIp = current.ip;
            raiseInto(PyExc.STOP_ITERATION.make(a), null);
        }
    }

    /** Build an argless frame for a PyFunction or Closure (class bodies). */
    private Frame frameForCallable(Object callable) {
        if (callable instanceof PyFunction) return prepareFrame((PyFunction) callable, new Object[0]);
        if (callable instanceof Closure) {
            Closure c = (Closure) callable;
            return prepareFrame(c.fun, c.closed.clone());
        }
        throw PyException.typeError("expected a function");
    }

    /** The global scope for a frame: its module's namespace for imported-module
     *  code, else the VM's main globals. */
    private Map<String, Object> scopeOf(Frame f) {
        if (f.execGlobals != null) return f.execGlobals;   // exec(code, ns)
        Map<String, Object> ns = f.code.module.ns;
        return ns != null ? ns : globals;
    }

    /** Enforce the frame-depth limit before attaching a new frame to `current`. */
    private void checkDepth() {
        int depth = 0;
        for (Frame f = current; f != null; f = f.caller) {
            if (++depth >= limits.maxFrameDepth) {
                throw new PyException("RuntimeError", "maximum recursion depth exceeded");
            }
        }
    }

    /** Expand a VAR_KW call's stack segment: bitmap bit i means positional arg i is
     *  a *seq to splat; a null kw key means the value is a **dict to splat. */
    private Object[] expandVarKw(Frame f, int argsBase, int nPos, int nKw, long bitmap) {
        java.util.List<Object> pos = new java.util.ArrayList<>();
        for (int i = 0; i < nPos; i++) {
            Object a = f.state[argsBase + i];
            if (((bitmap >> i) & 1) != 0) {
                if (a instanceof PyGen) { pos.addAll(drainGen((PyGen) a).items); continue; }
                PyObj.Iter it = Ops.getIter(a);
                Object v;
                while ((v = it.next()) != PyObj.STOP_ITERATION) pos.add(v);
            } else {
                pos.add(a);
            }
        }
        java.util.List<String> kn = new java.util.ArrayList<>();
        java.util.List<Object> kv = new java.util.ArrayList<>();
        for (int k = 0; k < nKw; k++) {
            Object key = f.state[argsBase + nPos + 2 * k];
            Object val = f.state[argsBase + nPos + 2 * k + 1];
            if (key == null) {                       // **dict splat (LOAD_NULL key)
                if (!(val instanceof PyObj.PyDict)) throw PyException.typeError("argument after ** must be a dict");
                for (var e : ((PyObj.PyDict) val).map.entrySet()) {
                    if (!(e.getKey() instanceof String)) throw PyException.typeError("keywords must be strings");
                    kn.add((String) e.getKey());
                    kv.add(e.getValue());
                }
            } else {
                kn.add((String) key);
                kv.add(val);
            }
        }
        return new Object[]{pos.toArray(), kn.toArray(new String[0]), kv.toArray()};
    }

    /** Synchronously call a callable to completion via a nested mini-execution
     *  (used by the with-protocol's __enter__/__exit__). */
    /** BINARY_OP with instance-dunder dispatch. If either operand is a user
     *  instance defining the relevant dunder (or reflected dunder), call it;
     *  otherwise fall back to the structural Ops.binaryOp. Comparison ops also
     *  consult __eq__/__lt__/... and their reflections. */
    Object binaryOpDispatch(int op, Object lhs, Object rhs) {
        // pass-through for operators that never dispatch to user dunders here
        // (is / in / exception-match are handled structurally)
        if (op == Ops.IS || op == Ops.EXCEPTION_MATCH) {
            if (op == Ops.EXCEPTION_MATCH) {
                // an except clause may name a user exception class (PyClass with an
                // excType) or a tuple containing such; unwrap to the PyExc.Type(s).
                rhs = unwrapExcSpec(rhs);
            }
            return Ops.binaryOp(op, lhs, rhs);
        }

        boolean lInst = lhs instanceof PyInstance;
        boolean rInst = rhs instanceof PyInstance;
        if (!lInst && !rInst) return Ops.binaryOp(op, lhs, rhs);

        // built-in subclass passthrough: if an operand is a native-subclass
        // instance with no user dunder for this op, operate on its native value
        // (result is the base type, matching MicroPython: MyStr('a')+'b' -> str).
        {
            String d = (op == Ops.IN) ? "__contains__" : (comparisonDunder(op) != null ? comparisonDunder(op) : arithDunder(op));
            if (d != null) {
                Object ln = lInst ? nativeFallback(lhs, d) : null;
                Object rn = rInst ? nativeFallback(rhs, d) : null;
                if (ln != null || rn != null) {
                    Object L = ln != null ? ln : lhs;
                    Object R = rn != null ? rn : rhs;
                    // avoid infinite recursion: if unwrapped operands are no longer
                    // instances, go straight to structural Ops
                    if (!(L instanceof PyInstance) && !(R instanceof PyInstance))
                        return Ops.binaryOp(op, L, R);
                    return binaryOpDispatch(op, L, R);
                }
            }
        }

        // membership: `x in y` -> y.__contains__(x)
        if (op == Ops.IN) {
            if (rInst) {
                Object m = ((PyInstance) rhs).cls.lookup("__contains__");
                if (m != null) return truthyBool(callSync(new BoundPyMethod(m, rhs), new Object[]{lhs}));
                // fall back to iterating rhs if it defines __iter__
                Object it = ((PyInstance) rhs).cls.lookup("__iter__");
                if (it != null) {
                    for (Object v : materialize(rhs)) if (Ops.pyEquals(v, lhs)) return Boolean.TRUE;
                    return Boolean.FALSE;
                }
            }
            return Ops.binaryOp(op, lhs, rhs);
        }

        // comparison operators
        String cmp = comparisonDunder(op);
        if (cmp != null) {
            if (lInst) {
                Object m = ((PyInstance) lhs).cls.lookup(cmp);
                if (m != null) {
                    Object r = callSync(new BoundPyMethod(m, lhs), new Object[]{rhs});
                    if (r != PyObj.NOT_IMPLEMENTED) return r;
                }
                // MicroPython derives __ne__ from __eq__ (negated)
                if (op == Ops.NOT_EQUAL) {
                    Object eq = ((PyInstance) lhs).cls.lookup("__eq__");
                    if (eq != null) {
                        Object r = callSync(new BoundPyMethod(eq, lhs), new Object[]{rhs});
                        if (r != PyObj.NOT_IMPLEMENTED) return Boolean.valueOf(!truthy(r));
                    }
                }
            }
            // MicroPython does NOT reflect comparisons (b>a does not try a.__lt__)
            if (op == Ops.EQUAL) return Boolean.valueOf(lhs == rhs);
            if (op == Ops.NOT_EQUAL) return Boolean.valueOf(lhs != rhs);
            throw PyException.typeError("unsupported types for " + cmp + ": '"
                    + StdLib.typeName(lhs) + "', '" + StdLib.typeName(rhs) + "'");
        }

        // arithmetic / bitwise: try __op__ on lhs, then reflected __rop__ on rhs
        String fwd = arithDunder(op);
        if (fwd != null) {
            if (lInst) {
                Object m = ((PyInstance) lhs).cls.lookup(fwd);
                if (m != null) {
                    Object r = callSync(new BoundPyMethod(m, lhs), new Object[]{rhs});
                    if (r != PyObj.NOT_IMPLEMENTED) return r;
                }
            }
            if (rInst) {
                Object m = ((PyInstance) rhs).cls.lookup(reflectedArith(op));
                if (m != null) {
                    Object r = callSync(new BoundPyMethod(m, rhs), new Object[]{lhs});
                    if (r != PyObj.NOT_IMPLEMENTED) return r;
                }
            }
            throw PyException.typeError("unsupported operand type(s) for "
                    + StdLib.typeName(lhs) + " and " + StdLib.typeName(rhs));
        }
        return Ops.binaryOp(op, lhs, rhs);
    }

    private boolean truthyBool(Object o) { return truthy(o) ? Boolean.TRUE : Boolean.FALSE; }

    /** Resolve an except-clause spec: a user exception PyClass -> its PyExc.Type,
     *  a tuple -> a tuple with each such class replaced. Leaves PyExc.Type as-is. */
    private static Object unwrapExcSpec(Object spec) {
        if (spec instanceof PyClass && ((PyClass) spec).excType != null)
            return ((PyClass) spec).excType;
        if (spec instanceof PyObj.Tuple) {
            Object[] items = ((PyObj.Tuple) spec).items;
            Object[] out = new Object[items.length];
            boolean changed = false;
            for (int i = 0; i < items.length; i++) {
                if (items[i] instanceof PyClass && ((PyClass) items[i]).excType != null) {
                    out[i] = ((PyClass) items[i]).excType; changed = true;
                } else out[i] = items[i];
            }
            return changed ? new PyObj.Tuple(out) : spec;
        }
        return spec;
    }

    /** The wrapped native value of a built-in subclass instance, or null. */
    static Object nativeValueOf(Object o) {
        return (o instanceof PyInstance) ? ((PyInstance) o).nativeValue : null;
    }
    /** If o is a native-subclass instance with no user override of `dunder`,
     *  return its native value to operate on; else null (use normal dispatch). */
    private Object nativeFallback(Object o, String dunder) {
        if (o instanceof PyInstance) {
            PyInstance inst = (PyInstance) o;
            if (inst.nativeValue != null && inst.cls.lookup(dunder) == null) return inst.nativeValue;
        }
        return null;
    }

    /** str()/repr() with __str__/__repr__ dispatch on user instances. repr falls
     *  back to __repr__ if __str__ is absent (str() does too, like Python). */
    String stringify(Object o, boolean useRepr) {
        if (o instanceof PyInstance) {
            PyInstance inst = (PyInstance) o;
            Object m = useRepr ? inst.cls.lookup("__repr__")
                               : firstNonNull(inst.cls.lookup("__str__"), inst.cls.lookup("__repr__"));
            if (m != null) {
                Object r = callSync(new BoundPyMethod(m, inst), NO_ARGS);
                return r instanceof String ? (String) r : Builtins.str(r);
            }
            if (inst.nativeValue != null)            // built-in subclass reprs/strs as native value
                return stringify(inst.nativeValue, useRepr);
            return PyObj.repr(o);   // no __str__/__repr__ -> <X object>
        }
        // containers: recurse so element __repr__ is dispatched (repr form inside)
        if (o instanceof PyObj.PyList) return joinSeq(((PyObj.PyList) o).items, "[", "]");
        if (o instanceof PyObj.Tuple) {
            PyObj.Tuple t = (PyObj.Tuple) o;
            if (t.fieldNames == null) {
                Object[] it = t.items;
                if (it.length == 1) return "(" + stringify(it[0], true) + ",)";
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < it.length; i++) { if (i>0) sb.append(", "); sb.append(stringify(it[i], true)); }
                return sb.append(")").toString();
            }
        }
        if (o instanceof PyObj.PyDict) {
            var m = ((PyObj.PyDict) o).map;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(", "); first = false;
                sb.append(stringify(e.getKey(), true)).append(": ").append(stringify(e.getValue(), true));
            }
            return sb.append("}").toString();
        }
        return useRepr ? PyObj.repr(o) : Builtins.str(o);
    }
    private String joinSeq(java.util.List<Object> xs, String open, String close) {
        StringBuilder sb = new StringBuilder(open);
        for (int i = 0; i < xs.size(); i++) { if (i>0) sb.append(", "); sb.append(stringify(xs.get(i), true)); }
        return sb.append(close).toString();
    }
    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }

    private static String comparisonDunder(int op) {
        switch (op) {
            case Ops.EQUAL: return "__eq__";
            case Ops.NOT_EQUAL: return "__ne__";
            case Ops.LESS: return "__lt__";
            case Ops.MORE: return "__gt__";
            case Ops.LESS_EQUAL: return "__le__";
            case Ops.MORE_EQUAL: return "__ge__";
            default: return null;
        }
    }
    private static String unaryDunder(int op) {
        switch (op) {
            case Ops.NEGATIVE: return "__neg__";
            case Ops.POSITIVE: return "__pos__";
            case Ops.INVERT: return "__invert__";
            default: return null;
        }
    }
    private static String arithDunder(int op) {
        switch (op) {
            case Ops.ADD: return "__add__";
            case Ops.SUBTRACT: return "__sub__";
            case Ops.MULTIPLY: return "__mul__";
            case Ops.TRUE_DIVIDE: return "__truediv__";
            case Ops.FLOOR_DIVIDE: return "__floordiv__";
            case Ops.MODULO: return "__mod__";
            case Ops.POWER: return "__pow__";
            case Ops.MAT_MULTIPLY: return "__matmul__";
            case Ops.AND: return "__and__";
            case Ops.OR: return "__or__";
            case Ops.XOR: return "__xor__";
            case Ops.LSHIFT: return "__lshift__";
            case Ops.RSHIFT: return "__rshift__";
            default: return null;
        }
    }
    private static String reflectedArith(int op) {
        switch (op) {
            case Ops.ADD: return "__radd__";
            case Ops.SUBTRACT: return "__rsub__";
            case Ops.MULTIPLY: return "__rmul__";
            case Ops.TRUE_DIVIDE: return "__rtruediv__";
            case Ops.FLOOR_DIVIDE: return "__rfloordiv__";
            case Ops.MODULO: return "__rmod__";
            case Ops.POWER: return "__rpow__";
            case Ops.MAT_MULTIPLY: return "__rmatmul__";
            case Ops.AND: return "__rand__";
            case Ops.OR: return "__ror__";
            case Ops.XOR: return "__rxor__";
            case Ops.LSHIFT: return "__rlshift__";
            case Ops.RSHIFT: return "__rrshift__";
            default: return null;
        }
    }

    /** Python truthiness with dunder dispatch: __bool__ then __len__ on user    /** Python truthiness with dunder dispatch: __bool__ then __len__ on user
     *  instances, otherwise the structural rule in Ops.isTrue. */
    boolean truthy(Object o) {
        if (o instanceof PyInstance) {
            PyInstance inst = (PyInstance) o;
            Object b = inst.cls.lookup("__bool__");
            if (b != null) return Ops.isTrue(callSync(new BoundPyMethod(b, inst), NO_ARGS));
            Object l = inst.cls.lookup("__len__");
            if (l != null) return Ops.isTrue(callSync(new BoundPyMethod(l, inst), NO_ARGS));
            return true;
        }
        return Ops.isTrue(o);
    }

    private Object callSync(Object callable, Object[] args) {
        if (callable instanceof BuiltinType && ((BuiltinType) callable).isConstructor())
            return ((BuiltinType) callable).construct(this, args, NO_KW_NAMES, NO_KW_VALUES);
        if (callable instanceof HostFunction) return ((HostFunction) callable).call(args);
        if (callable instanceof Methods.BoundMethod) return ((Methods.BoundMethod) callable).call(args);
        // native builtin singletons (len/sorted/map/filter/dict/list/sum/...) that
        // are dispatched specially in callInto: run them via a one-off nested call
        // so they're usable as first-class callables (e.g. sorted(key=len)).
        if (callable == LEN_BUILTIN || callable == BOOL_BUILTIN || callable == ALL_BUILTIN || callable == ANY_BUILTIN || callable == REPR_BUILTIN || callable == PRINT_BUILTIN || callable == GLOBALS_BUILTIN || callable == LOCALS_BUILTIN || callable == COMPILE_BUILTIN || callable == EXEC_BUILTIN || callable == EVAL_BUILTIN || callable == CREATE_TASK_BUILTIN || callable == RUN_BUILTIN || callable == TEST_AND_SET_BUILTIN || callable == MAKE_SLEEP_BUILTIN || callable == FINISH_DAEMON_BUILTIN || callable == SORTED_BUILTIN || callable == MAP_BUILTIN
                || callable == FILTER_BUILTIN || callable == DICT_BUILTIN || callable == LIST_BUILTIN
                || callable == SUM_BUILTIN) {
            Frame sc = current; boolean sf = finished; boolean smr = mainReturned; Object sr = returnValue;
            boolean sy = yielding; int sri = raiseIp;
            Frame probe = new Frame(current.code);   // scratch frame to receive setTop
            probe.push(callable);
            try {
                current = probe;
                callInto(callable, args, NO_KW_NAMES, NO_KW_VALUES);
                return probe.top();
            } finally {
                current = sc; finished = sf; mainReturned = smr; returnValue = sr; yielding = sy; raiseIp = sri;
            }
        }
        Object fn = callable;
        Object[] a = args;
        if (fn instanceof BoundPyMethod) {
            BoundPyMethod bm = (BoundPyMethod) fn;
            Object[] all = new Object[a.length + 1];
            all[0] = bm.self;
            System.arraycopy(a, 0, all, 1, a.length);
            a = all;
            fn = bm.fn;
        }
        if (fn instanceof Closure) {
            Closure c = (Closure) fn;
            Object[] all = new Object[c.closed.length + a.length];
            System.arraycopy(c.closed, 0, all, 0, c.closed.length);
            System.arraycopy(a, 0, all, c.closed.length, a.length);
            a = all;
            fn = c.fun;
        }
        if (!(fn instanceof PyFunction)) throw PyException.typeError("object is not callable");
        Frame fr = prepareFrame((PyFunction) fn, a);
        Frame sc = current; boolean sf = finished; boolean smr = mainReturned; Object sr = returnValue;
        boolean sy = yielding; int sri = raiseIp;
        try {
            current = fr; finished = false; returnValue = null;
            long fuel = limits.syncFuel;
            try {
                while (!finished) {
                    int batch = (int) Math.min(1 << 20, fuel);
                    if (batch <= 0) throw new PyException("RuntimeError",
                            "runaway call: sync fuel exhausted");
                    fuel -= batch - runLoop(batch);
                }
            } catch (PyException pe) {
                pe.escaped = false;   // let the outer frame's handlers see it
                throw pe;
            }
            return returnValue;
        } finally {
            current = sc; finished = sf; mainReturned = smr; returnValue = sr; yielding = sy; raiseIp = sri;
        }
    }

    /** LOAD_ATTR semantics: instance attrs, class-chain methods (bound), class
     *  namespace entries, and built-in type methods (bound). */
    private Object loadAttr(Object obj, String name) {
        if (obj instanceof PyObj.Complex) {
            PyObj.Complex c = (PyObj.Complex) obj;
            if (name.equals("real")) return c.re;
            if (name.equals("imag")) return c.im;
            if (name.equals("conjugate")) return new Methods.BoundMethod(obj, "conjugate");
            throw new PyException("AttributeError", "'complex' object has no attribute '" + name + "'");
        }
        if (obj instanceof PyInstance) {
            PyInstance inst = (PyInstance) obj;
            if (name.equals("__dict__")) {           // read-only snapshot of instance attrs
                PyObj.PyDict d = new PyObj.PyDict();
                d.map.putAll(inst.attrs);
                return d;
            }
            Object v = inst.attrs.get(name);
            if (v != null) return v;
            Object m = inst.cls.lookup(name);
            if (m == null) {
                if (inst.nativeValue != null && Methods.lookup(inst.nativeValue, name) != null)
                    return new Methods.BoundMethod(inst.nativeValue, name);
                throw new PyException("AttributeError",
                        "'" + inst.cls.name + "' object has no attribute '" + name + "'");
            }
            if (m instanceof Descriptors.Property) return callSync(((Descriptors.Property) m).getter, new Object[]{inst});
            if (m instanceof Descriptors.StaticMethod) return ((Descriptors.StaticMethod) m).fn;
            if (m instanceof Descriptors.ClassMethod) return new BoundPyMethod(((Descriptors.ClassMethod) m).fn, inst.cls);
            if (m instanceof PyFunction || m instanceof Closure) return new BoundPyMethod(m, inst);
            return m;
        }
        if (obj instanceof PyExc.Type) {
            if (name.equals("__name__")) return ((PyExc.Type) obj).name;
            throw new PyException("AttributeError",
                    "type object '" + ((PyExc.Type) obj).name + "' has no attribute '" + name + "'");
        }
        if (obj instanceof BuiltinType) {
            if (name.equals("__name__")) return ((BuiltinType) obj).name;
            throw new PyException("AttributeError",
                    "type object '" + ((BuiltinType) obj).name + "' has no attribute '" + name + "'");
        }
        if (obj instanceof PyClass) {
            if (name.equals("__name__")) return ((PyClass) obj).name;
            Object m = ((PyClass) obj).lookup(name);
            if (m == null) throw new PyException("AttributeError",
                    "type object '" + ((PyClass) obj).name + "' has no attribute '" + name + "'");
            if (m instanceof Descriptors.StaticMethod) return ((Descriptors.StaticMethod) m).fn;
            if (m instanceof Descriptors.ClassMethod) return new BoundPyMethod(((Descriptors.ClassMethod) m).fn, obj);
            if (m instanceof Descriptors.Property) return m;   // C.p returns the property object itself
            return m;
        }
        if (obj instanceof PyModule) {
            Object v = ((PyModule) obj).ns.get(name);
            if (v == null) throw new PyException("AttributeError",
                    "module '" + ((PyModule) obj).name + "' has no attribute '" + name + "'");
            return v;
        }
        if (obj instanceof PyObj.Tuple && ((PyObj.Tuple) obj).fieldNames != null) {
            PyObj.Tuple t = (PyObj.Tuple) obj;
            for (int i = 0; i < t.fieldNames.length; i++)
                if (t.fieldNames[i].equals(name)) return t.items[i];
            throw new PyException("AttributeError", "'" + t.typeName + "' object has no attribute '" + name + "'");
        }
        if (obj instanceof PyExc.Instance) {
            PyExc.Instance e = (PyExc.Instance) obj;
            if (name.equals("args")) return new PyObj.Tuple(e.args);
            if (name.equals("value")) {   // StopIteration.value: None if no args, the arg if one
                if (e.args.length == 0) return PyObj.NONE;
                if (e.args.length == 1) return e.args[0];
                return new PyObj.Tuple(e.args);
            }
            // user exception subclass: custom instance attrs, then class methods
            Object ua = e.userAttrs.get(name);
            if (ua != null) return ua;
            if (e.type.userClass != null) {
                Object m = ((PyClass) e.type.userClass).lookup(name);
                if (m instanceof PyFunction || m instanceof Closure) return new BoundPyMethod(m, e);
                if (m != null) return m;
            }
            throw new PyException("AttributeError", "'" + e.type.name + "' object has no attribute '" + name + "'");
        }
        if (Methods.lookup(obj, name) != null) return new Methods.BoundMethod(obj, name);
        throw new PyException("AttributeError", "'" + Methods.typeName(obj)
                + "' object has no attribute '" + name + "'");
    }

    /** Dispatch a call: push a bytecode frame, or invoke a host callable in place. */
    private void callInto(Object callable, Object[] args) {
        callInto(callable, args, NO_KW_NAMES, NO_KW_VALUES);
    }

    private void callInto(Object callable, Object[] args, String[] kwNames, Object[] kwValues) {
        if (callable instanceof PyFunction) {
            PyFunction fn = (PyFunction) callable;
            if ((fn.code.prelude.scopeFlags & SCOPE_GENERATOR) != 0) {
                // calling a generator function creates a (not yet started) generator
                current.setTop(new PyGen(prepareFrame(fn, args, kwNames, kwValues)));
                return;
            }
            checkDepth();
            Frame callee = prepareFrame(fn, args, kwNames, kwValues);
            callee.caller = current;
            current = callee;
            return;
        }
        if (callable instanceof Closure) {
            Closure c = (Closure) callable;
            Object[] all = new Object[c.closed.length + args.length];
            System.arraycopy(c.closed, 0, all, 0, c.closed.length);
            System.arraycopy(args, 0, all, c.closed.length, args.length);
            callInto(c.fun, all, kwNames, kwValues);   // cells become leading args
            return;
        }
        if (callable instanceof BoundPyMethod) {
            BoundPyMethod bm = (BoundPyMethod) callable;
            Object[] all = new Object[args.length + 1];
            all[0] = bm.self;
            System.arraycopy(args, 0, all, 1, args.length);
            callInto(bm.fn, all, kwNames, kwValues);
            return;
        }
        if (callable == EXC_INIT) {
            if (args.length >= 1 && args[0] instanceof PyExc.Instance) {
                Object[] rest = new Object[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                ((PyExc.Instance) args[0]).args = rest;
            }
            current.setTop(PyObj.NONE);
            return;
        }
        if (callable == BUILD_CLASS) {
            // build_class(body_fn, name, *bases)
            java.util.List<PyClass> baseList = new java.util.ArrayList<>();
            BuiltinType nativeBase = null;
            PyExc.Type excBase = null;
            for (int i = 2; i < args.length; i++) {
                if (args[i] instanceof PyClass) {
                    PyClass pb = (PyClass) args[i];
                    baseList.add(pb);
                    if (pb.excType != null) excBase = pb.excType;   // inherit exc-ness from a user exc base
                } else if (args[i] instanceof BuiltinType && ((BuiltinType) args[i]).canSubclass()) {
                    nativeBase = (BuiltinType) args[i];   // e.g. class MyStr(str)
                } else if (args[i] instanceof PyExc.Type) {
                    excBase = (PyExc.Type) args[i];       // e.g. class MyError(Exception)
                } else {
                    throw PyException.typeError("can only inherit from classes");
                }
            }
            PyClass cls = new PyClass((String) args[1], baseList.toArray(new PyClass[0]));
            if (nativeBase != null) cls.nativeBase = nativeBase;
            if (excBase != null) {
                // create a PyExc.Type for this user exception, chained to its base,
                // and back-link the PyClass so methods/__init__ resolve.
                PyExc.Type et = new PyExc.Type((String) args[1], excBase, cls);
                cls.excType = et;
            }
            checkDepth();
            Frame body = frameForCallable(args[0]);
            body.names = cls.ns;         // class body STORE_NAMEs go here
            body.buildingClass = cls;    // fills __class__ cell; the class is the result
            body.caller = current;
            current = body;
            return;
        }
        if (callable instanceof PyClass) {
            // instantiate: create the instance, then run __init__ (if any) with the
            // instance delivered as the call's result via returnOverride
            PyClass cls = (PyClass) callable;
            // user exception subclass: produce a PyExc.Instance carrying the exc type
            if (cls.excType != null) {
                PyExc.Instance exc = new PyExc.Instance(cls.excType, args);
                Object einit = cls.lookup("__init__");
                if (einit == null) { current.setTop(exc); return; }   // args become exc.args
                Object[] eall = new Object[args.length + 1];
                eall[0] = exc;
                System.arraycopy(args, 0, eall, 1, args.length);
                callInto(einit, eall, kwNames, kwValues);
                current.returnOverride = exc;
                return;
            }
            PyInstance inst = new PyInstance(cls);
            // subclass of a built-in: seed the native value from the argument
            if (cls.nativeBase != null) {
                inst.nativeValue = cls.nativeBase.construct(this,
                        args.length == 0 ? NO_ARGS : new Object[]{args[0]}, NO_KW_NAMES, NO_KW_VALUES);
            }
            Object init = cls.lookup("__init__");
            if (init == null) {
                // no __init__: for a native subclass the value is already seeded; for a
                // plain class, extra args are an error
                if (cls.nativeBase == null && (args.length != 0 || kwNames.length != 0))
                    throw PyException.typeError(cls.name + "() takes no arguments");
                current.setTop(inst);
                return;
            }
            Object[] all = new Object[args.length + 1];
            all[0] = inst;
            System.arraycopy(args, 0, all, 1, args.length);
            callInto(init, all, kwNames, kwValues);
            current.returnOverride = inst;   // the frame just pushed by callInto
            return;
        }
        if (callable == LIST_BUILTIN) {
            if (args.length == 0) { current.setTop(new PyObj.PyList()); return; }
            if (args.length != 1) throw PyException.typeError("list expected at most 1 argument");
            if (args[0] instanceof PyGen) { current.setTop(drainGen((PyGen) args[0])); return; }
            java.util.List<Object> out = new java.util.ArrayList<>();
            PyObj.Iter it = Ops.getIter(args[0]);
            Object v;
            while ((v = it.next()) != PyObj.STOP_ITERATION) out.add(v);
            current.setTop(new PyObj.PyList(out));
            return;
        }
        if (callable == MAKE_SLEEP_BUILTIN) {
            long ms = args.length >= 1 ? asLong(args[0]) : 0;
            current.setTop(new SleepRequest(ms));
            return;
        }
        if (callable == FINISH_DAEMON_BUILTIN) {
            daemonFinishOnExit = true;   // keep running async tasks past main-frame exit
            current.setTop(PyObj.NONE);
            return;
        }
        if (callable == CREATE_TASK_BUILTIN) {
            if (args.length < 1 || !(args[0] instanceof PyGen))
                throw PyException.typeError("create_task() requires a coroutine");
            registerTask((PyGen) args[0]);
            current.setTop(args[0]);   // return the coroutine as the task handle
            return;
        }
        if (callable == TEST_AND_SET_BUILTIN) {
            // atomic acquire: args[0] is a 1-element list [flag]; if flag is falsy,
            // set it to True and return True, else return False. One native step =
            // no interleaving, so the check-and-set is race-free under budget-suspend.
            if (args.length < 1 || !(args[0] instanceof PyObj.PyList))
                throw PyException.typeError("_test_and_set() requires a list");
            PyObj.PyList holder = (PyObj.PyList) args[0];
            if (holder.items.isEmpty())
                throw PyException.valueError("_test_and_set() list must be non-empty");
            Object cur = holder.items.get(0);
            boolean free = (cur == null) || (cur == PyObj.NONE)
                    || (cur instanceof Boolean && !((Boolean) cur));
            if (free) {
                holder.items.set(0, Boolean.TRUE);
                current.setTop(Boolean.TRUE);
            } else {
                current.setTop(Boolean.FALSE);
            }
            return;
        }
        if (callable == RUN_BUILTIN) {
            if (args.length < 1 || !(args[0] instanceof PyGen))
                throw PyException.typeError("run() requires a coroutine");
            // run() is only meaningful from the top-level (main) frame — it blocks it.
            // Nesting run() inside a coroutine is a programming error (as in CPython).
            if (current.genOwner != null)
                throw new PyException("RuntimeError", "run() cannot be called from a running coroutine");
            AsyncTask t = registerTask((PyGen) args[0]);
            mainWaitingTask = t;
            // Leave a placeholder on the main frame's TOS; step() will overwrite it
            // with the task's result once it settles and resume the main frame.
            current.setTop(PyObj.NONE);
            // Suspend the main frame: abort this run batch so the host's next step()
            // drives async. The main frame's ip already points past this call.
            requestYield();
            return;
        }
        if (callable == COMPILE_BUILTIN) {
            if (compiler == null) throw new PyException("NotImplementedError", "compile() is not available (no compiler configured)");
            String src = asStr(args, 0, "compile");
            String fname = args.length > 1 ? asStr(args, 1, "compile") : "<string>";
            String mode = args.length > 2 ? asStr(args, 2, "compile") : "exec";
            current.setTop(compileSource(src, fname, mode));
            return;
        }
        if (callable == EXEC_BUILTIN || callable == EVAL_BUILTIN) {
            boolean isEval = callable == EVAL_BUILTIN;
            Object arg0 = args.length > 0 ? args[0] : PyObj.NONE;
            CompiledCode code;
            if (arg0 instanceof CompiledCode) {
                code = (CompiledCode) arg0;
            } else if (arg0 instanceof String) {
                if (compiler == null) throw new PyException("NotImplementedError",
                        (isEval ? "eval()" : "exec()") + " is not available (no compiler configured)");
                // eval captures the expression's value: wrap it as an assignment to
                // a reserved global, then read that back after the unit runs. (Keeps
                // the value that a bare top-level expression would otherwise discard.)
                String src = isEval ? (EVAL_RESULT_NAME + " = (" + (String) arg0 + ")") : (String) arg0;
                code = compileSource(src, "<string>", isEval ? "eval" : "exec");
            } else {
                throw PyException.typeError((isEval ? "eval()" : "exec()") + " arg 1 must be a string or code object");
            }
            // optional namespace: exec(code, ns) runs against ns (a dict) instead of globals
            Map<String, Object> ns = null;
            if (args.length > 1 && args[1] instanceof PyObj.PyDict) ns = dictAsGlobals((PyObj.PyDict) args[1]);
            runCompiled(code, ns, isEval);
            return;
        }
        if (callable == GLOBALS_BUILTIN) {
            current.setTop(new PyObj.PyDict(userGlobalsView()));
            return;
        }
        if (callable == LOCALS_BUILTIN) {
            // class body -> its namespace; otherwise (function/module) -> globals
            if (current.names != null) current.setTop(new PyObj.PyDict((java.util.Map) current.names));
            else current.setTop(new PyObj.PyDict(userGlobalsView()));
            return;
        }
        if (callable == REPR_BUILTIN) {
            current.setTop(stringify(args[0], true));
            return;
        }
        if (callable == PRINT_BUILTIN) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(stringify(args[i], false));
            }
            System.out.println(sb);
            current.setTop(PyObj.NONE);
            return;
        }
        if (callable == ALL_BUILTIN || callable == ANY_BUILTIN) {
            boolean wantAll = callable == ALL_BUILTIN;
            if (args.length != 1) throw PyException.typeError((wantAll ? "all" : "any") + " expected 1 argument");
            java.util.List<Object> items = materialize(args[0]);
            for (Object o : items) {
                boolean t = truthy(o);
                if (wantAll && !t) { current.setTop(Boolean.FALSE); return; }
                if (!wantAll && t) { current.setTop(Boolean.TRUE); return; }
            }
            current.setTop(wantAll ? Boolean.TRUE : Boolean.FALSE);
            return;
        }
        if (callable == BOOL_BUILTIN) {
            current.setTop(args.length == 0 ? Boolean.FALSE : (Boolean) truthy(args[0]));
            return;
        }
        if (callable == LEN_BUILTIN) {
            if (args.length != 1) throw PyException.typeError("len expected 1 argument");
            Object o = args[0];
            if (o instanceof PyInstance) {
                Object m = ((PyInstance) o).cls.lookup("__len__");
                if (m != null) { current.setTop(callSync(new BoundPyMethod(m, o), NO_ARGS)); return; }
                Object nv = nativeValueOf(o);          // len(MyStr('hi')) -> len of native str
                if (nv != null) { current.setTop((long) Builtins.lenOf(nv)); return; }
                throw PyException.typeError("object has no len()");
            }
            current.setTop((long) Builtins.lenOf(o));
            return;
        }
        if (callable == DICT_BUILTIN) {
            PyObj.PyDict d = new PyObj.PyDict();
            if (args.length >= 1 && args[0] != PyObj.NONE) {
                if (args[0] instanceof PyObj.PyDict) d.map.putAll(((PyObj.PyDict) args[0]).map);
                else {
                    PyObj.Iter it = Ops.getIter(args[0]); Object v;
                    while ((v = it.next()) != PyObj.STOP_ITERATION) {
                        if (!(v instanceof PyObj.Tuple) || ((PyObj.Tuple) v).items.length != 2)
                            throw PyException.typeError("dict update sequence element is not a pair");
                        Object[] kv = ((PyObj.Tuple) v).items;
                        d.map.put(kv[0], kv[1]);
                    }
                }
            }
            for (int i = 0; i < kwNames.length; i++) d.map.put(kwNames[i], kwValues[i]);
            current.setTop(d);
            return;
        }
        if (callable == SORTED_BUILTIN) {
            if (args.length < 1) throw PyException.typeError("sorted expected at least 1 argument");
            java.util.List<Object> items = new java.util.ArrayList<>(materialize(args[0]));
            Object key = null; boolean reverse = false;
            for (int i = 0; i < kwNames.length; i++) {
                if (kwNames[i].equals("key")) key = kwValues[i];
                else if (kwNames[i].equals("reverse")) reverse = Ops.isTrue(kwValues[i]);
            }
            final Object keyFn = (key == null || key == PyObj.NONE) ? null : key;
            // decorate-sort-undecorate so a Python key fn is called via callSync
            java.util.List<Object[]> deco = new java.util.ArrayList<>(items.size());
            for (Object it : items) deco.add(new Object[]{keyFn == null ? it : callSync(keyFn, new Object[]{it}), it});
            deco.sort((x, y) -> {
                // dunder-aware: a < b via __lt__ when present, else structural
                if (x[0] instanceof PyInstance || y[0] instanceof PyInstance) {
                    Object lt = binaryOpDispatch(Ops.LESS, x[0], y[0]);
                    if (truthy(lt)) return -1;
                    Object gt = binaryOpDispatch(Ops.LESS, y[0], x[0]);
                    return truthy(gt) ? 1 : 0;
                }
                return Ops.compare(x[0], y[0]);
            });
            if (reverse) java.util.Collections.reverse(deco);
            java.util.List<Object> out = new java.util.ArrayList<>(deco.size());
            for (Object[] d : deco) out.add(d[1]);
            current.setTop(new PyObj.PyList(out));
            return;
        }
        if (callable == MAP_BUILTIN) {
            if (args.length < 2) throw PyException.typeError("map expected at least 2 arguments");
            Object fn = args[0];
            PyObj.Iter[] its = new PyObj.Iter[args.length - 1];
            for (int i = 1; i < args.length; i++) its[i - 1] = Ops.getIter(args[i]);
            PyObj.PyList out = new PyObj.PyList();
            outer:
            while (true) {
                Object[] row = new Object[its.length];
                for (int i = 0; i < its.length; i++) {
                    Object v = its[i].next();
                    if (v == PyObj.STOP_ITERATION) break outer;
                    row[i] = v;
                }
                out.items.add(callSync(fn, row));
            }
            current.setTop(out);
            return;
        }
        if (callable == FILTER_BUILTIN) {
            if (args.length != 2) throw PyException.typeError("filter expected 2 arguments");
            Object fn = args[0];
            PyObj.Iter it = Ops.getIter(args[1]);
            PyObj.PyList out = new PyObj.PyList();
            Object v;
            while ((v = it.next()) != PyObj.STOP_ITERATION) {
                boolean keep = (fn == PyObj.NONE) ? Ops.isTrue(v) : Ops.isTrue(callSync(fn, new Object[]{v}));
                if (keep) out.items.add(v);
            }
            current.setTop(out);
            return;
        }
        if (callable == SUM_BUILTIN) {
            if (args.length < 1 || args.length > 2) throw PyException.typeError("sum expected 1 or 2 arguments");
            java.util.List<Object> items;
            if (args[0] instanceof PyGen) items = drainGen((PyGen) args[0]).items;
            else {
                items = new java.util.ArrayList<>();
                PyObj.Iter it = Ops.getIter(args[0]);
                Object v;
                while ((v = it.next()) != PyObj.STOP_ITERATION) items.add(v);
            }
            Object acc = args.length == 2 ? args[1] : 0L;
            for (Object v : items) acc = Ops.binaryOp(Ops.ADD, acc, v);
            current.setTop(acc);
            return;
        }
        if (callable == NEXT_BUILTIN) {
            if (args.length != 1) throw PyException.typeError("next expected 1 argument");
            if (args[0] instanceof PyGen) { resumeGen((PyGen) args[0], PyGen.MODE_CALL, 0, PyObj.NONE); return; }
            PyObj.Iter it = Ops.getIter(args[0]);
            Object v = it.next();
            if (v == PyObj.STOP_ITERATION) throw new PyException("StopIteration", "", new Object[0]);
            current.setTop(v);
            return;
        }
        if (callable instanceof BuiltinType && ((BuiltinType) callable).isConstructor()) {
            current.setTop(((BuiltinType) callable).construct(this, args, kwNames, kwValues));
            return;
        }
        if (callable instanceof PyInstance) {
            Object m = ((PyInstance) callable).cls.lookup("__call__");
            if (m == null) throw PyException.typeError("'" + ((PyInstance) callable).cls.name + "' object is not callable");
            current.setTop(callSync(new BoundPyMethod(m, callable), args));
            return;
        }
        if (callable instanceof HostFunction) {
            if (kwNames.length != 0) throw PyException.typeError("host functions do not accept keyword arguments");
            // Host (Java) API: call synchronously and leave the result on the
            // caller's stack (the callable slot is TOS). No frame is pushed.
            current.setTop(((HostFunction) callable).call(args));
            return;
        }
        if (callable instanceof Methods.BoundMethod) {
            if (kwNames.length != 0) throw PyException.typeError("built-in methods do not accept keyword arguments");
            current.setTop(((Methods.BoundMethod) callable).call(args));
            return;
        }
        if (callable instanceof PyExc.Type) {
            // instantiating an exception: ValueError("msg") etc.
            if (kwNames.length != 0) throw PyException.typeError("exceptions do not accept keyword arguments");
            current.setTop(((PyExc.Type) callable).make(args));
            return;
        }
        throw PyException.notImpl("calling a non-callable ("
                + (callable == null ? "null" : callable.getClass().getSimpleName())
                + ")");
    }

    /** Bind positional + keyword arguments (with defaults) into a fresh frame's
     *  locals - a port of mp_setup_code_state. */
    /** Fast-path frame setup for a simple-signature call: exactly nPos positional
     *  params, no *args/**kw/kwonly/defaults/generator. Binds locals directly
     *  from the caller's stack (src[srcBase .. srcBase+nPos)) - no args array.
     *  Mirrors the tail of prepareFrame (cell-local wrapping) so semantics match. */
    private Frame prepareSimpleFrame(PyFunction fn, Object self, Frame src, int srcBase, int nPos) {
        Frame f = new Frame(fn.code);
        int off = 0;
        if (self != null) f.setLocal(off++, self);      // bound-method receiver -> local 0
        for (int k = 0; k < nPos; k++) f.setLocal(off + k, src.state[srcBase + k]);
        for (int cellLocal : fn.code.prelude.cellLocalNums(fn.code.funData)) {
            f.setLocal(cellLocal, new PyObj.Cell(f.local(cellLocal)));
        }
        return f;
    }

    /** True if fn can be entered without materialising an args array: a plain
     *  function (not a generator) whose signature is exactly nArgs positional
     *  params with no *args/**kw/keyword-only/defaults. */
    private static boolean isSimpleSig(PyFunction fn, int nArgs) {
        net.mpy.loader.CodeObject cc = fn.code;
        int sf = cc.prelude.scopeFlags;
        return (sf & (SCOPE_GENERATOR | SCOPE_VARARGS | SCOPE_VARKEYWORDS)) == 0
                && cc.prelude.nKwonlyArgs == 0
                && cc.prelude.nDefPosArgs == 0
                && cc.prelude.nPosArgs == nArgs;
    }

    private Frame prepareFrame(PyFunction fn, Object[] args) {
        return prepareFrame(fn, args, NO_KW_NAMES, NO_KW_VALUES);
    }

    private Frame prepareFrame(PyFunction fn, Object[] args, String[] kwNames, Object[] kwValues) {
        // A port of mp_setup_code_state_helper: positional args -> locals 0..nPos-1,
        // keyword-only args -> the next nKwonly locals, then (if declared) the
        // *args tuple and the **kwargs dict in the following locals.
        Frame f = new Frame(fn.code);
        int nPosArgs = fn.code.prelude.nPosArgs;
        int nKwonly = fn.code.prelude.nKwonlyArgs;
        int nDef = fn.code.prelude.nDefPosArgs;
        int flags = fn.code.prelude.scopeFlags;
        boolean varargs = (flags & SCOPE_VARARGS) != 0;
        boolean varkw = (flags & SCOPE_VARKEYWORDS) != 0;

        int varargsLocal = nPosArgs + nKwonly;
        int kwdictLocal = varargsLocal + (varargs ? 1 : 0);

        int nGiven = args.length;
        if (nGiven > nPosArgs) {
            if (!varargs) {
                throw PyException.typeError(fn.name() + "() takes " + nPosArgs
                        + " positional arguments but " + nGiven + " were given");
            }
            Object[] extra = new Object[nGiven - nPosArgs];
            System.arraycopy(args, nPosArgs, extra, 0, extra.length);
            f.setLocal(varargsLocal, new PyObj.Tuple(extra));
            nGiven = nPosArgs;
        } else if (varargs) {
            f.setLocal(varargsLocal, new PyObj.Tuple(new Object[0]));
        }
        PyObj.PyDict kwDict = null;
        if (varkw) {
            kwDict = new PyObj.PyDict();
            f.setLocal(kwdictLocal, kwDict);
        }

        boolean[] bound = new boolean[nPosArgs + nKwonly];
        for (int i = 0; i < nGiven; i++) { f.setLocal(i, args[i]); bound[i] = true; }

        // keyword arguments: match declared names (positional + kw-only), else **kwargs
        for (int j = 0; j < kwNames.length; j++) {
            int idx = -1;
            for (int i = 0; i < nPosArgs + nKwonly; i++) {
                if (fn.module.qstr(fn.code.prelude.argNameQstrs[i]).equals(kwNames[j])) { idx = i; break; }
            }
            if (idx < 0) {
                if (kwDict == null) {
                    throw PyException.typeError(fn.name() + "() got an unexpected keyword argument '" + kwNames[j] + "'");
                }
                if (kwDict.map.containsKey(kwNames[j])) {
                    throw PyException.typeError(fn.name() + "() got multiple values for argument '" + kwNames[j] + "'");
                }
                kwDict.map.put(kwNames[j], kwValues[j]);
                continue;
            }
            if (bound[idx]) {
                throw PyException.typeError(fn.name() + "() got multiple values for argument '" + kwNames[j] + "'");
            }
            f.setLocal(idx, kwValues[j]);
            bound[idx] = true;
        }

        // defaults: positional from fn.defaults, keyword-only from fn.kwDefaults
        int missing = 0;
        for (int i = 0; i < nPosArgs; i++) {
            if (bound[i]) continue;
            if (i >= nPosArgs - nDef) f.setLocal(i, fn.defaults[i - (nPosArgs - nDef)]);
            else missing++;
        }
        if (missing > 0) {
            throw PyException.typeError(fn.name() + "() missing " + missing + " required positional argument(s)");
        }
        for (int i = nPosArgs; i < nPosArgs + nKwonly; i++) {
            if (bound[i]) continue;
            String pname = fn.module.qstr(fn.code.prelude.argNameQstrs[i]);
            Object d = fn.kwDefaults != null ? fn.kwDefaults.get(pname) : null;
            if (d == null) {
                throw PyException.typeError(fn.name() + "() missing required keyword-only argument '" + pname + "'");
            }
            f.setLocal(i, d);
        }

        // wrap the declared cell locals (captured by inner functions) in cells
        for (int cellLocal : fn.code.prelude.cellLocalNums(fn.code.funData)) {
            f.setLocal(cellLocal, new PyObj.Cell(f.local(cellLocal)));
        }
        return f;
    }

    private Object loadLocal(Frame f, int k) {
        Object v = f.local(k);
        if (v == null) throw PyException.nameError("local variable referenced before assignment");
        return v;
    }

    // ---- exception & unwind machinery (Phase 6, mirrors vm.c) -----------------

    /**
     * The raise path (vm.c's unwind_loop): find the innermost handler at or below
     * the current frame - popping blocks already passed - or propagate to the
     * caller frame; if nothing catches, surface to the embedder as a Java
     * PyException.
     */
    private void raiseInto(PyExc.Instance exc, PyException original) {
        Frame f = current;
        int ipForTest = raiseIp; // instruction start in the raising frame
        while (true) {
            // traceback: record (file, line, block) for every frame the exception
            // unwinds through - including the one that finally handles it - but
            // not for re-raises (bare `raise` / END_FINALLY), mirroring vm.c
            int op = ipForTest < f.bc.length ? (f.bc[ipForTest] & 0xff) : -1;
            if (op != Opcodes.RAISE_LAST && op != Opcodes.END_FINALLY) {
                exc.traceback.add(new Object[]{
                        f.code.module.qstr(0),                       // source file
                        (long) f.code.sourceLine(ipForTest),
                        f.code.module.qstr(f.code.prelude.simpleNameQstr)});
            }
            // pop blocks whose handler we've already reached/passed (nested raise)
            while (f.excSp >= 0 && f.excHandler[f.excSp] <= ipForTest) f.excSp--;
            if (f.excSp >= 0) {
                int i = f.excSp;
                f.ip = f.excHandler[i];
                f.sp = f.excValSp[i];      // unwind the value stack to SETUP level
                f.excPrevExc[i] = exc;     // save for RAISE_LAST
                handledException = exc;    // for traceback.format_exc()
                f.push(exc);               // handler receives the exception at TOS
                current = f;
                return;
            }
            if (f.genOwner != null) {
                // the exception escapes a generator body: the gen is finished
                PyGen gen = f.genOwner;
                gen.done = true;
                Frame resumer = f.caller;
                f.caller = null;
                if (resumer == null) { current = f; throw escape(exc, original); }
                current = resumer;
                if (gen.resumeMode == PyGen.MODE_FOR_ITER && PyExc.isSub(exc.type, PyExc.STOP_ITERATION)) {
                    // a StopIteration raised inside the gen = normal exhaustion
                    resumer.sp -= NSLOTS;
                    resumer.ip = gen.forIterExhaustIp;
                    return;
                }
                if (gen.resumeMode == PyGen.MODE_YIELD_FROM && PyExc.isSub(exc.type, PyExc.STOP_ITERATION)) {
                    resumer.setTop(exc.args.length > 0 ? exc.args[0] : PyObj.NONE);
                    return;
                }
                f = resumer;
                ipForTest = f.ip;
                continue;
            }
            if (f.caller == null) {
                current = f;
                throw escape(exc, original);
            }
            f = f.caller;                  // propagate: discard the frame
            current = f;
            ipForTest = f.ip;              // callers compare at their resume point
        }
    }

    /** Wrap an uncaught exception for the embedder, carrying the instance so
     *  {@link PyException#pyTraceback()} can print the MicroPython-style chain. */
    private static PyException escape(PyExc.Instance exc, PyException original) {
        PyException pe = original != null ? original
                : new PyException(exc.type.name, PyExc.str(exc), exc.args);
        pe.instance = exc;
        pe.escaped = true;   // pass through enclosing runLoop catches untouched
        return pe;
    }

    /**
     * RETURN_VALUE (and END_FINALLY's -1 sentinel): run any not-yet-active finally
     * handlers as "coroutines" first (vm.c's unwind_return), then really return.
     * Expects the return value at TOS of the current frame.
     */
    /** Install the with-statement's exception handler block on frame {@code f} after
     *  __enter__ has returned (its result is on TOS). {@code arg} is the SETUP_WITH
     *  handler offset. Stack at call: (..., __exit__, ctx, enter_result). */
    private void installWithBlock(Frame f, int arg) {
        int i = ++f.excSp;
        f.excHandler[i] = (int) (f.ip + arg);
        f.excValSp[i] = f.sp - 1;      // unwind restores to (..., __exit__, ctx)
        f.excIsFinally[i] = true;
        f.excPrevExc[i] = null;
    }

    private void doReturn() {
        Frame f = current;
        while (f.excSp >= 0) {
            int i = f.excSp;
            if (f.excIsFinally[i]) {
                if (f.excHandler[i] >= f.ip) {
                    // enter the finally: move ret_val down over any live iterators,
                    // push the -1 sentinel, jump; END_FINALLY resumes the return.
                    Object ret = f.state[f.sp];
                    f.sp = f.excValSp[i] + 1;
                    f.state[f.sp] = ret;
                    f.push(-1L);
                    f.ip = f.excHandler[i];
                    return;                 // block stays; END_FINALLY pops it
                }
                cancelActiveFinally(f);
            }
            f.excSp--;
        }
        if (f.importRetryIp >= 0) {
            // an imported module's top finished: rewind the importer to its
            // import instruction, which re-runs against the (now warmer) cache
            f.drop(1);                       // the module top's None
            Frame importer = f.caller;
            f.caller = null;
            importer.ip = f.importRetryIp;
            current = importer;
            return;
        }
        Object res = f.pop();
        if (f.withEnterArg >= 0) {
            // an __enter__ subframe (pushed by SETUP_WITH) finished: deliver its
            // result to the caller's reserved slot and install the with-block there,
            // completing the SETUP_WITH that was suspended across the call.
            int warg = f.withEnterArg;
            Frame caller = f.caller;
            f.caller = null;
            current = caller;
            caller.setTop(res);            // (..., __exit__, ctx, enter_result)
            installWithBlock(caller, warg);
            return;
        }
        if (f.buildingClass != null) {
            // the class body returns its __class__ cell (if super() is used):
            // fill it with the finished class, and deliver the class itself
            if (res instanceof PyObj.Cell) ((PyObj.Cell) res).value = f.buildingClass;
            res = f.buildingClass;
        } else if (f.returnOverride != null) res = f.returnOverride;
        if (f.genOwner != null) {
            // a generator body returned: the resumer sees exhaustion
            Frame resumer = f.caller;
            f.caller = null;
            f.genOwner.done = true;
            if (resumer == null) { returnValue = res; finished = true; return; }
            current = resumer;
            genFinished(f.genOwner, res);
            return;
        }
        if (f.caller == null) {
            returnValue = res;
            mainReturned = true;
            // Always set finished so the current batch stops cleanly (nothing more to
            // run on the main chain). Whether the VM is *really* finished is decided by
            // isFinished(), which in daemon-finish mode stays false while async tasks
            // remain — step()/runToCompletion then keep draining them.
            finished = true;
        }
        else if (f.isExecRoot) {
            // exec()/eval() unit finished: deliver its result to the caller's stack
            // (the exec/eval builtin call site left room for the return value).
            Object delivered = res;   // exec -> None (the module root's return)
            if (f.isEvalRoot) {       // eval -> the captured expression value
                Map<String, Object> where = f.execGlobals != null ? f.execGlobals : globals;
                delivered = where.getOrDefault(EVAL_RESULT_NAME, PyObj.NONE);
                where.remove(EVAL_RESULT_NAME);   // don't leak the reserved name
            }
            current = f.caller;
            f.caller = null;
            current.setTop(delivered);
        }
        else { current = f.caller; current.setTop(res); }
    }

    /**
     * UNWIND_JUMP continuation (vm.c's unwind_jump): expects
     * (..., dest_ip, count) on the stack; runs up to {@code count & 0x7f} finally
     * handlers as coroutines, then jumps to dest_ip (popping an exhausted
     * iterator if the 0x80 flag is set).
     */
    private void unwindJump(Frame f) {
        long unum = (Long) f.pop();
        while ((unum & 0x7f) > 0) {
            unum -= 1;
            int i = f.excSp;
            if (f.excIsFinally[i]) {
                if (f.excHandler[i] >= f.ip) {
                    // stack: (..., dest_ip); sentinel = handlers left to unwind
                    f.push(unum);
                    f.ip = f.excHandler[i];
                    return;                 // block stays; END_FINALLY resumes
                }
                cancelActiveFinally(f);
            }
            f.excSp--;
        }
        f.ip = (int) (long) (Long) f.pop(); // destination
        if (unum != 0) f.sp -= NSLOTS;      // 0x80 flag: pop the exhausted iterator
    }

    /** vm.c's CANCEL_ACTIVE_FINALLY: a new unwind superseding one in progress. */
    private void cancelActiveFinally(Frame f) {
        if (f.state[f.sp - 1] instanceof Long) {
            // (..., prev_dest_ip, prev_cause, dest_ip): replace the previous unwind
            f.state[f.sp - 2] = f.state[f.sp];
            f.sp -= 2;
        } else {
            // (..., None/exception, dest_ip): silence the finally's pending value
            f.state[f.sp - 1] = f.state[f.sp];
            f.sp -= 1;
        }
    }

    /** What `raise X` accepts: an instance, or a type (instantiated with no args). */
    private static PyExc.Instance toExcInstance(Object o) {
        if (o instanceof PyExc.Instance) return (PyExc.Instance) o;
        if (o instanceof PyExc.Type) return ((PyExc.Type) o).make(null);
        throw PyException.typeError("exceptions must derive from BaseException");
    }
}
