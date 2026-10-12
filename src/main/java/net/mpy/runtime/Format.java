package net.mpy.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * str.format and format() with CPython's syntax: replacement fields
 * {name!conv:spec} with automatic ({}) or manual ({0}) numbering, .attr and
 * [index] lookups, !r/!s/!a conversions and {} nested inside the spec, and the
 * spec mini-language [[fill]align][sign][#][0][width][,|_][.precision][type]
 * with types b c d o x X n e E f F g G % s. A user __format__ gets the raw spec.
 * (Keyword fields are a KeyError: this VM's built-in methods take no keyword
 * arguments, and f-strings never generate them.) Float output is exact
 * (HALF_EVEN on the binary value), matching CPython's dtoa.
 */
public final class Format {
    private Format() {}

    static String apply(String tmpl, Object[] args) { return apply(tmpl, args, PyHooks.PLAIN); }

    public static String apply(String tmpl, Object[] args, PyHooks h) {
        return new Fields(args, h).render(tmpl, 2);
    }

    /** One str.format call: the arguments plus the auto/manual numbering state,
     *  shared with fields nested inside specs ("{:{}}"). */
    private static final class Fields {
        final Object[] args;
        final PyHooks h;
        int auto;
        int mode;   // 0 = not yet chosen, 1 = automatic, 2 = manual

        Fields(Object[] args, PyHooks h) { this.args = args; this.h = h; }

        String render(String tmpl, int depth) {
            if (depth < 0) throw PyException.valueError("Max string recursion exceeded");
            StringBuilder out = new StringBuilder();
            int i = 0, n = tmpl.length();
            while (i < n) {
                char c = tmpl.charAt(i);
                if (c == '{') {
                    if (i + 1 < n && tmpl.charAt(i + 1) == '{') { out.append('{'); i += 2; continue; }
                    if (i + 1 >= n) throw PyException.valueError("Single '{' encountered in format string");
                    int j = i + 1, nest = 1;
                    for (; j < n; j++) {
                        char d = tmpl.charAt(j);
                        if (d == '{') nest++;
                        else if (d == '}' && --nest == 0) break;
                    }
                    if (j >= n) throw PyException.valueError("expected '}' before end of string");
                    out.append(field(tmpl.substring(i + 1, j), depth));
                    i = j + 1;
                } else if (c == '}') {
                    if (i + 1 < n && tmpl.charAt(i + 1) == '}') { out.append('}'); i += 2; continue; }
                    throw PyException.valueError("Single '}' encountered in format string");
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        private String field(String f, int depth) {
            int k = 0, brackets = 0;
            for (; k < f.length(); k++) {
                char d = f.charAt(k);
                if (d == '[') brackets++;
                else if (d == ']') brackets--;
                else if (brackets == 0 && (d == '!' || d == ':')) break;
            }
            Object v = lookup(f.substring(0, k));
            if (k < f.length() && f.charAt(k) == '!') {
                if (k + 1 >= f.length()) throw PyException.valueError("end of string while looking for conversion specifier");
                char conv = f.charAt(k + 1);
                k += 2;
                if (k < f.length() && f.charAt(k) != ':') throw PyException.valueError("expected ':' after conversion specifier");
                switch (conv) {
                    case 'r': v = h.repr(v); break;
                    case 's': v = h.str(v); break;
                    case 'a': v = PyObj.asciiEscape(h.repr(v)); break;
                    default: throw PyException.valueError("Unknown conversion specifier " + conv);
                }
            }
            String spec = k < f.length() ? f.substring(k + 1) : "";
            if (spec.indexOf('{') >= 0) spec = render(spec, depth - 1);
            return formatValue(v, spec, h);
        }

        /** arg_name ("." attr | "[" index "]")* */
        private Object lookup(String name) {
            int k = 0;
            while (k < name.length() && name.charAt(k) != '.' && name.charAt(k) != '[') k++;
            String first = name.substring(0, k);
            Object v;
            if (first.isEmpty()) {
                if (mode == 2) throw PyException.valueError("cannot switch from manual field specification to automatic field numbering");
                mode = 1;
                v = arg(auto++);
            } else if (isDigits(first)) {
                if (mode == 1) throw PyException.valueError("cannot switch from automatic field numbering to manual field specification");
                mode = 2;
                if (first.length() > 9) throw PyException.valueError("Too many decimal digits in format string");
                v = arg(Integer.parseInt(first));
            } else {
                throw PyException.keyError(first);
            }
            while (k < name.length()) {
                if (name.charAt(k) == '.') {
                    int e = k + 1;
                    while (e < name.length() && name.charAt(e) != '.' && name.charAt(e) != '[') e++;
                    if (e == k + 1) throw PyException.valueError("Empty attribute in format string");
                    v = h.getAttr(v, name.substring(k + 1, e));
                    k = e;
                } else {
                    int e = name.indexOf(']', k);
                    if (e < 0) throw PyException.valueError("Missing ']' in format string");
                    if (e == k + 1) throw PyException.valueError("Empty attribute in format string");
                    String key = name.substring(k + 1, e);
                    Object index = isDigits(key) && key.length() <= 18 ? (Object) Long.parseLong(key) : key;
                    Object r = h.callDunder(v, "__getitem__", index);
                    v = r != null ? r : Ops.subscrGet(v, index);
                    k = e + 1;
                    if (k < name.length() && name.charAt(k) != '.' && name.charAt(k) != '[')
                        throw PyException.valueError("Only '.' or '[' may follow ']' in format field specifier");
                }
            }
            return v;
        }

        private Object arg(int i) {
            if (i >= args.length) throw new PyException("IndexError", "tuple index out of range");
            return args[i];
        }
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    /** A parsed [[fill]align][sign][#][0][width][,|_][.precision][type]. */
    private static final class Spec {
        int fill = ' ';
        char align, sign, grouping, type;
        boolean alt;
        int width = -1, prec = -1;

        Spec(String s) {
            int i = 0, n = s.length();
            boolean fillGiven = false;
            int fillLen = n > 0 ? Character.charCount(s.codePointAt(0)) : 0;
            if (n > fillLen && isAlign(s.charAt(fillLen))) {
                fill = s.codePointAt(0); fillGiven = true;
                align = s.charAt(fillLen); i = fillLen + 1;
            } else if (n > 0 && isAlign(s.charAt(0))) {
                align = s.charAt(0); i = 1;
            }
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-' || s.charAt(i) == ' ')) sign = s.charAt(i++);
            if (i < n && s.charAt(i) == '#') { alt = true; i++; }
            if (!fillGiven && i < n && s.charAt(i) == '0') {
                fill = '0';
                if (align == 0) align = '=';
                i++;
            }
            int start = i;
            width = number(s, i);
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == start) width = -1;
            if (i < n && (s.charAt(i) == ',' || s.charAt(i) == '_')) grouping = s.charAt(i++);
            if (i < n && s.charAt(i) == '.') {
                start = ++i;
                prec = number(s, i);
                while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == start) throw PyException.valueError("Format specifier missing precision");
            }
            if (n - i > 1) throw PyException.valueError("Invalid format specifier");
            if (i < n) type = s.charAt(i);
            if (grouping != 0) {
                boolean ok = "defgEFG%".indexOf(type) >= 0 || type == 0
                        || (grouping == '_' && "boxX".indexOf(type) >= 0);
                if (!ok) throw PyException.valueError("Cannot specify '" + grouping + "' with '" + type + "'.");
            }
            Ops.checkAlloc(Math.max(0, Math.max(width, prec)), 2);
        }

        private static int number(String s, int i) {
            long v = 0;
            for (; i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9'; i++) {
                v = v * 10 + (s.charAt(i) - '0');
                if (v > Integer.MAX_VALUE) throw PyException.valueError("Too many decimal digits in format string");
            }
            return (int) v;
        }
    }

    /** format(v, spec): a user __format__ first; an empty spec is str(v);
     *  otherwise the int/float/str mini-language. */
    public static String formatValue(Object v, String spec, PyHooks h) {
        Object r = h.callDunder(v, "__format__", spec);
        if (r != null) {
            if (!(r instanceof String))
                throw PyException.typeError("__format__ must return a str, not " + net.mpy.vm.StdLib.typeName(r));
            return (String) r;
        }
        if (spec.isEmpty()) return v instanceof String ? (String) v : h.str(v);
        Object u = Ops.unwrapNative(v);
        Spec s = new Spec(spec);
        if (u instanceof Long || u instanceof BigInteger || u instanceof Boolean) return formatInt(u, s, v);
        if (u instanceof Double) return formatFloat((Double) u, s, v);
        if (u instanceof String) return formatStr((String) u, s);
        throw PyException.typeError("unsupported format string passed to " + net.mpy.vm.StdLib.typeName(v) + ".__format__");
    }

    private static PyException unknownCode(char type, Object v) {
        return PyException.valueError("Unknown format code '" + type + "' for object of type '" + net.mpy.vm.StdLib.typeName(v) + "'");
    }

    private static String formatStr(String str, Spec s) {
        if (s.type != 0 && s.type != 's') throw unknownCode(s.type, str);
        if (s.sign != 0) throw PyException.valueError("Sign not allowed in string format specifier");
        if (s.alt) throw PyException.valueError("Alternate form (#) not allowed in string format specifier");
        if (s.align == '=') throw PyException.valueError("'=' alignment not allowed in string format specifier");
        if (s.grouping != 0) throw PyException.valueError("Cannot specify '" + s.grouping + "' with 's'.");
        if (s.prec >= 0 && str.codePointCount(0, str.length()) > s.prec) str = str.substring(0, str.offsetByCodePoints(0, s.prec));
        return pad("", "", str, "", s, '<');
    }

    private static String formatInt(Object u, Spec s, Object v) {
        BigInteger x = u instanceof BigInteger ? (BigInteger) u
                : BigInteger.valueOf(u instanceof Boolean ? ((Boolean) u ? 1 : 0) : (Long) u);
        char t = s.type;
        if ("eEfFgG%".indexOf(t) >= 0 && t != 0) return formatFloat(Ops.intToDouble(x), s, v);
        if (s.prec >= 0) throw PyException.valueError("Precision not allowed in integer format specifier");
        if (t == 'c') {
            if (s.sign != 0) throw PyException.valueError("Sign not allowed with integer format specifier 'c'");
            if (x.signum() < 0 || x.compareTo(BigInteger.valueOf(0x10FFFF)) > 0)
                throw new PyException("OverflowError", "%c arg not in range(0x110000)");
            return pad("", "", new String(Character.toChars(x.intValue())), "", s, '>');
        }
        int base;
        switch (t) {
            case 0: case 'd': case 'n': base = 10; break;
            case 'b': base = 2; break;
            case 'o': base = 8; break;
            case 'x': case 'X': base = 16; break;
            default: throw unknownCode(t, v);
        }
        String digits = x.abs().toString(base);
        if (t == 'X') digits = digits.toUpperCase(java.util.Locale.ROOT);
        String prefix = s.alt && base != 10 ? "0" + t : "";
        return pad(signOf(x.signum() < 0, s.sign), prefix, digits, "", s, '>');
    }

    private static String formatFloat(double d, Spec s, Object v) {
        char t = s.type;
        String body;
        if (t == 0) body = floatNoType(d, s.prec, s.sign == 0 ? '-' : s.sign);
        else if ("eEfFgGn%".indexOf(t) >= 0) body = floatBody(d, t == 'n' ? 'g' : t, s.prec, s.sign == 0 ? '-' : s.sign, s.alt);
        else throw unknownCode(t, v);
        String sign = "";
        if (!body.isEmpty() && (body.charAt(0) == '-' || body.charAt(0) == '+' || body.charAt(0) == ' ')) {
            sign = body.substring(0, 1);
            body = body.substring(1);
        }
        // split the integer digits (grouped / zero-filled) from the rest
        int end = 0;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        return pad(sign, "", body.substring(0, end), body.substring(end), s, '>');
    }

    private static String signOf(boolean negative, char sign) {
        return negative ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
    }

    /** CPython's fill_number: sign, prefix, the digits (grouped with
     *  separators, which also run through '0' padding under '=' alignment),
     *  then the rest, aligned in the width with the fill character. */
    private static String pad(String sign, String prefix, String digits, String rest, Spec s, char defaultAlign) {
        char align = s.align != 0 ? s.align : defaultAlign;
        int lead = sign.length() + prefix.length();
        int restLen = rest.codePointCount(0, rest.length());
        if (s.grouping != 0 && !digits.isEmpty()) {   // (inf/nan have no digits)
            int minWidth = s.fill == '0' && align == '=' ? s.width - lead - restLen : 0;
            digits = group(digits, minWidth, "boxX".indexOf(s.type) >= 0 ? 4 : 3, s.grouping);
        }
        int len = lead + digits.codePointCount(0, digits.length()) + restLen;
        int padN = Math.max(0, s.width - len);
        String fill = new String(Character.toChars(s.fill));
        StringBuilder sb = new StringBuilder();
        switch (align) {
            case '<': sb.append(sign).append(prefix).append(digits).append(rest); rep(sb, fill, padN); break;
            case '^': rep(sb, fill, padN / 2); sb.append(sign).append(prefix).append(digits).append(rest); rep(sb, fill, padN - padN / 2); break;
            case '=': sb.append(sign).append(prefix); rep(sb, fill, padN); sb.append(digits).append(rest); break;
            default:  rep(sb, fill, padN); sb.append(sign).append(prefix).append(digits).append(rest); break;
        }
        return sb.toString();
    }

    /** CPython's _PyUnicode_InsertThousandsGrouping: separators every `size`
     *  digits from the right, zero-padding (zeros grouped too) to minWidth. */
    private static String group(String digits, int minWidth, int size, char sep) {
        StringBuilder rev = new StringBuilder();
        int remaining = digits.length(), pos = digits.length();
        boolean useSep = false;
        while (true) {
            int l = Math.min(size, Math.max(Math.max(remaining, minWidth), 1));
            int zeros = Math.max(0, l - remaining), chars = Math.max(0, Math.min(remaining, l));
            if (useSep) rev.append(sep);
            for (int k = 0; k < chars; k++) rev.append(digits.charAt(--pos));
            for (int k = 0; k < zeros; k++) rev.append('0');
            useSep = true;
            remaining -= chars;
            minWidth -= size;
            if (remaining <= 0 && minWidth <= 0) break;
            minWidth -= 1;
        }
        return rev.reverse().toString();
    }

    private static boolean isAlign(char c) { return c == '<' || c == '>' || c == '^' || c == '='; }
    private static void rep(StringBuilder sb, String s, int n) { for (int k = 0; k < n; k++) sb.append(s); }
    private static void rep(StringBuilder sb, char c, int n) { for (int k = 0; k < n; k++) sb.append(c); }

    /** A float with no type: repr() without a precision, else like 'g' but
     *  switching to an exponent one digit sooner and always keeping a ".0"
     *  (CPython's Py_DTSF_ADD_DOT_0). */
    private static String floatNoType(double d, int prec, char sign) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return floatBody(d, 'g', prec, sign, false);
        String pre = signOf(d < 0 || (d == 0 && 1 / d < 0), sign);
        double ad = Math.abs(d);
        if (prec < 0) return pre + PyObj.floatRepr(ad);
        int p = Math.max(prec, 1);
        BigDecimal bd = new BigDecimal(ad, new MathContext(p, RoundingMode.HALF_EVEN));
        int exp = ad == 0 ? 0 : bd.precision() - bd.scale() - 1;
        if (exp < -4 || exp >= p - 1) return pre + stripZeros(sci(ad, p - 1, true));
        String s = stripZeros(bd.toPlainString());
        return pre + (s.indexOf('.') < 0 ? s + ".0" : s);
    }

    static String floatBody(double d, char type, int prec, char sign) { return floatBody(d, type, prec, sign, false); }

    /** e/E/f/F/g/G/% bodies with C-printf semantics: exact HALF_EVEN rounding,
     *  %g strips trailing zeros, and alt (#) keeps the point ("1.", "1.e+00",
     *  "1.00000"). sign is '+', ' ' or '-' (only negatives signed); like
     *  CPython, NaN never shows a minus sign. */
    static String floatBody(double d, char type, int prec, char sign, boolean alt) {
        boolean upper = Character.isUpperCase(type);
        if (Double.isNaN(d)) return signOf(false, sign) + (upper ? "NAN" : "nan");
        String pre = signOf(d < 0 || (d == 0 && 1 / d < 0), sign);
        double ad = Math.abs(d);
        if (Double.isInfinite(ad)) return pre + (upper ? "INF" : "inf");
        char t = Character.toLowerCase(type);
        String s;
        switch (t) {
            case 'f': {
                if (prec < 0) prec = 6;
                s = new BigDecimal(ad).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
                if (alt && prec == 0) s += ".";
                break;
            }
            case '%': {
                if (prec < 0) prec = 6;
                s = new BigDecimal(ad).multiply(BigDecimal.valueOf(100))
                        .setScale(prec, RoundingMode.HALF_EVEN).toPlainString() + (alt && prec == 0 ? ".%" : "%");
                break;
            }
            case 'e': {
                if (prec < 0) prec = 6;
                s = sci(ad, prec, false);
                if (alt && prec == 0) s = s.replace("e", ".e");
                break;
            }
            case 'g': {
                int p = prec < 0 ? 6 : Math.max(prec, 1);
                BigDecimal bd = new BigDecimal(ad, new MathContext(p, RoundingMode.HALF_EVEN));
                int exp = ad == 0 ? 0 : bd.precision() - bd.scale() - 1;
                if (exp < -4 || exp >= p) {
                    s = sci(ad, p - 1, true);
                    if (!alt) s = stripZeros(s);
                    else if (p == 1) s = s.replace("e", ".e");
                } else if (!alt) {
                    s = stripZeros(bd.toPlainString());
                } else {
                    s = bd.setScale(p - 1 - exp, RoundingMode.HALF_EVEN).toPlainString();
                    if (s.indexOf('.') < 0) s += ".";
                }
                break;
            }
            default: throw PyException.valueError("unknown format code '" + type + "' for float");
        }
        if (upper) s = s.toUpperCase(java.util.Locale.ROOT);
        return pre + s;
    }

    /** C-style %e: d.ddddde±XX with at least two exponent digits. */
    private static String sci(double ad, int prec, boolean forG) {
        if (ad == 0) {
            StringBuilder m = new StringBuilder("0");
            if (prec > 0) { m.append('.'); rep(m, '0', prec); }
            return m + "e+00";
        }
        BigDecimal bd = new BigDecimal(ad, new MathContext(prec + 1, RoundingMode.HALF_EVEN));
        int exp = bd.precision() - bd.scale() - 1;
        BigDecimal mant = bd.movePointLeft(exp).setScale(prec, RoundingMode.HALF_EVEN);
        if (mant.abs().compareTo(BigDecimal.TEN) >= 0) { mant = mant.movePointLeft(1).setScale(prec, RoundingMode.HALF_EVEN); exp++; }
        String e = (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        return mant.toPlainString() + "e" + e;
    }

    /** %g trailing-zero stripping (keeps the mantissa's integer part). */
    private static String stripZeros(String s) {
        int e = s.indexOf('e');
        String mant = e < 0 ? s : s.substring(0, e);
        String tail = e < 0 ? "" : s.substring(e);
        if (mant.indexOf('.') >= 0) {
            int end = mant.length();
            while (end > 0 && mant.charAt(end - 1) == '0') end--;
            if (end > 0 && mant.charAt(end - 1) == '.') end--;
            mant = mant.substring(0, end);
        }
        return mant + tail;
    }
}
