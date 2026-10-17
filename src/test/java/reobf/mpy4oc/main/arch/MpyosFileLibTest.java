package reobf.mpy4oc.main.arch;

import java.nio.file.Files;
import java.nio.file.Path;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class MpyosFileLibTest {
    @Test void filesystemPatternsMatchCPython() throws Exception {
        host(Files.readString(Path.of("src/test/resources/stdlib-files-disk-golden.py"))).runUntil("passed");
    }

    private TkPythonTest.Host host(String source) throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/stdio-fixture.py"))
                + "\n" + Files.readString(Path.of("src/test/resources/files-disk-fixture.py"));
        return TkPythonTest.shellHost(fixture + "\n" + source);
    }

    @Test void pathsOpenAndOsUseTheProcessCwd() throws Exception {
        var h = host("""
            from pathlib import Path
            os.chdir(Path('/home'))
            os.environ['HOME'] = '/home'
            assert str(Path.cwd()) == '/home' and Path.home() == Path('/home')
            assert os.path.normpath('a/../b') == 'b'
            assert os.path.basename('a/b/') == '' and os.path.dirname('a') == ''
            p = Path('notes.txt')
            assert p.write_text('é水\\n') == 3
            assert p.read_text() == 'é水\\n'
            with open(p, 'rb') as f:
                assert f.read() == 'é水\\n'.encode('utf-8')
            assert p.stat().st_size == 6 and p.stat().st_mtime == 1234
            assert os.stat(p)[6] == 6
            assert p.resolve() == Path('/home/notes.txt')
            assert p.exists() and p.is_file() and not p.is_dir()
            assert os.path.relpath('/home/notes.txt', Path('/home')) == 'notes.txt'
            assert os.path.expandvars('$HOME/${MISSING}') == '/home/${MISSING}'
            directory = Path('a/b')
            directory.mkdir(parents=True)
            directory.mkdir(exist_ok=True)
            raises(OSError, lambda: directory.mkdir())
            touched = directory / 'empty'
            touched.touch()
            assert touched.read_bytes() == b''
            touched.write_bytes(b'keep')
            touched.touch()
            assert touched.read_bytes() == b'keep'
            raises(NotImplementedError, lambda: touched.touch(exist_ok=False))
            renamed = p.rename('new.txt')
            assert renamed == Path('new.txt') and renamed.read_text() == 'é水\\n'
            assert sorted(str(x) for x in Path('.').iterdir()) == ['a', 'new.txt']
            assert not boot.handles
            raises(OSError, lambda: Path('/mnt/ro/no').write_text('bad'))
            assert not readonly.handles
            renamed.unlink()
            renamed.unlink(missing_ok=True)
            raises(OSError, renamed.unlink)
            passed = True
            """);
        h.runUntil("passed");
    }

    @Test void recursivePatternsAndCopyMoveUseVirtualDisks() throws Exception {
        var h = host("""
            from pathlib import Path
            import glob, shutil
            os.chdir('/home')
            root = Path('tree')
            (root / 'sub').mkdir(parents=True)
            (root / '.cache').mkdir()
            for name in ['a.py', '.hidden.py', 'drop.tmp', 'sub/b.py', 'sub/c.txt', '.cache/d.py', '[x].txt']:
                (root / name).write_text(name)
            assert sorted(glob.glob('tree/*.py')) == ['tree/a.py']
            assert sorted(glob.glob('**/*.py', root_dir=root, recursive=True)) == ['a.py', 'sub/b.py']
            assert sorted(glob.glob('**/*.py', root_dir=root, recursive=True, include_hidden=True)) == ['.cache/d.py', '.hidden.py', 'a.py', 'sub/b.py']
            assert sorted(str(p) for p in root.rglob('*.py')) == ['tree/.cache/d.py', 'tree/.hidden.py', 'tree/a.py', 'tree/sub/b.py']
            assert sorted(str(p) for p in root.glob('**')) == ['tree', 'tree/.cache', 'tree/sub']
            assert glob.glob(glob.escape('tree/[x].txt')) == ['tree/[x].txt']
            assert sorted(glob.glob('tree/*/')) == ['tree/sub/']
            assert glob.glob('missing/**', recursive=True) == []
            assert shutil.copytree(root, Path('copy'), ignore=shutil.ignore_patterns('*.tmp')) == Path('copy')
            assert Path('copy/sub/b.py').read_text() == 'sub/b.py'
            assert not Path('copy/drop.tmp').exists()
            raises(OSError, lambda: shutil.copytree(root, 'copy'))
            shutil.copytree(root, 'copy', dirs_exist_ok=True)
            assert Path('copy/drop.tmp').exists()
            raises(shutil.SameFileError, lambda: shutil.copyfile(root / 'a.py', 'tree/./a.py'))
            vfs.mounts['alias'] = 'boot'
            raises(shutil.SameFileError, lambda: shutil.copyfile(root / 'a.py', '/mnt/alias/home/tree/a.py'))
            raises(shutil.Error, lambda: shutil.copytree(root, root / 'nested'))
            raises(shutil.Error, lambda: shutil.copytree(root, '/mnt/alias/home/tree/nested'))
            assert not (root / 'nested').exists()
            assert shutil.copy(root / 'a.py', Path('/mnt/oth')) == '/mnt/oth/a.py'
            assert other.files['/a.py'] == b'a.py'
            assert shutil.move('copy', '/mnt/oth/moved') == '/mnt/oth/moved'
            assert not Path('copy').exists() and Path('/mnt/oth/moved/sub/b.py').exists()
            shutil.rmtree(Path('/mnt/oth/moved'))
            assert not Path('/mnt/oth/moved').exists()
            shutil.rmtree('absent', ignore_errors=True)
            errors = []
            shutil.rmtree('absent', onexc=lambda function, path, exc: errors.append(path))
            assert errors == ['absent']
            def fail_callback(function, path, exc):
                raise ValueError('callback failure')
            raises(ValueError, lambda: shutil.rmtree('absent', onexc=fail_callback))
            raises(FileNotFoundError, lambda: shutil.move('absent', 'absent'))
            raises(OSError, lambda: shutil.move(root / 'a.py', '/mnt/ro/no'))
            assert (root / 'a.py').read_text() == 'a.py'
            assert not boot.handles and not other.handles and not readonly.handles
            passed = True
            """);
        h.runUntil("passed");
    }

    @Test void copyingOpenFilesAndLazyGlobSurviveSnapshot() throws Exception {
        var h = host("""
            from pathlib import Path
            import glob, shutil
            os.chdir('/home')
            for name in ['a.txt', 'b.txt', 'c.txt']:
                Path(name).write_text(name)
            matches = glob.iglob('*.txt')
            assert next(matches) == 'a.txt'
            source = Path('a.txt')
            old_write = Disk._write_chunk
            def pausing_write(self, handle, data):
                global ready
                old_write(self, handle, data)
                if self.address == 'other' and not globals().get('resume'):
                    ready = True
                    while not globals().get('resume'):
                        computer._yield_tick()
                return True
            Disk._write_chunk = pausing_write
            shutil.copyfile(source, '/mnt/oth/saved.txt')
            assert source.read_text() == Path('/mnt/oth/saved.txt').read_text()
            assert list(matches) == ['b.txt', 'c.txt']
            assert not boot.handles and not other.handles
            passed = True
            """);
        h.runUntil("ready");
        byte[] saved = h.vm.saveSnapshot(h.registry);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        restored.vm = Vm.fromSnapshot(saved, restored.globals, restored.registry);
        restored.vm.globals.put("resume", true);
        restored.runUntil("passed");
    }
}
