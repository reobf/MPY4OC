"""Host-backed Python I/O. Hosts install path and terminal adapters per VM.

The opener returns a raw binary stream with read/write/seek/tell/close. Neither
the frozen library nor the VM accesses Java's filesystem or console input.
"""
import _program
import threading

_opener = None
_reader = None
_files = []
_input_lock = threading.RLock()


class UnsupportedOperation(OSError):
    pass


def install(opener=None, reader=None):
    global _opener, _reader
    if opener is not None:
        if not callable(opener):
            raise TypeError('opener must be callable')
        _opener = opener
    if reader is not None:
        if not callable(reader):
            raise TypeError('reader must be callable')
        _reader = reader


def _input(prompt=''):
    import sys
    stream = getattr(sys, 'stdin', None)
    if stream is not None:
        if prompt:
            sys.stdout.write(str(prompt))
        line = stream.readline()
        if not line:
            raise EOFError('EOF when reading a line')
        return line[:-1] if line.endswith('\n') else line
    if _reader is None:
        raise OSError('input: no terminal installed')
    if _program.in_background():
        raise OSError('a background job cannot read the terminal')
    with _input_lock:
        _program.check()
        line = _reader(str(prompt))
        if line is None:
            raise EOFError('EOF when reading a line')
        return line


def _cleanup(program):
    if program is None or _program.live(program):
        return
    for stream in list(_files):
        if stream._owner == program:
            try:
                stream.close()
            except OSError:
                pass  # a removed disk must not prevent other files being closed


_program._on_end.append(_cleanup)


def open(file, mode='r', buffering=-1, encoding=None, errors=None, newline=None, closefd=True, opener=None):
    import os
    file = os.fspath(file)
    if not isinstance(file, str):
        raise TypeError('file must be a path string')
    if '\x00' in file:
        raise ValueError('embedded null byte')
    if not isinstance(mode, str):
        raise TypeError('mode must be a string')
    if not mode or any(c not in 'rwaxbt+' for c in mode) or len(set(mode)) != len(mode):
        raise ValueError('invalid mode: ' + repr(mode))
    bases = [c for c in mode if c in 'rwax']
    if len(bases) != 1 or ('b' in mode and 't' in mode):
        raise ValueError('must have exactly one of create/read/write/append mode')
    if '+' in mode or 'x' in mode:
        raise UnsupportedOperation('OC files support r, w and a; update (+) and exclusive creation (x) are not supported')
    binary = 'b' in mode
    if binary and (encoding is not None or errors is not None or newline is not None):
        raise ValueError('binary mode does not take encoding, errors or newline')
    if not isinstance(buffering, int):
        raise TypeError('buffering must be an integer')
    if buffering < -1:
        raise ValueError('invalid buffering size')
    if buffering == 0 and not binary:
        raise ValueError("can't have unbuffered text I/O")
    if encoding is not None and (not isinstance(encoding, str) or encoding.lower().replace('_', '-') not in ('utf-8', 'utf8')):
        raise ValueError('only UTF-8 text encoding is supported')
    if errors is not None and errors not in ('strict', 'ignore', 'replace'):
        raise ValueError('unsupported error handler')
    if newline not in (None, '', '\n', '\r', '\r\n'):
        raise ValueError('illegal newline value')
    if not closefd:
        raise ValueError('Cannot use closefd=False with file name')
    if opener is not None:
        raise UnsupportedOperation('custom file-descriptor openers are not supported')
    if _opener is None:
        raise OSError('open: no filesystem installed')
    stream = _File(file, mode, binary, bases[0], errors or 'strict', newline, buffering)
    # Register before entering host code: the program owns the handle as soon as
    # it is acquired, including when its next instruction is an interrupt point.
    _files.append(stream)
    try:
        stream._raw = _opener(file, bases[0] + 'b')
    except BaseException:
        stream.close()
        raise
    return stream


class _File:
    def __init__(self, name, mode, binary, access, errors, newline, buffering):
        self.name, self.mode = name, mode
        self._binary, self._access = binary, access
        self.encoding = None if binary else 'utf-8'
        self.errors = None if binary else errors
        self._newline = newline
        self._raw = None
        self._closed = False
        self._buf = b''
        self._eof = False
        self._chunk = 1 if buffering == 0 else 4096 if buffering <= 1 else min(buffering, 65536)
        self._owner = _program.current()
        self._lock = threading.RLock()

    @property
    def closed(self):
        return self._closed

    def __repr__(self):
        kind = '_io.BufferedReader' if self._binary and self._access == 'r' else '_io.BufferedWriter' if self._binary else '_io.TextIOWrapper'
        return '<' + kind + ' name=' + repr(self.name) + ' mode=' + repr(self.mode) + ('' if self._binary else " encoding='utf-8'") + '>'

    def _check(self, access=None):
        if self._closed:
            raise ValueError('I/O operation on closed file')
        if access == 'r' and self._access != 'r':
            raise UnsupportedOperation('not readable')
        if access == 'w' and self._access == 'r':
            raise UnsupportedOperation('not writable')

    def readable(self):
        self._check()
        return self._access == 'r'

    def writable(self):
        self._check()
        return self._access != 'r'

    def seekable(self):
        self._check()
        return True

    def isatty(self):
        self._check()
        return False

    def _fill(self, size):
        # Buffer at least size bytes, unless the host has no more (then _eof is set).
        while len(self._buf) < size and not self._eof:
            chunk = self._raw.read(max(self._chunk, size - len(self._buf)))
            if not chunk:
                self._eof = True
                break
            self._buf += chunk

    def _text(self, size, line):
        # The host decodes whole buffers (__io_decode: UTF-8 with errors=, the newline mode,
        # the character limit and the line end), consuming exactly the bytes behind what it
        # returns, so tell() stays a byte position and no Python loop runs per character.
        parts, count = [], 0
        self._eof = False
        while size < 0 or count < size:
            self._fill(self._chunk)
            if not self._buf:
                break
            limit = -1 if size < 0 else size - count
            text, consumed, produced, done = __io_decode(self._buf, self._newline, self.errors, limit, line, self._eof)
            if consumed:
                self._buf = self._buf[consumed:]
            if text:
                parts.append(text)
                count += produced
            if done:
                break
            if not consumed:
                # Nothing could be decided from what is buffered (an incomplete sequence, or
                # a \r that may pair with the next byte): read on, or give up at the end.
                if self._eof:
                    break
                self._fill(len(self._buf) + 4)
        return ''.join(parts)

    def _bytes(self, size, line):
        parts, count = [], 0
        self._eof = False
        while size < 0 or count < size:
            self._fill(1)
            if not self._buf:
                break
            n = len(self._buf) if size < 0 else min(len(self._buf), size - count)
            if line:
                k = self._buf.find(b'\n', 0, n)
                if k >= 0:
                    n = k + 1
            part, self._buf = self._buf[:n], self._buf[n:]
            parts.append(part)
            count += n
            if line and part[-1] == 10:
                break
        return b''.join(parts)

    def _read(self, size, line):
        self._check('r')
        if size is None:
            size = -1
        if not isinstance(size, int):
            raise TypeError('size must be an integer')
        return self._bytes(size, line) if self._binary else self._text(size, line)

    def read(self, size=-1):
        with self._lock:
            return self._read(size, False)

    def readline(self, size=-1):
        with self._lock:
            return self._read(size, True)

    def readlines(self, hint=-1):
        with self._lock:
            lines, count = [], 0
            while True:
                line = self._read(-1, True)
                if not line:
                    return lines
                lines.append(line)
                count += len(line)
                if hint > 0 and count > hint:
                    return lines

    def write(self, data):
        with self._lock:
            self._check('w')
            if self._binary:
                # The VM's bytearray constructor is not yet an isinstance type;
                # bytes.join already enforces and copies the bytes-like protocol.
                data = b''.join([data])
                count = len(data)
            else:
                if not isinstance(data, str):
                    raise TypeError('write() argument must be str')
                data, count = __io_encode(data, self._newline)   # count: characters, as CPython
            for start in range(0, len(data), self._chunk):
                chunk = data[start:start + self._chunk]
                self._raw.write(chunk)
            return count

    def writelines(self, lines):
        self._check('w')
        for line in lines:
            self.write(line)

    def tell(self):
        with self._lock:
            self._check()
            return self._raw.tell() - len(self._buf)

    def seek(self, offset, whence=0):
        with self._lock:
            self._check()
            if not isinstance(offset, int) or not isinstance(whence, int):
                raise TypeError('an integer is required')
            if whence not in (0, 1, 2):
                raise ValueError('invalid whence')
            if not self._binary and whence != 0 and offset != 0:
                raise UnsupportedOperation("can't do nonzero cur-relative or end-relative seeks in text mode")
            if whence == 0 and offset < 0:
                raise ValueError('negative seek position')
            if whence == 1:
                offset -= len(self._buf)
            result = self._raw.seek(offset, whence)
            self._buf = b''
            self._eof = False
            return result

    def flush(self):
        self._check()  # writes already go to the host before write() returns

    def close(self):
        with self._lock:
            if self._closed:
                return
            raw, self._raw = self._raw, None
            self._buf = b''
            try:
                if raw is not None:
                    raw.close()
            finally:
                # Closed only once the host handle is gone: a thread that watches .closed
                # (another thread of the program, or the shell) must not get ahead of that.
                self._closed = True
                if self in _files:
                    _files.remove(self)

    def __enter__(self):
        self._check()
        return self

    def __exit__(self, *args):
        self.close()

    def __iter__(self):
        self._check()
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line
