# frozen random module: a pure-Python deterministic PRNG (xorshift64*).
# The state lives in this namespace, so VM snapshots capture and restore it.
_MASK = 0xFFFFFFFFFFFFFFFF
_state = 88172645463325252

def seed(n):
    global _state
    _state = (n & _MASK) or 88172645463325252

def _next():
    global _state
    x = _state
    x = (x ^ (x << 13)) & _MASK
    x = x ^ (x >> 7)
    x = (x ^ (x << 17)) & _MASK
    _state = x
    return x

def getrandbits(k):
    return _next() >> (64 - k)

def random():
    return getrandbits(53) * (1.0 / 9007199254740992.0)

def randrange(a, b=None):
    if b is None:
        lo, hi = 0, a
    else:
        lo, hi = a, b
    n = hi - lo
    if n <= 0:
        raise ValueError("empty range for randrange")
    return lo + _next() % n

def randint(a, b):
    return randrange(a, b + 1)

def uniform(a, b):
    return a + (b - a) * random()

def choice(seq):
    if len(seq) == 0:
        raise IndexError("empty sequence")
    return seq[randrange(len(seq))]
