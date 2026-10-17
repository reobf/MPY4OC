package net.mpy.vm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PilTest {
    @Test void pixelsAndColorsMatchPillow() throws Exception {
        StdlibTestSupport.golden("pil");
    }

    @Test void decodeIndependentPillowFilesAndEncodeWithoutHostFilesystem() throws Exception {
        for (String mode : new String[] {"L", "RGB", "RGBA"}) for (String format : new String[] {"PNG", "JPEG", "GIF", "BMP"}) {
            if (mode.equals("RGBA") && !format.equals("PNG")) continue;
            Path file = Path.of("src/test/resources/pil/" + mode.toLowerCase() + "." + format.toLowerCase());
            Vm vm = StdlibTestSupport.start("""
                    from PIL import Image
                    from io import BytesIO
                    src = BytesIO(encoded)
                    im = Image.open(src).convert(mode)
                    assert im.size == (5, 3)
                    assert not src.closed
                    raw = im.tobytes()
                    assert len(raw) == len(expected)
                    assert max([abs(a-b) for a,b in zip(raw, expected)]) <= tolerance
                    for fmt in ('PNG', 'BMP', 'JPEG'):
                        if mode == 'RGBA' and fmt != 'PNG':
                            continue
                        out = BytesIO()
                        im.save(out, format=fmt)
                        assert not out.closed
                        restored = Image.open(out).convert(mode)
                        assert restored.size == im.size
                        if fmt != 'JPEG':
                            assert restored.tobytes() == raw
                    passed = True
                    """);
            vm.globals.put("encoded", new PyObj.Bytes(Files.readAllBytes(file)));
            vm.globals.put("expected", new PyObj.Bytes(Files.readAllBytes(Path.of(file + ".raw"))));
            vm.globals.put("mode", mode);
            vm.globals.put("tolerance", format.equals("JPEG") ? 3L : 0L);
            try { StdlibTestSupport.finish(vm); } catch (AssertionError e) { throw new AssertionError(file.toString(), e); }
            assertEquals(Boolean.TRUE, vm.globals.get("passed"));
        }
    }

    @Test void errorsLimitsCloseAndAtomicMutations() throws Exception {
        StdlibTestSupport.run("""
                from PIL import Image, ImageDraw, ImageOps, UnidentifiedImageError
                from io import BytesIO
                def fails(kind, fn):
                    try:
                        fn()
                    except kind:
                        return
                    raise AssertionError('operation should fail')
                fails(NotImplementedError, lambda: Image.new('CMYK', (2, 2)))
                fails(ValueError, lambda: Image.new('RGB', (1025, 1)))
                fails(ValueError, lambda: Image.new('RGBA', (1024, 1024)))
                fails(ValueError, lambda: Image.new('RGB', (0, 1)))
                fails(UnidentifiedImageError, lambda: Image.open(BytesIO(b'bad image')))
                im = Image.new('RGB', (8, 6), 'navy')
                original = im.tobytes()
                fails(IndexError, lambda: im.getpixel((8, 0)))
                fails(ValueError, lambda: im.putdata([(1, 2, 3), (300, 0, 0)]))
                assert im.tobytes() == original
                fails(ValueError, lambda: im.paste('red', (0, 0, 8, 6), Image.new('L', (2, 2))))
                assert im.tobytes() == original
                fails(ValueError, lambda: im.resize((3, 3), 77))
                fails(ValueError, lambda: im.transpose(99))
                fails(ValueError, lambda: im.crop((2, 2, 1, 1)))
                fails(ValueError, lambda: im.point([1, 2]))
                fails(NotImplementedError, lambda: ImageDraw.Draw(im).text((0, 0), 'hi', direction='rtl'))
                fails(NotImplementedError, lambda: im.save(BytesIO(), 'WEBP'))
                px = im.load()
                px[2, 3] = (1, 2, 3)
                assert px[2, 3] == (1, 2, 3)
                im.thumbnail((4, 4))
                assert im.size == (4, 3)
                im.thumbnail((8, 8))
                assert im.size == (4, 3)
                im.close()
                im.close()
                fails(ValueError, lambda: px[0, 0])
                fails(ValueError, lambda: im.copy())
                with Image.new('L', (2, 2)) as opened:
                    assert opened.getpixel((0, 0)) == 0
                fails(ValueError, lambda: opened.load())
                passed = True
                """);
    }

    @Test void imagesAndPixelAccessKeepIdentityThroughSnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
                from PIL import Image, ImageDraw
                im = Image.new('RGBA', (40, 30), (1, 2, 3, 4))
                px = im.load()
                draw = ImageDraw.Draw(im)
                independent = im.copy()
                armed = True
                yieldJava()
                px[3, 4] = (90, 80, 70, 60)
                assert im.getpixel((3, 4)) == (90, 80, 70, 60)
                assert independent.getpixel((3, 4)) == (1, 2, 3, 4)
                draw.rectangle((0, 0, 2, 2), fill='red')
                assert px[1, 1] == (255, 0, 0, 255)
                passed = True
                """);
        while (!Boolean.TRUE.equals(vm.globals.get("armed"))) vm.step(new int[] {1});
        long before = vm.estimatedMemory();
        assertTrue(before > 2 * 40 * 30 * 4, "pixel bytearrays count towards VM memory");
        HostRegistry registry = new HostRegistry();
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(registry), new HashMap<>(), registry);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }

    @Test void drawingCurvesAndWideLinesIsHeadlessAndClipped() throws Exception {
        StdlibTestSupport.run("""
                from PIL import Image, ImageDraw
                im = Image.new('RGBA', (30, 20))
                d = ImageDraw.Draw(im)
                d.ellipse((3, 3, 17, 17), fill=(255, 0, 0, 100))
                assert im.getpixel((10, 10)) == (255, 0, 0, 100)
                assert im.getpixel((0, 0)) == (0, 0, 0, 0)
                d.polygon([(20, 3), (28, 3), (24, 14)], fill='blue')
                assert im.getpixel((24, 6)) == (0, 0, 255, 255)
                d.line([(-100, 18), (100, 18)], fill='white', width=2)
                assert im.getpixel((15, 18)) == (255, 255, 255, 255)
                passed = True
                """);
    }

    @Test void imageBuffersRespectTheVmAllocationBudget() throws Exception {
        Vm vm = StdlibTestSupport.start("""
                from PIL import Image
                armed = True
                yieldJava()
                try:
                    Image.new('RGBA', (100, 100))
                    raise AssertionError('pixel allocation bypassed VM budget')
                except MemoryError:
                    pass
                small = Image.new('RGB', (10, 10), 'red')
                assert small.getpixel((9, 9)) == (255, 0, 0)
                passed = True
                """);
        while (!Boolean.TRUE.equals(vm.globals.get("armed"))) vm.step(new int[] {1});
        long previous = net.mpy.runtime.Ops.MAX_SEQ_LEN;
        Limits limits = vm.limits(); limits.maxSeqLength = 4096;
        try {
            vm.setLimits(limits);
            StdlibTestSupport.finish(vm);
            assertEquals(Boolean.TRUE, vm.globals.get("passed"));
        } finally { net.mpy.runtime.Ops.MAX_SEQ_LEN = previous; }
    }
}
