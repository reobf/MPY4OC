"""Pillow 12.0.0 reference values for ImageDraw.floodfill (test-only).

python -m pip install --target build/pillow-reference Pillow==12.0.0
python tools/pil-floodfill-golden.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/pillow-reference'))
from PIL import Image, ImageDraw, __version__

assert __version__ == '12.0.0', __version__
out = ['# Generated with Pillow 12.0.0 by tools/pil-floodfill-golden.py', 'from PIL import Image, ImageDraw']
cases = 0
COLORS = {
    'L': {'base': 40, 'near': 44, 'wall': 200, 'ink': 120, 'other': 90},
    'RGB': {'base': (40, 60, 80), 'near': (42, 61, 83), 'wall': (200, 10, 10), 'ink': (0, 120, 250), 'other': (90, 90, 90)},
    'RGBA': {'base': (40, 60, 80, 255), 'near': (42, 61, 83, 250), 'wall': (200, 10, 10, 255), 'ink': (0, 120, 250, 128), 'other': (90, 90, 90, 0)},
}
for mode, c in COLORS.items():
    # A walled room with near-base speckles, a second room of another colour and a gap in the wall.
    im = Image.new(mode, (9, 7), c['base'])
    draw = ImageDraw.Draw(im)
    draw.rectangle((1, 1, 6, 5), outline=c['wall'])
    draw.point([(3, 3), (4, 2), (0, 6), (8, 0)], fill=c['near'])
    draw.rectangle((7, 2, 8, 4), fill=c['other'])
    draw.point([(6, 3)], fill=c['base'])
    source = im.tobytes()
    out.append(f'source = {source!r}')
    for seed in ((2, 2), (0, 0), (3, 3), (7, 3), (1, 1), (8, 6), (-1, 2), (0, -1), (9, 0), (-10, 0)):
        for border in (None, c['wall']):
            for thresh in (0, 5, 30):
                for value in (c['ink'], c['base']):
                    case = Image.frombytes(mode, im.size, source)
                    ImageDraw.floodfill(case, seed, value, border=border, thresh=thresh)
                    out.append(f'im = Image.frombytes({mode!r}, (9, 7), source)')
                    out.append(f'ImageDraw.floodfill(im, {seed!r}, {value!r}, border={border!r}, thresh={thresh!r})')
                    out.append(f'assert im.tobytes() == {case.tobytes()!r}, ({mode!r}, {seed!r}, {value!r}, {border!r}, {thresh!r})')
                    cases += 1
out.append('passed = True')
(ROOT / 'src/test/resources/stdlib-pil-floodfill-golden.py').write_text('\n'.join(out) + '\n', encoding='utf-8')
print(f'{cases} Pillow floodfill comparisons written')
