# frozen hashlib module: sha256 / sha1 / md5 via a Java MessageDigest primitive.
# Updates are buffered and the digest is computed over the concatenation, which
# matches the streaming semantics for these one-shot digests.

class _Hash:
    def __init__(self, name, data=None):
        self._name = name
        self._buf = b"" if data is None else data

    def update(self, data):
        self._buf += data

    def digest(self):
        return __hashlib_digest(self._name, self._buf)


def sha256(data=None):
    return _Hash("sha256", data)

def sha1(data=None):
    return _Hash("sha1", data)

def md5(data=None):
    return _Hash("md5", data)
