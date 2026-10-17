"""Lazy iterator tools. State lives in Python frames and survives VM snapshots."""
import operator

def _chain(iterables):
    for iterable in iterables:
        yield from iterable

def chain(*iterables):
    return _chain(iterables)

chain.from_iterable = _chain

def count(start=0, step=1):
    if not isinstance(start, (int, float, bool)) or not isinstance(step, (int, float, bool)):
        raise TypeError('a number is required')
    return _count(start, step)

def _count(value, step):
    while True:
        yield value
        value += step

def cycle(iterable):
    saved = []
    for value in iterable:
        saved.append(value)
        yield value
    while saved:
        yield from saved

def repeat(object, times=None):
    return _repeat(object, None if times is None else operator.index(times))

def _repeat(value, times):
    if times is None:
        while True:
            yield value
    else:
        for i in range(times):
            yield value

def islice(iterable, *args):
    if not 1 <= len(args) <= 3:
        raise TypeError('islice expected 2 to 4 arguments')
    start, stop, step = (0, args[0], 1) if len(args) == 1 else (args[0], args[1], args[2] if len(args) == 3 else 1)
    start = 0 if start is None else operator.index(start)
    stop = None if stop is None else operator.index(stop)
    step = 1 if step is None else operator.index(step)
    if start < 0 or stop is not None and stop < 0 or step <= 0:
        raise ValueError('islice indices must be non-negative and step positive')
    return _islice(iter(iterable), start, stop, step)

def _islice(iterator, start, stop, step):
    index, target = 0, start
    limit = None if stop is None else max(start, stop)
    while limit is None or index < limit:
        try:
            value = next(iterator)
        except StopIteration:
            return
        if index == target and (stop is None or index < stop):
            yield value
            target += step
        index += 1

def takewhile(predicate, iterable):
    for value in iterable:
        if not predicate(value):
            return
        yield value

def dropwhile(predicate, iterable):
    dropping = True
    for value in iterable:
        if dropping and predicate(value):
            continue
        dropping = False
        yield value

def filterfalse(predicate, iterable):
    for value in iterable:
        if not (value if predicate is None else predicate(value)):
            yield value

def compress(data, selectors):
    data, selectors = iter(data), iter(selectors)
    while True:
        try:
            value, selected = next(data), next(selectors)
        except StopIteration:
            return
        if selected:
            yield value

def starmap(function, iterable):
    for args in iterable:
        yield function(*args)

def accumulate(iterable, func=None, *, initial=None):
    iterator = iter(iterable)
    total = initial
    if total is None:
        try:
            total = next(iterator)
        except StopIteration:
            return
    yield total
    for value in iterator:
        total = total + value if func is None else func(total, value)
        yield total

def pairwise(iterable):
    iterator = iter(iterable)
    try:
        previous = next(iterator)
    except StopIteration:
        return
    for value in iterator:
        yield previous, value
        previous = value

def batched(iterable, n, *, strict=False):
    n = operator.index(n)
    if n < 1:
        raise ValueError('n must be at least one')
    return _batched(iter(iterable), n, strict)

def _batched(iterator, n, strict):
    while True:
        batch = tuple(islice(iterator, n))
        if not batch:
            return
        if strict and len(batch) != n:
            raise ValueError('batched(): incomplete batch')
        yield batch

def zip_longest(*iterables, fillvalue=None):
    iterators = [iter(x) for x in iterables]
    remaining = len(iterators)
    while remaining:
        row = []
        for i in range(len(iterators)):
            if iterators[i] is None:
                row.append(fillvalue)
            else:
                try:
                    row.append(next(iterators[i]))
                except StopIteration:
                    iterators[i] = None
                    remaining -= 1
                    if not remaining:
                        return
                    row.append(fillvalue)
        yield tuple(row)

class _Tee:
    def __init__(self, source, node):
        self._source, self._node = source, node
    def __iter__(self):
        return self
    def __next__(self):
        node = self._node
        if not node[0]:
            if self._source[1]:
                raise RuntimeError('cannot re-enter the tee iterator')
            self._source[1] = True
            try:
                node[1] = next(self._source[0])
                node[2] = [False, None, None]
                node[0] = True
            finally:
                self._source[1] = False
        self._node = node[2]
        return node[1]

def tee(iterable, n=2):
    n = operator.index(n)
    if n < 0:
        raise ValueError('n must be >= 0')
    if isinstance(iterable, _Tee):
        source, node = iterable._source, iterable._node
    else:
        source, node = [iter(iterable), False], [False, None, None]
    return tuple(_Tee(source, node) for i in range(n))

class groupby:
    def __init__(self, iterable, key=None):
        self._source, self._keyfunc = iter(iterable), key
        self._ready, self._started, self._token = False, False, 0
        self._key, self._target = None, None
    def __iter__(self):
        return self
    def _advance(self):
        try:
            self._value = next(self._source)
        except StopIteration:
            self._ready = False
            return
        self._key = self._value if self._keyfunc is None else self._keyfunc(self._value)
        self._ready = True
    def __next__(self):
        if not self._started:
            self._started = True
            self._advance()
        else:
            while self._ready and self._key == self._target:
                self._advance()
        if not self._ready:
            raise StopIteration
        self._token += 1
        self._target = self._key
        return self._key, self._group(self._token, self._key)
    def _group(self, token, key):
        while token == self._token and self._ready and self._key == key:
            yield self._value
            if token != self._token:
                return
            self._advance()

def product(*iterables, repeat=1):
    repeat = operator.index(repeat)
    if repeat < 0:
        raise ValueError('repeat argument cannot be negative')
    return _product([tuple(x) for x in iterables] * repeat)

def _product(pools):
    if any(not pool for pool in pools):
        return
    indices = [0] * len(pools)
    while True:
        yield tuple(pools[i][indices[i]] for i in range(len(pools)))
        i = len(indices) - 1
        while i >= 0 and indices[i] + 1 == len(pools[i]):
            indices[i] = 0
            i -= 1
        if i < 0:
            return
        indices[i] += 1

def permutations(iterable, r=None):
    pool = tuple(iterable)
    r = len(pool) if r is None else operator.index(r)
    if r < 0:
        raise ValueError('r must be non-negative')
    return _permutations(pool, r)

def _permutations(pool, r):
    if r == 0:
        yield ()
        return
    if r > len(pool):
        return
    indices, used, depth = [-1] * r, [False] * len(pool), 0
    while depth >= 0:
        candidate = indices[depth]
        if candidate >= 0:
            used[candidate] = False
        candidate += 1
        while candidate < len(pool) and used[candidate]:
            candidate += 1
        if candidate == len(pool):
            indices[depth] = -1
            depth -= 1
        else:
            indices[depth] = candidate
            used[candidate] = True
            if depth == r - 1:
                yield tuple(pool[i] for i in indices)
            else:
                depth += 1

def combinations(iterable, r):
    r = operator.index(r)
    if r < 0:
        raise ValueError('r must be non-negative')
    return _combinations(tuple(iterable), r, False)

def combinations_with_replacement(iterable, r):
    r = operator.index(r)
    if r < 0:
        raise ValueError('r must be non-negative')
    return _combinations(tuple(iterable), r, True)

def _combinations(pool, r, replacement):
    n = len(pool)
    if r and (not n or not replacement and r > n):
        return
    indices = [0] * r if replacement else list(range(r))
    while True:
        yield tuple(pool[i] for i in indices)
        i = r - 1
        while i >= 0 and indices[i] == (n - 1 if replacement else n - r + i):
            i -= 1
        if i < 0:
            return
        indices[i] += 1
        for j in range(i + 1, r):
            indices[j] = indices[j - 1] if replacement else indices[j - 1] + 1
