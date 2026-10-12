package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import org.junit.jupiter.api.Test;

/** Real shell and terminal Python running in the MPY VM, with keyboard/GPU fixtures. */
class MpyosShellTest {
    private static final String SETUP = """
            import fs, shell, term, event
            class Disk:
                def __init__(self):
                    self.entries = ['tkdesktop.py', 'tkdemo.py', 'python.py', 'help.py', 'data.txt', 'ignored.py/']
                    self.directories = {
                        '/': ['bin/', 'home/', 'mnt/'],
                        '/home': ['scripts/', 'script.py', 'scrape.py', 'readme.txt', '.hidden/', '.secret.py', 'space dir/', 'space draft.py', 'space data.py'],
                        '/home/scripts': ['run.py'],
                        '/home/space dir': ['inside.py', 'inventory.py'],
                        '/home/.hidden': ['private.py'],
                        '/mnt': ['a1/', 'b2/'],
                        '/mnt/a1': ['remote.py'],
                        '/mnt/b2': []
                    }
                    self.missing = False
                    self.files = {}
                def list(self, path):
                    if self.missing:
                        raise OSError('disk removed')
                    return self.entries if path == '/bin' else self.directories[path]
                def is_directory(self, path):
                    return path == '/bin' or path in self.directories
                def exists(self, path):
                    if self.is_directory(path) or path in self.files:
                        return True
                    cut = path.rfind('/')
                    directory = path[:cut] or '/'
                    entries = self.entries if directory == '/bin' else self.directories.get(directory, [])
                    return path[cut + 1:] in entries
                def read_file(self, path):
                    return self.files.get(path, 'pass') if self.exists(path) else None
                def refresh(self):
                    pass
            disk = Disk()
            fs.Vfs = lambda address: disk
            class Gpu:
                def __init__(self):
                    self.rows = [' ' * 80 for i in range(12)]
                    self.fg, self.bg = 0xffffff, 0
                    self.writes = []
                def getResolution(self):
                    return (80, 12)
                def getScreen(self):
                    return 'screen'
                def setForeground(self, value):
                    self.fg = value
                def setBackground(self, value):
                    self.bg = value
                def set(self, x, y, text):
                    text = text[:max(0, 81 - x)]
                    row = self.rows[y - 1]
                    self.rows[y - 1] = row[:x - 1] + text + row[x - 1 + len(text):]
                    self.writes.append((text, self.fg))
                def get(self, x, y):
                    return (self.rows[y - 1][x - 1], self.fg, self.bg)
                def fill(self, x, y, width, height, ch):
                    for row in range(y, y + height):
                        self.set(x, row, ch * width)
                def copy(self, x, y, width, height, dx, dy):
                    rows = list(self.rows)
                    for row in range(y, y + height):
                        self.set(x + dx, row + dy, rows[row - 1][x - 1:x - 1 + width])
            gpu = Gpu()
            tty = term.Terminal(gpu)
            sh = shell.Shell(tty, 'boot', globals())
            signals = []
            def pull(timeout):
                assert signals, 'line editor consumed all test input without returning'
                return signals.pop(0)
            event.pull = pull
            def key(char, code=0):
                signals.append(('key_down', 'keyboard', char, code))
            def paste(text):
                signals.append(('clipboard', 'keyboard', text))
            def enter():
                key(13, term.KEY_ENTER)
            """;

    @Test void commandCompletionUsesLiveBinAndBuiltins() throws Exception {
        run("""
                assert sh._complete('pyt', 3) == [('python ', 7, 'python')]
                assert sh._complete('  pyt file.py', 5) == [('  python file.py', 8, 'python')]
                assert sh._complete('python file.py', 3) == [('python file.py', 6, 'python')]
                assert [c[2] for c in sh._complete('tkd', 3)] == ['tkdemo', 'tkdesktop']
                assert [c[2] for c in sh._complete('', 0)] == ['cd', 'data.txt', 'exit', 'help', 'jobs', 'kill', 'python', 'reboot', 'shutdown', 'tkdemo', 'tkdesktop']
                assert sh._complete('nothing', 7) == []
                assert sh._complete('python pyt', 10) == []
                assert sh._complete('  pyt', 1) == []
                disk.entries.append('newcommand.py')
                assert sh._complete('new', 3) == [('newcommand ', 11, 'newcommand')]
                disk.entries.remove('newcommand.py')
                assert sh._complete('new', 3) == []
                disk.missing = True
                assert sh._complete('reb', 3) == [('reboot ', 7, 'reboot')]
                assert sh._complete('pyt', 3) == []
                passed = True
                """);
    }

    @Test void tabCompletesListsCandidatesAndRestoresTheColoredShellPrompt() throws Exception {
        run("""
                sh.cwd = '/home'
                paste('tkd')
                key(0, term.KEY_TAB)
                paste('mo notes.txt')
                enter()
                received = []
                def execute(name, args):
                    received.append((name, args))
                    sh.running = False
                sh.run_command = execute
                sh.loop()
                assert received == [('tkdemo', ['notes.txt'])], received
                assert gpu.rows[0].rstrip() == '/home # tkd', gpu.rows
                assert gpu.rows[1].rstrip() == 'tkdemo  tkdesktop', gpu.rows
                assert gpu.rows[2].rstrip() == '/home # tkdemo notes.txt', gpu.rows
                assert gpu.writes.count(('/home', term.CYAN)) == 2, gpu.writes
                assert gpu.writes.count((' # ', term.GREEN)) == 2, gpu.writes
                # Character-only Tab, as remote keyboard sources can send it.
                paste('pyt')
                key(9)
                enter()
                assert tty.read('> ', [], sh._complete) == 'python '
                # Unknown names stay unchanged and produce no candidate list.
                paste('no-such-command')
                key(9)
                enter()
                assert tty.read('> ', [], sh._complete) == 'no-such-command'
                passed = True
                """);
    }

    @Test void completingInTheMiddleKeepsArgumentsAndTheCursor() throws Exception {
        run("""
                paste('tkd notes.txt &')
                key(0, term.KEY_HOME)
                for i in range(3):
                    key(0, term.KEY_RIGHT)
                key(9)
                # Common prefix grows tkd to tkde while preserving the suffix.
                paste('mo')
                enter()
                assert tty.read('> ', [], sh._complete) == 'tkdemo notes.txt &', gpu.rows
                assert gpu.rows[2].rstrip() == '> tkdemo notes.txt &', gpu.rows
                paste('python script.py')
                key(0, term.KEY_HOME)
                for i in range(3):
                    key(0, term.KEY_RIGHT)
                key(9)
                enter()
                assert tty.read('> ', [], sh._complete) == 'python script.py'
                # String prompts and REPL-style candidates still preserve text after the cursor.
                def repl(text, pos):
                    return [('print(x)', 5, 'print'), ('private(x)', 7, 'private')]
                paste('pr(x)')
                key(0, term.KEY_HOME)
                key(0, term.KEY_RIGHT)
                key(0, term.KEY_RIGHT)
                key(9)
                enter()
                assert tty.read('>>> ', [], repl) == 'pri(x)', gpu.rows
                passed = True
                """);
    }

    @Test void pathCompletionUsesCwdDirectoriesAndMountedPaths() throws Exception {
        run("""
                sh.cwd = '/home'
                for text, expected in [
                    ('cd sc', 'cd scripts/'),
                    ('cd /home/sc', 'cd /home/scripts/'),
                    ('python ./scripts/ru', 'python ./scripts/run.py '),
                    ('python ../bi', 'python ../bin/'),
                    ('cd ..', 'cd ../'),
                    ('cd ../bi', 'cd ../bin/'),
                    ('python /mnt/a1/re', 'python /mnt/a1/remote.py '),
                    ('python .se', 'python .secret.py '),
                    ('cd .h', 'cd .hidden/')
                ]:
                    cands = sh._complete(text, len(text))
                    assert len(cands) == 1 and cands[0][0] == expected, (text, cands)
                assert [c[2] for c in sh._complete('python sc', 9)] == ['scrape.py', 'script.py', 'scripts/']
                assert [c[2] for c in sh._complete('cd /mnt/', 8)] == ['a1/', 'b2/']
                assert sh._complete('cd script.p', 11) == [], 'cd only completes directories'
                assert sh._complete('python /absent/x', 16) == []
                assert sh._complete('python /mnt/b2/', 15) == []
                assert not any(c[2].startswith('.') for c in sh._complete('python ', 7))
                assert [c[2] for c in sh._complete('cd .', 4)] == ['../', './', '.hidden/']
                # Every argument is a path, not just the first; preserve following arguments.
                text = 'cp readme.txt scr dest'
                cands = sh._complete(text, text.index(' dest'))
                assert cands[0][0] == 'cp readme.txt scrape.py dest', cands
                disk.directories['/home'].append('new.py')
                assert sh._complete('python ne', 9)[0][0] == 'python new.py '
                disk.directories['/home'].remove('new.py')
                assert sh._complete('python ne', 9) == []
                disk.missing = True
                assert sh._complete('python sc', 9) == []
                passed = True
                """);
    }

    @Test void commandPathsFollowOpenOsSearchOrderAndCwd() throws Exception {
        run("""
                sh.cwd = '/home'
                for directory in ('/bin', '/usr/bin', '/home/bin', '/home'):
                    disk.files[directory + '/same.py'] = directory
                assert sh.command_path('same') == '/bin/same.py'
                del disk.files['/bin/same.py']
                assert sh.command_path('same') == '/usr/bin/same.py'
                del disk.files['/usr/bin/same.py']
                assert sh.command_path('same') == '/home/bin/same.py'
                del disk.files['/home/bin/same.py']
                assert sh.command_path('same') == '/home/same.py'
                assert sh.find_command('same') == '/home'
                for name in ('./same', './same.py', '/home/same', '/home/same.py'):
                    assert sh.command_path(name) == '/home/same.py', name
                assert sh.command_path('scripts/run') == '/home/scripts/run.py'
                assert sh.command_path('../bin/python') == '/bin/python.py'
                assert sh.command_path('/mnt/a1/remote') == '/mnt/a1/remote.py'
                assert sh.command_path('./python') is None, 'explicit paths do not fall back to PATH'
                assert sh.command_path('scripts/') is None
                assert sh.command_path('./scripts') is None, 'an existing directory is not executable'
                assert sh.command_path('') is None
                assert sh.command_path('..') is None
                disk.files['/home/worker'] = 'exact'
                disk.files['/home/worker.py'] = 'extension'
                assert sh.command_path('./worker') == '/home/worker'
                assert sh.find_command('./worker') == 'exact'
                assert sh.find_command('./worker.py') == 'extension'
                disk.files['/home/missing.py.py'] = 'must not silently double .py'
                assert sh.command_path('./missing.py') is None
                sh.path = '.:/bin'
                disk.files['/home/tkdemo.py'] = 'local'
                assert sh.command_path('tkdemo') == '/home/tkdemo.py'
                sh.path = '/bin'
                assert sh.command_path('tkdemo') == '/bin/tkdemo.py'
                assert sh.command_path('same') is None
                passed = True
                """);
    }

    @Test void environmentPathImmediatelyControlsLookupAndTab() throws Exception {
        run("""
                import os
                assert os.getenv('PATH') == '/bin:/usr/bin:/home/bin:.'
                sh.cwd = '/home'
                os.environ['PATH'] = '.'
                assert sh.path == '.'
                assert sh.command_path('script') == '/home/script.py'
                assert sh.command_path('python') is None
                assert sh._complete('scri', 4)[0][0] == 'script '
                assert sh._complete('pyt', 3) == []
                sh.path = '/bin'
                assert os.getenv('PATH') == '/bin'
                assert sh.command_path('python') == '/bin/python.py'
                assert sh._complete('scri', 4) == []
                os.environ['PATH'] = ''
                assert sh.command_path('python') is None
                assert sh._complete('pyt', 3) == []
                del os.environ['PATH']
                assert os.getenv('PATH') is None and sh.path == ''
                assert sh.command_path('./script') == '/home/script.py'
                assert sh._complete('./scri', 6)[0][0] == './script.py '
                assert sh.command_path('/bin/python') == '/bin/python.py'
                assert sh._complete('cd', 2)[0][0] == 'cd '
                os.environ['PATH'] = '/home:/bin'
                other = shell.Shell(tty, 'boot', globals())
                assert other.path == '/home:/bin', 'shell creation must preserve a configured PATH'
                passed = True
                """);
    }

    @Test void tabCompletesCommandsAcrossTheSearchPathAndExplicitPaths() throws Exception {
        run("""
                sh.cwd = '/home'
                disk.directories['/usr/bin'] = ['usercmd.py', 'tkdemo.py']
                disk.directories['/home/bin'] = ['personal.py']
                assert sh._complete('userc', 5)[0][0] == 'usercmd '
                assert sh._complete('pers', 4)[0][0] == 'personal '
                assert sh._complete('readm', 5)[0][0] == 'readme.txt '
                assert len(sh._complete('tkdemo', 6)) == 1, 'duplicate PATH entries appear once'
                assert sh._complete('./scripts/ru', 12)[0][0] == './scripts/run.py '
                assert sh._complete('/mnt/a1/re', 10)[0][0] == '/mnt/a1/remote.py '
                assert sh._complete('../bin/pyt', 10)[0][0] == '../bin/python.py '
                paste('./scripts/ru')
                key(9)
                enter()
                assert tty.read('> ', [], sh._complete) == './scripts/run.py '
                sh.path = '/absent:/bin'
                assert sh._complete('userc', 5) == []
                assert sh._complete('pyt', 3)[0][0] == 'python '
                passed = True
                """);
    }

    @Test void pathCompletionQuotesSpacesAndBothKindsOfQuotes() throws Exception {
        run("""
                sh.cwd = '/home'
                disk.directories['/home'].append('novel name.py')
                for quote in ('', chr(34), chr(39)):
                    text = 'python ' + quote + ('space da' if quote else 'nov')
                    expected = 'space data.py' if quote else 'novel name.py'
                    cands = sh._complete(text, len(text))
                    assert len(cands) == 1, cands
                    assert shell._split(cands[0][0]) == ['python', expected], cands
                    assert cands[0][0].endswith(' ') and cands[0][1] == len(cands[0][0]), cands
                    text = 'cd ' + quote + ('space di' if quote else 'spa')
                    cand = sh._complete(text, len(text))[0]
                    assert shell._split(cand[0]) == ['cd', 'space dir/'], cand
                    assert cand[1] == len(cand[0]) - 1, cand
                    sh.run_command('cd', shell._split(cand[0])[1:])
                    assert sh.cwd == '/home/space dir', sh.cwd
                    sh.cwd = '/home'
                for name in ['he' + chr(34) + 'llo.py', 'it' + chr(39) + 's.py', 'both' + chr(34) + chr(39) + '.py', 'tail&']:
                    disk.directories['/home'].append(name)
                    for quote in ('', chr(34), chr(39)):
                        text = 'python ' + quote + name[:2]
                        cand = sh._complete(text, len(text))[0]
                        assert shell._split(cand[0]) == ['python', name], (text, cand)
                        assert not cand[0].strip().endswith('&'), cand
                disk.directories['/home'] += ['q' + chr(34) + 'ue-a.py', 'q' + chr(34) + 'ue-b.py']
                cands = sh._complete('python q', 8)
                shared, cursor = cands[0][3]
                assert shell._split(shared) == ['python', 'q' + chr(34) + 'ue-'], (shared, cursor)
                assert cursor == len(shared) - 1
                # A completed prefix with no extension keeps the user's spelling and cursor.
                text = 'python "q' + chr(34) + "'" + chr(34) + "'" + chr(34) + 'ue-"'
                cands = sh._complete(text, len(text) - 1)
                assert cands[0][3] == (text, len(text) - 1), cands
                passed = True
                """);
    }

    @Test void tabListsAmbiguousPathsAndContinuesInsideQuotedDirectories() throws Exception {
        run("""
                sh.cwd = '/home'
                paste('python spa')
                key(9)
                paste('i')
                key(9)
                key(9)
                paste('side')
                key(9)
                paste('--flag')
                enter()
                result = tty.read(sh._prompt, [], sh._complete)
                assert result == 'python "space dir/inside.py" --flag', (result, gpu.rows)
                assert shell._split(result) == ['python', 'space dir/inside.py', '--flag']
                assert gpu.rows[1].rstrip() == 'space data.py  space dir/  space draft.py', gpu.rows
                assert gpu.rows[3].rstrip() == 'inside.py  inventory.py', gpu.rows
                assert gpu.rows[4].rstrip() == '/home # python "space dir/inside.py" --flag', gpu.rows
                # cd on a blank argument offers directories, not files, and no hidden entries.
                paste('cd ')
                key(0, term.KEY_TAB)
                key(3)
                assert tty.read(sh._prompt, [], sh._complete) is None
                assert any(row.rstrip() == 'scripts/  space dir/' for row in gpu.rows), gpu.rows
                passed = True
                """);
    }

    @Test void pathCompletionInTheMiddlePreservesQuotesArgumentsAndBackgroundMarker() throws Exception {
        run("""
                sh.cwd = '/home'
                paste('python sc --flag &')
                key(0, term.KEY_HOME)
                for i in range(len('python sc')):
                    key(0, term.KEY_RIGHT)
                key(9)
                paste('ape')
                key(9)
                enter()
                assert tty.read('> ', [], sh._complete) == 'python scrape.py --flag &', gpu.rows
                paste('python "space di" --flag &')
                key(0, term.KEY_HOME)
                for i in range(len('python "space di')):
                    key(0, term.KEY_RIGHT)
                key(9)
                paste('inside')
                key(9)
                enter()
                result = tty.read('> ', [], sh._complete)
                assert result == 'python "space dir/inside.py" --flag &', (result, gpu.rows)
                assert shell._split(result[:-1]) == ['python', 'space dir/inside.py', '--flag']
                # Completion scans the whole token, so an old suffix is replaced only once.
                text = 'python "space dir/inside.py" other'
                cand = sh._complete(text, len('python "space dir/ins'))[0]
                assert cand[0] == text, cand
                assert cand[1] == len('python "space dir/inside.py"'), cand
                passed = True
                """);
    }

    private static void run(String program) throws Exception {
        Map<String, byte[]> modules = new HashMap<>();
        Path lib = Path.of("src/main/resources/assets/mpy4oc/mpyos/lib");
        for (String name : new String[] {"fs", "shell", "term", "event"})
            modules.put(name, MpyCross.compile(Files.readString(lib.resolve(name + ".py")), name + ".py"));
        MpyModule main = MpyLoader.load(MpyCross.compile(SETUP + program, "shell_test.py"), new QstrPool());
        Vm vm = new Vm(main, new HashMap<>());
        vm.setModuleFinder(modules::get);
        vm.start(new Frame(main.root));
        try {
            for (int i = 0; i < 2_000_000 && !vm.isFinished(); i++) vm.step();
        } catch (PyException error) {
            throw new AssertionError(error.getMessage() + "\n" + error.pyTraceback(), error);
        }
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
