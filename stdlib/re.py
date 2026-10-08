# frozen re module over one Java primitive: __re_exec(pattern, string,
# anchored, pos) -> None | flat span list [s0, e0, s1, e1, ...] (-1 for
# unmatched groups). The engine is java.util.regex - a superset of
# MicroPython's re (e.g. {n,m} works here but not there).

class Match:
    def __init__(self, s, spans):
        self._s = s
        self._spans = spans

    def group(self, n=0):
        a = self._spans[2 * n]
        if a < 0:
            return None
        return self._s[a:self._spans[2 * n + 1]]

    def start(self, n=0):
        return self._spans[2 * n]

    def end(self, n=0):
        return self._spans[2 * n + 1]

    def span(self, n=0):
        return (self._spans[2 * n], self._spans[2 * n + 1])

    def groups(self):
        out = []
        i = 2
        while i < len(self._spans):
            a = self._spans[i]
            out.append(None if a < 0 else self._s[a:self._spans[i + 1]])
            i += 2
        return tuple(out)

def _exec(p, s, anchored, pos):
    spans = __re_exec(p, s, anchored, pos)
    if spans is None:
        return None
    return Match(s, spans)

def match(pattern, string):
    return _exec(pattern, string, True, 0)

def search(pattern, string):
    return _exec(pattern, string, False, 0)

def sub(pattern, repl, string, count=0):
    out = ""
    pos = 0
    done = 0
    while True:
        if count and done >= count:
            break
        m = _exec(pattern, string, False, pos)
        if m is None:
            break
        a, b = m.span()
        out += string[pos:a]
        if callable_(repl):
            out += repl(m)
        else:
            out += _expand(repl, m)
        done += 1
        if b == a:                      # empty match: emit one char, advance
            if b < len(string):
                out += string[b]
            pos = b + 1
            if pos > len(string):
                break
        else:
            pos = b
    return out + string[pos:]

def callable_(x):
    return __jtype(x) not in ("str", "bytes")

def _expand(repl, m):
    out = ""
    i = 0
    while i < len(repl):
        c = repl[i]
        if c == "\\" and i + 1 < len(repl):
            n = repl[i + 1]
            if n.isdigit():
                g = m.group(int(n))
                out += g if g is not None else ""
                i += 2
                continue
            if n == "\\":
                out += "\\"
                i += 2
                continue
        out += c
        i += 1
    return out

class Pattern:
    def __init__(self, p):
        self._p = p

    def match(self, s):
        return match(self._p, s)

    def search(self, s):
        return search(self._p, s)

    def sub(self, repl, s, count=0):
        return sub(self._p, repl, s, count)

def compile(pattern):
    return Pattern(pattern)
