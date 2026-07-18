package net.mpy.compiler.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * MicroPython's repr(float), as written into the .mpy const object table.
 *
 * <p>This is <em>not</em> simply the shortest round-tripping decimal.
 * mp_format_float computes the decimal mantissa as
 * {@code (uint)(mp_decimal_exp(f, decexp) + 0.5)}, where mp_decimal_exp multiplies
 * by a power of ten using binary exponentiation in {@code long double} (80-bit on
 * x86-64). Rounding of those intermediate products can leave the mantissa one unit
 * off the mathematically exact value, which is why {@code 86.528} is written as
 * {@code 86.52800000000001}: at 16 significant digits the mantissa comes out as
 * 8652800000000001 rather than 8652800000000000.
 *
 * <p>The algorithm below mirrors the C one, using BigDecimal at 80-bit-significand
 * precision in place of {@code long double}. It starts at SAFE_MANTISSA_DIGITS (16)
 * and only grows the digit count when the result does not round-trip, exactly as
 * the C {@code try_again} loop does.
 */
public final class FloatRepr {
    private FloatRepr() {}

    private static final int MAX_MANTISSA_DIGITS = 19;
    private static final int SAFE_MANTISSA_DIGITS = 16;

    /** x87 long double: 64-bit significand, about 20 decimal digits. */
    private static final MathContext LONG_DOUBLE = new MathContext(20, RoundingMode.HALF_EVEN);

    public static String repr(double v) { return format(v, true); }

    /**
     * Like {@link #repr} but without forcing a decimal point, which is what
     * MicroPython uses for the parts of a complex number ("2j", not "2.0j").
     */
    public static String reprNoForcedDecimal(double v) { return format(v, false); }

    private static String format(double v, boolean alwaysDecimal) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        if (v == 0.0) {
            String z = alwaysDecimal ? "0.0" : "0";
            return (1 / v < 0) ? "-" + z : z;
        }

        boolean neg = v < 0;
        double a = Math.abs(v);
        int e = decimalExponent(a);

        String digits = null;
        for (int numDigits = SAFE_MANTISSA_DIGITS; numDigits <= MAX_MANTISSA_DIGITS; numDigits++) {
            String d = mantissaDigits(a, e, numDigits);
            String trimmed = d.replaceFirst("0+$", "");
            if (trimmed.isEmpty()) trimmed = "0";
            if (numDigits == MAX_MANTISSA_DIGITS || roundTrips(trimmed, e, a)) {
                digits = trimmed;
                break;
            }
        }
        if (digits == null) digits = "0";

        String body = (e < -4 || e >= 16) ? expForm(digits, e) : fixedForm(digits, e, alwaysDecimal);
        return neg ? "-" + body : body;
    }

    /**
     * The decimal exponent, computed the way mp_format_float does: estimate from
     * the binary exponent, then step until 10^(e+positive) brackets the value.
     * The estimate/step loop can land one below BigDecimal's exact exponent for
     * values like 1e-06, and that difference is visible in the output.
     */
    private static int decimalExponent(double a) {
        int e = (int) (Math.getExponent(a) * 0.3010299956639812);
        boolean positiveExp = !(a < 1.0);
        double uBase = decimalExp(BigDecimal.ONE, e + (positiveExp ? 1 : 0)).doubleValue();
        while ((a >= uBase) == positiveExp) {
            e += positiveExp ? 1 : -1;
            uBase = decimalExp(BigDecimal.ONE, e + (positiveExp ? 1 : 0)).doubleValue();
        }
        return e;
    }

    /** (uint)(mp_decimal_exp(f, numDigits - 1 - e) + 0.5), rendered as digits. */
    private static String mantissaDigits(double a, int e, int numDigits) {
        int decexp = numDigits - 1 - e;
        BigDecimal scaled = decimalExp(new BigDecimal(a), decexp);
        BigInteger mant = scaled.add(new BigDecimal("0.5")).toBigInteger();
        String s = mant.toString();
        if (s.length() > numDigits) s = s.substring(0, numDigits);
        while (s.length() < numDigits) s = s + "0";
        return s;
    }

    /** mp_decimal_exp: binary exponentiation by 10 at long-double precision. */
    private static BigDecimal decimalExp(BigDecimal num, int decExp) {
        if (decExp == 0 || num.signum() == 0) return num;
        boolean negExp = decExp < 0;
        int e = negExp ? -decExp : decExp;
        BigDecimal res = num;
        BigDecimal expo = BigDecimal.TEN;
        while (e != 0) {
            if ((e & 1) != 0) {
                res = negExp ? res.divide(expo, LONG_DOUBLE) : res.multiply(expo, LONG_DOUBLE);
            }
            e >>= 1;
            if (e != 0) expo = expo.multiply(expo, LONG_DOUBLE);
        }
        return res;
    }

    /** Does "<digits>e<e>" parse back to exactly this double? */
    private static boolean roundTrips(String digits, int e, double a) {
        try {
            String s = digits.charAt(0)
                    + (digits.length() > 1 ? "." + digits.substring(1) : "")
                    + "e" + e;
            return Double.parseDouble(s) == a;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String expForm(String digits, int exp) {
        StringBuilder sb = new StringBuilder();
        sb.append(digits.charAt(0));
        if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
        sb.append('e').append(exp < 0 ? '-' : '+');
        int ae = Math.abs(exp);
        if (ae < 10) sb.append('0');
        sb.append(ae);
        return sb.toString();
    }

    private static String fixedForm(String digits, int exp, boolean alwaysDecimal) {
        StringBuilder sb = new StringBuilder();
        if (exp >= 0) {
            if (digits.length() > exp + 1) {
                sb.append(digits, 0, exp + 1).append('.').append(digits, exp + 1, digits.length());
            } else {
                sb.append(digits);
                for (int i = digits.length(); i <= exp; i++) sb.append('0');
                if (alwaysDecimal) sb.append(".0");
            }
        } else {
            sb.append("0.");
            for (int i = 0; i < -exp - 1; i++) sb.append('0');
            sb.append(digits);
        }
        return sb.toString();
    }
}
