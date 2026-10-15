"""MPY virtual programs. Shared by subprocess, interpreter launchers and MPYOS shell.

The host installs an OC VFS; no host-machine executables or files are accessed.
Programs have independent identities, cwd, environment and argv, but share a VM.
"""
import os
import sys
import _program

_runtime = None
_OUTPUT_LIMIT = 256


def absolute(path, cwd='/'):
    if not isinstance(path, str):
        raise TypeError('path must be a string')
    if '\x00' in path:
        raise ValueError('embedded null byte')
    path = path.replace('\\', '/')
    parts = []
    for part in (path if path.startswith('/') else cwd + '/' + path).split('/'):
        if part == '..':
            if parts:
                parts.pop()
        elif part and part != '.':
            parts.append(part)
    return '/' + '/'.join(parts)


def command_path(vfs, name, cwd, path, empty_cwd=False):
    name = name.replace('\\', '/')
    if not name or name.endswith('/') or name in ('.', '..'):
        return None
    candidates = [absolute(name, cwd)] if '/' in name else [absolute((d or '.') + '/' + name, cwd) for d in path.split(':') if d or empty_cwd]
    for candidate in candidates:
        if vfs.exists(candidate):
            if not vfs.is_directory(candidate):
                return candidate
            continue
        if not candidate.endswith('.py') and not candidate.endswith('.mpy'):
            for suffix in ('.py', '.mpy'):
                filename = candidate + suffix
                if vfs.exists(filename) and not vfs.is_directory(filename):
                    return filename
    return None


def load_code(prepared):
    """Explicit .mpy files are bytecode; every other filename is Python source."""
    if prepared.get('bytecode', False):
        return __load_mpy(prepared['source'], prepared['filename'])
    return compile(prepared['source'], prepared['filename'], 'exec')


def split(command):
    """The MPYOS shell's small quote grammar, including empty quoted arguments."""
    parts, word, quote, started = [], '', None, False
    for ch in command:
        if quote is not None:
            if ch == quote:
                quote = None
            else:
                word += ch
        elif ch in "\"'":
            quote, started = ch, True
        elif ch in ' \t':
            if started:
                parts.append(word)
                word, started = '', False
        else:
            word += ch
            started = True
    if quote is not None:
        raise ValueError('unclosed quote')
    if started:
        parts.append(word)
    return parts


def _set_cwd(program, path):
    try:
        import osfs
        osfs.set_cwd(program, path)
    except ImportError:
        pass


def _validate_environment(env):
    for key, value in env.items():
        os._env_string(key)
        os._env_string(value)
        if not key or '=' in key or '\x00' in key or '\x00' in value:
            raise ValueError('invalid environment variable')


def _drop_callbacks(program):
    try:
        import event
        event.drop(program)
    except ImportError:
        pass


def release(program):
    try:
        return computer._tk_release_owned(program)
    except (NameError, AttributeError):
        return 0


class _NullTty:
    def write(self, text):
        print(text, end='')
    def print(self, *parts):
        print(*parts)
    def read(self, *args):
        raise OSError('no terminal attached')


class _JobTty:
    def __init__(self, runtime, job):
        self._shell, self._job = runtime, job
    def write(self, text):
        runtime = self._shell
        runtime._job_output.append(str(text))
        if len(runtime._job_output) > _OUTPUT_LIMIT:
            try:
                runtime._job_output.pop(0)
                runtime._job_dropped += 1
            except IndexError:
                pass  # the shell drained the buffer between VM instruction slices
    def print(self, *parts, **kw):
        self.write(kw.get('sep', ' ').join(str(p) for p in parts) + kw.get('end', '\n'))
    def write_color(self, text, rgb=None):
        self.write(text)
    def print_color(self, text, rgb=None, end='\n'):
        self.write(str(text) + end)
    def size(self):
        return self._shell.tty.size()
    def read(self, *args):
        raise OSError('a background job cannot read the terminal')
    def _screen(self, *args):
        raise OSError('a background job cannot draw on the terminal')
    clear = set_cursor = get_cursor = draw_cursor_cell = _screen
    @property
    def gpu(self):
        self._screen()


class Runtime:
    def __init__(self, vfs, tty=None):
        import threading
        self.fs, self.tty = vfs, tty or _NullTty()
        self._lock = threading.Lock()
        self.jobs, self.active = [], {}
        self.children = {}  # os.spawn children: live jobs, then small waitable exit records
        self._next_job = self._next_command = self._next_pid = 0
        self._job_output, self._job_dropped = [], 0
        self.context = None  # optional compatibility adapter; never used to execute a program
        _program._on_end.append(self.reap)

    def reap(self, program):
        if _program.live(program):
            return
        with self._lock:
            for pid, job in list(self.active.items()):
                if job['program'] == program and job['done']:
                    release(program)
                    _drop_callbacks(program)
                    # The job leaves active and jobs in one go: a waiter that sees it gone
                    # from active (a cooperative switch could fall between) finds no job.
                    irq = __machine_disable_irq()
                    try:
                        self.active.pop(pid, None)
                        if not job['announce']:
                            try:
                                self.jobs.remove(job)
                            except ValueError:
                                pass  # a concurrent shell reap already removed it
                    finally:
                        __machine_enable_irq(irq)
                    if pid in self.children:
                        # Do not keep a finished Thread, frames or GUI state alive just
                        # because its parent has not collected the exit status yet.
                        code = job['returncode']
                        status = -code if code < 0 else (code & 255) << 8
                        self.children[pid] = {'ppid': job['ppid'], 'status': status}
                    # Orphans keep running, but no dead parent can wait for them.
                    # PID 0 is the shell/root context; orphan exits are auto-reaped.
                    for child_pid, child in list(self.children.items()):
                        if child['ppid'] == pid:
                            self.children.pop(child_pid, None)
                    for child in list(self.active.values()):
                        if child['ppid'] == pid:
                            child['ppid'] = 0
                    __program_drop_ops(program)

    def pid(self, program=None):
        program = _program.current() if program is None else program
        for pid, job in list(self.active.items()):
            if job['program'] == program:
                return pid
        return 0

    def snapshot(self):
        """Detached process/ops data for diagnostics; no environment or live job objects."""
        with self._lock:
            counts = {}
            owners = dict(_program._tasks)
            for thread in list(_program._threads):
                if thread.is_alive():
                    owner = owners.get(thread._gen)
                    counts[owner] = counts.get(owner, 0) + 1
            main = _program._main[0]
            counts[main] = counts.get(main, 0) + 1
            raw = __program_ops()
            rows = [{'pid': 0, 'ppid': 0, 'command': 'Shell / system', 'state': 'Active',
                     'background': False, 'threads': counts.get(None, 0), 'ops': raw['programs'].get(None, 0)}]
            for job in list(self.active.values()):
                program = job['program']
                state = 'Finishing' if job['done'] else 'Stopping' if _program.stopping(program) else 'Active'
                rows.append({'pid': job['pid'], 'ppid': job['ppid'], 'command': job['line'], 'state': state,
                             'background': job['background'], 'threads': counts.get(program, 0),
                             'ops': raw['programs'].get(program, 0)})
            return {'total_ops': raw['total'], 'retired_ops': raw['retired'], 'processes': rows}

    def prepare(self, args, cwd=None, env=None, file=False):
        if isinstance(args, str):
            args = [args]
        if not isinstance(args, (list, tuple)) or not args:
            raise ValueError('args must be a nonempty sequence')
        args = list(args)
        for value in args:
            if not isinstance(value, str):
                raise TypeError('arguments must be strings')
            if '\x00' in value:
                raise ValueError('embedded null byte')
        here = os.getcwd() if hasattr(os, 'getcwd') else '/'
        cwd = absolute(here if cwd is None else cwd, here)
        if not self.fs.is_directory(cwd):
            raise OSError('not a directory: ' + cwd)
        env = os.environ.copy() if env is None else dict(env)
        _validate_environment(env)
        path = absolute(args[0], cwd) if file else command_path(self.fs, args[0], cwd, env.get('PATH', ''))
        if path is None or not self.fs.exists(path) or self.fs.is_directory(path):
            raise FileNotFoundError(args[0])
        bytecode = path.endswith('.mpy')
        source = self.fs.read_bytes(path) if bytecode else self.fs.read_file(path)
        if source is None:
            raise FileNotFoundError(path)
        first = '' if bytecode else source.split('\n', 1)[0].strip()
        windowed = False
        if not file and first in ('# MPYOS interpreter: python', '# MPYOS interpreter: pythonw'):
            windowed = first.endswith('pythonw')
            if len(args) > 1:
                target = self.prepare(args[1:], cwd, env, True)
                target['windowed'] = windowed
                return target
            if windowed:
                raise ValueError('usage: pythonw file [args...]')
        elif not file and first.startswith('#!'):
            words = first[2:].strip().split()
            # A deliberately small interpreter directive; explicit python ignores it.
            windowed = words in (['/usr/bin/env', 'pythonw'], ['/bin/pythonw'], ['/bin/pythonw.py'])
        return {'filename': path, 'source': source, 'bytecode': bytecode, 'args': [path] + args[1:], 'cwd': cwd, 'env': env, 'windowed': windowed}

    def create(self, prepared, asynchronous, background, announce=False, waitable=False):
        # VM tasks can switch between bytecodes, including during += and dictionary
        # construction. Allocate the identity and its context as one locked operation.
        with self._lock:
            return self._create(prepared, asynchronous, background, announce, waitable)

    def _create(self, prepared, asynchronous, background, announce, waitable):
        background = background or _program.in_background()
        self._next_pid += 1
        if asynchronous:
            self._next_job += 1
            number, program = self._next_job, 'job:' + str(self._next_job)
        else:
            self._next_command += 1
            number, program = 0, 'cmd:' + str(self._next_command)
        job = {'id': number, 'pid': self._next_pid, 'ppid': self.pid(), 'program': program,
               'line': ' '.join(prepared['args']), 'thread': None, 'done': False, 'error': None,
               'returncode': None, 'background': background, 'announce': announce}
        self.active[job['pid']] = job
        if waitable:
            self.children[job['pid']] = job
        os._inherit_environment(program, prepared['env'])
        _set_cwd(program, prepared['cwd'])
        sys._contexts[program] = {'argv': list(prepared['args'])}
        if background:
            _program._background.append(program)
        return job

    def execute(self, job, prepared, tty):
        program, parent = job['program'], _program.current()
        _program.set_current(program)
        namespace = {'args': list(prepared['args'][1:]), 'argv': sys.argv,
                     '__file__': prepared['filename'], '__name__': '__main__', 'tty': tty}
        if self.context is not None:
            namespace['sh'] = self.context(job, prepared['cwd'], tty)
        code = 0
        foreground = None
        try:
            if not job['background']:
                foreground = __program_foreground(namespace)
            _program.check()
            exec_sandbox(load_code(prepared), namespace)
        except SystemExit as error:
            value = error.args[0] if error.args else 0
            code = 0 if value is None else int(value) if isinstance(value, int) else 1
            if value is not None and not isinstance(value, int):
                job['error'] = str(value)
        except KeyboardInterrupt:
            code, job['error'] = 130, 'KeyboardInterrupt'
            _program.stop(program)
        except BaseException as error:
            code, job['error'] = 1, str(error)
        finally:
            if foreground is not None:
                __program_leave_foreground(foreground)
            if job['error'] is not None and not job['announce'] and job['thread'] is not None:
                tty.print(prepared['filename'] + ': ' + job['error'])
            _program.set_current(parent)
            self.end_program(program, not job['background'], tty)
            job['returncode'] = job.get('signal', code & 255 if prepared.get('spawn_api') else code)
            job['done'] = True
            _program.ended(program)

    def foreground(self, prepared, tty=None):
        job = self.create(prepared, False, False)
        self.execute(job, prepared, tty or self.tty)
        return job

    def spawn(self, prepared, background=True, announce=False, line=None, waitable=False):
        import threading
        job = self.create(prepared, True, background, announce, waitable)
        if line is not None:
            job['line'] = line
        tty = _JobTty(self, job) if background else self.tty
        thread = threading.Thread(target=lambda: self.execute(job, prepared, tty), name=job['line'], daemon=True)
        thread._program_main = True
        job['thread'] = thread
        self.jobs.append(job)
        # Bind the runner to the new program before scheduling: it is never a thread
        # owned by the launcher, even if that launcher exits immediately afterwards.
        parent = _program.current()
        try:
            _program.set_current(job['program'])
            thread.start()
        finally:
            _program.set_current(parent)
        return job

    def waitpid(self, pid, options):
        import time
        if not isinstance(pid, int) or not isinstance(options, int):
            raise TypeError('pid and options must be integers')
        if pid == 0 or pid < -1:
            raise NotImplementedError('process groups are not supported')
        if options not in (0, os.WNOHANG):
            raise ValueError('waitpid supports only 0 or WNOHANG')
        parent = self.pid()

        def collected():
            for child_pid, child in list(self.children.items()):
                if child['ppid'] == parent and (pid == -1 or pid == child_pid) and 'status' in child:
                    return True
            return False

        while True:
            _program.check()
            with self._lock:
                found = False
                for child_pid, child in list(self.children.items()):
                    if child['ppid'] == parent and (pid == -1 or pid == child_pid):
                        found = True
                        if 'status' in child:
                            self.children.pop(child_pid)
                            return child_pid, child['status']
                if not found:
                    raise ChildProcessError('no unwaited os.spawn child')
                if options == os.WNOHANG:
                    return 0, 0
            _program.wait_end(collected)    # woken when a program ends

    def kill(self, pid, signal):
        if not isinstance(pid, int) or not isinstance(signal, int):
            raise TypeError('pid and signal must be integers')
        if pid <= 0:
            raise NotImplementedError('kill requires a positive virtual PID; process groups are not supported')
        if signal not in (0, 9, 15):
            raise ValueError('supported signals: 0 (probe), 9, 15')
        with self._lock:
            job = self.active.get(pid)
            if job is None:
                if pid in self.children:
                    return  # a finished, uncollected child exists but cannot be stopped again
                raise ProcessLookupError('no such virtual process: ' + str(pid))
            if signal:
                self.terminate(job, signal)

    def ctrl_c(self, tty=None):
        try:
            queue = computer._signals
        except (NameError, AttributeError):
            return False
        for signal in list(queue):
            if len(signal) > 2 and signal[0] == 'key_down' and signal[2] == 3:
                try:
                    queue.remove(signal)
                except ValueError:
                    continue
                (tty or self.tty).print('^C')
                return True
        return False

    def end_program(self, program, interactive, tty=None):
        if _program.threads(program, False):
            if interactive:
                (tty or self.tty).print('(waiting for ' + str(len(_program.threads(program, False))) + ' thread(s); Ctrl+C stops them)')
            _program._waited.append(program)
            while _program.threads(program, False):
                if _program.stopping(program) or interactive and self.ctrl_c(tty):
                    break
                # Woken when a thread ends (or, interactive, a key comes).
                _program.wait_end(lambda: not _program.threads(program, False) or _program.stopping(program), None, interactive)
        _program.stop(program)
        release(program)
        _drop_callbacks(program)

    def terminate(self, job, signal=15):
        if not job['done']:
            job['signal'] = -signal
            _program.stop(job['program'])
            release(job['program'])


def install(vfs, tty=None):
    global _runtime
    if _runtime is None or _runtime.fs is not vfs:
        if _runtime is not None and _runtime.active:
            raise RuntimeError('cannot replace the filesystem while programs are running')
        if _runtime is not None and _runtime.reap in _program._on_end:
            _program._on_end.remove(_runtime.reap)
        _runtime = Runtime(vfs, tty)
    elif tty is not None:
        _runtime.tty = tty
    return _runtime


def runtime():
    if _runtime is None:
        raise OSError('program runtime not installed (MPYOS not booted)')
    return _runtime


def snapshot():
    """MPYOS diagnostics extension: registered programs, threads and cumulative VM ops."""
    return runtime().snapshot()


class ProcessMonitor:
    """Sample cumulative counters without resetting them (multiple viewers are safe)."""
    def __init__(self):
        self.previous = None
        self.when = None

    def sample(self, now):
        current = snapshot()
        elapsed = 0 if self.when is None else now - self.when
        valid = self.previous is not None and elapsed > 0 and current['total_ops'] >= self.previous['total_ops']
        total = current['total_ops'] - self.previous['total_ops'] if valid else 0
        old = {p['pid']: p['ops'] for p in self.previous['processes']} if valid else {}
        # Keep only detached cumulative data; no references to runtime jobs/threads.
        self.previous, self.when = current, now
        rows = []
        for entry in current['processes']:
            row = entry.copy()
            used = max(0, row['ops'] - old.get(row['pid'], 0)) if valid else 0
            row['ops_per_second'] = used / elapsed if valid else None
            row['share'] = 100 * used / total if valid and total else 0 if valid else None
            rows.append(row)
        return {'total_ops': current['total_ops'], 'ops_per_second': total / elapsed if valid else None, 'processes': rows}


def spawnv(mode, path, args, env=None, search=False, explicit_env=False):
    """os.spawn family: exact paths, or MPYOS PATH lookup with .py/.mpy shorthand.

    Like Unix spawn, an unavailable executable exits 127 and normal exit codes
    use the low eight bits. Invalid API arguments are rejected before launch.
    """
    if not isinstance(mode, int):
        raise TypeError('mode must be an integer')
    if mode not in (os.P_WAIT, os.P_NOWAIT):
        raise ValueError('spawn supports only P_WAIT and P_NOWAIT/P_NOWAITO')
    if not isinstance(args, (list, tuple)):
        raise TypeError('argv must be a tuple or a list')
    if not args or not args[0]:
        raise ValueError('argv first element cannot be empty')
    args = list(args)
    for value in [path] + args:
        if not isinstance(value, str):
            raise TypeError('path and arguments must be strings')
        if '\x00' in value:
            raise ValueError('embedded null byte')
    if explicit_env and not hasattr(env, 'keys'):
        raise TypeError('env must be a mapping')
    env = dict(env) if explicit_env else os.environ.copy()
    _validate_environment(env)
    engine = runtime()
    cwd = os.getcwd() if hasattr(os, 'getcwd') else '/'
    # Capture argv/cwd/env before the child is scheduled. argv[0] is a name the
    # caller supplied, independent of the file chosen for execution.
    prepared = {'filename': path, 'args': args, 'cwd': cwd, 'env': env,
                'source': 'raise SystemExit(127)', 'windowed': False}
    try:
        filename = None
        if path and not path.replace('\\', '/').endswith('/'):
            filename = command_path(engine.fs, path, cwd, env.get('PATH', os.defpath), True) if search else absolute(path, cwd)
        if filename is not None and engine.fs.exists(filename) and not engine.fs.is_directory(filename):
            prepared = engine.prepare([filename] + args[1:], cwd, env)
            if prepared['filename'] == filename:
                prepared['args'] = args
    except OSError:
        pass  # launch still produces a waitable child, with exit status 127
    prepared['spawn_api'] = True
    job = engine.spawn(prepared, background=mode == os.P_NOWAIT or prepared['windowed'], waitable=True)
    if mode == os.P_NOWAIT:
        return job['pid']
    return os.waitstatus_to_exitcode(engine.waitpid(job['pid'], 0)[1])


def python(args, background=False):
    engine = runtime()
    prepared = engine.prepare(args, file=True)
    if background:
        return engine.spawn(prepared, True, True)['pid']
    # Interpreter already owns a program; execute this file in that same program.
    sys.argv = list(prepared['args'])
    namespace = {'argv': sys.argv, 'args': sys.argv[1:], '__file__': prepared['filename'], '__name__': '__main__'}
    foreground = None
    try:
        if not _program.in_background():
            foreground = __program_foreground(namespace)
        exec_sandbox(load_code(prepared), namespace)
    finally:
        if foreground is not None:
            __program_leave_foreground(foreground)
