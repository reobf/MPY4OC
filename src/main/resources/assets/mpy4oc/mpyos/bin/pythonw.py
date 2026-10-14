# MPYOS interpreter: pythonw
"""Launch a Python file as an independent background MPYOS program."""
import sys
import _process
if len(sys.argv) < 2:
    raise ValueError('usage: pythonw file [args...]')
_process.python(sys.argv[1:], background=True)
