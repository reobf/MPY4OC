# jasyncio: a cooperative event loop for this VM. NOT standard uasyncio — scheduling
# is advanced by the embedding host via the VM's stepAsync(asyncOps), which fairly
# divides an op budget across runnable tasks. A task keeps its seat when it yields,
# is blacklisted for the round if it goes to the host via yieldJava(), and is removed
# when it finishes. sleep_ms() uses real wall-clock time (System.currentTimeMillis),
# but only while the VM is live and being driven: time elapsed while a snapshot is at
# rest does not count, because a restore resets each timer's clock base to the
# remaining amount. sleep(s) == sleep_ms(s * 1000).

# jasyncio.wait() return_when constants (match CPython asyncio string values)
FIRST_COMPLETED = "FIRST_COMPLETED"
FIRST_EXCEPTION = "FIRST_EXCEPTION"
ALL_COMPLETED = "ALL_COMPLETED"

import time


# ---------------------------------------------------------------------------
# Coroutine groups ("one run() = one private scheduling pool").
#
# A group is ONE task in the VM's pool; its member coroutines are driven by the
# group's scheduler generator (_group_sched), never by the VM directly. That
# makes coroutine atomicity STRUCTURAL: when the scheduler is switched away --
# ops budget exhausted mid-member, or a member's host call parks it -- the whole
# group stops together, so no member can ever observe another mid-step. Threads
# (to_thread / threading.Thread) stay as flat pool tasks and keep interleaving
# freely; they are exactly the tasks that are ALLOWED to see torn state.
#
# run() creates a fresh group each call, so runs are independent and re-entrant
# (a thread can run(); several runs can coexist). create_task() joins the group
# of the coroutine that calls it, or a lazily-created default group when called
# from the main frame / a thread -- the default group is the "global event
# loop": its members are mutually atomic, like asyncio's implicit loop.
# ---------------------------------------------------------------------------

class _Group:
    def __init__(self):
        self.members = []      # Task objects, round-robin in creation order
        self.dead = False      # scheduler exited (all members finished)
        self.sched_gen = None  # the scheduler generator registered on the VM pool


def _group_sched(g):
    # The scheduler: one send() per runnable member per round, then one
    # cooperative yield to the VM pool. Switching between members happens ONLY
    # here, between sends -- which is what makes two yields of a member atomic
    # with respect to its siblings.
    #
    # Member sleeps are tracked as REMAINING milliseconds, decremented by the
    # time observed between rounds -- the same semantics as the VM's own task
    # timer (time at rest under a snapshot is never counted, because we are not
    # running to observe it). When the whole group parks on the VM timer and is
    # woken, at least the requested span is credited even if the host clock did
    # not move (test benches often freeze the game clock; the VM timer is what
    # actually woke us, so that much time HAS passed).
    last = time.ticks_ms()
    slept_hint = 0
    while g.members:
        now = time.ticks_ms()
        elapsed = time.ticks_diff(now, last)
        if elapsed < 0:
            elapsed = 0
        if slept_hint > elapsed:
            elapsed = slept_hint
        slept_hint = 0
        last = now
        if elapsed > 0:
            for t in g.members:
                if t._park_ms > 0:
                    t._park_ms -= elapsed
                    if t._park_ms < 0:
                        t._park_ms = 0
        ran = False
        i = 0
        while i < len(g.members):        # live list: tasks created mid-round run this round
            t = g.members[i]
            i += 1
            if t._done:
                continue
            if t._park_ms > 0:
                continue                 # still sleeping
            ran = True
            try:
                got = t._driver.send(None)
            except StopIteration:
                continue                 # driver finished; t._done already set
            except BaseException as e:   # defensive: _run captures everything itself
                if not t._done:
                    t._done = True
                    if t.exc is None:
                        t.exc = e
                continue
            ms = __jasyncio_sleep_ms(got)
            if ms is not None:
                t._park_ms = ms
        _keep = []
        for t in g.members:
            if not t._done:
                _keep.append(t)
        g.members = _keep
        if not g.members:
            break
        if ran:
            yield                        # give the pool a turn; come back next round
        else:
            # everyone is sleeping: park the WHOLE group on the earliest deadline
            # (the VM's timer wakes us; cancel() wakes us early via _wake_task)
            soon = None
            for t in g.members:
                if soon is None or t._park_ms < soon:
                    soon = t._park_ms
            if soon is None or soon <= 0:
                yield
            else:
                yield __jasyncio_make_sleep(soon)
                slept_hint = soon
    g.dead = True
    try:
        _scheds.remove(g.sched_gen)
    except ValueError:
        pass


_scheds = []       # live group-scheduler generators (for run()'s in-loop check)


def _start_sched(g):
    g.sched_gen = _group_sched(g)
    _scheds.append(g.sched_gen)
    __jasyncio_create_task(g.sched_gen)


_default_group = [None]


def _default_live_group():
    g = _default_group[0]
    if g is None or g.dead:
        g = _Group()
        _default_group[0] = g
        _start_sched(g)
    return g


def _current_group():
    # The group of the coroutine we are inside; the default group when called
    # from the main frame or from a thread-mode (flat) task.
    if _driving and _driving[-1]._group is not None:
        return _driving[-1]._group
    return _default_live_group()


def _wake_task(t):
    # Make `t` runnable NOW: clear its group park (and its group scheduler's
    # timer), or -- for a flat task -- clear the VM-level sleep on its driver.
    t._park_ms = 0
    g = t._group
    if g is not None and g.sched_gen is not None:
        __jasyncio_wake(g.sched_gen)
    __jasyncio_wake(t._driver)


def _running_inside(t):
    # True only when the caller is executing INSIDE task `t` right now. The
    # naive check (`t is _current_task[0]`) is wrong across pool switches: a
    # task suspended mid-coroutine leaves _current_task set, so the main frame
    # or a thread cancelling that task would be mistaken for a self-cancel.
    # Ask the VM whose task we are actually in and require both to agree.
    if _current_task[0] is not t:
        return False
    cur = __jasyncio_current_coro()
    if cur is None:
        return False               # main frame: never "inside" a task
    if t._group is not None:
        return cur is t._group.sched_gen
    return cur is t._driver


class CancelledError(Exception):
    pass


class TimeoutError(Exception):
    """Raised by wait_for when its deadline passes (asyncio.TimeoutError)."""


class InvalidStateError(Exception):
    pass


class Future:
    # A low-level awaitable whose result is set from outside (by the host or another
    # task). `await future` suspends the awaiting coroutine until the future is
    # resolved via set_result()/set_exception(); the scheduler re-drives the waiter
    # each round until then. Being a pure-Python object, a Future — and any coroutine
    # suspended awaiting one — is captured by VM snapshots like any other value.
    def __init__(self):
        self._done = False
        self._result = None
        self._exc = None

    def set_result(self, value):
        if self._done:
            raise InvalidStateError("result already set")
        self._result = value
        self._done = True

    def set_exception(self, exc):
        if self._done:
            raise InvalidStateError("result already set")
        self._exc = exc
        self._done = True

    def done(self):
        return self._done

    def result(self):
        if not self._done:
            raise InvalidStateError("result is not set")
        if self._exc is not None:
            raise self._exc
        return self._result

    def exception(self):
        if not self._done:
            raise InvalidStateError("result is not set")
        return self._exc

    def __await__(self):
        # A task can only have ONE consumer: asyncio refuses to let a task be gathered
        # and separately awaited at once, because the two would fight over its result
        # and its cancellation.
        if self._waiting_kind == "gather":
            raise RuntimeError("can't wait")
        self._waiting_kind = "wait"
        # Suspend until resolved. Fast path: a couple of bare yields (settles the
        # same step); then park on a short timer so a long wait does not burn the
        # ops budget polling every scheduling round.
        #
        # Cancellation has to reach the INNERMOST task first (asyncio runs the awaited
        # task's except/finally before the awaiter's). We cannot ask "who is awaiting
        # me" -- run()'s top-level coroutine is driven natively and has no Task -- so
        # instead each awaited Task records that it is being awaited, and cancel()
        # walks _blocking_on downwards from the task being cancelled.
        _spin = 0
        while not self._done:
            if _spin < 2:
                _spin += 1
                yield
            else:
                yield __jasyncio_make_sleep(50)
        if self._exc is not None:
            raise self._exc
        return self._result

    __iter__ = __await__


def _spawn(coro):
    """Register a coroutine on the VM's event loop (no Task wrapper)."""
    return __jasyncio_create_task(coro)


_pending_report = []
_live_tasks = []


def _await_task(t):
    """The waiter run() registers as the main frame's blocker (mainWaitingTask).

    Polls with a PLAIN yield every round -- never a timer. run() must return the
    moment its coroutine settles: a sleep-backoff here adds up to a tick of
    latency to every run() (and made fast host drive loops, whose wall clock
    barely advances, wait forever). The poll itself is a handful of ops per
    scheduling round, noise against a tick's budget."""
    while not t.done():
        yield


def _flush_exc_reports():
    """Hand still-unretrieved task exceptions to the loop's exception handler.

    Called from the scheduler's idle points. Deferring by one round matters: a task
    that dies is usually awaited a moment later, and reporting immediately would
    print a spurious "exception wasn't retrieved" for code that does handle it.
    """
    while _pending_report:
        t = _pending_report.pop(0)
        if not t._exc_unretrieved:
            continue
        t._exc_unretrieved = False
        get_event_loop().call_exception_handler({
            "message": "Task exception wasn't retrieved",
            "exception": t.exc,
            "future": t,
        })


def create_task(coro):
    # asyncio/uasyncio hand back a Task, not the raw coroutine: callers expect
    # done()/cancel() on it. The Task's driver is what actually runs on the loop.
    return Task(coro).start()


def sleep_ms(ms=0):
    # Every suspension is a chance to flush pending exception reports; a coroutine
    # that dies is reported at the next scheduling point rather than at exit.
    if _pending_report:
        _flush_exc_reports()
    # ask the scheduler not to resume this task for at least `ms` real milliseconds
    if ms > 0:
        yield __jasyncio_make_sleep(ms)
    else:
        yield


def sleep(s=0, result=None):
    # seconds; delegates to the millisecond timer, then returns `result`
    yield from sleep_ms(int(s * 1000))
    return result


class Task:
    # A pollable handle around a coroutine, and what create_task() hands back --
    # matching asyncio/uasyncio, where the return value is a Task with done()/cancel(),
    # not the bare coroutine. A driver coroutine (_run) advances the wrapped coroutine
    # to completion, capturing its return value or exception, so other coroutines can
    # poll done() / read result / exc without blocking on it. Being pure Python, a Task
    # and its in-flight driver are captured by VM snapshots.
    def __init__(self, coro):
        self.coro = coro
        self._done = False
        self.result = None
        self.exc = None
        self._exc_unretrieved = False
        self._cancel = False        # cancellation requested but not yet delivered
        self._blocking_on = None    # Task this one is currently awaiting
        self._driver = None         # the coroutine registered on the loop
        self._group = None          # the _Group scheduling this task (None = flat)
        self._park_ms = 0           # group mode: remaining sleep in ms (0 = runnable)
        self._flat = False          # True for thread-mode tasks (to_thread)

    def done(self):
        return self._done

    def cancel(self):
        """Request cancellation. Returns True if the task was still running.

        The flag is delivered the next time the task reaches a suspension point --
        cooperative, like everything else here, because there is no way to interrupt
        a coroutine that is mid-statement. A task that has already finished cannot be
        cancelled and returns False, as in asyncio.
        """
        if _running_inside(self):
            # asyncio refuses this: the CancelledError would be delivered at our own
            # suspension point, i.e. never, since we are the one running.
            raise RuntimeError("can't cancel self")
        if self._done:
            return False
        # asyncio delivers cancellation INNERMOST FIRST: a task blocked on another
        # task hands it down, and the inner one runs its except/finally before ours.
        # Flagging the chain here is what produces that order -- reacting only when
        # our own CancelledError arrives would unwind outward first, which is the
        # opposite of what asyncio prints.
        # Collect the chain first, then flag it from the INSIDE OUT. Order matters:
        # every flagged task becomes runnable immediately, and the scheduler serves
        # them round-robin in list order -- so if the outer one were flagged first it
        # would raise its CancelledError before the inner task had even looked at the
        # flag. asyncio runs the innermost except/finally first, so we flag innermost
        # first too.
        chain = []
        inner = self._blocking_on
        seen = 0
        while inner is not None and not inner.done() and seen < 64:
            chain.append(inner)
            inner = inner._blocking_on
            seen += 1
        for i in range(len(chain) - 1, -1, -1):
            chain[i]._cancel = True
            _wake_task(chain[i])
        self._cancel = True
        # A task parked in `await sleep(n)` is held out of the runnable set until its
        # timer expires, so it would not look at the flag for up to n seconds. Clear the
        # sleep so it rejoins the round-robin now and takes the CancelledError promptly.
        _wake_task(self)
        return True

    def cancelled(self):
        return self._done and isinstance(self.exc, CancelledError)

    def _run(self):
        try:
            # Drive the wrapped coroutine one suspension at a time so a cancel()
            # between suspensions can be turned into a CancelledError thrown at the
            # point the coroutine is waiting -- which is where asyncio raises it too.
            if self._cancel:
                # Cancelled before it ever ran: the coroutine never starts, matching
                # asyncio (its body, including any except CancelledError, is skipped).
                self.coro.close()
                raise CancelledError
            send = None
            throwing = False
            while True:
                _prev = _current_task[0]
                _current_task[0] = self          # so current_task() works inside the coro
                _driving.append(self)
                try:
                    if throwing:
                        # Deliver the cancellation INSIDE the coroutine, at the point it
                        # is suspended, so its own except/finally can react -- that is
                        # what asyncio does, and merely raising here in the driver would
                        # skip the coroutine's cleanup entirely.
                        throwing = False
                        got = self.coro.throw(CancelledError())
                    else:
                        got = self.coro.send(send)
                except StopIteration as si:
                    self.result = si.args[0] if si.args else None
                    _current_task[0] = _prev
                    break
                finally:
                    _current_task[0] = _prev
                    if _driving and _driving[-1] is self:
                        _driving.pop()
                send = yield got
                if self._cancel:
                    # Hold off while a task we are awaiting is still settling its own
                    # cancellation: asyncio runs the inner except/finally first, and
                    # throwing here would make ours run before it.
                    inner = self._blocking_on
                    if inner is None:
                        self._cancel = False
                        throwing = True
                    elif inner.done():
                        # The awaited task has settled. If it swallowed its own
                        # CancelledError and finished normally, the cancellation did
                        # not take -- asyncio lets the awaiter carry on rather than
                        # raising in it anyway. Only propagate when the inner task
                        # actually ended up cancelled.
                        self._cancel = False
                        if inner.cancelled():
                            throwing = True
        except BaseException as e:   # capture the exception for the waiter to inspect
            self.exc = e
            # asyncio reports an exception nobody retrieved, so a background task that
            # dies does not vanish silently. Queue it; if an awaiter reads .exc before
            # the queue is flushed, the report is dropped.
            if not isinstance(e, CancelledError):
                self._exc_unretrieved = True
                _pending_report.append(self)
        self._done = True
        # Drop out of the live set: run_forever() returns once nothing is left to run,
        # and a list that only ever grew meant that condition never became true.
        try:
            _live_tasks.remove(self)
        except ValueError:
            pass

    def start(self, flat=False):
        self._driver = self._run()
        _live_tasks.append(self)
        if flat:
            # thread mode: a task of its own in the VM pool, preempted at op-budget
            # boundaries and free to interleave -- thread semantics.
            self._flat = True
            _spawn(self._driver)
            return self
        # coroutine mode: join the calling coroutine's group (or the default
        # group), whose scheduler drives us -- created mid-round, we run within
        # the creator's current round, i.e. before its next suspension, matching
        # asyncio's fresh-task-first behaviour.
        g = _current_group()
        self._group = g
        g.members.append(self)
        if g.sched_gen is not None:
            __jasyncio_wake(g.sched_gen)   # scheduler may be parked on a timer
        return self

    def __await__(self):
        # awaiting a Task suspends until it settles, then returns its result
        # (or re-raises its exception) -- so `await task` works like asyncio.
        #
        # Record the link while parked: cancel() walks it so the INNERMOST awaited
        # task is flagged first, which is the order asyncio runs the except/finally
        # blocks in. _driving's top is the task whose coroutine is running right now
        # (empty for run()'s top-level coroutine, which has no Task of its own).
        awaiter = _driving[-1] if _driving else None
        if awaiter is not None:
            awaiter._blocking_on = self
        try:
            _spin = 0
            while not self._done:
                # If our awaiter has been cancelled we are cancelled too, but the task
                # we are awaiting must settle FIRST (asyncio runs the inner
                # except/finally before the outer one). Staying parked until it does
                # keeps that order; the driver then delivers our own CancelledError.
                if awaiter is not None and awaiter._cancel and not self._done:
                    yield
                    continue
                if _spin < 2:
                    _spin += 1
                    yield
                else:
                    yield __jasyncio_make_sleep(50)
        finally:
            if awaiter is not None and awaiter._blocking_on is self:
                awaiter._blocking_on = None
        if self.exc is not None:
            self._exc_unretrieved = False     # someone is handling it
            raise self.exc
        return self.result

    __iter__ = __await__


_current_task = [None]
_driving = []      # stack of Tasks whose coroutine is being advanced right now


def current_task():
    """The Task whose coroutine is running right now.

    Raises RuntimeError outside a task, as asyncio/uasyncio do -- returning None
    would let a caller silently treat "no task" as a valid task.
    """
    t = _current_task[0]
    if t is None:
        raise RuntimeError("no running event loop")
    return t


def wait_for(aw, timeout=None):
    """Await `aw`, giving up after `timeout` seconds with TimeoutError.

    The awaitable is run as a Task so the timeout can be enforced from outside it;
    on expiry the task is cancelled (cooperatively) before the error is raised, so
    it does not keep running in the background.
    """
    t = ensure_task(aw)
    if timeout is None:
        return (yield from t.__await__())
    import time
    deadline = time.time() + timeout
    while not t.done():
        if time.time() >= deadline:
            # Cancel and then let the task actually settle before reporting: its own
            # except/finally has to run (it may even swallow the CancelledError and
            # finish normally, which asyncio then reports as success, not a timeout).
            t.cancel()
            while not t.done():
                yield
            if t.exc is None:
                return t.result        # the task caught the cancel and completed
            if isinstance(t.exc, CancelledError):
                t._exc_unretrieved = False
                raise TimeoutError
            t._exc_unretrieved = False
            raise t.exc
        yield
    if t.exc is not None:
        t._exc_unretrieved = False
        raise t.exc
    return t.result


def wait_for_ms(aw, timeout):
    return wait_for(aw, timeout / 1000)


def ensure_task(aw):
    # Wrap a coroutine (or an already-scheduled Task) into a pollable, started Task.
    if isinstance(aw, Task):
        return aw
    return Task(aw).start()


def gather(*aws, return_exceptions=False):
    # Schedule every awaitable to run concurrently, then collect results in the
    # ORDER THEY WERE PASSED (not completion order). With return_exceptions=False
    # (default), the first exception raised by any task is re-raised once all have
    # settled; with True, each task's exception object is placed in the result list
    # in that task's position instead of being raised.
    tasks = [ensure_task(aw) for aw in aws]
    # Wait for all of them -- but with return_exceptions=False, bail out as soon as
    # ONE fails. asyncio propagates that first exception immediately and leaves the
    # other tasks running; waiting for every task first (what this used to do) made
    # a still-running sibling look finished by the time the caller inspected it.
    _spin = 0
    while True:
        pending = False
        for t in tasks:
            if t.done():
                if t.exc is not None and not return_exceptions:
                    t._exc_unretrieved = False
                    raise t.exc
            else:
                pending = True
        if not pending:
            break
        if _spin < 2:
            _spin += 1
            yield
        else:
            yield __jasyncio_make_sleep(50)
    results = []
    first_exc = None
    for t in tasks:
        # We are the retriever: gather either returns the exception or re-raises it,
        # so it must not also be reported as "nobody retrieved this".
        t._exc_unretrieved = False
        if t.exc is not None:
            if return_exceptions:
                results.append(t.exc)
            else:
                if first_exc is None:
                    first_exc = t.exc
                results.append(None)
        else:
            results.append(t.result)
    if first_exc is not None and not return_exceptions:
        raise first_exc
    return results


def wait(aws, timeout=None, return_when=ALL_COMPLETED):
    # Concurrently run the given awaitables and return (done, pending) sets of Tasks.
    # return_when controls when to stop waiting:
    #   FIRST_COMPLETED - as soon as any task finishes (success OR exception)
    #   FIRST_EXCEPTION - as soon as any task finishes with an exception, else all done
    #   ALL_COMPLETED   - (default) when every task has finished
    # timeout (seconds) caps the wait via wall-clock; on timeout the still-running
    # tasks are returned in `pending` and keep running (they are not cancelled).
    tasks = [ensure_task(aw) for aw in aws]
    deadline_ms = None
    if timeout is not None:
        deadline_ms = int(timeout * 1000)
    elapsed = 0
    rounds = 0
    while True:
        done_count = 0
        exc_seen = False
        for t in tasks:
            if t.done():
                done_count += 1
                if t.exc is not None:
                    exc_seen = True
        if return_when == FIRST_COMPLETED and done_count > 0:
            break
        if return_when == FIRST_EXCEPTION and exc_seen:
            break
        if done_count == len(tasks):
            break
        if deadline_ms is not None and elapsed >= deadline_ms:
            break
        # yield one scheduling round; account elapsed time for the timeout.
        # Two quick polling rounds, then park ~50ms per round so a long wait
        # does not burn the ops budget.
        if rounds < 2:
            rounds += 1
            step = 1
            yield __jasyncio_make_sleep(step) if deadline_ms is not None else None
        else:
            step = 50
            yield __jasyncio_make_sleep(step)
        elapsed += step
    done = set()
    pending = set()
    for t in tasks:
        if t.done():
            done.add(t)
        else:
            pending.add(t)
    return (done, pending)


def _call_in_task(func, args, kwargs):
    # coroutine shell that runs an ordinary (blocking, non-async) callable as a
    # scheduled task: yield once so the scheduler takes over before the call, then
    # invoke func. The single VM is cooperative, so func runs on the event loop's
    # own execution line — but because it is driven by the scheduler's op budget,
    # it interleaves with other tasks at the VM's instruction-budget boundaries
    # rather than blocking the loop until it returns.
    yield
    return func(*args, **kwargs)


def to_thread(func, *args, **kwargs):
    # Run a blocking, synchronous func as a scheduled task and return an awaitable
    # handle immediately (like asyncio.to_thread returning an awaitable). The caller
    # `await`s the handle to block until func finishes and obtain its return value
    # (or propagate its exception). There is no real OS thread: the single-threaded
    # cooperative VM drives func via the scheduler's op budget, so it interleaves
    # with other tasks instead of running to completion synchronously in place.
    # flat=True: a "thread" is its OWN task in the VM pool -- preemptible at
    # budget boundaries, free to interleave, and not subject to any coroutine
    # group's atomicity. threading.Thread is built on this.
    return Task(_call_in_task(func, args, kwargs)).start(flat=True)


def _block_until(t):
    # Wait for `t` to settle, from OUTSIDE any coroutine group.
    if __jasyncio_in_task():
        # We are inside a pool task (a thread): its group scheduler is a separate
        # pool task and advances on its own, so a cooperative wait is enough. In
        # a game OS time.sleep yields ticks; in a bare VM it degrades to a spin
        # that the op budget still preempts.
        while not t.done():
            time.sleep(0.05)
    else:
        # Main frame: block it via the VM (mainWaitingTask), exactly as before --
        # the waiter generator is what the VM registers and waits on.
        __jasyncio_run(_await_task(t))


def _await_group(g):
    while not g.dead:
        yield


def _block_until_dead(g):
    # Wait for the group's scheduler to exit (all members finished).
    if __jasyncio_in_task():
        while not g.dead:
            time.sleep(0.05)
    else:
        __jasyncio_run(_await_group(g))


def run(coro):
    # Run the top-level coroutine to completion and return its result.
    #
    # Each call creates a PRIVATE group: the coroutine (and every task it
    # create_task()s) is scheduled by that group's own scheduler, which is one
    # task in the VM pool. Atomicity between the group's coroutines is therefore
    # structural, runs are independent, and run() works from a thread too --
    # several runs can coexist, each with its own pool.
    #
    # Calling run() from inside a coroutine is an error, as in asyncio. (From a
    # thread-mode task it is fine -- that is what "threads can open their own
    # event loop" means.) The check asks the VM which pool task WE are running
    # inside: only if it is a group scheduler are we inside a coroutine. A global
    # flag cannot decide this -- another task suspended mid-coroutine leaves its
    # marks set, and a thread must not be mis-flagged by someone else's state.
    _cur = __jasyncio_current_coro()
    if _cur is not None:
        for _s in _scheds:
            if _s is _cur:
                raise RuntimeError("asyncio.run() cannot be called from a running event loop")
    # run() accepts anything awaitable, not just a fresh coroutine: loop
    # .run_until_complete(existing_task) is a normal pattern, and wrapping an
    # already-running Task in another Task would try to .send() into it.
    if isinstance(coro, Task):
        t = coro
        if not t.done():
            _block_until(t)
        if t.exc is not None:
            t._exc_unretrieved = False
            raise t.exc
        return t.result
    g = _Group()
    t = Task(coro)
    t._group = g
    t._driver = t._run()
    # NOT added to _live_tasks: run_forever()'s "nothing left to run" check must
    # not see the run() wrapper itself (same as the old native-driven run()).
    g.members.append(t)
    _start_sched(g)
    _block_until(t)
    # CPython semantics: asyncio.run() closes its loop when the coroutine
    # settles. The group IS the loop -- it dies with the run, so a task the
    # coroutine left pending has nowhere to keep running. Cancel each one (its
    # except/finally runs) and wait for the group to drain before returning.
    leftovers = False
    for m in list(g.members):
        if m is not t and not m._done:
            m.cancel()
            leftovers = True
    if leftovers:
        _block_until_dead(g)
    # The driver stores the outcome on the Task rather than returning it, so unpack
    # it here: run() must yield the coroutine's value, or re-raise its exception.
    if t.exc is not None:
        raise t.exc
    return t.result


_loop_singleton = []


def get_event_loop():
    # asyncio hands back the SAME loop each time; tests rely on that (they stop one
    # loop and check a later call sees the same object).
    if not _loop_singleton:
        _loop_singleton.append(_Loop())
    return _loop_singleton[0]


def set_event_loop(loop):
    del _loop_singleton[:]
    _loop_singleton.append(loop)


def new_event_loop():
    lp = _Loop()
    del _loop_singleton[:]
    _loop_singleton.append(lp)
    return lp


_exc_handler = [None]


def _drive_until_stopped(loop):
    """Yield until the loop is stopped, or nothing is left to run.

    Tracked in Python (_live_tasks) rather than asking the VM: run_forever has to
    return when the last task finishes, and the VM's own task list also holds this
    driver itself, so asking it would never report "empty".
    """
    # Check BEFORE yielding as well as after: stop() called from a task takes effect
    # at that task's next suspension, so by the time control reaches us the flag is
    # already set and we must not hand out another round.
    while True:
        if loop._stopped or not _live_tasks:
            break
        yield
        if loop._stopped:
            break


class _Loop:
    def __init__(self):
        self._stopped = False

    def create_task(self, coro):
        return create_task(coro)

    def run_until_complete(self, aw):
        # Must actually RUN it to completion and hand back the result -- returning a
        # task handle (what this used to do) meant `loop.run_until_complete(main())`
        # returned immediately and the coroutine never ran.
        return run(aw)

    def run_forever(self):
        """Drive the loop until stop() is called (or nothing is left to run).

        Re-entrant across calls: stop() only ends the CURRENT run_forever, and a
        later call picks the tasks up where they were. The tests rely on that --
        they call run_forever() in a loop and expect each stop() to hand control
        back without discarding the pending tasks.
        """
        self._stopped = False
        run(_drive_until_stopped(self))

    def stop(self):
        # Takes effect at the next suspension point, as in asyncio; calling it twice
        # is harmless.
        self._stopped = True

    def close(self):
        pass

    # asyncio/uasyncio expose an exception handler hook on the loop; tests set one
    # and expect get_exception_handler() to hand it back.
    def set_exception_handler(self, handler):
        _exc_handler[0] = handler

    def get_exception_handler(self):
        return _exc_handler[0]

    def default_exception_handler(self, context):
        print(context["message"])

    def call_exception_handler(self, context):
        h = _exc_handler[0]
        if h is None:
            self.default_exception_handler(context)
        else:
            h(self, context)




class Event:
    # Waiters are kept in a FIFO queue rather than each polling the flag. Polling
    # left the wake order up to whatever the scheduler happened to visit first;
    # asyncio wakes waiters in the order they started waiting, and the tests check
    # exactly that ordering.
    def __init__(self):
        self._flag = False
        self._waiters = []

    def set(self):
        self._flag = True
        # Release everyone waiting now, in arrival order. Each waiter still has to be
        # scheduled to notice, but the ticket order fixes who goes first.
        for w in self._waiters:
            w[0] = True
        del self._waiters[:]

    def clear(self):
        self._flag = False

    def is_set(self):
        return self._flag

    def wait(self):
        if self._flag:
            return True
        ticket = [False]
        self._waiters.append(ticket)
        _spin = 0
        while not ticket[0]:
            if _spin < 2:
                _spin += 1
                yield
            else:
                yield __jasyncio_make_sleep(50)
        return True


class Lock:
    def __init__(self):
        self._locked = False
        self._waiters = []

    def locked(self):
        return self._locked

    def acquire(self):
        # FIFO, like Event: polling left the hand-off order to whichever waiter the
        # scheduler happened to reach first, while asyncio grants the lock in the
        # order acquire() was called.
        if not self._locked:
            self._locked = True
            return True
        ticket = [False]
        self._waiters.append(ticket)
        _spin = 0
        while not ticket[0]:
            if _spin < 2:
                _spin += 1
                yield
            else:
                yield __jasyncio_make_sleep(50)
        self._locked = True
        return True

    def release(self):
        self._locked = False
        # hand straight to the longest-waiting acquirer, if any
        while self._waiters:
            t = self._waiters.pop(0)
            t[0] = True
            break

    # `async with lock:` -- asyncio and uasyncio both support this.
    def __aenter__(self):
        return self.acquire()

    def __aexit__(self, exc_type, exc, tb):
        # Must be awaitable. A generator that never yields is the simplest such
        # object, but it has to actually contain a reachable `yield` -- `if False:
        # yield` compiles to a generator whose body the VM will not treat as one,
        # which surfaced as "object is not iterable" at the end of `async with`.
        self.release()
        return
        yield          # unreachable, but it is what makes this a generator


# asyncio/uasyncio expose the loop class itself; tests reference asyncio.Loop.
Loop = _Loop
