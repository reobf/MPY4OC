"""CPython reference cases for the POSIX file-library subset (test-only)."""
from pathlib import Path, PurePosixPath
import posixpath, fnmatch, sys

destination = Path(__file__).resolve().parents[1] / 'src/test/resources/stdlib-files-pure-golden.py'
text = ['# Generated with CPython ' + sys.version.split()[0], 'import posixpath as p, fnmatch, re', 'from pathlib import PurePosixPath as P']
count = 0
def case(expression, value):
    global count
    text.append('assert ' + expression + ' == ' + repr(value) + ', ' + repr(expression))
    count += 1

paths = ['', '.', '..', '/', '//', '///', 'a', 'a/b/', './a//b', '../a/../b', '../../a', '/a/../../b', '//a//b', 'a.tar.gz', '.env', '..name', 'a.', 'a/..', 'a/./b', 'a\\b']
for path in paths:
    for method in ('normpath', 'split', 'splitext', 'basename', 'dirname', 'isabs'):
        case('p.' + method + '(' + repr(path) + ')', getattr(posixpath, method)(path))
    obj = PurePosixPath(path)
    for prop in ('parts', 'root', 'anchor', 'drive', 'name', 'suffix', 'suffixes', 'stem'):
        case('P(' + repr(path) + ').' + prop, getattr(obj, prop))
    case('str(P(' + repr(path) + '))', str(obj))
    case('str(P(' + repr(path) + ').parent)', str(obj.parent))
    case('[str(x) for x in P(' + repr(path) + ').parents]', [str(x) for x in obj.parents])
    for pattern in ('*', '*.gz', 'a/*', '/a/*', '**/b'):
        case('P(' + repr(path) + ').match(' + repr(pattern) + ')', obj.match(pattern))

for parts in (('a', ''), ('a/', 'b'), ('/a', '/b', 'c'), ('', '/'), ('//a', '..', 'b')):
    case('p.join(*' + repr(parts) + ')', posixpath.join(*parts))
    case('str(P(*' + repr(parts) + '))', str(PurePosixPath(*parts)))
for path in ['a.tar.gz', '.env', 'a.', '/a/b']:
    for suffix in ['', '.txt', '.tar.gz']:
        case('str(P(' + repr(path) + ').with_suffix(' + repr(suffix) + '))', str(PurePosixPath(path).with_suffix(suffix)))
for path, parent in [('a/b', 'a'), ('/a/b', '/'), ('a', '.'), ('a/b', 'a/b'), ('a/../b', 'a')]:
    case('str(P(' + repr(path) + ').relative_to(' + repr(parent) + '))', str(PurePosixPath(path).relative_to(parent)))
for paths in (['a/b', 'a/c'], ['/a/b', '/a/c'], ['a', 'ab'], ['a/../b', 'a/x'], ['', '.']):
    case('p.commonpath(' + repr(paths) + ')', posixpath.commonpath(paths))

patterns = ['*', '?', '*.py', 'a?c', '[ab]', '[!ab]', '[a-z]', '[z-a]', '[]]', '[!]]', '[[]', '[', '[]', '[!]', '[^]', '[a-c-e]', '*a*b*', '**a***', 'a/b', '[&|~]', '[\\]', '*\n*']
names = ['', 'a', 'b', 'abc', 'a/b', '.hidden', 'test.py', 'A.PY', ']', '[', '-', '^', '&', '\\', 'é', 'a\nb', 'z', 'c', 'e']
for pattern in patterns:
    # The generator runs on Windows; the guest uses POSIX case-sensitive names.
    result = [name for name in names if fnmatch.fnmatchcase(name, pattern)]
    case('fnmatch.filter(' + repr(names) + ', ' + repr(pattern) + ')', result)
    case('[n for n in ' + repr(names) + ' if re.match(fnmatch.translate(' + repr(pattern) + '), n)]', result)
text.append('passed = True')
destination.write_text('\n'.join(text) + '\n', encoding='utf-8')
print(str(count) + ' CPython comparisons written')

# Build a tiny local tree only to ask CPython for filesystem-glob results.
# The temporary directory is confined to this generator's test-output directory.
import tempfile, os, glob
setup = """from pathlib import Path
import glob
os.chdir('/home')
for directory in ['a/sub', 'b', '.hidden']:
    Path(directory).mkdir(parents=True, exist_ok=True)
for name in ['x.py', '.dot.py', 'a/f.py', 'a/sub/g.txt', 'a/.dot.py', 'b/z.py', '.hidden/h.py', '[a].txt']:
    Path(name).write_text(name)
"""
disk_text, disk_count = [setup], 0
previous_cwd = os.getcwd()
with tempfile.TemporaryDirectory(prefix='files-golden-', dir=destination.parent) as folder:
    assert Path(folder).resolve().is_relative_to(destination.parent.resolve())
    try:
        os.chdir(folder)
        for directory in ['a/sub', 'b', '.hidden']:
            Path(directory).mkdir(parents=True, exist_ok=True)
        for name in ['x.py', '.dot.py', 'a/f.py', 'a/sub/g.txt', 'a/.dot.py', 'b/z.py', '.hidden/h.py', '[a].txt']:
            Path(name).write_text(name)
        patterns = ['*', '*.py', '.*', '*/', '**', '**/', '**/*.py', '**/**', '**/**/', 'a/**', 'a/**/', './*.py', 'a/*', 'a/../*.py', 'missing/**', 'missing', '[[]a].txt']
        for pattern in patterns:
            for hidden in (False, True):
                expected = sorted(p.replace('\\', '/') for p in glob.glob(pattern, recursive=True, include_hidden=hidden))
                expr = 'sorted(glob.glob(' + repr(pattern) + ', recursive=True, include_hidden=' + repr(hidden) + '))'
                disk_text.append('assert ' + expr + ' == ' + repr(expected) + ', ' + repr(expr))
                disk_count += 1
        for pattern in ['*', '*.py', '*/', '**', '**/', '**/*.py', '**/**', '**/**/', 'a/**', 'a/**/', './*.py', 'a/*', '[[]a].txt']:
            expected = sorted(p.as_posix() for p in Path('.').glob(pattern))
            expr = 'sorted(str(p) for p in Path(".").glob(' + repr(pattern) + '))'
            disk_text.append('assert ' + expr + ' == ' + repr(expected) + ', ' + repr(expr))
            disk_count += 1
    finally:
        os.chdir(previous_cwd)
disk_text.append('passed = True')
destination.with_name('stdlib-files-disk-golden.py').write_text('\n'.join(disk_text) + '\n', encoding='utf-8')
print(str(disk_count) + ' filesystem comparisons written')
