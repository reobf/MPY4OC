"""Virtual MPYOS subprocesses: Popen/run/call, cwd/env, exit status and cooperative stop.

No native host execution, pipes, redirection or platform-specific creation flags.
All programs share the OC computer's VM/modules; this is not memory isolation.
"""
import _process
import _program
import time

PIPE, STDOUT, DEVNULL = -1, -2, -3


class SubprocessError(Exception):
    pass


_SIGNALS = {9: 'SIGKILL', 15: 'SIGTERM'}


class CalledProcessError(SubprocessError):
    def __init__(self, returncode, cmd, output=None, stderr=None):
        Exception.__init__(self, returncode, cmd)
        self.returncode, self.cmd = returncode, cmd
        self.output, self.stdout, self.stderr = output, output, stderr

    def __str__(self):
        # CPython's wording: the command with %s (a list shows as a list), a signal by name.
        if self.returncode and self.returncode < 0:
            name = _SIGNALS.get(-self.returncode)
            if name is None:
                return "Command '%s' died with unknown signal %d." % (self.cmd, -self.returncode)
            return "Command '%s' died with <Signals.%s: %d>." % (self.cmd, name, -self.returncode)
        return "Command '%s' returned non-zero exit status %d." % (self.cmd, self.returncode)


class TimeoutExpired(SubprocessError):
    def __init__(self, cmd, timeout, output=None, stderr=None):
        Exception.__init__(self, cmd, timeout)
        self.cmd, self.timeout = cmd, timeout
        self.output, self.stdout, self.stderr = output, output, stderr

    def __str__(self):
        return "Command '%s' timed out after %s seconds" % (self.cmd, self.timeout)


class CompletedProcess:
    def __init__(self, args, returncode, stdout=None, stderr=None):
        self.args, self.returncode, self.stdout, self.stderr = args, returncode, stdout, stderr
    def __repr__(self):
        parts = ['args=' + repr(self.args), 'returncode=' + repr(self.returncode)]
        if self.stdout is not None:
            parts.append('stdout=' + repr(self.stdout))
        if self.stderr is not None:
            parts.append('stderr=' + repr(self.stderr))
        return 'CompletedProcess(' + ', '.join(parts) + ')'
    def check_returncode(self):
        if self.returncode:
            raise CalledProcessError(self.returncode, self.args, self.stdout, self.stderr)


class Popen:
    def __init__(self, args, *, cwd=None, env=None, shell=False, stdin=None, stdout=None, stderr=None, **kwargs):
        if shell or kwargs or stdin is not None or stdout is not None or stderr is not None:
            raise NotImplementedError('subprocess supports args, cwd and env; shell, pipes and redirection are not implemented')
        self.args = args if isinstance(args, str) else list(args)
        self._runtime = _process.runtime()
        prepared = self._runtime.prepare(self.args, cwd, env)
        self._job = self._runtime.spawn(prepared, background=prepared['windowed'])
        self.pid = self._job['pid']
        self.stdin = self.stdout = self.stderr = None
    @property
    def returncode(self):
        return self._job['returncode']
    def poll(self):
        return self.returncode
    def wait(self, timeout=None):
        deadline = None if timeout is None else time.ticks_ms() + max(0, timeout) * 1000
        while self.returncode is None:
            _program.check()
            if deadline is not None and time.ticks_ms() >= deadline:
                raise TimeoutExpired(self.args, timeout)
            time.sleep(0.01)
        return self.returncode
    def communicate(self, input=None, timeout=None):
        if input is not None:
            raise NotImplementedError('subprocess input pipes are not implemented')
        self.wait(timeout)
        return None, None
    def terminate(self):
        self._runtime.terminate(self._job, 15)
    def kill(self):
        self._runtime.terminate(self._job, 9)
    def send_signal(self, signal):
        if signal not in (9, 15):
            raise NotImplementedError('only SIGTERM (15) and SIGKILL (9) are supported')
        self._runtime.terminate(self._job, signal)
    def __enter__(self):
        return self
    def __exit__(self, *exc):
        self.wait()


def run(args, *, timeout=None, check=False, input=None, capture_output=False, **kwargs):
    if input is not None or capture_output:
        raise NotImplementedError('subprocess pipes are not implemented')
    process = Popen(args, **kwargs)
    try:
        code = process.wait(timeout)
    except BaseException:
        process.kill()
        raise
    result = CompletedProcess(process.args, code)
    if check:
        result.check_returncode()
    return result


def call(args, *, timeout=None, **kwargs):
    return run(args, timeout=timeout, **kwargs).returncode


def check_call(args, **kwargs):
    return run(args, check=True, **kwargs).returncode
