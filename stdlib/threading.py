# threading: Python's threading API on this VM's threads. A thread is a generator the VM
# runs as a task of its own (__thread_start): the VM shares each step's instruction budget
# between the main frame and the runnable threads and switches between them when a share
# runs out, so threads interleave even when they never wait. There is one execution line,
# no parallelism.
#
# Waiting is the VM's park/unpark pair (_program.park), the counterpart of the kernel wait
# CPython's locks block in: a parked thread holds no seat and spends no instruction until
# its deadline, an unpark, or (pullSignal) a queued signal. Lock is the one class built on
# it directly: a flag the VM tests and sets in one step, plus the parked waiters in
# arrival order, the first of which release() wakes. Everything else is CPython 3.12's own
# pure-Python threading.py over that Lock -- RLock, Condition, Semaphore, BoundedSemaphore,
# Event, Barrier and Timer are its code -- and join() waits on the thread's _tstate_lock
# as it does there.
#
# As CPython: Thread(group, target, name, args, kwargs, daemon) with names Thread-N (target),
# start/run/join/is_alive, name/daemon/ident, a daemon by default when started by a daemon
# (an MPYOS background job's runner counts as its program's main thread), an exception that
# leaves run() is printed as "Exception in thread NAME:" with its traceback, SystemExit ends
# the thread quietly. Lock is not owned by a thread (as CPython's primitive lock). Threads
# and locks are saved with the VM.

import time
import _program

_counter = [0]          # for default Thread-N names
_active = []            # started, not-yet-finished Thread objects
_ident = [1]            # monotonic id source; main thread is 1

# CPython: threading.ThreadError is _thread.error, i.e. RuntimeError.
ThreadError = RuntimeError
# The largest timeout a lock accepts (CPython: _thread.TIMEOUT_MAX).
TIMEOUT_MAX = 9223372036.0

_time = time.monotonic


def _here():
    # The thread running now (its generator), or None on the main frame.
    try:
        return __thread_current()
    except NameError:    # a VM without threads
        return None


# --- Lock -------------------------------------------------------------------
class Lock:
    # The primitive lock, the one wait built on the VM directly. The flag lives in a
    # 1-element list so the VM's atomic __thread_test_and_set can check-and-set it in a
    # single native step (no instruction-boundary interleaving), making acquire race-free
    # even though threads are preempted at op-budget boundaries. A thread that finds it
    # taken parks in the waiter queue; release() unparks the first waiter, which re-tries
    # the flag (a newcomer may still take the lock first, as with CPython's lock). Not
    # owned by a particular thread, matching CPython's primitive lock.
    def __init__(self):
        self._flag = [False]
        self._waiters = []       # the VM threads (None: the main frame) parked in acquire()

    def __repr__(self):
        return "<" + ("locked" if self._flag[0] else "unlocked") + " _thread.lock object>"

    def acquire(self, blocking=True, timeout=-1):
        if not blocking and timeout != -1:
            raise ValueError("can't specify a timeout for a non-blocking call")
        if timeout < 0 and timeout != -1:
            raise ValueError("timeout value must be positive")
        if timeout > TIMEOUT_MAX:
            raise OverflowError("timeout value is too large")
        if __thread_test_and_set(self._flag):
            return True
        if not blocking:
            return False
        deadline = None
        if timeout != -1:
            deadline = time.ticks_add(time.ticks_ms(), int(timeout * 1000))
            if time.ticks_diff(deadline, time.ticks_ms()) <= 0:
                return False
        # In the waiter queue BEFORE the next try of the flag: a release between that try and
        # the park then finds us and unparks (a permit), so no wake-up is lost.
        me = _here()
        self._waiters.append(me)
        try:
            while True:
                if __thread_test_and_set(self._flag):
                    return True
                if deadline is not None and time.ticks_diff(deadline, time.ticks_ms()) <= 0:
                    return False
                _program.park(deadline)
        finally:
            self._waiters.remove(me)
            # Leaving without the lock (timed out, or interrupted at the park) while the
            # lock is free: the wake-up meant for us must reach the next waiter.
            if not self._flag[0]:
                self._wake_next()

    def _wake_next(self):
        # Take a private snapshot in one native slice. A waiter can leave between
        # bytecodes, including between testing the shared list and indexing it.
        # A waiter that leaves after this snapshot forwards the wake-up above.
        first = self._waiters[:1]
        if first:
            _program.unpark(first[0])

    def release(self):
        if not self._flag[0]:
            raise RuntimeError("release unlocked lock")
        self._flag[0] = False
        self._wake_next()

    def locked(self):
        return self._flag[0]

    def __enter__(self):
        self.acquire()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.release()
        return False


_allocate_lock = Lock


def _run(thread):
    # A thread's own generator: it starts in the VM's next round, as a new thread does.
    yield
    thread._bootstrap()


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
        # As CPython: a lock the thread holds from start to end; join() acquires it.
        self._tstate_lock = None

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
        self._tstate_lock = Lock()
        self._tstate_lock.acquire()
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
            lock = self._tstate_lock
            if lock is not None:
                lock.release()      # the joiners wake here
            _program.forget(self._gen)

    def join(self, timeout=None):
        if not self._started:
            raise RuntimeError("cannot join thread before it is started")
        if self is current_thread():
            raise RuntimeError("cannot join current thread")
        if timeout is None:
            self._wait_for_tstate_lock()
        else:
            self._wait_for_tstate_lock(timeout=max(timeout, 0))
        # join() returns None; per docs, callers use is_alive() to detect timeout

    def _wait_for_tstate_lock(self, block=True, timeout=-1):
        # As CPython: the thread holds its _tstate_lock until it ends; acquiring it is
        # the wait, and a timed-out acquire leaves the thread alive.
        lock = self._tstate_lock
        if lock is None:
            return
        if lock.acquire(block, timeout):
            lock.release()
            self._tstate_lock = None

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
        # Held for good, as CPython's main thread holds its _tstate_lock until the
        # interpreter shuts down: joining the main thread from another thread waits.
        self._tstate_lock = Lock()
        self._tstate_lock.acquire()


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


# --- CPython 3.12's threading.py from here on (RLock, Condition, Semaphore, Event,
# --- Barrier, Timer), over Lock ---------------------------------------------------

def RLock(*args, **kwargs):
    """Factory function that returns a new reentrant lock.

    A reentrant lock must be released by the thread that acquired it. Once a
    thread has acquired a reentrant lock, the same thread may acquire it again
    without blocking; the thread must release it once for each time it has
    acquired it.
    """
    return _RLock(*args, **kwargs)


class _RLock:
    """This class implements reentrant lock objects.

    A reentrant lock must be released by the thread that acquired it. Once a
    thread has acquired a reentrant lock, the same thread may acquire it
    again without blocking; the thread must release it once for each time it
    has acquired it.
    """

    def __init__(self):
        self._block = _allocate_lock()
        self._owner = None
        self._count = 0

    def __repr__(self):
        owner = self._owner
        for t in _active:
            if t._ident == owner:
                owner = t._name
                break
        return "<%s %s.%s object owner=%s count=%d>" % (
            "locked" if self._block.locked() else "unlocked",
            "threading", "RLock", repr(owner), self._count)

    def acquire(self, blocking=True, timeout=-1):
        """Acquire a lock, blocking or non-blocking.

        When invoked without arguments: if this thread already owns the lock,
        increment the recursion level by one, and return immediately. Otherwise,
        if another thread owns the lock, block until the lock is unlocked. Once
        the lock is unlocked (not owned by any thread), then grab ownership, set
        the recursion level to one, and return. If more than one thread is
        blocked waiting until the lock is unlocked, only one at a time will be
        able to grab ownership of the lock. There is no return value in this
        case.

        When invoked with the blocking argument set to true, do the same thing
        as when called without arguments, and return true.

        When invoked with the blocking argument set to false, do not block. If a
        call without an argument would block, return false immediately;
        otherwise, do the same thing as when called without arguments, and
        return true.

        When invoked with the floating-point timeout argument set to a positive
        value, block for at most the number of seconds specified by timeout
        and as long as the lock cannot be acquired.  Return true if the lock has
        been acquired, false if the timeout has elapsed.
        """
        me = get_ident()
        if self._owner == me:
            self._count += 1
            return True     # as CPython's C RLock (its Python one returns 1)
        rc = self._block.acquire(blocking, timeout)
        if rc:
            self._owner = me
            self._count = 1
        return rc

    __enter__ = acquire

    def release(self):
        """Release a lock, decrementing the recursion level.

        If after the decrement it is zero, reset the lock to unlocked (not owned
        by any thread), and if any other threads are blocked waiting for the
        lock to become unlocked, allow exactly one of them to proceed. If after
        the decrement the recursion level is still nonzero, the lock remains
        locked and owned by the calling thread.

        Only call this method when the calling thread owns the lock. A
        RuntimeError is raised if this method is called when the lock is
        unlocked.

        There is no return value.
        """
        if self._owner != get_ident():
            raise RuntimeError("cannot release un-acquired lock")
        self._count = count = self._count - 1
        if not count:
            self._owner = None
            self._block.release()

    def __exit__(self, t, v, tb):
        self.release()

    # Internal methods used by condition variables

    def _acquire_restore(self, state):
        self._block.acquire()
        self._count, self._owner = state

    def _release_save(self):
        if self._count == 0:
            raise RuntimeError("cannot release un-acquired lock")
        count = self._count
        self._count = 0
        owner = self._owner
        self._owner = None
        self._block.release()
        return (count, owner)

    def _is_owned(self):
        return self._owner == get_ident()

    # Internal method used for reentrancy checks

    def _recursion_count(self):
        if self._owner != get_ident():
            return 0
        return self._count


_PyRLock = _RLock


class Condition:
    """Class that implements a condition variable.

    A condition variable allows one or more threads to wait until they are
    notified by another thread.

    If the lock argument is given and not None, it must be a Lock or RLock
    object, and it is used as the underlying lock. Otherwise, a new RLock object
    is created and used as the underlying lock.
    """

    def __init__(self, lock=None):
        if lock is None:
            lock = RLock()
        self._lock = lock
        # Export the lock's acquire() and release() methods
        self.acquire = lock.acquire
        self.release = lock.release
        # If the lock defines _release_save() and/or _acquire_restore(),
        # these override the default implementations (which just call
        # release() and acquire() on the lock).  Ditto for _is_owned().
        if hasattr(lock, '_release_save'):
            self._release_save = lock._release_save
        if hasattr(lock, '_acquire_restore'):
            self._acquire_restore = lock._acquire_restore
        if hasattr(lock, '_is_owned'):
            self._is_owned = lock._is_owned
        self._waiters = []

    def __enter__(self):
        return self._lock.__enter__()

    def __exit__(self, *args):
        return self._lock.__exit__(*args)

    def __repr__(self):
        return "<Condition(%s, %d)>" % (self._lock, len(self._waiters))

    def _release_save(self):
        self._lock.release()           # No state to save

    def _acquire_restore(self, x):
        self._lock.acquire()           # Ignore saved state

    def _is_owned(self):
        # Return True if lock is owned by current_thread.
        # This method is called only if _lock doesn't have _is_owned().
        if self._lock.acquire(False):
            self._lock.release()
            return False
        else:
            return True

    def wait(self, timeout=None):
        """Wait until notified or until a timeout occurs.

        If the calling thread has not acquired the lock when this method is
        called, a RuntimeError is raised.

        This method releases the underlying lock, and then blocks until it is
        awakened by a notify() or notify_all() call for the same condition
        variable in another thread, or until the optional timeout occurs. Once
        awakened or timed out, it re-acquires the lock and returns.

        When the timeout argument is present and not None, it should be a
        floating point number specifying a timeout for the operation in seconds
        (or fractions thereof).

        When the underlying lock is an RLock, it is not released using its
        release() method, since this may not actually unlock the lock when it
        was acquired multiple times recursively. Instead, an internal interface
        of the RLock class is used, which really unlocks it even when it has
        been recursively acquired several times. Another internal interface is
        then used to restore the recursion level when the lock is reacquired.
        """
        if not self._is_owned():
            raise RuntimeError("cannot wait on un-acquired lock")
        waiter = _allocate_lock()
        waiter.acquire()
        self._waiters.append(waiter)
        saved_state = self._release_save()
        gotit = False
        try:    # restore state no matter what (e.g., KeyboardInterrupt)
            if timeout is None:
                waiter.acquire()
                gotit = True
            else:
                if timeout > 0:
                    gotit = waiter.acquire(True, timeout)
                else:
                    gotit = waiter.acquire(False)
            return gotit
        finally:
            self._acquire_restore(saved_state)
            if not gotit:
                try:
                    self._waiters.remove(waiter)
                except ValueError:
                    pass

    def wait_for(self, predicate, timeout=None):
        """Wait until a condition evaluates to True.

        predicate should be a callable which result will be interpreted as a
        boolean value.  A timeout may be provided giving the maximum time to
        wait.
        """
        endtime = None
        waittime = timeout
        result = predicate()
        while not result:
            if waittime is not None:
                if endtime is None:
                    endtime = _time() + waittime
                else:
                    waittime = endtime - _time()
                    if waittime <= 0:
                        break
            self.wait(waittime)
            result = predicate()
        return result

    def notify(self, n=1):
        """Wake up one or more threads waiting on this condition, if any.

        If the calling thread has not acquired the lock when this method is
        called, a RuntimeError is raised.

        This method wakes up at most n of the threads waiting for the condition
        variable; it is a no-op if no threads are waiting.
        """
        if not self._is_owned():
            raise RuntimeError("cannot notify on un-acquired lock")
        waiters = self._waiters
        while waiters and n > 0:
            waiter = waiters[0]
            try:
                waiter.release()
            except RuntimeError:
                # gh-92530: The previous call of notify() released the lock,
                # but was interrupted before removing it from the queue.
                # It can happen if a signal handler raises an exception,
                # like CTRL+C which raises KeyboardInterrupt.
                pass
            else:
                n -= 1
            try:
                waiters.remove(waiter)
            except ValueError:
                pass

    def notify_all(self):
        """Wake up all threads waiting on this condition.

        If the calling thread has not acquired the lock when this method
        is called, a RuntimeError is raised.
        """
        self.notify(len(self._waiters))

    def notifyAll(self):
        """Wake up all threads waiting on this condition.

        This method is deprecated, use notify_all() instead.
        """
        self.notify_all()


class Semaphore:
    """This class implements semaphore objects.

    Semaphores manage a counter representing the number of release() calls minus
    the number of acquire() calls, plus an initial value. The acquire() method
    blocks if necessary until it can return without making the counter
    negative. If not given, value defaults to 1.
    """

    def __init__(self, value=1):
        if value < 0:
            raise ValueError("semaphore initial value must be >= 0")
        self._cond = Condition(Lock())
        self._value = value

    def __repr__(self):
        return "<threading.%s: value=%d>" % (type(self).__name__, self._value)

    def acquire(self, blocking=True, timeout=None):
        """Acquire a semaphore, decrementing the internal counter by one.

        When invoked without arguments: if the internal counter is larger than
        zero on entry, decrement it by one and return immediately. If it is zero
        on entry, block, waiting until some other thread has called release() to
        make it larger than zero. This is done with proper interlocking so that
        if multiple acquire() calls are blocked, release() will wake exactly one
        of them up. The implementation may pick one at random, so the order in
        which blocked threads are awakened should not be relied on. There is no
        return value in this case.

        When invoked with blocking set to true, do the same thing as when called
        without arguments, and return true.

        When invoked with blocking set to false, do not block. If a call without
        an argument would block, return false immediately; otherwise, do the
        same thing as when called without arguments, and return true.

        When invoked with a timeout other than None, it will block for at
        most timeout seconds.  If acquire does not complete successfully in
        that interval, return false.  Return true otherwise.
        """
        if not blocking and timeout is not None:
            raise ValueError("can't specify timeout for non-blocking acquire")
        rc = False
        endtime = None
        with self._cond:
            while self._value == 0:
                if not blocking:
                    break
                if timeout is not None:
                    if endtime is None:
                        endtime = _time() + timeout
                    else:
                        timeout = endtime - _time()
                        if timeout <= 0:
                            break
                self._cond.wait(timeout)
            else:
                self._value -= 1
                rc = True
        return rc

    __enter__ = acquire

    def release(self, n=1):
        """Release a semaphore, incrementing the internal counter by one or more.

        When the counter is zero on entry and another thread is waiting for it
        to become larger than zero again, wake up that thread.
        """
        if n < 1:
            raise ValueError('n must be one or more')
        with self._cond:
            self._value += n
            self._cond.notify(n)

    def __exit__(self, t, v, tb):
        self.release()


class BoundedSemaphore(Semaphore):
    """Implements a bounded semaphore.

    A bounded semaphore checks to make sure its current value doesn't exceed its
    initial value. If it does, ValueError is raised. In most situations
    semaphores are used to guard resources with limited capacity.

    If the semaphore is released too many times it's a sign of a bug. If not
    given, value defaults to 1.

    Like regular semaphores, bounded semaphores manage a counter representing
    the number of release() calls minus the number of acquire() calls, plus an
    initial value. The acquire() method blocks if necessary until it can return
    without making the counter negative. If not given, value defaults to 1.
    """

    def __init__(self, value=1):
        super().__init__(value)
        self._initial_value = value

    def __repr__(self):
        return "<threading.%s: value=%d/%d>" % (type(self).__name__, self._value, self._initial_value)

    def release(self, n=1):
        """Release a semaphore, incrementing the internal counter by one or more.

        When the counter is zero on entry and another thread is waiting for it
        to become larger than zero again, wake up that thread.

        If the number of releases exceeds the number of acquires,
        raise a ValueError.
        """
        if n < 1:
            raise ValueError('n must be one or more')
        with self._cond:
            if self._value + n > self._initial_value:
                raise ValueError("Semaphore released too many times")
            self._value += n
            self._cond.notify(n)


class Event:
    """Class implementing event objects.

    Events manage a flag that can be set to true with the set() method and reset
    to false with the clear() method. The wait() method blocks until the flag is
    true.  The flag is initially false.
    """

    def __init__(self):
        self._cond = Condition(Lock())
        self._flag = False

    def __repr__(self):
        return "<threading.Event: %s>" % ('set' if self._flag else 'unset')

    def is_set(self):
        """Return true if and only if the internal flag is true."""
        return self._flag

    def isSet(self):
        """Return true if and only if the internal flag is true.

        This method is deprecated, use is_set() instead.
        """
        return self.is_set()

    def set(self):
        """Set the internal flag to true.

        All threads waiting for it to become true are awakened. Threads
        that call wait() once the flag is true will not block at all.
        """
        with self._cond:
            self._flag = True
            self._cond.notify_all()

    def clear(self):
        """Reset the internal flag to false.

        Subsequently, threads calling wait() will block until set() is called to
        set the internal flag to true again.
        """
        with self._cond:
            self._flag = False

    def wait(self, timeout=None):
        """Block until the internal flag is true.

        If the internal flag is true on entry, return immediately. Otherwise,
        block until another thread calls set() to set the flag to true, or until
        the optional timeout occurs.

        When the timeout argument is present and not None, it should be a
        floating point number specifying a timeout for the operation in seconds
        (or fractions thereof).

        This method returns the internal flag on exit, so it will always return
        True except if a timeout is given and the operation times out.
        """
        with self._cond:
            signaled = self._flag
            if not signaled:
                signaled = self._cond.wait(timeout)
            return signaled


class Barrier:
    """Implements a Barrier.

    Useful for synchronizing a fixed number of threads at known synchronization
    points.  Threads block on 'wait()' and are simultaneously awoken once they
    have all made that call.
    """

    def __init__(self, parties, action=None, timeout=None):
        """Create a barrier, initialised to 'parties' threads.

        'action' is a callable which, when supplied, will be called by one of
        the threads after they have all entered the barrier and just prior to
        releasing them all. If a 'timeout' is provided, it is used as the
        default for all subsequent 'wait()' calls.
        """
        self._cond = Condition(Lock())
        self._action = action
        self._timeout = timeout
        self._parties = parties
        self._state = 0  # 0 filling, 1 draining, -1 resetting, -2 broken
        self._count = 0

    def __repr__(self):
        if self.broken:
            return "<threading.Barrier: broken>"
        return "<threading.Barrier: waiters=%d/%d>" % (self.n_waiting, self.parties)

    def wait(self, timeout=None):
        """Wait for the barrier.

        When the specified number of threads have started waiting, they are all
        simultaneously awoken. If an 'action' was provided for the barrier, one
        of the threads will have executed that callback prior to returning.
        Returns an individual index number from 0 to 'parties-1'.
        """
        if timeout is None:
            timeout = self._timeout
        with self._cond:
            self._enter() # Block while the barrier drains.
            index = self._count
            self._count += 1
            try:
                if index + 1 == self._parties:
                    # We release the barrier
                    self._release()
                else:
                    # We wait until someone releases us
                    self._wait(timeout)
                return index
            finally:
                self._count -= 1
                # Wake up any threads waiting for barrier to drain.
                self._exit()

    # Block until the barrier is ready for us, or raise an exception
    # if it is broken.
    def _enter(self):
        while self._state in (-1, 1):
            # It is draining or resetting, wait until done
            self._cond.wait()
        #see if the barrier is in a broken state
        if self._state < 0:
            raise BrokenBarrierError
        assert self._state == 0

    # Optionally run the 'action' and release the threads waiting
    # in the barrier.
    def _release(self):
        try:
            if self._action:
                self._action()
            # enter draining state
            self._state = 1
            self._cond.notify_all()
        except:
            #an exception during the _action handler.  Break and reraise
            self._break()
            raise

    # Wait in the barrier until we are released.  Raise an exception
    # if the barrier is reset or broken.
    def _wait(self, timeout):
        if not self._cond.wait_for(lambda : self._state != 0, timeout):
            #timed out.  Break the barrier
            self._break()
            raise BrokenBarrierError
        if self._state < 0:
            raise BrokenBarrierError
        assert self._state == 1

    # If we are the last thread to exit the barrier, signal any threads
    # waiting for the barrier to drain.
    def _exit(self):
        if self._count == 0:
            if self._state in (-1, 1):
                #resetting or draining
                self._state = 0
                self._cond.notify_all()

    def reset(self):
        """Reset the barrier to the initial state.

        Any threads currently waiting will get the BrokenBarrier exception
        raised.
        """
        with self._cond:
            if self._count > 0:
                if self._state == 0:
                    #reset the barrier, waking up threads
                    self._state = -1
                elif self._state == -2:
                    #was broken, set it to reset state
                    #which clears when the last thread exits
                    self._state = -1
            else:
                self._state = 0
            self._cond.notify_all()

    def abort(self):
        """Place the barrier into a 'broken' state.

        Useful in case of error.  Any currently waiting threads and threads
        attempting to 'wait()' will have BrokenBarrierError raised.
        """
        with self._cond:
            self._break()

    def _break(self):
        # An internal error was detected.  The barrier is set to
        # a broken state all parties awakened.
        self._state = -2
        self._cond.notify_all()

    @property
    def parties(self):
        """Return the number of threads required to trip the barrier."""
        return self._parties

    @property
    def n_waiting(self):
        """Return the number of threads currently waiting at the barrier."""
        # We don't need synchronization here since this is an ephemeral result
        # anyway.  It returns the correct value in the steady state.
        if self._state == 0:
            return self._count
        return 0

    @property
    def broken(self):
        """Return True if the barrier is in a broken state."""
        return self._state == -2


# exception raised by the Barrier class
class BrokenBarrierError(RuntimeError):
    pass


class Timer(Thread):
    """Call a function after a specified number of seconds:

            t = Timer(30.0, f, args=None, kwargs=None)
            t.start()
            t.cancel()     # stop the timer's action if it's still waiting
    """

    def __init__(self, interval, function, args=None, kwargs=None):
        Thread.__init__(self)
        self.interval = interval
        self.function = function
        self.args = args if args is not None else []
        self.kwargs = kwargs if kwargs is not None else {}
        self.finished = Event()

    def cancel(self):
        """Stop the timer if it hasn't finished yet."""
        self.finished.set()

    def run(self):
        self.finished.wait(self.interval)
        if not self.finished.is_set():
            self.function(*self.args, **self.kwargs)
        self.finished.set()
