package net.mpy.vm;

import net.mpy.loader.CodeObject;
import net.mpy.loader.MpyModule;
import net.mpy.runtime.Methods;
import net.mpy.runtime.PyObj;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * (De)serialization of VM values and frames for {@link Vm#saveSnapshot()} /
 * {@link Vm#restoreSnapshot(byte[])}.
 *
 * A snapshot is taken relative to a loaded module: code objects are referenced by
 * their <em>path</em> (child indices from {@code module.root}). Host functions are
 * written as stable ids resolved through a {@link HostRegistry}.
 *
 * <b>Identity is preserved</b> for mutable objects (list/dict/set/iter/tuple):
 * one {@link Writer}/{@link Reader} spans the whole snapshot (all frames, globals,
 * return value), assigning each object an id on first encounter and emitting a
 * back-reference on later ones - so aliases (the same list held in a local and on
 * the stack) stay aliases after restore, and cyclic structures round-trip.
 */
final class Snapshot {

    private Snapshot() {}

    // value type tags
    private static final int T_NULL = 0, T_NONE = 1, T_ELLIPSIS = 2, T_FALSE = 3, T_TRUE = 4,
            T_LONG = 5, T_BIGINT = 6, T_DOUBLE = 7, T_STR = 8, T_BYTES = 9, T_TUPLE = 10,
            T_LIST = 11, T_DICT = 12, T_SET = 13, T_SLICE = 14, T_COMPLEX = 15, T_ITER = 16,
            T_FUNC = 17, T_HOSTFUNC = 18, T_RANGE = 19, T_METHODREF = 20, T_BOUNDMETHOD = 21,
            T_BACKREF = 22, T_EXCTYPE = 23, T_EXCINSTANCE = 24,
            T_CELL = 25, T_CLOSURE = 26, T_GEN = 27, T_CLASS = 28, T_INSTANCE = 29,
            T_BOUNDPYM = 30, T_NEXT_BUILTIN = 31, T_LIST_BUILTIN = 32, T_BUILD_CLASS = 33, T_SUPER_BUILTIN = 34,
            T_MODULE = 35, T_SUM_BUILTIN = 36,
            T_USER_EXCTYPE = 51, T_USER_EXCINSTANCE = 52;

    /** The nearest built-in exception type name in a user excType's base chain,
     *  i.e. the registered PyExc.TYPES ancestor (Exception, ValueError, ...). */
    private static String excBaseName(net.mpy.runtime.PyExc.Type et) {
        for (net.mpy.runtime.PyExc.Type x = et.base; x != null; x = x.base) {
            if (x.userClass == null) return x.name;   // a built-in type
        }
        return "Exception";
    }

    // ==== writer ==============================================================

    static final class Writer {
        private final DataOutputStream out;
        private final MpyModule module;   // the main module
        private final HostRegistry hosts;
        private final IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        private int nextId = 0;
        private final IdentityHashMap<Frame, Integer> frameSeen = new IdentityHashMap<>();
        private int nextFrameId = 0;
        // Identity table for exec/sandbox namespaces, so frames that share the same
        // ns (an exec unit and the functions defined in it) restore to one shared
        // dict rather than diverging copies.
        private final IdentityHashMap<java.util.Map<String, Object>, Integer> nsSeen = new IdentityHashMap<>();
        private int nextNsId = 0;

        Writer(DataOutputStream out, MpyModule module, HostRegistry hosts) {
            this.out = out; this.module = module; this.hosts = hosts;
        }

        /** module -> index in the embedded module table (0 == main). Set by
         *  Vm.saveSnapshot, which also writes the table (name + raw .mpy bytes)
         *  at the head of the snapshot - restores are fully self-contained. */
        IdentityHashMap<MpyModule, Integer> moduleIndex;

        /** A code reference: embedded-module-table index + tree path. */
        private void writeCodeRef(net.mpy.loader.CodeObject code) throws IOException {
            Integer idx = moduleIndex.get(code.module);
            if (idx == null) throw new IllegalStateException("code belongs to a module that was not embedded");
            out.writeInt(idx);
            writePath(out, codePath(code.module, code));
        }

        /** Write a frame reference: -1 null, id for already-written, -2 + body. */
        void writeFrameRef(Frame f) throws IOException {
            if (f == null) { out.writeInt(-1); return; }
            Integer id = frameSeen.get(f);
            if (id != null) { out.writeInt(id); return; }
            frameSeen.put(f, nextFrameId++);
            out.writeInt(-2);
            writeCodeRef(f.code);
            out.writeInt(f.ip);
            out.writeInt(f.sp);
            out.writeInt(f.state.length);
            for (Object o : f.state) writeValue(o);
            out.writeInt(f.excSp);
            for (int i = 0; i <= f.excSp; i++) {
                out.writeInt(f.excHandler[i]);
                out.writeInt(f.excValSp[i]);
                out.writeBoolean(f.excIsFinally[i]);
                writeValue(f.excPrevExc[i]);
            }
            out.writeInt(f.importRetryIp);
            out.writeInt(f.withEnterArg);
            writeValue(f.returnOverride);
            writeValue(f.buildingClass);
            // names alias either the class being built or the module being imported
            if (f.names != null) {
                if (f.buildingClass != null && f.buildingClass.ns == f.names) out.writeByte(1);
                else if (f.returnOverride instanceof PyModule && ((PyModule) f.returnOverride).ns == f.names) out.writeByte(2);
                else throw new IllegalStateException("cannot snapshot a frame with a detached name scope");
            } else {
                out.writeByte(0);
            }
            // exec()/exec_sandbox() namespace: a frame running inside an exec unit
            // (or a function defined in one) carries its ns here. Without persisting
            // it, a suspended command/REPL loses its injected names (tty, sh, args,
            // and any variables it defined) across a save/load. Stored as a plain
            // string->value map; sandboxScope records the fall-through mode.
            if (f.execGlobals != null) {
                out.writeByte(1);
                out.writeBoolean(f.sandboxScope);
                writeNs(f.execGlobals);
            } else {
                out.writeByte(0);
            }
            // exec()/eval() unit root flags: without these, a restored suspended
            // command/REPL would not deliver its result back to the caller when it
            // finishes -- doReturn would treat it as the end of the whole program.
            out.writeBoolean(f.isExecRoot);
            out.writeBoolean(f.isEvalRoot);
        }

        /** Write an exec/sandbox namespace with identity dedup: the first sighting
         *  assigns an id and writes contents; later sightings write a back-ref, so
         *  frames and functions sharing one ns restore to one shared dict. A
         *  DictGlobals view (exec given a Python dict) is written as a reference to
         *  its backing PyDict, which writeValue dedupes with every other reference
         *  to that dict in the snapshot -- the Python-visible dict and the exec ns
         *  must stay one storage after restore. */
        void writeNs(java.util.Map<String, Object> ns) throws IOException {
            if (ns instanceof DictGlobals) {
                out.writeByte(3);              // view over a Python dict
                writeValue(((DictGlobals) ns).dict);
                return;
            }
            Integer nsId = nsSeen.get(ns);
            if (nsId != null) {
                out.writeByte(2);              // back-ref
                out.writeInt(nsId);
                return;
            }
            out.writeByte(1);                  // first sighting
            out.writeInt(nextNsId);
            nsSeen.put(ns, nextNsId++);
            out.writeInt(ns.size());
            for (java.util.Map.Entry<String, Object> e : ns.entrySet()) {
                writeStr(out, e.getKey());
                writeValue(e.getValue());
            }
        }

        /** Track identity: emit a back-ref if already written, else assign an id.
         *  @return true if a back-ref was emitted (caller must not write contents). */
        private boolean ref(Object v) throws IOException {
            Integer id = seen.get(v);
            if (id != null) { out.writeByte(T_BACKREF); out.writeInt(id); return true; }
            seen.put(v, nextId++);
            return false;
        }

        void writeValue(Object v) throws IOException {
            if (v == null) { out.writeByte(T_NULL); return; }
            if (v == PyObj.NONE) { out.writeByte(T_NONE); return; }
            if (v == PyObj.ELLIPSIS) { out.writeByte(T_ELLIPSIS); return; }
            if (v instanceof Boolean) { out.writeByte((Boolean) v ? T_TRUE : T_FALSE); return; }
            if (v instanceof Long) { out.writeByte(T_LONG); out.writeLong((Long) v); return; }
            if (v instanceof BigInteger) {
                out.writeByte(T_BIGINT);
                byte[] b = ((BigInteger) v).toByteArray();
                out.writeInt(b.length); out.write(b);
                return;
            }
            if (v instanceof Double) { out.writeByte(T_DOUBLE); out.writeDouble((Double) v); return; }
            if (v instanceof String) { out.writeByte(T_STR); writeStr(out, (String) v); return; }
            if (v instanceof PyObj.Bytes) {
                out.writeByte(T_BYTES);
                byte[] b = ((PyObj.Bytes) v).data;
                out.writeInt(b.length); out.write(b);
                return;
            }
            if (v instanceof PyObj.ByteArray) {
                if (ref(v)) return;                 // mutable: preserve aliasing
                out.writeByte(68);
                byte[] b = ((PyObj.ByteArray) v).toBytes();
                out.writeInt(b.length); out.write(b);
                return;
            }
            if (v instanceof PyObj.Tuple) {
                if (ref(v)) return;
                out.writeByte(T_TUPLE);
                PyObj.Tuple tup = (PyObj.Tuple) v;
                Object[] it = tup.items;
                out.writeInt(it.length);
                out.writeBoolean(tup.luaStyle);   // Lua-style unpack flag (lua())
                for (Object o : it) writeValue(o);
                return;
            }
            if (v instanceof PyObj.PyList) {
                if (ref(v)) return;
                out.writeByte(T_LIST);
                List<Object> xs = ((PyObj.PyList) v).items;
                out.writeInt(xs.size());
                for (Object o : xs) writeValue(o);
                return;
            }
            if (v instanceof PyObj.PyDict) {
                if (ref(v)) return;
                out.writeByte(T_DICT);
                var m = ((PyObj.PyDict) v).map;
                out.writeInt(m.size());
                for (var e : m.entrySet()) { writeValue(e.getKey()); writeValue(e.getValue()); }
                return;
            }
            if (v instanceof PyObj.PySet) {
                if (ref(v)) return;
                out.writeByte(T_SET);
                var s = ((PyObj.PySet) v).items;
                out.writeInt(s.size());
                for (Object o : s) writeValue(o);
                return;
            }
            if (v instanceof PyObj.Iter) {
                if (ref(v)) return;
                out.writeByte(T_ITER);
                PyObj.Iter it = (PyObj.Iter) v;
                it.materialize();   // drain any live host supplier into items first
                out.writeInt(it.pos);
                out.writeInt(it.items.size());
                for (Object o : it.items) writeValue(o);
                return;
            }
            if (v instanceof PyObj.Slice) {
                out.writeByte(T_SLICE);
                PyObj.Slice s = (PyObj.Slice) v;
                writeValue(s.start); writeValue(s.stop); writeValue(s.step);
                return;
            }
            if (v instanceof PyObj.Complex) {
                out.writeByte(T_COMPLEX);
                PyObj.Complex c = (PyObj.Complex) v;
                out.writeDouble(c.re); out.writeDouble(c.im);
                return;
            }
            if (v instanceof PyObj.Range) {
                out.writeByte(T_RANGE);
                PyObj.Range r = (PyObj.Range) v;
                out.writeLong(r.start); out.writeLong(r.stop); out.writeLong(r.step);
                return;
            }
            if (v instanceof Methods.Ref) {
                out.writeByte(T_METHODREF);
                writeStr(out, ((Methods.Ref) v).name);
                return;
            }
            if (v instanceof Methods.BoundMethod) {
                out.writeByte(T_BOUNDMETHOD);
                Methods.BoundMethod bm = (Methods.BoundMethod) v;
                writeStr(out, bm.name);
                writeValue(bm.self);
                return;
            }
            if (v instanceof PyObj.Cell) {
                if (ref(v)) return;
                out.writeByte(T_CELL);
                writeValue(((PyObj.Cell) v).value);
                return;
            }
            if (v instanceof Closure) {
                if (ref(v)) return;
                out.writeByte(T_CLOSURE);
                writeValue(((Closure) v).fun);
                out.writeInt(((Closure) v).closed.length);
                for (PyObj.Cell c : ((Closure) v).closed) writeValue(c);
                return;
            }
            if (v instanceof PyGen) {
                if (ref(v)) return;
                out.writeByte(T_GEN);
                PyGen g = (PyGen) v;
                out.writeBoolean(g.started);
                out.writeBoolean(g.done);
                out.writeInt(g.resumeMode);
                out.writeInt(g.forIterExhaustIp);
                writeFrameRef(g.frame);
                return;
            }
            if (v instanceof PyClass) {
                if (ref(v)) return;
                out.writeByte(T_CLASS);
                PyClass c = (PyClass) v;
                writeStr(out, c.name);
                writeStr(out, c.nativeBase != null ? c.nativeBase.name : "");   // native base (str/list) or ""
                // exc base name (the built-in exception at the root of the excType chain), or ""
                writeStr(out, c.excType != null && c.excType.base != null ? excBaseName(c.excType) : "");
                out.writeInt(c.bases.length);
                for (PyClass b : c.bases) writeValue(b);
                out.writeInt(c.ns.size());
                for (var e : c.ns.entrySet()) { writeStr(out, e.getKey()); writeValue(e.getValue()); }
                return;
            }
            if (v instanceof PyInstance) {
                if (ref(v)) return;
                out.writeByte(T_INSTANCE);
                PyInstance inst = (PyInstance) v;
                writeValue(inst.cls);
                out.writeBoolean(inst.nativeValue != null);
                if (inst.nativeValue != null) writeValue(inst.nativeValue);
                out.writeInt(inst.attrs.size());
                for (var e : inst.attrs.entrySet()) { writeStr(out, e.getKey()); writeValue(e.getValue()); }
                return;
            }
            if (v instanceof Descriptors.StaticMethod) { out.writeByte(37); writeValue(((Descriptors.StaticMethod) v).fn); return; }
            if (v instanceof Descriptors.ClassMethod) { out.writeByte(38); writeValue(((Descriptors.ClassMethod) v).fn); return; }
            if (v instanceof Descriptors.Property) {
                Descriptors.Property p = (Descriptors.Property) v;
                out.writeByte(39);
                writeValue(p.getter);
                writeValue(p.setter == null ? PyObj.NONE : p.setter);
                writeValue(p.deleter == null ? PyObj.NONE : p.deleter);
                return;
            }
            if (v instanceof BoundPyMethod) {
                out.writeByte(T_BOUNDPYM);
                writeValue(((BoundPyMethod) v).fn);
                writeValue(((BoundPyMethod) v).self);
                return;
            }
            if (v == Vm.NEXT_BUILTIN) { out.writeByte(T_NEXT_BUILTIN); return; }
            if (v == Vm.LIST_BUILTIN) { out.writeByte(T_LIST_BUILTIN); return; }
            if (v == Vm.BUILD_CLASS) { out.writeByte(T_BUILD_CLASS); return; }
            if (v == Vm.SUPER_BUILTIN) { out.writeByte(T_SUPER_BUILTIN); return; }
            if (v == Vm.SUM_BUILTIN) { out.writeByte(T_SUM_BUILTIN); return; }
            if (v == Vm.SORTED_BUILTIN) { out.writeByte(40); return; }
            if (v == Vm.MAP_BUILTIN) { out.writeByte(41); return; }
            if (v == Vm.FILTER_BUILTIN) { out.writeByte(42); return; }
            if (v == Vm.DICT_BUILTIN) { out.writeByte(44); return; }
            if (v == Vm.LEN_BUILTIN) { out.writeByte(45); return; }
            if (v == Vm.BOOL_BUILTIN) { out.writeByte(46); return; }
            if (v == Vm.ALL_BUILTIN) { out.writeByte(47); return; }
            if (v == Vm.ANY_BUILTIN) { out.writeByte(48); return; }
            if (v == Vm.REPR_BUILTIN) { out.writeByte(49); return; }
            if (v == Vm.PRINT_BUILTIN) { out.writeByte(50); return; }
            if (v == Vm.GLOBALS_BUILTIN) { out.writeByte(53); return; }
            if (v == Vm.LOCALS_BUILTIN) { out.writeByte(54); return; }
            if (v == Vm.COMPILE_BUILTIN) { out.writeByte(55); return; }
            if (v == Vm.EXEC_BUILTIN) { out.writeByte(56); return; }
            if (v == Vm.EVAL_BUILTIN) { out.writeByte(57); return; }
            if (v == Vm.EXEC_SANDBOX_BUILTIN) { out.writeByte(65); return; }
            if (v == Vm.EVAL_SANDBOX_BUILTIN) { out.writeByte(66); return; }
            if (v == Vm.CREATE_TASK_BUILTIN) { out.writeByte(59); return; }
            if (v instanceof SleepRequest) { out.writeByte(60); out.writeLong(((SleepRequest) v).ms); return; }
            if (v == Vm.MAKE_SLEEP_BUILTIN) { out.writeByte(61); return; }
            if (v == Vm.FINISH_DAEMON_BUILTIN) { out.writeByte(62); return; }
            if (v == Vm.RUN_BUILTIN) { out.writeByte(63); return; }
            if (v == Vm.TEST_AND_SET_BUILTIN) { out.writeByte(64); return; }
            if (v instanceof BuiltinType) { out.writeByte(43); writeStr(out, ((BuiltinType) v).name); return; }
            if (v instanceof PyModule) {
                if (ref(v)) return;
                out.writeByte(T_MODULE);
                PyModule pm = (PyModule) v;
                writeStr(out, pm.name);
                writeStr(out, pm.root);
                out.writeBoolean(pm.initializing);      // mid-init snapshot keeps waiters waiting
                if (pm.code == null) {
                    out.writeInt(-1);                       // synthetic package
                } else {
                    Integer idx = moduleIndex.get(pm.code);
                    if (idx == null) throw new IllegalStateException("module '" + pm.name + "' was not embedded");
                    out.writeInt(idx);
                }
                out.writeInt(pm.ns.size());
                for (var e : pm.ns.entrySet()) { writeStr(out, e.getKey()); writeValue(e.getValue()); }
                return;
            }
            if (v instanceof net.mpy.runtime.PyExc.Type) {
                net.mpy.runtime.PyExc.Type et = (net.mpy.runtime.PyExc.Type) v;
                if (et.userClass != null) {         // user exception subclass
                    out.writeByte(T_USER_EXCTYPE);
                    writeValue(et.userClass);       // the backing PyClass carries name/bases/excType
                    return;
                }
                out.writeByte(T_EXCTYPE);
                writeStr(out, et.name);
                return;
            }
            if (v instanceof net.mpy.runtime.PyExc.Instance) {
                net.mpy.runtime.PyExc.Instance e = (net.mpy.runtime.PyExc.Instance) v;
                boolean user = e.type.userClass != null;
                out.writeByte(user ? T_USER_EXCINSTANCE : T_EXCINSTANCE);
                if (user) writeValue(e.type.userClass); else writeStr(out, e.type.name);
                out.writeInt(e.args.length);
                for (Object a : e.args) writeValue(a);
                if (user) {
                    out.writeInt(e.userAttrs.size());
                    for (var en : e.userAttrs.entrySet()) { writeStr(out, en.getKey()); writeValue(en.getValue()); }
                }
                out.writeInt(e.traceback.size());
                for (Object[] t : e.traceback) {
                    writeStr(out, (String) t[0]);
                    out.writeLong((Long) t[1]);
                    writeStr(out, (String) t[2]);
                }
                return;
            }
            if (v instanceof PyFunction) {
                out.writeByte(T_FUNC);
                PyFunction fn = (PyFunction) v;
                writeCodeRef(fn.code);
                out.writeInt(fn.defaults.length);
                for (Object d : fn.defaults) writeValue(d);
                if (fn.kwDefaults == null) out.writeInt(-1);
                else {
                    out.writeInt(fn.kwDefaults.size());
                    for (var e : fn.kwDefaults.entrySet()) { writeValue(e.getKey()); writeValue(e.getValue()); }
                }
                // The defining exec/sandbox scope (see PyFunction.defScope): without
                // this, a function defined in a REPL/command sandbox loses its ns on
                // restore and every module-level name it uses becomes a NameError.
                if (fn.defScope != null) {
                    out.writeByte(1);
                    out.writeBoolean(fn.defSandbox);
                    writeNs(fn.defScope);
                } else {
                    out.writeByte(0);
                }
                // function attributes (func.attr = x); usually empty
                if (fn.attrs == null || fn.attrs.isEmpty()) {
                    out.writeInt(0);
                } else {
                    out.writeInt(fn.attrs.size());
                    for (var e : fn.attrs.entrySet()) { writeStr(out, e.getKey()); writeValue(e.getValue()); }
                }
                return;
            }
            if (v instanceof HostFunction) {
                String id = hosts == null ? null : hosts.idFor((HostFunction) v);
                if (id == null) {
                    throw new IllegalStateException("cannot snapshot a live host function: "
                            + (hosts == null ? "no HostRegistry was supplied"
                                             : "this host function has no id in the supplied HostRegistry"));
                }
                out.writeByte(T_HOSTFUNC);
                writeStr(out, id);
                return;
            }
            if (v instanceof CompiledCode) {
                Integer idx = moduleIndex.get(((CompiledCode) v).module);
                if (idx == null) throw new IllegalStateException("compiled code's module was not embedded");
                out.writeByte(58);                  // T_COMPILEDCODE
                out.writeInt(idx);
                writeStr(out, ((CompiledCode) v).source);
                return;
            }
            // Generic fallback for any builtin singleton not given an explicit tag
            // above: store it by its stable toString() name and resolve it back on
            // read via Vm's registry. This keeps snapshots working even if a new
            // builtin is added without a dedicated tag (as happened with
            // exec_sandbox/eval_sandbox).
            if (v instanceof Vm.NativeBuiltin) {
                String name = Vm.builtinName(v);
                if (name != null) {
                    out.writeByte(67);              // T_NAMED_BUILTIN
                    writeStr(out, name);
                    return;
                }
            }
            throw new IllegalStateException("cannot snapshot value of type " + v.getClass().getName());
        }
    }

    // ==== reader ==============================================================

    static final class Reader {
        private final DataInputStream in;
        private final MpyModule module;   // the main module
        private final HostRegistry hosts;
        /** The embedded module table (index 0 == main), read by Vm.restore. */
        MpyModule[] moduleTable;

        private MpyModule readModuleOf() throws IOException {
            return moduleTable[in.readInt()];
        }
        private final List<Object> byId = new ArrayList<>();
        private final List<Frame> framesById = new ArrayList<>();
        private final java.util.Map<Integer, java.util.Map<String, Object>> nsById = new java.util.HashMap<>();

        Reader(DataInputStream in, MpyModule module, HostRegistry hosts) {
            this.in = in; this.module = module; this.hosts = hosts;
        }

        Frame readFrameRef() throws IOException {
            int tag = in.readInt();
            if (tag == -1) return null;
            if (tag >= 0) return framesById.get(tag);
            MpyModule fm = readModuleOf();
            CodeObject code = codeAt(fm, readPath(in));
            Frame f = new Frame(code);
            framesById.add(f);                 // register before contents (cycles)
            f.ip = in.readInt();
            f.sp = in.readInt();
            int n = in.readInt();
            if (n != f.state.length) throw new IllegalStateException("snapshot state size mismatch for " + code);
            for (int i = 0; i < n; i++) f.state[i] = readValue();
            f.excSp = in.readInt();
            for (int i = 0; i <= f.excSp; i++) {
                f.excHandler[i] = in.readInt();
                f.excValSp[i] = in.readInt();
                f.excIsFinally[i] = in.readBoolean();
                f.excPrevExc[i] = readValue();
            }
            f.importRetryIp = in.readInt();
            f.withEnterArg = in.readInt();
            f.returnOverride = readValue();
            f.buildingClass = (PyClass) readValue();
            int namesKind = in.readByte();
            if (namesKind == 1) f.names = f.buildingClass.ns;
            else if (namesKind == 2) f.names = ((PyModule) f.returnOverride).ns;
            int hasExecGlobals = in.readByte();
            if (hasExecGlobals == 1) {
                f.sandboxScope = in.readBoolean();
                f.execGlobals = readNs();
            }
            f.isExecRoot = in.readBoolean();
            f.isEvalRoot = in.readBoolean();
            return f;
        }

        /** Read a namespace written by writeNs, honouring identity back-refs. */
        java.util.Map<String, Object> readNs() throws IOException {
            int kind = in.readByte();
            if (kind == 3) return new DictGlobals((PyObj.PyDict) readValue());
            if (kind == 2) return nsById.get(in.readInt());   // back-ref
            int nsId = in.readInt();
            int nEG = in.readInt();
            java.util.Map<String, Object> ns = new java.util.HashMap<String, Object>();
            nsById.put(nsId, ns);              // register before contents (cycles)
            for (int i = 0; i < nEG; i++) {
                String k = readStr(in);
                ns.put(k, readValue());
            }
            return ns;
        }

        Object readValue() throws IOException {
            int tag = in.readByte();
            switch (tag) {
                case T_NULL: return null;
                case T_NONE: return PyObj.NONE;
                case T_ELLIPSIS: return PyObj.ELLIPSIS;
                case T_FALSE: return Boolean.FALSE;
                case T_TRUE: return Boolean.TRUE;
                case T_LONG: return in.readLong();
                case T_BIGINT: {
                    byte[] b = new byte[in.readInt()]; in.readFully(b);
                    return PyObj.normInt(new BigInteger(b));
                }
                case T_DOUBLE: return in.readDouble();
                case T_STR: return readStr(in);
                case T_BYTES: {
                    byte[] b = new byte[in.readInt()]; in.readFully(b);
                    return new PyObj.Bytes(b);
                }
                case 68: {   // ByteArray
                    byte[] b = new byte[in.readInt()]; in.readFully(b);
                    PyObj.ByteArray ba = new PyObj.ByteArray(b);
                    byId.add(ba);   // matches ref() on the write side
                    return ba;
                }
                case T_TUPLE: {
                    int n = in.readInt();
                    boolean luaStyle = in.readBoolean();
                    PyObj.Tuple t = new PyObj.Tuple(new Object[n], luaStyle);
                    byId.add(t);                     // register before contents (cycles/aliases)
                    for (int i = 0; i < n; i++) t.items[i] = readValue();
                    return t;
                }
                case T_LIST: {
                    int n = in.readInt();
                    PyObj.PyList l = new PyObj.PyList(new ArrayList<>(n));
                    byId.add(l);
                    for (int i = 0; i < n; i++) l.items.add(readValue());
                    return l;
                }
                case T_DICT: {
                    int n = in.readInt();
                    PyObj.PyDict d = new PyObj.PyDict();
                    byId.add(d);
                    for (int i = 0; i < n; i++) { Object k = readValue(); Object v = readValue(); d.map.put(k, v); }
                    return d;
                }
                case T_SET: {
                    int n = in.readInt();
                    PyObj.PySet s = new PyObj.PySet();
                    byId.add(s);
                    for (int i = 0; i < n; i++) s.items.add(readValue());
                    return s;
                }
                case T_ITER: {
                    int pos = in.readInt();
                    int n = in.readInt();
                    PyObj.Iter it = new PyObj.Iter(new ArrayList<>(n));
                    it.pos = pos;
                    byId.add(it);
                    for (int i = 0; i < n; i++) it.items.add(readValue());
                    return it;
                }
                case T_SLICE: {
                    Object a = readValue(), b = readValue(), c = readValue();
                    return new PyObj.Slice(a, b, c);
                }
                case T_COMPLEX: return new PyObj.Complex(in.readDouble(), in.readDouble());
                case T_RANGE: return new PyObj.Range(in.readLong(), in.readLong(), in.readLong());
                case T_METHODREF: return new Methods.Ref(readStr(in));
                case T_BOUNDMETHOD: {
                    String name = readStr(in);
                    Object self = readValue();
                    return new Methods.BoundMethod(self, name);
                }
                case T_CELL: {
                    PyObj.Cell c = new PyObj.Cell(null);
                    byId.add(c);
                    c.value = readValue();
                    return c;
                }
                case T_CLOSURE: {
                    // register a placeholder ordering slot first, then fill
                    int slot = byId.size();
                    byId.add(null);
                    PyFunction fn = (PyFunction) readValue();
                    int n = in.readInt();
                    PyObj.Cell[] closed = new PyObj.Cell[n];
                    for (int i = 0; i < n; i++) closed[i] = (PyObj.Cell) readValue();
                    Closure c = new Closure(fn, closed);
                    byId.set(slot, c);
                    return c;
                }
                case T_GEN: {
                    PyGen g = new PyGen(null);
                    byId.add(g);
                    g.started = in.readBoolean();
                    g.done = in.readBoolean();
                    g.resumeMode = in.readInt();
                    g.forIterExhaustIp = in.readInt();
                    Frame fr = readFrameRef();
                    g.frame = fr;
                    if (fr != null) fr.genOwner = g;
                    return g;
                }
                case T_CLASS: {
                    // register the id slot first (mirrors the writer's ref() order),
                    // then read base + namespace; base chains are acyclic.
                    String cname = readStr(in);
                    String nbName = readStr(in);
                    String excBaseName = readStr(in);
                    int slot = byId.size();
                    byId.add(null);
                    int nb = in.readInt();
                    PyClass[] bases = new PyClass[nb];
                    for (int i = 0; i < nb; i++) bases[i] = (PyClass) readValue();
                    PyClass cls = new PyClass(cname, bases);
                    if (!nbName.isEmpty()) cls.nativeBase = BuiltinType.byName(nbName);
                    if (!excBaseName.isEmpty()) {
                        // rebuild the excType: prefer a user exc base already among bases,
                        // else chain to the built-in exception type of that name.
                        net.mpy.runtime.PyExc.Type base = null;
                        for (PyClass b : bases) if (b.excType != null) { base = b.excType; break; }
                        if (base == null) base = net.mpy.runtime.PyExc.TYPES.get(excBaseName);
                        cls.excType = new net.mpy.runtime.PyExc.Type(cname, base, cls);
                    }
                    byId.set(slot, cls);
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) { String k = readStr(in); cls.ns.put(k, readValue()); }
                    return cls;
                }
                case T_INSTANCE: {
                    int slot = byId.size();
                    byId.add(null);
                    PyClass cls = (PyClass) readValue();
                    PyInstance inst = new PyInstance(cls);
                    byId.set(slot, inst);
                    boolean hasNative = in.readBoolean();
                    if (hasNative) inst.nativeValue = readValue();
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) { String k = readStr(in); inst.attrs.put(k, readValue()); }
                    return inst;
                }
                case 37: return new Descriptors.StaticMethod(readValue());
                case 38: return new Descriptors.ClassMethod(readValue());
                case 39: {
                    Object g = readValue();
                    Object st = readValue();
                    Object dl = readValue();
                    return new Descriptors.Property(g,
                            st == PyObj.NONE ? null : st,
                            dl == PyObj.NONE ? null : dl);
                }
                case T_BOUNDPYM: {
                    Object fn = readValue();
                    Object self = readValue();
                    return new BoundPyMethod(fn, self);
                }
                case 40: return Vm.SORTED_BUILTIN;
                case 41: return Vm.MAP_BUILTIN;
                case 42: return Vm.FILTER_BUILTIN;
                case 44: return Vm.DICT_BUILTIN;
                case 45: return Vm.LEN_BUILTIN;
                case 46: return Vm.BOOL_BUILTIN;
                case 47: return Vm.ALL_BUILTIN;
                case 48: return Vm.ANY_BUILTIN;
                case 49: return Vm.REPR_BUILTIN;
                case 50: return Vm.PRINT_BUILTIN;
                case 53: return Vm.GLOBALS_BUILTIN;
                case 54: return Vm.LOCALS_BUILTIN;
                case 55: return Vm.COMPILE_BUILTIN;
                case 56: return Vm.EXEC_BUILTIN;
                case 57: return Vm.EVAL_BUILTIN;
                case 65: return Vm.EXEC_SANDBOX_BUILTIN;
                case 66: return Vm.EVAL_SANDBOX_BUILTIN;
                case 67: {   // T_NAMED_BUILTIN: builtin stored by stable name
                    Object b = Vm.builtinByName(readStr(in));
                    if (b == null) throw new IllegalStateException("unknown named builtin in snapshot");
                    return b;
                }
                case 59: return Vm.CREATE_TASK_BUILTIN;
                case 60: return new SleepRequest(in.readLong());
                case 61: return Vm.MAKE_SLEEP_BUILTIN;
                case 62: return Vm.FINISH_DAEMON_BUILTIN;
                case 63: return Vm.RUN_BUILTIN;
                case 64: return Vm.TEST_AND_SET_BUILTIN;
                case 58: {                          // T_COMPILEDCODE
                    MpyModule cm = moduleTable[in.readInt()];
                    String csrc = readStr(in);
                    return new CompiledCode(cm, null, csrc);
                }
                case 43: return BuiltinType.byName(readStr(in));
                case T_NEXT_BUILTIN: return Vm.NEXT_BUILTIN;
                case T_LIST_BUILTIN: return Vm.LIST_BUILTIN;
                case T_BUILD_CLASS: return Vm.BUILD_CLASS;
                case T_SUPER_BUILTIN: return Vm.SUPER_BUILTIN;
                case T_SUM_BUILTIN: return Vm.SUM_BUILTIN;
                case T_MODULE: {
                    String name = readStr(in);
                    String root = readStr(in);
                    boolean initializing = in.readBoolean();
                    int idx = in.readInt();
                    MpyModule fm = idx < 0 ? null : moduleTable[idx];
                    PyModule pm = new PyModule(name, root, fm); // ctor links fm.ns
                    pm.initializing = initializing;
                    byId.add(pm);
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) { String k = readStr(in); pm.ns.put(k, readValue()); }
                    return pm;
                }
                case T_EXCTYPE: {
                    String name = readStr(in);
                    net.mpy.runtime.PyExc.Type t = net.mpy.runtime.PyExc.TYPES.get(name);
                    if (t == null) throw new IllegalStateException("unknown exception type in snapshot: " + name);
                    return t;
                }
                case T_USER_EXCTYPE: {
                    PyClass cls = (PyClass) readValue();   // its excType was rebuilt during class read
                    return cls.excType;
                }
                case T_USER_EXCINSTANCE: {
                    PyClass cls = (PyClass) readValue();
                    int n = in.readInt();
                    Object[] eargs = new Object[n];
                    for (int i = 0; i < n; i++) eargs[i] = readValue();
                    net.mpy.runtime.PyExc.Instance inst = new net.mpy.runtime.PyExc.Instance(cls.excType, eargs);
                    int nua = in.readInt();
                    for (int i = 0; i < nua; i++) { String k = readStr(in); inst.userAttrs.put(k, readValue()); }
                    int ntb = in.readInt();
                    for (int i = 0; i < ntb; i++) inst.traceback.add(new Object[]{readStr(in), in.readLong(), readStr(in)});
                    return inst;
                }
                case T_EXCINSTANCE: {
                    String name = readStr(in);
                    net.mpy.runtime.PyExc.Type t = net.mpy.runtime.PyExc.TYPES.get(name);
                    if (t == null) throw new IllegalStateException("unknown exception type in snapshot: " + name);
                    int n = in.readInt();
                    Object[] eargs = new Object[n];
                    for (int i = 0; i < n; i++) eargs[i] = readValue();
                    net.mpy.runtime.PyExc.Instance inst = new net.mpy.runtime.PyExc.Instance(t, eargs);
                    int nt = in.readInt();
                    for (int i = 0; i < nt; i++) {
                        inst.traceback.add(new Object[]{readStr(in), in.readLong(), readStr(in)});
                    }
                    return inst;
                }
                case T_FUNC: {
                    MpyModule fm = readModuleOf();
                    CodeObject code = codeAt(fm, readPath(in));
                    int n = in.readInt(); Object[] defs = new Object[n];
                    for (int i = 0; i < n; i++) defs[i] = readValue();
                    int nk = in.readInt();
                    java.util.Map<Object, Object> kwd = null;
                    if (nk >= 0) {
                        kwd = new java.util.LinkedHashMap<>();
                        for (int i = 0; i < nk; i++) { Object k = readValue(); Object vv = readValue(); kwd.put(k, vv); }
                    }
                    PyFunction fn = new PyFunction(code, code.module, defs, kwd);
                    if (in.readByte() == 1) {
                        fn.defSandbox = in.readBoolean();
                        fn.defScope = readNs();
                    }
                    int nAttrs = in.readInt();
                    for (int i = 0; i < nAttrs; i++) { String k = readStr(in); fn.attrsOrNew().put(k, readValue()); }
                    return fn;
                }
                case T_HOSTFUNC: {
                    String id = readStr(in);
                    HostFunction fn = hosts == null ? null : hosts.functionFor(id);
                    if (fn == null) {
                        throw new IllegalStateException("cannot restore host function id '" + id + "': "
                                + (hosts == null ? "no HostRegistry was supplied"
                                                 : "no such id in the supplied HostRegistry"));
                    }
                    return fn;
                }
                case T_BACKREF: {
                    int id = in.readInt();
                    if (id < 0 || id >= byId.size()) throw new IllegalStateException("bad snapshot back-reference " + id);
                    return byId.get(id);
                }
                default: throw new IllegalStateException("unknown snapshot value tag " + tag);
            }
        }
    }

    // ---- code-object paths --------------------------------------------------

    static int[] codePath(MpyModule module, CodeObject target) {
        List<Integer> path = new ArrayList<>();
        if (!findPath(module.root, target, path)) {
            throw new IllegalStateException("code object is not part of the module tree");
        }
        int[] r = new int[path.size()];
        for (int i = 0; i < r.length; i++) r[i] = path.get(i);
        return r;
    }

    private static boolean findPath(CodeObject node, CodeObject target, List<Integer> path) {
        if (node == target) return true;
        for (int i = 0; i < node.children.size(); i++) {
            path.add(i);
            if (findPath(node.children.get(i), target, path)) return true;
            path.remove(path.size() - 1);
        }
        return false;
    }

    static CodeObject codeAt(MpyModule module, int[] path) {
        CodeObject c = module.root;
        for (int idx : path) c = c.children.get(idx);
        return c;
    }

    // ---- primitives ---------------------------------------------------------

    private static void writePath(DataOutputStream out, int[] path) throws IOException {
        out.writeInt(path.length);
        for (int i : path) out.writeInt(i);
    }

    private static int[] readPath(DataInputStream in) throws IOException {
        int[] path = new int[in.readInt()];
        for (int i = 0; i < path.length; i++) path[i] = in.readInt();
        return path;
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length); out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()]; in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
