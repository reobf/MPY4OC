# frozen io module: StringIO / BytesIO implemented in pure Python.
# Position-tracked in-memory text/binary buffers matching MicroPython semantics.

class StringIO:
    def __init__(self, initial=""):
        self._buf = initial
        self._pos = 0

    def write(self, s):
        # overwrite from current position, extending as needed
        p = self._pos
        buf = self._buf
        if p >= len(buf):
            self._buf = buf + s
        else:
            self._buf = buf[:p] + s + buf[p + len(s):]
        self._pos = p + len(s)
        return len(s)

    def read(self, n=-1):
        buf = self._buf
        p = self._pos
        if n is None or n < 0:
            r = buf[p:]
            self._pos = len(buf)
        else:
            r = buf[p:p + n]
            self._pos = p + len(r)
        return r

    def readline(self):
        buf = self._buf
        p = self._pos
        nl = buf.find("\n", p)
        if nl < 0:
            r = buf[p:]
            self._pos = len(buf)
        else:
            r = buf[p:nl + 1]
            self._pos = nl + 1
        return r

    def getvalue(self):
        return self._buf

    def seek(self, pos):
        self._pos = pos
        return pos

    def tell(self):
        return self._pos

    def close(self):
        pass


class BytesIO:
    def __init__(self, initial=b""):
        self._buf = initial
        self._pos = 0

    def write(self, b):
        p = self._pos
        buf = self._buf
        if p >= len(buf):
            self._buf = buf + b
        else:
            self._buf = buf[:p] + b + buf[p + len(b):]
        self._pos = p + len(b)
        return len(b)

    def read(self, n=-1):
        buf = self._buf
        p = self._pos
        if n is None or n < 0:
            r = buf[p:]
            self._pos = len(buf)
        else:
            r = buf[p:p + n]
            self._pos = p + len(r)
        return r

    def getvalue(self):
        return self._buf

    def seek(self, pos):
        self._pos = pos
        return pos

    def tell(self):
        return self._pos

    def close(self):
        pass
