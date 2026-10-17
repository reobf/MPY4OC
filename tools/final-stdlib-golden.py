"""Generate independent CPython expectations for the remaining common libraries."""
from pathlib import Path
import sys
import itertools as it
from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta, timezone
import argparse, logging, io

out = Path(__file__).resolve().parents[1] / 'src/test/resources'
def _utc_timestamp(seconds, tz):
    return datetime(1970,1,1,tzinfo=timezone.utc) + timedelta(seconds=seconds)

text = []
count = 0
def start(imports):
    global text
    text = ['# CPython ' + sys.version.split()[0], imports]
def case(expr):
    global count
    try:
        value = eval(expr, globals())
    except OSError:
        value = eval(expr.replace('datetime.fromtimestamp(', '_utc_timestamp('), globals())
    if isinstance(value, tuple):
        value = tuple(value)
    text.append('actual = ' + expr)
    text.append('assert actual == ' + repr(value) + ', ' + repr(expr) + ' + ' + repr(': ') + ' + repr(actual)')
    count += 1
def finish(name):
    (out / ('stdlib-final-' + name + '-golden.py')).write_text('\n'.join(text + ['passed = True']) + '\n', encoding='utf8')

start('import itertools as it')
for pool in [[], [1], [1, 2, 3], ['a', 'a', 'b']]:
    for r in range(5):
        for fn in ('permutations', 'combinations', 'combinations_with_replacement'):
            case(f'list(it.{fn}({pool!r}, {r}))')
    for repeat in range(3):
        case(f'list(it.product({pool!r}, repeat={repeat}))')
for expr in ["list(it.chain('ab', [], 'cd'))", "list(it.chain.from_iterable(['ab', '', 'cd']))", "list(it.islice(it.count(10, -2), 6))", "list(it.islice(it.cycle('abc'), 8))", "list(it.repeat('x', 3))", "list(it.repeat('x', -1))", "list(it.accumulate([1,2,3], initial=10))", "list(it.accumulate([1,2,3], lambda a,b:a*b))", "list(it.accumulate([]))", "list(it.pairwise('abcd'))", "list(it.batched('abcde', 2))", "list(it.zip_longest('ab', '123', fillvalue='x'))", "list(it.zip_longest([], []))", "list(it.compress('ABCDE', [1,0,1,0]))", "list(it.starmap(pow, [(2,3),(3,2)]))", "list(it.takewhile(lambda n:n<3, [1,2,3,1]))", "list(it.dropwhile(lambda n:n<3, [1,2,3,1]))", "list(it.filterfalse(None, [0,1,False,2]))", "[(k,list(v)) for k,v in it.groupby('AAABBCCAA')]", "[(k,list(v)) for k,v in it.groupby([1,3,2,4,5], lambda x:x%2)]"]:
    case(expr)
for start_ in [0, 2, 6]:
    for stop in [0, 3, 9, None]:
        for step in [1, 2, 3]:
            case(f'list(it.islice(range(8), {start_}, {stop!r}, {step}))')
finish('itertools')

start('from collections import Counter, defaultdict')
for values in [[], 'abracadabra', {'a':3, 'b':0, 'c':-2}]:
    expr = f'Counter({values!r})'
    for method in ['total()', 'most_common()', 'most_common(2)', 'most_common(-1)']:
        case(expr + '.' + method)
    case('list(' + expr + '.elements())')
    case('dict(' + expr + ')')
    case('dict(+' + expr + ')')
    case('dict(-' + expr + ')')
    for other in [{'a':1, 'd':2}, {'a':0}, {}]:
        for op in ['+', '-', '|', '&']:
            case('dict(' + expr + ' ' + op + ' Counter(' + repr(other) + '))')
        for op in ['==', '<=', '<', '>=', '>']:
            case(expr + ' ' + op + ' Counter(' + repr(other) + ')')
case("Counter(a=1) == Counter(a=1,b=0)")
case("dict(defaultdict(list, {'a': [1]}).copy())")
finish('collections')

start('from datetime import date, datetime, time, timedelta, timezone')
for y,m,d in [(1,1,1),(100,3,1),(400,2,29),(1900,3,1),(1969,12,31),(1970,1,1),(2000,2,29),(2020,12,31),(2021,1,1),(2024,2,29),(9999,12,31)]:
    expr = f'date({y},{m},{d})'
    for method in ['isoformat()', 'toordinal()', 'weekday()', 'isoweekday()', 'isocalendar()']:
        case(expr+'.'+method)
    case(f'str(date.fromordinal({date(y,m,d).toordinal()}))')
    if 1 < y < 9999:
        for days in [-367,-1,0,1,367]:
            case(f'str({expr}+timedelta(days={days}))')
for us in [-86400000001,-1000001,-1,0,1,1234567,86400000001]:
    expr = f'timedelta(microseconds={us})'
    for method in ['days','seconds','microseconds','total_seconds()']:
        case(expr+'.'+method)
    for prefix in ['str','repr','bool']:
        case(prefix+'('+expr+')')
    case(f'str({expr} * 3)')
    case(f'str({expr} // 3)')
    case(f'str({expr} / 2)')
for raw in ['2024-02-29','20240229','2020-W53-7','2020W537']:
    case(f'str(date.fromisoformat({raw!r}))')
for raw in ['00','12:34','123456','T12:34:56.123456','12:34:56+08:00','12:34:56Z','12:34:56.123456789-03:30']:
    case(f'time.fromisoformat({raw!r}).isoformat()')
for raw in ['2024-02-29','2024-02-29T12:34:56.123456+08:00','20240229 123456','1970-01-01T00:00:00Z']:
    expr = f'datetime.fromisoformat({raw!r})'
    case(expr+'.isoformat()')
    case(expr+'.isoformat(" ", "milliseconds")')
    case('str('+expr+'.date())')
    case('str('+expr+'+timedelta(days=2,seconds=60))')
    if '+' in raw or raw.endswith('Z'):
        case(expr+'.timestamp()')
        case(expr+'.astimezone(timezone.utc).isoformat()')
for seconds in [-86400.123456,-1.5,0,1.123456,1709200000.5]:
    case(f'datetime.fromtimestamp({seconds}, timezone.utc).isoformat()')
for fmt in ['%Y-%m-%d %H:%M:%S.%f %z %Z','%a %A %b %B %j %w %u %G %V','%I:%M %p %%']:
    case(f'datetime(2024,2,29,13,4,5,123456,timezone(timedelta(hours=8))).strftime({fmt!r})')
for raw,fmt in [('2024-02-29 13:04:05.123 +0800','%Y-%m-%d %H:%M:%S.%f %z'),('Feb 29 24 01:04 PM','%b %d %y %I:%M %p'),('1969-12-31','%Y-%m-%d')]:
    case(f'datetime.strptime({raw!r}, {fmt!r}).isoformat()')
finish('datetime')

start('import argparse')
setup = """p = argparse.ArgumentParser(prog='demo')
p.add_argument('-v', '--verbose', action='count', default=0)
p.add_argument('--number', type=int, default='4')
p.add_argument('--tag', action='append', default=['base'])
p.add_argument('--flag', action=argparse.BooleanOptionalAction)
p.add_argument('--mode', choices=['a','b'], default='a')
p.add_argument('files', nargs='*')
"""
exec(setup, globals())
text.append(setup)
for args in [[], ['-vv','a','b'], ['--number=-3','x'], ['--tag','one','--tag','two'], ['--flag','--mode=b'], ['--no-flag','--num','9'], ['--','-odd','--flag'], ['a','--flag','b']]:
    # Interspersed optional + trailing star positional is parse_known_args in CPython.
    ns, extras = p.parse_known_args(args)
    text.append(f'n, extras = p.parse_known_args({args!r})')
    text.append(f'assert n.__dict__ == {vars(ns)!r}, repr(n.__dict__)')
    text.append(f'assert extras == {extras!r}, {args!r}')
    count += 2
setup = """q = argparse.ArgumentParser(prog='jobs')
subs = q.add_subparsers(dest='command', required=True)
run = subs.add_parser('run', aliases=['r'])
run.add_argument('script')
run.add_argument('--force', action='store_true')
run.set_defaults(handler='run')
"""
exec(setup, globals())
text.append(setup)
for args in [['run','a.py'],['r','a.py','--force']]:
    case(f'q.parse_args({args!r}).__dict__')
finish('argparse')

start('import logging\nfrom io import StringIO')
setup = """stream = StringIO()
logging.basicConfig(stream=stream, level=logging.INFO, format='%(levelname)s:%(name)s:%(message)s', force=True)
logger = logging.getLogger('test.child')
logger.debug('hidden')
logger.info('value=%s', 3)
logger.warning('%(x)s', {'x':'mapping'})
parent = logging.getLogger('test')
parent.setLevel(logging.ERROR)
logger.warning('hidden')
logger.error('failure')
parent.setLevel(logging.DEBUG)
handler = logging.StreamHandler(stream)
handler.setFormatter(logging.Formatter('{levelname}:{name}:{message}:{job}', style='{', defaults={'job':'-'}))
handler.addFilter(logging.Filter('test.child'))
logger.addHandler(handler)
logger.propagate = False
logging.LoggerAdapter(logger, {'job':42}).info('ok')
logger.info('plain')
logging.disable(logging.CRITICAL)
logger.critical('hidden')
logging.disable(logging.NOTSET)
"""
from io import StringIO
exec(setup, globals())
text.append(setup)
case('stream.getvalue()')
case('logger.getEffectiveLevel()')
case('logger.hasHandlers()')
case('logging.getLevelName("WARN")')
case('logging.getLevelName(99)')
finish('logging')
print(count, 'CPython comparisons')
