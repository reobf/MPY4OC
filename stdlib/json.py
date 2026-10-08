# frozen json module: pure Python (no primitives except the __jtype dispatcher).

def dumps(obj):
    t = __jtype(obj)
    if obj is None:
        return "null"
    if t == "bool":
        return "true" if obj else "false"
    if t == "int" or t == "float":
        return str(obj)
    if t == "str":
        return _esc(obj)
    if t == "list" or t == "tuple":
        parts = []
        for v in obj:
            parts.append(dumps(v))
        return "[" + ", ".join(parts) + "]"
    if t == "dict":
        parts = []
        for k in obj.keys():
            kt = __jtype(k)
            if kt == "str":
                ks = _esc(k)
            elif kt == "int" or kt == "float":
                ks = _esc(str(k))
            elif kt == "bool":
                ks = _esc("true" if k else "false")
            elif k is None:
                ks = _esc("null")
            else:
                raise TypeError("keys must be str, int, float, bool or None")
            parts.append(ks + ": " + dumps(obj[k]))
        return "{" + ", ".join(parts) + "}"
    raise TypeError("can't serialize " + t)

_HEX = "0123456789abcdef"

def _esc(s):
    out = '"'
    for ch in s:
        c = ord(ch)
        if ch == '"':
            out += '\\"'
        elif ch == "\\":
            out += "\\\\"
        elif ch == "\n":
            out += "\\n"
        elif ch == "\t":
            out += "\\t"
        elif ch == "\r":
            out += "\\r"
        elif c < 32:
            out += "\\u" + _HEX[(c >> 12) & 15] + _HEX[(c >> 8) & 15] + _HEX[(c >> 4) & 15] + _HEX[c & 15]
        else:
            out += ch
    return out + '"'

def loads(s):
    val, i = _value(s, _ws(s, 0))
    i = _ws(s, i)
    if i != len(s):
        raise ValueError("trailing garbage in JSON")
    return val

def _ws(s, i):
    while i < len(s) and (s[i] == " " or s[i] == "\t" or s[i] == "\n" or s[i] == "\r"):
        i += 1
    return i

def _value(s, i):
    if i >= len(s):
        raise ValueError("unexpected end of JSON")
    c = s[i]
    if c == "{":
        return _object(s, i)
    if c == "[":
        return _array(s, i)
    if c == '"':
        return _string(s, i)
    if s[i:i + 4] == "true":
        return True, i + 4
    if s[i:i + 5] == "false":
        return False, i + 5
    if s[i:i + 4] == "null":
        return None, i + 4
    return _number(s, i)

def _object(s, i):
    out = {}
    i = _ws(s, i + 1)
    if i < len(s) and s[i] == "}":
        return out, i + 1
    while True:
        k, i = _string(s, _ws(s, i))
        i = _ws(s, i)
        if i >= len(s) or s[i] != ":":
            raise ValueError("expected ':' in object")
        v, i = _value(s, _ws(s, i + 1))
        out[k] = v
        i = _ws(s, i)
        if i < len(s) and s[i] == ",":
            i += 1
            continue
        if i < len(s) and s[i] == "}":
            return out, i + 1
        raise ValueError("expected ',' or '}' in object")

def _array(s, i):
    out = []
    i = _ws(s, i + 1)
    if i < len(s) and s[i] == "]":
        return out, i + 1
    while True:
        v, i = _value(s, i)
        out.append(v)
        i = _ws(s, i)
        if i < len(s) and s[i] == ",":
            i = _ws(s, i + 1)
            continue
        if i < len(s) and s[i] == "]":
            return out, i + 1
        raise ValueError("expected ',' or ']' in array")

def _string(s, i):
    if i >= len(s) or s[i] != '"':
        raise ValueError("expected string")
    out = ""
    i += 1
    while True:
        if i >= len(s):
            raise ValueError("unterminated string")
        c = s[i]
        if c == '"':
            return out, i + 1
        if c == "\\":
            i += 1
            c = s[i]
            if c == "n":
                out += "\n"
            elif c == "t":
                out += "\t"
            elif c == "r":
                out += "\r"
            elif c == "b":
                out += "\b"
            elif c == "f":
                out += "\f"
            elif c == "u":
                out += chr(_hex4(s, i + 1))
                i += 4
            else:
                out += c    # '"', '\\', '/'
            i += 1
        else:
            out += c
            i += 1

def _hex4(s, i):
    n = 0
    for k in range(4):
        c = s[i + k]
        d = _HEX.find(c)
        if d < 0:
            d = _HEX.find(c.lower())
        if d < 0:
            raise ValueError("bad \\u escape")
        n = n * 16 + d
    return n

def _number(s, i):
    j = i
    if j < len(s) and (s[j] == "-" or s[j] == "+"):
        j += 1
    isfloat = False
    while j < len(s):
        c = s[j]
        if c.isdigit():
            j += 1
        elif c == "." or c == "e" or c == "E" or c == "-" or c == "+":
            if c == "." or c == "e" or c == "E":
                isfloat = True
            j += 1
        else:
            break
    if j == i:
        raise ValueError("invalid JSON value")
    text = s[i:j]
    if isfloat:
        return float(text), j
    return int(text), j
