package net.mpy.vm;

import org.junit.jupiter.api.Test;

class MemoryStreamTest {
    @Test void operationsMatchCpythonIncludingEofSeekAndClose() throws Exception {
        StdlibTestSupport.golden("io");
    }

    @Test void fragmentedAppendsOverwriteAndIteration() throws Exception {
        StdlibTestSupport.run("""
                import io
                for stream, piece in ((io.StringIO(), 'line\\n'), (io.BytesIO(), b'line\\n')):
                    for i in range(2000):
                        assert stream.write(piece) == len(piece)
                    assert stream.getvalue() == piece * 2000
                    stream.seek(0)
                    assert stream.readline(2) == piece[:2]
                    assert stream.readline() == piece[2:]
                    stream.seek(0)
                    assert list(stream) == [piece] * 2000
                    stream.seek(0)
                    stream.write(piece[:2])
                    stream.seek(0, 2)
                    stream.writelines([piece, piece])
                    assert stream.getvalue() == piece * 2002
                b = io.BytesIO()
                b.write(bytearray(b'abc'))
                assert b.getvalue() == b'abc'
                with io.StringIO('abc') as f:
                    assert f.read() == 'abc'
                assert f.closed
                passed = True
                """);
    }
}
