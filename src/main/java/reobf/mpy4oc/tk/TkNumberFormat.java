package reobf.mpy4oc.tk;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbers as a Spinbox reads and writes them: C's sscanf/printf (classic Tk, tkEntry.c) and
 * Tcl's scan/format (ttk::spinbox). Rounding is glibc's: the exact binary value, ties to even
 * (1.25 with one decimal is "1.2").
 */
final class TkNumberFormat {
    private TkNumberFormat() {}

    private static final Pattern DECIMAL = Pattern.compile("\\s*([+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern HEX = Pattern.compile("\\s*([+-]?)0[xX]((?:[0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+))(?:[pP]([+-]?[0-9]+))?");
    private static final Pattern SPECIAL = Pattern.compile("\\s*([+-]?)(inf|nan)", Pattern.CASE_INSENSITIVE);

    /**
     * The number at the start of {@code text}, as C's sscanf("%lf") ({@code c}: hexadecimal
     * "0x1p4", "inf" and "nan" too) or Tcl's scan %f reads it; null when there is none.
     */
    static Double scan(String text, boolean c) {
        if (c) {
            Matcher hex = HEX.matcher(text);
            if (hex.lookingAt()) {
                String digits = hex.group(2);
                double value = Double.parseDouble("0x" + (digits.startsWith(".") ? "0" + digits : digits) + "p" + (hex.group(3) == null ? "0" : clampExponent(hex.group(3))));
                return hex.group(1).equals("-") ? -value : value;
            }
            Matcher special = SPECIAL.matcher(text);
            if (special.lookingAt()) {
                if (special.group(2).equalsIgnoreCase("nan")) return Double.NaN;
                return special.group(1).equals("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
        }
        Matcher decimal = DECIMAL.matcher(text);
        if (!decimal.lookingAt()) return null;
        try { return Double.valueOf(decimal.group(1)); }
        catch (NumberFormatException odd) { return null; }
    }

    private static String clampExponent(String exponent) {
        try { return Integer.toString(Math.max(-100000, Math.min(100000, Integer.parseInt(exponent)))); }
        catch (NumberFormatException huge) { return exponent.startsWith("-") ? "-100000" : "100000"; }
    }

    // ---- C printf --------------------------------------------------------------------------------

    /** One conversion: %[flags][width][.precision]type, and literal text after it. */
    static final class Spec {
        boolean left, plus, space, zero, alternate;
        int width = -1, precision = -1;
        char type;
        String suffix = "";
    }

    static final int MAX_FIELD = 1024;

    /** printf of a floating-point conversion (f, e, E, g, G) with C's padding. */
    static String printf(Spec spec, double value) {
        boolean negative = Double.doubleToRawLongBits(value) < 0 && !Double.isNaN(value);
        double magnitude = Math.abs(value);
        char type = Character.toLowerCase(spec.type);
        String body;
        if (Double.isNaN(value)) body = "nan";
        else if (Double.isInfinite(value)) body = "inf";
        else if (type == 'f') body = fixed(magnitude, spec.precision < 0 ? 6 : spec.precision, spec.alternate);
        else if (type == 'e') body = scientific(magnitude, spec.precision < 0 ? 6 : spec.precision, spec.alternate);
        else body = general(magnitude, spec.precision < 0 ? 6 : Math.max(1, spec.precision), spec.alternate);
        if (Character.isUpperCase(spec.type)) body = body.toUpperCase(java.util.Locale.ROOT);
        String sign = negative ? "-" : spec.plus ? "+" : spec.space ? " " : "";
        return pad(sign, body, spec, Double.isFinite(value)) + spec.suffix;
    }

    private static String pad(String sign, String body, Spec spec, boolean zeroPads) {
        int length = sign.length() + body.length();
        if (spec.width <= length) return sign + body;
        StringBuilder out = new StringBuilder(spec.width);
        if (spec.left) { out.append(sign).append(body); while (out.length() < spec.width) out.append(' '); }
        else if (spec.zero && zeroPads) { out.append(sign); for (int i = length; i < spec.width; i++) out.append('0'); out.append(body); }
        else { for (int i = length; i < spec.width; i++) out.append(' '); out.append(sign).append(body); }
        return out.toString();
    }

    private static String fixed(double magnitude, int precision, boolean alternate) {
        String digits = new BigDecimal(magnitude).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
        return alternate && precision == 0 ? digits + "." : digits;
    }

    /** {@code significant} decimal digits of magnitude, rounded, and the power of ten of the first. */
    private static Object[] digits(double magnitude, int significant) {
        StringBuilder all = new StringBuilder();
        int exponent = 0;
        if (magnitude != 0) {
            BigDecimal rounded = new BigDecimal(magnitude).round(new MathContext(significant, RoundingMode.HALF_EVEN));
            String unscaled = rounded.unscaledValue().toString();
            exponent = unscaled.length() - 1 - rounded.scale();
            all.append(unscaled);
        }
        while (all.length() < significant) all.append('0');
        return new Object[] {all.substring(0, significant), exponent};
    }

    private static String scientific(double magnitude, int precision, boolean alternate) {
        Object[] d = digits(magnitude, precision + 1);
        String digits = (String) d[0];
        int exponent = (Integer) d[1], abs = Math.abs(exponent);
        return digits.charAt(0) + (precision > 0 || alternate ? "." : "") + digits.substring(1)
                + (exponent < 0 ? "e-" : "e+") + (abs < 10 ? "0" : "") + abs;
    }

    private static String general(double magnitude, int precision, boolean alternate) {
        int x = (Integer) digits(magnitude, precision)[1];
        String out = precision > x && x >= -4 ? fixed(magnitude, precision - 1 - x, alternate) : scientific(magnitude, precision - 1, alternate);
        if (alternate) return out;
        // %g drops trailing zeros, and then a bare decimal point, from the fraction.
        int e = out.indexOf('e');
        String mantissa = e < 0 ? out : out.substring(0, e), tail = e < 0 ? "" : out.substring(e);
        if (mantissa.indexOf('.') >= 0) {
            int end = mantissa.length();
            while (mantissa.charAt(end - 1) == '0') end--;
            if (mantissa.charAt(end - 1) == '.') end--;
            mantissa = mantissa.substring(0, end);
        }
        return mantissa + tail;
    }

    /**
     * A printf conversion at {@code format[at]} (just after its '%'): flags, width, precision and
     * type; null if it is not one of {@code types}. The rest of the string is its literal suffix
     * (which may not hold another conversion).
     */
    private static Spec parse(String format, int at, String types) {
        Spec spec = new Spec();
        int i = at, n = format.length();
        for (; i < n && "-+ 0#".indexOf(format.charAt(i)) >= 0; i++) {
            char f = format.charAt(i);
            if (f == '-') spec.left = true; else if (f == '+') spec.plus = true; else if (f == ' ') spec.space = true;
            else if (f == '0') spec.zero = true; else spec.alternate = true;
        }
        int start = i;
        while (i < n && Character.isDigit(format.charAt(i))) i++;
        if (i > start) spec.width = number(format.substring(start, i));
        if (i < n && format.charAt(i) == '.') {
            int p = ++i;
            while (i < n && Character.isDigit(format.charAt(i))) i++;
            spec.precision = i > p ? number(format.substring(p, i)) : 0;
        }
        if (i >= n || types.indexOf(format.charAt(i)) < 0) return null;
        spec.type = format.charAt(i);
        spec.suffix = format.substring(i + 1);
        return spec;
    }

    private static int number(String digits) {
        if (digits.length() > 4 || Integer.parseInt(digits) > MAX_FIELD) throw new IllegalArgumentException("format field wider than " + MAX_FIELD);
        return Integer.parseInt(digits);
    }

    // ---- classic Tk ----------------------------------------------------------------------------

    /** sscanf's %d at {@code at}: blanks, a sign, at least one digit; {value, end} or null. */
    private static long[] scanD(String text, int at) {
        int i = at, n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        boolean negative = false;
        if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) negative = text.charAt(i++) == '-';
        int digits = i;
        long value = 0;
        while (i < n && Character.isDigit(text.charAt(i))) value = Math.min(1L << 40, value * 10 + text.charAt(i++) - '0');
        return i == digits ? null : new long[] {negative ? -value : value, i};
    }

    private static boolean at(String text, int index, char c) { return index < text.length() && text.charAt(index) == c; }

    /**
     * A classic Spinbox -format, accepted as Tk 8.6 accepts it: it starts with '%', ends with 'f'
     * and sscanf reads it as "%<w>.<p>f" (p not negative), "%.<p>f", "%<w>f" or "%<w>.f" (w and p
     * not negative): "%05.1f", "%+5.1f", "%-6.2f", but not "%f", "%+.1f", "%d" or "%.2e".
     */
    static Spec classicFormat(String format) {
        IllegalArgumentException bad = new IllegalArgumentException("bad spinbox format specifier \"" + format + "\"");
        int n = format.length();
        if (n < 2 || format.charAt(0) != '%' || format.charAt(n - 1) != 'f') throw bad;
        long[] width = scanD(format, 1);
        boolean ok = false;
        if (width != null && at(format, (int) width[1], '.')) {
            long[] precision = scanD(format, (int) width[1] + 1);
            ok = precision != null && precision[0] >= 0 && at(format, (int) precision[1], 'f');
        }
        if (!ok && at(format, 1, '.')) {
            long[] precision = scanD(format, 2);
            ok = precision != null && precision[0] >= 0 && at(format, (int) precision[1], 'f');
        }
        if (!ok && width != null && width[0] >= 0)
            ok = at(format, (int) width[1], 'f') || at(format, (int) width[1], '.') && at(format, (int) width[1] + 1, 'f');
        if (!ok) throw bad;
        // printf reads the accepted text: anything sscanf let through that printf would not, is not taken.
        Spec spec = parse(format, 1, "f");
        if (spec == null || spec.suffix.indexOf('%') >= 0) throw bad;
        return spec;
    }

    /**
     * Tk's ComputeFormat: "%.<n>f", or "%.<n>e" when that is shorter, from the larger of |from|
     * and |to| and from the increment.
     */
    static Spec computed(double from, double to, double increment) {
        double maxValue = Math.max(Math.abs(from), Math.abs(to));
        if (maxValue == 0) maxValue = 1;
        int mostSigDigit = (int) Math.floor(Math.log10(maxValue));
        int leastSigDigit = Math.abs(increment) > 1e-9 ? (int) Math.floor(Math.log10(increment)) : 0;
        int numDigits = Math.max(1, mostSigDigit - leastSigDigit + 1);
        int eDigits = numDigits + 4 + (numDigits > 1 ? 1 : 0);
        int afterDecimal = Math.max(0, numDigits - mostSigDigit - 1);
        int fDigits = (mostSigDigit >= 0 ? mostSigDigit + afterDecimal : afterDecimal) + (afterDecimal > 0 ? 1 : 0) + (mostSigDigit < 0 ? 1 : 0);
        Spec spec = new Spec();
        if (fDigits <= eDigits) { spec.type = 'f'; spec.precision = afterDecimal; }
        else { spec.type = 'e'; spec.precision = numDigits - 1; }
        return spec;
    }

    /**
     * A classic Spinbox value: printf with -format, or the computed one. Tk 8.6 keeps only
     * |width| - 1 characters of a left-justified one ("%-6.2f" gives "0.00 "): so does this.
     */
    static String classic(String format, double from, double to, double increment, double value) {
        Spec spec = format.isEmpty() ? computed(from, to, increment) : classicFormat(format);
        String text = printf(spec, value);
        if (spec.left && spec.width > 0 && text.length() > spec.width - 1) text = text.substring(0, spec.width - 1);
        return text;
    }

    // ---- Tcl format (ttk) ------------------------------------------------------------------------

    /**
     * Tcl's [format $fmt $value] for one number: literal text, %% and one conversion. f, e, E, g
     * and G print the number, d and i a whole one, s its Tcl string; anything else is Tcl's error.
     */
    static String tcl(String format, double value) {
        StringBuilder out = new StringBuilder();
        boolean used = false;
        for (int i = 0; i < format.length();) {
            char c = format.charAt(i++);
            if (c != '%') { out.append(c); continue; }
            if (at(format, i, '%')) { out.append('%'); i++; continue; }
            if (used) throw new IllegalArgumentException("not enough arguments for all format specifiers");
            used = true;
            Spec spec = parse(format, i, "feEgGdis");
            if (spec == null) {
                int end = i;
                while (end < format.length() && "-+ 0#.123456789".indexOf(format.charAt(end)) >= 0) end++;
                if (end >= format.length()) throw new IllegalArgumentException("format string ended in middle of field specifier");
                throw new IllegalArgumentException("bad field specifier \"" + format.charAt(end) + "\"");
            }
            String suffix = spec.suffix;
            spec.suffix = "";
            switch (spec.type) {
                case 'd': case 'i': {
                    if (value != Math.rint(value) || Math.abs(value) >= 9.2e18)
                        throw new IllegalArgumentException("expected integer but got \"" + tclString(value) + "\"");
                    String digits = Long.toString(Math.abs((long) value));
                    StringBuilder zeros = new StringBuilder();
                    for (int k = digits.length(); k < spec.precision; k++) zeros.append('0');
                    out.append(pad(value < 0 ? "-" : spec.plus ? "+" : spec.space ? " " : "", zeros + digits, spec, spec.precision < 0));
                    break;
                }
                case 's': {
                    String s = tclString(value);
                    if (spec.precision >= 0 && s.length() > spec.precision) s = s.substring(0, spec.precision);
                    out.append(pad("", s, spec, false));
                    break;
                }
                default:
                    out.append(printf(spec, value).replace("nan", "NaN").replace("NAN", "NaN").replace("inf", "Inf").replace("INF", "Inf"));
            }
            // The rest is read on: it may hold %% and literal text, but not another conversion.
            format = suffix;
            i = 0;
        }
        return out.toString();
    }

    /** A double as Tcl writes it: the shortest digits that read back, with a '.' or an exponent. */
    static String tclString(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Inf" : "-Inf";
        String java = Double.toString(value);
        int e = java.indexOf('E');
        if (e < 0) return java;
        String mantissa = java.substring(0, e);
        int x = Integer.parseInt(java.substring(e + 1));
        if (x >= -4 && x < 17) return new BigDecimal(java).toPlainString() + (new BigDecimal(java).scale() <= 0 ? ".0" : "");
        if (mantissa.endsWith(".0")) mantissa = mantissa.substring(0, mantissa.length() - 2);
        return mantissa + "e" + (x < 0 ? "-" : "+") + (Math.abs(x) < 10 ? "0" : "") + Math.abs(x);
    }
}
