# threading: Python's threading API on this VM's threads. A thread is a generator the VM
# runs as a task of its own (__thread_start): the VM shares each step's instruction budget
# between the main frame and the runnable threads and switches between them when a share
# runs out, so threads interleave even when they never wait. There is one execution line,
# no parallelism. Waiting (time.sleep, a contended Lock, join from a thread) is a poll each
# tick. A system-main join can park in __thread_wait; a program's main thread polls
# so KeyboardInterrupt can unwind its join. Threads and locks are saved with the VM.
#
# As CPython: Thread(group, target, name, args, kwargs, daemon) with names Thread-N (target),
# start/run/join/is_alive, name/daemon/ident, a daemon by default when started by a daemon
# (an MPYOS background job's runner counts as its program's main thread), an exception that
# leaves run() is printed as "Exception in thread NAME:" with its traceback, SystemExit ends
# the thread quietly. Lock is not owned by a thread (as CPython's primitive lock); RLock is
# a plain Lock here.

import time
import _program

_counter = [0]          # for default Thread-N names
_active = []            # started, not-yet-finished Thread objects
_ident = [1]            # monotonic id source; main thread is 1

# CPython: threading.ThreadError is _thread.error, i.e. RuntimeError.
ThreadError = RuntimeError


def _here():
    # The thread running now (its generator), or None on the main frame.
    try:
        return __thread_current()
    except NameError:    # a VM without threads
        return None


def _run(thread):
    # A thread's own generator: it starts in the VM's next round, as a new thread does.
    yield
    thread._bootstrap()


def _await_thread(thread):
    # What the main frame waits on in join(): ends when the thread does.
    spin = 0
    while not thread._done:
        if spin < 2:
            spin += 1
            yield
        else:
            yield __thread_sleep(50)


class Thread:
    _program_main = False   # set on an MPYOS job's runner (see __init__)

    def __init__(self, group=None, target=None, name=None, args=(), kwargs=None,
                 daemon=None):
        if group is not None:
            raise AssertionError("group argument must be None for now")
        self._target = target
        self._args = args
        self._kwargs = kwargs if kwargs is not None else {}
        if name is not None:
            self._name = str(name)
        else:
            _counter[0] += 1
            self._name = "Thread-" + str(_counter[0])
            target_name = getattr(target, "__name__", None)
            if target_name:
                self._name += " (" + str(target_name) + ")"
        if daemon is None:
            # As CPython: a new thread is a daemon if the thread starting it is. The runner
            # of an MPYOS background job counts as its program's main thread.
            creator = current_thread()
            daemon = creator._daemon and not creator._program_main
        self._daemon = bool(daemon)
        self._gen = None
        self._started = False
        self._done = False
        self._ident = None

    # --- run/target ---------------------------------------------------------
    def run(self):
        # The activity of the thread: invoke target with args/kwargs. Subclasses
        # may override this instead of passing a target.
        if self._target is not None:
            return self._target(*self._args, **self._kwargs)

    def start(self):
        if self._started:
            raise RuntimeError("threads can only be started once")
        self._started = True
        _ident[0] += 1
        self._ident = _ident[0]
        _active.append(self)
        # The program the thread runs for: its creator's (see _program).
        self._program = _program.current()
        _program._threads.append(self)
        gen = _run(self)
        self._gen = gen
        _program.adopt(gen)
        __thread_start(gen)
        if self._daemon:
            # daemon threads must not keep the program alive on their own
            finishDaemonOnExit()

    def _bootstrap(self):
        try:
            self.run()
        except SystemExit:
            pass
        except BaseException as exc:
            # threading.excepthook: the thread's name, then the traceback.
            print("Exception in thread " + self._name + ":")
            __thread_print_exc(exc)
        finally:
            # As CPython: a finished thread leaves the active list and drops its target
            # (whose closure may hold a lot), whether or not anyone joins it.
            self._target = self._args = self._kwargs = None
            for registry in (_active, _program._threads):
                if self in registry:
                    registry.remove(self)
            self._done = True
            _program.forget(self._gen)

    def join(self, timeout=None):
        if not self._started:
            raise RuntimeError("cannot join thread before it is started")
        if self is current_thread():
            raise RuntimeError("cannot join current thread")
        if self._done:
            return
        if timeout is None and _here() is None and _program.current() is None:
            # The main frame waits in the VM: its share of each step goes to the threads.
            try:
                __thread_wait(_await_thread(self))
                return
            except RuntimeError:
                pass             # called from inside a generator on the main frame: poll
        deadline = None if timeout is None else time.ticks_add(time.ticks_ms(), int(max(0, timeout) * 1000))
        while not self._done:
            if deadline is not None and time.ticks_diff(deadline, time.ticks_ms()) <= 0:
                return
            time.sleep(0.05)
        # join() returns None; per docs, callers use is_alive() to detect timeout

    def is_alive(self):
        return self._started and not self._done

    # --- name / daemon / ident ---------------------------------------------
    # NOTE: CPython exposes name/daemon as properties with setters. This VM's property
    # lacks .setter, so setName/setDaemon (the classic threading API) set them.
    def getName(self):
        return self._name

    def setName(self, value):
        self._name = str(value)

    def isDaemon(self):
        return self._daemon

    def setDaemon(self, value):
        if self._started:
            raise RuntimeError("cannot set daemon status of active thread")
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
        status = "initial"
        if self._started:
            status = "stopped" if self._done else "started"
        if self._daemon:
            status += " daemon"
        if self._ident is not None:
            status += " " + str(self._ident)
        return "<" + type(self).__name__ + "(" + self._name + ", " + status + ")>"


class _MainThread(Thread):
    def __init__(self):
        Thread.__init__(self, name="MainThread", daemon=False)
        self._started = True
        self._ident = 1


_main = _MainThread()


def current_thread():
    # Ask the VM which thread is running and match it against the live Thread objects; the
    # main frame (or a VM task that is not a Thread) maps to the main-thread object.
    cur = _here()
    if cur is not None:
        for t in _active:
            if cur is t._gen:
                return t
    return _main


currentThread = current_thread


def active_count():
    # started, not-yet-finished threads, plus the main thread
    n = 1
    for t in _active:
        if t.is_alive():
            n += 1
    return n


activeCount = active_count


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
    # threads are preempted at op-budget boundaries. acquire() polls that primitive,
    # sleeping a tick between tries so the holder gets the budget. Not owned by a
    # particular thread, matching CPython's primitive lock.
    def __init__(self):
        self._flag = [False]

    def acquire(self, blocking=True, timeout=-1):
        if not blocking:
            if timeout != -1:
                raise ValueError("can't specify a timeout for a non-blocking call")
            return __thread_test_and_set(self._flag)
        deadline = None if timeout is None or timeout < 0 else time.ticks_add(time.ticks_ms(), int(timeout * 1000))
        while not __thread_test_and_set(self._flag):
            if deadline is not None and time.ticks_diff(deadline, time.ticks_ms()) <= 0:
                return False
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
