"""Expected output for AsyncioTest, from CPython 3.12's asyncio.

Each scenario in src/test/resources/asyncio/*.py records lines with say(...) into OUT; this
runs it with CPython in a fresh process and writes the lines to the matching .out file. The
test runs the same script in the VM and compares. Scenarios sleep in multiples of 50 ms (a
game tick), so the order of events is the same under CPython's real clock and the VM's.

Run:  python3 tools/asyncio-golden.py
"""
import glob
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCENARIOS = os.path.join(HERE, '..', 'src', 'test', 'resources', 'asyncio')
RUNNER = '''
import sys, warnings
warnings.simplefilter('ignore')
path = sys.argv[1]
g = {'__name__': '__main__'}
exec(compile(open(path, encoding='utf-8').read(), path, 'exec'), g)
sys.stdout.write('\\n'.join(g['OUT']) + '\\n')
'''


def main():
    for path in sorted(glob.glob(os.path.join(SCENARIOS, '*.py'))):
        out = subprocess.run([sys.executable, '-X', 'utf8', '-c', RUNNER, path], capture_output=True, text=True, encoding='utf-8')
        if out.returncode != 0:
            sys.exit(path + ' failed:\n' + out.stderr)
        with open(path[:-3] + '.out', 'w', encoding='utf-8', newline='\n') as f:
            f.write(out.stdout)
        print(os.path.basename(path), len(out.stdout.splitlines()), 'lines')


if __name__ == '__main__':
    main()
