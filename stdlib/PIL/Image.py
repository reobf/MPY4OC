"""Small, snapshot-safe images backed by VM bytearrays (L, RGB, RGBA)."""
from PIL import ImageColor, UnidentifiedImageError

_native = __pil
_open_file = open
MAX_IMAGE_PIXELS = 524288
_MAX_FILE_BYTES = 1048576


class Resampling:
    NEAREST = 0
    LANCZOS = 1
    BILINEAR = 2
    BICUBIC = 3
    BOX = 4
    HAMMING = 5


class Transpose:
    FLIP_LEFT_RIGHT = 0
    FLIP_TOP_BOTTOM = 1
    ROTATE_90 = 2
    ROTATE_180 = 3
    ROTATE_270 = 4
    TRANSPOSE = 5
    TRANSVERSE = 6


NEAREST, LANCZOS, BILINEAR, BICUBIC, BOX, HAMMING = 0, 1, 2, 3, 4, 5
FLIP_LEFT_RIGHT, FLIP_TOP_BOTTOM, ROTATE_90, ROTATE_180, ROTATE_270, TRANSPOSE, TRANSVERSE = 0, 1, 2, 3, 4, 5, 6


def _color(value, mode):
    return ImageColor.getcolor(value, mode) if isinstance(value, str) else value


def _wrap(spec):
    image = Image()
    image._set(spec)
    return image


def new(mode, size, color=0):
    return _wrap(_native('new', mode, tuple(size), _color(color, mode)))


def frombytes(mode, size, data, decoder_name='raw', *args):
    if decoder_name != 'raw' or args:
        raise NotImplementedError('only tightly packed raw pixels are supported')
    return _wrap(_native('frombytes', mode, tuple(size), data))


def open(fp, mode='r', formats=None):
    if mode != 'r':
        raise ValueError('bad mode')
    owned = isinstance(fp, str)
    stream = _open_file(fp, 'rb') if owned else fp
    try:
        stream.seek(0)
        data = stream.read(_MAX_FILE_BYTES + 1)
    finally:
        if owned:
            stream.close()
    if len(data) > _MAX_FILE_BYTES:
        raise ValueError('image file exceeds 1 MiB limit')
    try:
        spec, fmt = _native('decode', data)
    except OSError as error:
        raise UnidentifiedImageError(str(error))
    if formats is not None and fmt not in [f.upper() for f in formats]:
        raise UnidentifiedImageError('image format excluded by formats')
    image = _wrap(spec)
    image.format = fmt
    image.filename = fp if owned else getattr(fp, 'name', '')
    return image


def blend(im1, im2, alpha):
    return _wrap(_native('blend', im1._spec(), im2._spec(), alpha))


def alpha_composite(im1, im2):
    return _wrap(_native('alpha_composite', im1._spec(), im2._spec()))


def composite(image1, image2, mask):
    out = image2.copy()
    out.paste(image1, (0, 0), mask)
    return out


class _PixelAccess:
    def __init__(self, image):
        self._image = image

    def __getitem__(self, xy):
        return self._image.getpixel(xy)

    def __setitem__(self, xy, color):
        self._image.putpixel(xy, color)


class Image:
    def __init__(self):
        self._pixels = None
        self._mode = ''
        self._size = (0, 0)
        self.format = None
        self.info = {}

    def _set(self, spec):
        self._mode, width, height, self._pixels = spec
        self._size = (width, height)

    def _spec(self):
        if self._pixels is None:
            raise ValueError('Operation on closed image')
        return (self._mode, self.width, self.height, self._pixels)

    @property
    def mode(self):
        return self._mode

    @property
    def size(self):
        return self._size

    @property
    def width(self):
        return self._size[0]

    @property
    def height(self):
        return self._size[1]

    def close(self):
        self._pixels = None

    def __enter__(self):
        self._spec()
        return self

    def __exit__(self, *args):
        self.close()

    def load(self):
        self._spec()
        return _PixelAccess(self)

    def copy(self):
        out = _wrap(_native('copy', self._spec()))
        out.info = self.info.copy()
        return out

    def convert(self, mode=None):
        return _wrap(_native('convert', self._spec(), self.mode if mode is None else mode))

    def getpixel(self, xy):
        return _native('get', self._spec(), tuple(xy))

    def putpixel(self, xy, value):
        _native('put', self._spec(), tuple(xy), _color(value, self.mode))

    def getdata(self, band=None):
        if band is not None:
            return self.getchannel(band).getdata()
        return _native('data', self._spec())

    def putdata(self, data, scale=1.0, offset=0.0):
        if scale != 1 or offset != 0:
            raise NotImplementedError('putdata scale/offset are not supported')
        _native('putdata', self._spec(), list(data))

    def getbands(self):
        return tuple(self.mode)

    def getchannel(self, channel):
        if isinstance(channel, str):
            channel = self.getbands().index(channel)
        return _wrap(_native('channel', self._spec(), channel))

    def split(self):
        return tuple(self.getchannel(i) for i in range(len(self.mode)))

    def putalpha(self, alpha):
        self._set(_native('putalpha', self._spec(), alpha._spec() if isinstance(alpha, Image) else alpha))

    def point(self, lut, mode=None):
        if mode is not None and mode != self.mode:
            raise NotImplementedError('point mode conversion is not supported')
        if callable(lut):
            lut = [round(lut(i)) for i in range(256)] * len(self.mode)
        return _wrap(_native('point', self._spec(), list(lut)))

    def histogram(self, mask=None, extrema=None):
        if extrema is not None:
            raise NotImplementedError('histogram extrema is not supported')
        return _native('histogram', self._spec(), None if mask is None else mask._spec())

    def filter(self, filter):
        if isinstance(filter, type):
            filter = filter()
        return filter.filter(self)

    def crop(self, box=None):
        if box is None:
            return self.copy()
        return _wrap(_native('crop', self._spec(), tuple(round(v) for v in box)))

    def resize(self, size, resample=None, box=None, reducing_gap=None):
        if reducing_gap is not None:
            raise NotImplementedError('reducing_gap is not supported')
        if box is not None:
            return self.crop(box).resize(size, resample)
        return _wrap(_native('resize', self._spec(), tuple(size), BICUBIC if resample is None else resample))

    def thumbnail(self, size, resample=BICUBIC, reducing_gap=None):
        from PIL import ImageOps
        if reducing_gap is not None:
            raise NotImplementedError('reducing_gap is not supported')
        if size[0] < 1 or size[1] < 1:
            raise ValueError('size must be positive')
        fitted = ImageOps.contain(self, (min(size[0], self.width), min(size[1], self.height)), resample)
        self._set(fitted._spec())

    def transpose(self, method):
        return _wrap(_native('transpose', self._spec(), method))

    def paste(self, im, box=None, mask=None):
        if isinstance(box, Image):
            if mask is not None:
                raise ValueError('mask supplied twice')
            mask, box = box, None
        if box is None:
            box = (0, 0)
        box = tuple(box)
        if len(box) == 2 and not isinstance(im, Image):
            if mask is None:
                raise ValueError('cannot determine region size; use 4-item box')
            box = (box[0], box[1], box[0] + mask.width, box[1] + mask.height)
        if isinstance(im, Image) and im.mode != self.mode:
            im = im.convert(self.mode)
        _native('paste', self._spec(), im._spec() if isinstance(im, Image) else _color(im, self.mode), box, None if mask is None else mask._spec())

    def tobytes(self, encoder_name='raw', *args):
        self._spec()
        if encoder_name != 'raw' or args:
            raise NotImplementedError('only tightly packed raw pixels are supported')
        return bytes(self._pixels)

    def frombytes(self, data, decoder_name='raw', *args):
        self._spec()
        self._set(frombytes(self.mode, self.size, data, decoder_name, *args)._spec())

    def _encoded(self, format='PNG', quality=75):
        return _native('encode', self._spec(), format, quality)

    def save(self, fp, format=None, **params):
        quality = params.pop('quality', 75)
        if params:
            raise NotImplementedError('unsupported save option: ' + next(iter(params)))
        owned = isinstance(fp, str)
        if format is None:
            name = fp if owned else getattr(fp, 'name', '')
            extension = name.rsplit('.', 1)[-1].upper()
            if extension not in ('PNG', 'JPEG', 'JPG', 'BMP'):
                raise ValueError('unknown file extension; specify format')
            format = extension
        data = self._encoded(format, quality)
        stream = _open_file(fp, 'wb') if owned else fp
        try:
            stream.write(data)
        finally:
            if owned:
                stream.close()
