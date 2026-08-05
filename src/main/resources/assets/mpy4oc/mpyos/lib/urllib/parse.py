"""urllib.parse -- the pure-Python corner of urllib.

Implements the pieces day-to-day scripts actually reach for: percent-encoding
(quote/unquote and the *_plus forms), query strings (urlencode/parse_qs),
splitting and joining URLs (urlparse/urlunparse/urljoin). No internet card
needed -- this is plain string work.
"""

_ALWAYS_SAFE = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                "abcdefghijklmnopqrstuvwxyz"
                "0123456789_.-~")

_HEX = "0123456789ABCDEF"


def quote(s, safe="/"):
    """Percent-encode s, leaving alphanumerics, '_.-~' and `safe` untouched."""
    if isinstance(s, str):
        s = s.encode()
    keep = _ALWAYS_SAFE + safe
    out = []
    for b in s:
        c = chr(b)
        if c in keep:
            out.append(c)
        else:
            out.append("%" + _HEX[b >> 4] + _HEX[b & 15])
    return "".join(out)


def quote_plus(s, safe=""):
    """Like quote(), but encodes spaces as '+' (form encoding)."""
    if isinstance(s, str) and " " in s:
        return quote(s.replace(" ", "\x00"), safe).replace("%00", "+")
    if isinstance(s, bytes) and b" " in s:
        return quote(s.replace(b" ", b"\x00"), safe).replace("%00", "+")
    return quote(s, safe)


def unquote(s):
    """Decode %xx escapes (utf-8)."""
    parts = s.split("%")
    if len(parts) == 1:
        return s
    out = [parts[0].encode()]
    for part in parts[1:]:
        if len(part) >= 2:
            try:
                out.append(bytes([int(part[:2], 16)]) + part[2:].encode())
                continue
            except ValueError:
                pass
        out.append(b"%" + part.encode())
    return b"".join(out).decode()


def unquote_plus(s):
    return unquote(s.replace("+", " "))


def urlencode(query):
    """dict or list of (key, value) pairs -> 'a=1&b=2' (form-encoded)."""
    if isinstance(query, dict):
        query = list(query.items())
    parts = []
    for k, v in query:
        parts.append(quote_plus(str(k)) + "=" + quote_plus(str(v)))
    return "&".join(parts)


def parse_qs(qs):
    """'a=1&a=2&b=x' -> {'a': ['1', '2'], 'b': ['x']}"""
    out = {}
    for field in qs.split("&"):
        if not field:
            continue
        if "=" in field:
            k, v = field.split("=", 1)
        else:
            k, v = field, ""
        out.setdefault(unquote_plus(k), []).append(unquote_plus(v))
    return out


class ParseResult:
    """Result of urlparse(): behaves like the CPython 6-tuple with named fields
    scheme/netloc/path/params/query/fragment, plus hostname/port helpers."""

    def __init__(self, scheme, netloc, path, params, query, fragment):
        self.scheme = scheme
        self.netloc = netloc
        self.path = path
        self.params = params
        self.query = query
        self.fragment = fragment

    def _tuple(self):
        return (self.scheme, self.netloc, self.path,
                self.params, self.query, self.fragment)

    def __getitem__(self, i):
        return self._tuple()[i]

    def __iter__(self):
        return iter(self._tuple())

    def __len__(self):
        return 6

    def __eq__(self, other):
        if isinstance(other, ParseResult):
            other = other._tuple()
        return self._tuple() == other

    def __repr__(self):
        return ("ParseResult(scheme=%r, netloc=%r, path=%r, params=%r, "
                "query=%r, fragment=%r)") % self._tuple()

    @property
    def hostname(self):
        host = self.netloc
        if "@" in host:
            host = host.split("@", 1)[1]
        if host.startswith("["):                 # [ipv6]:port
            return host[1:].split("]", 1)[0].lower()
        if ":" in host:
            host = host.split(":", 1)[0]
        return host.lower() if host else None

    @property
    def port(self):
        host = self.netloc
        if "@" in host:
            host = host.split("@", 1)[1]
        if host.startswith("["):
            rest = host.split("]", 1)
            host = rest[1] if len(rest) > 1 else ""
        if ":" in host:
            p = host.rsplit(":", 1)[1]
            if p:
                return int(p)
        return None

    def geturl(self):
        return urlunparse(self)


def urlparse(url):
    """Split a URL into ParseResult(scheme, netloc, path, params, query, fragment)."""
    scheme = netloc = params = query = fragment = ""
    rest = url
    if "#" in rest:
        rest, fragment = rest.split("#", 1)
    # scheme: letters+digits+.+- before ':', per RFC 3986
    i = rest.find(":")
    if i > 0:
        cand = rest[:i]
        ok = cand[0].isalpha()
        for ch in cand:
            if not (ch.isalpha() or ch.isdigit() or ch in "+-."):
                ok = False
                break
        if ok:
            scheme, rest = cand.lower(), rest[i + 1:]
    if rest.startswith("//"):
        rest = rest[2:]
        for j in range(len(rest) + 1):
            if j == len(rest) or rest[j] in "/?#":
                netloc, rest = rest[:j], rest[j:]
                break
    if "?" in rest:
        rest, query = rest.split("?", 1)
    path = rest
    if ";" in path:
        path, params = path.rsplit(";", 1)
    return ParseResult(scheme, netloc, path, params, query, fragment)


def urlunparse(parts):
    scheme, netloc, path, params, query, fragment = tuple(parts)
    out = ""
    if scheme:
        out += scheme + ":"
    if netloc or (path and path.startswith("//")) or scheme in ("http", "https", "ftp", "file"):
        out += "//" + netloc
    out += path
    if params:
        out += ";" + params
    if query:
        out += "?" + query
    if fragment:
        out += "#" + fragment
    return out


def urljoin(base, url):
    """Resolve `url` relative to `base` (the common cases of RFC 3986)."""
    if not base:
        return url
    if not url:
        return base
    u = urlparse(url)
    if u.scheme:                       # already absolute
        return url
    b = urlparse(base)
    scheme = b.scheme
    if url.startswith("//"):           # network-path reference
        return scheme + ":" + url
    if u.path.startswith("/"):
        path = _normpath(u.path)
        return urlunparse((scheme, b.netloc, path, u.params, u.query, u.fragment))
    if not u.path:
        # query/fragment-only reference: keep base path
        query = u.query if u.query else b.query
        return urlunparse((scheme, b.netloc, b.path, b.params, query, u.fragment))
    # relative path: replace the last segment of the base path
    bdir = b.path.rsplit("/", 1)[0] if "/" in b.path else ""
    path = _normpath(bdir + "/" + u.path)
    return urlunparse((scheme, b.netloc, path, u.params, u.query, u.fragment))


def _normpath(path):
    """Collapse ./ and ../ segments of a URL path."""
    out = []
    for seg in path.split("/"):
        if seg == ".":
            continue
        if seg == "..":
            if out and out[-1] not in ("", ".."):
                out.pop()
            continue
        out.append(seg)
    if path.endswith("/.") or path.endswith("/.."):
        out.append("")
    joined = "/".join(out)
    if path.startswith("/") and not joined.startswith("/"):
        joined = "/" + joined
    return joined
