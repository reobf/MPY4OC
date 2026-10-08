# frozen functools module: the commonly-used subset, in pure Python.
# reduce, partial, lru_cache, wraps/update_wrapper, cmp_to_key.

_sentinel = object()


def reduce(function, iterable, initializer=_sentinel):
    it = iter(iterable)
    if initializer is _sentinel:
        try:
            value = next(it)
        except StopIteration:
            raise TypeError("reduce() of empty iterable with no initial value")
    else:
        value = initializer
    for element in it:
        value = function(value, element)
    return value


class partial:
    def __init__(self, func, *args, **keywords):
        self.func = func
        self.args = args
        self.keywords = keywords

    def __call__(self, *args, **keywords):
        merged = dict(self.keywords)
        merged.update(keywords)
        return self.func(*(self.args + args), **merged)


# attributes copied by wraps/update_wrapper
WRAPPER_ASSIGNMENTS = ("__module__", "__name__", "__qualname__", "__doc__")


def update_wrapper(wrapper, wrapped):
    for attr in WRAPPER_ASSIGNMENTS:
        try:
            value = getattr(wrapped, attr)
        except AttributeError:
            pass
        else:
            try:
                setattr(wrapper, attr, value)
            except (AttributeError, TypeError):
                pass
    try:
        wrapper.__wrapped__ = wrapped
    except (AttributeError, TypeError):
        pass
    return wrapper


def wraps(wrapped):
    def deco(wrapper):
        return update_wrapper(wrapper, wrapped)
    return deco


def _make_key(args, kwds):
    # a hashable key from positional + keyword arguments
    key = args
    if kwds:
        key += (_sentinel,)
        for item in kwds.items():
            key += item
    return key


def lru_cache(maxsize=128):
    # Support both @lru_cache and @lru_cache(maxsize=...) / @lru_cache(None).
    if callable(maxsize):
        func = maxsize
        return _lru_wrap(func, 128)

    def deco(func):
        return _lru_wrap(func, maxsize)
    return deco


def _lru_wrap(func, maxsize):
    cache = {}
    order = []          # keys in least-recently-used-first order
    stats = [0, 0]      # hits, misses

    def wrapper(*args, **kwds):
        key = _make_key(args, kwds)
        if key in cache:
            stats[0] += 1
            if maxsize is not None:
                order.remove(key)
                order.append(key)
            return cache[key]
        stats[1] += 1
        result = func(*args, **kwds)
        cache[key] = result
        if maxsize is not None:
            order.append(key)
            if len(order) > maxsize:
                oldest = order.pop(0)
                del cache[oldest]
        return result

    def cache_clear():
        cache.clear()
        del order[:]
        stats[0] = 0
        stats[1] = 0

    def cache_info():
        return (stats[0], stats[1], maxsize, len(cache))

    wrapper.cache_clear = cache_clear
    wrapper.cache_info = cache_info
    wrapper.__wrapped__ = func
    return wrapper


class _CmpKey:
    def __init__(self, obj, mycmp):
        self.obj = obj
        self.mycmp = mycmp

    def __lt__(self, other):
        return self.mycmp(self.obj, other.obj) < 0

    def __gt__(self, other):
        return self.mycmp(self.obj, other.obj) > 0

    def __eq__(self, other):
        return self.mycmp(self.obj, other.obj) == 0

    def __le__(self, other):
        return self.mycmp(self.obj, other.obj) <= 0

    def __ge__(self, other):
        return self.mycmp(self.obj, other.obj) >= 0


def cmp_to_key(mycmp):
    def keyfn(obj):
        return _CmpKey(obj, mycmp)
    return keyfn
