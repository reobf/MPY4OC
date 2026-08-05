"""requests.py -- HTTP for mpyos, in the style of python-requests.

Built on the OC internet card's `internet.request()`, which does the actual
HTTP(S) on the server side (TLS included -- Java's HttpURLConnection under the
hood). This module wraps the card's non-blocking handle in the familiar API:

    import requests
    r = requests.get("https://example.com/api", timeout=10)
    r.status_code          # 200
    r.headers["content-type"]
    r.text                 # decoded body
    r.json()               # parsed body

The module is always importable; the card is looked up when a request is made.
Without an internet card every request raises OSError("no internet card
installed").

Differences from real python-requests, inherited from OpenComputers:
  * On non-2xx responses the OC card discards the body (a Java quirk of
    HttpURLConnection error streams). You still get a proper Response with
    status_code/reason/headers -- only .content is b"".
  * No cookies, sessions, auth helpers or redirects control (the underlying
    HttpURLConnection follows same-protocol redirects by itself).
  * `timeout` defaults to 30 in-game seconds rather than None: a script stuck
    forever on a dead server would otherwise hold its OC computer hostage.
  * POST bodies are sent as text (the card writes strings, not raw bytes).

All waiting is cooperative (time.sleep yields to the scheduler) and follows the
in-game clock: time spent with the chunk unloaded does not count.
"""

import time
import json as _json


_POLL = 0.05          # one game tick between polls of the card's handle
DEFAULT_TIMEOUT = 30  # in-game seconds


class RequestException(OSError):
    """Base for everything this module raises on purpose."""
    pass


class ConnectionError(RequestException):
    pass


class Timeout(RequestException):
    pass


class HTTPError(RequestException):
    """Raised by Response.raise_for_status() for 4xx/5xx."""

    def __init__(self, msg, response=None):
        RequestException.__init__(self, msg)
        self.response = response


def _card():
    try:
        return component.internet
    except AttributeError:
        raise OSError("no internet card installed")


class Response:
    """The result of a request. Body access:

    .content     bytes (drains the stream on first use)
    .text        str, decoded per the charset in Content-Type (default utf-8)
    .json()      parsed .text
    .iter_content(n)  streaming chunks, for stream=True requests
    """

    def __init__(self, url, handle, status_code, reason, headers, timeout):
        self.url = url
        self.status_code = status_code
        self.reason = reason
        self.headers = headers          # dict, keys lower-cased
        self._h = handle                # None once fully drained/closed
        self._timeout = timeout
        self._body = None               # cached full body
        self._consumed = False

    @property
    def ok(self):
        return self.status_code < 400

    @property
    def content(self):
        if self._body is None:
            parts = []
            for chunk in self.iter_content():
                parts.append(chunk)
            self._body = b"".join(parts)
        return self._body

    @property
    def text(self):
        data = self.content
        try:
            return data.decode(self.encoding)
        except Exception:
            pass
        try:
            return data.decode()
        except Exception:
            # undecodable bytes: fall back to latin-1 semantics
            return "".join([chr(b) for b in data])

    @property
    def encoding(self):
        ct = self.headers.get("content-type", "")
        i = ct.find("charset=")
        if i >= 0:
            enc = ct[i + 8:].split(";")[0].strip().strip('"')
            if enc:
                return enc
        return "utf-8"

    def json(self):
        return _json.loads(self.text)

    def iter_content(self, chunk_size=4096):
        """Yield body chunks as they arrive. Each chunk waits at most `timeout`
        in-game seconds. After exhaustion the handle is closed."""
        if self._body is not None:
            if self._body:
                yield self._body
            return
        if self._consumed:
            raise RequestException("response body already consumed")
        self._consumed = True
        h = self._h
        if h is None:
            self._body = b""
            return
        while True:
            deadline = (None if self._timeout is None
                        else computer.uptime() + self._timeout)
            while True:
                chunk = h.read(chunk_size)
                if chunk is None:            # EOF
                    self.close()
                    return
                if len(chunk):
                    break
                if deadline is not None and computer.uptime() >= deadline:
                    self.close()
                    raise Timeout("read timed out: " + self.url)
                time.sleep(_POLL)
            yield chunk

    def raise_for_status(self):
        if self.status_code >= 400:
            raise HTTPError(str(self.status_code) + " " +
                            (self.reason or "") + " for url: " + self.url,
                            response=self)

    def close(self):
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

    def __repr__(self):
        return "<Response [" + str(self.status_code) + "]>"


def request(method, url, params=None, data=None, json=None, headers=None,
            timeout=DEFAULT_TIMEOUT, stream=False):
    """Send an HTTP request; return a Response.

    method   "GET"/"POST"/"HEAD"/"PUT"/"PATCH"/"DELETE"/...
    params   dict appended to the URL as a query string
    data     request body: str, bytes, or dict (form-encoded)
    json     request body: any json-serializable object (sets Content-Type)
    headers  dict of extra request headers
    timeout  in-game seconds per phase (connect / each read); None = no limit
    stream   False (default): body is fetched before returning.
             True: body left on the wire; use iter_content()/content.
    """
    if not (url.startswith("http://") or url.startswith("https://")):
        raise ValueError("unsupported URL scheme (need http:// or https://): " + url)

    method = method.upper()
    hdrs = {}
    if headers:
        for k in headers:
            hdrs[str(k)] = str(headers[k])

    body = None
    if json is not None:
        body = _json.dumps(json)
        if not _has_header(hdrs, "content-type"):
            hdrs["Content-Type"] = "application/json"
    elif data is not None:
        if isinstance(data, dict):
            from urllib.parse import urlencode
            body = urlencode(data)
            if not _has_header(hdrs, "content-type"):
                hdrs["Content-Type"] = "application/x-www-form-urlencoded"
        elif isinstance(data, bytes) or isinstance(data, bytearray):
            body = _decode_body(bytes(data))
        else:
            body = str(data)

    if params:
        from urllib.parse import urlencode
        url = url + ("&" if "?" in url else "?") + urlencode(params)

    card = _card()
    r = card.request(url, body, hdrs, method)
    if isinstance(r, tuple):       # (None, "http requests are unavailable")
        raise ConnectionError(r[1] if len(r) > 1 and r[1] else "request failed")
    h = r

    # Wait for the response headers. finishConnect() is False while in flight;
    # it RAISES both for genuine failures (DNS, refused) and -- OC quirk -- for
    # any non-2xx status. In the latter case the status line and headers are
    # still available via response(), so we recover a real Response from it.
    deadline = None if timeout is None else computer.uptime() + timeout
    failure = None
    while True:
        try:
            if h.finishConnect():
                break
        except Exception as e:
            failure = e
            break
        if deadline is not None and computer.uptime() >= deadline:
            try:
                h.close()
            except Exception:
                pass
            raise Timeout("connect timed out: " + url)
        time.sleep(_POLL)

    resp = h.response()
    if resp is None or not isinstance(resp, tuple):
        # failed before any HTTP response: a real connection error
        try:
            h.close()
        except Exception:
            pass
        raise ConnectionError(str(failure) if failure else ("no response: " + url))

    code, reason, raw_headers = resp[0], resp[1], resp[2] if len(resp) > 2 else {}
    out = Response(url, h if failure is None else None,
                   int(code), reason, _clean_headers(raw_headers), timeout)
    if failure is not None:
        # OC discarded the body of this non-2xx response; close the handle.
        try:
            h.close()
        except Exception:
            pass
    if not stream:
        out.content            # drain now so the handle is freed
        out.close()
    return out


def get(url, **kw):
    return request("GET", url, **kw)


def head(url, **kw):
    return request("HEAD", url, **kw)


def post(url, **kw):
    return request("POST", url, **kw)


def put(url, **kw):
    return request("PUT", url, **kw)


def patch(url, **kw):
    return request("PATCH", url, **kw)


def delete(url, **kw):
    return request("DELETE", url, **kw)


def _has_header(hdrs, lower_name):
    for k in hdrs:
        if k.lower() == lower_name:
            return True
    return False


def _clean_headers(raw):
    """OC hands back Java's Map<String, List<String>> (plus a None key holding
    the status line). Flatten to a plain dict with lower-case keys."""
    out = {}
    if isinstance(raw, dict):
        for k in raw:
            if k is None:
                continue
            v = raw[k]
            if isinstance(v, (list, tuple)):
                v = ", ".join([str(x) for x in v])
            out[str(k).lower()] = str(v)
    return out


def _decode_body(data):
    """The OC card sends POST bodies as text; make bytes survive the trip."""
    try:
        return data.decode()
    except Exception:
        return "".join([chr(b) for b in data])
