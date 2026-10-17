"""Bounded headless filters. Radius <= 256; rank windows <= 31."""
from PIL import Image

_native = __pil


class Filter:
    def filter(self, image):
        raise NotImplementedError('filter is not implemented')


class Kernel(Filter):
    def __init__(self, size, kernel, scale=None, offset=0):
        self.size = tuple(size)
        self.kernel = list(kernel)
        if self.size not in ((3, 3), (5, 5)) or len(self.kernel) != self.size[0] * self.size[1]:
            raise ValueError('kernel must be 3x3 or 5x5')
        self.scale = sum(self.kernel) if scale is None else scale
        self.offset = offset

    def filter(self, image):
        return Image._wrap(_native('kernel', image._spec(), self.size[0], self.kernel, self.scale, self.offset))


class RankFilter(Filter):
    def __init__(self, size, rank):
        self.size, self.rank = size, rank

    def filter(self, image):
        return Image._wrap(_native('rank', image._spec(), self.size, self.rank))


class MinFilter(RankFilter):
    def __init__(self, size=3):
        RankFilter.__init__(self, size, 0)


class MaxFilter(RankFilter):
    def __init__(self, size=3):
        RankFilter.__init__(self, size, size * size - 1)


class MedianFilter(RankFilter):
    def __init__(self, size=3):
        RankFilter.__init__(self, size, size * size // 2)


class BoxBlur(Filter):
    def __init__(self, radius):
        self.radius = radius

    def filter(self, image):
        radius = self.radius if isinstance(self.radius, (tuple, list)) else (self.radius, self.radius)
        if len(radius) != 2:
            raise ValueError('radius must be a number or pair')
        return Image._wrap(_native('blur', image._spec(), radius[0], radius[1], 1))


class GaussianBlur(BoxBlur):
    def __init__(self, radius=2):
        BoxBlur.__init__(self, radius)

    def filter(self, image):
        radius = self.radius if isinstance(self.radius, (tuple, list)) else (self.radius, self.radius)
        if len(radius) != 2:
            raise ValueError('radius must be a number or pair')
        return Image._wrap(_native('blur', image._spec(), radius[0], radius[1], 3))


class UnsharpMask(Filter):
    def __init__(self, radius=2, percent=150, threshold=3):
        self.radius, self.percent, self.threshold = radius, percent, threshold

    def filter(self, image):
        return Image._wrap(_native('unsharp', image._spec(), self.radius, self.percent, self.threshold))


class BLUR(Kernel):
    def __init__(self):
        Kernel.__init__(self, (5, 5), [1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1], 16)


class CONTOUR(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [-1, -1, -1, -1, 8, -1, -1, -1, -1], 1, 255)


class DETAIL(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [0, -1, 0, -1, 10, -1, 0, -1, 0], 6)


class EDGE_ENHANCE(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [-1, -1, -1, -1, 10, -1, -1, -1, -1], 2)


class EDGE_ENHANCE_MORE(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [-1, -1, -1, -1, 9, -1, -1, -1, -1], 1)


class EMBOSS(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [-1, 0, 0, 0, 1, 0, 0, 0, 0], 1, 128)


class FIND_EDGES(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [-1, -1, -1, -1, 8, -1, -1, -1, -1], 1)


class SHARPEN(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [-2, -2, -2, -2, 32, -2, -2, -2, -2], 16)


class SMOOTH(Kernel):
    def __init__(self):
        Kernel.__init__(self, (3, 3), [1, 1, 1, 1, 5, 1, 1, 1, 1], 13)


class SMOOTH_MORE(Kernel):
    def __init__(self):
        Kernel.__init__(self, (5, 5), [1, 1, 1, 1, 1, 1, 5, 5, 5, 1, 1, 5, 44, 5, 1, 1, 5, 5, 5, 1, 1, 1, 1, 1, 1], 100)
