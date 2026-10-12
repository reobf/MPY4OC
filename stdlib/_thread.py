"""MicroPython's low-level `_thread` API, on top of our cooperative threading.

Same caveat as `threading`: these are cooperative pseudo-threads inside one VM, not
OS threads. Switching happens at suspension points and op-budget boundaries, so a
thread that never yields still interleaves with others at budget granularity.
"""

import threading as _t
import sys


# the class of allocate_lock()'s result, as in MicroPython (_thread.LockType)
LockType = _t.Lock

_stack = [0]


def start_new_thread(target, args, kwargs=None):
    """Start `target(*args, **kwargs)` and return its ident.

    Argument shapes are checked HERE (as MicroPython does): args must be a tuple
    or list, kwargs a dict (or omitted). An exception escaping the target is
    reported ("Unhandled exception in thread started by ...") and the thread
    dies without taking the program down -- also MicroPython's behaviour.
    """
    if not (isinstance(args, tuple) or isinstance(args, list)):
        raise TypeError("2nd arg must be a tuple")
    if kwargs is None:
        kwargs = {}
    if not isinstance(kwargs, dict):
        raise TypeError("3rd arg must be a dict")

    def _runner():
        try:
            target(*args, **kwargs)
        except SystemExit:
            pass                      # _thread.exit(): a normal way out
        except BaseException as e:
            print("Unhandled exception in thread started by", target)
            __thread_print_exc(e)

    # Daemon, as in CPython and MicroPython: nothing waits for a _thread thread when its
    # program ends (an MPYOS command's end stops it at its next poll point).
    th = _t.Thread(target=_runner, daemon=True)
    th.start()
    return th.ident


def get_ident():
    return _t.get_ident()


def allocate_lock():
    return _t.Lock()


def exit():
    """Leave the current thread. Raises SystemExit, as MicroPython does."""
    raise SystemExit


def stack_size(size=None):
    """Get/set the stack size for NEW threads: returns the previous value, then
    sets `size` (or resets to the default 0 when called without arguments) --
    MicroPython's exact contract. The value is recorded but has no effect here:
    our threads live in the VM's frame storage and have no C stack to size."""
    old = _stack[0]
    _stack[0] = size if size is not None else 0
    return old
