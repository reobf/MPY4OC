# frozen deflate: raw/zlib/gzip compress & decompress over host primitives.
# MicroPython exposes a stream class (DeflateIO); this sandbox has no first-class
# streams, so we provide the practical blob API compress()/decompress() plus
# format constants. Output is format-compatible with MicroPython's deflate.
RAW = 0
ZLIB = 1
GZIP = 2
AUTO = 3

def compress(data, format=ZLIB, level=6):
    return __deflate_compress(data, format, level)

def decompress(data, format=AUTO):
    return __deflate_decompress(data, format)
