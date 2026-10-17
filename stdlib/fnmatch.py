"""Case-sensitive POSIX filename matching; matching consumes the VM ops budget."""
_cache = {}

def _tokens(pattern):
    if not isinstance(pattern, str):
        raise TypeError('filename patterns must be str')
    if pattern in _cache:
        return _cache[pattern]
    result, i = [], 0
    while i < len(pattern):
        c = pattern[i]
        i += 1
        if c == '*':
            if not result or result[-1][0] != '*':
                result.append(('*',))
        elif c == '?':
            result.append(('?',))
        elif c == '[':
            start = i
            if i < len(pattern) and pattern[i] == '!':
                i += 1
            if i < len(pattern) and pattern[i] == ']':
                i += 1
            end = pattern.find(']', i)
            if end < 0:
                result.append(('=', '[')); i = start; continue
            raw, i = pattern[start:end], end + 1
            negate = raw.startswith('!')
            raw = raw[1:] if negate else raw
            ranges, j = [], 0
            while j < len(raw):
                if j + 2 < len(raw) and raw[j + 1] == '-':
                    if raw[j] <= raw[j + 2]:
                        ranges.append((raw[j], raw[j + 2]))
                    j += 3
                else:
                    ranges.append((raw[j], raw[j])); j += 1
            result.append(('[]', negate, ranges))
        else:
            result.append(('=', c))
    if len(_cache) >= 64:
        _cache.clear()
    _cache[pattern] = result
    return result

def _one(c, token):
    if token[0] == '?':
        return True
    if token[0] == '=':
        return c == token[1]
    if token[0] == '[]':
        hit = any(lo <= c <= hi for lo, hi in token[2])
        return not hit if token[1] else hit
    return False

def fnmatchcase(name, pat):
    if not isinstance(name, str):
        raise TypeError('filenames must be str')
    tokens = _tokens(pat)
    i, j, star, retry = 0, 0, -1, 0
    while i < len(name):
        if j < len(tokens) and tokens[j][0] == '*':
            star, retry, j = j, i, j + 1
        elif j < len(tokens) and _one(name[i], tokens[j]):
            i += 1; j += 1
        elif star >= 0:
            retry += 1; i, j = retry, star + 1
        else:
            return False
    while j < len(tokens) and tokens[j][0] == '*':
        j += 1
    return j == len(tokens)

fnmatch = fnmatchcase

def filter(names, pat):
    _tokens(pat)
    return [name for name in names if fnmatchcase(name, pat)]

def translate(pat):
    import re
    out = []
    for token in _tokens(pat):
        if token[0] == '*':
            out.append('.*')
        elif token[0] == '?':
            out.append('.')
        elif token[0] == '=':
            out.append(re.escape(token[1]))
        elif not token[2]:
            out.append('.' if token[1] else '(?!)')
        else:
            chars = []
            for lo, hi in token[2]:
                chars.append(re.escape(lo) if lo == hi else re.escape(lo) + '-' + re.escape(hi))
            out.append('[' + ('^' if token[1] else '') + ''.join(chars) + ']')
    return '(?s:' + ''.join(out) + ')\\Z'
