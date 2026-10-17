package net.mpy.vm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PilExtraTest {
    @Test void grayOperationsMatchPillow() throws Exception { StdlibTestSupport.golden("pil-extra-l"); }
    @Test void rgbOperationsMatchPillow() throws Exception { StdlibTestSupport.golden("pil-extra-rgb"); }
    @Test void rgbaOperationsMatchPillow() throws Exception { StdlibTestSupport.golden("pil-extra-rgba"); }
    @Test void floodfillMatchesPillow() throws Exception { StdlibTestSupport.golden("pil-floodfill"); }

    /** A fill of the largest image is one host call, not a pixel walk through the VM. */
    @Test void floodfillCostsAFewInstructionsOnTheLargestImage() throws Exception {
        Vm vm = StdlibTestSupport.start(String.join(String.valueOf((char) 10),
                "from PIL import Image, ImageDraw",
                "im = Image.new('RGB', (1024, 512), 'white')",
                "ImageDraw.Draw(im).rectangle((100, 100, 900, 400), outline='black')",
                "armed = True",
                "ImageDraw.floodfill(im, (500, 250), 'red')",
                "done = True",
                "assert im.getpixel((500, 250)) == (255, 0, 0) and im.getpixel((50, 50)) == (255, 255, 255)",
                "assert im.getpixel((100, 100)) == (0, 0, 0) and im.getpixel((899, 399)) == (255, 0, 0)",
                "ImageDraw.floodfill(im, (0, 0), (0, 0, 255), border=(0, 0, 0))",
                "assert im.getpixel((5, 5)) == (0, 0, 255) and im.getpixel((500, 250)) == (255, 0, 0)",
                "ImageDraw.floodfill(im, (1024, 0), 'green')",
                "assert im.getpixel((0, 0)) == (0, 0, 255)",
                "passed = True", ""));
        while (!Boolean.TRUE.equals(vm.globals.get("armed"))) vm.step(new int[] {1});
        int spent = 0;
        while (!Boolean.TRUE.equals(vm.globals.get("done"))) {
            int[] budget = {1};
            vm.step(budget);
            spent += 1 - budget[0];
        }
        // Parsing the colour name costs a few hundred instructions; the fill itself costs none per pixel.
        assertTrue(spent < 1000, "floodfill spent " + spent + " instructions");
        StdlibTestSupport.finish(vm);
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }

    @Test void filtersValidateArgumentsAndPreserveTheirSource() throws Exception {
        StdlibTestSupport.run("""
                from PIL import Image, ImageFilter, ImageChops, ImageStat
                def fails(kind, fn):
                    try:
                        fn()
                    except kind:
                        return
                    raise AssertionError('expected error')
                im = Image.new('RGB', (10, 8), (70, 90, 110))
                original = im.tobytes()
                fails(ValueError, lambda: im.filter(ImageFilter.BoxBlur(-1)))
                fails(ValueError, lambda: im.filter(ImageFilter.GaussianBlur(257)))
                fails(ValueError, lambda: im.filter(ImageFilter.GaussianBlur(float('nan'))))
                fails(ValueError, lambda: im.filter(ImageFilter.MedianFilter(4)))
                fails(ValueError, lambda: im.filter(ImageFilter.RankFilter(3, 9)))
                fails(ValueError, lambda: im.filter(ImageFilter.Kernel((3, 3), [0] * 9)))
                fails(ValueError, lambda: ImageChops.add(im, im, 0))
                fails(ValueError, lambda: ImageChops.difference(im, im.convert('L')))
                fails(NotImplementedError, lambda: ImageChops.logical_and(im, im))
                fails(ValueError, lambda: ImageStat.Stat(im, Image.new('L', (2, 2))))
                fails(ValueError, lambda: ImageStat.Stat([1, 2, 3]))
                stat = ImageStat.Stat(im, Image.new('L', im.size, 0))
                assert stat.count == [0, 0, 0]
                fails(ZeroDivisionError, lambda: stat.mean)
                assert im.tobytes() == original
                assert im.filter(ImageFilter.BoxBlur(256)).tobytes() == original
                passed = True
                """);
    }

    @Test void defaultFontMeasuresDrawsAndSurvivesSnapshot() throws Exception {
        Vm vm = StdlibTestSupport.start("""
                from PIL import Image, ImageFont, ImageDraw, ImageStat, ImageFilter, ImageEnhance
                font = ImageFont.load_default(18)
                assert font.getlength('Hello') > 0
                assert font.getlength('') == 0
                assert font.getbbox('') == (0, 0, 0, 0)
                assert font.getmetrics()[0] > 0
                im = Image.new('RGBA', (128, 80))
                draw = ImageDraw.Draw(im)
                draw.text((10, 5), 'Hello', fill='white', font=font)
                assert ImageStat.Stat(im.getchannel('A')).sum[0] > 0
                bbox = draw.textbbox((64, 40), 'Hi', font=font, anchor='mm', stroke_width=1)
                assert bbox[0] < 64 < bbox[2] and bbox[1] < 40 < bbox[3]
                kernel = ImageFilter.BoxBlur(1)
                enhancer = ImageEnhance.Brightness(im)
                before = im.tobytes()
                length = font.getlength('snapshot')
                armed = True
                yieldJava()
                assert font.getlength('snapshot') == length
                draw.multiline_text((64, 40), 'A\\nB', fill='yellow', font=font, anchor='mm', align='center', stroke_width=1, stroke_fill='blue')
                assert im.tobytes() != before
                assert im.filter(kernel).size == im.size
                assert enhancer.enhance(1).tobytes() == im.tobytes()
                assert font.font_variant(size=30).getlength('Hello') > font.getlength('Hello')
                passed = True
                """);
        while (!Boolean.TRUE.equals(vm.globals.get("armed"))) vm.step(new int[] {1});
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(), new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }

    @Test void fontErrorsSurfaceAsPythonExceptions() throws Exception {
        StdlibTestSupport.run("""
                from PIL import Image, ImageDraw, ImageFont
                from io import BytesIO
                def fails(kind, fn):
                    try:
                        fn()
                    except kind:
                        return
                    raise AssertionError('expected error')
                fails(OSError, lambda: ImageFont.truetype(BytesIO(b'invalid')))
                fails(ValueError, lambda: ImageFont.load_default(0))
                fails(ValueError, lambda: ImageFont.load_default(257))
                font = ImageFont.load_default()
                fails(ValueError, lambda: font.getlength('x' * 4097))
                fails(ValueError, lambda: font.getbbox('abc', anchor='bad'))
                fails(NotImplementedError, lambda: font.getlength('abc', direction='rtl'))
                fails(NotImplementedError, lambda: font.font_variant(layout_engine=ImageFont.Layout.RAQM))
                draw = ImageDraw.Draw(Image.new('RGB', (8, 8)))
                fails(ValueError, lambda: draw.text((0, 0), 'a', stroke_width=100))
                fails(ValueError, lambda: draw.text((0, 0), 'a', align='bad'))
                fails(NotImplementedError, lambda: draw.text((0, 0), 'a', embedded_color=True))
                passed = True
                """);
    }

    @Test void trueTypeBytesAndMetricsSurviveWithoutTheOriginalStream() throws Exception {
        Vm vm = StdlibTestSupport.start("""
                from PIL import Image, ImageFont, ImageDraw, ImageStat
                from io import BytesIO
                stream = BytesIO(font_bytes)
                font = ImageFont.truetype(stream, 20)
                assert not stream.closed
                stream.close()
                assert font.getname()[0] == 'MPY Test Shapes'
                assert abs(font.getlength('AB中') - 36) < 0.01
                assert font.getmetrics() == (16, 4)
                bbox = font.getbbox('AB中')
                im = Image.new('RGBA', (100, 60))
                draw = ImageDraw.Draw(im)
                armed = True
                yieldJava()
                assert font.getbbox('AB中') == bbox
                draw.text((4, 2), 'AB中', fill=(100, 200, 50, 150), font=font)
                assert ImageStat.Stat(im.getchannel('A')).extrema == [(0, 150)]
                draw.fontmode = '1'
                draw.text((4, 30), 'AB', fill='white', font=font.font_variant(size=14))
                assert ImageStat.Stat(im.getchannel('A')).extrema == [(0, 255)]
                passed = True
                """);
        vm.globals.put("font_bytes", new PyObj.Bytes(Files.readAllBytes(Path.of("src/test/resources/pil/test-shapes.ttf"))));
        while (!Boolean.TRUE.equals(vm.globals.get("armed"))) vm.step(new int[] {1});
        Vm restored = Vm.fromSnapshot(vm.saveSnapshot(), new HashMap<>(), null);
        StdlibTestSupport.finish(restored);
        assertEquals(Boolean.TRUE, restored.globals.get("passed"));
    }
}
