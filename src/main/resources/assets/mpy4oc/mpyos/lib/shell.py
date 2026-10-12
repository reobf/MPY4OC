"""shell.py -- the mpyos command shell.

Reads a line, searches the OpenOS-style command path, and runs a Python file
with a small context (args, tty, sh, __file__). Explicit paths use the caller's cwd. The
shell keeps the working directory and offers the few things that must be
built in because they mutate shell state (cd), end the session (exit/reboot) or
manage background jobs (jobs/kill). A line ending in & runs its command in the
background, in a thread of this VM: the prompt comes back at once, and a Tk
program keeps its windows until it ends.

Every command runs as a program of its own (see the stdlib's _program): the threads,
coroutines, Tk windows, event listeners and os working directory it has belong to it.
When a command ends, the shell does what a process exit does: it waits for the
command's non-daemon threads (Ctrl+C stops them), then stops whatever else it left
running, closes its windows and drops its listeners. kill stops a background job the
same way.

init.py calls run(tty, boot_fs).
"""

import fs
import os
import _program

_BUILTINS = ("cd", "exit", "shutdown", "reboot", "help", "jobs", "kill")
_DEFAULT_PATH = "/bin:/usr/bin:/home/bin:."  # OpenOS boot/02_os.lua
_OUTPUT_LIMIT = 256     # chunks of background output kept for the next prompt


def _set_cwd(program, path):
    # os.getcwd() of program (None: the shell's own); see osfs.
    try:
        import osfs
        osfs.set_cwd(program, path)
    except Exception:
        pass            # a stripped image without osfs: os keeps no directory


def _drop_cwd(program):
    try:
        import osfs
        osfs.drop_cwd(program)
    except Exception:
        pass


def _drop_callbacks(program):
    # The event listeners and timers program registered (see event).
    try:
        import event
        event.drop(program)
    except Exception:
        pass


class _JobTty:
    """The terminal as a background job sees it. Its output is kept and shown before the
    next prompt (written straight away it would land on the line being typed); it cannot
    read the keyboard or draw on the screen -- the shell in front owns both."""

    def __init__(self, shell, job):
        self._shell = shell
        self._job = job

    def write(self, text):
        shell = self._shell
        shell._job_output.append(str(text))
        if len(shell._job_output) > _OUTPUT_LIMIT:
            # A chatty job with nobody at the prompt keeps only its latest output.
            try:
                shell._job_output.pop(0)
                shell._job_dropped += 1
            except IndexError:
                pass

    def print(self, *parts, **kw):
        self.write(kw.get("sep", " ").join([str(p) for p in parts]) + kw.get("end", "\n"))

    def write_color(self, text, rgb=None):
        self.write(text)

    def print_color(self, text, rgb=None, end="\n"):
        self.write(str(text) + end)

    def size(self):
        return self._shell.tty.size()

    def read(self, *args):
        raise OSError("a background job cannot read the terminal")

    def _screen(self, *args):
        raise OSError("a background job cannot draw on the terminal")

    clear = set_cursor = get_cursor = draw_cursor_cell = _screen

    @property
    def gpu(self):
        self._screen()


class _ShellEnvironment:
    # Compatibility with the original sh.path API: PATH has a single source of truth.
    @property
    def path(self):
        return self._env.get('PATH', '')

    @path.setter
    def path(self, value):
        self._env['PATH'] = value


class _ProgramShell(_ShellEnvironment):
    """A program's shell view: its own cwd and search path, plus the shared filesystem.
    os.chdir and sh.run_command('cd', ...) change this program, not the interactive shell."""
    background = False

    def __init__(self, shell, program, cwd, tty):
        self._shell = shell
        self._program = program
        self.fs = shell.fs
        self.g = shell.g
        self._cwd = cwd
        self._env = os._Environ(program, False)
        self.history = shell.history
        self.tty = tty

    @property
    def cwd(self):
        try:
            import osfs
            return osfs._cwds.get(self._program, self._cwd)
        except ImportError:
            return self._cwd

    def resolve(self, path):
        if not path:
            return self.cwd
        if path.startswith("/"):
            return fs.normalize(path)
        return fs.join(self.cwd, path)

    def find_command(self, name):
        return self._shell.find_command(name, self.cwd, self.path)

    def command_path(self, name):
        return self._shell.command_path(name, self.cwd, self.path)

    def run_command(self, name, args):
        if self._shell._builtin(name, args, self, self.tty):
            return
        return self._shell._run_nested(name, args, self, self.tty)

    def spawn(self, name, args, cwd=None):
        cwd = self.cwd if cwd is None else self.resolve(cwd)
        return self._shell.spawn(name, args, cwd, self.tty)

    def _cd(self, args):
        target = self.resolve(args[0]) if args else "/"
        if not self.fs.exists(target) or not self.fs.is_directory(target):
            self.tty.print("cd: no such directory: " + target)
            return
        self._cwd = target
        _set_cwd(self._program, target)


class _JobShell(_ProgramShell):
    background = True

    def __init__(self, shell, job, cwd):
        _ProgramShell.__init__(self, shell, job["program"], cwd, _JobTty(shell, job))


class Shell(_ShellEnvironment):
    background = False   # commands run here hold the prompt until they end

    def __init__(self, tty, boot_fs_addr, main_globals=None):
        self.tty = tty
        self.boot_addr = boot_fs_addr
        # Reuse the Vfs installed into `os` at boot (single mount state); build
        # our own only on a stripped image where osfs was not installed.
        try:
            import osfs
            self.fs = osfs.shared_vfs() or fs.Vfs(boot_fs_addr)
        except Exception:
            self.fs = fs.Vfs(boot_fs_addr)
        self.cwd = "/"
        self._env = os._Environ(_program.current(), False)
        self._env.setdefault('PATH', _DEFAULT_PATH)
        self.history = []
        self.running = True
        self.jobs = []          # background commands: {id, line, program, thread, done, error}
        self._next_job = 0
        self._next_command = 0
        self._job_output = []   # what background jobs printed, shown before the next prompt
        self._job_dropped = 0   # ... and how much of it did not fit (_OUTPUT_LIMIT)
        self._exit_warned = False
        # Commands' inner functions resolve globals against the MAIN module (a VM
        # scoping rule -- see run_command), so the context must be planted there.
        # init.py passes its globals() in; they are the main namespace.
        self.g = main_globals if main_globals is not None else globals()

    # -- paths --
    def resolve(self, path):
        """Absolute path stays; relative resolves against the working directory."""
        if not path:
            return self.cwd
        if path.startswith("/"):
            return fs.normalize(path)
        return fs.join(self.cwd, path)

    # -- command lookup --
    def command_path(self, name, cwd=None, path=None):
        """Resolve a command like OpenOS: explicit paths use cwd; bare names use path.
        Try the exact filename first, then .py; an existing directory is not executable."""
        name = name.replace("\\", "/")
        if not name or name.endswith("/") or name in (".", ".."):
            return None
        cwd = self.cwd if cwd is None else cwd
        path = self.path if path is None else path
        if "/" in name:
            candidates = [fs.normalize(name) if name.startswith("/") else fs.join(cwd, name)]
        else:
            candidates = []
            for directory in path.split(":"):
                if directory:
                    base = directory if directory.startswith("/") else fs.join(cwd, directory)
                    candidates.append(fs.join(base, name))
        for candidate in candidates:
            if not self.fs.exists(candidate) and not candidate.endswith(".py"):
                candidate += ".py"
            if self.fs.exists(candidate) and not self.fs.is_directory(candidate):
                return candidate
        return None

    def find_command(self, name, cwd=None, path=None):
        """The source of a resolved command, or None (kept for callers reading commands)."""
        filename = self.command_path(name, cwd, path)
        return None if filename is None else self.fs.read_file(filename)

    def _complete(self, text, pos):
        start, end, partial, words = _completion_token(text, pos)
        if not words and pos < len(text) and start == pos and text[pos] in " \t":
            return []
        try:
            self.fs.refresh()    # also discover disks inserted while waiting at the prompt
        except Exception:
            pass
        matches = []              # (unquoted word, is_directory, display_name)
        if not words and "/" not in partial and "\\" not in partial and partial not in (".", ".."):
            names = list(_BUILTINS)
            for directory in self.path.split(":"):
                if not directory:
                    continue
                try:
                    for entry in self.fs.list(self.resolve(directory)):
                        # Bare .py commands omit the extension; extensionless scripts keep it.
                        if entry.endswith("/") or (entry.startswith(".") and not partial.startswith(".")):
                            continue
                        name = entry[:-3] if entry.endswith(".py") else entry
                        if name not in names:
                            names.append(name)
                except Exception:
                    pass    # a missing search directory does not hide the other entries
            matches = [(name, False, name) for name in sorted(names) if name.startswith(partial)]
        else:
            # Match only the basename; preserve the user's ./, ../ or absolute prefix.
            partial = partial.replace("\\", "/")
            slash = partial.rfind("/")
            directory, basename = partial[:slash + 1], partial[slash + 1:]
            try:
                entries = list(self.fs.list(self.resolve(directory or ".")))
            except Exception:
                return []
            if basename in (".", ".."):
                entries += ["./", "../"]
            for entry in sorted(set(entries)):
                if not entry.startswith(basename) or (entry.startswith(".") and not basename.startswith(".")):
                    continue
                is_dir = entry.endswith("/")
                if words and words[0] == "cd" and not is_dir:
                    continue
                matches.append((directory + entry, is_dir, entry))
        matches = [m for m in matches if not any(c in m[0] for c in "\r\n\x00")]
        if not matches:
            return []

        quote = text[start] if start < len(text) and text[start] in "\"'" else None
        if quote is None and any(any(c in m[0] for c in " \t\"'&") for m in matches):
            quote = '"'
        before, after = text[:start], text[end:]
        candidates = []
        for value, is_dir, label in matches:
            replacement = before + _completion_quote(value, quote)
            cursor = len(replacement)
            if is_dir and quote:
                cursor -= 1       # keep typing inside the closing quote
            elif not is_dir and not after:
                replacement += " "
                cursor += 1
            candidates.append((replacement + after, cursor, label))
        if len(candidates) > 1:
            # Compute a logical prefix before quoting. String-prefixing quoted candidate
            # lines can split an embedded quote or attach one candidate's suffix to another.
            common = matches[0][0]
            for value, is_dir, label in matches[1:]:
                n = 0
                while n < min(len(common), len(value)) and common[n] == value[n]:
                    n += 1
                common = common[:n]
            shared = (text, pos)
            if len(common) > len(partial):
                replacement = before + _completion_quote(common, quote)
                shared = (replacement + after, len(replacement) - (1 if quote else 0))
            candidates[0] = candidates[0] + (shared,)
        return candidates

    def _prompt(self):
        # Terminal.read also calls this after displaying completion candidates.
        import term
        self.tty.write_color(self.cwd, term.CYAN)
        self.tty.write_color(" # ", term.GREEN)

    # -- execution --

    def run_command(self, name, args):
        # State-mutating and session commands live in the shell itself.
        if self._builtin(name, args, self, self.tty):
            return
        if _program.current() is not None:
            # Called from inside a running command (or job): part of that program.
            return self._run_nested(name, args, self, self.tty)
        environment = self._env.copy()
        src = self._source(name, path=environment.get('PATH', ''))
        if src is None:
            return
        # Each command runs in its own sandbox namespace: args/tty/sh are injected
        # there, functions the command defines keep that namespace as their scope
        # (so they see args/tty/sh too), and reads of component/computer/the libs
        # fall through to the system globals. Nothing the command defines leaks
        # back into the shell, so no save/restore of main globals is needed.
        self._next_command += 1
        program = "cmd:" + str(self._next_command)
        os._inherit_environment(program, environment)
        _set_cwd(program, self.cwd)         # its os.chdir moves only itself
        view = _ProgramShell(self, program, self.cwd, self.tty)
        _program.set_current(program)      # its threads and windows belong to it
        try:
            exec_sandbox(compile(src[1], src[0], 'exec'), {"args": args, "tty": self.tty, "sh": view, "__file__": src[0], "__name__": "__main__"})
        except SystemExit:
            pass
        except Exception as e:
            self.tty.print(name + ": " + str(e))
        finally:
            _program.set_current(None)
            self._end_program(program, True)

    def _builtin(self, name, args, view, tty):
        # The commands that live in the shell itself; False if name is not one. view is the
        # shell as the caller sees it: each program has its own directory and search path.
        if name == "cd":
            view._cd(args)
        elif name in ("exit", "shutdown", "reboot"):
            self._exit(name, tty)
        elif name == "help":
            tty.print("built-in: cd exit reboot help jobs kill; others use the command path; "
                      "end a line with & to run it in the background")
        elif name == "jobs":
            if tty is self.tty:
                self._report_jobs()
            for job in self.jobs:
                if not job["done"]:
                    tty.print("[" + str(job["id"]) + "] running  " + job["line"])
        elif name == "kill":
            self._kill(args, tty)
        else:
            return False
        return True

    def _exit(self, name, tty):
        # As bash: with jobs still running, the first exit only warns; the second stops them
        # (giving their finally blocks a second) and goes ahead.
        running = [job for job in self.jobs if not job["done"]]
        if running and not self._exit_warned:
            self._exit_warned = True
            tty.print("there are running jobs (see jobs); " + name + " again to stop them")
            return
        for job in running:
            _program.stop(job["program"])
            self._release_owned(job["program"])
        deadline = computer.uptime() + 1
        while [job for job in running if not job["done"]] and computer.uptime() < deadline:
            computer._yield_tick()
        self.running = False
        computer.shutdown(name == "reboot")

    def _source(self, name, tty=None, cwd=None, path=None):
        try:
            filename = self.command_path(name, cwd, path)
            src = None if filename is None else self.fs.read_file(filename)
        except Exception as e:     # unreadable, e.g. not UTF-8: report it, keep the shell
            (tty or self.tty).print(name + ": " + str(e))
            return None
        if src is None:
            (tty or self.tty).print(name + ": command not found")
        return None if src is None else (filename, src)

    def _run_nested(self, name, args, sh, tty):
        # A command run by a command: part of the caller's program, which cleans up for it.
        src = self._source(name, tty, sh.cwd, sh.path)
        if src is None:
            return
        try:
            exec_sandbox(compile(src[1], src[0], 'exec'), {"args": args, "tty": tty, "sh": sh, "__file__": src[0], "__name__": "__main__"})
        except SystemExit as stop:
            if _program.stopping():
                raise stop          # the caller's program was stopped: it stops too
        except Exception as e:
            tty.print(name + ": " + str(e))

    # -- programs ending --
    def _end_program(self, program, interactive):
        # What a process exit does: wait for its non-daemon threads (CPython waits too),
        # then stop whatever else it left running (daemon threads, coroutines), close its
        # windows and drop its listeners. A stopped task ends at its next poll point (see
        # _program). A killed job does not wait.
        if _program.threads(program, False):
            if interactive:
                self.tty.print("(waiting for " + str(len(_program.threads(program, False))) +
                               " thread(s); Ctrl+C stops them)")
            _program._waited.append(program)     # its threads leave Ctrl+C to us
            while _program.threads(program, False):
                if interactive:
                    if self._ctrl_c():
                        break
                elif _program.stopping(program):
                    break
                computer._yield_tick()
        _program.stop(program)
        self._release_owned(program)
        _drop_callbacks(program)
        _drop_cwd(program)
        _program.ended(program)

    def _ctrl_c(self):
        # Take a Ctrl+C from the keyboard queue; leave every other signal where it is. Other
        # tasks take signals from the same list meanwhile: scan a copy, remove by value.
        queue = computer._signals
        for signal in list(queue):
            if len(signal) > 2 and signal[0] == "key_down" and signal[2] == 3:
                try:
                    queue.remove(signal)
                except ValueError:
                    continue        # taken meanwhile
                self.tty.print("^C")
                return True
        return False

    # -- background jobs --
    def spawn(self, name, args, cwd=None, tty=None, path=None):
        """Run a command or Python path in the background (what `name args &` does); returns the
        job number, or None if there is no such command. tkedit uses this. tty: where
        to report (a job starting another reports to its own output)."""
        tty = tty or self.tty
        cwd = self.cwd if cwd is None else self.resolve(cwd)
        environment = os.environ.copy()
        if path is not None:
            environment['PATH'] = path
        src = self._source(name, tty, cwd, environment.get('PATH', ''))
        if src is None:
            return None
        import threading
        self._next_job += 1
        program = "job:" + str(self._next_job)
        job = {"id": self._next_job, "line": " ".join([name] + list(args)), "program": program,
               "thread": None, "done": False, "error": None}
        os._inherit_environment(program, environment)
        view = _JobShell(self, job, cwd)
        _set_cwd(program, view.cwd)

        def run():
            _program.set_current(program)     # this thread and what it starts run for the job
            try:
                exec_sandbox(compile(src[1], src[0], 'exec'), {"args": list(args), "tty": view.tty, "sh": view, "__file__": src[0], "__name__": "__main__"})
            except SystemExit:
                pass
            except Exception as e:
                job["error"] = str(e)
            finally:
                # The job's non-daemon threads keep it running, as a process; then the rest stops.
                self._end_program(program, False)
                job["done"] = True

        _program._background.append(program)   # it does not get the keyboard
        thread = threading.Thread(target=run, name=job["line"], daemon=True)
        thread._program_main = True              # threads it starts are not daemons by default
        job["thread"] = thread
        self.jobs.append(job)
        thread.start()
        tty.print("[" + str(job["id"]) + "] " + job["line"])
        return job["id"]

    def _report_jobs(self):
        # Before a prompt, as bash does: what background jobs printed, and which have ended.
        dropped, self._job_dropped = self._job_dropped, 0
        if dropped:
            self.tty.print("(" + str(dropped) + " earlier lines of background output dropped)")
        output, self._job_output = self._job_output, []
        for text in output:
            self.tty.write(text)
        for job in list(self.jobs):
            if job["done"] or not job["thread"].is_alive():
                line = "[" + str(job["id"]) + "] "
                self.tty.print(line + ("done  " + job["line"] if job["error"] is None
                                       else job["line"] + ": " + job["error"]))
                self.jobs.remove(job)
        for program in list(_program._stopping):
            # A stopped program's thread may have opened a window or a listener after its
            # clean-up (it stops at its next poll point); close them, and forget the program
            # once its tasks have all ended.
            self._release_owned(program)
            _drop_callbacks(program)
            _program.ended(program)

    def _kill(self, args, tty=None):
        tty = tty or self.tty
        if not args:
            tty.print("usage: kill <job>   (see jobs)")
            return
        number = args[0].lstrip("%")
        for job in self.jobs:
            if str(job["id"]) == number:
                if not job["done"]:
                    # Its tasks end at their next poll point; its windows close now.
                    _program.stop(job["program"])
                    self._release_owned(job["program"])
                return
        tty.print("kill: no such job: " + args[0])

    def _release_owned(self, program):
        # Close the windows one program opened; how many there were.
        try:
            return computer._tk_release_owned(program)
        except Exception:
            return 0

    def _cd(self, args):
        target = self.resolve(args[0]) if args else "/"
        if not self.fs.exists(target) or not self.fs.is_directory(target):
            self.tty.print("cd: no such directory: " + target)
            return
        self.cwd = target
        # keep os.getcwd() in step with the shell (shell is authoritative), and with the
        # command that ran cd through sh.run_command, as before
        _set_cwd(None, target)
        program = _program.current()
        if program is not None:
            _set_cwd(program, target)

    # -- main loop --
    def loop(self):
        while self.running:
            # If the screen went away (monitor broken, or the SFTP card's virtual
            # screen just came up), rebind to whatever is available now. Passive:
            # costs one getScreen() when nothing changed.
            try:
                self.tty.ensure_screen()
            except Exception:
                pass
            # Re-scan mounts so a disk inserted while the shell is running shows up
            # under /mnt (and a removed one disappears), the way OpenOS does.
            self.fs.refresh()
            self._report_jobs()
            line = self.tty.read(self._prompt, self.history, self._complete)
            if line is None:
                continue
            line = line.strip()
            if not line:
                continue
            self.history.append(line)
            background = line.endswith("&")
            parts = _split(line[:-1] if background else line)
            if not parts:
                continue
            if parts[0] not in ("exit", "shutdown", "reboot"):
                self._exit_warned = False
            if background and parts[0] not in _BUILTINS:
                self.spawn(parts[0], parts[1:])
            else:
                self.run_command(parts[0], parts[1:])


def _completion_token(text, pos):
    """Word at the cursor: its raw span, unquoted prefix and preceding words.
    Follow _split's quote rules, including adjacent quoted and unquoted pieces."""
    start, quote, value, words = 0, None, "", []
    for i in range(pos):
        ch = text[i]
        if quote is not None:
            if ch == quote:
                quote = None
            else:
                value += ch
        elif ch in "\"'":
            quote = ch
        elif ch in " \t":
            if value:
                words.append(value)
            start, value = i + 1, ""
        else:
            value += ch
    end = pos
    while end < len(text):
        ch = text[end]
        if quote is not None:
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch in " \t":
            break
        end += 1
    return start, end, value, words


def _completion_quote(value, quote):
    if quote is None:
        return value
    other = "'" if quote == '"' else '"'
    # _split has no backslash escapes: put an embedded quote in the other quote type.
    return quote + value.replace(quote, quote + other + quote + other + quote) + quote


def _split(line):
    """Split a command line into words. Double and single quotes group words;
    no escapes, no globbing -- a shell for a floppy, not bash."""
    out = []
    cur = ""
    quote = None
    for ch in line:
        if quote is not None:
            if ch == quote:
                quote = None
            else:
                cur += ch
        elif ch == '"' or ch == "'":
            quote = ch
        elif ch == " " or ch == "\t":
            if cur:
                out.append(cur)
                cur = ""
        else:
            cur += ch
    if cur:
        out.append(cur)
    return out


def run(tty, boot_fs_addr, main_globals=None):
    sh = Shell(tty, boot_fs_addr, main_globals)
    sh.loop()
