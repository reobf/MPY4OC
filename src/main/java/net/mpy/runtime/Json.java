package net.mpy.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPython's json module for the frozen library: the parser and the serializer run here,
 * one host call each, with CPython's grammar (RFC 8259 plus NaN/Infinity), its error
 * messages and positions, and its output format (separators, indent, sort_keys,
 * ensure_ascii). json.py turns a failed parse into JSONDecodeError from the position
 * carried in the ValueError's args.
 */
public final class Json {
    private Json() {}

    private static String spaces(long n) {
        if (n <= 0) return "";
        Ops.checkAlloc(n, 2);
        StringBuilder b = new StringBuilder();
        for (long i = 0; i < n; i++) b.append(' ');
        return b.toString();
    }

    private static PyException error(String message, int pos) {
        return new PyException("ValueError", message, new Object[] {message, (long) pos});
    }

    // ==== loads ================================================================

    public static Object loads(String text) {
        return loads(text, false, true);
    }

    public static Object loads(String text, boolean pairs, boolean strict) {
        Parser p = new Parser(text, pairs, strict);
        if (text.startsWith("\uFEFF")) throw p.error("Unexpected UTF-8 BOM (decode using utf-8-sig)", 0);
        Object value = p.value(p.ws(0));
        int end = p.ws(p.pos);
        if (end != text.length()) throw p.error("Extra data", end);
        return value;
    }

    /** JSON positions count Unicode code points, independently of the VM's string storage. */
    public static Object location(String text, int pos) {
        int end = text.offsetByCodePoints(0, Math.max(0, Math.min(pos, text.codePointCount(0, text.length()))));
        long line = 1, column = 1;
        for (int i = 0; i < end;) {
            int cp = text.codePointAt(i);
            if (cp == '\n') { line++; column = 1; } else column++;
            i += Character.charCount(cp);
        }
        return new PyObj.Tuple(new Object[] {line, column});
    }

    private static final class Parser {
        final String s;
        final int n;
        int pos;
        int depth;
        final boolean pairs, strict;

        Parser(String s, boolean pairs, boolean strict) {
            this.s = s; this.n = s.length(); this.pairs = pairs; this.strict = strict;
        }

        private PyException error(String message, int index) {
            return Json.error(message, s.codePointCount(0, index));
        }

        int ws(int i) {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
            return i;
        }

        private static boolean digit(char c) { return c >= '0' && c <= '9'; }

        Object value(int i) {
            if (++depth > 256) throw new PyException("RecursionError", "maximum recursion depth exceeded while decoding JSON");
            try { return readValue(i); } finally { depth--; }
        }

        Object readValue(int i) {
            if (i >= n) throw error("Expecting value", i);
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(i);
                case '[': return array(i);
                case '"': return string(i);
                case 'n': if (s.startsWith("null", i)) { pos = i + 4; return PyObj.NONE; } break;
                case 't': if (s.startsWith("true", i)) { pos = i + 4; return Boolean.TRUE; } break;
                case 'f': if (s.startsWith("false", i)) { pos = i + 5; return Boolean.FALSE; } break;
                case 'N': if (s.startsWith("NaN", i)) { pos = i + 3; return Double.NaN; } break;
                case 'I': if (s.startsWith("Infinity", i)) { pos = i + 8; return Double.POSITIVE_INFINITY; } break;
                case '-':
                    if (s.startsWith("-Infinity", i)) { pos = i + 9; return Double.NEGATIVE_INFINITY; }
                    return number(i);
                default:
                    if (digit(c)) return number(i);
            }
            throw error("Expecting value", i);
        }

        /** CPython's NUMBER_RE: -?(0|[1-9]\d*)(\.\d+)?([eE][-+]?\d+)? */
        Object number(int i) {
            int j = i;
            if (j < n && s.charAt(j) == '-') j++;
            if (j < n && s.charAt(j) == '0') j++;
            else if (j < n && digit(s.charAt(j))) { while (j < n && digit(s.charAt(j))) j++; }
            else throw error("Expecting value", i);
            boolean isFloat = false;
            if (j + 1 < n && s.charAt(j) == '.' && digit(s.charAt(j + 1))) {
                isFloat = true;
                j++;
                while (j < n && digit(s.charAt(j))) j++;
            }
            if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                int k = j + 1;
                if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                if (k < n && digit(s.charAt(k))) {
                    isFloat = true;
                    j = k;
                    while (j < n && digit(s.charAt(j))) j++;
                }
            }
            pos = j;
            String text = s.substring(i, j);
            if (isFloat) return Double.parseDouble(text);
            try { return Long.parseLong(text); }
            catch (NumberFormatException e) { return new BigInteger(text); }
        }

        Object string(int start) {
            StringBuilder out = new StringBuilder();
            int i = start + 1;
            while (true) {
                if (i >= n) throw error("Unterminated string starting at", start);
                char c = s.charAt(i);
                if (c == '"') { pos = i + 1; return out.toString(); }
                if (strict && c < 0x20) throw error("Invalid control character at", i);
                if (c != '\\') { out.append(c); i++; continue; }
                if (i + 1 >= n) throw error("Unterminated string starting at", start);
                char e = s.charAt(i + 1);
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u': {
                        int code = hex4(i + 2);
                        i += 6;
                        // A high surrogate followed by a Unicode-escaped low surrogate is one character; a lone
                        // surrogate stays as it is, as CPython's decoder keeps it.
                        if (code >= 0xD800 && code <= 0xDBFF && i + 1 < n && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                            int low = hex4(i + 2);
                            if (low >= 0xDC00 && low <= 0xDFFF) { out.append((char) code).append((char) low); i += 6; continue; }
                        }
                        out.append((char) code);
                        continue;
                    }
                    default: throw error("Invalid \\escape", i);
                }
                i += 2;
            }
        }

        private int hex4(int i) {
            if (i + 4 > n) throw error("Invalid \\uXXXX escape", i - 1);
            int code = 0;
            for (int k = 0; k < 4; k++) {
                char c = s.charAt(i + k);
                int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
                        : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                if (d < 0) throw error("Invalid \\uXXXX escape", i - 1);
                code = code << 4 | d;
            }
            return code;
        }

        Object object(int i) {
            PyObj.PyDict out = new PyObj.PyDict();
            List<Object> items = pairs ? new ArrayList<>() : null;
            i = ws(i + 1);
            if (i < n && s.charAt(i) == '}') { pos = i + 1; return pairs ? new PyObj.Tuple(items.toArray()) : out; }
            while (true) {
                if (i >= n || s.charAt(i) != '"') throw error("Expecting property name enclosed in double quotes", i);
                Object key = string(i);
                i = ws(pos);
                if (i >= n || s.charAt(i) != ':') throw error("Expecting ':' delimiter", i);
                Object value = value(ws(i + 1));
                if (pairs) items.add(new PyObj.Tuple(new Object[] {key, value}));
                else out.map.put(key, value);
                i = ws(pos);
                if (i < n && s.charAt(i) == ',') {
                    int comma = i;
                    i = ws(i + 1);
                    if (i < n && s.charAt(i) == '}') throw error("Illegal trailing comma before end of object", comma);
                    continue;
                }
                if (i < n && s.charAt(i) == '}') { pos = i + 1; return pairs ? new PyObj.Tuple(items.toArray()) : out; }
                throw error("Expecting ',' delimiter", i);
            }
        }

        Object array(int i) {
            PyObj.PyList out = new PyObj.PyList();
            i = ws(i + 1);
            if (i < n && s.charAt(i) == ']') { pos = i + 1; return out; }
            while (true) {
                out.items.add(value(i));
                i = ws(pos);
                if (i < n && s.charAt(i) == ',') {
                    int comma = i;
                    i = ws(i + 1);
                    if (i < n && s.charAt(i) == ']') throw error("Illegal trailing comma before end of array", comma);
                    continue;
                }
                if (i < n && s.charAt(i) == ']') { pos = i + 1; return out; }
                throw error("Expecting ',' delimiter", i);
            }
        }
    }

    // ==== dumps ================================================================

    /**
     * @param indent     None, an int (that many spaces per level) or a str
     * @param itemSep    between items (CPython's item_separator)
     * @param keySep     between a key and its value (key_separator)
     * @param skipKeys   skip keys that are not str/int/float/bool/None instead of raising
     * @param allowNan   emit NaN/Infinity/-Infinity for such floats instead of raising
     */
    public static String dumps(Object obj, Object indent, String itemSep, String keySep, boolean sortKeys, boolean ensureAscii, boolean skipKeys, boolean allowNan) {
        return dumps(obj, indent, itemSep, keySep, sortKeys, ensureAscii, skipKeys, allowNan, true);
    }

    public static String dumps(Object obj, Object indent, String itemSep, String keySep, boolean sortKeys, boolean ensureAscii, boolean skipKeys, boolean allowNan, boolean checkCircular) {
        String indentText = indent == null || indent == PyObj.NONE ? null
                : indent instanceof String ? (String) indent
                : indent instanceof Boolean ? ((Boolean) indent ? " " : "")
                : spaces(((Number) indent).longValue());
        Writer w = new Writer(indentText, itemSep, keySep, sortKeys, ensureAscii, skipKeys, allowNan, checkCircular);
        w.write(obj, 0);
        return w.out.toString();
    }

    private static final class Writer {
        final StringBuilder out = new StringBuilder();
        final String indent, itemSep, keySep;
        final boolean sortKeys, ensureAscii, skipKeys, allowNan, checkCircular;
        final Map<Object, Boolean> open = new IdentityHashMap<>();   // the containers on the current path

        Writer(String indent, String itemSep, String keySep, boolean sortKeys, boolean ensureAscii, boolean skipKeys, boolean allowNan, boolean checkCircular) {
            this.indent = indent; this.itemSep = itemSep; this.keySep = keySep;
            this.sortKeys = sortKeys; this.ensureAscii = ensureAscii; this.skipKeys = skipKeys; this.allowNan = allowNan;
            this.checkCircular = checkCircular;
        }

        void write(Object o, int level) {
            if (level > 256) throw new PyException("RecursionError", "maximum recursion depth exceeded while encoding JSON");
            if (o instanceof net.mpy.vm.PyInstance && ((net.mpy.vm.PyInstance) o).nativeValue != null)
                o = ((net.mpy.vm.PyInstance) o).nativeValue;   // a subclass of str/list/dict: its native value
            if (o == null || o == PyObj.NONE) { out.append("null"); return; }
            if (o instanceof Boolean) { out.append((Boolean) o ? "true" : "false"); return; }
            if (o instanceof Long || o instanceof BigInteger) { out.append(o.toString()); return; }
            if (o instanceof Double) { out.append(floatText((Double) o)); return; }
            if (o instanceof String) { quote((String) o); return; }
            if (o instanceof PyObj.PyList) { array(((PyObj.PyList) o).items, o, level); return; }
            if (o instanceof PyObj.Tuple) { array(java.util.Arrays.asList(((PyObj.Tuple) o).items), o, level); return; }
            if (o instanceof PyObj.PyDict) { object((PyObj.PyDict) o, level); return; }
            throw PyException.typeError("Object of type " + Methods.typeName(o) + " is not JSON serializable");
        }

        private String floatText(double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                if (!allowNan) throw PyException.valueError("Out of range float values are not JSON compliant");
                return Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity";
            }
            return net.mpy.vm.Builtins.str(d);
        }

        private void enter(Object container) {
            if (checkCircular && open.put(container, Boolean.TRUE) != null) throw PyException.valueError("Circular reference detected");
        }

        private void newline(int level) {
            if (indent == null) return;
            out.append('\n');
            for (int i = 0; i < level; i++) out.append(indent);
        }

        private void array(List<Object> items, Object container, int level) {
            if (items.isEmpty()) { out.append("[]"); return; }
            enter(container);
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(itemSep);
                first = false;
                newline(level + 1);
                write(item, level + 1);
            }
            newline(level);
            out.append(']');
            open.remove(container);
        }

        private void object(PyObj.PyDict dict, int level) {
            if (dict.map.isEmpty()) { out.append("{}"); return; }
            enter(dict);
            List<Object[]> entries = new ArrayList<>();
            List<Map.Entry<Object, Object>> raw = new ArrayList<>(dict.map.entrySet());
            if (sortKeys) Collections.sort(raw, (a, b) -> compareKeys(a.getKey(), b.getKey()));
            for (Map.Entry<Object, Object> e : raw) {
                String key = keyText(e.getKey());
                if (key == null) {
                    if (skipKeys) continue;
                    throw PyException.typeError("keys must be str, int, float, bool or None, not " + Methods.typeName(e.getKey()));
                }
                entries.add(new Object[] {key, e.getValue()});
            }
            if (entries.isEmpty()) { out.append("{}"); open.remove(dict); return; }
            out.append('{');
            boolean first = true;
            for (Object[] entry : entries) {
                if (!first) out.append(itemSep);
                first = false;
                newline(level + 1);
                quote((String) entry[0]);
                out.append(keySep);
                write(entry[1], level + 1);
            }
            newline(level);
            out.append('}');
            open.remove(dict);
        }

        private String keyText(Object key) {
            key = Ops.unwrapNative(key);
            if (key instanceof String) return (String) key;
            if (key instanceof Boolean) return (Boolean) key ? "true" : "false";
            if (key == PyObj.NONE || key == null) return "null";
            if (key instanceof Long || key instanceof BigInteger) return key.toString();
            if (key instanceof Double) return floatText((Double) key);
            return null;
        }

        private int compareKeys(Object a, Object b) {
            a = Ops.unwrapNative(a); b = Ops.unwrapNative(b);
            if (a instanceof String && b instanceof String) {
                String x = (String) a, y = (String) b;
                int i = 0, j = 0;
                while (i < x.length() && j < y.length()) {
                    int p = x.codePointAt(i), q = y.codePointAt(j);
                    if (p != q) return Integer.compare(p, q);
                    i += Character.charCount(p); j += Character.charCount(q);
                }
                return Integer.compare(x.length() - i, y.length() - j);
            }
            return Ops.compare(a, b);
        }

        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private void quote(String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    default:
                        if (c < 0x20 || (ensureAscii && c > 0x7e)) {
                            out.append("\\u").append(HEX[c >> 12 & 15]).append(HEX[c >> 8 & 15]).append(HEX[c >> 4 & 15]).append(HEX[c & 15]);
                        } else out.append(c);
                }
            }
            out.append('"');
        }
    }
}
