# frozen io module: host-backed files and in-memory StringIO / BytesIO.
# The in-memory streams keep what is written at the end as a list of pieces and join them
# only when something reads, so building a large text by many small writes is linear.

from _fileio import open, _input, install, UnsupportedOperation


class _MemoryStream:
    _empty = ''
    _newline = '\n'

    def __init__(self, initial=None):
        self._buf = self._empty if initial is None else self._check_type(initial)
        self._parts = []            # pieces appended at the end since the last join
        self._end = len(self._buf)  # the stream's length, pieces included
        self._pos = 0
        self.closed = False

    def _join(self):
        if self._parts:
            self._buf += self._empty.join(self._parts)
            self._parts = []
        return self._buf

    def _open(self):
        if self.closed:
            raise ValueError('I/O operation on closed file')

    def write(self, data):
        self._open()
        data = self._check_type(data)
        if not data:
            return 0
        if self._pos == self._end:          # appending: no copy until something reads
            self._parts.append(data)
        else:
            buf = self._join()
            p = self._pos
            if p > len(buf):                # a seek past the end pads with zeros/NULs
                buf += ('\x00' if self._empty == '' else b'\x00') * (p - len(buf))
            self._buf = buf[:p] + data + buf[p + len(data):]
        self._pos += len(data)
        if self._pos > self._end:
            self._end = self._pos
        return len(data)

    def writelines(self, lines):
        self._open()
        for line in lines:
            self.write(line)

    def read(self, size=-1):
        self._open()
        if size is not None:
            size = self._index(size)
        buf = self._join()
        p = self._pos
        if size is None or size < 0:
            r = buf[p:]
            self._pos = p + len(r)
        else:
            r = buf[p:p + size]
            self._pos = p + len(r)
        return r

    def readline(self, size=-1):
        self._open()
        if size is not None:
            size = self._index(size)
        buf = self._join()
        p = self._pos
        nl = buf.find(self._newline, p)
        end = len(buf) if nl < 0 else nl + 1
        if size is not None and size >= 0 and end > p + size:
            end = p + size
        r = buf[p:end]
        self._pos = p + len(r)
        return r

    def readlines(self, hint=-1):
        lines, count = [], 0
        while True:
            line = self.readline()
            if not line:
                return lines
            lines.append(line)
            count += len(line)
            if hint is not None and hint > 0 and count >= hint:
                return lines

    def __iter__(self):
        self._open()
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line

    def getvalue(self):
        self._open()
        return self._join()

    def seek(self, pos, whence=0):
        self._open()
        pos, whence = self._index(pos), self._index(whence)
        if whence == 0:
            if pos < 0:
                raise ValueError('negative seek position ' + str(pos))
            self._pos = pos
        elif whence == 1:
            if pos != 0:
                raise OSError("can't do nonzero cur-relative seeks")
        elif whence == 2:
            if pos != 0:
                raise OSError("can't do nonzero end-relative seeks")
            self._pos = self._end
        else:
            raise ValueError('invalid whence (' + str(whence) + ', should be 0, 1 or 2)')
        return self._pos

    def tell(self):
        self._open()
        return self._pos

    def truncate(self, size=None):
        self._open()
        if size is None:
            size = self._pos
        size = self._index(size)
        if size < 0:
            raise ValueError('negative size value ' + str(size))
        buf = self._join()
        if size < len(buf):
            self._buf = buf[:size]
        self._end = len(self._buf)
        return size

    def readable(self):
        self._open()
        return True

    def writable(self):
        self._open()
        return True

    def seekable(self):
        self._open()
        return True

    def _index(self, value):
        if isinstance(value, (int, bool)):
            return value
        index = getattr(value, '__index__', None)
        if index is not None:
            result = index()
            if isinstance(result, (int, bool)):
                return result
        raise TypeError('integer argument expected')

    def flush(self):
        pass

    def close(self):
        self.closed = True
        self._buf, self._parts = self._empty, []

    def __enter__(self):
        self._open()
        return self

    def __exit__(self, *args):
        self.close()


class StringIO(_MemoryStream):
    def _check_type(self, data):
        if not isinstance(data, str):
            raise TypeError('string argument expected, got ' + repr(type(data).__name__))
        return data


class BytesIO(_MemoryStream):
    _empty = b''
    _newline = b'\n'

    def seek(self, pos, whence=0):
        self._open()
        pos, whence = self._index(pos), self._index(whence)
        if whence == 1:
            self._pos = max(0, self._pos + pos)
        elif whence == 2:
            self._pos = max(0, self._end + pos)
        elif whence == 0:
            if pos < 0:
                raise ValueError('negative seek value ' + str(pos))
            self._pos = pos
        else:
            raise ValueError('invalid whence (' + str(whence) + ', should be 0, 1 or 2)')
        return self._pos

    def flush(self):
        self._open()

    def _check_type(self, data):
        if isinstance(data, bytes):
            return data
        if isinstance(data, bytearray):
            return bytes(data)
        raise TypeError('a bytes-like object is required, not ' + repr(type(data).__name__))
