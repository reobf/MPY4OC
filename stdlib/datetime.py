"""Gregorian dates and microsecond times; local civil time defaults to UTC.

Fixed offsets are supported. No host timezone/locale or IANA/DST database.
All persistent state is Python data, independent of host calendar objects.
"""
import operator as _op

_now = __datetime_now
MINYEAR, MAXYEAR = 1, 9999
_DAY = 86400000000
_MONTHS = ('January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December')
_WEEKDAYS = ('Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday')

def _leap(y):
    return y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)

def _before(y):
    n = y - 1
    return 365*n + n//4 - n//100 + n//400

def _monthdays(y, m):
    return 29 if m == 2 and _leap(y) else (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m-1]

def _ymd(ordinal):
    ordinal = _op.index(ordinal)
    if not 1 <= ordinal <= 3652059:
        raise ValueError('ordinal must be >= 1 and <= 3652059')
    lo, hi = 1, 10000
    while lo + 1 < hi:
        mid = (lo + hi)//2
        if _before(mid) < ordinal:
            lo = mid
        else:
            hi = mid
    day, month = ordinal - _before(lo), 1
    while day > _monthdays(lo, month):
        day -= _monthdays(lo, month)
        month += 1
    return lo, month, day

def _pad(n, width=2):
    value = str(n)
    return '0' * max(0, width-len(value)) + value

class timedelta:
    def __init__(self, days=0, seconds=0, microseconds=0, milliseconds=0, minutes=0, hours=0, weeks=0):
        self._us = int(round(days*_DAY + seconds*1000000 + microseconds + milliseconds*1000 + minutes*60000000 + hours*3600000000 + weeks*7*_DAY))
        if not -999999999 <= self.days <= 999999999:
            raise OverflowError('timedelta days out of range')
    @property
    def days(self):
        return self._us // _DAY
    @property
    def seconds(self):
        return self._us % _DAY // 1000000
    @property
    def microseconds(self):
        return self._us % 1000000
    def total_seconds(self):
        return self._us / 1000000
    def __bool__(self):
        return self._us != 0
    def __eq__(self, other):
        return isinstance(other, timedelta) and self._us == other._us
    def __ne__(self, other):
        return not self == other
    def __lt__(self, other):
        return self._us < other._us
    def __le__(self, other):
        return self._us <= other._us
    def __gt__(self, other):
        return self._us > other._us
    def __ge__(self, other):
        return self._us >= other._us
    def __add__(self, other):
        if isinstance(other, timedelta):
            return timedelta(microseconds=self._us + other._us)
        return other + self
    __radd__ = __add__
    def __sub__(self, other):
        return timedelta(microseconds=self._us - other._us)
    def __neg__(self):
        return timedelta(microseconds=-self._us)
    def __pos__(self):
        return self
    def __abs__(self):
        return -self if self._us < 0 else self
    def __mul__(self, other):
        return timedelta(microseconds=round(self._us * other))
    __rmul__ = __mul__
    def __truediv__(self, other):
        if isinstance(other, timedelta):
            return self._us / other._us
        return timedelta(microseconds=round(self._us / other))
    def __floordiv__(self, other):
        if isinstance(other, timedelta):
            return self._us // other._us
        return timedelta(microseconds=self._us // _op.index(other))
    def __mod__(self, other):
        return timedelta(microseconds=self._us % other._us)
    def __divmod__(self, other):
        return self // other, self % other
    def __str__(self):
        sec = self.seconds
        text = str(sec//3600) + ':' + _pad(sec//60 % 60) + ':' + _pad(sec % 60)
        if self.microseconds:
            text += '.' + _pad(self.microseconds, 6)
        return (str(self.days) + (' day, ' if abs(self.days) == 1 else ' days, ') if self.days else '') + text
    def __repr__(self):
        args = []
        for key in ('days', 'seconds', 'microseconds'):
            if getattr(self, key):
                args.append(key + '=' + str(getattr(self, key)))
        return 'datetime.timedelta(' + (', '.join(args) if args else '0') + ')'

class tzinfo:
    def utcoffset(self, dt):
        raise NotImplementedError('tzinfo.utcoffset()')
    def dst(self, dt):
        raise NotImplementedError('tzinfo.dst()')
    def tzname(self, dt):
        raise NotImplementedError('tzinfo.tzname()')
    def fromutc(self, dt):
        if dt.tzinfo is not self:
            raise ValueError('fromutc: dt.tzinfo is not self')
        return dt + self.utcoffset(dt)

class timezone(tzinfo):
    def __init__(self, offset, name=None):
        if not isinstance(offset, timedelta):
            raise TypeError('offset must be a timedelta')
        if not -_DAY < offset._us < _DAY:
            raise ValueError('offset must be strictly between -24h and +24h')
        if name is not None and not isinstance(name, str):
            raise TypeError('name must be a string')
        self._offset, self._name = offset, name
    def utcoffset(self, dt):
        return self._offset
    def dst(self, dt):
        return None
    def tzname(self, dt):
        return self._name if self._name is not None else 'UTC' + (_offset(self._offset) if self._offset else '')
    def __str__(self):
        return self.tzname(None)
    def __repr__(self):
        if not self._offset and self._name is None:
            return 'datetime.timezone.utc'
        return 'datetime.timezone(' + repr(self._offset) + (', ' + repr(self._name) if self._name is not None else '') + ')'
    def __eq__(self, other):
        return isinstance(other, timezone) and self._offset == other._offset

def _offset(delta, colon=True):
    if delta is None:
        return ''
    us = delta._us
    sign, us = ('-' if us < 0 else '+'), abs(us)
    sep = ':' if colon else ''
    text = sign + _pad(us//3600000000) + sep + _pad(us//60000000 % 60)
    if us % 60000000:
        text += sep + _pad(us//1000000 % 60)
        if us % 1000000:
            text += '.' + _pad(us % 1000000, 6)
    return text

timezone.utc = timezone(timedelta(0))
timezone.min = timezone(timedelta(hours=-23, minutes=-59))
timezone.max = timezone(timedelta(hours=23, minutes=59))
UTC = timezone.utc

class date:
    def __init__(self, year, month, day):
        year, month, day = _op.index(year), _op.index(month), _op.index(day)
        if not MINYEAR <= year <= MAXYEAR:
            raise ValueError('year out of range')
        if not 1 <= month <= 12:
            raise ValueError('month must be in 1..12')
        if not 1 <= day <= _monthdays(year, month):
            raise ValueError('day is out of range for month')
        self._year, self._month, self._day = year, month, day
    @property
    def year(self):
        return self._year
    @property
    def month(self):
        return self._month
    @property
    def day(self):
        return self._day
    def toordinal(self):
        return _before(self.year) + sum(_monthdays(self.year, m) for m in range(1, self.month)) + self.day
    @classmethod
    def fromordinal(cls, n):
        return cls(*_ymd(n))
    @classmethod
    def fromtimestamp(cls, timestamp):
        return cls.fromordinal(int(timestamp//86400) + 719163)
    @classmethod
    def today(cls):
        return cls.fromtimestamp(_now()/1000000)
    @classmethod
    def fromisoformat(cls, text):
        if not isinstance(text, str):
            raise TypeError('fromisoformat: argument must be str')
        if 'W' in text:
            parts = text.replace('-', '').split('W')
            if len(parts) == 2 and len(parts[0]) == 4 and len(parts[1]) in (2, 3):
                return cls.fromisocalendar(int(parts[0]), int(parts[1][:2]), int(parts[1][2:]) if len(parts[1]) == 3 else 1)
        raw = text.replace('-', '')
        if len(raw) != 8 or not raw.isdigit() or len(text) not in (8, 10) or len(text) == 10 and (text[4] != '-' or text[7] != '-'):
            raise ValueError('Invalid isoformat string: ' + repr(text))
        return cls(int(raw[:4]), int(raw[4:6]), int(raw[6:]))
    @classmethod
    def fromisocalendar(cls, year, week, day):
        jan4 = date(year, 1, 4)
        if not 1 <= day <= 7 or not 1 <= week <= 53:
            raise ValueError('Invalid week or day')
        result = cls.fromordinal(jan4.toordinal() - jan4.weekday() + 7*(week-1) + day-1)
        if result.isocalendar()[0] != year:
            raise ValueError('Invalid week: 53')
        return result
    def weekday(self):
        return (self.toordinal()-1) % 7
    def isoweekday(self):
        return self.weekday()+1
    def isocalendar(self):
        n = self.toordinal()
        y = _ymd(n + 3 - self.weekday())[0]
        jan4 = _before(y) + 4
        monday = jan4 - (jan4-1) % 7
        return y, (n-monday)//7+1, self.isoweekday()
    def replace(self, year=None, month=None, day=None):
        return type(self)(self.year if year is None else year, self.month if month is None else month, self.day if day is None else day)
    def isoformat(self):
        return _pad(self.year, 4) + '-' + _pad(self.month) + '-' + _pad(self.day)
    def __str__(self):
        return self.isoformat()
    def __repr__(self):
        return 'datetime.date(%d, %d, %d)' % (self.year, self.month, self.day)
    def __eq__(self, other):
        return isinstance(other, date) and isinstance(self, datetime) == isinstance(other, datetime) and self.toordinal() == other.toordinal()
    def __ne__(self, other):
        return not self == other
    def __lt__(self, other):
        return self.toordinal() < other.toordinal()
    def __le__(self, other):
        return self == other or self < other
    def __gt__(self, other):
        return other < self
    def __ge__(self, other):
        return self == other or self > other
    def __add__(self, delta):
        if not isinstance(delta, timedelta):
            raise TypeError('date addition requires timedelta')
        try:
            return type(self).fromordinal(self.toordinal() + delta.days)
        except ValueError:
            raise OverflowError('date value out of range')
    __radd__ = __add__
    def __sub__(self, other):
        if isinstance(other, timedelta):
            return self + timedelta(days=-other.days)
        return timedelta(days=self.toordinal() - other.toordinal())
    def strftime(self, format):
        return _strftime(self, format)
    def __format__(self, format):
        return self.strftime(format) if format else str(self)
    def ctime(self):
        return self.strftime('%a %b ') + (' ' if self.day < 10 else '') + str(self.day) + self.strftime(' 00:00:00 %Y')

def _checktime(hour, minute, second, microsecond, zone, fold):
    values = tuple(_op.index(v) for v in (hour, minute, second, microsecond, fold))
    for name, value, limit in zip(('hour', 'minute', 'second', 'microsecond', 'fold'), values, (24, 60, 60, 1000000, 2)):
        if not 0 <= value < limit:
            raise ValueError(name + ' out of range')
    if zone is not None and not isinstance(zone, tzinfo):
        raise TypeError('tzinfo must be None or a tzinfo subclass')
    return values

def _clocktext(obj, timespec):
    if timespec == 'auto':
        timespec = 'microseconds' if obj.microsecond else 'seconds'
    if timespec not in ('hours', 'minutes', 'seconds', 'milliseconds', 'microseconds'):
        raise ValueError('Unknown timespec value')
    text = _pad(obj.hour)
    if timespec != 'hours':
        text += ':' + _pad(obj.minute)
    if timespec not in ('hours', 'minutes'):
        text += ':' + _pad(obj.second)
    if timespec in ('milliseconds', 'microseconds'):
        text += '.' + (_pad(obj.microsecond, 6)[:3] if timespec == 'milliseconds' else _pad(obj.microsecond, 6))
    return text + _offset(obj.utcoffset())

_KEEP = object()
class time:
    def __init__(self, hour=0, minute=0, second=0, microsecond=0, tzinfo=None, *, fold=0):
        self._hour, self._minute, self._second, self._microsecond, self._fold = _checktime(hour, minute, second, microsecond, tzinfo, fold)
        self._tzinfo = tzinfo
    @property
    def hour(self):
        return self._hour
    @property
    def minute(self):
        return self._minute
    @property
    def second(self):
        return self._second
    @property
    def microsecond(self):
        return self._microsecond
    @property
    def tzinfo(self):
        return self._tzinfo
    @property
    def fold(self):
        return self._fold
    def utcoffset(self):
        return None if self.tzinfo is None else self.tzinfo.utcoffset(None)
    def dst(self):
        return None if self.tzinfo is None else self.tzinfo.dst(None)
    def tzname(self):
        return None if self.tzinfo is None else self.tzinfo.tzname(None)
    def _local(self):
        return ((self.hour*60+self.minute)*60+self.second)*1000000 + self.microsecond
    def _compare(self, other, equality=False):
        a, b = self.utcoffset(), other.utcoffset()
        if (a is None) != (b is None):
            if equality:
                return None
            raise TypeError("can't compare offset-naive and offset-aware times")
        return self._local() - (a._us if a else 0) - other._local() + (b._us if b else 0)
    def __eq__(self, other):
        return isinstance(other, time) and self._compare(other, True) == 0
    def __ne__(self, other):
        return not self == other
    def __lt__(self, other):
        return self._compare(other) < 0
    def __le__(self, other):
        return self._compare(other) <= 0
    def __gt__(self, other):
        return self._compare(other) > 0
    def __ge__(self, other):
        return self._compare(other) >= 0
    def isoformat(self, timespec='auto'):
        return _clocktext(self, timespec)
    def __str__(self):
        return self.isoformat()
    def __repr__(self):
        return 'datetime.time(' + ', '.join(str(v) for v in (self.hour, self.minute, self.second, self.microsecond)) + (', tzinfo=' + repr(self.tzinfo) if self.tzinfo else '') + ')'
    @classmethod
    def fromisoformat(cls, text):
        h, m, s, us, zone = _parse_time(text)
        return cls(h, m, s, us, zone)
    def replace(self, hour=None, minute=None, second=None, microsecond=None, tzinfo=_KEEP, *, fold=None):
        return type(self)(self.hour if hour is None else hour, self.minute if minute is None else minute, self.second if second is None else second, self.microsecond if microsecond is None else microsecond, self.tzinfo if tzinfo is _KEEP else tzinfo, fold=self.fold if fold is None else fold)
    def strftime(self, format):
        return _strftime(self, format)
    def __format__(self, format):
        return self.strftime(format) if format else str(self)

class datetime(date):
    def __init__(self, year, month, day, hour=0, minute=0, second=0, microsecond=0, tzinfo=None, *, fold=0):
        date.__init__(self, year, month, day)
        self._hour, self._minute, self._second, self._microsecond, self._fold = _checktime(hour, minute, second, microsecond, tzinfo, fold)
        self._tzinfo = tzinfo
    hour, minute, second, microsecond, tzinfo, fold = time.hour, time.minute, time.second, time.microsecond, time.tzinfo, time.fold
    def utcoffset(self):
        return None if self.tzinfo is None else self.tzinfo.utcoffset(self)
    def dst(self):
        return None if self.tzinfo is None else self.tzinfo.dst(self)
    def tzname(self):
        return None if self.tzinfo is None else self.tzinfo.tzname(self)
    def _local(self):
        return (self.toordinal()-1)*_DAY + time._local(self)
    _compare = time._compare
    def __eq__(self, other):
        return isinstance(other, datetime) and self._compare(other, True) == 0
    __ne__, __lt__, __le__, __gt__, __ge__ = time.__ne__, time.__lt__, time.__le__, time.__gt__, time.__ge__
    @classmethod
    def _fromus(cls, us, zone=None, fold=0):
        ordinal, rem = divmod(us, _DAY)
        y, m, d = _ymd(ordinal+1)
        return cls(y, m, d, rem//3600000000, rem//60000000 % 60, rem//1000000 % 60, rem % 1000000, zone, fold=fold)
    @classmethod
    def fromtimestamp(cls, timestamp, tz=None):
        base = cls._fromus(round(timestamp*1000000) + 719162*_DAY, tz)
        return base if tz is None else tz.fromutc(base)
    @classmethod
    def utcfromtimestamp(cls, timestamp):
        return cls.fromtimestamp(timestamp)
    @classmethod
    def now(cls, tz=None):
        base = cls._fromus(_now() + 719162*_DAY, tz)
        return base if tz is None else tz.fromutc(base)
    @classmethod
    def today(cls):
        return cls.now()
    @classmethod
    def utcnow(cls):
        return cls.now()
    @classmethod
    def combine(cls, date, time, tzinfo=_KEEP):
        return cls(date.year, date.month, date.day, time.hour, time.minute, time.second, time.microsecond, time.tzinfo if tzinfo is _KEEP else tzinfo, fold=time.fold)
    @classmethod
    def fromisoformat(cls, text):
        # Calendar and ISO week dates, optionally followed by one separator and a time.
        end = 10 if len(text) >= 10 and text[4] == '-' else 8
        if 'W' in text[:8]:
            end = (10 if len(text) >= 10 and text[8] == '-' else 8) if text[4] == '-' else (8 if len(text) >= 8 and text[7].isdigit() else 7)
        d = date.fromisoformat(text[:end])
        return cls.combine(d, time.fromisoformat(text[end+1:])) if len(text) > end else cls(d.year, d.month, d.day)
    @classmethod
    def strptime(cls, text, format):
        return _strptime(cls, text, format)
    def date(self):
        return date(self.year, self.month, self.day)
    def time(self):
        return time(self.hour, self.minute, self.second, self.microsecond, fold=self.fold)
    def timetz(self):
        return time(self.hour, self.minute, self.second, self.microsecond, self.tzinfo, fold=self.fold)
    def timestamp(self):
        offset = self.utcoffset()
        return (self._local() - 719162*_DAY - (offset._us if offset else 0))/1000000
    def astimezone(self, tz=None):
        zone = UTC if tz is None else tz
        offset = self.utcoffset()
        return zone.fromutc(type(self)._fromus(self._local() - (offset._us if offset else 0), zone))
    def replace(self, year=None, month=None, day=None, hour=None, minute=None, second=None, microsecond=None, tzinfo=_KEEP, *, fold=None):
        values = [self.year if year is None else year, self.month if month is None else month, self.day if day is None else day, self.hour if hour is None else hour, self.minute if minute is None else minute, self.second if second is None else second, self.microsecond if microsecond is None else microsecond]
        return type(self)(*values, tzinfo=self.tzinfo if tzinfo is _KEEP else tzinfo, fold=self.fold if fold is None else fold)
    def __add__(self, delta):
        if not isinstance(delta, timedelta):
            raise TypeError('datetime addition requires timedelta')
        try:
            return type(self)._fromus(self._local()+delta._us, self.tzinfo)
        except ValueError:
            raise OverflowError('date value out of range')
    __radd__ = __add__
    def __sub__(self, other):
        if isinstance(other, timedelta):
            return self + -other
        if not isinstance(other, datetime):
            raise TypeError('datetime subtraction requires datetime or timedelta')
        return timedelta(microseconds=self._local()-other._local() if self.tzinfo is other.tzinfo else self._compare(other))
    def isoformat(self, sep='T', timespec='auto'):
        if not isinstance(sep, str) or len(sep) != 1:
            raise TypeError('sep must be a single character')
        return date.isoformat(self) + sep + _clocktext(self, timespec)
    def __str__(self):
        return self.isoformat(' ')
    def __repr__(self):
        return 'datetime.datetime(' + ', '.join(str(v) for v in (self.year, self.month, self.day, self.hour, self.minute, self.second, self.microsecond)) + (', tzinfo=' + repr(self.tzinfo) if self.tzinfo else '') + ')'
    def ctime(self):
        return self.strftime('%a %b ') + (' ' if self.day < 10 else '') + str(self.day) + self.strftime(' %H:%M:%S %Y')

def _parse_time(text):
    import re
    if not isinstance(text, str):
        raise TypeError('fromisoformat: argument must be str')
    match = re.fullmatch(r'T?(\d{2})(?:(:?)(\d{2})(?:\2(\d{2}))?)?(?:[.,](\d+))?(Z|[+-]\d{2}(?::?\d{2})?(?::?\d{2}(?:[.,]\d+)?)?)?', text)
    if match is None:
        raise ValueError('Invalid isoformat string: ' + repr(text))
    h, sep, m, s, fraction, offset = match.groups()
    zone = None
    if offset:
        if offset == 'Z':
            zone = UTC
        else:
            oh, om, os, ous, unused = _parse_time(offset[1:])
            delta = timedelta(hours=oh, minutes=om, seconds=os, microseconds=ous)
            zone = timezone(-delta if offset[0] == '-' else delta)
    h, m, s, us, fold = _checktime(int(h), int(m or '0'), int(s or '0'), int(((fraction or '') + '000000')[:6]), zone, 0)
    return h, m, s, us, zone

def _strftime(obj, fmt):
    y, m, d = getattr(obj, 'year', 1900), getattr(obj, 'month', 1), getattr(obj, 'day', 1)
    h, minute, s, us = getattr(obj, 'hour', 0), getattr(obj, 'minute', 0), getattr(obj, 'second', 0), getattr(obj, 'microsecond', 0)
    cal = date(y, m, d)
    week = cal.weekday()
    iso = cal.isocalendar()
    offset = obj.utcoffset() if hasattr(obj, 'utcoffset') else None
    name = obj.tzname() if hasattr(obj, 'tzname') else None
    values = {'Y': _pad(y,4), 'y': _pad(y%100), 'm': _pad(m), 'd': _pad(d), 'H': _pad(h), 'I': _pad((h-1)%12+1), 'M': _pad(minute), 'S': _pad(s), 'f': _pad(us,6), 'p': 'AM' if h < 12 else 'PM', 'a': _WEEKDAYS[week][:3], 'A': _WEEKDAYS[week], 'b': _MONTHS[m-1][:3], 'B': _MONTHS[m-1], 'w': str((week+1)%7), 'u': str(week+1), 'j': _pad(cal.toordinal()-_before(y),3), 'z': _offset(offset,False), 'Z': name or '', 'G': _pad(iso[0],4), 'V': _pad(iso[1]), '%': '%'}
    result, i = [], 0
    while i < len(fmt):
        if fmt[i] != '%':
            result.append(fmt[i])
        else:
            i += 1
            if i == len(fmt) or fmt[i] not in values:
                raise ValueError('unsupported strftime directive')
            result.append(values[fmt[i]])
        i += 1
    return ''.join(result)

def _strptime(cls, text, fmt):
    import re
    patterns = {'Y': r'\d{4}', 'y': r'\d{2}', 'm': r'\d{1,2}', 'd': r'\d{1,2}', 'H': r'\d{1,2}', 'I': r'\d{1,2}', 'M': r'\d{1,2}', 'S': r'\d{1,2}', 'f': r'\d{1,6}', 'p': r'AM|PM|am|pm', 'z': r'Z|[+-]\d{2}:?\d{2}(?::?\d{2}(?:\.\d{1,6})?)?', 'b': '|'.join(m[:3] for m in _MONTHS), 'B': '|'.join(_MONTHS)}
    parts, i = [], 0
    while i < len(fmt):
        if fmt[i] != '%':
            parts.append(re.escape(fmt[i]))
        else:
            i += 1
            if i == len(fmt):
                raise ValueError('stray % in format')
            code = fmt[i]
            if code == '%':
                parts.append('%')
            elif code in patterns:
                parts.append('(?P<' + code + '>' + patterns[code] + ')')
            else:
                raise ValueError('unsupported strptime directive: %' + code)
        i += 1
    match = re.fullmatch(''.join(parts), text, re.IGNORECASE)
    if match is None:
        raise ValueError('time data does not match format')
    fields = match.groupdict()
    y = int(fields.get('Y', '1900'))
    if 'y' in fields:
        y = int(fields['y'])
        y += 2000 if y <= 68 else 1900
    m = int(fields.get('m', '1'))
    for code in ('b', 'B'):
        if code in fields:
            names = [v[:3].lower() if code == 'b' else v.lower() for v in _MONTHS]
            m = names.index(fields[code].lower()) + 1
    h = int(fields.get('H', fields.get('I', '0')))
    if 'I' in fields:
        if not 1 <= h <= 12:
            raise ValueError('hour out of range')
        h = h % 12 + (12 if fields.get('p', 'AM').upper() == 'PM' else 0)
    zone = _parse_time('00' + fields['z'])[4] if 'z' in fields else None
    return cls(y, m, int(fields.get('d','1')), h, int(fields.get('M','0')), int(fields.get('S','0')), int((fields.get('f','')+'000000')[:6]), zone)

date.min, date.max, date.resolution = date(1,1,1), date(9999,12,31), timedelta(days=1)
time.min, time.max, time.resolution = time(), time(23,59,59,999999), timedelta(microseconds=1)
datetime.min, datetime.max, datetime.resolution = datetime(1,1,1), datetime(9999,12,31,23,59,59,999999), timedelta(microseconds=1)
timedelta.min, timedelta.max, timedelta.resolution = timedelta(days=-999999999), timedelta(days=999999999,seconds=86399,microseconds=999999), timedelta(microseconds=1)
