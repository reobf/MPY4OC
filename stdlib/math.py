# frozen math module: thin Java primitives (__math_*) + pure-Python sugar.
pi = 3.141592653589793
e = 2.718281828459045
tau = 6.283185307179586
inf = float("inf")
nan = float("nan")

sqrt = __math_sqrt
sin = __math_sin
cos = __math_cos
tan = __math_tan
asin = __math_asin
acos = __math_acos
atan = __math_atan
atan2 = __math_atan2
exp = __math_exp
pow = __math_pow
log10 = __math_log10

def log(x, base=None):
    r = __math_log(x)
    if base is None:
        return r
    return r / __math_log(base)

def log2(x):
    r = __math_log(x) * 1.4426950408889634
    ri = int(r + 0.5)
    if __math_pow(2.0, ri) == x:    # snap exact powers of two (matches C log2)
        return float(ri)
    return r

def floor(x):
    i = int(x)
    if i > x:
        i -= 1
    return i

def ceil(x):
    i = int(x)
    if i < x:
        i += 1
    return i

def trunc(x):
    return int(x)

def fabs(x):
    return abs(float(x))

def degrees(x):
    return x * 180.0 / pi

def radians(x):
    return x * pi / 180.0

def isnan(x):
    return x != x

def isinf(x):
    return x == inf or x == -inf

def copysign(x, y):
    m = fabs(x)
    if y < 0 or (y == 0.0 and str(y)[0] == "-"):
        return -m
    return m

def fmod(x, y):
    return __math_fmod(x, y)

sinh = __math_sinh
cosh = __math_cosh
tanh = __math_tanh

def asinh(x):
    return __math_log(x + __math_sqrt(x * x + 1.0))

def acosh(x):
    return __math_log(x + __math_sqrt(x * x - 1.0))

def atanh(x):
    return 0.5 * __math_log((1.0 + x) / (1.0 - x))

def expm1(x):
    return __math_expm1(x)

gamma = __math_gamma
lgamma = __math_lgamma
erf = __math_erf
erfc = __math_erfc
ldexp = __math_ldexp
frexp = __math_frexp

def modf(x):
    # returns (fractional, integer) both as floats, sign preserved
    if x != x or x == inf or x == -inf:
        return (x - x if x == x else x, x)
    ip = float(int(x))
    return (x - ip, ip)

def isfinite(x):
    return x == x and x != inf and x != -inf

def factorial(n):
    if n < 0:
        raise ValueError("factorial of negative number")
    r = 1
    i = 2
    while i <= n:
        r *= i
        i += 1
    return r

def isclose(a, b, rel_tol=1e-9, abs_tol=0.0):
    if a == b:
        return True
    if a == inf or a == -inf or b == inf or b == -inf:
        return False
    diff = fabs(b - a)
    return diff <= fabs(rel_tol * b) or diff <= fabs(rel_tol * a) or diff <= abs_tol
