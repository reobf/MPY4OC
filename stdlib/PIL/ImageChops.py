"""8-bit channel arithmetic for L/RGB/RGBA images."""
from PIL import Image

_native = __pil
blend = Image.blend
composite = Image.composite


def _binary(a, b, op, scale=1.0, offset=0):
    return Image._wrap(_native('chop', a._spec(), b._spec(), op, scale, offset))


def duplicate(image):
    return image.copy()


def constant(image, value):
    image._spec()
    return Image.new('L', image.size, value)


def invert(image):
    return image.point([255 - i for i in range(256)] * len(image.mode))


def difference(image1, image2):
    return _binary(image1, image2, 'difference')


def lighter(image1, image2):
    return _binary(image1, image2, 'lighter')


def darker(image1, image2):
    return _binary(image1, image2, 'darker')


def multiply(image1, image2):
    return _binary(image1, image2, 'multiply')


def screen(image1, image2):
    return _binary(image1, image2, 'screen')


def add(image1, image2, scale=1.0, offset=0):
    return _binary(image1, image2, 'add', scale, offset)


def subtract(image1, image2, scale=1.0, offset=0):
    return _binary(image1, image2, 'subtract', scale, offset)


def add_modulo(image1, image2):
    return _binary(image1, image2, 'add_modulo')


def subtract_modulo(image1, image2):
    return _binary(image1, image2, 'subtract_modulo')


def overlay(image1, image2):
    return _binary(image1, image2, 'overlay')


def hard_light(image1, image2):
    return _binary(image1, image2, 'hard_light')


def soft_light(image1, image2):
    return _binary(image1, image2, 'soft_light')


def offset(image, xoffset, yoffset=None):
    return Image._wrap(_native('offset', image._spec(), xoffset, xoffset if yoffset is None else yoffset))


def logical_and(image1, image2):
    raise NotImplementedError('logical channel operations require the unsupported mode 1')


logical_or = logical_and
logical_xor = logical_and
