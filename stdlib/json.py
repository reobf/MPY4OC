# frozen json module: CPython's json API. Parsing and serializing are one host call each
# (__json_loads / __json_dumps), with CPython's grammar, messages, positions and output
# format; the hooks CPython offers (default, object_hook, object_pairs_hook) are applied
# here, in Python, only when given.


class JSONDecodeError(ValueError):
    def __init__(self, msg, doc, pos):
        lineno, colno = __json_location(doc, pos)
        ValueError.__init__(self, '%s: line %d column %d (char %d)' % (msg, lineno, colno, pos))
        self.msg = msg
        self.doc = doc
        self.pos = pos
        self.lineno = lineno
        self.colno = colno


def _convert(obj, default, markers, skipkeys):
    # The serializer runs on the host and cannot call back: apply default() first.
    if obj is None or isinstance(obj, (str, int, float, bool)):
        return obj
    identity = id(obj)
    if markers is not None:
        if identity in markers:
            raise ValueError('Circular reference detected')
        markers[identity] = obj
    try:
        if isinstance(obj, dict):
            result = {}
            for k, v in obj.items():
                if k is not None and not isinstance(k, (str, int, float, bool)):
                    if skipkeys:
                        continue
                    raise TypeError('keys must be str, int, float, bool or None, not ' + type(k).__name__)
                result[k] = _convert(v, default, markers, skipkeys)
            return result
        if isinstance(obj, (list, tuple)):
            return [_convert(v, default, markers, skipkeys) for v in obj]
        return _convert(default(obj), default, markers, skipkeys)
    finally:
        if markers is not None:
            del markers[identity]


def dumps(obj, *, skipkeys=False, ensure_ascii=True, check_circular=True, allow_nan=True, cls=None,
          indent=None, separators=None, default=None, sort_keys=False, **kw):
    if cls is not None:
        raise TypeError('custom JSON encoders are not supported')
    if kw:
        raise TypeError('unexpected JSON encoder keyword argument')
    if separators is None:
        item_separator, key_separator = (', ', ': ') if indent is None else (',', ': ')
    else:
        item_separator, key_separator = separators
    if indent is not None and not isinstance(indent, (int, bool, str)):
        raise TypeError('indent must be None, an int or a str')
    if not isinstance(item_separator, str) or not isinstance(key_separator, str):
        raise TypeError('separators must be strings')
    if default is not None:
        obj = _convert(obj, default, {} if check_circular else None, skipkeys)
    return __json_dumps(obj, indent, item_separator, key_separator, bool(sort_keys), bool(ensure_ascii),
                        bool(skipkeys), bool(allow_nan), bool(check_circular))


def dump(obj, fp, **kw):
    fp.write(dumps(obj, **kw))


def _hook(obj, object_hook, object_pairs_hook):
    if isinstance(obj, tuple):
        pairs = [(k, _hook(v, object_hook, object_pairs_hook)) for k, v in obj]
        if object_pairs_hook is not None:
            return object_pairs_hook(pairs)
        return object_hook(dict(pairs))
    if isinstance(obj, list):
        for i in range(len(obj)):
            obj[i] = _hook(obj[i], object_hook, object_pairs_hook)
        return obj
    if isinstance(obj, dict):
        for k in list(obj):
            obj[k] = _hook(obj[k], object_hook, object_pairs_hook)
        if object_pairs_hook is not None:
            return object_pairs_hook(list(obj.items()))
        if object_hook is not None:
            return object_hook(obj)
    return obj


def loads(s, *, cls=None, object_hook=None, parse_float=None, parse_int=None, parse_constant=None,
          object_pairs_hook=None, strict=True, **kw):
    if isinstance(s, (bytes, bytearray)):
        s = bytes(s).decode('utf-8')
    elif not isinstance(s, str):
        raise TypeError('the JSON object must be str, bytes or bytearray, not ' + type(s).__name__)
    if cls is not None or parse_float is not None or parse_int is not None or parse_constant is not None:
        raise TypeError('custom JSON decoders and parse hooks are not supported')
    if kw:
        raise TypeError('unexpected JSON decoder keyword argument')
    try:
        obj = __json_loads(s, object_pairs_hook is not None or object_hook is not None, bool(strict))
    except ValueError as error:
        if len(error.args) == 2 and isinstance(error.args[1], int):
            raise JSONDecodeError(error.args[0], s, error.args[1])
        raise
    if object_hook is not None or object_pairs_hook is not None:
        obj = _hook(obj, object_hook, object_pairs_hook)
    return obj


def load(fp, **kw):
    return loads(fp.read(), **kw)
