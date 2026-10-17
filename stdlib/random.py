# frozen random module: a deterministic xorshift64 generator. The state lives in this
# namespace (a 64-bit value; the host hands it back signed, older saves hold it unsigned),
# so VM snapshots capture and restore it; the step itself is a host primitive,
# __random_step(state, bits, mod) -> (new state, value), the value being the top `bits`
# bits of the new state, or the unsigned state modulo `mod`, or the whole unsigned state.
_MASK = 0xFFFFFFFFFFFFFFFF
_DEFAULT = 88172645463325252
_state = _DEFAULT


def seed(n):
    global _state
    _state = (n & _MASK) or _DEFAULT


def _next():
    # The next unsigned 64-bit value.
    global _state
    _state, value = __random_step(_state, 64, 0)
    return value


def getrandbits(k):
    global _state
    if k < 0:
        raise ValueError("number of bits must be non-negative")
    if k == 0:
        return 0
    if k > 64:
        result = 0
        while k > 0:
            take = 64 if k > 64 else k
            result = (result << take) | getrandbits(take)
            k -= take
        return result
    _state, value = __random_step(_state, k, 0)
    return value


def random():
    return getrandbits(53) * (1.0 / 9007199254740992.0)


def randrange(a, b=None):
    global _state
    if b is None:
        lo, hi = 0, a
    else:
        lo, hi = a, b
    n = hi - lo
    if n <= 0:
        raise ValueError("empty range for randrange")
    if n < 9223372036854775808:
        _state, r = __random_step(_state, 0, n)
        return lo + r
    return lo + _next() % n


def randint(a, b):
    return randrange(a, b + 1)


def uniform(a, b):
    return a + (b - a) * random()


def choice(seq):
    if len(seq) == 0:
        raise IndexError("empty sequence")
    return seq[randrange(len(seq))]


def shuffle(seq):
    for i in range(len(seq) - 1, 0, -1):
        j = randrange(i + 1)
        seq[i], seq[j] = seq[j], seq[i]
