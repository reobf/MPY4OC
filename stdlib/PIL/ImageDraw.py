"""Basic headless drawing. Curves/wide-line edges may differ from Pillow."""
from PIL import Image

_native = __pil


def Draw(im, mode=None):
    return ImageDraw(im, mode)


def floodfill(image, xy, value, border=None, thresh=0):
    """Fill the region around xy like Pillow's ImageDraw.floodfill, in one host call.
    Colours may also be names or #rrggbb strings; an RGB tuple on an RGBA image is opaque."""
    _native('floodfill', image._spec(), tuple(xy), Image._color(value, image.mode),
            None if border is None else Image._color(border, image.mode), thresh)


class ImageDraw:
    def __init__(self, im, mode=None):
        im._spec()
        if mode is not None and mode != im.mode:
            raise NotImplementedError('drawing in a different mode is not supported')
        self._image = im
        self.ink = 0 if im.mode == 'L' else -1
        self.fontmode = 'L'
        self.font = None

    def _draw(self, shape, xy, fill, outline, width):
        coords = []
        for value in xy:
            if isinstance(value, (tuple, list)):
                coords.extend(value)
            else:
                coords.append(value)
        im = self._image
        if fill is None and outline is None:
            if shape in ('point', 'line'):
                fill = self.ink
            else:
                outline = self.ink
        _native('draw', im._spec(), shape, [int(v) for v in coords], Image._color(fill, im.mode), Image._color(outline, im.mode), width)

    def point(self, xy, fill=None):
        self._draw('point', xy, fill, None, 1)

    def line(self, xy, fill=None, width=0, joint=None):
        if joint is not None:
            raise NotImplementedError('line joint option is not supported')
        self._draw('line', xy, fill, None, width)

    def rectangle(self, xy, fill=None, outline=None, width=1):
        self._draw('rectangle', xy, fill, outline, width)

    def ellipse(self, xy, fill=None, outline=None, width=1):
        self._draw('ellipse', xy, fill, outline, width)

    def polygon(self, xy, fill=None, outline=None, width=1):
        self._draw('polygon', xy, fill, outline, width)

    def getfont(self):
        if self.font is None:
            from PIL import ImageFont
            self.font = ImageFont.load_default()
        return self.font

    def _font(self, font, font_size, direction, features, language, embedded_color):
        from PIL import ImageFont
        ImageFont._options(direction, features, language)
        if embedded_color:
            raise NotImplementedError('embedded color glyphs are not supported')
        if font is not None:
            return font
        return ImageFont.load_default(font_size) if font_size is not None else self.getfont()

    def text(self, xy, text, fill=None, font=None, anchor=None, spacing=4, align='left', direction=None, features=None, language=None, stroke_width=0, stroke_fill=None, embedded_color=False, font_size=None):
        font = self._font(font, font_size, direction, features, language, embedded_color)
        im = self._image
        fill = self.ink if fill is None else fill
        _native('text', im._spec(), font._spec(), font._text(text), tuple(xy), Image._color(fill, im.mode), anchor, spacing, align, stroke_width, Image._color(stroke_fill, im.mode), self.fontmode)

    def textbbox(self, xy, text, font=None, anchor=None, spacing=4, align='left', direction=None, features=None, language=None, stroke_width=0, embedded_color=False, font_size=None):
        font = self._font(font, font_size, direction, features, language, embedded_color)
        left, top, right, bottom = _native('font', 'bbox', font._spec(), font._text(text), anchor, spacing, align, stroke_width)
        return (left + xy[0], top + xy[1], right + xy[0], bottom + xy[1])

    def textlength(self, text, font=None, direction=None, features=None, language=None, embedded_color=False, font_size=None):
        font = self._font(font, font_size, direction, features, language, embedded_color)
        return font.getlength(text)

    def multiline_text(self, *args, **kwargs):
        return self.text(*args, **kwargs)

    def multiline_textbbox(self, *args, **kwargs):
        return self.textbbox(*args, **kwargs)
