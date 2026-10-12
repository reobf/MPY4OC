# Frozen os: VM-local environment; MPYOS installs the filesystem API at boot.
import _program

urandom = __os_urandom

def uname():
    return ("mpy-java", "mpy-java", "1.0", "1.0", "java")


# Never import the Java/Windows host's environment. Each OC computer owns this
# state, and each shell program inherits a copy; its threads share that copy.
_environments = {None: {}}
supports_bytes_environ = False


def _environment(program):
    if program not in _environments:
        _environments[program] = _environments[None].copy()
    return _environments[program]


def _inherit_environment(program, values=None):
    _environments[program] = environ.copy() if values is None else values.copy()


def _drop_environment(program):
    # Stopped threads may still be running finally blocks. Keep their environment
    # until they have ended; the shell's next job reap invokes this hook again.
    if program is not None and not _program.live(program):
        _environments.pop(program, None)


_program._on_end.append(_drop_environment)


def _env_string(value):
    if not isinstance(value, str):
        raise TypeError('str expected, not ' + type(value).__name__)
    return value


class _Environ:
    """Mapping for the caller's program, or a bound shell view. No host OS state."""
    def __init__(self, program=None, dynamic=True):
        self._program = program
        self._dynamic = dynamic

    def _data(self):
        return _environment(_program.current() if self._dynamic else self._program)

    def __getitem__(self, key):
        return self._data()[_env_string(key)]

    def __setitem__(self, key, value):
        _env_string(key)
        _env_string(value)
        if not key or '=' in key:
            raise ValueError('illegal environment variable name')
        if '\x00' in key or '\x00' in value:
            raise ValueError('embedded null byte')
        self._data()[key] = value

    def __delitem__(self, key):
        del self._data()[_env_string(key)]

    def __iter__(self):
        return iter(self._data())

    def __len__(self):
        return len(self._data())

    def __contains__(self, key):
        return _env_string(key) in self._data()

    def __repr__(self):
        return 'environ(' + repr(self._data()) + ')'

    def get(self, key, default=None):
        return self._data().get(_env_string(key), default)

    def keys(self):
        return self._data().keys()

    def values(self):
        return self._data().values()

    def items(self):
        return self._data().items()

    def copy(self):
        return self._data().copy()

    def setdefault(self, key, default=None):
        if key in self:
            return self[key]
        self[key] = default
        return default

    def pop(self, key, *default):
        if len(default) > 1:
            raise TypeError('pop expected at most 2 arguments')
        _env_string(key)
        if key in self:
            value = self[key]
            del self[key]
            return value
        if default:
            return default[0]
        raise KeyError(key)

    def popitem(self):
        for key in self:
            return key, self.pop(key)
        raise KeyError('popitem(): mapping is empty')

    def clear(self):
        self._data().clear()

    def update(self, *args, **kwargs):
        if len(args) > 1:
            raise TypeError('update expected at most 1 argument')
        if args:
            other = args[0]
            if hasattr(other, 'keys'):
                for key in other.keys():
                    self[key] = other[key]
            else:
                for key, value in other:
                    self[key] = value
        for key in kwargs:
            self[key] = kwargs[key]


environ = _Environ()


def getenv(key, default=None):
    return environ.get(key, default)
