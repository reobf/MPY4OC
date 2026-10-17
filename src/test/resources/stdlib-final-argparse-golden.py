# CPython 3.12.14
import argparse
p = argparse.ArgumentParser(prog='demo')
p.add_argument('-v', '--verbose', action='count', default=0)
p.add_argument('--number', type=int, default='4')
p.add_argument('--tag', action='append', default=['base'])
p.add_argument('--flag', action=argparse.BooleanOptionalAction)
p.add_argument('--mode', choices=['a','b'], default='a')
p.add_argument('files', nargs='*')

n, extras = p.parse_known_args([])
assert n.__dict__ == {'verbose': 0, 'number': 4, 'tag': ['base'], 'flag': None, 'mode': 'a', 'files': []}, repr(n.__dict__)
assert extras == [], []
n, extras = p.parse_known_args(['-vv', 'a', 'b'])
assert n.__dict__ == {'verbose': 2, 'number': 4, 'tag': ['base'], 'flag': None, 'mode': 'a', 'files': ['a', 'b']}, repr(n.__dict__)
assert extras == [], ['-vv', 'a', 'b']
n, extras = p.parse_known_args(['--number=-3', 'x'])
assert n.__dict__ == {'verbose': 0, 'number': -3, 'tag': ['base'], 'flag': None, 'mode': 'a', 'files': ['x']}, repr(n.__dict__)
assert extras == [], ['--number=-3', 'x']
n, extras = p.parse_known_args(['--tag', 'one', '--tag', 'two'])
assert n.__dict__ == {'verbose': 0, 'number': 4, 'tag': ['base', 'one', 'two'], 'flag': None, 'mode': 'a', 'files': []}, repr(n.__dict__)
assert extras == [], ['--tag', 'one', '--tag', 'two']
n, extras = p.parse_known_args(['--flag', '--mode=b'])
assert n.__dict__ == {'verbose': 0, 'number': 4, 'tag': ['base'], 'flag': True, 'mode': 'b', 'files': []}, repr(n.__dict__)
assert extras == [], ['--flag', '--mode=b']
n, extras = p.parse_known_args(['--no-flag', '--num', '9'])
assert n.__dict__ == {'verbose': 0, 'number': 9, 'tag': ['base'], 'flag': False, 'mode': 'a', 'files': []}, repr(n.__dict__)
assert extras == [], ['--no-flag', '--num', '9']
n, extras = p.parse_known_args(['--', '-odd', '--flag'])
assert n.__dict__ == {'verbose': 0, 'number': 4, 'tag': ['base'], 'flag': None, 'mode': 'a', 'files': ['-odd', '--flag']}, repr(n.__dict__)
assert extras == [], ['--', '-odd', '--flag']
n, extras = p.parse_known_args(['a', '--flag', 'b'])
assert n.__dict__ == {'verbose': 0, 'number': 4, 'tag': ['base'], 'flag': True, 'mode': 'a', 'files': ['a']}, repr(n.__dict__)
assert extras == ['b'], ['a', '--flag', 'b']
q = argparse.ArgumentParser(prog='jobs')
subs = q.add_subparsers(dest='command', required=True)
run = subs.add_parser('run', aliases=['r'])
run.add_argument('script')
run.add_argument('--force', action='store_true')
run.set_defaults(handler='run')

actual = q.parse_args(['run', 'a.py']).__dict__
assert actual == {'command': 'run', 'script': 'a.py', 'force': False, 'handler': 'run'}, "q.parse_args(['run', 'a.py']).__dict__" + ': ' + repr(actual)
actual = q.parse_args(['r', 'a.py', '--force']).__dict__
assert actual == {'command': 'r', 'script': 'a.py', 'force': True, 'handler': 'run'}, "q.parse_args(['r', 'a.py', '--force']).__dict__" + ': ' + repr(actual)
passed = True
