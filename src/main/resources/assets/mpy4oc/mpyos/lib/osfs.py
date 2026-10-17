"""osfs.py -- mpyos's filesystem layer for the standard `os` module.

The frozen VM `os` module deliberately has no filesystem ("sandbox-appropriate
bits only"): what a file IS depends on the host. Here the host is mpyos with OC
disks, so at boot init.py calls install(os, vfs), which plants the standard
filesystem API (listdir/mkdir/remove/stat/os.path/...) straight into the frozen
module's namespace. User code then just does `import os` and gets the standard
experience; urandom/uname from the frozen module stay untouched.

Working directory: os keeps its own cwd (getcwd/chdir). The shell's `cd` syncs
it (shell -> os), so scripts see the shell's directory; a script's own os.chdir
affects os.* relative paths for that script without moving the shell. Each
program (see the stdlib's _program: a shell command, a background job) has a cwd
of its own, as a process does: the shell gives a command the shell's directory
when it starts, a job keeps the one it started in, and a program's os.chdir
moves only its own.
"""

import fs

try:
    import _program
except ImportError:
    _program = None

_vfs = None
_cwd = "/"          # the main program's (the shell's)
_cwds = {}          # program -> its own cwd (set_cwd)

sep = "/"


def install(os_module, vfs):
    """Wire the shared Vfs and plant the filesystem API into `os_module`."""
    global _vfs
    _vfs = vfs
    import posixpath
    os_module.path = posixpath
    os_module._realpath = _abs
    os_module._path_identity = _path_identity
    os_module._path_exists = _path_exists
    os_module._path_mode = _path_mode
    os_module.sep = sep
    os_module.getcwd = getcwd
    os_module.chdir = chdir
    os_module.listdir = listdir
    os_module.mkdir = mkdir
    os_module.makedirs = makedirs
    os_module.rmdir = rmdir
    os_module.remove = remove
    os_module.unlink = remove
    os_module.rename = rename
    os_module.stat = stat
    os_module.statvfs = statvfs
    import _process
    _process.install(vfs)
    import io
    io.install(opener=_open_raw)


def shared_vfs():
    """The Vfs given to install(), so the shell can reuse it (single mount state)."""
    return _vfs


def _v():
    if _vfs is None:
        raise OSError("os: filesystem not installed (mpyos not booted)")
    return _vfs


class _RawFile:
    """Keep the original disk with its handle, even when mounts/cwd change."""
    def __init__(self, disk, path, mode):
        self.disk = disk
        self.handle = disk.open(path, mode)

    def read(self, count):
        result = self.disk._read_chunk(self.handle, count)
        if result is None:
            return b''
        return result.encode('utf-8') if isinstance(result, str) else result

    def write(self, data):
        if not self.disk._write_chunk(self.handle, data):
            raise OSError('file write failed')

    def seek(self, offset, whence):
        return self.disk.seek(self.handle, ('set', 'cur', 'end')[whence], offset)

    def tell(self):
        return self.seek(0, 1)

    def close(self):
        self.disk.close(self.handle)


def _open_raw(path, mode):
    if not path:
        raise FileNotFoundError('empty file name')
    absolute = _abs(path)
    vfs = _v()
    if vfs.is_directory(absolute):
        raise IsADirectoryError('is a directory: ' + path)
    disk, local = vfs._route(absolute)
    if mode == 'rb' and not disk.exists(local):
        raise FileNotFoundError('no such file: ' + path)
    if mode != 'rb' and disk.is_readonly():
        raise PermissionError('read-only filesystem: ' + path)
    return _RawFile(disk, local, mode)


def _program_now():
    return _program.current() if _program is not None and _cwds else None


def _abs(path):
    """Absolute, normalized path; relative resolves against os's cwd."""
    import os
    path = os.fspath(path)
    if not isinstance(path, str):
        raise TypeError('virtual filesystem paths must be str')
    if '\x00' in path:
        raise ValueError('embedded null byte')
    cwd = getcwd()
    if not path:
        raise FileNotFoundError('empty file name')
    if path == ".":
        return cwd
    if path.startswith("/"):
        return fs.normalize(path)
    return fs.normalize(cwd + "/" + path)


def _path_identity(path):
    disk, local = _v()._route(_abs(path))
    return disk.address, local


def _path_exists(path):
    return _v().exists(_abs(path))


def _path_mode(path):
    path = _abs(path)
    vfs = _v()
    return (0x4000 if vfs.is_directory(path) else 0x8000) if vfs.exists(path) else 0


# -- directory state ---------------------------------------------------------

def getcwd():
    program = _program_now()
    return _cwd if program is None else _cwds.get(program, _cwd)


def chdir(path):
    t = _abs(path)
    if not _v().is_directory(t):
        raise OSError("chdir: not a directory: " + t)
    program = _program_now()
    set_cwd(program if program in _cwds else None, t)


def set_cwd(program, path):
    """The shell: program's cwd (None: the shell's own) is now path."""
    global _cwd
    if program is None:
        _cwd = path
    else:
        _cwds[program] = path


def drop_cwd(program):
    """The shell: program has ended."""
    if _program is None or not _program.live(program):
        _cwds.pop(program, None)


if _program is not None:
    _program._on_end.append(drop_cwd)


# -- queries -----------------------------------------------------------------

def listdir(path="."):
    t = _abs(path)
    v = _v()
    if not v.is_directory(t):
        raise OSError("listdir: not a directory: " + t)
    out = []
    for name in v.list(t):
        # mpyos's fs marks directories with a trailing '/'; os.listdir gives
        # bare names like CPython.
        out.append(name[:-1] if name.endswith("/") else name)
    return out


def stat(path):
    """MicroPython-style 10-tuple: (mode, ino, dev, nlink, uid, gid, size,
    atime, mtime, ctime). mode is 0x4000 for a directory, 0x8000 for a file;
    times are seconds (OC reports milliseconds)."""
    t = _abs(path)
    v = _v()
    if not v.exists(t):
        raise FileNotFoundError("stat: no such file or directory: " + t)
    is_dir = v.is_directory(t)
    mode = 0x4000 if is_dir else 0x8000
    size = 0 if is_dir else (v.size(t) or 0)
    mtime = (v.last_modified(t) or 0) // 1000
    return (mode, 0, 0, 0, 0, 0, size, mtime, mtime, mtime)


def statvfs(path="/"):
    """MicroPython-style 10-tuple with 1-byte blocks: (bsize, frsize, blocks,
    bfree, bavail, files, ffree, favail, flags, namemax)."""
    v = _v()
    total = v.space_total() or 0
    used = v.space_used() or 0
    free = total - used if total > used else 0
    return (1, 1, total, free, free, 0, 0, 0, 0, 255)


# -- mutations ---------------------------------------------------------------

def mkdir(path):
    t = _abs(path)
    v = _v()
    if v.exists(t):
        raise FileExistsError("mkdir: already exists: " + t)
    if not v.make_directory(t):
        raise OSError("mkdir: cannot create: " + t)


def makedirs(path, exist_ok=False):
    t = _abs(path)
    v = _v()
    if v.exists(t):
        if exist_ok and v.is_directory(t):
            return
        raise FileExistsError("makedirs: already exists: " + t)
    # build each missing level; OC's makeDirectory is single-level on some
    # filesystems, so walk the segments explicitly
    parts = [p for p in t.split("/") if p]
    cur = ""
    for p in parts:
        cur = cur + "/" + p
        if v.exists(cur):
            if not v.is_directory(cur):
                raise NotADirectoryError("makedirs: not a directory: " + cur)
            continue
        if not v.make_directory(cur):
            raise OSError("makedirs: cannot create: " + cur)


def rmdir(path):
    """Remove an EMPTY directory (unlike OC's recursive remove)."""
    t = _abs(path)
    v = _v()
    if not v.is_directory(t):
        raise OSError("rmdir: not a directory: " + t)
    if v.list(t):
        raise OSError("rmdir: directory not empty: " + t)
    if not v.remove(t):
        raise OSError("rmdir: cannot remove: " + t)


def remove(path):
    """Remove a FILE (directories need rmdir, matching CPython)."""
    t = _abs(path)
    v = _v()
    if not v.exists(t):
        raise FileNotFoundError("remove: no such file: " + t)
    if v.is_directory(t):
        raise IsADirectoryError("remove: is a directory (use rmdir): " + t)
    if not v.remove(t):
        raise OSError("remove: cannot remove: " + t)


def rename(src, dst):
    s = _abs(src)
    d = _abs(dst)
    v = _v()
    if not v.exists(s):
        raise OSError("rename: no such file or directory: " + s)
    if not v.rename(s, d):
        raise OSError("rename: cannot rename: " + s + " -> " + d)
