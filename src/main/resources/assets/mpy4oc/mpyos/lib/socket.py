"""socket.py -- CPython-flavoured TCP sockets over the OC internet card.

The OpenComputers internet card exposes raw TCP through
`internet.connect(host, port)`, which returns a non-blocking connection handle
(userdata) with finishConnect()/read()/write()/close(). This module wraps that
handle in the familiar CPython/MicroPython `socket` API.

The module is always importable; the internet card is looked up lazily, at the
moment a connection is actually opened. Without a card every attempt raises
OSError("no internet card installed").

Scope: outbound TCP streams only (AF_INET/SOCK_STREAM). There is no bind/listen
(OC cannot accept inbound connections) and no UDP. getaddrinfo() is the
MicroPython-style formality: OC resolves host names itself when connecting.

Timeouts follow the in-game clock (computer.uptime()), like everything else in
mpyos: time spent with the world unloaded does not count.
"""

import time

AF_INET = 2
AF_INET6 = 10
SOCK_STREAM = 1
SOCK_DGRAM = 2

# One game tick between polls of the non-blocking OC handle. The VM's
# time.sleep yields cooperatively, so waiting sockets cost (almost) no ops.
_POLL = 0.05

error = OSError


class timeout(OSError):
    """Raised when a socket operation exceeds settimeout()."""
    pass


def _card():
    """The primary internet component, or raise OSError if none is installed."""
    try:
        return component.internet
    except AttributeError:
        raise OSError("no internet card installed")


def getaddrinfo(host, port, af=0, type=0, proto=0, flags=0):
    """MicroPython-style stub: OC does its own name resolution on connect, so
    this just echoes the address in getaddrinfo shape."""
    return [(AF_INET, SOCK_STREAM, 0, "", (host, port))]


class socket:
    """A TCP client socket backed by an OC internet-card connection handle."""

    def __init__(self, family=AF_INET, type=SOCK_STREAM, proto=0):
        if type != SOCK_STREAM:
            raise OSError("only SOCK_STREAM (TCP) is supported")
        self._h = None          # OC connection handle (userdata proxy)
        self._timeout = None    # None = block forever, 0 = non-blocking-ish
        self._eof = False
        self._closed = False

    # -- configuration -------------------------------------------------

    def settimeout(self, value):
        if value is not None and value < 0:
            raise ValueError("timeout must be non-negative")
        self._timeout = value

    def gettimeout(self):
        return self._timeout

    def setblocking(self, flag):
        self._timeout = None if flag else 0

    def setsockopt(self, *a):
        pass                    # accepted for compatibility, no-op

    # -- lifecycle -----------------------------------------------------

    def connect(self, address):
        """address = (host, port). Blocks (cooperatively) until the connection
        is established, the timeout expires, or the connection fails."""
        if self._h is not None:
            raise OSError("socket is already connected")
        if self._closed:
            raise OSError("socket is closed")
        host, port = address
        card = _card()
        r = card.connect(host, port)
        if isinstance(r, tuple):        # (None, "tcp connections are unavailable")
            raise OSError(r[1] if len(r) > 1 and r[1] else "connect failed")
        self._h = r
        deadline = self._deadline()
        while True:
            # finishConnect: False while in progress; a failed connection is
            # closed card-side and the *next* call raises "connection lost".
            if self._h.finishConnect():
                return
            self._wait(deadline, "connect timed out")

    def close(self):
        self._closed = True
        h = self._h
        self._h = None
        if h is not None:
            try:
                h.close()
            except Exception:
                pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False

    # -- data ----------------------------------------------------------

    def send(self, data):
        """Write once, return the number of bytes accepted (may be short)."""
        h = self._need()
        n = h.write(_as_bytes(data))
        return n if n is not None else 0

    def sendall(self, data):
        """Write all of data, cooperatively waiting while the stream is busy."""
        data = _as_bytes(data)
        h = self._need()
        deadline = self._deadline()
        sent = 0
        while sent < len(data):
            n = h.write(data[sent:])
            n = n if n is not None else 0
            if n > 0:
                sent += n
                continue
            self._wait(deadline, "send timed out")

    def recv(self, bufsize=4096):
        """Read up to bufsize bytes. Blocks (cooperatively) until data arrives;
        returns b"" only at end-of-stream, like CPython."""
        h = self._need()
        if self._eof:
            return b""
        deadline = self._deadline()
        while True:
            chunk = h.read(bufsize)
            if chunk is None:           # remote closed the stream
                self._eof = True
                return b""
            if len(chunk):
                return chunk
            self._wait(deadline, "recv timed out")

    def recv_exactly(self, n):
        """Non-standard helper: read exactly n bytes or raise OSError on EOF."""
        parts = []
        got = 0
        while got < n:
            chunk = self.recv(n - got)
            if not chunk:
                raise OSError("connection closed mid-read")
            parts.append(chunk)
            got += len(chunk)
        return b"".join(parts)

    def recv_line(self, limit=65536):
        """Non-standard helper: read up to and including b"\\n" (or EOF)."""
        parts = []
        got = 0
        while got < limit:
            c = self.recv(1)
            if not c:
                break
            parts.append(c)
            got += 1
            if c == b"\n":
                break
        return b"".join(parts)

    # -- internals -----------------------------------------------------

    def _need(self):
        if self._closed or self._h is None:
            raise OSError("socket is not connected")
        return self._h

    def _deadline(self):
        t = self._timeout
        return None if t is None else computer.uptime() + t

    def _wait(self, deadline, msg):
        if deadline is not None and computer.uptime() >= deadline:
            raise timeout(msg)
        time.sleep(_POLL)


def create_connection(address, timeout=None):
    """Connect to (host, port) and return the connected socket."""
    s = socket()
    if timeout is not None:
        s.settimeout(timeout)
    s.connect(address)
    return s


def _as_bytes(data):
    if isinstance(data, bytes) or isinstance(data, bytearray):
        return bytes(data)
    if isinstance(data, str):
        return data.encode()
    raise TypeError("a bytes-like object is required")
