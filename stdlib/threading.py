# threading: a cooperative re-creation of Python's threading API on top of this
# VM's single-threaded, stackful-coroutine scheduler (jasyncio). There is NO real
# OS thread and NO preemptive time-slicing: a "thread" is an ordinary function
# scheduled as a cooperative task and driven by the VM's per-step op budget, so
# threads interleave at instruction-budget boundaries and at explicit yield points
# (sleep, a contended Lock). The main frame acts as the non-daemon main thread;
# Thread.join() blocks it (via jasyncio.run) until the target task settles. Being
# pure Python over jasyncio, threads/locks are captured by VM snapshots.
#
# Differences from CPython threading:
#  - no preemption: a thread that never hits an instruction-budget boundary or a
#    yield point runs to completion before another thread advances;
#  - no real parallelism (single execution line);
#  - Lock is a cooperative busy-wait flag, not owned by a thread;
#  - subset API: Thread(target/name/args/kwargs/daemon), start, join, is_alive,
#    name, daemon, run; Lock (acquire/release/locked/context manager);
#    current_thread(), active_count(), get_ident() (approximate).

import jasyncio
import time

_counter = [0]          # for default Thread-N names
_active = []            # started, not-yet-finished Thread objects
_ident = [1]            # monotonic id source; main thread is 1


class ThreadError(Exception):
    pass


class Thread:
    def __init__(self, group=None, target=None, name=None, args=(), kwargs=None,
                 daemon=None):
        if group is not None:
            raise ValueError("group argument must be None for now")
        self._target = target
        self._args = args
        self._kwargs = kwargs if kwargs is not None else {}
        if name is not None:
            self._name = name
        else:
            _counter[0] += 1
            self._name = "Thread-" + str(_counter[0])
        self._daemon = bool(daemon) if daemon is not None else False
        self._task = None
        self._started = False
        self._ident = None

    # --- run/target ---------------------------------------------------------
    def run(self):
        # The activity of the thread: invoke target with args/kwargs. Subclasses
        # may override this instead of passing a target.
        if self._target is not None:
            return self._target(*self._args, **self._kwargs)

    def start(self):
        if self._started:
            raise ThreadError("threads can only be started once")
        self._started = True
        _ident[0] += 1
        self._ident = _ident[0]
        _active.append(self)
        # schedule run() as a cooperative task; returns immediately
        self._task = jasyncio.to_thread(self.run)
        if self._daemon:
            # daemon threads must not keep the program alive on their own
            finishDaemonOnExit()

    def join(self, timeout=None):
        if not self._started:
            raise ThreadError("cannot join thread before it is started")
        task = self._task
        if timeout is None:
            # block until the task settles
            def _wait():
                _spin = 0
                while not task.done():
                    if _spin < 2:
                        _spin += 1
                        yield
                    else:
                        yield __jasyncio_make_sleep(50)
            jasyncio.run(_wait())
        else:
            # bounded wait: stop after `timeout` seconds of wall-clock
            deadline_ms = int(timeout * 1000)
            def _wait_to():
                elapsed = 0
                step = 1
                while not task.done() and elapsed < deadline_ms:
                    yield __jasyncio_make_sleep(step)
                    elapsed += step
            jasyncio.run(_wait_to())
        if task.done() and self in _active:
            _active.remove(self)
        # join() returns None; per docs, callers use is_alive() to detect timeout

    def is_alive(self):
        return self._started and self._task is not None and not self._task.done()

    # --- name / daemon / ident ---------------------------------------------
    # NOTE: CPython exposes name/daemon as properties. This VM's property lacks
    # .setter, so we expose them as plain attributes plus getName/setName and
    # isDaemon/setDaemon (the classic threading API) for compatibility.
    def getName(self):
        return self._name

    def setName(self, value):
        self._name = value

    def isDaemon(self):
        return self._daemon

    def setDaemon(self, value):
        if self._started:
            raise ThreadError("cannot set daemon status of active thread")
        self._daemon = bool(value)

    @property
    def name(self):
        return self._name

    @property
    def daemon(self):
        return self._daemon

    @property
    def ident(self):
        return self._ident

    def __repr__(self):
        status = "started" if self._started else "initial"
        if self._started and self._task is not None and self._task.done():
            status = "stopped"
        return "<Thread(" + self._name + ", " + status + ")>"


class _MainThread(Thread):
    def __init__(self):
        Thread.__init__(self, name="MainThread")
        self._started = True
        self._ident = 1


_main = _MainThread()


def current_thread():
    # Ask the VM which pool task we are running inside and match it against the
    # live Thread objects; the main frame (or any non-thread task) maps to the
    # main-thread object.
    cur = __jasyncio_current_coro()
    if cur is not None:
        for t in _active:
            if t._task is not None and cur is t._task._driver:
                return t
    return _main


def active_count():
    # started, not-yet-finished threads, plus the main thread
    n = 1
    for t in _active:
        if t.is_alive():
            n += 1
    return n


def enumerate():
    live = [_main]
    for t in _active:
        if t.is_alive():
            live.append(t)
    return live


def get_ident():
    return current_thread()._ident


def main_thread():
    return _main


# --- Lock -------------------------------------------------------------------
class Lock:
    # A cooperative primitive lock. The flag lives in a 1-element list so the VM's
    # atomic __thread_test_and_set can check-and-set it in a single native step
    # (no instruction-boundary interleaving), making acquire race-free even though
    # threads are preempted at op-budget boundaries. acquire() busy-waits on that
    # atomic primitive, letting the scheduler interleave other threads until the
    # lock is free. Not owned by a particular thread, matching CPython's primitive.
    def __init__(self):
        self._flag = [False]

    def acquire(self, blocking=True):
        if not blocking:
            return __thread_test_and_set(self._flag)
        # Cooperative blocking acquire: retry the atomic test-and-set; while
        # contended, sleep a tick per round -- in mpyos time.sleep yields the
        # tick, so the holder gets budget and we do not burn ours spinning
        # (in a bare VM sleep is a no-op and this degrades to the old spin).
        while not __thread_test_and_set(self._flag):
            time.sleep(0.05)
        return True

    def release(self):
        if not self._flag[0]:
            raise RuntimeError("release unlocked lock")
        self._flag[0] = False

    def locked(self):
        return self._flag[0]

    def __enter__(self):
        self.acquire()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.release()
        return False


def RLock():
    # Simplified: a reentrant lock is approximated by a plain Lock here. (Full
    # reentrancy tracking can be added if needed.)
    return Lock()
