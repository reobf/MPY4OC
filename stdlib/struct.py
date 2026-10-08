# frozen struct module: direct bindings to Java ByteBuffer primitives.
# Native mode (no prefix / '@') aligns and uses 8-byte l/L, matching
# MicroPython on 64-bit; '<', '>', '=', '!' are the standard unaligned modes.
pack = __struct_pack
unpack = __struct_unpack
calcsize = __struct_calcsize

def unpack_from(fmt, data, offset=0):
    return unpack(fmt, data[offset:offset + calcsize(fmt)])
