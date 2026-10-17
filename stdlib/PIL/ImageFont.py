"""ImageFont subset backed by Java2D, with serializable font bytes and size.

Metrics and glyph edges may differ from Pillow/FreeType. The default Dialog font
depends on the server JRE; use the same TTF/OTF file for more consistent results.
"""
_native = __pil
_open_file = open
MAX_STRING_LENGTH = 4096


class Layout:
    BASIC = 0
    RAQM = 1


def _options(direction, features, language):
    if direction is not None or features is not None or language is not None:
        raise NotImplementedError('direction/features/language are not supported by this font subset')


def load_default(size=None):
    return ImageFont(None, 10 if size is None else size)


def truetype(font, size=10, index=0, encoding='', layout_engine=None):
    return FreeTypeFont(font, size, index, encoding, layout_engine)


class ImageFont:
    def __init__(self, data=None, size=10):
        self._data = data
        self.size = size
        self.layout_engine = Layout.BASIC
        _native('font', 'check', self._spec())

    def _spec(self):
        return (self._data, self.size)

    def _text(self, text):
        if not isinstance(text, str):
            raise TypeError('text must be a string')
        if len(text) > MAX_STRING_LENGTH:
            raise ValueError('text is too long')
        return text

    def getbbox(self, text, mode='', direction=None, features=None, language=None, stroke_width=0, anchor=None):
        _options(direction, features, language)
        return _native('font', 'bbox', self._spec(), self._text(text), anchor, 4, 'left', stroke_width)

    def getlength(self, text, mode='', direction=None, features=None, language=None):
        _options(direction, features, language)
        text = self._text(text)
        if '\n' in text:
            raise ValueError('cannot measure length of multiline text')
        return _native('font', 'length', self._spec(), text, None, 4, 'left', 0)

    def getmetrics(self):
        return _native('font', 'metrics', self._spec())

    def getname(self):
        return _native('font', 'name', self._spec())

    def font_variant(self, font=None, size=None, index=None, encoding=None, layout_engine=None):
        if font is not None:
            return truetype(font, self.size if size is None else size, 0 if index is None else index, '' if encoding is None else encoding, layout_engine)
        if index not in (None, 0) or encoding not in (None, '') or layout_engine not in (None, Layout.BASIC):
            raise NotImplementedError('font collections, encoding selection and RAQM are not supported')
        return ImageFont(self._data, self.size if size is None else size)

    def getmask(self, *args, **kwargs):
        raise NotImplementedError('raw ImagingCore glyph masks are not exposed; use ImageDraw.text')


class FreeTypeFont(ImageFont):
    """Pillow-compatible class name; rendering uses Java2D, not FreeType."""
    def __init__(self, font, size=10, index=0, encoding='', layout_engine=None):
        if index != 0 or encoding != '' or layout_engine not in (None, Layout.BASIC):
            raise NotImplementedError('font collections, encoding selection and RAQM are not supported')
        owned = isinstance(font, str)
        stream = _open_file(font, 'rb') if owned else font
        try:
            stream.seek(0)
            data = stream.read(4194305)
        finally:
            if owned:
                stream.close()
        self.path = font if owned else None
        ImageFont.__init__(self, bytes(data), size)
