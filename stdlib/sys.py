# frozen sys: identifying data + exit + exception printing.
version = "3.4.0; MicroPython on mpy-java"
version_info = (3, 4, 0)
platform = "linux"
maxsize = 9223372036854775807
byteorder = "little"
path = []
argv = []
executable = '/bin/python.py'
_contexts = {}


def _drop_context(program):
    import _program
    if not _program.live(program):
        _contexts.pop(program, None)


import _program
_program._on_end.append(_drop_context)

class __Impl:
    name = "micropython"
    version = (1, 23, 0)

implementation = __Impl()

def exit(code=0):
    raise SystemExit(code)

def print_exception(exc, file=None):
    __sys_print_exc(exc)
