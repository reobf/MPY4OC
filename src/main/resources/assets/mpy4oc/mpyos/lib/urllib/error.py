"""urllib.error -- exception types for urllib.request."""


class URLError(OSError):
    def __init__(self, reason):
        OSError.__init__(self, str(reason))
        self.reason = reason


class HTTPError(URLError):
    """A non-2xx HTTP response, raised by urlopen(). Carries code/reason/headers.
    Note: OpenComputers discards error-response bodies, so read() returns b""."""

    def __init__(self, url, code, msg, hdrs):
        URLError.__init__(self, "HTTP Error " + str(code) + ": " + str(msg))
        self.url = url
        self.code = code
        self.msg = msg
        self.headers = hdrs

    def read(self):
        return b""
