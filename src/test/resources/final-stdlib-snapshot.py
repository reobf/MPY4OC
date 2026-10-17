from collections import defaultdict, Counter
from datetime import datetime, timedelta, timezone
import itertools, argparse, logging
from pathlib import Path

os.chdir('/home')
stream = logging.FileHandler('saved.log')
stream.setFormatter(logging.Formatter('%(message)s'))
logger = logging.getLogger('snapshot')
logger.setLevel(logging.INFO)
logger.propagate = False
logger.addHandler(stream)
logger.info('before')
when = datetime(2024, 2, 29, 23, 59, 59, tzinfo=timezone(timedelta(hours=8)))
parser = argparse.ArgumentParser(prog='saved')
parser.add_argument('-n', type=int)
first, second = itertools.tee(range(4))
assert next(first) == 0 and next(first) == 1
groups = itertools.groupby('AABB')
key, group = next(groups)
assert next(group) == 'A'
def factory():
    global armed
    armed = True
    yieldJava()
    return []
mapping = defaultdict(factory)
mapping['key'].append('value')
assert dict(mapping) == {'key':['value']}
assert list(first) == [2,3] and list(second) == [0,1,2,3]
assert list(group) == ['A']
assert [(k,list(v)) for k,v in groups] == [('B',['B','B'])]
assert parser.parse_args(['-n7']).n == 7
logger.info('%s', (when + timedelta(seconds=2)).isoformat())
stream.close()
assert Path('saved.log').read_text() == 'before\n2024-03-01T00:00:01+08:00\n'
assert Counter(a=1) == Counter(a=1,b=0)
assert not boot.handles
passed = True
