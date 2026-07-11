package net.mpy.loader;

import net.mpy.qstr.QstrPool;
import net.mpy.qstr.StaticQstrs;
import net.mpy.runtime.PyObj;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 - loads a .mpy file (bytecode-only, format v6.x) into an
 * {@link MpyModule}. Faithful port of mp_raw_code_load / load_qstr / load_obj /
 * load_raw_code from py/persistentcode.c.
 */
public final class MpyLoader {

    public static final int MPY_VERSION = 6;
    private static final int NATIVE_ARCH_NONE = 0;
    private static final int FEATURE_ARCH_FLAGS = 0x40;

    // Persistent object type tags (py/persistentcode.h enum).
    private static final int OBJ_FUN_TABLE = 0, OBJ_NONE = 1, OBJ_FALSE = 2, OBJ_TRUE = 3,
            OBJ_ELLIPSIS = 4, OBJ_STR = 5, OBJ_BYTES = 6, OBJ_INT = 7, OBJ_FLOAT = 8,
            OBJ_COMPLEX = 9, OBJ_TUPLE = 10;

    private final MpyReader r;
    private final QstrPool pool;

    private MpyLoader(byte[] bytes, QstrPool pool) {
        this.r = new MpyReader(bytes);
        this.pool = pool;
    }

    /** Load a .mpy file from disk. */
    public static MpyModule loadFile(Path path, QstrPool pool) throws Exception {
        return load(Files.readAllBytes(path), pool);
    }

    /** Load a .mpy image from a byte array. */
    public static MpyModule load(byte[] bytes, QstrPool pool) {
        MpyModule m = load0(bytes, pool);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) sb.append(String.format("%02x", b));
            m.sha256 = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        m.rawBytes = bytes;
        setModule(m.root, m);
        return m;
    }

    private static void setModule(net.mpy.loader.CodeObject c, MpyModule m) {
        c.module = m;
        for (net.mpy.loader.CodeObject k : c.children) setModule(k, m);
    }

    private static MpyModule load0(byte[] bytes, QstrPool pool) {
        return new MpyLoader(bytes, pool).parse();
    }

    private MpyModule parse() {
        // --- header (4 bytes) ---
        byte[] header = r.readBytes(4);
        int magic = header[0] & 0xff;
        int version = header[1] & 0xff;
        int feature = header[2] & 0xff;
        int smallIntBits = header[3] & 0xff;
        int arch = (feature >> 2) & 0x2f;
        int subVersion = feature & 3;

        if (magic != 'M') {
            throw new MpyReader.MpyFormatException("not a .mpy file (bad magic 0x" + Integer.toHexString(magic) + ")");
        }
        if (version != MPY_VERSION) {
            throw new MpyReader.MpyFormatException("unsupported .mpy version " + version + " (loader supports " + MPY_VERSION + ")");
        }
        if (arch != NATIVE_ARCH_NONE) {
            throw new MpyReader.MpyFormatException("native .mpy (arch " + arch + ") not supported; bytecode-only loader");
        }
        if ((feature & FEATURE_ARCH_FLAGS) != 0) {
            throw new MpyReader.MpyFormatException("arch-specific flags present; not supported");
        }

        // --- global tables ---
        int nQstr = r.readUintInt();
        int nObj = r.readUintInt();

        String[] qstrTable = new String[nQstr];
        for (int i = 0; i < nQstr; i++) {
            qstrTable[i] = loadQstr();
        }
        Object[] objTable = new Object[nObj];
        for (int i = 0; i < nObj; i++) {
            objTable[i] = loadObj();
        }

        // --- top-level module code ---
        CodeObject root = loadRawCode();

        return new MpyModule(version, subVersion, arch, smallIntBits, qstrTable, objTable, root);
    }

    /** load_qstr: static reference or inline string (+null terminator). */
    private String loadQstr() {
        long len = r.readUint();
        if ((len & 1) != 0) {
            int globalNum = (int) (len >> 1);
            String s = StaticQstrs.resolve(globalNum);
            if (s == null) {
                throw new MpyReader.MpyFormatException("static qstr out of range: " + globalNum);
            }
            return s; // already interned by the pool's static seeding
        }
        int n = (int) (len >> 1);
        byte[] data = r.readBytes(n);
        r.readByte(); // discard null terminator
        String s = new String(data, StandardCharsets.UTF_8);
        pool.intern(s);
        return s;
    }

    /** load_obj: one constant object from the object table. */
    private Object loadObj() {
        int type = r.readByte();
        switch (type) {
            case OBJ_NONE:     return PyObj.NONE;
            case OBJ_FALSE:    return Boolean.FALSE;
            case OBJ_TRUE:     return Boolean.TRUE;
            case OBJ_ELLIPSIS: return PyObj.ELLIPSIS;
            case OBJ_FUN_TABLE:
                throw new MpyReader.MpyFormatException("mp_fun_table reference in bytecode-only .mpy");
            default: /* sized objects below */
        }

        int len = r.readUintInt();

        if (len == 0 && type == OBJ_BYTES) {
            r.readByte(); // null terminator
            return new PyObj.Bytes(new byte[0]);
        }
        if (type == OBJ_TUPLE) {
            Object[] items = new Object[len];
            for (int i = 0; i < len; i++) items[i] = loadObj();
            return new PyObj.Tuple(items);
        }

        byte[] data = r.readBytes(len);
        switch (type) {
            case OBJ_STR:
                r.readByte(); // null terminator
                return new String(data, StandardCharsets.UTF_8);
            case OBJ_BYTES:
                r.readByte(); // null terminator
                return new PyObj.Bytes(data);
            case OBJ_INT:
                // stored as a base-10 decimal string (no null terminator)
                return PyObj.normInt(new BigInteger(new String(data, StandardCharsets.US_ASCII)));
            case OBJ_FLOAT: {
                String fs = new String(data, StandardCharsets.US_ASCII).trim();
                String fl = fs.toLowerCase();
                if (fl.equals("inf") || fl.equals("+inf") || fl.equals("infinity")) return Double.POSITIVE_INFINITY;
                if (fl.equals("-inf") || fl.equals("-infinity")) return Double.NEGATIVE_INFINITY;
                if (fl.equals("nan") || fl.equals("-nan")) return Double.NaN;
                return Double.parseDouble(fs);
            }
            case OBJ_COMPLEX: {
                // "re+imj" or "imj" - parse leniently
                return parseComplex(new String(data, StandardCharsets.US_ASCII));
            }
            default:
                throw new MpyReader.MpyFormatException("unknown const obj type " + type);
        }
    }

    private static PyObj.Complex parseComplex(String s) {
        s = s.trim();
        if (s.endsWith("j") || s.endsWith("J")) s = s.substring(0, s.length() - 1);
        // find split point of the imaginary sign (not at position 0, not after 'e')
        int split = -1;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '+' || c == '-') && s.charAt(i - 1) != 'e' && s.charAt(i - 1) != 'E') split = i;
        }
        if (split < 0) return new PyObj.Complex(0.0, Double.parseDouble(s));
        double re = Double.parseDouble(s.substring(0, split));
        double im = Double.parseDouble(s.substring(split));
        return new PyObj.Complex(re, im);
    }

    /** load_raw_code: one (recursive) code unit. Bytecode kind only. */
    private CodeObject loadRawCode() {
        long kindLen = r.readUint();
        int kindNum = (int) (kindLen & 3);
        boolean hasChildren = (kindLen & 4) != 0;
        int funDataLen = (int) (kindLen >> 3);

        if (kindNum != 0) {
            throw new MpyReader.MpyFormatException(
                    "non-bytecode code unit (kind " + kindNum + ") not supported by this loader");
        }

        byte[] funData = r.readBytes(funDataLen);

        List<CodeObject> children = new ArrayList<>();
        if (hasChildren) {
            int nChildren = r.readUintInt();
            for (int i = 0; i < nChildren; i++) {
                children.add(loadRawCode());
            }
        }

        Prelude prelude = Prelude.parse(funData);
        return new CodeObject(CodeObject.Kind.BYTECODE, funData, prelude, children);
    }
}
