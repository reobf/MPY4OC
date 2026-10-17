"""Per-program CGI streams. The default VM print fast path is unchanged outside CGI."""
import io
import sys
from _fileio import _File


class Buffer(io.BytesIO):
    def __init__(self, initial=b'', limit=1048576):
        io.BytesIO.__init__(self, initial)
        self.limit = limit
    def write(self, data):
        if self.tell() + len(data) > self.limit:
            raise OSError('CGI output limit exceeded')
        return io.BytesIO.write(self, data)


class TextStream:
    encoding = 'utf-8'
    errors = 'strict'
    def __init__(self, data=b'', limit=1048576):
        self.buffer = Buffer(data, limit)
        self._text = _File('<cgi>', 'r', False, 'r', 'strict', None, 4096)
        self._text._raw = self.buffer
    def write(self, text):
        if not isinstance(text, str):
            raise TypeError('write() requires str')
        self.buffer.write(text.encode('utf-8'))
        return len(text)
    def read(self, size=-1):
        return self._text.read(size)
    def readline(self, size=-1):
        return self._text.readline(size)
    def flush(self):
        pass
    def isatty(self):
        return False
    def close(self):
        self.buffer.close()


def print_to_stream(*args, sep=' ', end='\n', file=None, flush=False):
    if sep is None:
        sep = ' '
    if end is None:
        end = '\n'
    if not isinstance(sep, str) or not isinstance(end, str):
        raise TypeError('sep and end must be strings or None')
    stream = sys.stdout if file is None else file
    stream.write(sep.join(str(value) for value in args) + end)
    if flush:
        stream.flush()
