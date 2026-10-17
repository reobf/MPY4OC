package net.mpy.vm;

import org.junit.jupiter.api.Test;

class MpyosBufferTest {
    @Test void filesystemJoinsChunksAndClosesOnSuccessAndFailure() throws Exception {
        StdlibTestSupport.run("""
                import fs
                class Disk(fs.Filesystem):
                    def __init__(self, chunks):
                        self.chunks = chunks
                        self.closed = False
                    def exists(self, path):
                        return path != 'missing'
                    def open(self, path, mode):
                        return 42
                    def _read_chunk(self, handle, size):
                        assert handle == 42 and size == 4096
                        if not self.chunks:
                            raise OSError('disk removed')
                        return self.chunks.pop(0)
                    def close(self, handle):
                        assert handle == 42
                        self.closed = True
                d = Disk([b'head', b'\\x00\\xff', 'tail', None])
                assert d.read_bytes('file') == b'head\\x00\\xfftail' and d.closed
                d = Disk([])
                assert d.read_bytes('missing') is None and not d.closed
                try:
                    d.read_bytes('file')
                    raise AssertionError('expected removed disk')
                except OSError:
                    pass
                assert d.closed
                passed = True
                """, "fs");
    }

    @Test void requestsLatin1FallbackAndJsonUseRealModules() throws Exception {
        StdlibTestSupport.run("""
                import requests
                r = requests.Response('local', None, 200, 'OK', {}, None)
                r._body = b'caf\\xe9'
                assert r.text == 'caf\\xe9'
                assert requests._decode_body(b'caf\\xe9') == 'caf\\xe9'
                r._body = b'caf\\xc3\\xa9'
                assert r.text == 'caf\\xe9'
                r._body = b'{"answer": 42}'
                assert r.json() == {'answer': 42}
                passed = True
                """, "requests");
    }
}
