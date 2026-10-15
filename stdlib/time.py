# frozen time module over host primitives: __time_ms, a monotonic millisecond clock (the
# game host wires it to the server's), and the VM's park for sleeping. A host or a test may
# install __time_sleep(ms) to replace the sleep.
def ticks_ms():
    return __time_ms()   # late-bound: hosts may swap the clock at any time

def ticks_diff(a, b):
    return a - b

def ticks_add(a, b):
    return a + b

def time():
    return __time_ms() // 1000

def time_ns():
    # nanoseconds, from the same millisecond clock (ms precision)
    return __time_ms() * 1000000

def monotonic():
    return __time_ms() / 1000

def _sleep(ms):
    # The sleep itself: park until the deadline. A poll point: a stop request or a Ctrl+C
    # ends it early, raising here. sleep(0) still yields once, as it always did.
    import _program
    deadline = __time_ms() + int(ms)
    while True:
        _program.park(deadline)
        if __time_ms() >= deadline:
            return

def _sleeper():
    try:
        return __time_sleep
    except NameError:
        return _sleep

def sleep(s):
    if s < 0:
        raise ValueError("sleep length must be non-negative")
    _sleeper()(int(s * 1000))

def sleep_ms(ms):
    _sleeper()(ms)
