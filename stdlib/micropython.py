"""The `micropython` module: the parts that make sense on this host.

MicroPython exposes VM/heap controls here. Two of them map cleanly onto our
scheduler; the heap ones do not, because we run on the JVM and have no heap of our
own to lock. Rather than pretend, the no-ops below say so.
"""

import _program


_pending = [0]


def schedule(func, arg):
    """Queue `func(arg)` to run at the next scheduling point.

    MicroPython's version exists so an interrupt handler can defer real work to a
    safe moment. We have no interrupts, but the same primitive is still useful for
    handing work from any context to the event loop, and the asyncio tests use it.
    Like MicroPython, the queue is BOUNDED (depth 8): a producer that outruns the
    consumer gets RuntimeError instead of unbounded task growth.
    """
    if _pending[0] >= 8:
        raise RuntimeError("schedule queue full")
    _pending[0] += 1
    task = _run_scheduled(func, arg)
    _program.adopt(task)             # it runs for the caller's program
    __thread_start(task)


def _run_scheduled(func, arg):
    # A one-shot thread: yield once so the callback lands on the NEXT scheduling round
    # (schedule() must not run it inline), then invoke it.
    try:
        yield
        _pending[0] -= 1
        func(arg)
    finally:
        _program.forget(__thread_current())


def heap_lock():
    """No-op: there is no interpreter-owned heap to lock on this host (JVM GC).

    MicroPython uses this to assert that a code path allocates nothing. We cannot
    enforce that, and silently pretending to would make such an assertion
    meaningless -- so it does nothing, deliberately.
    """


def heap_unlock():
    """No-op counterpart to heap_lock()."""


def mem_info(*args):
    """No-op: heap statistics do not apply on the JVM."""


# MicroPython reports the bytecode "emit" kind; ours is always plain bytecode.
bytecode = 0
native = 1
viper = 2


def const(x):
    """Compile-time constant marker. The compiler folds `const(...)` itself, so at
    runtime this is just the identity."""
    return x
