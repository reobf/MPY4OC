"""POSIX pathlib subset for MPYOS; pure paths work without an installed disk."""
import os
import posixpath as _path
import fnmatch

class PurePosixPath(os.PathLike):
    def __init__(self, *segments):
        joined = _path.join(*segments) if segments else ''
        self._root = '//' if joined.startswith('//') and not joined.startswith('///') else '/' if joined.startswith('/') else ''
        self._parts = tuple(p for p in joined.split('/') if p and p != '.')

    def __fspath__(self):
        return self._root + '/'.join(self._parts) or '.'

    def __str__(self):
        return self.__fspath__()

    def __repr__(self):
        return type(self).__name__ + '(' + repr(str(self)) + ')'

    def __truediv__(self, other):
        return self.joinpath(other)

    def __rtruediv__(self, other):
        return type(self)(other, self)

    def __eq__(self, other):
        return isinstance(other, PurePosixPath) and str(self) == str(other)

    def __lt__(self, other):
        if not isinstance(other, PurePosixPath):
            raise TypeError('paths can only be ordered with paths')
        return self.parts < other.parts

    def __le__(self, other):
        return self == other or self < other

    def __gt__(self, other):
        return not self <= other

    def __ge__(self, other):
        return not self < other

    @property
    def parts(self):
        return (self._root,) + self._parts if self._root else self._parts

    @property
    def drive(self):
        return ''

    @property
    def root(self):
        return self._root

    @property
    def anchor(self):
        return self._root

    @property
    def name(self):
        return self._parts[-1] if self._parts else ''

    @property
    def suffix(self):
        name = self.name
        dot = name.rfind('.')
        return name[dot:] if 0 < dot < len(name) - 1 else ''

    @property
    def suffixes(self):
        name = self.name
        return [] if name.endswith('.') else ['.' + s for s in name.lstrip('.').split('.')[1:]]

    @property
    def stem(self):
        suffix = self.suffix
        return self.name[:-len(suffix)] if suffix else self.name

    @property
    def parent(self):
        return type(self)(self._root + '/'.join(self._parts[:-1])) if self._parts else self

    @property
    def parents(self):
        out, path = [], self
        while path._parts:
            path = path.parent
            out.append(path)
        return tuple(out)

    def joinpath(self, *other):
        return type(self)(self, *other)

    def with_name(self, name):
        if not self.name:
            raise ValueError(str(self) + ' has an empty name')
        if not isinstance(name, str):
            raise TypeError('name must be str')
        if not name or name == '.' or '/' in name:
            raise ValueError('Invalid name: ' + repr(name))
        return self.parent / name

    def with_suffix(self, suffix):
        if not isinstance(suffix, str):
            raise TypeError('suffix must be str')
        if suffix and (not suffix.startswith('.') or suffix == '.' or '/' in suffix):
            raise ValueError('Invalid suffix: ' + repr(suffix))
        return self.with_name(self.stem + suffix)

    def with_stem(self, stem):
        return self.with_name(stem + self.suffix)

    def is_absolute(self):
        return bool(self._root)

    def is_reserved(self):
        return False

    def as_posix(self):
        return str(self)

    def relative_to(self, *other, walk_up=False):
        other = PurePosixPath(*other)
        if self.root != other.root:
            raise ValueError('paths have different anchors')
        common = 0
        while common < min(len(self._parts), len(other._parts)) and self._parts[common] == other._parts[common]:
            common += 1
        if common != len(other._parts):
            if not walk_up or '..' in other._parts[common:]:
                raise ValueError(str(self) + ' is not in the subpath of ' + str(other))
        return type(self)(*(['..'] * (len(other._parts) - common) + list(self._parts[common:])))

    def is_relative_to(self, *other):
        try:
            self.relative_to(*other)
            return True
        except ValueError:
            return False

    def match(self, path_pattern, *, case_sensitive=None):
        pattern = PurePosixPath(path_pattern)
        if not pattern.parts:
            raise ValueError('empty pattern')
        if pattern.root and (self.root != pattern.root or len(self._parts) != len(pattern._parts)):
            return False
        if len(pattern._parts) > len(self._parts):
            return False
        for name, pat in zip(reversed(self._parts), reversed(pattern._parts)):
            if case_sensitive is False:
                name, pat = name.lower(), pat.lower()
            if not fnmatch.fnmatchcase(name, pat):
                return False
        return True

PurePath = PurePosixPath

class _StatResult(tuple):
    @property
    def st_mode(self):
        return self[0]
    @property
    def st_size(self):
        return self[6]
    @property
    def st_atime(self):
        return self[7]
    @property
    def st_mtime(self):
        return self[8]
    @property
    def st_ctime(self):
        return self[9]

class PosixPath(PurePosixPath):
    @classmethod
    def cwd(cls):
        return cls(os.getcwd())

    @classmethod
    def home(cls):
        return cls('~').expanduser()

    def expanduser(self):
        text = _path.expanduser(self)
        if text.startswith('~'):
            raise RuntimeError('Could not determine home directory')
        return type(self)(text)

    def absolute(self):
        return self if self.is_absolute() else type(self)(os.getcwd(), self)

    def resolve(self, strict=False):
        return type(self)(_path.realpath(self, strict=strict))

    def exists(self, *, follow_symlinks=True):
        return _path.exists(self)

    def is_dir(self):
        return _path.isdir(self)

    def is_file(self):
        return _path.isfile(self)

    def is_symlink(self):
        return False

    def stat(self, *, follow_symlinks=True):
        return _StatResult(os.stat(self))

    lstat = stat

    def samefile(self, other):
        return _path.samefile(self, other)

    def iterdir(self):
        for name in os.listdir(self):
            yield self / name

    def glob(self, pattern, *, case_sensitive=None):
        import glob
        pattern = _path._path(pattern)
        if not pattern:
            raise ValueError('Unacceptable pattern: empty pattern')
        if pattern.startswith('/'):
            raise NotImplementedError('Non-relative patterns are unsupported')
        if case_sensitive is False:
            raise NotImplementedError('case-insensitive disk globbing is not supported')
        for part in pattern.split('/'):
            if '**' in part and part != '**':
                raise ValueError("'**' can only be an entire path component")
        # pathlib's trailing ** selects directories, unlike glob's bare **.
        components = [p for p in pattern.split('/') if p and p != '.']
        seen = set()
        if components and all(p == '**' for p in components):
            if self.is_dir():
                yield self
                seen.add(str(self))
        if pattern.endswith('**'):
            pattern += '/'
        for name in glob.iglob(pattern, root_dir=self, recursive=True, include_hidden=True):
            result = self / name
            if pattern.count('**') > 1:
                if str(result) in seen:
                    continue
                seen.add(str(result))
            yield result

    def rglob(self, pattern, *, case_sensitive=None):
        return self.glob('**/' + _path._path(pattern), case_sensitive=case_sensitive)

    def open(self, mode='r', buffering=-1, encoding=None, errors=None, newline=None):
        return open(self, mode, buffering, encoding, errors, newline)

    def read_bytes(self):
        with self.open('rb') as stream:
            return stream.read()

    def read_text(self, encoding=None, errors=None):
        with self.open('r', encoding=encoding, errors=errors) as stream:
            return stream.read()

    def write_bytes(self, data):
        if not isinstance(data, (bytes, bytearray)):
            raise TypeError('data must be bytes-like')
        with self.open('wb') as stream:
            return stream.write(data)

    def write_text(self, data, encoding=None, errors=None, newline=None):
        if not isinstance(data, str):
            raise TypeError('data must be str')
        with self.open('w', encoding=encoding, errors=errors, newline=newline) as stream:
            return stream.write(data)

    def mkdir(self, mode=511, parents=False, exist_ok=False):
        if self.exists():
            if exist_ok and self.is_dir():
                return
            raise FileExistsError(str(self))
        if parents:
            os.makedirs(self, exist_ok=exist_ok)
        else:
            os.mkdir(self)

    def touch(self, mode=438, exist_ok=True):
        if not exist_ok:
            raise NotImplementedError('atomic exclusive creation is not supported by OC')
        with self.open('ab'):
            pass

    def unlink(self, missing_ok=False):
        try:
            os.unlink(self)
        except FileNotFoundError:
            if not missing_ok:
                raise

    def rmdir(self):
        os.rmdir(self)

    def rename(self, target):
        os.rename(self, target)
        return type(self)(target)

Path = PosixPath
