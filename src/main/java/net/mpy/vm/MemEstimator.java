package net.mpy.vm;

import net.mpy.runtime.Methods;
import net.mpy.runtime.PyExc;
import net.mpy.runtime.PyObj;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Approximate live-size accounting for the memory limit (Phase 8): walks the
 * script-reachable object graph (frame chain + globals, following suspended
 * generator frames) summing rough per-object byte costs. Shared/cyclic
 * structures are counted once (identity-based).
 */
final class MemEstimator {

    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private long total;

    static long estimate(Frame top, Map<String, Object> globals) {
        MemEstimator e = new MemEstimator();
        for (Frame f = top; f != null; f = f.caller) e.addFrame(f);
        for (Map.Entry<String, Object> g : globals.entrySet()) {
            e.total += 48 + 2L * g.getKey().length();
            e.add(g.getValue());
        }
        return e.total;
    }

    private void addFrame(Frame f) {
        if (f == null || seen.put(f, Boolean.TRUE) != null) return;
        total += 96 + 8L * f.state.length + 24L * f.excHandler.length;
        for (Object o : f.state) add(o);
        for (int i = 0; i <= f.excSp; i++) add(f.excPrevExc[i]);
        add(f.returnOverride);
        // f.names aliases a PyClass ns counted via buildingClass
        if (f.buildingClass != null) add(f.buildingClass);
    }

    private void add(Object v) {
        if (v == null || v == PyObj.NONE || v == PyObj.ELLIPSIS || v instanceof Boolean) return;
        if (v instanceof Long || v instanceof Double) { total += 16; return; }
        // containers & compounds: identity-deduplicated
        if (v instanceof String) { total += 40 + 2L * ((String) v).length(); return; }
        if (seen.put(v, Boolean.TRUE) != null) return;
        if (v instanceof BigInteger) { total += 40 + ((BigInteger) v).bitLength() / 8; return; }
        if (v instanceof PyObj.Bytes) { total += 40 + ((PyObj.Bytes) v).data.length; return; }
        if (v instanceof PyObj.ByteArray) { total += 48 + ((PyObj.ByteArray) v).data.length; return; }
        if (v instanceof PyObj.Tuple) {
            Object[] it = ((PyObj.Tuple) v).items;
            total += 40 + 8L * it.length;
            for (Object o : it) add(o);
            return;
        }
        if (v instanceof PyObj.PyList) {
            var xs = ((PyObj.PyList) v).items;
            total += 56 + 8L * xs.size();
            for (Object o : xs) add(o);
            return;
        }
        if (v instanceof PyObj.PyDict) {
            var m = ((PyObj.PyDict) v).map;
            total += 64 + 48L * m.size();
            for (var e : m.entrySet()) { add(e.getKey()); add(e.getValue()); }
            return;
        }
        if (v instanceof PyObj.PySet) {
            var s = ((PyObj.PySet) v).items;
            total += 64 + 32L * s.size();
            for (Object o : s) add(o);
            return;
        }
        if (v instanceof PyObj.Iter) {
            PyObj.Iter it = (PyObj.Iter) v;
            total += 40 + 8L * it.items.size();
            for (Object o : it.items) add(o);
            return;
        }
        if (v instanceof PyObj.Cell) { total += 24; add(((PyObj.Cell) v).value); return; }
        if (v instanceof Closure) {
            total += 32;
            for (PyObj.Cell c : ((Closure) v).closed) add(c);
            return;
        }
        if (v instanceof PyFunction) {
            PyFunction fn = (PyFunction) v;
            total += 48;
            for (Object d : fn.defaults) add(d);
            if (fn.kwDefaults != null) for (var e : fn.kwDefaults.entrySet()) { add(e.getKey()); add(e.getValue()); }
            return;
        }
        if (v instanceof PyGen) { total += 64; addFrame(((PyGen) v).frame); return; }
        if (v instanceof PyClass) {
            PyClass c = (PyClass) v;
            total += 64 + 48L * c.ns.size();
            for (PyClass b : c.bases) add(b);
            for (var e : c.ns.entrySet()) add(e.getValue());
            return;
        }
        if (v instanceof PyInstance) {
            PyInstance i = (PyInstance) v;
            total += 48 + 48L * i.attrs.size();
            add(i.cls);
            for (var e : i.attrs.entrySet()) add(e.getValue());
            return;
        }
        if (v instanceof Descriptors.StaticMethod) { total += 24; add(((Descriptors.StaticMethod) v).fn); return; }
        if (v instanceof Descriptors.ClassMethod) { total += 24; add(((Descriptors.ClassMethod) v).fn); return; }
        if (v instanceof Descriptors.Property) { total += 24; add(((Descriptors.Property) v).getter); return; }
        if (v instanceof BoundPyMethod) { total += 32; add(((BoundPyMethod) v).fn); add(((BoundPyMethod) v).self); return; }
        if (v instanceof Methods.BoundMethod) { total += 32; add(((Methods.BoundMethod) v).self); return; }
        if (v instanceof PyExc.Instance) {
            total += 40;
            for (Object a : ((PyExc.Instance) v).args) add(a);
            return;
        }
        total += 32; // markers, exception types, host functions, slices, ...
    }
}
