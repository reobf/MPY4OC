"""asyncio for MPY4OC: CPython 3.12's asyncio without I/O.

Each thread runs at most one event loop at a time, in the thread itself: asyncio.run() (or
loop.run_until_complete / run_forever) runs it in the calling thread, as CPython does, and a
coroutine's tasks belong to the loop that runs them. The loop is the one in CPython's
base_events: a queue of callbacks to call soon, a heap of timers, and Futures and Tasks that
schedule callbacks on it. There is no I/O to select on, so when nothing is ready the loop
sleeps (time.sleep) until the next timer, looking every tick for work another thread handed
it (call_soon_threadsafe, run_coroutine_threadsafe). Threads come from threading; nothing
here needs the VM beyond that.

In an MPYOS program the loop's rounds are poll points (see _program): when the program is
stopped, the loop's thread gets SystemExit, and asyncio.run() cancels its tasks on the way
out, as it does when its main coroutine ends.

Not here: streams, subprocesses and other I/O, TaskGroup and Barrier (no ExceptionGroup),
contextvars (context= is accepted and ignored), event loop policies beyond the default.
"""

import time
import heapq

try:
    import _program
except ImportError:
    _program = None

__all__ = ['AbstractEventLoop', 'AbstractEventLoopPolicy', 'BoundedSemaphore', 'CancelledError',
           'Condition', 'DefaultEventLoopPolicy', 'Event', 'EventLoop', 'FIRST_COMPLETED', 'FIRST_EXCEPTION',
           'ALL_COMPLETED', 'Future', 'Handle', 'InvalidStateError', 'LifoQueue', 'Lock', 'PriorityQueue',
           'Queue', 'QueueEmpty', 'QueueFull', 'Runner', 'Semaphore', 'Task', 'Timeout', 'TimeoutError',
           'TimerHandle', 'all_tasks', 'as_completed', 'create_task', 'current_task', 'ensure_future',
           'gather', 'get_event_loop', 'get_event_loop_policy', 'get_running_loop', 'iscoroutine',
           'iscoroutinefunction', 'isfuture', 'new_event_loop', 'run', 'run_coroutine_threadsafe',
           'set_event_loop', 'set_event_loop_policy', 'shield', 'sleep', 'timeout', 'timeout_at',
           'to_thread', 'wait', 'wait_for']

FIRST_COMPLETED = 'FIRST_COMPLETED'
FIRST_EXCEPTION = 'FIRST_EXCEPTION'
ALL_COMPLETED = 'ALL_COMPLETED'

# How often a loop with nothing to do looks for work from other threads (a game tick).
_POLL = 0.05


class CancelledError(BaseException):
    """The operation was cancelled (a BaseException, as since Python 3.8)."""


class InvalidStateError(Exception):
    """The Future is not in a state that allows this."""


TimeoutError = TimeoutError     # asyncio.TimeoutError is the builtin, as since Python 3.11


class QueueEmpty(Exception):
    """get_nowait() on an empty Queue."""


class QueueFull(Exception):
    """put_nowait() on a full Queue."""


# ---- which loop a thread runs -----------------------------------------------------

def _thread():
    # The thread running now (its VM generator), or None on the main frame.
    try:
        return __thread_current()
    except NameError:
        return None


def _key():
    # set_event_loop() is per thread; on the main frame, where MPYOS runs one command after
    # another, also per program, so a command does not find the loop the previous one set.
    return (_thread(), _program.current() if _program is not None else None)


_running_loops = {}     # thread -> the loop it is running
_current_loops = {}     # _key() -> the loop set_event_loop() made current (None: set to None)
_UNSET = object()


def _forget_program(program):
    for key in list(_current_loops):
        if key[1] == program:
            del _current_loops[key]


if _program is not None:
    _program._on_end.append(_forget_program)


def _thread_name():
    import threading
    return threading.current_thread().name


def _get_running_loop():
    return _running_loops.get(_thread())


def get_running_loop():
    """The loop running in this thread; RuntimeError outside one."""
    loop = _running_loops.get(_thread())
    if loop is None:
        raise RuntimeError('no running event loop')
    return loop


def get_event_loop():
    """The running loop, else this thread's current one. As Python 3.12, the main thread
    gets a new one when none was ever set; other threads have to set one first."""
    loop = _running_loops.get(_thread())
    if loop is not None:
        return loop
    key = _key()
    loop = _current_loops.get(key, _UNSET)
    if loop is _UNSET and key[0] is None:
        loop = _current_loops[key] = new_event_loop()
    if loop is _UNSET or loop is None:
        raise RuntimeError('There is no current event loop in thread %r.' % _thread_name())
    return loop


def set_event_loop(loop):
    if loop is not None and not isinstance(loop, AbstractEventLoop):
        raise TypeError("loop must be an instance of AbstractEventLoop or None, not '" + type(loop).__name__ + "'")
    key = _key()
    if loop is None and key[0] is not None:
        # In a thread, None and never set are the same (no loop): keep no entry that would
        # outlive the thread (asyncio.run sets None when it is done).
        _current_loops.pop(key, None)
    else:
        _current_loops[key] = loop


def new_event_loop():
    return EventLoop()


class AbstractEventLoopPolicy:
    pass


class DefaultEventLoopPolicy(AbstractEventLoopPolicy):
    """The only policy: get_event_loop/set_event_loop/new_event_loop as the functions."""

    def get_event_loop(self):
        return get_event_loop()

    def set_event_loop(self, loop):
        set_event_loop(loop)

    def new_event_loop(self):
        return new_event_loop()


_policy = [DefaultEventLoopPolicy()]


def get_event_loop_policy():
    return _policy[0]


def set_event_loop_policy(policy):
    if policy is not None and not isinstance(policy, AbstractEventLoopPolicy):
        raise TypeError("policy must be an instance of AbstractEventLoopPolicy or None, not '" + type(policy).__name__ + "'")
    _policy[0] = DefaultEventLoopPolicy() if policy is None else policy


# ---- what is a coroutine, a future ---------------------------------------------------

def iscoroutine(obj):
    # This VM's coroutines are its generators, which do not show their methods as attributes.
    return repr(obj).startswith('<generator ')


def iscoroutinefunction(func):
    # An async def compiles to a generator function here; a plain generator function is
    # one too, so it also counts (CPython tells them apart).
    try:
        return __is_generator_function(func) or getattr(func, '_is_coroutine', False) is True
    except NameError:
        return getattr(func, '_is_coroutine', False) is True


def isfuture(obj):
    return getattr(obj, '_asyncio_future_blocking', None) is not None


def _isawaitable(obj):
    return iscoroutine(obj) or getattr(obj, '__await__', None) is not None


# ---- callbacks -----------------------------------------------------------------

def _format_callback(callback):
    name = getattr(callback, '__qualname__', None) or getattr(callback, '__name__', None)
    return (name or repr(callback)) + '()'


class Handle:
    """A callback scheduled by call_soon (cancel() keeps it from running)."""

    def __init__(self, callback, args, loop, context=None):
        self._callback = callback
        self._args = args
        self._loop = loop
        self._cancelled = False

    def __repr__(self):
        state = ' cancelled' if self._cancelled else ''
        return '<' + type(self).__name__ + state + ' ' + ('' if self._callback is None else _format_callback(self._callback)) + '>'

    def cancel(self):
        if not self._cancelled:
            self._cancelled = True
            self._callback = None
            self._args = None

    def cancelled(self):
        return self._cancelled

    def get_context(self):
        return None

    def _run(self):
        try:
            self._callback(*self._args)
        except (SystemExit, KeyboardInterrupt):
            raise
        except BaseException as exc:
            self._loop.call_exception_handler({
                'message': 'Exception in callback ' + _format_callback(self._callback),
                'exception': exc,
                'handle': self,
            })


class TimerHandle(Handle):
    """A callback scheduled by call_later/call_at."""

    def __init__(self, when, callback, args, loop, context=None):
        Handle.__init__(self, callback, args, loop, context)
        self._when = when
        self._scheduled = False

    def when(self):
        return self._when

    def cancel(self):
        Handle.cancel(self)


# ---- the event loop ----------------------------------------------------------------

class AbstractEventLoop:
    """Base class of event loops (isinstance checks; EventLoop is the only one)."""


def _run_until_complete_cb(fut):
    if not fut.cancelled():
        exc = fut.exception()
        if isinstance(exc, (SystemExit, KeyboardInterrupt)):
            return      # the loop is being stopped by the exception itself
    fut.get_loop().stop()


class EventLoop(AbstractEventLoop):
    def __init__(self):
        self._ready = []            # handles to run soon (a list: append is atomic across threads)
        self._scheduled = []        # heap of (when, sequence, TimerHandle)
        self._sequence = 0
        self._stopping = False
        self._closed = False
        self._thread = _UNSET       # the thread running it (None is the main frame)
        self._debug = False
        self._exception_handler = None
        self._tasks = []            # tasks not yet done (all_tasks)
        self._unretrieved = []      # tasks that failed and nobody asked why (reported at close)
        self._current_task = None
        self._executor_threads = []
        self._clock_resolution = 0.001  # time() counts milliseconds

    def __repr__(self):
        return '<EventLoop running=' + str(self.is_running()) + ' closed=' + str(self._closed) + ' debug=' + str(self._debug) + '>'

    # -- running --
    def time(self):
        return time.ticks_ms() / 1000

    def is_running(self):
        return self._thread is not _UNSET

    def is_closed(self):
        return self._closed

    def get_debug(self):
        return self._debug

    def set_debug(self, enabled):
        self._debug = bool(enabled)

    def _check_closed(self):
        if self._closed:
            raise RuntimeError('Event loop is closed')

    def _check_running(self):
        if self.is_running():
            raise RuntimeError('This event loop is already running')
        if _get_running_loop() is not None:
            raise RuntimeError('Cannot run the event loop while another loop is running')

    def run_forever(self):
        """Run in this thread until stop()."""
        self._check_closed()
        self._check_running()
        thread = _thread()
        self._thread = thread
        _running_loops[thread] = self
        try:
            while True:
                self._run_once()
                if self._stopping:
                    break
        finally:
            self._stopping = False
            self._thread = _UNSET
            if _running_loops.get(thread) is self:
                del _running_loops[thread]

    def run_until_complete(self, future):
        """Run until future (a Future, Task or coroutine) is done; its result."""
        self._check_closed()
        self._check_running()
        new_task = not isfuture(future)
        future = ensure_future(future, loop=self)
        future.add_done_callback(_run_until_complete_cb)
        try:
            self.run_forever()
        except BaseException:
            if new_task and future.done() and not future.cancelled():
                future.exception()      # its exception goes up with this one
            raise
        finally:
            future.remove_done_callback(_run_until_complete_cb)
        if not future.done():
            raise RuntimeError('Event loop stopped before Future completed.')
        return future.result()

    def stop(self):
        self._stopping = True

    def close(self):
        if self.is_running():
            raise RuntimeError('Cannot close a running event loop')
        if self._closed:
            return
        self._closed = True
        del self._ready[:]
        del self._scheduled[:]
        # CPython reports a task whose exception nobody retrieved when the task is collected;
        # here, when its loop closes.
        for task in self._unretrieved:
            if task._log_traceback:
                task._log_traceback = False
                self.call_exception_handler({
                    'message': 'Task exception was never retrieved',
                    'exception': task._exception,
                    'future': task,
                })
        del self._unretrieved[:]

    def _run_once(self):
        # One round: wait for something to do, move due timers to the ready queue, then run
        # what was ready when the round began (callbacks scheduled now run next round).
        if _program is not None:
            _program.check()            # a poll point: the program may have been stopped
        while self._scheduled and self._scheduled[0][2]._cancelled:
            heapq.heappop(self._scheduled)[2]._scheduled = False
        if self._ready or self._stopping:
            timeout = 0
        elif self._scheduled:
            timeout = self._scheduled[0][0] - self.time()
        else:
            timeout = None
        if timeout is None or timeout > 0:
            self._idle(timeout)
        end_time = self.time() + self._clock_resolution     # as CPython: due within the resolution
        while self._scheduled and self._scheduled[0][0] < end_time:
            handle = heapq.heappop(self._scheduled)[2]
            handle._scheduled = False
            if not handle._cancelled:
                self._ready.append(handle)
        ready = self._ready
        for i in range(len(ready)):
            handle = ready.pop(0)
            if not handle._cancelled:
                handle._run()

    def _idle(self, timeout):
        # Nothing to run: sleep until the next timer is due or another thread hands us work.
        deadline = None if timeout is None else self.time() + timeout
        while not self._ready and not self._stopping:
            if deadline is None:
                time.sleep(_POLL)
            else:
                left = deadline - self.time()
                if left < self._clock_resolution:   # time.sleep() would sleep 0 ms: spin no more
                    return
                time.sleep(left if left < _POLL else _POLL)

    # -- callbacks --
    def _check_callback(self, callback, method):
        if iscoroutine(callback) or iscoroutinefunction(callback):
            raise TypeError('coroutines cannot be used with ' + method + '()')
        if not callable(callback):
            raise TypeError('a callable object was expected by ' + method + '(), got ' + repr(callback))

    def call_soon(self, callback, *args, context=None):
        self._check_closed()
        if self._debug:                 # as CPython, the callback is checked in debug mode only
            self._check_callback(callback, 'call_soon')
        handle = Handle(callback, args, self)
        self._ready.append(handle)
        return handle

    def call_soon_threadsafe(self, callback, *args, context=None):
        # Another thread may call this: list.append is one step on this VM, and the loop
        # notices within a tick when it is idle.
        self._check_closed()
        if self._debug:
            self._check_callback(callback, 'call_soon_threadsafe')
        handle = Handle(callback, args, self)
        self._ready.append(handle)
        return handle

    def call_later(self, delay, callback, *args, context=None):
        if delay is None:
            raise TypeError('delay must not be None')
        return self.call_at(self.time() + delay, callback, *args)

    def call_at(self, when, callback, *args, context=None):
        if when is None:
            raise TypeError('when cannot be None')
        self._check_closed()
        if self._debug:
            self._check_callback(callback, 'call_at')
        timer = TimerHandle(when, callback, args, self)
        self._sequence += 1
        heapq.heappush(self._scheduled, (when, self._sequence, timer))
        timer._scheduled = True
        return timer

    # -- futures and tasks --
    def create_future(self):
        return Future(loop=self)

    def create_task(self, coro, *, name=None, context=None):
        self._check_closed()
        return Task(coro, loop=self, name=name)

    # -- threads --
    def run_in_executor(self, executor, func, *args):
        """func(*args) in a thread of its own (the default executor; others are not
        supported); a Future of its result."""
        self._check_closed()
        if executor is not None:
            raise NotImplementedError('only the default executor (None) is supported')
        future = self.create_future()
        loop = self

        def work():
            try:
                result = func(*args)
            except BaseException as exc:
                if not loop._closed:
                    loop.call_soon_threadsafe(_set_exception_unless_cancelled, future, exc)
                if isinstance(exc, SystemExit):
                    raise
            else:
                if not loop._closed:
                    loop.call_soon_threadsafe(_set_result_unless_cancelled, future, result)

        import threading
        thread = threading.Thread(target=work, name='asyncio_' + str(len(self._executor_threads)))
        self._executor_threads.append(thread)
        thread.start()
        return future

    async def shutdown_default_executor(self, timeout=None):
        """Wait for the threads run_in_executor started (a thread joins them, as in CPython)."""
        if not self._executor_threads:
            return
        import threading
        threads, self._executor_threads = self._executor_threads, []
        future = self.create_future()
        loop = self

        def _do_shutdown():
            for thread in threads:
                thread.join()
            if not loop._closed:
                loop.call_soon_threadsafe(_set_result_unless_cancelled, future, None)

        joiner = threading.Thread(target=_do_shutdown)
        joiner.start()
        try:
            async with timeout_at(None if timeout is None else self.time() + timeout):
                await future
        except TimeoutError:
            print('RuntimeWarning: The executor did not finishing joining its threads within ' + str(timeout) + ' seconds.')
        else:
            joiner.join()

    async def shutdown_asyncgens(self):
        """Asynchronous generators are not tracked: nothing to close."""

    # -- errors --
    def set_exception_handler(self, handler):
        if handler is not None and not callable(handler):
            raise TypeError('A callable object or None is expected, got ' + repr(handler))
        self._exception_handler = handler

    def get_exception_handler(self):
        return self._exception_handler

    def default_exception_handler(self, context):
        message = context.get('message') or 'Unhandled exception in event loop'
        lines = [message]
        for key in sorted(context):
            if key not in ('message', 'exception'):
                lines.append(key + ': ' + repr(context[key]))
        print('\n'.join(lines))
        exception = context.get('exception')
        if exception is not None:
            try:
                __thread_print_exc(exception)
            except NameError:
                print(repr(exception))

    def call_exception_handler(self, context):
        if self._exception_handler is None:
            try:
                self.default_exception_handler(context)
            except (SystemExit, KeyboardInterrupt):
                raise
            except BaseException:
                print('Exception in default exception handler')
        else:
            try:
                self._exception_handler(self, context)
            except (SystemExit, KeyboardInterrupt):
                raise
            except BaseException as exc:
                try:
                    self.default_exception_handler({
                        'message': 'Unhandled error in exception handler',
                        'exception': exc,
                        'context': context,
                    })
                except (SystemExit, KeyboardInterrupt):
                    raise
                except BaseException:
                    print('Exception in default exception handler')


# ---- futures -------------------------------------------------------------------

_PENDING = 'PENDING'
_CANCELLED = 'CANCELLED'
_FINISHED = 'FINISHED'


def _set_result_unless_cancelled(fut, result):
    if fut.cancelled():
        return
    fut.set_result(result)


def _set_exception_unless_cancelled(fut, exc):
    if fut.cancelled():
        return
    fut.set_exception(exc)


class Future:
    """A result that will be there later; awaiting it waits for it."""
    _asyncio_future_blocking = False

    def __init__(self, *, loop=None):
        self._loop = get_event_loop() if loop is None else loop
        self._state = _PENDING
        self._result = None
        self._exception = None
        self._callbacks = []
        self._cancel_message = None
        self._cancelled_exc = None
        self._log_traceback = False

    def _repr_info(self):
        if self._state == _FINISHED:
            if self._exception is not None:
                return ['finished', 'exception=' + repr(self._exception)]
            return ['finished', 'result=' + repr(self._result)]
        return [self._state.lower()]

    def __repr__(self):
        return '<' + type(self).__name__ + ' ' + ' '.join(self._repr_info()) + '>'

    def get_loop(self):
        return self._loop

    def _make_cancelled_error(self):
        if self._cancelled_exc is not None:
            exc = self._cancelled_exc
            self._cancelled_exc = None
            return exc
        if self._cancel_message is None:
            return CancelledError()
        return CancelledError(self._cancel_message)

    def cancel(self, msg=None):
        self._log_traceback = False
        if self._state != _PENDING:
            return False
        self._state = _CANCELLED
        self._cancel_message = msg
        self._schedule_callbacks()
        return True

    def _schedule_callbacks(self):
        callbacks = self._callbacks
        if not callbacks:
            return
        self._callbacks = []
        for callback in callbacks:
            self._loop.call_soon(callback, self)

    def cancelled(self):
        return self._state == _CANCELLED

    def done(self):
        return self._state != _PENDING

    def result(self):
        if self._state == _CANCELLED:
            raise self._make_cancelled_error()
        if self._state != _FINISHED:
            raise InvalidStateError('Result is not set.')
        self._log_traceback = False
        if self._exception is not None:
            raise self._exception
        return self._result

    def exception(self):
        if self._state == _CANCELLED:
            raise self._make_cancelled_error()
        if self._state != _FINISHED:
            raise InvalidStateError('Exception is not set.')
        self._log_traceback = False
        return self._exception

    def add_done_callback(self, fn, *, context=None):
        if self._state != _PENDING:
            self._loop.call_soon(fn, self)
        else:
            self._callbacks.append(fn)

    def remove_done_callback(self, fn):
        kept = [f for f in self._callbacks if f != fn]
        removed = len(self._callbacks) - len(kept)
        if removed:
            self._callbacks = kept
        return removed

    def set_result(self, result):
        if self._state != _PENDING:
            raise InvalidStateError('invalid state')
        self._result = result
        self._state = _FINISHED
        self._schedule_callbacks()

    def set_exception(self, exception):
        if self._state != _PENDING:
            raise InvalidStateError('invalid state')
        if isinstance(exception, type):
            exception = exception()
        if type(exception) is StopIteration:
            raise TypeError('StopIteration interacts badly with generators and cannot be raised into a Future')
        self._exception = exception
        self._state = _FINISHED
        self._schedule_callbacks()
        self._log_traceback = True

    def __await__(self):
        if not self.done():
            self._asyncio_future_blocking = True
            yield self                  # the Task waits for us (see Task._step)
        if not self.done():
            raise RuntimeError("await wasn't used with future")
        return self.result()

    __iter__ = __await__


# ---- tasks -----------------------------------------------------------------------

_task_counter = [0]


def _sleep0():
    # A bare yield: the task gives up the loop for one round.
    yield


class Task(Future):
    """A coroutine run by the loop, step by step, as a Future of its result."""

    def __init__(self, coro, *, loop=None, name=None, context=None, eager_start=False):
        Future.__init__(self, loop=loop)
        if not iscoroutine(coro):
            raise TypeError('a coroutine was expected, got ' + repr(coro))
        if name is None:
            _task_counter[0] += 1
            self._name = 'Task-' + str(_task_counter[0])
        else:
            self._name = str(name)
        self._num_cancels_requested = 0
        self._must_cancel = False
        self._fut_waiter = None
        self._coro = coro
        self._loop.call_soon(self._step)
        self._loop._tasks.append(self)

    def _repr_info(self):
        info = Future._repr_info(self)
        info.insert(1, 'name=' + repr(self._name))
        return info

    def get_coro(self):
        return self._coro

    def get_context(self):
        return None

    def get_name(self):
        return self._name

    def set_name(self, value):
        self._name = str(value)

    def set_result(self, result):
        raise RuntimeError('Task does not support set_result operation')

    def set_exception(self, exception):
        raise RuntimeError('Task does not support set_exception operation')

    def get_stack(self, *, limit=None):
        return []

    def print_stack(self, *, limit=None, file=None):
        print('No stack for ' + repr(self))

    def cancel(self, msg=None):
        """Request cancellation: a CancelledError where the coroutine waits next."""
        self._log_traceback = False
        if self.done():
            return False
        self._num_cancels_requested += 1
        if self._fut_waiter is not None:
            if self._fut_waiter.cancel(msg=msg):
                # The awaited future's cancellation wakes us with the CancelledError.
                return True
        self._must_cancel = True
        self._cancel_message = msg
        return True

    def cancelling(self):
        return self._num_cancels_requested

    def uncancel(self):
        if self._num_cancels_requested > 0:
            self._num_cancels_requested -= 1
            if self._num_cancels_requested == 0:
                self._must_cancel = False
        return self._num_cancels_requested

    def _finish(self):
        # Done: no longer in all_tasks(); if it failed, keep it to report at loop close
        # unless someone asks for its exception first.
        tasks = self._loop._tasks
        if self in tasks:
            tasks.remove(self)
        if self._log_traceback:
            self._loop._unretrieved.append(self)

    def _step(self, exc=None):
        if self.done():
            raise InvalidStateError('_step(): already done: ' + repr(self) + ', ' + repr(exc))
        if self._must_cancel:
            if not isinstance(exc, CancelledError):
                exc = self._make_cancelled_error()
            self._must_cancel = False
        self._fut_waiter = None
        loop = self._loop
        previous = loop._current_task
        loop._current_task = self
        try:
            self._step_run(exc)
        finally:
            loop._current_task = previous
        if self.done():
            self._finish()

    def _step_run(self, exc):
        coro = self._coro
        try:
            if exc is None:
                result = coro.send(None)
            else:
                result = coro.throw(exc)
        except StopIteration as stop:
            if self._must_cancel:
                # Cancelled while it was finishing: as CPython, a cancelled task.
                self._must_cancel = False
                Future.cancel(self, msg=self._cancel_message)
            else:
                Future.set_result(self, stop.args[0] if stop.args else None)
        except CancelledError as cancelled:
            self._cancelled_exc = cancelled
            Future.cancel(self)
        except (KeyboardInterrupt, SystemExit) as fatal:
            Future.set_exception(self, fatal)
            self._log_traceback = False
            raise
        except BaseException as error:
            Future.set_exception(self, error)
        else:
            blocking = getattr(result, '_asyncio_future_blocking', None)
            if blocking is not None:
                if result.get_loop() is not self._loop:
                    new_exc = RuntimeError('Task ' + repr(self) + ' got Future ' + repr(result) + ' attached to a different loop')
                    self._loop.call_soon(self._step, new_exc)
                elif blocking:
                    if result is self:
                        self._loop.call_soon(self._step, RuntimeError('Task cannot await on itself: ' + repr(self)))
                    else:
                        result._asyncio_future_blocking = False
                        result.add_done_callback(self._wakeup)
                        self._fut_waiter = result
                        if self._must_cancel:
                            if self._fut_waiter.cancel(msg=self._cancel_message):
                                self._must_cancel = False
                else:
                    self._loop.call_soon(self._step, RuntimeError(
                        'yield was used instead of yield from in task ' + repr(self) + ' with ' + repr(result)))
            elif result is None:
                # A bare yield: give up the loop for one round.
                self._loop.call_soon(self._step)
            elif iscoroutine(result):
                self._loop.call_soon(self._step, RuntimeError(
                    'yield was used instead of yield from for generator in task ' + repr(self) + ' with ' + repr(result)))
            else:
                self._loop.call_soon(self._step, RuntimeError('Task got bad yield: ' + repr(result)))

    def _wakeup(self, future):
        try:
            future.result()
        except BaseException as exc:
            # This may also be a cancellation.
            self._step(exc)
        else:
            self._step()


def create_task(coro, *, name=None, context=None):
    """A Task for coro on the running loop (RuntimeError outside one)."""
    task = get_running_loop().create_task(coro)
    if name is not None:
        task.set_name(name)     # as CPython: named after it was numbered
    return task


def current_task(loop=None):
    """The task running now on loop (the running loop by default), or None."""
    if loop is None:
        loop = get_running_loop()
    return loop._current_task


def all_tasks(loop=None):
    """The loop's tasks that are not done yet."""
    if loop is None:
        loop = get_running_loop()
    return set(t for t in loop._tasks if not t.done())


def ensure_future(coro_or_future, *, loop=None):
    """A Future as is; a coroutine (or awaitable) as a Task on loop."""
    if isfuture(coro_or_future):
        if loop is not None and loop is not coro_or_future.get_loop():
            raise ValueError('The future belongs to a different loop than the one specified as the loop argument')
        return coro_or_future
    if not iscoroutine(coro_or_future):
        if getattr(coro_or_future, '__await__', None) is not None:
            awaitable = coro_or_future

            async def _wrap_awaitable():
                return await awaitable
            coro_or_future = _wrap_awaitable()
        else:
            raise TypeError('An asyncio.Future, a coroutine or an awaitable is required')
    if loop is None:
        loop = get_event_loop()
    return loop.create_task(coro_or_future)


async def sleep(delay, result=None):
    """Wait delay seconds (0: one round of the loop); then result."""
    if delay <= 0:
        await _sleep0()
        return result
    loop = get_running_loop()
    future = loop.create_future()
    handle = loop.call_later(delay, _set_result_unless_cancelled, future, result)
    try:
        return await future
    finally:
        handle.cancel()


# ---- running ------------------------------------------------------------------------

def _cancel_all_tasks(loop):
    to_cancel = all_tasks(loop)
    if not to_cancel:
        return
    for task in to_cancel:
        task.cancel()
    loop.run_until_complete(gather(*to_cancel, return_exceptions=True))
    for task in to_cancel:
        if task.cancelled():
            continue
        if task.exception() is not None:
            loop.call_exception_handler({
                'message': 'unhandled exception during asyncio.run() shutdown',
                'exception': task.exception(),
                'task': task,
            })


class Runner:
    """A loop to run coroutines in, closed (its tasks cancelled) by close() or `with`."""

    def __init__(self, *, debug=None, loop_factory=None):
        self._state = 'created'
        self._debug = debug
        self._loop_factory = loop_factory
        self._loop = None
        self._set_event_loop = False

    def __enter__(self):
        self._lazy_init()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def close(self):
        if self._state != 'initialized':
            return
        try:
            loop = self._loop
            _cancel_all_tasks(loop)
            loop.run_until_complete(loop.shutdown_asyncgens())
            loop.run_until_complete(loop.shutdown_default_executor())
        finally:
            if self._set_event_loop:
                set_event_loop(None)
            loop.close()
            self._loop = None
            self._state = 'closed'

    def get_loop(self):
        self._lazy_init()
        return self._loop

    def run(self, coro, *, context=None):
        if not iscoroutine(coro):
            raise ValueError('a coroutine was expected, got ' + repr(coro))
        if _get_running_loop() is not None:
            raise RuntimeError('Runner.run() cannot be called from a running event loop')
        self._lazy_init()
        task = self._loop.create_task(coro)
        return self._loop.run_until_complete(task)

    def _lazy_init(self):
        if self._state == 'closed':
            raise RuntimeError('Runner is closed')
        if self._state == 'initialized':
            return
        if self._loop_factory is None:
            self._loop = new_event_loop()
            set_event_loop(self._loop)
            self._set_event_loop = True
        else:
            self._loop = self._loop_factory()
        if self._debug is not None:
            self._loop.set_debug(self._debug)
        self._state = 'initialized'


def run(main, *, debug=None, loop_factory=None):
    """Run coroutine main in a new event loop in this thread, cancel what it leaves running,
    close the loop; main's result."""
    if _get_running_loop() is not None:
        raise RuntimeError('asyncio.run() cannot be called from a running event loop')
    # As CPython, the loop exists (and is this thread's current one) before main is checked.
    with Runner(debug=debug, loop_factory=loop_factory) as runner:
        return runner.run(main)


# ---- combining futures --------------------------------------------------------

class _GatheringFuture(Future):
    def __init__(self, children, *, loop):
        Future.__init__(self, loop=loop)
        self._children = children
        self._cancel_requested = False

    def cancel(self, msg=None):
        if self.done():
            return False
        ret = False
        for child in self._children:
            if child.cancel(msg=msg):
                ret = True
        if ret:
            # A child that does not take the cancellation still ends the gather as cancelled.
            self._cancel_requested = True
        return ret


def gather(*coros_or_futures, return_exceptions=False):
    """A Future of the results (in order) of the given coroutines and futures."""
    if not coros_or_futures:
        loop = get_event_loop()
        outer = loop.create_future()
        outer.set_result([])
        return outer
    state = {'finished': 0, 'outer': None}
    children = []
    arg_to_fut = {}
    done_futs = []
    loop = None

    def _done_callback(fut):
        state['finished'] += 1
        outer = state['outer']
        if outer is None or outer.done():
            if not fut.cancelled():
                fut.exception()         # retrieved
            return
        if not return_exceptions:
            if fut.cancelled():
                outer.set_exception(fut._make_cancelled_error())
                return
            exc = fut.exception()
            if exc is not None:
                outer.set_exception(exc)
                return
        if state['finished'] == len(arg_to_fut):
            results = []
            for child in children:
                if child.cancelled():
                    res = CancelledError('' if child._cancel_message is None else child._cancel_message)
                else:
                    res = child.exception()
                    if res is None:
                        res = child.result()
                results.append(res)
            if outer._cancel_requested:
                outer.set_exception(fut._make_cancelled_error())
            else:
                outer.set_result(results)

    for arg in coros_or_futures:
        if arg not in arg_to_fut:
            fut = ensure_future(arg, loop=loop)
            if loop is None:
                loop = fut.get_loop()
            arg_to_fut[arg] = fut
            if fut.done():
                done_futs.append(fut)
            else:
                fut.add_done_callback(_done_callback)
        else:
            fut = arg_to_fut[arg]
        children.append(fut)
    outer = _GatheringFuture(children, loop=loop)
    state['outer'] = outer
    for fut in done_futs:
        _done_callback(fut)
    return outer


def shield(arg):
    """Await arg without passing a cancellation on to it."""
    inner = ensure_future(arg)
    if inner.done():
        return inner
    loop = inner.get_loop()
    outer = loop.create_future()

    def _inner_done_callback(inner):
        if outer.cancelled():
            if not inner.cancelled():
                inner.exception()       # retrieved
            return
        if inner.cancelled():
            outer.cancel()
        else:
            exc = inner.exception()
            if exc is not None:
                outer.set_exception(exc)
            else:
                outer.set_result(inner.result())

    def _outer_done_callback(outer):
        if not inner.done():
            inner.remove_done_callback(_inner_done_callback)

    inner.add_done_callback(_inner_done_callback)
    outer.add_done_callback(_outer_done_callback)
    return outer


def _release_waiter(waiter, *args):
    if not waiter.done():
        waiter.set_result(None)


async def wait(fs, *, timeout=None, return_when=ALL_COMPLETED):
    """Wait for the Futures/Tasks in fs: (done, pending) sets."""
    if isfuture(fs) or iscoroutine(fs):
        raise TypeError('expect an iterable of futures, not ' + type(fs).__name__)
    if not fs:
        raise ValueError('Set of Tasks/Futures is empty.')
    if return_when not in (FIRST_COMPLETED, FIRST_EXCEPTION, ALL_COMPLETED):
        raise ValueError('Invalid return_when value: ' + str(return_when))
    fs = set(fs)
    for f in fs:
        if iscoroutine(f):
            raise TypeError('Passing coroutines is forbidden, use tasks explicitly.')
    loop = get_running_loop()
    waiter = loop.create_future()
    timeout_handle = None
    if timeout is not None:
        timeout_handle = loop.call_later(timeout, _release_waiter, waiter)
    counter = [len(fs)]

    def _on_completion(f):
        counter[0] -= 1
        if (counter[0] <= 0 or return_when == FIRST_COMPLETED
                or return_when == FIRST_EXCEPTION and not f.cancelled() and f.exception() is not None):
            if timeout_handle is not None:
                timeout_handle.cancel()
            if not waiter.done():
                waiter.set_result(None)

    for f in fs:
        f.add_done_callback(_on_completion)
    try:
        await waiter
    finally:
        if timeout_handle is not None:
            timeout_handle.cancel()
        for f in fs:
            f.remove_done_callback(_on_completion)
    done, pending = set(), set()
    for f in fs:
        if f.done():
            done.add(f)
        else:
            pending.add(f)
    return done, pending


async def _cancel_and_wait(fut):
    loop = get_running_loop()
    waiter = loop.create_future()

    def _done(f):
        if not waiter.done():
            waiter.set_result(None)

    fut.add_done_callback(_done)
    try:
        fut.cancel()
        await waiter
    finally:
        fut.remove_done_callback(_done)


async def wait_for(fut, timeout):
    """Await fut, cancelling it and raising TimeoutError after timeout seconds."""
    if timeout is not None and timeout <= 0:
        fut = ensure_future(fut)
        if fut.done():
            return fut.result()
        await _cancel_and_wait(fut)
        try:
            return fut.result()
        except CancelledError:
            raise TimeoutError()
    async with timeout_at(None if timeout is None else get_running_loop().time() + timeout):
        return await fut


def as_completed(fs, *, timeout=None):
    """Coroutines that give the results of fs in the order they finish."""
    if isfuture(fs) or iscoroutine(fs):
        raise TypeError('expect an iterable of futures, not ' + type(fs).__name__)
    done = Queue()
    loop = get_event_loop()
    todo = set(ensure_future(f, loop=loop) for f in set(fs))
    handle = [None]

    def _on_timeout():
        for f in todo:
            f.remove_done_callback(_on_completion)
            done.put_nowait(None)
        todo.clear()

    def _on_completion(f):
        if not todo:
            return
        todo.remove(f)
        done.put_nowait(f)
        if not todo and handle[0] is not None:
            handle[0].cancel()

    async def _wait_for_one():
        f = await done.get()
        if f is None:
            raise TimeoutError()
        return f.result()

    for f in todo:
        f.add_done_callback(_on_completion)
    if todo and timeout is not None:
        handle[0] = loop.call_later(timeout, _on_timeout)
    return iter([_wait_for_one() for _ in range(len(todo))])


# ---- timeouts (Python 3.11) ------------------------------------------------------

class Timeout:
    """`async with asyncio.timeout(delay)`: cancels the task when the time comes, and turns
    that cancellation into TimeoutError when the block exits."""

    def __init__(self, when):
        self._state = 'created'
        self._timeout_handler = None
        self._task = None
        self._when = when
        self._cancelling = 0

    def when(self):
        return self._when

    def reschedule(self, when):
        if self._state != 'active':
            if self._state == 'created':
                raise RuntimeError('Timeout has not been entered')
            raise RuntimeError('Cannot change state of ' + self._state + ' Timeout')
        self._when = when
        if self._timeout_handler is not None:
            self._timeout_handler.cancel()
        if when is None:
            self._timeout_handler = None
        else:
            loop = get_running_loop()
            if when <= loop.time():
                self._timeout_handler = loop.call_soon(self._on_timeout)
            else:
                self._timeout_handler = loop.call_at(when, self._on_timeout)

    def expired(self):
        return self._state in ('expiring', 'expired')

    def __repr__(self):
        info = [self._state]
        if self._state == 'active':
            info.append('when=' + repr(self._when))
        return '<Timeout [' + ' '.join(info) + ']>'

    async def __aenter__(self):
        if self._state != 'created':
            raise RuntimeError('Timeout has already been entered')
        task = current_task()
        if task is None:
            raise RuntimeError('Timeout should be used inside a task')
        self._state = 'active'
        self._task = task
        self._cancelling = task.cancelling()
        self.reschedule(self._when)
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if self._timeout_handler is not None:
            self._timeout_handler.cancel()
            self._timeout_handler = None
        if self._state == 'expiring':
            self._state = 'expired'
            if self._task.uncancel() <= self._cancelling and isinstance(exc_val, CancelledError):
                # Our own cancellation, nobody else's: report it as the timeout.
                raise TimeoutError()
        elif self._state == 'active':
            self._state = 'exited'
        return None

    def _on_timeout(self):
        self._task.cancel()
        self._state = 'expiring'
        self._timeout_handler = None


def timeout(delay):
    """`async with asyncio.timeout(seconds)` (None: no limit)."""
    loop = get_running_loop()
    return Timeout(loop.time() + delay if delay is not None else None)


def timeout_at(when):
    """`async with asyncio.timeout_at(loop.time() + seconds)`."""
    return Timeout(when)


# ---- other threads ------------------------------------------------------------------

class _ThreadFuture:
    """What run_coroutine_threadsafe returns (a concurrent.futures.Future): result()
    waits in the calling thread."""

    def __init__(self):
        self._state = _PENDING
        self._result = None
        self._exception = None
        self._callbacks = []
        self._cancel_hook = None

    def done(self):
        return self._state != _PENDING

    def cancelled(self):
        return self._state == _CANCELLED

    def running(self):
        return self._state == _PENDING

    def cancel(self):
        if self._state != _PENDING:
            return False
        if self._cancel_hook is not None:
            self._cancel_hook()
        self._state = _CANCELLED
        self._run_callbacks()
        return True

    def _wait(self, timeout):
        deadline = None if timeout is None else time.ticks_add(time.ticks_ms(), int(timeout * 1000))
        while self._state == _PENDING:
            if deadline is not None and time.ticks_diff(deadline, time.ticks_ms()) <= 0:
                raise TimeoutError()
            time.sleep(_POLL)

    def result(self, timeout=None):
        self._wait(timeout)
        if self._state == _CANCELLED:
            raise CancelledError()
        if self._exception is not None:
            raise self._exception
        return self._result

    def exception(self, timeout=None):
        self._wait(timeout)
        if self._state == _CANCELLED:
            raise CancelledError()
        return self._exception

    def add_done_callback(self, fn):
        if self._state != _PENDING:
            fn(self)
        else:
            self._callbacks.append(fn)

    def _set(self, result=None, exception=None, cancelled=False):
        if self._state != _PENDING:
            return
        if cancelled:
            self._state = _CANCELLED
        else:
            self._result, self._exception, self._state = result, exception, _FINISHED
        self._run_callbacks()

    def _run_callbacks(self):
        callbacks, self._callbacks = self._callbacks, []
        for fn in callbacks:
            try:
                fn(self)
            except Exception as exc:
                print('exception calling callback for ' + repr(self) + ': ' + repr(exc))


def run_coroutine_threadsafe(coro, loop):
    """Run coro on loop (another thread's) from this thread; a Future-like whose
    result(timeout) waits for it here."""
    if not iscoroutine(coro):
        raise TypeError('A coroutine object is required')
    future = _ThreadFuture()

    def callback():
        try:
            task = ensure_future(coro, loop=loop)
        except (SystemExit, KeyboardInterrupt):
            raise
        except BaseException as exc:
            future._set(exception=exc)
            raise

        def copy(task):
            if task.cancelled():
                future._set(cancelled=True)
            elif task.exception() is not None:
                future._set(exception=task.exception())
            else:
                future._set(result=task.result())

        task.add_done_callback(copy)
        future._cancel_hook = lambda: loop.call_soon_threadsafe(task.cancel)

    loop.call_soon_threadsafe(callback)
    return future


async def to_thread(func, *args, **kwargs):
    """Run func(*args, **kwargs) in a thread of its own; await its result."""
    loop = get_running_loop()
    return await loop.run_in_executor(None, lambda: func(*args, **kwargs))


# ---- synchronization (asyncio.locks) -------------------------------------------------

class _LoopBound:
    _loop = None

    def _get_loop(self):
        loop = get_running_loop()
        if self._loop is None:
            self._loop = loop
        if loop is not self._loop:
            raise RuntimeError(repr(self) + ' is bound to a different event loop')
        return loop


class _ContextManagerMixin:
    async def __aenter__(self):
        await self.acquire()
        return None

    async def __aexit__(self, exc_type, exc, tb):
        self.release()


class Lock(_ContextManagerMixin, _LoopBound):
    """A coroutine lock: `async with lock:`, granted in the order the waiters asked."""

    def __init__(self):
        self._waiters = None
        self._locked = False

    def __repr__(self):
        extra = 'locked' if self._locked else 'unlocked'
        if self._waiters:
            extra += ', waiters:' + str(len(self._waiters))
        return '<asyncio.locks.Lock [' + extra + ']>'

    def locked(self):
        return self._locked

    async def acquire(self):
        if not self._locked and (self._waiters is None or all(w.cancelled() for w in self._waiters)):
            self._locked = True
            return True
        if self._waiters is None:
            self._waiters = []
        fut = self._get_loop().create_future()
        self._waiters.append(fut)
        try:
            try:
                await fut
            finally:
                self._waiters.remove(fut)
        except CancelledError:
            if not self._locked:
                self._wake_up_first()
            raise
        self._locked = True
        return True

    def release(self):
        if self._locked:
            self._locked = False
            self._wake_up_first()
        else:
            raise RuntimeError('Lock is not acquired.')

    def _wake_up_first(self):
        if not self._waiters:
            return
        fut = self._waiters[0]
        if not fut.done():
            fut.set_result(True)


class Event(_LoopBound):
    """A flag coroutines can wait for."""

    def __init__(self):
        self._waiters = []
        self._value = False

    def __repr__(self):
        extra = 'set' if self._value else 'unset'
        if self._waiters:
            extra += ', waiters:' + str(len(self._waiters))
        return '<asyncio.locks.Event [' + extra + ']>'

    def is_set(self):
        return self._value

    def set(self):
        if not self._value:
            self._value = True
            for fut in self._waiters:
                if not fut.done():
                    fut.set_result(True)

    def clear(self):
        self._value = False

    async def wait(self):
        if self._value:
            return True
        fut = self._get_loop().create_future()
        self._waiters.append(fut)
        try:
            await fut
            return True
        finally:
            self._waiters.remove(fut)


class Condition(_ContextManagerMixin, _LoopBound):
    """wait() releases the lock until notify(); then takes it again."""

    def __init__(self, lock=None):
        if lock is None:
            lock = Lock()
        self._lock = lock
        self.locked = lock.locked
        self.acquire = lock.acquire
        self.release = lock.release
        self._waiters = []

    def __repr__(self):
        extra = 'locked' if self.locked() else 'unlocked'
        if self._waiters:
            extra += ', waiters:' + str(len(self._waiters))
        return '<asyncio.locks.Condition [' + extra + ']>'

    async def wait(self):
        if not self.locked():
            raise RuntimeError('cannot wait on un-acquired lock')
        self.release()
        try:
            fut = self._get_loop().create_future()
            self._waiters.append(fut)
            try:
                await fut
                return True
            finally:
                self._waiters.remove(fut)
        finally:
            # Take the lock again whatever happened (as CPython, retrying on cancellation).
            cancelled = None
            while True:
                try:
                    await self.acquire()
                    break
                except CancelledError as error:
                    cancelled = error
            if cancelled is not None:
                raise cancelled

    async def wait_for(self, predicate):
        result = predicate()
        while not result:
            await self.wait()
            result = predicate()
        return result

    def notify(self, n=1):
        if not self.locked():
            raise RuntimeError('cannot notify on un-acquired lock')
        idx = 0
        for fut in self._waiters:
            if idx >= n:
                break
            if not fut.done():
                idx += 1
                fut.set_result(False)

    def notify_all(self):
        self.notify(len(self._waiters))


class Semaphore(_ContextManagerMixin, _LoopBound):
    """A counter: acquire() waits while it is 0, in the order the waiters asked."""

    def __init__(self, value=1):
        if value < 0:
            raise ValueError('Semaphore initial value must be >= 0')
        self._waiters = None
        self._value = value

    def __repr__(self):
        extra = 'locked' if self.locked() else 'unlocked, value:' + str(self._value)
        if self._waiters:
            extra += ', waiters:' + str(len(self._waiters))
        return '<asyncio.locks.' + type(self).__name__ + ' [' + extra + ']>'

    def locked(self):
        return self._value == 0 or any(not w.cancelled() for w in (self._waiters or ()))

    async def acquire(self):
        if not self.locked():
            self._value -= 1
            return True
        if self._waiters is None:
            self._waiters = []
        fut = self._get_loop().create_future()
        self._waiters.append(fut)
        try:
            try:
                await fut
            finally:
                self._waiters.remove(fut)
        except CancelledError:
            if fut.done() and not fut.cancelled():
                self._value += 1
            raise
        finally:
            while self._value > 0:
                if not self._wake_up_next():
                    break
        return True

    def release(self):
        self._value += 1
        self._wake_up_next()

    def _wake_up_next(self):
        if not self._waiters:
            return False
        for fut in self._waiters:
            if not fut.done():
                self._value -= 1
                fut.set_result(True)
                return True
        return False


class BoundedSemaphore(Semaphore):
    """A Semaphore that may not be released more often than it was acquired."""

    def __init__(self, value=1):
        self._bound_value = value
        Semaphore.__init__(self, value)

    def release(self):
        if self._value >= self._bound_value:
            raise ValueError('BoundedSemaphore released too many times')
        Semaphore.release(self)


# ---- queues (asyncio.queues) ---------------------------------------------------------

class Queue(_LoopBound):
    """A FIFO queue for coroutines; maxsize 0 is unbounded."""

    def __init__(self, maxsize=0):
        self._maxsize = maxsize
        self._getters = []
        self._putters = []
        self._unfinished_tasks = 0
        self._finished = Event()
        self._finished.set()
        self._init(maxsize)

    def _init(self, maxsize):
        self._queue = []

    def _get(self):
        return self._queue.pop(0)

    def _put(self, item):
        self._queue.append(item)

    def _wakeup_next(self, waiters):
        while waiters:
            waiter = waiters.pop(0)
            if not waiter.done():
                waiter.set_result(None)
                break

    def __repr__(self):
        return '<' + type(self).__name__ + ' maxsize=' + repr(self._maxsize) + '>'

    def qsize(self):
        return len(self._queue)

    @property
    def maxsize(self):
        return self._maxsize

    def empty(self):
        return not self._queue

    def full(self):
        if self._maxsize <= 0:
            return False
        return self.qsize() >= self._maxsize

    async def put(self, item):
        while self.full():
            putter = self._get_loop().create_future()
            self._putters.append(putter)
            try:
                await putter
            except BaseException:
                putter.cancel()
                if putter in self._putters:
                    self._putters.remove(putter)
                if not self.full() and not putter.cancelled():
                    self._wakeup_next(self._putters)
                raise
        return self.put_nowait(item)

    def put_nowait(self, item):
        if self.full():
            raise QueueFull
        self._put(item)
        self._unfinished_tasks += 1
        self._finished.clear()
        self._wakeup_next(self._getters)

    async def get(self):
        while self.empty():
            getter = self._get_loop().create_future()
            self._getters.append(getter)
            try:
                await getter
            except BaseException:
                getter.cancel()
                if getter in self._getters:
                    self._getters.remove(getter)
                if not self.empty() and not getter.cancelled():
                    self._wakeup_next(self._getters)
                raise
        return self.get_nowait()

    def get_nowait(self):
        if self.empty():
            raise QueueEmpty
        item = self._get()
        self._wakeup_next(self._putters)
        return item

    def task_done(self):
        if self._unfinished_tasks <= 0:
            raise ValueError('task_done() called too many times')
        self._unfinished_tasks -= 1
        if self._unfinished_tasks == 0:
            self._finished.set()

    async def join(self):
        if self._unfinished_tasks > 0:
            await self._finished.wait()


class PriorityQueue(Queue):
    """Lowest first (entries are usually (priority, data) tuples)."""

    def _init(self, maxsize):
        self._queue = []

    def _put(self, item):
        heapq.heappush(self._queue, item)

    def _get(self):
        return heapq.heappop(self._queue)


class LifoQueue(Queue):
    """Last in, first out."""

    def _get(self):
        return self._queue.pop()

