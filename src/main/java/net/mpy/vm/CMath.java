package net.mpy.vm;

import net.mpy.runtime.PyObj;

/** Complex transcendental functions for the cmath module. */
final class CMath {
    private CMath() {}

    static PyObj.Complex sqrt(PyObj.Complex z) {
        double r = Math.hypot(z.re, z.im);
        double re = StrictMath.sqrt((r + z.re) / 2.0);
        double im = StrictMath.sqrt((r - z.re) / 2.0);
        if (z.im < 0) im = -im;
        return new PyObj.Complex(re, im);
    }

    static PyObj.Complex exp(PyObj.Complex z) {
        double e = StrictMath.exp(z.re);
        return new PyObj.Complex(e * StrictMath.cos(z.im), e * StrictMath.sin(z.im));
    }

    static PyObj.Complex log(PyObj.Complex z) {
        double r = Math.hypot(z.re, z.im);
        return new PyObj.Complex(StrictMath.log(r), StrictMath.atan2(z.im, z.re));
    }

    static PyObj.Complex log10(PyObj.Complex z) {
        PyObj.Complex l = log(z);
        double ln10 = StrictMath.log(10.0);
        return new PyObj.Complex(l.re / ln10, l.im / ln10);
    }

    static PyObj.Complex sin(PyObj.Complex z) {
        return new PyObj.Complex(StrictMath.sin(z.re) * StrictMath.cosh(z.im),
                                 StrictMath.cos(z.re) * StrictMath.sinh(z.im));
    }

    static PyObj.Complex cos(PyObj.Complex z) {
        return new PyObj.Complex(StrictMath.cos(z.re) * StrictMath.cosh(z.im),
                                 -StrictMath.sin(z.re) * StrictMath.sinh(z.im));
    }
}
