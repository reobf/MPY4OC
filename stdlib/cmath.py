# frozen cmath module: complex transcendentals via Java primitives.
e = 2.718281828459045
pi = 3.141592653589793

sqrt = __cmath_sqrt
exp = __cmath_exp
log10 = __cmath_log10
sin = __cmath_sin
cos = __cmath_cos
phase = __cmath_phase
polar = __cmath_polar
rect = __cmath_rect

def log(x, base=None):
    r = __cmath_log(x)
    if base is None:
        return r
    return r / __cmath_log(base)
