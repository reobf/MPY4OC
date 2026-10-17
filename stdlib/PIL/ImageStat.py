"""Per-channel statistics calculated from an 8-bit histogram."""
import math
from PIL import Image


class Stat:
    def __init__(self, image_or_list, mask=None):
        self.h = image_or_list.histogram(mask) if isinstance(image_or_list, Image.Image) else list(image_or_list)
        if not self.h or len(self.h) % 256 or len(self.h) > 1024:
            raise ValueError('histogram must contain 256 entries per channel')
        if any(v < 0 for v in self.h):
            raise ValueError('histogram counts must not be negative')
        self.bands = list(range(len(self.h) // 256))

    @property
    def count(self):
        return [sum(self.h[b * 256:(b + 1) * 256]) for b in self.bands]

    @property
    def sum(self):
        return [float(sum([i * self.h[b * 256 + i] for i in range(256)])) for b in self.bands]

    @property
    def sum2(self):
        return [float(sum([i * i * self.h[b * 256 + i] for i in range(256)])) for b in self.bands]

    @property
    def mean(self):
        return [s / n for s, n in zip(self.sum, self.count)]

    @property
    def rms(self):
        return [math.sqrt(s / n) for s, n in zip(self.sum2, self.count)]

    @property
    def var(self):
        return [max(0, s2 / n - (s / n) ** 2) for s, s2, n in zip(self.sum, self.sum2, self.count)]

    @property
    def stddev(self):
        return [math.sqrt(v) for v in self.var]

    @property
    def extrema(self):
        out = []
        for b in self.bands:
            present = [i for i in range(256) if self.h[b * 256 + i]]
            out.append((present[0], present[-1]) if present else (255, 0))
        return out

    @property
    def median(self):
        out = []
        for b, count in zip(self.bands, self.count):
            sofar = 0
            value = 255
            for i in range(256):
                sofar += self.h[b * 256 + i]
                if sofar > count // 2:
                    value = i
                    break
            out.append(value)
        return out
