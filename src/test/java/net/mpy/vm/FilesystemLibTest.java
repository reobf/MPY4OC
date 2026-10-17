package net.mpy.vm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FilesystemLibTest {
    @Test void purePathsAndPatternsMatchCPython() throws Exception {
        StdlibTestSupport.golden("files-pure");
    }

    @Test void pathProtocolErrorsAndSnapshots() throws Exception {
        Vm vm = StdlibTestSupport.start("""
            import os, posixpath, pathlib, fnmatch, glob, shutil
            from io import BytesIO
            from pathlib import Path, PurePath
            def fail(kind, callback):
                try:
                    callback()
                except kind:
                    return
                raise AssertionError('expected error')
            class P(os.PathLike):
                def __fspath__(self):
                    return 'a/b'
            class Bad:
                def __fspath__(self):
                    return 3
            assert os.fspath(P()) == 'a/b'
            assert IOError is OSError and issubclass(FileExistsError, OSError)
            assert issubclass(IsADirectoryError, OSError) and issubclass(PermissionError, OSError)
            assert os.fspath(b'a') == b'a'
            assert os.fsdecode(os.fsencode('é')) == 'é'
            fail(TypeError, lambda: os.fspath(Bad()))
            fail(TypeError, lambda: Path(42))
            fail(ValueError, lambda: Path('x').with_suffix('txt'))
            fail(ValueError, lambda: Path('/').with_name('x'))
            fail(ValueError, lambda: Path('a').relative_to('/a'))
            fail(ValueError, lambda: posixpath.commonpath(['/a', 'b']))
            p = Path('a') / 'b.tar.gz'
            assert str('root' / p) == 'root/a/b.tar.gz'
            assert p == PurePath('a/b.tar.gz') and p != 'a/b.tar.gz'
            assert str(p.with_stem('new')) == 'a/new.gz'
            assert str(p.relative_to('a/c', walk_up=True)) == '../b.tar.gz'
            source, dest = BytesIO(b'abcd'), BytesIO()
            shutil.copyfileobj(source, dest, 2)
            assert dest.getvalue() == b'abcd'
            ignore = shutil.ignore_patterns('*.tmp', 'build')
            armed = True
            yieldJava()
            assert str(p.parent) == 'a' and p.match('*.gz')
            assert ignore('a', ['x.tmp', 'build', 'keep']) == {'x.tmp', 'build'}
            assert fnmatch.fnmatchcase('abbbz', 'a*z')
            passed = True
            """);
        for (int i = 0; i < 100000 && !Boolean.TRUE.equals(vm.globals.get("armed")); i++) vm.step(new int[] {1});
        assertEquals(Boolean.TRUE, vm.globals.get("armed"));
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot());
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }
}
