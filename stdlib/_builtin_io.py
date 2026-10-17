"""Default-free builtin entry points installed directly as resumable VM frames.

Imports run inside calls. Helpers are installed in this module's namespace only.
"""

def open(*args, **kwargs):
    import io
    return io.open(*args, **kwargs)

def input(*args):
    import io
    return io._input(*args)

def _extreme(want_min, args, kwargs):
    name = 'min' if want_min else 'max'
    if not args:
        raise TypeError(name + ' expected at least 1 argument, got 0')
    for k in kwargs:
        if k not in ('key', 'default'):
            raise TypeError(name + "() got an unexpected keyword argument '" + k + "'")
    if len(args) > 1 and 'default' in kwargs:
        raise TypeError('Cannot specify a default for ' + name + '() with multiple positional arguments')
    key = kwargs.get('key')
    first = True
    for item in args[0] if len(args) == 1 else args:
        value = item if key is None else key(item)
        if first or (value < best_key if want_min else value > best_key):
            best, best_key, first = item, value, False
    if first:
        if 'default' in kwargs:
            return kwargs['default']
        raise ValueError(name + '() arg is an empty sequence')
    return best

def _min(*args, **kwargs):
    return _extreme(True, args, kwargs)

def _max(*args, **kwargs):
    return _extreme(False, args, kwargs)

def _all(*args, **kwargs):
    if kwargs or len(args) != 1:
        raise TypeError('all() takes exactly one positional argument')
    for item in args[0]:
        if not item:
            return False
    return True

def _any(*args, **kwargs):
    if kwargs or len(args) != 1:
        raise TypeError('any() takes exactly one positional argument')
    for item in args[0]:
        if item:
            return True
    return False

def next(*args, **kwargs):
    if kwargs or len(args) < 1 or len(args) > 2:
        raise TypeError('next() takes one or two positional arguments')
    try:
        return _next_raw(args[0])
    except StopIteration:
        if len(args) == 2:
            return args[1]
        raise

def _iterate(iterator):
    while True:
        try:
            value = _next_raw(iterator)
        except StopIteration:
            return
        yield value

def _iter_callable(function, sentinel):
    while True:
        value = function()
        if value == sentinel:
            return
        yield value

def _iter(*args, **kwargs):
    if kwargs or len(args) < 1 or len(args) > 2:
        raise TypeError('iter() takes one or two positional arguments')
    if len(args) == 2:
        if not callable(args[0]):
            raise TypeError('iter(v, w): v must be callable')
        return _iter_callable(args[0], args[1])
    method = getattr(args[0], '__iter__', None)
    if method is not None:
        return method()
    return __iter_native(args[0])
