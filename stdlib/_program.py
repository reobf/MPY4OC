"""Which program each cooperative task runs for: the main program, an MPYOS shell command or
a background job (`cmd &`), and the wait every blocking call is built on.

A thread takes its creator's program when it is started -- threading.Thread and
micropython.schedule start theirs through adopt() -- so a program's windows, threads and
timers can be told apart from another's. stop() ends every thread of a program at its next
poll point: time.sleep, computer.pullSignal (event.pull, tty.read), a lock, a Tk
mainloop/update or an asyncio event loop's round call check(), which raises SystemExit
there, so try/finally blocks still run (asyncio.run() then cancels its tasks, as it does
when its main coroutine ends). A thread gets that SystemExit once; its cleanup may then
sleep or wait for _GRACE seconds before it comes again (a cleanup that swallowed it, or
never ends, is not left running for good). A thread that only computes and never reaches
a poll point cannot be stopped.

park() is the wait itself: the VM's park/unpark pair, the counterpart of the kernel wait
CPython's sleeps, locks and selector block in. A parked thread holds no seat and spends no
instruction until its deadline, an unpark, or (pullSignal) a queued signal; stop() and a
Ctrl+C wake the parked threads so they reach their poll point at once. Wakes may be early:
every caller re-checks what it waited for.

Everything here is plain Python over the VM's thread identity (__thread_current(), None on
the main frame), so it is saved with the VM like any other state.
"""

_tasks = {}          # pool coroutine -> program; absent means the main program (None)
_main = [None]       # the main frame's program (the shell sets it while a command runs)
_stopping = []       # programs asked to stop
_background = []     # programs running in the background: no keyboard input for them
_waited = []         # programs the shell waits for (their threads leave Ctrl+C to it)
_threads = []        # live threading.Thread objects (threading registers and drops them)
_delivered = {}      # task (None: the main frame) -> [program, when] of its last SystemExit
_on_end = []         # called with a program when it is over (asyncio drops its current loops)
_foreground = []     # VM-owned [program, task, namespace, pending level, yielded] scopes
_end_waiters = []    # threads (None: the main frame) parked until a thread or a program ends
_GRACE = 2


def _here():
    try:
        return __thread_current()
    except NameError:    # a VM without threads
        return None


def _now():
    try:
        return computer.uptime()
    except Exception:    # a VM without a computer: no clock, deliver once
        return None


def current():
    """The program the caller runs for (None: the main program)."""
    task = _here()
    return _main[0] if task is None else _tasks.get(task)


def set_current(program):
    """Make the caller's task (or the main frame) run for program from now on."""
    __program_set(program)


def adopt(coro):
    """Called by the creator of a new pool task, before it runs: it inherits our program."""
    program = current()
    if program is not None:
        _tasks[coro] = program


def forget(task):
    """A thread (a VM task) ended."""
    program = __program_forget(task)
    _delivered.pop(task, None)
    _wake_end_waiters()
    if program in _stopping and not live(program):
        ended(program)


def live(program):
    """How many pool tasks of program have not ended."""
    count = 0
    for owner in _tasks.values():
        if owner == program:
            count += 1
    return count


def threads(program, daemon=None):
    """The live threading.Thread objects started by program (daemon: only those, or not)."""
    found = []
    for thread in _threads:
        if thread._program == program and (daemon is None or thread._daemon == daemon) and thread.is_alive():
            found.append(thread)
    return found


def stop(program):
    """Ask every task of program to end: each raises SystemExit at its next poll point.
    The parked ones are woken so that point comes now."""
    if program is not None and program not in _stopping:
        _stopping.append(program)
        for task, owner in _tasks.items():
            if owner == program:
                __unpark(task)
        if _main[0] == program:
            __unpark(None)


def ended(program):
    """program is over: no longer background, and no longer stopping once its tasks have
    all ended (one still computing keeps the request, and stops at its next poll point)."""
    while program in _background:
        _background.remove(program)
    while program in _waited:
        _waited.remove(program)
    if not live(program):
        while program in _stopping:
            _stopping.remove(program)
    for hook in list(_on_end):
        hook(program)
    _wake_end_waiters()


def stopping(program=None):
    return (current() if program is None else program) in _stopping


def exit_due(task):
    """Whether task (running for the caller's program) should get SystemExit now: its program
    is stopping, and it has not had one in the last _GRACE seconds."""
    program = current()
    if program is None or program not in _stopping:
        return False
    now = _now()
    last = _delivered.get(task)
    if last is not None and last[0] == program and (now is None or last[1] is None or now - last[1] < _GRACE):
        return False
    _delivered[task] = [program, now]
    return True


def check():
    """A poll point for foreground KeyboardInterrupt and program shutdown."""
    __program_interrupt_check()
    if _stopping and exit_due(_here()):
        raise SystemExit


def park(deadline=None, wake_on_signal=False):
    """Block the caller until the host clock (time.ticks_ms()) passes deadline (None: until
    woken), it is unparked, or -- with wake_on_signal -- a signal is queued. A poll point: a
    pending KeyboardInterrupt or a due SystemExit is raised here, before or after the wait.
    While the program is stopping a wait lasts at most _GRACE seconds at a time, so a
    cleanup that keeps waiting still gets its SystemExit again. Wakes may be early: the
    caller re-checks what it waited for."""
    check()
    if _stopping and current() in _stopping:
        cap = __time_ms() + _GRACE * 1000
        if deadline is None or deadline > cap:
            deadline = cap
    __park(deadline, wake_on_signal)
    check()


def unpark(task):
    """Wake task (a thread's generator; None: the main frame) if it is parked, else make its
    next park return at once."""
    __unpark(task)


def wait_end(ready, deadline=None, wake_on_signal=False):
    """park() until a thread or a program ends (or deadline, or a signal): what waiting for
    a program's exit status or for its threads is built on. ready() is the caller's own
    check, made again here once the caller is registered as a waiter, so an end between
    its first look and the park is not missed."""
    me = _here()
    _end_waiters.append(me)
    try:
        if not ready():
            park(deadline, wake_on_signal)
    finally:
        _end_waiters.remove(me)


def _wake_end_waiters():
    for waiter in _end_waiters:
        __unpark(waiter)


def in_background():
    """Whether the caller runs for a background program (keyboard input is not for it)."""
    return bool(_background) and current() in _background


def leaves_interrupt():
    """Whether the caller's program is one the shell waits for: a Ctrl+C is the shell's."""
    return bool(_waited) and current() in _waited
