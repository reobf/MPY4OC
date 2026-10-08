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
