"""event.py -- the event loop for mpyos, built on computer.pullSignal.

OpenComputers delivers everything -- key presses, component hotplug, timers, a
signal another program pushed -- as signals. This module turns that single stream
into a small dispatcher: register callbacks with listen(), wait for one specific
signal with pull(), or run a blocking loop with the shell.

It corresponds to OpenOS's event.lua, kept deliberately small.

Listeners and timers belong to the program that registered them (see the
stdlib's _program): a callback runs as that program, whoever happens to be
pulling events, and the shell drops a program's callbacks when it ends. A
background program's listeners do not see the keyboard, as it cannot read it.
"""

try:
    import _program
except ImportError:
    _program = None

# name -> list of callbacks. A callback returns False to unregister itself.
_listeners = {}
_owners = {}        # name -> list of the programs that registered _listeners[name]

# timers: list of [deadline, interval_or_None, callback, times, program]
_timers = []

_KEYBOARD = ("key_down", "key_up", "clipboard")


def _current():
    return _program.current() if _program is not None else None


def _run_as(program, callback, args):
    # Run a callback as the program that registered it (its threads and windows are its).
    if _program is None:
        return callback(*args)
    previous = _program.current()
    if previous == program:
        return callback(*args)
    _program.set_current(program)
    try:
        return callback(*args)
    finally:
        _program.set_current(previous)

# next timer id
_next_timer = [1]
_timer_ids = {}


def listen(name, callback):
    """Call callback(name, *args) whenever a signal `name` arrives. Returns True
    if newly registered. A callback that returns False is removed."""
    lst = _listeners.setdefault(name, [])
    if callback in lst:
        return False
    lst.append(callback)
    _owners.setdefault(name, []).append(_current())
    return True


def ignore(name, callback):
    """Stop calling callback for signal `name`."""
    lst = _listeners.get(name)
    if lst and callback in lst:
        owners = _owners.get(name, [])
        i = lst.index(callback)
        lst.pop(i)
        if i < len(owners):
            owners.pop(i)
        if not lst:
            del _listeners[name]
            _owners.pop(name, None)
        return True
    return False


def drop(program):
    """Forget every listener and timer of program (the shell: it has ended)."""
    for name in list(_listeners):
        owners = _owners.get(name, [])
        for i in range(len(owners) - 1, -1, -1):
            if owners[i] == program:
                ignore(name, _listeners[name][i])
    for tid in list(_timer_ids):
        entry = _timer_ids[tid]
        if entry[4] == program:
            cancel(tid)


def timer(interval, callback, times=1):
    """Call callback() after `interval` seconds. times>1 repeats; times=0 or a
    huge number repeats indefinitely. Returns an id usable with cancel()."""
    tid = _next_timer[0]
    _next_timer[0] += 1
    deadline = computer.uptime() + interval
    entry = [deadline, interval, callback, times, _current()]
    _timers.append(entry)
    _timer_ids[tid] = entry
    return tid


def cancel(tid):
    entry = _timer_ids.pop(tid, None)
    if entry is not None and entry in _timers:
        _timers.remove(entry)
        return True
    return False


def _run_due_timers():
    now = computer.uptime()
    # iterate over a copy: callbacks may add/cancel timers
    for entry in list(_timers):
        if now >= entry[0]:
            deadline, interval, callback, times, program = entry
            if _stopped(program):
                continue
            try:
                _run_as(program, callback, ())
            except SystemExit:
                times = 1             # it exited, or its program was stopped: no more runs
            except Exception as e:
                _report(e)
            if times is not None and times > 0:
                times -= 1
                entry[3] = times
                if times == 0:
                    for tid in list(_timer_ids):
                        if _timer_ids[tid] is entry:
                            del _timer_ids[tid]
                    if entry in _timers:
                        _timers.remove(entry)
                    continue
            entry[0] = now + interval     # times 0 (or None): it repeats until cancel()


def _dispatch(signal):
    """Deliver one signal to its listeners. signal is (name, *args) or None."""
    if signal is None:
        return
    name = signal[0]
    args = signal[1:]
    lst = _listeners.get(name)
    if not lst:
        return
    owners = _owners.get(name, [])
    # Pair them up first: a callback may ignore() another one meanwhile.
    pairs = [(lst[i], owners[i] if i < len(owners) else None) for i in range(len(lst))]
    for cb, program in pairs:
        if _stopped(program) or (name in _KEYBOARD and program is not None and program in _program._background):
            continue
        try:
            keep = _run_as(program, cb, (name,) + tuple(args))
        except SystemExit:
            keep = False      # it exited, or its program was stopped while it waited
        except Exception as e:
            _report(e)
            keep = True
        if keep is False:
            ignore(name, cb)


def _stopped(program):
    return program is not None and _program is not None and program in _program._stopping


def pull(timeout=None, name=None):
    """Wait for the next signal, optionally only one matching `name`. Returns the
    signal tuple (name, *args), or None on timeout. Timers and other listeners
    still fire while waiting."""
    deadline = None if timeout is None else computer.uptime() + timeout
    while True:
        # Figure out how long pullSignal may block: until the next timer, or the
        # caller's deadline, whichever is sooner, so timers stay roughly on time.
        budget = _time_to_next_timer()
        if deadline is not None:
            remaining = deadline - computer.uptime()
            if remaining <= 0:
                return None
            budget = remaining if budget is None else min(budget, remaining)

        sig = computer.pullSignal(budget)
        _run_due_timers()
        if sig is None:
            if deadline is not None and computer.uptime() >= deadline:
                return None
            continue

        # Always let registered listeners see it.
        _dispatch(sig)
        if name is None or sig[0] == name:
            return sig


def push(name, *args):
    """Queue a signal, as if hardware produced it."""
    return computer.pushSignal(name, *args)


def _time_to_next_timer():
    if not _timers:
        return None
    now = computer.uptime()
    soonest = None
    for entry in _timers:
        dt = entry[0] - now
        if dt < 0:
            dt = 0
        if soonest is None or dt < soonest:
            soonest = dt
    return soonest


def _report(exc):
    # Errors in a callback must not kill the event loop.
    try:
        print("event: callback error: " + str(exc))
    except Exception:
        pass
