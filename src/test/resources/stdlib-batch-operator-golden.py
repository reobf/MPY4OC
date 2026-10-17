# Generated with CPython 3.12.14
import operator as o
cases = [('add', 7, 2, 9), ('sub', 7, 2, 5), ('mul', 7, 2, 14), ('truediv', 7, 2, 3.5), ('floordiv', 7, 2, 3), ('mod', 7, 2, 1), ('pow', 7, 2, 49), ('and_', 7, 2, 2), ('or_', 7, 2, 7), ('xor', 7, 2, 5), ('lshift', 7, 2, 28), ('rshift', 7, 2, 1), ('lt', 7, 2, False), ('le', 7, 2, False), ('eq', 7, 2, False), ('ne', 7, 2, True), ('ge', 7, 2, True), ('gt', 7, 2, True)]
for name, a, b, expected in cases:
    assert getattr(o, name)(a, b) == expected, name
assert o.itemgetter(1)([4, 5]) == 5
assert o.itemgetter('a', 'b')({'a': 3, 'b': 4}) == (3, 4)
assert sorted([('b', 2), ('c', 1)], key=o.itemgetter(1)) == [('c', 1), ('b', 2)]
assert o.methodcaller('replace', 'a', 'x')('aba') == 'xbx'
class Box:
    pass
b = Box()
b.child = Box()
b.child.value = 5
assert o.attrgetter('child.value')(b) == 5
assert o.countOf((x % 2 for x in range(7)), 0) == 4
assert o.indexOf((x for x in range(4)), 2) == 2
assert o.abs(-4) == 4
assert o.__add__(2, 3) == 5
a = [1]
assert o.iadd(a, [2]) is a and a == [1, 2]
assert o.length_hint([1, 2, 3]) == 3
assert o.index(True) == 1

passed = True
