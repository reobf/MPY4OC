"""Common operator functions and serializable field/method extractors."""
def add(a, b):
    return a + b

__add__ = add

def sub(a, b):
    return a - b

__sub__ = sub

def mul(a, b):
    return a * b

__mul__ = mul

def matmul(a, b):
    return a @ b

__matmul__ = matmul

def truediv(a, b):
    return a / b

__truediv__ = truediv

def floordiv(a, b):
    return a // b

__floordiv__ = floordiv

def mod(a, b):
    return a % b

__mod__ = mod

def pow(a, b):
    return a ** b

__pow__ = pow

def and_(a, b):
    return a & b

__and__ = and_

def or_(a, b):
    return a | b

__or__ = or_

def xor(a, b):
    return a ^ b

__xor__ = xor

def lshift(a, b):
    return a << b

__lshift__ = lshift

def rshift(a, b):
    return a >> b

__rshift__ = rshift

def lt(a, b):
    return a < b

__lt__ = lt

def le(a, b):
    return a <= b

__le__ = le

def eq(a, b):
    return a == b

__eq__ = eq

def ne(a, b):
    return a != b

__ne__ = ne

def ge(a, b):
    return a >= b

__ge__ = ge

def gt(a, b):
    return a > b

__gt__ = gt

def is_(a, b):
    return a is b

__is__ = is_

def is_not(a, b):
    return a is not b

__is_not__ = is_not

def concat(a, b):
    if not isinstance(a, (str, bytes, list, tuple)) and not hasattr(a, '__getitem__'):
        raise TypeError('object cannot be concatenated')
    return a + b

__concat__ = concat

def neg(a):
    return -a

__neg__ = neg

def pos(a):
    return +a

__pos__ = pos

def not_(a):
    return not a

__not__ = not_

def truth(a):
    return bool(a)

__truth__ = truth

_abs = abs
def abs(a):
    return _abs(a)

__abs__ = abs

def invert(a):
    return ~a

__invert__ = invert

def contains(a, b):
    return b in a

def getitem(a, b):
    return a[b]

__contains__ = contains
__getitem__ = getitem
def isub(a, b):
    return _inplace('__isub__', a, b, sub)

__isub__ = isub

def imatmul(a, b):
    return _inplace('__imatmul__', a, b, matmul)

__imatmul__ = imatmul

def itruediv(a, b):
    return _inplace('__itruediv__', a, b, truediv)

__itruediv__ = itruediv

def ifloordiv(a, b):
    return _inplace('__ifloordiv__', a, b, floordiv)

__ifloordiv__ = ifloordiv

def imod(a, b):
    return _inplace('__imod__', a, b, mod)

__imod__ = imod

def ipow(a, b):
    return _inplace('__ipow__', a, b, pow)

__ipow__ = ipow

def iand(a, b):
    return _inplace('__iand__', a, b, and_)

__iand__ = iand

def ior(a, b):
    return _inplace('__ior__', a, b, or_)

__ior__ = ior

def ixor(a, b):
    return _inplace('__ixor__', a, b, xor)

__ixor__ = ixor

def ilshift(a, b):
    return _inplace('__ilshift__', a, b, lshift)

__ilshift__ = ilshift

def irshift(a, b):
    return _inplace('__irshift__', a, b, rshift)

__irshift__ = irshift


"""Standard operations and serializable field/method extractors."""

class itemgetter:
    def __init__(self, *items):
        if not items:
            raise TypeError('itemgetter expected 1 argument, got 0')
        self._items = items

    def __call__(self, obj):
        if len(self._items) == 1:
            return obj[self._items[0]]
        return tuple(obj[item] for item in self._items)

class attrgetter:
    def __init__(self, *attrs):
        if not attrs:
            raise TypeError('attrgetter expected 1 argument, got 0')
        if any(not isinstance(name, str) for name in attrs):
            raise TypeError('attribute name must be a string')
        self._attrs = [name.split('.') for name in attrs]

    def _get(self, obj, path):
        for name in path:
            obj = getattr(obj, name)
        return obj

    def __call__(self, obj):
        if len(self._attrs) == 1:
            return self._get(obj, self._attrs[0])
        return tuple(self._get(obj, path) for path in self._attrs)

class methodcaller:
    def __init__(self, name, *args, **kwargs):
        if not isinstance(name, str):
            raise TypeError('method name must be a string')
        self._name, self._args, self._kwargs = name, args, kwargs

    def __call__(self, obj):
        return getattr(obj, self._name)(*self._args, **self._kwargs)

def index(a):
    if isinstance(a, int):
        return int(a)
    if isinstance(a, bool):
        return 1 if a else 0
    method = getattr(a, '__index__', None)
    if method is None:
        raise TypeError('object cannot be interpreted as an integer')
    result = method()
    if not isinstance(result, (int, bool)):
        raise TypeError('__index__ returned non-int')
    return int(result)

def countOf(a, b):
    count = 0
    for item in a:
        if item == b:
            count += 1
    return count

def indexOf(a, b):
    count = 0
    for item in a:
        if item == b:
            return count
        count += 1
    raise ValueError('sequence.index(x): x not in sequence')

def length_hint(obj, default=0):
    default = index(default)
    try:
        return len(obj)
    except TypeError:
        pass
    method = getattr(obj, '__length_hint__', None)
    if method is None:
        return default
    result = method()
    if not isinstance(result, (int, bool)):
        raise TypeError('__length_hint__ must be an integer')
    if result < 0:
        raise ValueError('__length_hint__() should return >= 0')
    return int(result)

def call(obj, *args, **kwargs):
    return obj(*args, **kwargs)

def setitem(obj, key, value):
    obj[key] = value

def delitem(obj, key):
    del obj[key]

def _inplace(name, a, b, operation):
    method = getattr(a, name, None)
    if method is not None:
        return method(b)
    result = operation(a, b)
    if isinstance(a, set):
        a.clear()
        a.update(result)
        return a
    return result

def iadd(a, b):
    method = getattr(a, '__iadd__', None)
    if method is not None:
        return method(b)
    if isinstance(a, list):
        for item in b:
            a.append(item)
        return a
    a += b
    return a

def imul(a, b):
    method = getattr(a, '__imul__', None)
    if method is not None:
        return method(b)
    if isinstance(a, list):
        a[:] = a * index(b)
        return a
    a *= b
    return a

def iconcat(a, b):
    if not isinstance(a, (str, bytes, list, tuple)) and not hasattr(a, '__getitem__'):
        raise TypeError('object cannot be concatenated')
    return iadd(a, b)
inv = invert
__index__ = index
__setitem__ = setitem
__delitem__ = delitem
__iadd__ = iadd
__imul__ = imul
__iconcat__ = iconcat
__inv__ = inv
