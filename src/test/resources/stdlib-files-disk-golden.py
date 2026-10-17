from pathlib import Path
import glob
os.chdir('/home')
for directory in ['a/sub', 'b', '.hidden']:
    Path(directory).mkdir(parents=True, exist_ok=True)
for name in ['x.py', '.dot.py', 'a/f.py', 'a/sub/g.txt', 'a/.dot.py', 'b/z.py', '.hidden/h.py', '[a].txt']:
    Path(name).write_text(name)

assert sorted(glob.glob('*', recursive=True, include_hidden=False)) == ['[a].txt', 'a', 'b', 'x.py'], "sorted(glob.glob('*', recursive=True, include_hidden=False))"
assert sorted(glob.glob('*', recursive=True, include_hidden=True)) == ['.dot.py', '.hidden', '[a].txt', 'a', 'b', 'x.py'], "sorted(glob.glob('*', recursive=True, include_hidden=True))"
assert sorted(glob.glob('*.py', recursive=True, include_hidden=False)) == ['x.py'], "sorted(glob.glob('*.py', recursive=True, include_hidden=False))"
assert sorted(glob.glob('*.py', recursive=True, include_hidden=True)) == ['.dot.py', 'x.py'], "sorted(glob.glob('*.py', recursive=True, include_hidden=True))"
assert sorted(glob.glob('.*', recursive=True, include_hidden=False)) == ['.dot.py', '.hidden'], "sorted(glob.glob('.*', recursive=True, include_hidden=False))"
assert sorted(glob.glob('.*', recursive=True, include_hidden=True)) == ['.dot.py', '.hidden'], "sorted(glob.glob('.*', recursive=True, include_hidden=True))"
assert sorted(glob.glob('*/', recursive=True, include_hidden=False)) == ['a/', 'b/'], "sorted(glob.glob('*/', recursive=True, include_hidden=False))"
assert sorted(glob.glob('*/', recursive=True, include_hidden=True)) == ['.hidden/', 'a/', 'b/'], "sorted(glob.glob('*/', recursive=True, include_hidden=True))"
assert sorted(glob.glob('**', recursive=True, include_hidden=False)) == ['[a].txt', 'a', 'a/f.py', 'a/sub', 'a/sub/g.txt', 'b', 'b/z.py', 'x.py'], "sorted(glob.glob('**', recursive=True, include_hidden=False))"
assert sorted(glob.glob('**', recursive=True, include_hidden=True)) == ['.dot.py', '.hidden', '.hidden/h.py', '[a].txt', 'a', 'a/.dot.py', 'a/f.py', 'a/sub', 'a/sub/g.txt', 'b', 'b/z.py', 'x.py'], "sorted(glob.glob('**', recursive=True, include_hidden=True))"
assert sorted(glob.glob('**/', recursive=True, include_hidden=False)) == ['a/', 'a/sub/', 'b/'], "sorted(glob.glob('**/', recursive=True, include_hidden=False))"
assert sorted(glob.glob('**/', recursive=True, include_hidden=True)) == ['.hidden/', 'a/', 'a/sub/', 'b/'], "sorted(glob.glob('**/', recursive=True, include_hidden=True))"
assert sorted(glob.glob('**/*.py', recursive=True, include_hidden=False)) == ['a/f.py', 'b/z.py', 'x.py'], "sorted(glob.glob('**/*.py', recursive=True, include_hidden=False))"
assert sorted(glob.glob('**/*.py', recursive=True, include_hidden=True)) == ['.dot.py', '.hidden/h.py', 'a/.dot.py', 'a/f.py', 'b/z.py', 'x.py'], "sorted(glob.glob('**/*.py', recursive=True, include_hidden=True))"
assert sorted(glob.glob('**/**', recursive=True, include_hidden=False)) == ['[a].txt', 'a', 'a/', 'a/f.py', 'a/f.py', 'a/sub', 'a/sub', 'a/sub/', 'a/sub/g.txt', 'a/sub/g.txt', 'a/sub/g.txt', 'b', 'b/', 'b/z.py', 'b/z.py', 'x.py'], "sorted(glob.glob('**/**', recursive=True, include_hidden=False))"
assert sorted(glob.glob('**/**', recursive=True, include_hidden=True)) == ['.dot.py', '.hidden', '.hidden/', '.hidden/h.py', '.hidden/h.py', '[a].txt', 'a', 'a/', 'a/.dot.py', 'a/.dot.py', 'a/f.py', 'a/f.py', 'a/sub', 'a/sub', 'a/sub/', 'a/sub/g.txt', 'a/sub/g.txt', 'a/sub/g.txt', 'b', 'b/', 'b/z.py', 'b/z.py', 'x.py'], "sorted(glob.glob('**/**', recursive=True, include_hidden=True))"
assert sorted(glob.glob('**/**/', recursive=True, include_hidden=False)) == ['a/', 'a/', 'a/sub/', 'a/sub/', 'a/sub/', 'b/', 'b/'], "sorted(glob.glob('**/**/', recursive=True, include_hidden=False))"
assert sorted(glob.glob('**/**/', recursive=True, include_hidden=True)) == ['.hidden/', '.hidden/', 'a/', 'a/', 'a/sub/', 'a/sub/', 'a/sub/', 'b/', 'b/'], "sorted(glob.glob('**/**/', recursive=True, include_hidden=True))"
assert sorted(glob.glob('a/**', recursive=True, include_hidden=False)) == ['a/', 'a/f.py', 'a/sub', 'a/sub/g.txt'], "sorted(glob.glob('a/**', recursive=True, include_hidden=False))"
assert sorted(glob.glob('a/**', recursive=True, include_hidden=True)) == ['a/', 'a/.dot.py', 'a/f.py', 'a/sub', 'a/sub/g.txt'], "sorted(glob.glob('a/**', recursive=True, include_hidden=True))"
assert sorted(glob.glob('a/**/', recursive=True, include_hidden=False)) == ['a/', 'a/sub/'], "sorted(glob.glob('a/**/', recursive=True, include_hidden=False))"
assert sorted(glob.glob('a/**/', recursive=True, include_hidden=True)) == ['a/', 'a/sub/'], "sorted(glob.glob('a/**/', recursive=True, include_hidden=True))"
assert sorted(glob.glob('./*.py', recursive=True, include_hidden=False)) == ['./x.py'], "sorted(glob.glob('./*.py', recursive=True, include_hidden=False))"
assert sorted(glob.glob('./*.py', recursive=True, include_hidden=True)) == ['./.dot.py', './x.py'], "sorted(glob.glob('./*.py', recursive=True, include_hidden=True))"
assert sorted(glob.glob('a/*', recursive=True, include_hidden=False)) == ['a/f.py', 'a/sub'], "sorted(glob.glob('a/*', recursive=True, include_hidden=False))"
assert sorted(glob.glob('a/*', recursive=True, include_hidden=True)) == ['a/.dot.py', 'a/f.py', 'a/sub'], "sorted(glob.glob('a/*', recursive=True, include_hidden=True))"
assert sorted(glob.glob('a/../*.py', recursive=True, include_hidden=False)) == ['a/../x.py'], "sorted(glob.glob('a/../*.py', recursive=True, include_hidden=False))"
assert sorted(glob.glob('a/../*.py', recursive=True, include_hidden=True)) == ['a/../.dot.py', 'a/../x.py'], "sorted(glob.glob('a/../*.py', recursive=True, include_hidden=True))"
assert sorted(glob.glob('missing/**', recursive=True, include_hidden=False)) == [], "sorted(glob.glob('missing/**', recursive=True, include_hidden=False))"
assert sorted(glob.glob('missing/**', recursive=True, include_hidden=True)) == [], "sorted(glob.glob('missing/**', recursive=True, include_hidden=True))"
assert sorted(glob.glob('missing', recursive=True, include_hidden=False)) == [], "sorted(glob.glob('missing', recursive=True, include_hidden=False))"
assert sorted(glob.glob('missing', recursive=True, include_hidden=True)) == [], "sorted(glob.glob('missing', recursive=True, include_hidden=True))"
assert sorted(glob.glob('[[]a].txt', recursive=True, include_hidden=False)) == ['[a].txt'], "sorted(glob.glob('[[]a].txt', recursive=True, include_hidden=False))"
assert sorted(glob.glob('[[]a].txt', recursive=True, include_hidden=True)) == ['[a].txt'], "sorted(glob.glob('[[]a].txt', recursive=True, include_hidden=True))"
assert sorted(str(p) for p in Path(".").glob('*')) == ['.dot.py', '.hidden', '[a].txt', 'a', 'b', 'x.py'], 'sorted(str(p) for p in Path(".").glob(\'*\'))'
assert sorted(str(p) for p in Path(".").glob('*.py')) == ['.dot.py', 'x.py'], 'sorted(str(p) for p in Path(".").glob(\'*.py\'))'
assert sorted(str(p) for p in Path(".").glob('*/')) == ['.hidden', 'a', 'b'], 'sorted(str(p) for p in Path(".").glob(\'*/\'))'
assert sorted(str(p) for p in Path(".").glob('**')) == ['.', '.hidden', 'a', 'a/sub', 'b'], 'sorted(str(p) for p in Path(".").glob(\'**\'))'
assert sorted(str(p) for p in Path(".").glob('**/')) == ['.', '.hidden', 'a', 'a/sub', 'b'], 'sorted(str(p) for p in Path(".").glob(\'**/\'))'
assert sorted(str(p) for p in Path(".").glob('**/*.py')) == ['.dot.py', '.hidden/h.py', 'a/.dot.py', 'a/f.py', 'b/z.py', 'x.py'], 'sorted(str(p) for p in Path(".").glob(\'**/*.py\'))'
assert sorted(str(p) for p in Path(".").glob('**/**')) == ['.', '.hidden', 'a', 'a/sub', 'b'], 'sorted(str(p) for p in Path(".").glob(\'**/**\'))'
assert sorted(str(p) for p in Path(".").glob('**/**/')) == ['.', '.hidden', 'a', 'a/sub', 'b'], 'sorted(str(p) for p in Path(".").glob(\'**/**/\'))'
assert sorted(str(p) for p in Path(".").glob('a/**')) == ['a', 'a/sub'], 'sorted(str(p) for p in Path(".").glob(\'a/**\'))'
assert sorted(str(p) for p in Path(".").glob('a/**/')) == ['a', 'a/sub'], 'sorted(str(p) for p in Path(".").glob(\'a/**/\'))'
assert sorted(str(p) for p in Path(".").glob('./*.py')) == ['.dot.py', 'x.py'], 'sorted(str(p) for p in Path(".").glob(\'./*.py\'))'
assert sorted(str(p) for p in Path(".").glob('a/*')) == ['a/.dot.py', 'a/f.py', 'a/sub'], 'sorted(str(p) for p in Path(".").glob(\'a/*\'))'
assert sorted(str(p) for p in Path(".").glob('[[]a].txt')) == ['[a].txt'], 'sorted(str(p) for p in Path(".").glob(\'[[]a].txt\'))'
passed = True
