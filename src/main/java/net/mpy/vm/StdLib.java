package net.mpy.vm;

import net.mpy.runtime.Ops;
import net.mpy.runtime.PyObj;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyExc;

import java.math.BigInteger;
import java.util.Map;

/**
 * The frozen standard library: math, json, random, heapq, time - each a
 * pure-Python module (compiled to .mpy and embedded in {@link StdLibData})
 * over a handful of Java primitives installed into the main globals.
 *
 * - math: transcendentals via StrictMath (bit-reproducible across JVMs)
 * - json: 100% pure Python (only the __jtype dispatcher)
 * - random: a pure-Python deterministic PRNG whose state lives in the module
 *   namespace, so snapshots capture and restore it
 * - heapq: pure Python
 * - time: host-overridable __time_ms / __time_sleep (defaults: a monotonic
 *   millisecond clock and a no-op sleep; game hosts should install their own
 *   deterministic clock for snapshot-safe behavior)
 *
 * Usage:
 *   StdLib.installPrimitives(globals);
 *   vm.setModuleFinder(ModuleFinder.chain(StdLib.finder(), myFs::get));
 */
public final class StdLib {
    private StdLib() {}

    private static final long T0 = System.nanoTime();

    /** A finder serving the embedded stdlib modules (flat names: "math", ...). */
    public static ModuleFinder finder() {
        return StdLibData.MODULES::get;
    }

    /** Install the Java primitives the frozen modules bind against. Hosts may
     *  overwrite __time_ms / __time_sleep afterwards with their own clock. */
    public static void installPrimitives(Map<String, Object> globals) {
        globals.putIfAbsent("__math_sqrt", f1(StrictMath::sqrt));
        globals.putIfAbsent("__math_sin", f1(StrictMath::sin));
        globals.putIfAbsent("__math_cos", f1(StrictMath::cos));
        globals.putIfAbsent("__math_tan", f1(StrictMath::tan));
        globals.putIfAbsent("__math_asin", f1(StrictMath::asin));
        globals.putIfAbsent("__math_acos", f1(StrictMath::acos));
        globals.putIfAbsent("__math_atan", f1(StrictMath::atan));
        globals.putIfAbsent("__math_exp", f1(StrictMath::exp));
        globals.putIfAbsent("__math_log", f1(StrictMath::log));
        globals.putIfAbsent("__math_log10", f1(StrictMath::log10));
        globals.putIfAbsent("__math_atan2", (HostFunction) a -> StrictMath.atan2(toF(a[0]), toF(a[1])));
        globals.putIfAbsent("__math_pow", (HostFunction) a -> StrictMath.pow(toF(a[0]), toF(a[1])));
        globals.putIfAbsent("__math_fmod", (HostFunction) a -> toF(a[0]) % toF(a[1]));  // Java % == C fmod
        globals.putIfAbsent("__math_sinh", f1(StrictMath::sinh));
        globals.putIfAbsent("__math_cosh", f1(StrictMath::cosh));
        globals.putIfAbsent("__math_tanh", f1(StrictMath::tanh));
        globals.putIfAbsent("__math_expm1", f1(StrictMath::expm1));
        globals.putIfAbsent("__math_log1p", f1(StrictMath::log1p));
        globals.putIfAbsent("__math_gamma", (HostFunction) a -> MathSpecial.gamma(toF(a[0])));
        globals.putIfAbsent("__math_lgamma", (HostFunction) a -> MathSpecial.lgamma(toF(a[0])));
        globals.putIfAbsent("__math_erf", (HostFunction) a -> MathSpecial.erf(toF(a[0])));
        globals.putIfAbsent("__math_erfc", (HostFunction) a -> MathSpecial.erfc(toF(a[0])));
        globals.putIfAbsent("__math_ldexp", (HostFunction) a -> StrictMath.scalb(toF(a[0]), (int) toLong(a[1])));
        // frexp returns (mantissa, exponent) with 0.5 <= |m| < 1
        // cmath primitives (operate on Complex, returning Complex or double)
        // hashlib: (algorithm-name, data-bytes) -> digest bytes
        globals.putIfAbsent("__hashlib_digest", (HostFunction) a -> {
            String alg = (String) a[0];
            byte[] data = ((PyObj.Bytes) a[1]).data;
            String jalg = alg.equals("sha256") ? "SHA-256" : alg.equals("sha1") ? "SHA-1" : alg.equals("md5") ? "MD5" : null;
            if (jalg == null) throw PyException.valueError("unsupported hash algorithm: " + alg);
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance(jalg);
                return new PyObj.Bytes(md.digest(data));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw PyException.valueError("unsupported hash algorithm: " + alg);
            }
        });
        globals.putIfAbsent("__cmath_sqrt", (HostFunction) a -> CMath.sqrt(cval(a[0])));
        globals.putIfAbsent("__cmath_exp", (HostFunction) a -> CMath.exp(cval(a[0])));
        globals.putIfAbsent("__cmath_log", (HostFunction) a -> CMath.log(cval(a[0])));
        globals.putIfAbsent("__cmath_log10", (HostFunction) a -> CMath.log10(cval(a[0])));
        globals.putIfAbsent("__cmath_sin", (HostFunction) a -> CMath.sin(cval(a[0])));
        globals.putIfAbsent("__cmath_cos", (HostFunction) a -> CMath.cos(cval(a[0])));
        globals.putIfAbsent("__cmath_phase", (HostFunction) a -> { PyObj.Complex c = cval(a[0]); return StrictMath.atan2(c.im, c.re); });
        globals.putIfAbsent("__cmath_polar", (HostFunction) a -> {
            PyObj.Complex c = cval(a[0]);
            return new PyObj.Tuple(new Object[]{ Math.hypot(c.re, c.im), StrictMath.atan2(c.im, c.re) });
        });
        globals.putIfAbsent("__cmath_rect", (HostFunction) a -> {
            double r = toF(a[0]), phi = toF(a[1]);
            return new PyObj.Complex(r * StrictMath.cos(phi), r * StrictMath.sin(phi));
        });
        globals.putIfAbsent("__math_frexp", (HostFunction) a -> {
            double x = toF(a[0]);
            if (x == 0.0 || Double.isNaN(x) || Double.isInfinite(x))
                return new PyObj.Tuple(new Object[]{ x, 0L });
            int e = Math.getExponent(x) + 1;
            double m = x / StrictMath.scalb(1.0, e);
            return new PyObj.Tuple(new Object[]{ m, (long) e });
        });
        globals.putIfAbsent("__jtype", (HostFunction) a -> typeName(a[0]));
        globals.putIfAbsent("__re_exec", (HostFunction) a -> reExec((String) a[0], (String) a[1],
                net.mpy.runtime.Ops.isTrue(a[2]), (int) (long) (Long) a[3]));
        globals.putIfAbsent("__struct_pack", (HostFunction) StdLib::structPack);
        globals.putIfAbsent("__struct_unpack", (HostFunction) a -> structUnpack((String) a[0], (PyObj.Bytes) a[1]));
        globals.putIfAbsent("__struct_calcsize", (HostFunction) a -> (long) fmtSize((String) a[0]));
        // binascii
        globals.putIfAbsent("__ba_hexlify", (HostFunction) a -> {
            byte[] d = asBytes(a[0]);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) { sb.append(Character.forDigit((b >> 4) & 0xf, 16)); sb.append(Character.forDigit(b & 0xf, 16)); }
            return new PyObj.Bytes(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        });
        globals.putIfAbsent("__ba_unhexlify", (HostFunction) a -> {
            byte[] h = asBytes(a[0]);
            if ((h.length & 1) != 0) throw PyException.valueError("odd-length hex string");
            byte[] out = new byte[h.length / 2];
            for (int i = 0; i < out.length; i++) {
                int hi = Character.digit(h[2 * i], 16), lo = Character.digit(h[2 * i + 1], 16);
                if (hi < 0 || lo < 0) throw PyException.valueError("non-hex digit");
                out[i] = (byte) ((hi << 4) | lo);
            }
            return new PyObj.Bytes(out);
        });
        globals.putIfAbsent("__ba_b64encode", (HostFunction) a -> new PyObj.Bytes(
                java.util.Base64.getEncoder().encode(asBytes(a[0]))));
        globals.putIfAbsent("__ba_b64decode", (HostFunction) a -> {
            try { return new PyObj.Bytes(java.util.Base64.getMimeDecoder().decode(asBytes(a[0]))); }
            catch (IllegalArgumentException e) { throw PyException.valueError("invalid base64"); }
        });
        // os.urandom
        globals.putIfAbsent("__os_urandom", (HostFunction) a -> {
            int n = (int) (long) (Long) a[0];
            byte[] b = new byte[n];
            new java.security.SecureRandom().nextBytes(b);
            return new PyObj.Bytes(b);
        });
        // gc (best-effort over the JVM; deterministic-enough for scripts)
        globals.putIfAbsent("__gc_collect", (HostFunction) a -> { System.gc(); return PyObj.NONE; });
        globals.putIfAbsent("__gc_mem_alloc", (HostFunction) a -> {
            Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory();
        });
        globals.putIfAbsent("__gc_mem_free", (HostFunction) a -> Runtime.getRuntime().freeMemory());
        // sys.print_exception(exc): format like a traceback to stdout
        // deflate: raw/zlib/gzip compress+decompress over java.util.zip
        globals.putIfAbsent("__deflate_compress", (HostFunction) a -> {
            byte[] data = asBytes(a[0]);
            int fmt = (int) (long) (Long) a[1];       // 0=raw 1=zlib 2=gzip (AUTO n/a for compress)
            int level = (int) (long) (Long) a[2];
            try { return new PyObj.Bytes(deflateCompress(data, fmt, level)); }
            catch (Exception e) { throw PyException.valueError("deflate: " + e.getMessage()); }
        });
        globals.putIfAbsent("__deflate_decompress", (HostFunction) a -> {
            byte[] data = asBytes(a[0]);
            int fmt = (int) (long) (Long) a[1];       // 0=raw 1=zlib 2=gzip 3=auto
            try { return new PyObj.Bytes(deflateDecompress(data, fmt)); }
            catch (Exception e) { throw PyException.valueError("deflate: " + e.getMessage()); }
        });
        // pickle (Java-backed data serialization; protocol -1)
        globals.putIfAbsent("__pickle_dumps", (HostFunction) a -> Pickle.dumps(a[0]));
        globals.putIfAbsent("__pickle_loads", (HostFunction) a -> Pickle.loads(a[0]));
        globals.putIfAbsent("__namedtuple", (HostFunction) a -> {
            String tname = (String) a[0];
            java.util.List<Object> fl = Builtins.toList(a[1]);
            String[] fields = new String[fl.size()];
            for (int i = 0; i < fields.length; i++) fields[i] = (String) fl.get(i);
            // the factory: called with positional values, returns a namedtuple Tuple
            return (HostFunction) vals -> {
                if (vals.length != fields.length)
                    throw PyException.typeError(tname + " expected " + fields.length + " arguments");
                return new PyObj.Tuple(vals.clone(), fields, tname);
            };
        });
        globals.putIfAbsent("__sys_print_exc", (HostFunction) a -> {
            if (a[0] instanceof PyExc.Instance) System.out.println(PyExc.formatTraceback((PyExc.Instance) a[0]));
            else System.out.println(net.mpy.vm.Builtins.str(a[0]));
            return PyObj.NONE;
        });
        globals.putIfAbsent("__time_ms", (HostFunction) a -> (System.nanoTime() - T0) / 1_000_000L);
        globals.putIfAbsent("__time_sleep", (HostFunction) a -> PyObj.NONE);
    }

    private interface D2D { double apply(double x); }
    private static HostFunction f1(D2D fn) { return a -> fn.apply(toF(a[0])); }

    // ---- deflate (raw/zlib/gzip) over java.util.zip -------------------------
    private static final int F_RAW = 0, F_ZLIB = 1, F_GZIP = 2, F_AUTO = 3;

    private static byte[] deflateCompress(byte[] data, int fmt, int level) throws java.io.IOException {
        if (level < 0 || level > 9) level = 6;
        if (fmt == F_GZIP) {
            final int lvl = level;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo) {
                { def.setLevel(lvl); }
            };
            gz.write(data); gz.finish(); gz.close();
            return bo.toByteArray();
        }
        java.util.zip.Deflater d = new java.util.zip.Deflater(level, fmt == F_RAW);
        d.setInput(data); d.finish();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) { int n = d.deflate(buf); bo.write(buf, 0, n); }
        d.end();
        return bo.toByteArray();
    }

    private static byte[] deflateDecompress(byte[] data, int fmt) throws java.io.IOException, java.util.zip.DataFormatException {
        if (fmt == F_AUTO) {                          // sniff: gzip magic, else zlib, else raw
            if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) fmt = F_GZIP;
            else if (data.length >= 1 && (data[0] & 0x0f) == 0x08) fmt = F_ZLIB;
            else fmt = F_RAW;
        }
        if (fmt == F_GZIP) {
            java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data));
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int n;
            while ((n = gz.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
        java.util.zip.Inflater inf = new java.util.zip.Inflater(fmt == F_RAW);
        inf.setInput(data);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && inf.needsInput()) break;
            bo.write(buf, 0, n);
        }
        inf.end();
        return bo.toByteArray();
    }

    /** bytes-like -> byte[] (accepts bytes or an ASCII str, like MicroPython). */
    static byte[] asBytes(Object o) {
        if (o instanceof PyObj.Bytes) return ((PyObj.Bytes) o).data;
        if (o instanceof String) return ((String) o).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        throw PyException.typeError("a bytes-like object is required");
    }

    private static PyObj.Complex cval(Object o) {
        if (o instanceof PyObj.Complex) return (PyObj.Complex) o;
        return new PyObj.Complex(toF(o), 0.0);
    }
    private static double toF(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Long) return (Long) o;
        if (o instanceof BigInteger) return ((BigInteger) o).doubleValue();
        if (o instanceof Boolean) return (Boolean) o ? 1.0 : 0.0;
        throw net.mpy.runtime.PyException.typeError("can't convert to float");
    }

    // ---- re: java.util.regex behind a single primitive ----

    private static final java.util.LinkedHashMap<String, java.util.regex.Pattern> RE_CACHE =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, java.util.regex.Pattern> e) {
                    return size() > 64;
                }
            };

    private static synchronized java.util.regex.Pattern rePattern(String p) {
        java.util.regex.Pattern pat = RE_CACHE.get(p);
        if (pat == null) {
            try { pat = java.util.regex.Pattern.compile(p); }
            catch (java.util.regex.PatternSyntaxException e) {
                throw net.mpy.runtime.PyException.valueError("bad regular expression: " + e.getDescription());
            }
            RE_CACHE.put(p, pat);
        }
        return pat;
    }

    /** None (null) or a flat span list [s0,e0,s1,e1,...] (-1 for unmatched groups). */
    private static Object reExec(String pattern, String s, boolean anchored, int pos) {
        java.util.regex.Matcher m = rePattern(pattern).matcher(s);
        m.region(pos, s.length());
        boolean hit = anchored ? m.lookingAt() : m.find();
        if (!hit) return PyObj.NONE;
        PyObj.PyList spans = new PyObj.PyList();
        for (int g = 0; g <= m.groupCount(); g++) {
            spans.items.add((long) m.start(g));
            spans.items.add((long) m.end(g));
        }
        return spans;
    }

    // ---- struct: a ByteBuffer-backed pack/unpack engine ----

    private record FmtItem(int count, char type) {}

    private static java.nio.ByteOrder fmtOrder(String fmt) {
        if (fmt.isEmpty()) return java.nio.ByteOrder.LITTLE_ENDIAN;
        char c = fmt.charAt(0);
        return (c == '>' || c == '!') ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN;
    }

    private static boolean fmtNative(String fmt) {
        return fmt.isEmpty() || "@<>=!".indexOf(fmt.charAt(0)) < 0 || fmt.charAt(0) == '@';
    }

    private static java.util.List<FmtItem> fmtItems(String fmt) {
        java.util.List<FmtItem> out = new java.util.ArrayList<>();
        int i = (!fmt.isEmpty() && "@<>=!".indexOf(fmt.charAt(0)) >= 0) ? 1 : 0;
        while (i < fmt.length()) {
            int n = -1;
            while (i < fmt.length() && Character.isDigit(fmt.charAt(i))) {
                n = (n < 0 ? 0 : n) * 10 + (fmt.charAt(i++) - '0');
            }
            if (i >= fmt.length()) throw net.mpy.runtime.PyException.valueError("bad struct format");
            char t = fmt.charAt(i++);
            if (t == ' ') continue;
            if ("bBhHiIlLqQfds".indexOf(t) < 0) {
                throw net.mpy.runtime.PyException.valueError("unsupported format character '" + t + "'");
            }
            out.add(new FmtItem(n < 0 ? 1 : n, t));
        }
        return out;
    }

    private static int typeSize(char t, boolean nat) {
        switch (t) {
            case 'b': case 'B': case 's': return 1;
            case 'h': case 'H': return 2;
            case 'i': case 'I': case 'f': return 4;
            case 'l': case 'L': return nat ? 8 : 4;
            case 'q': case 'Q': case 'd': return 8;
            default: throw new IllegalStateException();
        }
    }

    private static int align(int off, int size, boolean nat) {
        if (!nat || size <= 1) return off;
        int rem = off % size;
        return rem == 0 ? off : off + (size - rem);
    }

    private static int fmtSize(String fmt) {
        boolean nat = fmtNative(fmt);
        int off = 0;
        for (FmtItem it : fmtItems(fmt)) {
            int sz = typeSize(it.type(), nat);
            if (it.type() == 's') { off += it.count(); continue; }
            for (int k = 0; k < it.count(); k++) off = align(off, sz, nat) + sz;
        }
        return off;
    }

    private static long toLong(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof BigInteger) return ((BigInteger) o).longValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        throw net.mpy.runtime.PyException.typeError("an integer is required");
    }

    private static Object structPack(Object[] args) {
        String fmt = (String) args[0];
        boolean nat = fmtNative(fmt);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(fmtSize(fmt)).order(fmtOrder(fmt));
        int ai = 1;
        for (FmtItem it : fmtItems(fmt)) {
            char t = it.type();
            if (t == 's') {
                byte[] src = ((PyObj.Bytes) args[ai++]).data;
                byte[] dst = new byte[it.count()];
                System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
                buf.put(dst);
                continue;
            }
            int sz = typeSize(t, nat);
            for (int k = 0; k < it.count(); k++) {
                buf.position(align(buf.position(), sz, nat));
                Object v = args[ai++];
                switch (t) {
                    case 'b': case 'B': buf.put((byte) toLong(v)); break;
                    case 'h': case 'H': buf.putShort((short) toLong(v)); break;
                    case 'i': case 'I': buf.putInt((int) toLong(v)); break;
                    case 'l': case 'L': if (sz == 8) buf.putLong(toLong(v)); else buf.putInt((int) toLong(v)); break;
                    case 'q': case 'Q': buf.putLong(toLong(v)); break;
                    case 'f': buf.putFloat((float) toF(v)); break;
                    case 'd': buf.putDouble(toF(v)); break;
                }
            }
        }
        return new PyObj.Bytes(buf.array());
    }

    private static Object structUnpack(String fmt, PyObj.Bytes data) {
        boolean nat = fmtNative(fmt);
        int need = fmtSize(fmt);
        if (data.data.length < need) {
            throw net.mpy.runtime.PyException.valueError("buffer too small");
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data.data).order(fmtOrder(fmt));
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (FmtItem it : fmtItems(fmt)) {
            char t = it.type();
            if (t == 's') {
                byte[] b = new byte[it.count()];
                buf.get(b);
                out.add(new PyObj.Bytes(b));
                continue;
            }
            int sz = typeSize(t, nat);
            for (int k = 0; k < it.count(); k++) {
                buf.position(align(buf.position(), sz, nat));
                switch (t) {
                    case 'b': out.add((long) buf.get()); break;
                    case 'B': out.add(buf.get() & 0xffL); break;
                    case 'h': out.add((long) buf.getShort()); break;
                    case 'H': out.add(buf.getShort() & 0xffffL); break;
                    case 'i': out.add((long) buf.getInt()); break;
                    case 'I': out.add(buf.getInt() & 0xffffffffL); break;
                    case 'l': out.add(sz == 8 ? buf.getLong() : (long) buf.getInt()); break;
                    case 'L': out.add(sz == 8 ? unsignedLong(buf.getLong()) : (buf.getInt() & 0xffffffffL)); break;
                    case 'q': out.add(buf.getLong()); break;
                    case 'Q': out.add(unsignedLong(buf.getLong())); break;
                    case 'f': out.add((double) buf.getFloat()); break;
                    case 'd': out.add(buf.getDouble()); break;
                }
            }
        }
        return new PyObj.Tuple(out.toArray());
    }

    private static Object unsignedLong(long v) {
        if (v >= 0) return v;
        return new BigInteger(Long.toUnsignedString(v));
    }

    /** Python-visible type name of a runtime value (the __jtype dispatcher). */
    public static String typeName(Object o) {
        if (o == null || o == PyObj.NONE) return "NoneType";
        if (o instanceof Boolean) return "bool";
        if (o instanceof Long || o instanceof BigInteger) return "int";
        if (o instanceof Double) return "float";
        if (o instanceof String) return "str";
        if (o instanceof PyObj.PyList) return "list";
        if (o instanceof PyObj.Tuple) return "tuple";
        if (o instanceof PyObj.PyDict) return "dict";
        if (o instanceof PyObj.PySet) return "set";
        if (o instanceof PyObj.Bytes) return "bytes";
        if (o instanceof PyObj.Range) return "range";
        if (o instanceof PyGen) return "generator";
        if (o instanceof PyClass) return "type";
        if (o instanceof PyInstance) return ((PyInstance) o).cls.name;
        if (o instanceof PyModule) return "module";
        return "object";
    }
}
