package net.mpy.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * str.format: MicroPython's mini-language over positional arguments -
 * auto/indexed fields ({}, {0}), !r/!s conversions, and format specs
 * [[fill]align][sign][0][width][.precision][type] with types
 * d x X o b s f e E g G %. (Keyword fields aren't supported, matching this
 * VM's builtin-methods-take-no-kwargs rule; f-strings never generate them.)
 * Float paths round HALF_EVEN and %g strips trailing zeros, matching C printf.
 */
final class Format {
    private Format() {}

    static String apply(String tmpl, Object[] args) {
        StringBuilder out = new StringBuilder();
        int i = 0, auto = 0;
        int n = tmpl.length();
        while (i < n) {
            char c = tmpl.charAt(i);
            if (c == '{') {
                if (i + 1 < n && tmpl.charAt(i + 1) == '{') { out.append('{'); i += 2; continue; }
                int end = tmpl.indexOf('}', i);
                if (end < 0) throw PyException.valueError("single '{' encountered in format string");
                String field = tmpl.substring(i + 1, end);
                i = end + 1;

                String conv = null;
                int bang = field.indexOf('!');
                int colon = field.indexOf(':');
                String spec = "";
                if (colon >= 0 && (bang < 0 || colon < bang)) { spec = field.substring(colon + 1); field = field.substring(0, colon); bang = field.indexOf('!'); }
                if (bang >= 0) { conv = field.substring(bang + 1); field = field.substring(0, bang); }

                Object v;
                if (field.isEmpty()) {
                    if (auto < 0) throw PyException.valueError("can't switch from manual to automatic field numbering");
                    if (auto >= args.length) throw new PyException("IndexError", "tuple index out of range");
                    v = args[auto++];
                } else {
                    int idx;
                    try { idx = Integer.parseInt(field); }
                    catch (NumberFormatException e) { throw PyException.typeError("keyword fields are not supported by this VM's str.format"); }
                    if (idx >= args.length) throw new PyException("IndexError", "tuple index out of range");
                    v = args[idx];
                    auto = -1;   // manual mode
                }
                if ("r".equals(conv)) v = PyObj.repr(v);
                else if ("s".equals(conv)) v = net.mpy.vm.Builtins.str(v);
                out.append(formatValue(v, spec));
            } else if (c == '}') {
                if (i + 1 < n && tmpl.charAt(i + 1) == '}') { out.append('}'); i += 2; continue; }
                throw PyException.valueError("single '}' encountered in format string");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static String formatValue(Object v, String spec) {
        // parse [[fill]align][sign][0][width][.precision][type]
        char fill = ' ', align = 0, sign = '-';
        int width = 0, prec = -1;
        char type = 0;
        int i = 0, n = spec.length();
        if (n >= 2 && isAlign(spec.charAt(1))) { fill = spec.charAt(0); align = spec.charAt(1); i = 2; }
        else if (n >= 1 && isAlign(spec.charAt(0))) { align = spec.charAt(0); i = 1; }
        if (i < n && (spec.charAt(i) == '+' || spec.charAt(i) == '-' || spec.charAt(i) == ' ')) sign = spec.charAt(i++);
        if (i < n && spec.charAt(i) == '0') { if (align == 0) { fill = '0'; align = '='; } i++; }
        while (i < n && Character.isDigit(spec.charAt(i))) width = width * 10 + (spec.charAt(i++) - '0');
        if (i < n && spec.charAt(i) == '.') {
            i++; prec = 0;
            while (i < n && Character.isDigit(spec.charAt(i))) prec = prec * 10 + (spec.charAt(i++) - '0');
        }
        if (i < n) type = spec.charAt(i);

        String body;
        boolean numeric;
        if (v instanceof Long || v instanceof BigInteger || v instanceof Boolean) {
            BigInteger x = v instanceof BigInteger ? (BigInteger) v
                    : BigInteger.valueOf(v instanceof Boolean ? ((Boolean) v ? 1 : 0) : (Long) v);
            numeric = true;
            if (type == 'f' || type == 'F' || type == 'e' || type == 'E' || type == 'g' || type == 'G' || type == '%') {
                body = floatBody(x.doubleValue(), type, prec, sign);
            } else {
                boolean neg = x.signum() < 0;
                String digits;
                switch (type) {
                    case 0: case 'd': digits = x.abs().toString(); break;
                    case 'x': digits = x.abs().toString(16); break;
                    case 'X': digits = x.abs().toString(16).toUpperCase(); break;
                    case 'o': digits = x.abs().toString(8); break;
                    case 'b': digits = x.abs().toString(2); break;
                    case 'c': digits = new String(Character.toChars(x.intValueExact())); neg = false; break;
                    default: throw PyException.valueError("unknown format code '" + type + "' for int");
                }
                body = (neg ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "") + digits;
            }
        } else if (v instanceof Double) {
            numeric = true;
            body = floatBody((Double) v, type == 0 ? 'g' : type, prec, sign);
            if (type == 0 && body.indexOf('.') < 0 && body.indexOf('e') < 0
                    && body.indexOf("inf") < 0 && body.indexOf("nan") < 0) body += ".0";
        } else {
            numeric = false;
            String s = net.mpy.vm.Builtins.str(v);
            if (prec >= 0 && prec < s.length()) s = s.substring(0, prec);
            body = s;
        }

        if (body.length() >= width) return body;
        int pad = width - body.length();
        char a = align != 0 ? align : (numeric ? '>' : '<');
        StringBuilder sb = new StringBuilder();
        switch (a) {
            case '<': sb.append(body); rep(sb, fill, pad); break;
            case '>': rep(sb, fill, pad); sb.append(body); break;
            case '^': rep(sb, fill, pad / 2); sb.append(body); rep(sb, fill, pad - pad / 2); break;
            case '=': {
                int cut = (body.startsWith("-") || body.startsWith("+") || body.startsWith(" ")) ? 1 : 0;
                sb.append(body, 0, cut); rep(sb, fill, pad); sb.append(body, cut, body.length());
                break;
            }
        }
        return sb.toString();
    }

    private static boolean isAlign(char c) { return c == '<' || c == '>' || c == '^' || c == '='; }
    private static void rep(StringBuilder sb, char c, int n) { for (int k = 0; k < n; k++) sb.append(c); }

    /** f/e/g/% bodies with C-printf semantics (HALF_EVEN, %g strips zeros). */
    static String floatBody(double d, char type, int prec, char sign) {
        String pre = d < 0 || (d == 0 && 1 / d < 0) ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
        double ad = Math.abs(d);
        if (Double.isNaN(d)) return (Character.isUpperCase(type) ? "NAN" : "nan");
        if (Double.isInfinite(ad)) return pre + (Character.isUpperCase(type) ? "INF" : "inf");
        boolean upper = Character.isUpperCase(type);
        char t = Character.toLowerCase(type);
        String s;
        switch (t) {
            case 'f': {
                if (prec < 0) prec = 6;
                s = new BigDecimal(ad).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
                break;
            }
            case '%': {
                if (prec < 0) prec = 6;
                s = new BigDecimal(ad).multiply(BigDecimal.valueOf(100))
                        .setScale(prec, RoundingMode.HALF_EVEN).toPlainString() + "%";
                break;
            }
            case 'e': {
                if (prec < 0) prec = 6;
                s = sci(ad, prec, false);
                break;
            }
            case 'g': {
                int p = prec < 0 ? 6 : Math.max(prec, 1);
                BigDecimal bd = new BigDecimal(ad, new MathContext(p, RoundingMode.HALF_EVEN));
                int exp = ad == 0 ? 0 : bd.precision() - bd.scale() - 1;
                if (exp < -4 || exp >= p) {
                    s = stripZeros(sci(ad, p - 1, true));
                } else {
                    s = stripZeros(bd.toPlainString());
                }
                break;
            }
            default: throw PyException.valueError("unknown format code '" + type + "' for float");
        }
        if (upper) s = s.toUpperCase();
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
