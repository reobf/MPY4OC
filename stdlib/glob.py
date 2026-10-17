"""Lazy POSIX globbing on the virtual filesystem. No host filesystem access."""
import os
import posixpath as _path
import fnmatch

def escape(pathname):
    pathname = _path._path(pathname)
    return ''.join('[' + c + ']' if c in '*?[' else c for c in pathname)

def _magic(pattern):
    return any(c in pattern for c in '*?[')

def _names(path, include_hidden):
    try:
        names = os.listdir(path or '.')
    except OSError:
        return []
    return names if include_hidden else [n for n in names if not n.startswith('.')]

def _descendants(base, root, include_hidden):
    # Iterative DFS: nesting depth does not consume Python/Java call stacks.
    pending = [base]
    while pending:
        parent = pending.pop()
        for name in _names(_path.join(root, parent), include_hidden):
            child = _path.join(parent, name)
            yield child
            if _path.isdir(_path.join(root, child)):
                pending.append(child)

def _walk(base, parts, root, recursive, include_hidden):
    if not parts:
        if _path.exists(_path.join(root, base) or '.'):
            yield base
        return
    part, rest = parts[0], parts[1:]
    if not part:
        if base and not rest and _path.isdir(_path.join(root, base) or '.'):
            yield base if base.endswith('/') else base + '/'
        return
    if part == '**' and recursive:
        if not rest:
            if base and _path.isdir(_path.join(root, base)):
                yield base if base.endswith('/') else base + '/'
            yield from _descendants(base, root, include_hidden)
        else:
            yield from _walk(base, rest, root, recursive, include_hidden)
            for child in _descendants(base, root, include_hidden):
                if _path.isdir(_path.join(root, child)):
                    yield from _walk(child, rest, root, recursive, include_hidden)
        return
    if not _magic(part):
        yield from _walk(_path.join(base, part), rest, root, recursive, include_hidden)
        return
    for name in _names(_path.join(root, base), include_hidden or part.startswith('.')):
        if fnmatch.fnmatchcase(name, part):
            yield from _walk(_path.join(base, name), rest, root, recursive, include_hidden)

def iglob(pathname, *, root_dir=None, dir_fd=None, recursive=False, include_hidden=False):
    if dir_fd is not None:
        raise NotImplementedError('file descriptors are not supported')
    pathname = _path._path(pathname)
    root = '' if root_dir is None else _path._path(root_dir)
    if not pathname:
        return
    base = '//' if pathname.startswith('//') and not pathname.startswith('///') else '/' if pathname.startswith('/') else ''
    body = pathname[len(base):]
    parts = [p for p in body.split('/') if p]
    if pathname.endswith('/'):
        parts.append('')
    yield from _walk(base, parts, root, recursive, include_hidden)

def glob(pathname, *, root_dir=None, dir_fd=None, recursive=False, include_hidden=False):
    return list(iglob(pathname, root_dir=root_dir, dir_fd=dir_fd, recursive=recursive, include_hidden=include_hidden))
