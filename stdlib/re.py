"""Common Python regular-expression API over Java regex; str patterns only."""
NOFLAG = 0
IGNORECASE = I = 2
LOCALE = L = 4
MULTILINE = M = 8
DOTALL = S = 16
UNICODE = U = 32
VERBOSE = X = 64
DEBUG = 128
ASCII = A = 256
_native_exec, _native_info, _native_purge = __re_exec, __re_info, __re_purge

class error(ValueError):
    def __init__(self, msg, pattern=None, pos=None):
        self.msg, self.pattern, self.pos = msg, pattern, pos
        self.lineno = self.colno = None
        detail = msg
        if pos is not None:
            self.lineno = pattern.count('\n', 0, pos) + 1
            self.colno = pos - pattern.rfind('\n', 0, pos)
            detail += ' at position ' + str(pos)
        ValueError.__init__(self, detail)

PatternError = error

def compile(pattern, flags=0):
    if isinstance(pattern, Pattern):
        if flags:
            raise ValueError('cannot process flags argument with a compiled pattern')
        return pattern
    return Pattern(pattern, flags)

def purge():
    _native_purge()

def escape(pattern):
    if not isinstance(pattern, str):
        raise TypeError('pattern must be str')
    return ''.join(('\\' + c if c in '()[]{}?*+-|^$\\.&~# \t\n\r\v\f' else c) for c in pattern)

def match(pattern, string, flags=0):
    return compile(pattern, flags).match(string)

def search(pattern, string, flags=0):
    return compile(pattern, flags).search(string)

def fullmatch(pattern, string, flags=0):
    return compile(pattern, flags).fullmatch(string)

def finditer(pattern, string, flags=0):
    return compile(pattern, flags).finditer(string)

def findall(pattern, string, flags=0):
    return compile(pattern, flags).findall(string)

def split(pattern, string, maxsplit=0, flags=0):
    return compile(pattern, flags).split(string, maxsplit)

def sub(pattern, repl, string, count=0, flags=0):
    return compile(pattern, flags).sub(repl, string, count)

def subn(pattern, repl, string, count=0, flags=0):
    return compile(pattern, flags).subn(repl, string, count)

class Pattern:
    def __init__(self, pattern, flags=0):
        if not isinstance(pattern, str):
            raise TypeError('first argument must be str or compiled pattern')
        if not isinstance(flags, int):
            raise TypeError('flags must be an integer')
        if flags & LOCALE:
            raise ValueError('cannot use LOCALE flag with a str pattern')
        self.pattern = pattern
        try:
            self.flags, self.groups, self.groupindex = _native_info(pattern, flags)
        except ValueError as exc:
            if len(exc.args) == 2:
                raise error(exc.args[0], pattern, exc.args[1])
            raise

    def _match(self, string, pos, endpos, anchored, mode=0):
        if not isinstance(string, str):
            raise TypeError('expected string')
        if not isinstance(pos, int) or endpos is not None and not isinstance(endpos, int):
            raise TypeError('position must be an integer')
        pos = max(0, pos)
        endpos = len(string) if endpos is None else min(max(0, endpos), len(string))
        spans = _native_exec(self.pattern, string, anchored, pos, endpos, self.flags, mode)
        return None if spans is None else Match(self, string, spans, pos, endpos)

    def match(self, string, pos=0, endpos=None):
        return self._match(string, pos, endpos, True)

    def search(self, string, pos=0, endpos=None):
        return self._match(string, pos, endpos, False)

    def fullmatch(self, string, pos=0, endpos=None):
        return self._match(string, pos, endpos, True, 1)

    def finditer(self, string, pos=0, endpos=None):
        initial = max(0, pos)
        end = len(string) if endpos is None else min(max(0, endpos), len(string))
        pos = initial
        empty = False
        while pos <= end:
            match = self._match(string, pos, end, empty, 2 if empty else 0)
            if match is None:
                if empty:
                    pos += 1
                    empty = False
                    continue
                return
            match.pos = initial
            yield match
            pos = match.end()
            empty = pos == match.start()

    def findall(self, string, pos=0, endpos=None):
        result = []
        for match in self.finditer(string, pos, endpos):
            result.append(match.group() if self.groups == 0 else (match.group(1) or '') if self.groups == 1 else match.groups(''))
        return result

    def split(self, string, maxsplit=0):
        result, pos, count = [], 0, 0
        if maxsplit >= 0:
            for match in self.finditer(string):
                if maxsplit and count >= maxsplit:
                    break
                result.append(string[pos:match.start()])
                result.extend(match.groups())
                pos, count = match.end(), count + 1
        result.append(string[pos:])
        return result

    def sub(self, repl, string, count=0):
        return self.subn(repl, string, count)[0]

    def subn(self, repl, string, count=0):
        parts, pos, number = [], 0, 0
        if not callable(repl) and not isinstance(repl, str):
            raise TypeError('replacement must be str or callable')
        if count >= 0:
            for match in self.finditer(string):
                if count and number >= count:
                    break
                parts.append(string[pos:match.start()])
                parts.append(repl(match) if callable(repl) else match.expand(repl))
                pos, number = match.end(), number + 1
        parts.append(string[pos:])
        return ''.join(parts), number

class Match:
    def __init__(self, pattern, string, spans, pos, endpos):
        self.re, self.string, self.pos, self.endpos = pattern, string, pos, endpos
        self._spans = spans
        self.regs = tuple((spans[i], spans[i + 1]) for i in range(0, len(spans), 2))

    def _index(self, group):
        if isinstance(group, str):
            if group not in self.re.groupindex:
                raise IndexError('no such group')
            group = self.re.groupindex[group]
        if not isinstance(group, int) or group < 0 or group > self.re.groups:
            raise IndexError('no such group')
        return group * 2

    def start(self, group=0):
        return self._spans[self._index(group)]

    def end(self, group=0):
        return self._spans[self._index(group) + 1]

    def span(self, group=0):
        return self.start(group), self.end(group)

    def _group(self, group):
        start, end = self.span(group)
        return None if start < 0 else self.string[start:end]

    def group(self, *groups):
        if not groups:
            return self._group(0)
        if len(groups) == 1:
            return self._group(groups[0])
        return tuple(self._group(g) for g in groups)

    def __getitem__(self, group):
        return self._group(group)

    def groups(self, default=None):
        return tuple(default if self.start(i) < 0 else self._group(i) for i in range(1, self.re.groups + 1))

    def groupdict(self, default=None):
        return {name: default if self.start(name) < 0 else self._group(name) for name in self.re.groupindex}

    def expand(self, template):
        parts, i = [], 0
        escapes = {'n': '\n', 'r': '\r', 't': '\t', 'a': '\a', 'b': '\b', 'f': '\f', 'v': '\v', '\\': '\\'}
        while i < len(template):
            char = template[i]
            i += 1
            if char != '\\':
                parts.append(char)
                continue
            if i >= len(template):
                raise error('bad escape (end of pattern)', template, i - 1)
            char = template[i]
            i += 1
            if char == 'g':
                if i >= len(template) or template[i] != '<':
                    raise error('missing <', template, i)
                end = template.find('>', i + 1)
                if end < 0:
                    raise error('missing >', template, i)
                name = template[i + 1:end]
                group = int(name) if name.isdigit() else name
                parts.append(self.group(group) or '')
                i = end + 1
            elif char in '0123456789':
                digits = char
                if char == '0':
                    while len(digits) < 3 and i < len(template) and template[i] in '01234567':
                        digits += template[i]
                        i += 1
                    parts.append(chr(int(digits, 8)))
                else:
                    if i < len(template) and template[i] in '0123456789':
                        digits += template[i]
                        i += 1
                    if len(digits) == 2 and all(c in '01234567' for c in digits) and i < len(template) and template[i] in '01234567':
                        digits += template[i]
                        i += 1
                        value = int(digits, 8)
                        if value > 255:
                            raise error('octal escape value outside of range 0-0o377')
                        parts.append(chr(value))
                    else:
                        try:
                            parts.append(self.group(int(digits)) or '')
                        except IndexError:
                            raise error('invalid group reference')
            elif char in escapes:
                parts.append(escapes[char])
            elif char in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ':
                raise error('bad escape \\' + char, template, i - 2)
            else:
                parts.append('\\' + char)
        return ''.join(parts)
