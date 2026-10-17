"""Brightness, contrast, saturation and sharpness; alpha is preserved."""
from PIL import Image, ImageFilter, ImageStat


class _Enhance:
    def enhance(self, factor):
        return Image.blend(self.degenerate, self.image, factor)

    def _alpha(self):
        if self.image.mode == 'RGBA':
            self.degenerate.putalpha(self.image.getchannel('A'))


class Brightness(_Enhance):
    def __init__(self, image):
        self.image = image
        self.degenerate = Image.new(image.mode, image.size, 0)
        self._alpha()


class Contrast(_Enhance):
    def __init__(self, image):
        self.image = image
        gray = image.convert('L')
        average = int(ImageStat.Stat(gray).mean[0] + 0.5)
        self.degenerate = Image.new('L', image.size, average).convert(image.mode)
        self._alpha()


class Color(_Enhance):
    def __init__(self, image):
        self.image = image
        self.degenerate = image.convert('L').convert(image.mode)
        self._alpha()


class Sharpness(_Enhance):
    def __init__(self, image):
        self.image = image
        self.degenerate = image.filter(ImageFilter.SMOOTH)
        self._alpha()
