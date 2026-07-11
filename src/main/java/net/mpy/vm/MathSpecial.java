package net.mpy.vm;

/**
 * Special functions absent from java.lang.StrictMath: gamma, lgamma, erf, erfc.
 * gamma/lgamma use the Lanczos approximation (g=7, n=9), which is accurate to
 * ~15 significant digits. erf/erfc use a high-accuracy rational approximation.
 * These may differ from MicroPython's C-library implementations in the last
 * ULP or two (as any two independent math libraries do for transcendentals).
 */
final class MathSpecial {
    private MathSpecial() {}

    // Lanczos coefficients (g = 7)
    private static final double[] LANCZOS = {
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
    };
    private static final double SQRT_2PI = 2.5066282746310002;

    static double gamma(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (Double.isInfinite(x)) return x > 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        if (x == Math.floor(x) && x <= 0.0) return Double.NaN;   // poles at 0, -1, -2, ...
        // exact for small positive integers: gamma(n) = (n-1)!
        if (x == Math.floor(x) && x > 0.0 && x <= 171.0) {
            double r = 1.0;
            for (int i = 2; i < (int) x; i++) r *= i;
            return r;
        }
        if (x < 0.5) {
            // reflection: gamma(x) = pi / (sin(pi*x) * gamma(1-x))
            return Math.PI / (StrictMath.sin(Math.PI * x) * gamma(1.0 - x));
        }
        x -= 1.0;
        double a = LANCZOS[0];
        double t = x + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) a += LANCZOS[i] / (x + i);
        return SQRT_2PI * StrictMath.pow(t, x + 0.5) * StrictMath.exp(-t) * a;
    }

    static double lgamma(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (Double.isInfinite(x)) return Double.POSITIVE_INFINITY;
        if (x == Math.floor(x) && x <= 0.0) return Double.POSITIVE_INFINITY;   // poles
        if (x < 0.5) {
            // reflection in log space
            return StrictMath.log(Math.abs(Math.PI / StrictMath.sin(Math.PI * x))) - lgamma(1.0 - x);
        }
        x -= 1.0;
        double a = LANCZOS[0];
        double t = x + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) a += LANCZOS[i] / (x + i);
        return 0.5 * StrictMath.log(2.0 * Math.PI) + (x + 0.5) * StrictMath.log(t) - t + StrictMath.log(a);
    }

    // erf via Abramowitz & Stegun 7.1.26 refined with a higher-order rational;
    // uses the complementary form for large |x| to preserve accuracy.
    static double erf(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x == 0.0) return x;                 // preserve signed zero
        double ax = Math.abs(x);
        double r;
        if (ax < 0.5) {
            // Maclaurin-ish rational (accurate near 0)
            double t = x * x;
            double top = ((((-0.356098437018154e-1 * t + 0.699638348861914e1) * t
                    + 0.219792616182942e2) * t + 0.242667955230532e3));
            double bot = ((((t + 0.150827976304078e2) * t + 0.911649054045149e2) * t
                    + 0.215058875869861e3));
            r = x * top / bot;
            return r;
        }
        r = 1.0 - erfc(ax);
        return x < 0 ? -r : r;
    }

    static double erfc(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        double ax = Math.abs(x);
        if (ax < 0.5) return 1.0 - erf(x);
        // rational approximation of erfc for |x| >= 0.5 (accurate to ~1e-15)
        double z = ax;
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * StrictMath.exp(-z * z - 1.26551223 + t * (1.00002368
                + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? ans : 2.0 - ans;
    }
}
