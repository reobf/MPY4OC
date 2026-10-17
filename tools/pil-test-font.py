"""Generate an original tiny TTF test fixture, not a third-party font.

Test-only dependency: pip install --target build/pillow-reference fonttools==4.60.1
The rectangles/triangles below are deliberately simple; no external font is used.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/pillow-reference'))
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

builder = FontBuilder(1000, isTTF=True)
order = ['.notdef', 'space', 'A', 'B', 'uni4E2D']
builder.setupGlyphOrder(order)
builder.setupCharacterMap({32: 'space', 65: 'A', 66: 'B', 0x4E2D: 'uni4E2D'})
shapes = {
    '.notdef': [(100, 0), (500, 0), (500, 700), (100, 700)],
    'space': [],
    'A': [(50, 0), (550, 0), (300, 700)],
    'B': [(100, 0), (500, 0), (500, 700), (100, 700)],
    'uni4E2D': [(50, 250), (250, 250), (250, 0), (350, 0), (350, 250), (550, 250), (550, 450), (350, 450), (350, 700), (250, 700), (250, 450), (50, 450)],
}
glyphs = {}
for name, points in shapes.items():
    pen = TTGlyphPen(None)
    if points:
        pen.moveTo(points[0])
        for point in points[1:]:
            pen.lineTo(point)
        pen.closePath()
    glyphs[name] = pen.glyph()
builder.setupGlyf(glyphs)
builder.setupHorizontalMetrics({name: (600, 0) for name in order})
builder.setupHorizontalHeader(ascent=800, descent=-200)
builder.setupNameTable({'familyName': 'MPY Test Shapes', 'styleName': 'Regular', 'uniqueFontIdentifier': 'MPY-Test-Shapes-1', 'fullName': 'MPY Test Shapes', 'psName': 'MPYTestShapes'})
builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
builder.setupPost()
builder.setupMaxp()
builder.setupHead(created=2082844800, modified=2082844800)
builder.font.recalcTimestamp = False
path = ROOT / 'src/test/resources/pil/test-shapes.ttf'
builder.save(path)
print(f'Wrote original test font: {path.stat().st_size} bytes')
