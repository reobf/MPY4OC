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
import _process
import sys

_BUILTINS = ("cd", "exit", "shutdown", "reboot", "help", "jobs", "kill")
_DEFAULT_PATH = "/bin:/bin/tkinter:/usr/bin:/home/bin:."  # OpenOS order with bundled Tk commands.


def _set_cwd(program, path):
    # os.getcwd() of program (None: the shell's own); see osfs.
    try:
        import osfs
        osfs.set_cwd(program, path)
    except Exception:
        pass            # a stripped image without osfs: os keeps no directory


def _drop_callbacks(program):
    # The event listeners and timers program registered (see event).
    try:
        import event
        event.drop(program)
    except Exception:
        pass


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
        self._runtime = _process.install(self.fs, tty)
        self._runtime.context = self._context
        self.jobs = self._runtime.jobs
        self._exit_warned = False
        self.g = main_globals if main_globals is not None else globals()

    def _context(self, job, cwd, tty):
        view = _ProgramShell(self, job['program'], cwd, tty)
        view.background = job['background']
        return view

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
        Try the exact filename first, then .py and .mpy; a directory is not executable."""
        return _process.command_path(self.fs, name, self.cwd if cwd is None else cwd, self.path if path is None else path)

    def find_command(self, name, cwd=None, path=None):
        """Resolved command contents (str source or bytes for .mpy), or None."""
        filename = self.command_path(name, cwd, path)
        if filename is None:
            return None
        return self.fs.read_bytes(filename) if filename.endswith('.mpy') else self.fs.read_file(filename)

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
                        # Source and bytecode commands share one bare-name completion.
                        if entry.endswith("/") or (entry.startswith(".") and not partial.startswith(".")):
                            continue
                        name = entry[:-3] if entry.endswith('.py') else entry[:-4] if entry.endswith('.mpy') else entry
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
        prepared = self._prepare(name, args, self.cwd, self._env.copy(), self.tty)
        if prepared is None:
            return
        if prepared['windowed']:
            return self._spawn(prepared, name, args, self.tty)
        job = self._runtime.foreground(prepared, self.tty)
        if job['error'] is not None:
            self.tty.print(name + ': ' + job['error'])
        return job['returncode']

    def _prepare(self, name, args, cwd, environment, tty):
        try:
            return self._runtime.prepare([name] + list(args), cwd, environment)
        except FileNotFoundError:
            tty.print(name + ': command not found')
        except Exception as error:
            tty.print(name + ': ' + str(error))
        return None

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
            self._runtime.terminate(job)
        deadline = computer.uptime() + 1
        while [job for job in running if not job["done"]] and computer.uptime() < deadline:
            computer._yield_tick()
        self.running = False
        computer.shutdown(name == "reboot")

    def _run_nested(self, name, args, sh, tty):
        # Legacy sh calls share the caller's program. subprocess creates a child.
        prepared = self._prepare(name, args, sh.cwd, sh._env.copy(), tty)
        if prepared is None:
            return
        if prepared['windowed']:
            return self._spawn(prepared, name, args, tty)
        previous = sys.argv
        sys.argv = list(prepared['args'])
        try:
            exec_sandbox(_process.load_code(prepared), {'args': sys.argv[1:], 'argv': sys.argv, 'tty': tty, 'sh': sh, '__file__': prepared['filename'], '__name__': '__main__'})
        except SystemExit as stop:
            if _program.stopping():
                raise stop
        except Exception as error:
            tty.print(name + ': ' + str(error))
        finally:
            sys.argv = previous

    def spawn(self, name, args, cwd=None, tty=None, path=None):
        """Compatibility API: launch a background program and return its job number."""
        tty = tty or self.tty
        cwd = self.cwd if cwd is None else self.resolve(cwd)
        environment = os.environ.copy()
        if path is not None:
            environment['PATH'] = path
        prepared = self._prepare(name, args, cwd, environment, tty)
        if prepared is not None:
            return self._spawn(prepared, name, args, tty)
        return None

    def _spawn(self, prepared, name, args, tty):
        job = self._runtime.spawn(prepared, True, True, ' '.join([name] + list(args)))
        tty.print('[' + str(job['id']) + '] ' + job['line'])
        return job['id']

    def _report_jobs(self):
        # Before a prompt, as bash does: what background jobs printed, and which have ended.
        dropped, self._runtime._job_dropped = self._runtime._job_dropped, 0
        if dropped:
            self.tty.print("(" + str(dropped) + " earlier lines of background output dropped)")
        output, self._runtime._job_output = self._runtime._job_output, []
        for text in output:
            self.tty.write(text)
        for job in list(self.jobs):
            if job["done"]:
                line = "[" + str(job["id"]) + "] "
                if job['announce']:
                    self.tty.print(line + ("done  " + job["line"] if job["error"] is None
                                           else job["line"] + ": " + job["error"]))
                try:
                    self.jobs.remove(job)
                except ValueError:
                    pass  # subprocess reaped its completed child meanwhile
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
                    self._runtime.terminate(job)
                return
        tty.print("kill: no such job: " + args[0])

    def _release_owned(self, program):
        return _process.release(program)

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
            try:
                parts = _split(line[:-1] if background else line)
            except ValueError as error:
                self.tty.print('shell: ' + str(error))
                continue
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
    return _process.split(line)


def run(tty, boot_fs_addr, main_globals=None):
    sh = Shell(tty, boot_fs_addr, main_globals)
    sh.loop()
