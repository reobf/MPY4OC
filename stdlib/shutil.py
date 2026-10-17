"""File utilities for the virtual disk. OC has no permissions, links or utime."""
import os
import posixpath as _path
import fnmatch

class Error(OSError):
    pass

class SameFileError(Error):
    pass

def copyfileobj(fsrc, fdst, length=65536):
    if not isinstance(length, int):
        raise TypeError('length must be an integer')
    if length == 0:
        length = 65536
    while True:
        data = fsrc.read(length)
        if not data:
            return
        fdst.write(data)

def _identity(path):
    hook = getattr(os, '_path_identity', None)
    return hook(path) if hook is not None else ('', _path.realpath(path))

def _same(a, b):
    return _identity(a) == _identity(b)

def _inside(src, dst):
    source_disk, source_path = _identity(src)
    dest_disk, dest_path = _identity(dst)
    return source_disk == dest_disk and (source_path == dest_path or dest_path.startswith(source_path.rstrip('/') + '/'))

def copyfile(src, dst, *, follow_symlinks=True):
    if _same(src, dst):
        raise SameFileError(str(src) + ' and ' + str(dst) + ' are the same file')
    if _path.isdir(src) or _path.isdir(dst):
        raise IsADirectoryError('copyfile requires regular files')
    with open(src, 'rb') as source:
        with open(dst, 'wb') as target:
            copyfileobj(source, target)
    return dst

def copymode(src, dst, *, follow_symlinks=True):
    # No permission bits exist on OC files. Still validate both paths.
    os.stat(src)
    os.stat(dst)

def copystat(src, dst, *, follow_symlinks=True):
    # Read-only disks and modification timestamps are controlled by the component.
    copymode(src, dst, follow_symlinks=follow_symlinks)

def copy(src, dst, *, follow_symlinks=True):
    if _path.isdir(dst):
        dst = _path.join(dst, _path.basename(src))
    copyfile(src, dst, follow_symlinks=follow_symlinks)
    copymode(src, dst, follow_symlinks=follow_symlinks)
    return dst

def copy2(src, dst, *, follow_symlinks=True):
    if _path.isdir(dst):
        dst = _path.join(dst, _path.basename(src))
    copyfile(src, dst, follow_symlinks=follow_symlinks)
    copystat(src, dst, follow_symlinks=follow_symlinks)
    return dst

def ignore_patterns(*patterns):
    def ignore(path, names):
        return {name for name in names if any(fnmatch.fnmatch(name, pattern) for pattern in patterns)}
    return ignore

def _copytree(src, dst, ignore, copy_function, dirs_exist_ok):
    names = os.listdir(src)
    ignored = set() if ignore is None else set(ignore(src, names))
    os.makedirs(dst, exist_ok=dirs_exist_ok)
    errors = []
    for name in names:
        if name in ignored:
            continue
        source, target = _path.join(src, name), _path.join(dst, name)
        try:
            if _path.isdir(source):
                _copytree(source, target, ignore, copy_function, dirs_exist_ok)
            else:
                copy_function(source, target)
        except Error as exc:
            if exc.args and isinstance(exc.args[0], list):
                errors.extend(exc.args[0])
            else:
                errors.append((source, target, str(exc)))
        except OSError as exc:
            errors.append((source, target, str(exc)))
    if errors:
        raise Error(errors)
    return dst

def copytree(src, dst, symlinks=False, ignore=None, copy_function=copy2, ignore_dangling_symlinks=False, dirs_exist_ok=False):
    if _inside(src, dst):
        raise Error('cannot copy a directory into itself')
    _copytree(_path._path(src), _path._path(dst), ignore, copy_function, dirs_exist_ok)
    return dst

def _remove_error(function, path, exc, ignore_errors, onerror, onexc):
    if ignore_errors:
        return
    if onexc is not None:
        onexc(function, path, exc)
    elif onerror is not None:
        onerror(function, path, (type(exc), exc, None))
    else:
        raise exc

def rmtree(path, ignore_errors=False, onerror=None, *, onexc=None, dir_fd=None):
    if dir_fd is not None:
        raise NotImplementedError('file descriptors are not supported')
    if onerror is not None and onexc is not None:
        raise TypeError('onerror and onexc cannot both be supplied')
    path = _path._path(path)
    pending = [(path, False)]
    while pending:
        current, leave = pending.pop()
        try:
            if leave:
                os.rmdir(current)
            else:
                names = os.listdir(current)
        except OSError as exc:
            _remove_error(os.rmdir if leave else os.listdir, current, exc, ignore_errors, onerror, onexc)
            continue
        if leave:
            continue
        pending.append((current, True))
        for name in names:
            child = _path.join(current, name)
            if _path.isdir(child):
                pending.append((child, False))
            else:
                try:
                    os.unlink(child)
                except OSError as exc:
                    _remove_error(os.unlink, child, exc, ignore_errors, onerror, onexc)

def move(src, dst, copy_function=copy2):
    result = dst
    src, dst = _path._path(src), _path._path(dst)
    if not _path.exists(src):
        raise FileNotFoundError(src)
    target = dst
    if _path.isdir(dst):
        if _same(src, dst):
            return result
        target = _path.join(dst, _path.basename(src.rstrip('/')))
        result = target
        if _path.exists(target):
            raise Error('Destination path already exists: ' + target)
    if _same(src, target):
        return result
    if _path.isdir(src) and _inside(src, target):
        raise Error('cannot move a directory into itself')
    try:
        os.rename(src, target)
    except OSError:
        if _path.isdir(src):
            copytree(src, target, copy_function=copy_function)
            rmtree(src)
        else:
            copy_function(src, target)
            os.unlink(src)
    return result
