"""Generate common-standard-library reference cases with CPython (test-only)."""
from pathlib import Path
import csv, io, operator, re, statistics, sys

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'src/test/resources'
DEST.mkdir(parents=True, exist_ok=True)
total = 0
def write(name, source, count):
    global total
    total += count
    (DEST / ('stdlib-batch-' + name + '-golden.py')).write_text('# Generated with CPython ' + sys.version.split()[0] + '\n' + source + '\npassed = True\n', encoding='utf-8')

cases = []
for pattern in (r'\d+', r'(\d+)', r'([a-z]+)?(\d+)', r'\s+', r'', r'x*', r'.*?', r'(?P<first_word>\w+)(?:-(?P=first_word))?', r'^a|b$', r'\b', r'(?<=a)b', r'(a)|(b)', r'a\Z'):
    for text in ('a12 b3', 'ab', '', 'a\nb\n', 'é_7-é_7'):
        for flags in (0, re.I | re.M):
            compiled = re.compile(pattern, flags)
            cases.append((pattern, text, int(flags), compiled.findall(text), compiled.split(text), compiled.subn('-', text), [(m.span(), m.groups()) for m in compiled.finditer(text)]))
write('re', 'import re\ncases = ' + repr(cases) + '''
for pattern, text, flags, found, split, replaced, spans in cases:
    p = re.compile(pattern, flags)
    assert p.findall(text) == found, ('findall', pattern, text, p.findall(text), found)
    assert p.split(text) == split, ('split', pattern, text)
    assert p.subn('-', text) == replaced, ('subn', pattern, text)
    assert [(m.span(), m.groups()) for m in p.finditer(text)] == spans, ('finditer', pattern, text)
assert re.fullmatch('a|ab', 'ab').group() == 'ab'
assert re.compile('^a').search('xa', 1) is None
assert re.compile('a$').search('xab', 1, 2).span() == (1, 2)
assert re.compile('(?<=x)a').match('xa', 1).span() == (1, 2)
assert re.findall('a.b', 'a\\nb', re.S) == ['a\\nb']
assert re.findall('a #ignored\\n b', 'ab', re.X) == ['ab']
assert re.findall(r'\\w+', 'é abc', re.A) == ['abc']
assert re.compile('(?i)abc').flags == re.I | re.U
assert re.escape('a+b [c].#') == r'a\\+b\\ \\[c\\]\\.\\#'
m = re.search(r'(?P<my_name>a)(b)?', 'a')
assert m.group(0, 'my_name', 2) == ('a', 'a', None)
assert m.groupdict('missing') == {'my_name': 'a'}
assert m.groups('missing') == ('a', 'missing')
assert m.expand(r'<\\g<my_name>>\\2\\n') == '<a>\\n'
assert re.sub(r'(a)', lambda m: m[1].upper(), 'aba', count=1) == 'Aba'
''', len(cases) * 4 + 14)

reads = []
for text in ('a,b\r\n1,2\r\n', '"a,b",c\n"x\ny",z', '\n\r\n', ',,\n', '"a""b",c', 'a, b, c\n', 'one\t"two\tthree"\n', '\"a,b', 'a,b\rc,d\r', 'a\\,b,c', '"a"x,b', 'a\\\nb,c\n'):
    for kw in ({}, {'delimiter': '\t'}, {'skipinitialspace': True}, {'escapechar': '\\'}, {'doublequote': False, 'escapechar': '\\'}, {'quoting': csv.QUOTE_NONE, 'escapechar': '\\'}):
        lines = list(io.StringIO(text, newline=''))
        reads.append((lines, kw, list(csv.reader(lines, **kw))))
writes = []
rows = [['a,b', 'x"y', 'line\nbreak', None], ['a\\b', '', 12, 1.5], [], [''], ['a', 'b']]
for row in rows:
    for kw in ({}, {'delimiter': ';', 'lineterminator': '\n'}, {'quoting': 1}, {'quoting': 2}, {'doublequote': False, 'escapechar': '\\'}, {'quoting': 3, 'escapechar': '\\'}):
        stream = io.StringIO(newline='')
        try:
            written = csv.writer(stream, **kw).writerow(row)
            writes.append((row, kw, stream.getvalue(), written, False))
        except csv.Error:
            writes.append((row, kw, '', 0, True))
write('csv', 'import csv\nfrom io import StringIO\nreads = ' + repr(reads) + '\nwrites = ' + repr(writes) + '''
for lines, kw, expected in reads:
    assert list(csv.reader(lines, **kw)) == expected, (lines, kw)
for row, kw, expected, length, fails in writes:
    stream = StringIO()
    try:
        actual_length = csv.writer(stream, **kw).writerow(row)
    except csv.Error:
        assert fails, (row, kw)
    else:
        assert not fails, (row, kw)
        assert stream.getvalue() == expected and actual_length == length, (row, kw, stream.getvalue(), expected)
rows = list(csv.DictReader(['name,note\\n', 'a,"one\\ntwo"\\n', 'b,ok,extra\\n', 'c\\n'], restkey='extra', restval='missing'))
assert rows == [{'name': 'a', 'note': 'one\\ntwo'}, {'name': 'b', 'note': 'ok', 'extra': ['extra']}, {'name': 'c', 'note': 'missing'}]
s = StringIO()
w = csv.DictWriter(s, ['a', 'b'], restval='?', extrasaction='ignore')
w.writeheader()
w.writerows([{'a': 1}, {'a': 2, 'b': 3, 'ignored': 4}])
assert s.getvalue() == 'a,b\\r\\n1,?\\r\\n2,3\\r\\n'
''', len(reads) + len(writes) + 2)

cases = []
for data in ([1], [1, 2], [1, 1, 2, 3, 7], [1.25, 2.5, 10.0, -4], [10**12 + i for i in (1, 3, 4, 8)], [1e16, 1.0, -1e16]):
    for name in ('mean', 'fmean', 'median', 'median_low', 'median_high', 'pvariance', 'pstdev', 'variance', 'stdev'):
        if len(data) == 1 and name in ('variance', 'stdev'):
            continue
        cases.append((name, data, getattr(statistics, name)(data)))
write('statistics', 'import statistics as s\ncases = ' + repr(cases) + '''
for name, data, expected in cases:
    actual = getattr(s, name)(x for x in data)
    assert abs(actual - expected) <= max(1e-12, abs(expected) * 1e-12), (name, data, actual, expected)
assert s.mode(['a', 'b', 'b', 'a']) == 'a'
assert s.multimode(['a', 'b', 'b', 'a', 'c']) == ['a', 'b']
assert s.multimode([]) == []
assert s.quantiles([1, 2, 3, 4, 5]) == [1.5, 3.0, 4.5]
assert s.quantiles([1, 2, 3, 4, 5], method='inclusive') == [2.0, 3.0, 4.0]
assert s.fmean([10, 20, 30], [1, 2, 1]) == 20.0
assert abs(s.geometric_mean([1, 4, 16]) - 4) < 1e-12
assert abs(s.harmonic_mean([1, 2, 4]) - 12 / 7) < 1e-12
assert s.harmonic_mean([0, 2, 4]) == 0
''', len(cases) + 9)

cases = []
for name in ('add', 'sub', 'mul', 'truediv', 'floordiv', 'mod', 'pow', 'and_', 'or_', 'xor', 'lshift', 'rshift', 'lt', 'le', 'eq', 'ne', 'ge', 'gt'):
    cases.append((name, 7, 2, getattr(operator, name)(7, 2)))
write('operator', 'import operator as o\ncases = ' + repr(cases) + '''
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
''', len(cases) + 12)
print('Wrote', total, 'CPython comparisons')
