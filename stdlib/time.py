# frozen time module over host primitives. Defaults: a monotonic millisecond
# clock and a no-op sleep; game hosts override __time_ms / __time_sleep with
# their own deterministic clock for snapshot-safe behavior.
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

def sleep(s):
    __time_sleep(int(s * 1000))

def sleep_ms(ms):
    __time_sleep(ms)
