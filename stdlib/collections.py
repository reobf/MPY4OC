# frozen collections: namedtuple (VM-assisted) + a pure-Python deque.

def namedtuple(name, fields):
    if isinstance(fields, str):
        fields = fields.split()
    return __namedtuple(name, list(fields))

class deque:
    def __init__(self, iterable=(), maxlen=None, flags=0):
        self._items = list(iterable)
        self._maxlen = maxlen
        self._flags = flags
        if maxlen is not None and len(self._items) > maxlen:
            self._items = self._items[len(self._items) - maxlen:]

    def append(self, x):
        self._items.append(x)
        if self._maxlen is not None and len(self._items) > self._maxlen:
            if self._flags & 1:
                raise IndexError("full")
            self._items.pop(0)

    def appendleft(self, x):
        self._items.insert(0, x)
        if self._maxlen is not None and len(self._items) > self._maxlen:
            if self._flags & 1:
                raise IndexError("full")
            self._items.pop()

    def pop(self):
        if not self._items:
            raise IndexError("empty")
        return self._items.pop()

    def popleft(self):
        if not self._items:
            raise IndexError("empty")
        return self._items.pop(0)

    def extend(self, iterable):
        for x in iterable:
            self.append(x)

    def __len__(self):
        return len(self._items)

    def __bool__(self):
        return len(self._items) > 0


def _mapping_update(target, source):
    if hasattr(source, 'keys'):
        for key in source.keys():
            target[key] = source[key]
    else:
        for key, value in source:
            target[key] = value


class defaultdict(dict):
    def __init__(self, default_factory=None, *args, **kwargs):
        if default_factory is not None and not callable(default_factory):
            raise TypeError('first argument must be callable or None')
        if len(args) > 1:
            raise TypeError('dict expected at most 1 argument')
        self.default_factory = default_factory
        if args:
            _mapping_update(self, args[0])
        _mapping_update(self, kwargs)

    def __missing__(self, key):
        if self.default_factory is None:
            raise KeyError(key)
        value = self.default_factory()
        self[key] = value
        return value

    def copy(self):
        return type(self)(self.default_factory, self)

    __copy__ = copy

    def __repr__(self):
        return 'defaultdict(' + repr(self.default_factory) + ', ' + repr(dict(self)) + ')'

    def __or__(self, other):
        if not isinstance(other, dict):
            raise TypeError('dict union requires a dict')
        result = self.copy()
        _mapping_update(result, other)
        return result

    def __ror__(self, other):
        result = type(self)(self.default_factory, other)
        _mapping_update(result, self)
        return result

    def __ior__(self, other):
        _mapping_update(self, other)
        return self


class Counter(dict):
    def __init__(self, iterable=None, **kwargs):
        self.update(iterable, **kwargs)

    def __missing__(self, key):
        return 0

    def update(self, iterable=None, **kwargs):
        if iterable is not None:
            if hasattr(iterable, 'keys'):
                for key in iterable.keys():
                    self[key] = self.get(key, 0) + iterable[key]
            else:
                for key in iterable:
                    self[key] = self.get(key, 0) + 1
        for key in kwargs:
            self[key] = self.get(key, 0) + kwargs[key]

    def subtract(self, iterable=None, **kwargs):
        if iterable is not None:
            if hasattr(iterable, 'keys'):
                for key in iterable.keys():
                    self[key] = self.get(key, 0) - iterable[key]
            else:
                for key in iterable:
                    self[key] = self.get(key, 0) - 1
        for key in kwargs:
            self[key] = self.get(key, 0) - kwargs[key]

    def total(self):
        return sum(self.values())

    def most_common(self, n=None):
        result = sorted(self.items(), key=lambda pair: pair[1], reverse=True)
        if n is None:
            return result
        import operator
        return result[:max(0, operator.index(n))]

    def elements(self):
        import operator
        for key, count in self.items():
            for i in range(operator.index(count)):
                yield key

    def copy(self):
        return type(self)(self)

    __copy__ = copy

    @classmethod
    def fromkeys(cls, iterable, value=None):
        raise NotImplementedError('Counter.fromkeys() is undefined; use Counter(iterable) instead')

    def __repr__(self):
        return 'Counter(' + (repr(dict(self.most_common())) if self else '') + ')'

    def __eq__(self, other):
        if not isinstance(other, Counter):
            return dict(self) == other
        return all(self.get(k, 0) == other.get(k, 0) for k in list(self) + list(other))

    def __le__(self, other):
        if not isinstance(other, Counter):
            raise TypeError('Counter comparison requires a Counter')
        return all(self.get(k, 0) <= other.get(k, 0) for k in list(self) + list(other))

    def __ne__(self, other):
        return not self == other

    def __lt__(self, other):
        return self <= other and self != other

    def __ge__(self, other):
        return other <= self

    def __gt__(self, other):
        return other < self

    def _combine(self, other, op):
        if not isinstance(other, Counter):
            raise TypeError('Counter arithmetic requires a Counter')
        result = Counter()
        keys = list(self) + [k for k in other if k not in self]
        for key in keys:
            a, b = self.get(key, 0), other.get(key, 0)
            value = a + b if op == '+' else a - b if op == '-' else max(a, b) if op == '|' else min(a, b)
            if value > 0:
                result[key] = value
        return result

    def __add__(self, other):
        return self._combine(other, '+')

    def __sub__(self, other):
        return self._combine(other, '-')

    def __or__(self, other):
        return self._combine(other, '|')

    def __and__(self, other):
        return self._combine(other, '&')

    def __pos__(self):
        return Counter({k: v for k, v in self.items() if v > 0})

    def __neg__(self):
        return Counter({k: -v for k, v in self.items() if v < 0})

    def _inplace(self, other, op):
        result = self._combine(other, op)
        self.clear()
        for key in result:
            self[key] = result[key]
        return self

    def __iadd__(self, other):
        return self._inplace(other, '+')

    def __isub__(self, other):
        return self._inplace(other, '-')

    def __ior__(self, other):
        return self._inplace(other, '|')

    def __iand__(self, other):
        return self._inplace(other, '&')
