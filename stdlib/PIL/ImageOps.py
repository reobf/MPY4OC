"""Basic Pillow ImageOps, without metadata or palette processing."""
from PIL import Image


def flip(image):
    return image.transpose(Image.FLIP_TOP_BOTTOM)


def mirror(image):
    return image.transpose(Image.FLIP_LEFT_RIGHT)


def grayscale(image):
    return image.convert('L')


def invert(image):
    if image.mode not in ('L', 'RGB'):
        raise NotImplementedError('invert supports L and RGB')
    return image.point([255 - i for i in range(256)] * len(image.mode))


def posterize(image, bits):
    if image.mode not in ('L', 'RGB') or bits < 0 or bits > 8:
        raise ValueError('posterize requires L/RGB and 0..8 bits')
    return image.point([i & (255 << (8 - bits)) for i in range(256)] * len(image.mode))


def solarize(image, threshold=128):
    if image.mode not in ('L', 'RGB'):
        raise NotImplementedError('solarize supports L and RGB')
    return image.point([i if i < threshold else 255 - i for i in range(256)] * len(image.mode))


def _border(border):
    if isinstance(border, int):
        border = (border,) * 4
    elif len(border) == 2:
        border = tuple(border) * 2
    else:
        border = tuple(border)
    if len(border) != 4 or min(border) < 0:
        raise ValueError('border must have 1, 2 or 4 non-negative values')
    return border


def expand(image, border=0, fill=0):
    left, top, right, bottom = _border(border)
    out = Image.new(image.mode, (image.width + left + right, image.height + top + bottom), fill)
    out.paste(image, (left, top))
    return out


def crop(image, border=0):
    left, top, right, bottom = _border(border)
    return image.crop((left, top, image.width - right, image.height - bottom))


def _size(size):
    if len(size) != 2 or min(size) < 1:
        raise ValueError('size must have two positive dimensions')
    return tuple(size)


def contain(image, size, method=Image.BICUBIC):
    size = _size(size)
    if image.width * size[1] > image.height * size[0]:
        size = (size[0], max(1, round(image.height * size[0] / image.width)))
    elif image.width * size[1] < image.height * size[0]:
        size = (max(1, round(image.width * size[1] / image.height)), size[1])
    return image.resize(size, method)


def cover(image, size, method=Image.BICUBIC):
    size = _size(size)
    if image.width * size[1] > image.height * size[0]:
        size = (max(1, round(image.width * size[1] / image.height)), size[1])
    elif image.width * size[1] < image.height * size[0]:
        size = (size[0], max(1, round(image.height * size[0] / image.width)))
    return image.resize(size, method)


def _centering(centering):
    if len(centering) != 2 or min(centering) < 0 or max(centering) > 1:
        raise ValueError('centering must be two values in 0..1')
    return centering


def pad(image, size, method=Image.BICUBIC, color=None, centering=(0.5, 0.5)):
    size = _size(size)
    x, y = _centering(centering)
    scaled = contain(image, size, method)
    if scaled.size == size:
        return scaled
    out = Image.new(image.mode, size, color)
    out.paste(scaled, (round((size[0] - scaled.width) * x), round((size[1] - scaled.height) * y)))
    return out


def fit(image, size, method=Image.BICUBIC, bleed=0.0, centering=(0.5, 0.5)):
    size = _size(size)
    x, y = _centering(centering)
    if bleed != 0:
        raise NotImplementedError('fit bleed is not supported')
    # Integer crop bounds: subpixel crop/resampling is outside this subset.
    width, height = image.size
    if width * size[1] > height * size[0]:
        width = max(1, round(height * size[0] / size[1]))
    else:
        height = max(1, round(width * size[1] / size[0]))
    left, top = round((image.width - width) * x), round((image.height - height) * y)
    return image.crop((left, top, left + width, top + height)).resize(size, method)
