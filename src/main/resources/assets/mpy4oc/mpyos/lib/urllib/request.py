"""urllib.request -- CPython-style urlopen() over the OC internet card.

A thin veneer over the `requests` module for scripts written against the
standard library:

    from urllib.request import urlopen
    with urlopen("https://example.com/data.txt") as r:
        body = r.read()

Always importable; needs an internet card at call time (OSError without one).
Non-2xx responses raise urllib.error.HTTPError, like CPython.
"""

import requests as _requests
from urllib.error import URLError, HTTPError


class Request:
    """Minimal Request object: url, data, headers, method."""

    def __init__(self, url, data=None, headers=None, method=None):
        self.url = url
        self.data = data
        self.headers = dict(headers) if headers else {}
        self.method = method if method else ("POST" if data is not None else "GET")

    def add_header(self, key, val):
        self.headers[key] = val


class _UrlResponse:
    """What urlopen() returns: a file-like reader plus HTTP metadata."""

    def __init__(self, resp):
        self._resp = resp
        self._iter = resp.iter_content()
        self._buf = b""
        self._eof = False
        self.url = resp.url
        self.status = resp.status_code
        self.code = resp.status_code       # legacy alias
        self.reason = resp.reason
        self.headers = resp.headers

    def read(self, n=-1):
        if n is None or n < 0:
            parts = [self._buf]
            self._buf = b""
            if not self._eof:
                for chunk in self._iter:
                    parts.append(chunk)
                self._eof = True
            return b"".join(parts)
        while len(self._buf) < n and not self._eof:
            try:
                self._buf += next(self._iter)
            except StopIteration:
                self._eof = True
        out = self._buf[:n]
        self._buf = self._buf[n:]
        return out

    def readline(self):
        while b"\n" not in self._buf and not self._eof:
            try:
                self._buf += next(self._iter)
            except StopIteration:
                self._eof = True
        i = self._buf.find(b"\n")
        if i < 0:
            out = self._buf
            self._buf = b""
            return out
        out = self._buf[:i + 1]
        self._buf = self._buf[i + 1:]
        return out

    def getcode(self):
        return self.status

    def getheader(self, name, default=None):
        return self.headers.get(name.lower(), default)

    def geturl(self):
        return self.url

    def close(self):
        self._resp.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def urlopen(url, data=None, timeout=_requests.DEFAULT_TIMEOUT):
    """Open a URL. `url` is a string or a Request. Returns a file-like response;
    raises HTTPError for non-2xx statuses, URLError for connection failures."""
    if isinstance(url, Request):
        req, target = url, url.url
    else:
        req, target = None, url
    try:
        resp = _requests.request(
            req.method if req else ("POST" if data is not None else "GET"),
            target,
            data=data if data is not None else (req.data if req else None),
            headers=req.headers if req else None,
            timeout=timeout,
            stream=True)
    except _requests.RequestException as e:
        raise URLError(e)
    if resp.status_code >= 400:
        resp.close()
        raise HTTPError(target, resp.status_code, resp.reason, resp.headers)
    return _UrlResponse(resp)
