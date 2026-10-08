# frozen pickle: data serialization backed by Java's binary writer (not the
# CPython pickle opcode stream). Uses protocol -1 (a private, deliberately
# incompatible format) so blobs never collide with real pickle protocols 0..5.
#
# Picklable: None, bool, int, float, complex, str, bytes, tuple, list, dict,
# set, range, and namedtuples (shared/cyclic structures round-trip via
# back-references). Functions, classes, and generators are not picklable - use
# a VM snapshot for live execution state.
HIGHEST_PROTOCOL = -1
DEFAULT_PROTOCOL = -1

def dumps(obj, protocol=-1):
    return __pickle_dumps(obj)

def loads(data):
    return __pickle_loads(data)
