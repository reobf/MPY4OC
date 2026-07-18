package net.mpy.vm;

import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A pickle-like serializer for data values, backed by Java's DataOutputStream
 * (primitive binary writes) rather than the CPython pickle opcode stream. It is
 * deliberately format-incompatible with real pickle: the stream starts with a
 * private magic and <b>protocol number -1</b> (stored as a byte 0xFF), so these
 * blobs can never be confused with pickle protocols 0-5.
 *
 * Scope: the picklable data types - None, bool, int, float, complex, str, bytes,
 * tuple, list, dict, set, range, and namedtuples. Back-references make cyclic and
 * shared structures round-trip. Functions, classes, generators, cells, and other
 * code-bearing objects are rejected with a clear error (they belong in a VM
 * snapshot, not a pickle). This shares the philosophy of the snapshot value
 * serializer but is standalone and needs no module context.
 */
public final class Pickle {
    private Pickle() {}

    /**
     * Optional guard consulted before {@code loads} allocates any array whose size
     * comes from the (untrusted) stream: bytes/str byte buffers, tuple/list/dict/set
     * element arrays, bigint magnitude, namedtuple fields. Given the requested size
     * in <b>bytes</b> (element count times a per-element estimate), it returns true
     * to allow the allocation and false to reject it. A false result makes
     * {@code loads} raise {@code ValueError} <i>before</i> the array is created, so a
     * malicious blob claiming a huge length can never allocate an oversized array.
     *
     * <p>Left null in a bare VM (no limit, original behaviour). The OpenComputers
     * host installs one that caps a single allocation at the machine's installed RAM,
     * so a crafted pickle can't demand a giant array. The guard is only a size gate;
     * it does not track cumulative memory (the VM's own memory limit does that).
     */
    public interface AllocGuard { boolean allow(long requestedBytes); }
    private static volatile AllocGuard allocGuard = null;
    public static void setAllocGuard(AllocGuard g) { allocGuard = g; }

    /** Per-element byte estimate for object arrays (a reference plus overhead). */
    private static final int REF_BYTES = 16;

    /** Validate a length read from the stream before allocating {@code count}
     *  elements of {@code elemBytes} each. Rejects negatives and, when a guard is
     *  installed, allocations larger than the guard permits. Returns the count. */
    private static int guardLen(int count, int elemBytes) {
        if (count < 0) throw new PyException("ValueError", "invalid pickle length: " + count);
        AllocGuard g = allocGuard;
        if (g != null) {
            long bytes = (long) count * elemBytes;
            if (!g.allow(bytes))
                throw new PyException("ValueError",
                        "pickle allocation of " + bytes + " bytes exceeds the memory limit");
        }
        return count;
    }

    // private magic + protocol -1 (as unsigned byte 0xFF) so it can't collide
    // with pickle protocols 0..5 or with our snapshot format.
    private static final int MAGIC0 = 0x6D, MAGIC1 = 0x70, MAGIC2 = 0x6A;  // "mpj"
    private static final int PROTOCOL = 0xFF;   // -1

    // type tags
    private static final int T_NONE = 0, T_TRUE = 1, T_FALSE = 2, T_LONG = 3, T_BIGINT = 4,
            T_DOUBLE = 5, T_STR = 6, T_BYTES = 7, T_TUPLE = 8, T_LIST = 9, T_DICT = 10,
            T_SET = 11, T_COMPLEX = 12, T_RANGE = 13, T_NAMEDTUPLE = 14, T_REF = 15;

    // ---- dumps ----
    public static PyObj.Bytes dumps(Object v) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeByte(MAGIC0); out.writeByte(MAGIC1); out.writeByte(MAGIC2);
            out.writeByte(PROTOCOL);
            new W(out).write(v);
        } catch (IOException e) {
            throw new PyException("RuntimeError", "pickle write failed: " + e.getMessage());
        }
        return new PyObj.Bytes(bo.toByteArray());
    }

    // ---- loads ----
    public static Object loads(Object data) {
        byte[] bytes;
        if (data instanceof PyObj.Bytes) bytes = ((PyObj.Bytes) data).data;
        else throw PyException.typeError("a bytes-like object is required");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if ((in.readByte() & 0xff) != MAGIC0 || (in.readByte() & 0xff) != MAGIC1
                    || (in.readByte() & 0xff) != MAGIC2) {
                throw new PyException("ValueError", "not an mpy-pickle stream");
            }
            int proto = in.readByte() & 0xff;
            if (proto != PROTOCOL) {
                throw new PyException("ValueError", "unsupported pickle protocol: " + (proto == PROTOCOL ? -1 : proto));
            }
            return new R(in).read();
        } catch (java.io.EOFException e) {
            throw new PyException("ValueError", "truncated pickle stream");
        } catch (IOException e) {
            throw new PyException("RuntimeError", "pickle read failed: " + e.getMessage());
        }
    }

    // ---- writer ----
    private static final class W {
        final DataOutputStream out;
        final IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        int nextId = 0;
        W(DataOutputStream out) { this.out = out; }

        void write(Object v) throws IOException {
            if (v == null || v == PyObj.NONE) { out.writeByte(T_NONE); return; }
            if (v instanceof Boolean) { out.writeByte((Boolean) v ? T_TRUE : T_FALSE); return; }
            if (v instanceof Long) { out.writeByte(T_LONG); out.writeLong((Long) v); return; }
            if (v instanceof BigInteger) {
                out.writeByte(T_BIGINT);
                byte[] b = ((BigInteger) v).toByteArray();
                out.writeInt(b.length); out.write(b);
                return;
            }
            if (v instanceof Double) { out.writeByte(T_DOUBLE); out.writeDouble((Double) v); return; }
            if (v instanceof String) { out.writeByte(T_STR); writeStr((String) v); return; }
            if (v instanceof PyObj.Complex) {
                out.writeByte(T_COMPLEX);
                out.writeDouble(((PyObj.Complex) v).re); out.writeDouble(((PyObj.Complex) v).im);
                return;
            }
            if (v instanceof PyObj.Range) {
                PyObj.Range r = (PyObj.Range) v;
                out.writeByte(T_RANGE);
                out.writeLong(r.start); out.writeLong(r.stop); out.writeLong(r.step);
                return;
            }
            // reference types (may be shared/cyclic): emit a back-ref if seen
            Integer id = seen.get(v);
            if (id != null) { out.writeByte(T_REF); out.writeInt(id); return; }

            if (v instanceof PyObj.Bytes) {
                seen.put(v, nextId++);
                out.writeByte(T_BYTES);
                byte[] b = ((PyObj.Bytes) v).data;
                out.writeInt(b.length); out.write(b);
                return;
            }
            if (v instanceof PyObj.Tuple) {
                PyObj.Tuple t = (PyObj.Tuple) v;
                seen.put(v, nextId++);
                if (t.fieldNames != null) {           // namedtuple
                    out.writeByte(T_NAMEDTUPLE);
                    writeStr(t.typeName);
                    out.writeInt(t.fieldNames.length);
                    for (String f : t.fieldNames) writeStr(f);
                    for (Object it : t.items) write(it);
                } else {
                    out.writeByte(T_TUPLE);
                    out.writeInt(t.items.length);
                    for (Object it : t.items) write(it);
                }
                return;
            }
            if (v instanceof PyObj.PyList) {
                seen.put(v, nextId++);
                out.writeByte(T_LIST);
                List<Object> xs = ((PyObj.PyList) v).items;
                out.writeInt(xs.size());
                for (Object it : xs) write(it);
                return;
            }
            if (v instanceof PyObj.PyDict) {
                seen.put(v, nextId++);
                out.writeByte(T_DICT);
                var m = ((PyObj.PyDict) v).map;
                out.writeInt(m.size());
                for (var e : m.entrySet()) { write(e.getKey()); write(e.getValue()); }
                return;
            }
            if (v instanceof PyObj.PySet) {
                seen.put(v, nextId++);
                out.writeByte(T_SET);
                var s = ((PyObj.PySet) v).items;
                out.writeInt(s.size());
                for (Object it : s) write(it);
                return;
            }
            throw new PyException("TypeError", "cannot pickle '" + StdLib.typeName(v) + "' object");
        }

        void writeStr(String s) throws IOException {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(b.length); out.write(b);
        }
    }

    // ---- reader ----
    private static final class R {
        final DataInputStream in;
        final List<Object> objs = new ArrayList<>();   // by id, for back-refs
        R(DataInputStream in) { this.in = in; }

        Object read() throws IOException {
            int tag = in.readByte() & 0xff;
            switch (tag) {
                case T_NONE: return PyObj.NONE;
                case T_TRUE: return Boolean.TRUE;
                case T_FALSE: return Boolean.FALSE;
                case T_LONG: return in.readLong();
                case T_BIGINT: { byte[] b = new byte[guardLen(in.readInt(), 1)]; in.readFully(b); return PyObj.normInt(new BigInteger(b)); }
                case T_DOUBLE: return in.readDouble();
                case T_STR: return readStr();
                case T_COMPLEX: return new PyObj.Complex(in.readDouble(), in.readDouble());
                case T_RANGE: return new PyObj.Range(in.readLong(), in.readLong(), in.readLong());
                case T_REF: {
                    int idx = in.readInt();
                    if (idx < 0 || idx >= objs.size())
                        throw new PyException("ValueError", "pickle back-reference out of range: " + idx);
                    return objs.get(idx);
                }
                case T_BYTES: {
                    byte[] b = new byte[guardLen(in.readInt(), 1)]; in.readFully(b);
                    PyObj.Bytes o = new PyObj.Bytes(b); objs.add(o); return o;
                }
                case T_TUPLE: {
                    int n = guardLen(in.readInt(), REF_BYTES);
                    Object[] items = new Object[n];
                    PyObj.Tuple t = new PyObj.Tuple(items);
                    objs.add(t);                         // reserve id before children
                    for (int i = 0; i < n; i++) items[i] = read();
                    return t;
                }
                case T_NAMEDTUPLE: {
                    String tname = readStr();
                    int nf = guardLen(in.readInt(), REF_BYTES);
                    String[] fields = new String[nf];
                    for (int i = 0; i < nf; i++) fields[i] = readStr();
                    Object[] items = new Object[nf];
                    PyObj.Tuple t = new PyObj.Tuple(items, fields, tname);
                    objs.add(t);
                    for (int i = 0; i < nf; i++) items[i] = read();
                    return t;
                }
                case T_LIST: {
                    PyObj.PyList o = new PyObj.PyList();
                    objs.add(o);
                    int n = guardLen(in.readInt(), REF_BYTES);
                    for (int i = 0; i < n; i++) o.items.add(read());
                    return o;
                }
                case T_DICT: {
                    PyObj.PyDict o = new PyObj.PyDict();
                    objs.add(o);
                    int n = guardLen(in.readInt(), 2 * REF_BYTES);   // key + value per entry
                    for (int i = 0; i < n; i++) { Object k = read(); Object val = read(); o.map.put(k, val); }
                    return o;
                }
                case T_SET: {
                    PyObj.PySet o = new PyObj.PySet();
                    objs.add(o);
                    int n = guardLen(in.readInt(), REF_BYTES);
                    for (int i = 0; i < n; i++) o.items.add(read());
                    return o;
                }
                default: throw new PyException("ValueError", "bad pickle tag: " + tag);
            }
        }

        String readStr() throws IOException {
            byte[] b = new byte[guardLen(in.readInt(), 1)]; in.readFully(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
