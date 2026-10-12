"""Which program each cooperative task runs for: the main program, an MPYOS shell command or
a background job (`cmd &`).

A thread takes its creator's program when it is started -- threading.Thread and
micropython.schedule start theirs through adopt() -- so a program's windows, threads and
timers can be told apart from another's. stop() ends every thread of a program at its next
poll point: time.sleep, computer.pullSignal (event.pull, tty.read), a Tk mainloop/update or
an asyncio event loop's round call check(), which raises SystemExit there, so try/finally
blocks still run (asyncio.run() then cancels its tasks, as it does when its main coroutine
ends). A thread gets that SystemExit once; its cleanup may then sleep or wait for _GRACE
seconds before it comes again (a cleanup that swallowed it, or never ends, is not left
running for good). A thread that only computes and never reaches a poll point cannot be
stopped.

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
    task = _here()
    if task is None:
        _main[0] = program
    elif program is None:
        _tasks.pop(task, None)
    else:
        _tasks[task] = program


def adopt(coro):
    """Called by the creator of a new pool task, before it runs: it inherits our program."""
    program = current()
    if program is not None:
        _tasks[coro] = program


def forget(task):
    """A thread (a VM task) ended."""
    _tasks.pop(task, None)
    _delivered.pop(task, None)


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
    """Ask every task of program to end: each raises SystemExit at its next poll point."""
    if program is not None and program not in _stopping:
        _stopping.append(program)


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
    """A poll point: SystemExit if the caller's program was asked to stop."""
    if _stopping and exit_due(_here()):
        raise SystemExit


def in_background():
    """Whether the caller runs for a background program (keyboard input is not for it)."""
    return bool(_background) and current() in _background


def leaves_interrupt():
    """Whether the caller's program is one the shell waits for: a Ctrl+C is the shell's."""
    return bool(_waited) and current() in _waited
