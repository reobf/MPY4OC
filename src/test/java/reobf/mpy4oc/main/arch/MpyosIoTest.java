package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class MpyosIoTest {
    private TkPythonTest.Host host(String source) throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/stdio-fixture.py"));
        return TkPythonTest.shellHost(fixture + "\n" + source);
    }

    @Test void pilReadsAndWritesThroughMpyosCwdAndMountedDisks() throws Exception {
        var h = host("""
                from PIL import Image, ImageDraw
                os.chdir('/home')
                im = Image.new('RGB', (12, 8), 'navy')
                ImageDraw.Draw(im).rectangle((2, 2, 8, 6), fill='orange')
                im.save('picture.png')
                assert boot.files['/home/picture.png'].startswith(b'\\x89PNG')
                assert not boot.handles
                with Image.open('picture.png') as loaded:
                    assert loaded.format == 'PNG'
                    assert loaded.filename == 'picture.png'
                    assert loaded.tobytes() == im.tobytes()
                    loaded.save('/mnt/oth/copy.bmp')
                assert not boot.handles and not other.handles
                assert other.files['/copy.bmp'].startswith(b'BM')
                assert Image.open('/mnt/oth/copy.bmp').tobytes() == im.tobytes()
                boot.files['/bad.png'] = b'bad image'
                raises(OSError, lambda: Image.open('/bad.png'))
                assert not boot.handles
                raises(OSError, lambda: im.save('/mnt/ro/denied.png'))
                assert not readonly.handles
                passed = True
                """);
        h.runUntil("passed");
    }

    @Test void pilFontsLoadFromTheProcessWorkingDirectory() throws Exception {
        var h = host("""
                from PIL import ImageFont, Image, ImageDraw, ImageStat
                os.chdir('/home')
                boot.files['/home/font.ttf'] = font_bytes
                font = ImageFont.truetype('font.ttf', 20)
                assert not boot.handles
                del boot.files['/home/font.ttf']
                assert abs(font.getlength('AB') - 24) < 0.01
                im = Image.new('L', (60, 30))
                ImageDraw.Draw(im).text((2, 2), 'AB', fill=255, font=font)
                assert ImageStat.Stat(im).sum[0] > 0
                raises(OSError, lambda: ImageFont.truetype('missing.ttf'))
                assert not boot.handles
                passed = True
                """);
        h.vm.globals.put("font_bytes", new net.mpy.runtime.PyObj.Bytes(Files.readAllBytes(Path.of("src/test/resources/pil/test-shapes.ttf"))));
        h.runUntil("passed");
    }

    @Test void builtinFilesRouteCwdAndMountsAndDecodeShortUtf8Reads() throws Exception {
        var h = host("""
                os.chdir('/home')
                with open('notes.txt', 'w', encoding='utf-8', newline='\\r\\n') as f:
                    assert f.write('Aé水😀\\nnext\\n') == 10
                    assert f.writable() and not f.readable()
                    assert boot.files['/home/notes.txt'] == 'Aé水😀\\r\\nnext\\r\\n'.encode('utf-8')
                assert f.closed and not boot.handles
                with open('notes.txt') as f:
                    assert f.read(2) == 'Aé'
                    pos = f.tell()
                    assert pos == 3
                    assert f.readline() == '水😀\\n'
                    f.seek(pos)
                    assert f.read(2) == '水😀'
                    assert f.readline() == '\\n'
                    assert list(f) == ['next\\n']
                    assert f.read() == ''
                with io.open('notes.txt', newline='') as f:
                    assert f.readlines() == ['Aé水😀\\r\\n', 'next\\r\\n']
                with open('/mnt/oth/data', 'wb') as f:
                    f.write(b'abc')
                    os.chdir('/work')
                    vfs.mounts['oth'] = 'readonly'
                    f.write(bytearray(b'\\x00\\xff'))
                assert other.files['/data'] == b'abc\\x00\\xff' and not other.handles
                assert not readonly.files
                passed = True
                """);
        h.runUntil("passed");
    }

    @Test void modesErrorsAndBinarySeekDoNotLoseOrTruncateData() throws Exception {
        var h = host("""
                boot.files['/data'] = b'old'
                for mode in ('r+', 'w+', 'x'):
                    raises(io.UnsupportedOperation, lambda: open('/data', mode))
                    assert boot.files['/data'] == b'old'
                for mode in ('', 'rw', 'rr', 'rbt', 'q'):
                    raises(ValueError, lambda: open('/data', mode))
                raises(ValueError, lambda: open('/data', 'wb', encoding='utf-8'))
                raises(ValueError, lambda: open('/data', 'w', encoding='ascii'))
                raises(FileNotFoundError, lambda: open('/missing'))
                raises(OSError, lambda: open('/home'))
                raises(OSError, lambda: open('/mnt/ro/f', 'w'))
                assert not boot.handles and not readonly.handles
                with open('/data', 'wb') as f:
                    raises(TypeError, lambda: f.write('bad'))
                    f.write(b'012345')
                    assert f.tell() == 6
                    f.seek(1)
                    f.write(b'X')
                    f.flush()
                    raises(io.UnsupportedOperation, lambda: f.read())
                with open('/data', 'ab') as f:
                    f.seek(0)
                    f.writelines([b'!', b'\\n'])
                with open('/data', 'rb') as f:
                    assert f.read(3) == b'0X2'
                    assert f.tell() == 3
                    assert f.seek(-1, 1) == 2
                    assert f.read(2) == b'23'
                    assert f.seek(-2, 2) == 6
                    assert f.readline() == b'!\\n'
                    assert list(f) == []
                raises(ValueError, lambda: f.read())
                f.close()
                boot.files['/bad'] = b'A\\xe2(B\\xffC'
                with open('/bad') as f:
                    raises(UnicodeDecodeError, lambda: f.read())
                with open('/bad', errors='replace') as f:
                    assert f.read() == 'A�(B�C'
                with open('/bad', errors='ignore') as f:
                    assert f.read() == 'A(BC'
                assert not boot.handles
                passed = True
                """);
        h.runUntil("passed");
    }

    @Test void programOwnedFilesCloseAfterThreadsAndInputUsesForegroundTerminal() throws Exception {
        var h = host("""
                import _process
                sh = shell.Shell(tty, 'boot', globals())
                class Reader:
                    def __init__(self):
                        self.prompts = []
                        self.lines = ['hello', '', None]
                    def read(self, prompt, interrupt=False):
                        assert interrupt
                        self.prompts.append(prompt)
                        return self.lines.pop(0)
                reader = Reader()
                _process.runtime().tty = reader
                assert input('Name: ') == 'hello' and input() == ''
                raises(EOFError, lambda: input(123))
                assert reader.prompts == ['Name: ', '', '123']
                boot.files['/bin/iochild.py'] = '''
                import threading
                SHARED['file'] = open('thread.txt', 'w')
                def worker():
                    SHARED['file'].write('thread')
                t = threading.Thread(target=worker)
                t.start()
                t.join()
                try:
                    input('background must not consume input')
                except OSError:
                    SHARED['blocked'] = True
                '''.encode('utf-8')
                _process.runtime().tty = tty
                sh.spawn('iochild', [], cwd='/home')
                wait(lambda: sh.jobs[-1]['done'] and SHARED['file'].closed)
                assert SHARED['blocked'] and SHARED['file'].closed
                assert boot.files['/home/thread.txt'] == b'thread'
                assert not boot.handles
                assert reader.prompts == ['Name: ', '', '123']
                passed = True
                """);
        h.runUntil("passed");
    }

    @Test void fileBufferHandleAndBuiltinAliasSurviveSnapshot() throws Exception {
        var h = host("""
                boot.files['/saved'] = 'é水\\r\\nlast'.encode('utf-8')
                saved_open = open
                f = saved_open('/saved', **{'mode': 'r'})
                assert f.read(1) == 'é'
                ready = True
                while not globals().get('resume'):
                    computer._yield_tick()
                assert f.readline() == '水\\n'
                assert f.read() == 'last'
                f.close()
                with saved_open('/saved', 'rb') as other_file:
                    assert other_file.read(2) == b'\\xc3\\xa9'
                assert not boot.handles
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
