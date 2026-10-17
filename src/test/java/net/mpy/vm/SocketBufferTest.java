package net.mpy.vm;

import org.junit.jupiter.api.Test;

class SocketBufferTest {
    private static final String SETUP = """
            import socket
            class Handle:
                def __init__(self, chunks):
                    self.chunks = list(chunks)
                    self.reads = []
                    self.closed = False
                def read(self, n):
                    self.reads.append(n)
                    assert self.chunks, 'unexpected card read'
                    chunk = self.chunks.pop(0)
                    if chunk is not None and len(chunk) > n:
                        self.chunks.insert(0, chunk[n:])
                        return chunk[:n]
                    return chunk
                def close(self):
                    self.closed = True
            def connected(chunks):
                s = socket.socket()
                s._h = Handle(chunks)
                return s
            """;

    @Test void lineReadsPreserveSurplusForRecvAndRespectLimitsAndEof() throws Exception {
        StdlibTestSupport.run(SETUP + """
                s = connected([b'one\\r\\ntwo\\nbody', None])
                h = s._h
                assert s.recv_line() == b'one\\r\\n'
                assert s.recv_line() == b'two\\n'
                assert s.recv(2) == b'bo' and s.recv_exactly(2) == b'dy'
                assert len(h.reads) == 1
                assert s.recv_line() == b'' and s.recv(1) == b''
                assert len(h.reads) == 2
                s = connected([b'a', b'bc\\nrest', None])
                assert s.recv_line(2) == b'ab'
                assert s.recv_line() == b'c\\n'
                assert s.recv_line() == b'rest' and s.recv_line() == b''
                s = connected([b'abc\\n'])
                assert s.recv(0) == b'' and s.recv_line(0) == b''
                assert not s._h.reads
                for fn in (lambda: s.recv(-1), lambda: s.recv_line(-1)):
                    try:
                        fn()
                        raise AssertionError('negative size accepted')
                    except ValueError:
                        pass
                s.close()
                try:
                    s.recv_line()
                    raise AssertionError('closed read accepted')
                except OSError:
                    pass
                passed = True
                """, "socket");
    }

    @Test void timeoutKeepsPartialDataForTheNextRead() throws Exception {
        StdlibTestSupport.run(SETUP + """
                class Clock:
                    def __init__(self):
                        self.now = 0
                    def uptime(self):
                        return self.now
                    def sleep(self, seconds):
                        self.now += seconds
                clock = Clock()
                socket.computer = clock
                socket.time.sleep = clock.sleep
                s = connected([b'a', b'', b'b', b'', b'c', b'', b'd', b'\\n', None])
                s.settimeout(0.09)
                try:
                    s.recv_line()
                    raise AssertionError('deadline reset for each fragment')
                except socket.timeout:
                    pass
                s.settimeout(None)
                assert s.recv_line() == b'abcd\\n'
                assert s.recv() == b''
                passed = True
                """, "socket");
    }
}
