"""Pillow 12.0.0 reference values for enhancement, filtering, chops and statistics."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/pillow-reference'))
from PIL import Image, ImageChops, ImageEnhance, ImageFilter, ImageStat, __version__

assert __version__ == '12.0.0'
total = 0
for mode in ('L', 'RGB', 'RGBA'):
    out = ['# Generated with Pillow 12.0.0 by tools/pil-extra-golden.py',
           'from PIL import Image, ImageChops, ImageEnhance, ImageFilter, ImageStat']
    scope = dict(Image=Image, ImageChops=ImageChops, ImageEnhance=ImageEnhance, ImageFilter=ImageFilter, ImageStat=ImageStat)

    def setup(code):
        exec(code, scope)
        out.append(code)

    def pixels(expr, tolerance=0):
        global total
        im = eval(expr, scope)
        out.append('result = ' + expr)
        out.append(f'assert result.size == {im.size!r} and result.mode == {im.mode!r}, {expr!r}')
        if tolerance:
            out.append(f'assert max([abs(a-b) for a,b in zip(result.tobytes(), {im.tobytes()!r})]) <= {tolerance}, {expr!r}')
        else:
            out.append(f'assert result.tobytes() == {im.tobytes()!r}, {expr!r}')
        total += 1

    setup(f'im = Image.frombytes({mode!r}, (7, 6), bytes((i * 37 + 11) % 256 for i in range({42 * len(mode)})))')
    setup(f'other = Image.frombytes({mode!r}, (7, 6), bytes((i * 63 + 97) % 256 for i in range({42 * len(mode)})))')
    for operation in ('difference', 'lighter', 'darker', 'multiply', 'screen', 'add_modulo', 'subtract_modulo', 'overlay', 'hard_light', 'soft_light'):
        pixels(f'ImageChops.{operation}(im, other)')
    for operation in ('add', 'subtract'):
        for scale, offset in ((1, 0), (2, 12), (0.75, -30), (-2, 200)):
            pixels(f'ImageChops.{operation}(im, other, {scale}, {offset})')
    pixels('ImageChops.difference(im, other.crop((0, 0, 3, 2)))')
    for operation in ('invert(im)', 'constant(im, 73)', 'duplicate(im)', 'offset(im, 3)', 'offset(im, -2, 7)'):
        pixels('ImageChops.' + operation)
    for cls in ('Color', 'Contrast', 'Brightness', 'Sharpness'):
        for factor in (-0.5, 0, 0.5, 1, 1.75):
            pixels(f'ImageEnhance.{cls}(im).enhance({factor})', 1 if cls == 'Sharpness' else 0)
    for preset in ('BLUR', 'CONTOUR', 'DETAIL', 'EDGE_ENHANCE', 'EDGE_ENHANCE_MORE', 'EMBOSS', 'FIND_EDGES', 'SHARPEN', 'SMOOTH', 'SMOOTH_MORE'):
        pixels(f'im.filter(ImageFilter.{preset})', 1)
    pixels('im.filter(ImageFilter.Kernel((3, 3), [1, 2, 3, 4, 5, 6, 7, 8, 9], 45, -3))', 1)
    for cls in ('MinFilter', 'MaxFilter', 'MedianFilter'):
        for size in (1, 3, 5):
            pixels(f'im.filter(ImageFilter.{cls}({size}))')
    pixels('im.filter(ImageFilter.RankFilter(3, 2))')
    for cls in ('BoxBlur', 'GaussianBlur'):
        for radius in (0, 0.5, 1, 2.3, (0, 3), (2, 0), 12):
            pixels(f'im.filter(ImageFilter.{cls}({radius!r}))', 1)
    pixels('im.filter(ImageFilter.UnsharpMask(2, 150, 3))', 2)
    setup('mask = Image.frombytes("L", (7, 6), bytes((255 if i % 3 else 0) for i in range(42)))')
    for arg in ('im', 'im, mask', 'im.histogram()'):
        setup('stat = ImageStat.Stat(' + arg + ')')
        for attr in ('count', 'sum', 'sum2', 'mean', 'median', 'rms', 'var', 'stddev', 'extrema'):
            expected = getattr(scope['stat'], attr)
            if attr in ('rms', 'var', 'stddev', 'mean'):
                out.append(f'assert max([abs(a-b) for a,b in zip(stat.{attr}, {expected!r})]) < 0.000001, {attr!r}')
            else:
                out.append(f'assert stat.{attr} == {expected!r}, {attr!r}')
            total += 1
    out.append('passed = True')
    (ROOT / f'src/test/resources/stdlib-pil-extra-{mode.lower()}-golden.py').write_text('\n'.join(out) + '\n', encoding='utf-8')
print(f'{total} additional Pillow comparisons written')
