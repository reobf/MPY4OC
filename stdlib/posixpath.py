"""POSIX text paths; filesystem operations use only the installed virtual os."""
sep, pathsep, curdir, pardir, extsep, altsep = '/', ':', '.', '..', '.', None

def _path(value):
    import os
    value = os.fspath(value)
    if not isinstance(value, str):
        raise TypeError('virtual filesystem paths must be str')
    return value

def join(a, *parts):
    out = _path(a)
    for part in parts:
        part = _path(part)
        if part.startswith('/') or not out:
            out = part
        else:
            out += ('' if out.endswith('/') else '/') + part
    return out

def split(path):
    path = _path(path)
    index = path.rfind('/') + 1
    head, tail = path[:index], path[index:]
    if head and head != '/' * len(head):
        head = head.rstrip('/')
    return head, tail

def basename(path):
    return split(path)[1]

def dirname(path):
    return split(path)[0]

def splitdrive(path):
    return '', _path(path)

def splitext(path):
    path = _path(path)
    slash, dot = path.rfind('/'), path.rfind('.')
    if dot > slash:
        for c in path[slash + 1:dot]:
            if c != '.':
                return path[:dot], path[dot:]
    return path, ''

def isabs(path):
    return _path(path).startswith('/')

def normcase(path):
    return _path(path)

def normpath(path):
    path = _path(path)
    if not path:
        return '.'
    root = '//' if path.startswith('//') and not path.startswith('///') else '/' if path.startswith('/') else ''
    out = []
    for part in path.split('/'):
        if not part or part == '.':
            continue
        if part == '..' and out and out[-1] != '..':
            out.pop()
        elif part != '..' or not root:
            out.append(part)
    return root + '/'.join(out) or '.'

def abspath(path):
    import os
    path = _path(path)
    return normpath(path if isabs(path) else join(os.getcwd(), path))

def realpath(path, *, strict=False):
    import os
    path = _path(path)
    resolver = getattr(os, '_realpath', None)
    result = resolver(path or '.') if resolver is not None else abspath(path)
    if strict:
        os.stat(result)
    return result

def exists(path):
    import os
    path = _path(path)
    hook = getattr(os, '_path_exists', None)
    stat = getattr(os, 'stat', None)
    try:
        if hook is not None:
            return hook(path)
        if stat is None:        # a host without a filesystem: nothing exists
            return False
        stat(path)
        return True
    except (OSError, ValueError):
        return False

lexists = exists

def _mode(path):
    import os
    path = _path(path)
    hook = getattr(os, '_path_mode', None)
    stat = getattr(os, 'stat', None)
    try:
        if hook is not None:
            return hook(path)
        return 0 if stat is None else stat(path)[0] & 0xf000
    except (OSError, ValueError):
        return 0

def isdir(path):
    return _mode(path) == 0x4000

def isfile(path):
    return _mode(path) == 0x8000

def islink(path):
    _path(path)
    return False  # OC filesystems have no symbolic links.

def getsize(path):
    import os
    return os.stat(_path(path))[6]

def getmtime(path):
    import os
    return os.stat(_path(path))[8]

def getatime(path):
    import os
    return os.stat(_path(path))[7]

def getctime(path):
    import os
    return os.stat(_path(path))[9]

def samefile(a, b):
    import os
    a, b = _path(a), _path(b)
    os.stat(a)
    os.stat(b)
    identity = getattr(os, '_path_identity', None)
    return identity(a) == identity(b) if identity is not None else realpath(a) == realpath(b)

def commonpath(paths):
    paths = [_path(p) for p in paths]
    if not paths:
        raise ValueError('commonpath() arg is an empty sequence')
    absolute = isabs(paths[0])
    if any(isabs(p) != absolute for p in paths):
        raise ValueError("Can't mix absolute and relative paths")
    parts = [[x for x in p.split('/') if x and x != '.'] for p in paths]
    lo, hi = min(parts), max(parts)
    count = 0
    while count < min(len(lo), len(hi)) and lo[count] == hi[count]:
        count += 1
    return ('/' if absolute else '') + '/'.join(lo[:count])

def commonprefix(paths):
    if not paths:
        return ''
    lo, hi = min(paths), max(paths)
    count = 0
    while count < min(len(lo), len(hi)) and lo[count] == hi[count]:
        count += 1
    return lo[:count]

def relpath(path, start='.'):
    path = _path(path)
    if not path:
        raise ValueError('no path specified')
    path_parts = [p for p in abspath(path).split('/') if p]
    start_parts = [p for p in abspath(start).split('/') if p]
    common = 0
    while common < min(len(path_parts), len(start_parts)) and path_parts[common] == start_parts[common]:
        common += 1
    return '/'.join(['..'] * (len(start_parts) - common) + path_parts[common:]) or '.'

def expanduser(path):
    import os
    path = _path(path)
    if path == '~' or path.startswith('~/'):
        home = os.getenv('HOME')
        return path if home is None else home.rstrip('/') + path[1:] or '/'
    return path

def expandvars(path):
    import os
    path = _path(path)
    out, i = [], 0
    while i < len(path):
        if path[i] != '$':
            out.append(path[i]); i += 1; continue
        start = i
        i += 1
        if i < len(path) and path[i] == '{':
            end = path.find('}', i + 1)
            if end < 0:
                out.append(path[start:]); break
            key, i = path[i + 1:end], end + 1
        else:
            end = i
            while end < len(path) and (path[end] in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_'):
                end += 1
            key, i = path[i:end], end
        out.append(os.getenv(key, path[start:i]))
    return ''.join(out)
