"""Streaming CSV reader/writer and dictionaries; common dialects and QUOTE_* 0..3."""
QUOTE_MINIMAL, QUOTE_ALL, QUOTE_NONNUMERIC, QUOTE_NONE = 0, 1, 2, 3
_limit = 131072

class Error(Exception):
    pass

class Dialect:
    delimiter = ','
    quotechar = '"'
    escapechar = None
    doublequote = True
    skipinitialspace = False
    lineterminator = '\r\n'
    quoting = QUOTE_MINIMAL
    strict = False

class excel(Dialect):
    pass

class excel_tab(excel):
    delimiter = '\t'

class unix_dialect(Dialect):
    lineterminator = '\n'
    quoting = QUOTE_ALL

_dialects = {'excel': excel, 'excel-tab': excel_tab, 'unix': unix_dialect}
_options = ('delimiter', 'quotechar', 'escapechar', 'doublequote', 'skipinitialspace', 'lineterminator', 'quoting', 'strict')

def _dialect(value, kwargs):
    if isinstance(value, str):
        if value not in _dialects:
            raise Error('unknown dialect')
        value = _dialects[value]
    result = Dialect()
    for name in kwargs:
        if name not in _options:
            raise TypeError("invalid keyword argument: '" + name + "'")
    for name in _options:
        setattr(result, name, kwargs.get(name, getattr(value, name, getattr(Dialect, name))))
    if 'quotechar' in kwargs and kwargs['quotechar'] is None and 'quoting' not in kwargs:
        result.quoting = QUOTE_NONE
    if not isinstance(result.delimiter, str) or len(result.delimiter) != 1:
        raise TypeError('delimiter must be a 1-character string')
    for name in ('quotechar', 'escapechar'):
        char = getattr(result, name)
        if char is not None and (not isinstance(char, str) or len(char) != 1):
            raise TypeError(name + ' must be a 1-character string or None')
    if result.quoting not in (0, 1, 2, 3):
        raise TypeError('bad quoting value (supported: QUOTE_MINIMAL/ALL/NONNUMERIC/NONE)')
    if result.quoting != QUOTE_NONE and result.quotechar is None:
        raise TypeError('quotechar must be set if quoting enabled')
    if not isinstance(result.lineterminator, str):
        raise TypeError('lineterminator must be a string')
    return result

def register_dialect(name, dialect='excel', **fmtparams):
    if not isinstance(name, str):
        raise TypeError('dialect name must be a string')
    _dialects[name] = _dialect(dialect, fmtparams)

def unregister_dialect(name):
    if name not in _dialects:
        raise Error('unknown dialect')
    del _dialects[name]

def get_dialect(name):
    return _dialect(name, {})

def list_dialects():
    return list(_dialects)

def field_size_limit(new_limit=None):
    global _limit
    old = _limit
    if new_limit is not None:
        if not isinstance(new_limit, int):
            raise TypeError('limit must be an integer')
        _limit = new_limit
    return old

def reader(iterable, dialect='excel', **fmtparams):
    return _Reader(iterable, _dialect(dialect, fmtparams))

class _Reader:
    def __init__(self, iterable, dialect):
        self.dialect = dialect
        self.line_num = 0
        self._iterator = self._rows(iterable)

    def __iter__(self):
        return self._iterator

    def __next__(self):
        return next(self._iterator)

    def _field(self, parts, quoted):
        text = ''.join(parts)
        return float(text) if self.dialect.quoting == QUOTE_NONNUMERIC and text and not quoted else text

    def _rows(self, iterable):
        d = self.dialect
        row, parts = [], []
        # 0: field start; 1: unquoted; 2: quoted; 3: after quote; 4/5: escaped.
        state, quoted, length = 0, False, 0
        pending = False
        for line in iterable:
            if not isinstance(line, str):
                raise Error('iterator should return strings, not bytes (open in text mode)')
            self.line_num += 1
            ended = False
            escaped_newline = False
            for char in line:
                if ended:
                    if char not in '\r\n':
                        raise Error("new-line character seen in unquoted field - open with newline=''")
                    continue
                pending = True
                append = None
                if state in (4, 5):
                    append = char
                    escaped_newline = state == 4 and char in '\r\n'
                    state = 2 if state == 5 else 1
                elif state == 2:
                    if char == d.escapechar:
                        state = 5
                    elif char == d.quotechar:
                        state = 3 if d.doublequote else 1
                    else:
                        append = char
                elif state == 3 and char == d.quotechar:
                    append, state = char, 2
                elif char == d.delimiter:
                    row.append(self._field(parts, quoted))
                    parts, quoted, length, state = [], False, 0, 0
                elif char in '\r\n':
                    if row or parts or state != 0:
                        row.append(self._field(parts, quoted))
                    ended = True
                elif state == 3:
                    if d.strict:
                        raise Error("delimiter expected after quote")
                    append, state = char, 1
                elif char == d.escapechar:
                    state = 4
                elif state == 0 and char == ' ' and d.skipinitialspace:
                    pass
                elif state == 0 and char == d.quotechar and d.quoting != QUOTE_NONE:
                    state, quoted = 2, True
                else:
                    append, state = char, 1
                if append is not None:
                    length += 1
                    if length > _limit:
                        raise Error('field larger than field limit (' + str(_limit) + ')')
                    parts.append(append)
            if ended:
                yield row
                row, parts = [], []
                state, quoted, length, pending = 0, False, 0, False
            elif state in (2, 5) or escaped_newline:
                continue
            elif state == 4:
                parts.append('\n')
                length += 1
                state = 1
            else:
                if pending:
                    row.append(self._field(parts, quoted))
                yield row
                row, parts = [], []
                state, quoted, length, pending = 0, False, 0, False
        if pending:
            if state in (2, 4, 5) and d.strict:
                raise Error('unexpected end of data')
            row.append(self._field(parts, quoted))
            yield row

def writer(file, dialect='excel', **fmtparams):
    return _Writer(file, _dialect(dialect, fmtparams))

class _Writer:
    def __init__(self, file, dialect):
        self._write = file.write
        self.dialect = dialect

    def writerow(self, row):
        values = [value for value in row]
        out = []
        d = self.dialect
        for value in values:
            text = '' if value is None else str(value)
            numeric = isinstance(value, (int, float, bool, complex))
            quoted = d.quoting == QUOTE_ALL or d.quoting == QUOTE_NONNUMERIC and not numeric
            special = d.delimiter + '\r\n' + d.lineterminator
            if d.doublequote or d.quoting == QUOTE_NONE:
                special += d.quotechar or ''
            if d.quoting == QUOTE_MINIMAL and any(c in special for c in text):
                quoted = True
            if len(values) == 1 and text == '':
                if d.quoting == QUOTE_NONE:
                    raise Error('single empty field record must be quoted')
                quoted = True
            escaped = []
            for char in text:
                if d.quoting != QUOTE_NONE and char == d.quotechar and d.doublequote:
                    escaped.append(char + char)
                    quoted = True
                elif char == d.escapechar or d.quoting == QUOTE_NONE and char in special or char == d.quotechar and not d.doublequote:
                    if d.escapechar is None:
                        raise Error('need to escape, but no escapechar set')
                    escaped.append(d.escapechar + char)
                else:
                    escaped.append(char)
            field = ''.join(escaped)
            out.append(d.quotechar + field + d.quotechar if quoted else field)
        return self._write(d.delimiter.join(out) + d.lineterminator)

    def writerows(self, rows):
        for row in rows:
            self.writerow(row)

class DictReader:
    def __init__(self, f, fieldnames=None, restkey=None, restval=None, dialect='excel', **fmtparams):
        self.reader = reader(f, dialect, **fmtparams)
        self._fieldnames = None if fieldnames is None else list(fieldnames)
        self.restkey, self.restval = restkey, restval
        self.dialect = dialect

    @property
    def line_num(self):
        return self.reader.line_num

    @property
    def fieldnames(self):
        if self._fieldnames is None:
            self._fieldnames = next(self.reader, None)
        return self._fieldnames

    @fieldnames.setter
    def fieldnames(self, values):
        self._fieldnames = values

    def __next__(self):
        names = self.fieldnames
        row = next(self.reader)
        while not row:
            row = next(self.reader)
        result = {}
        for i, name in enumerate(names):
            result[name] = row[i] if i < len(row) else self.restval
        if len(row) > len(names):
            result[self.restkey] = row[len(names):]
        return result

    def __iter__(self):
        while True:
            try:
                row = next(self)
            except StopIteration:
                return
            yield row

class DictWriter:
    def __init__(self, f, fieldnames, restval='', extrasaction='raise', dialect='excel', **fmtparams):
        self.fieldnames = list(fieldnames)
        self.restval = restval
        self.extrasaction = extrasaction.lower()
        if self.extrasaction not in ('raise', 'ignore'):
            raise ValueError("extrasaction must be 'raise' or 'ignore'")
        self.writer = writer(f, dialect, **fmtparams)

    def writeheader(self):
        return self.writer.writerow(self.fieldnames)

    def writerow(self, rowdict):
        if self.extrasaction == 'raise':
            for key in rowdict:
                if key not in self.fieldnames:
                    raise ValueError('dict contains fields not in fieldnames: ' + repr(key))
        return self.writer.writerow([rowdict.get(name, self.restval) for name in self.fieldnames])

    def writerows(self, rows):
        for row in rows:
            self.writerow(row)
