"""VM-local logging, with console/stream/file handlers and hierarchical loggers."""
import datetime as _dt
import time as _time
import traceback as _traceback

CRITICAL, FATAL, ERROR, WARNING, WARN, INFO, DEBUG, NOTSET = 50, 50, 40, 30, 30, 20, 10, 0
_names = {50: 'CRITICAL', 40: 'ERROR', 30: 'WARNING', 20: 'INFO', 10: 'DEBUG', 0: 'NOTSET'}
_levels = {'CRITICAL': 50, 'FATAL': 50, 'ERROR': 40, 'WARNING': 30, 'WARN': 30, 'INFO': 20, 'DEBUG': 10, 'NOTSET': 0}
_loggers, _handlers, _disabled = {}, [], 0
_start = _time.monotonic()
raiseExceptions = True
BASIC_FORMAT = '%(levelname)s:%(name)s:%(message)s'

def _level(value):
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value in _levels:
        return _levels[value]
    raise ValueError('Unknown level: ' + repr(value))

def getLevelName(level):
    return _names.get(level, _levels.get(level, 'Level ' + str(level)))

def addLevelName(level, levelName):
    _names[level], _levels[levelName] = levelName, level

def getLevelNamesMapping():
    return dict(_levels)

def _brace_format(template, values):
    parts, i = [], 0
    while i < len(template):
        c = template[i]
        if c in '{}' and i+1 < len(template) and template[i+1] == c:
            parts.append(c)
            i += 2
            continue
        if c == '}':
            raise ValueError('Single } encountered in format string')
        if c != '{':
            parts.append(c)
            i += 1
            continue
        end = template.find('}', i+1)
        if end < 0:
            raise ValueError('unmatched { in format string')
        field = template[i+1:end]
        fields = field.split(':', 1)
        name, sep, spec = fields[0], len(fields) == 2, fields[1] if len(fields) == 2 else ''
        conversion = None
        if '!' in name:
            name, conversion = name.split('!', 1)
        value = values[name]
        if conversion == 'r':
            value = repr(value)
        elif conversion == 's':
            value = str(value)
        elif conversion is not None:
            raise ValueError('unsupported conversion')
        parts.append(('{:' + spec + '}').format(value) if sep else str(value))
        i = end+1
    return ''.join(parts)

class LogRecord:
    def __init__(self, name, level, pathname, lineno, msg, args, exc_info, func=None, sinfo=None, **kwargs):
        self.name, self.msg, self.args = name, msg, args
        if len(args) == 1 and isinstance(args[0], dict) and args[0]:
            self.args = args[0]
        self.levelno, self.levelname = level, getLevelName(level)
        self.pathname, self.lineno, self.funcName = pathname, lineno, func
        self.filename = pathname.rsplit('/', 1)[-1]
        self.module = self.filename.rsplit('.', 1)[0]
        self.exc_info, self.exc_text, self.stack_info = exc_info, None, sinfo
        now = _dt.datetime.now(_dt.UTC)
        self.created = now.timestamp()
        self.msecs = now.microsecond // 1000
        self.relativeCreated = (_time.monotonic()-_start)*1000
        self.thread, self.threadName, self.process, self.processName = None, None, None, None
    def getMessage(self):
        message = str(self.msg)
        return message % self.args if self.args else message

def makeLogRecord(dict):
    record = LogRecord('', 0, '', 0, '', (), None)
    for key, value in dict.items():
        setattr(record, key, value)
    return record

class Formatter:
    def __init__(self, fmt=None, datefmt=None, style='%', validate=True, *, defaults=None):
        if style not in ('%', '{'):
            raise ValueError('supported formatter styles are % and {')
        self._fmt = fmt if fmt is not None else ('%(message)s' if style == '%' else '{message}')
        self.datefmt, self.style, self.defaults = datefmt, style, {} if defaults is None else dict(defaults)
    def formatTime(self, record, datefmt=None):
        dt = _dt.datetime.fromtimestamp(record.created, _dt.UTC)
        return dt.strftime(datefmt) if datefmt else dt.strftime('%Y-%m-%d %H:%M:%S') + ',%03d' % int(record.msecs)
    def formatException(self, exc_info):
        if isinstance(exc_info, tuple):
            exc_info = exc_info[1]
        return _traceback.format_exception(exc_info).rstrip('\n')
    def usesTime(self):
        return 'asctime' in self._fmt
    def format(self, record):
        record.message = record.getMessage()
        if self.usesTime():
            record.asctime = self.formatTime(record, self.datefmt)
        values = dict(self.defaults)
        values.update(record.__dict__)
        text = self._fmt % values if self.style == '%' else _brace_format(self._fmt, values)
        if record.exc_info and not record.exc_text:
            record.exc_text = self.formatException(record.exc_info)
        if record.exc_text:
            text += ('\n' if not text.endswith('\n') else '') + record.exc_text
        if record.stack_info:
            text += '\n' + record.stack_info
        return text

class Filter:
    def __init__(self, name=''):
        self.name = name
    def filter(self, record):
        return not self.name or record.name == self.name or record.name.startswith(self.name + '.')

class Filterer:
    def __init__(self):
        self.filters = []
    def addFilter(self, filter):
        if filter not in self.filters:
            self.filters.append(filter)
    def removeFilter(self, filter):
        if filter in self.filters:
            self.filters.remove(filter)
    def filter(self, record):
        for f in self.filters:
            if not (f.filter(record) if hasattr(f, 'filter') else f(record)):
                return False
        return True

class Handler(Filterer):
    def __init__(self, level=NOTSET):
        Filterer.__init__(self)
        self.level, self.formatter, self._closed = _level(level), None, False
        self.name = None
        self.createLock()
        _handlers.append(self)
    def createLock(self):
        import threading
        self.lock = threading.RLock()
    def acquire(self):
        self.lock.acquire()
    def release(self):
        self.lock.release()
    def setLevel(self, level):
        self.level = _level(level)
    def setFormatter(self, fmt):
        self.formatter = fmt
    def format(self, record):
        return (self.formatter or _defaultFormatter).format(record)
    def handle(self, record):
        if self._closed or not self.filter(record):
            return False
        with self.lock:
            self.emit(record)
        return True
    def emit(self, record):
        raise NotImplementedError('emit must be implemented by Handler subclasses')
    def flush(self):
        pass
    def close(self):
        self._closed = True
        if self in _handlers:
            _handlers.remove(self)
    def handleError(self, record):
        if raiseExceptions:
            print('--- Logging error ---')
            _traceback.print_exc()

class _Console:
    def write(self, text):
        import _process
        try:
            runtime = _process.runtime()
        except OSError:
            print(text, end='')
            return
        pid = runtime.pid()
        job = runtime.active.get(pid)
        tty = _process._JobTty(runtime, job) if job is not None and job['background'] else runtime.tty
        tty.write(text)
    def flush(self):
        pass

class StreamHandler(Handler):
    terminator = '\n'
    def __init__(self, stream=None):
        Handler.__init__(self)
        self.stream = _Console() if stream is None else stream
    def emit(self, record):
        try:
            self.stream.write(self.format(record) + self.terminator)
            self.flush()
        except Exception:
            self.handleError(record)
    def flush(self):
        if self.stream is not None and hasattr(self.stream, 'flush'):
            self.stream.flush()
    def setStream(self, stream):
        if stream is self.stream:
            return None
        self.flush()
        old, self.stream = self.stream, stream
        return old

class FileHandler(StreamHandler):
    def __init__(self, filename, mode='a', encoding=None, delay=False, errors=None):
        import os
        self.baseFilename, self.mode = os.path.abspath(os.fspath(filename)), mode
        self.encoding, self.errors, self.delay = encoding, errors, delay
        Handler.__init__(self)
        self.stream = None if delay else self._open()
    def _open(self):
        return open(self.baseFilename, self.mode, encoding=self.encoding, errors=self.errors)
    def emit(self, record):
        if self.stream is None and not self._closed:
            self.stream = self._open()
        if self.stream is not None:
            StreamHandler.emit(self, record)
    def close(self):
        try:
            if self.stream is not None:
                self.stream.close()
                self.stream = None
        finally:
            Handler.close(self)

class NullHandler(Handler):
    def emit(self, record):
        pass

class Logger(Filterer):
    def __init__(self, name, level=NOTSET):
        Filterer.__init__(self)
        self.name, self.level = name, _level(level)
        self.handlers, self.disabled, self.propagate = [], False, True
    @property
    def parent(self):
        if self is root:
            return None
        name = self.name
        while '.' in name:
            name = name.rsplit('.', 1)[0]
            if name in _loggers:
                return _loggers[name]
        return root
    def getChild(self, suffix):
        return getLogger(suffix if self is root else self.name + '.' + suffix)
    def setLevel(self, level):
        self.level = _level(level)
    def getEffectiveLevel(self):
        logger = self
        while logger is not None:
            if logger.level:
                return logger.level
            logger = logger.parent
        return NOTSET
    def isEnabledFor(self, level):
        return not self.disabled and level > _disabled and level >= self.getEffectiveLevel()
    def addHandler(self, handler):
        if handler not in self.handlers:
            self.handlers.append(handler)
    def removeHandler(self, handler):
        if handler in self.handlers:
            self.handlers.remove(handler)
    def hasHandlers(self):
        logger = self
        while logger is not None:
            if logger.handlers:
                return True
            logger = logger.parent if logger.propagate else None
        return False
    def makeRecord(self, name, level, fn, lno, msg, args, exc_info, func=None, extra=None, sinfo=None):
        record = LogRecord(name, level, fn, lno, msg, args, exc_info, func, sinfo)
        if extra:
            for key, value in extra.items():
                if key in record.__dict__ or key in ('message', 'asctime'):
                    raise KeyError('Attempt to overwrite ' + key + ' in LogRecord')
                setattr(record, key, value)
        return record
    def handle(self, record):
        if not self.disabled and self.filter(record):
            self.callHandlers(record)
    def callHandlers(self, record):
        logger, found = self, False
        while logger is not None:
            for handler in list(logger.handlers):
                found = True
                if record.levelno >= handler.level:
                    handler.handle(record)
            logger = logger.parent if logger.propagate else None
        if not found and lastResort is not None and record.levelno >= lastResort.level:
            lastResort.handle(record)
    def log(self, level, msg, *args, **kwargs):
        if self.isEnabledFor(level):
            self._log(level, msg, args, **kwargs)
    def _log(self, level, msg, args, exc_info=None, extra=None, stack_info=False, stacklevel=1):
        if stack_info or stacklevel != 1:
            raise NotImplementedError('caller stack inspection is not supported')
        record = self.makeRecord(self.name, level, '', 0, msg, args, None, extra=extra)
        if exc_info:
            if exc_info is True:
                record.exc_text = _traceback.format_exc().rstrip('\n')
            else:
                record.exc_info = exc_info
        self.handle(record)
    def debug(self, msg, *args, **kwargs):
        self.log(DEBUG, msg, *args, **kwargs)
    def info(self, msg, *args, **kwargs):
        self.log(INFO, msg, *args, **kwargs)
    def warning(self, msg, *args, **kwargs):
        self.log(WARNING, msg, *args, **kwargs)
    warn = warning
    def error(self, msg, *args, **kwargs):
        self.log(ERROR, msg, *args, **kwargs)
    def critical(self, msg, *args, **kwargs):
        self.log(CRITICAL, msg, *args, **kwargs)
    fatal = critical
    def exception(self, msg, *args, exc_info=True, **kwargs):
        self.log(ERROR, msg, *args, exc_info=exc_info, **kwargs)

class LoggerAdapter:
    def __init__(self, logger, extra=None):
        self.logger, self.extra = logger, extra
    def process(self, msg, kwargs):
        kwargs['extra'] = self.extra
        return msg, kwargs
    def isEnabledFor(self, level):
        return self.logger.isEnabledFor(level)
    def log(self, level, msg, *args, **kwargs):
        if self.isEnabledFor(level):
            msg, kwargs = self.process(msg, kwargs)
            self.logger.log(level, msg, *args, **kwargs)
    debug, info, warning, warn, error, critical, fatal, exception = Logger.debug, Logger.info, Logger.warning, Logger.warn, Logger.error, Logger.critical, Logger.fatal, Logger.exception
    def setLevel(self, level):
        self.logger.setLevel(level)
    def getEffectiveLevel(self):
        return self.logger.getEffectiveLevel()
    def hasHandlers(self):
        return self.logger.hasHandlers()

_defaultFormatter = Formatter()
root = Logger('root', WARNING)
lastResort = StreamHandler()
lastResort.setLevel(WARNING)

def getLogger(name=None):
    if not name or name == 'root':
        return root
    if not isinstance(name, str):
        raise TypeError('logger name must be a string')
    if name not in _loggers:
        _loggers[name] = Logger(name)
    return _loggers[name]

def disable(level=CRITICAL):
    global _disabled
    _disabled = _level(level)

def basicConfig(**kwargs):
    force = kwargs.pop('force', False)
    if force:
        for handler in list(root.handlers):
            root.removeHandler(handler)
            handler.close()
    if root.handlers:
        return
    filename, stream, handlers = kwargs.pop('filename', None), kwargs.pop('stream', None), kwargs.pop('handlers', None)
    if filename is not None and stream is not None or handlers is not None and (filename is not None or stream is not None):
        raise ValueError('filename, stream and handlers are mutually exclusive')
    fmt = Formatter(kwargs.pop('format', None) or BASIC_FORMAT, kwargs.pop('datefmt', None), kwargs.pop('style', '%'))
    mode, encoding, errors = kwargs.pop('filemode', 'a'), kwargs.pop('encoding', None), kwargs.pop('errors', None)
    level = kwargs.pop('level', None)
    if kwargs:
        raise ValueError('Unrecognised argument(s): ' + ', '.join(kwargs))
    if handlers is None:
        handlers = [FileHandler(filename, mode, encoding, errors=errors) if filename is not None else StreamHandler(stream)]
    for handler in handlers:
        if handler.formatter is None:
            handler.setFormatter(fmt)
        root.addHandler(handler)
    if level is not None:
        root.setLevel(level)

def log(level, msg, *args, **kwargs):
    if not root.handlers:
        basicConfig()
    root.log(level, msg, *args, **kwargs)

def debug(msg, *args, **kwargs):
    log(DEBUG, msg, *args, **kwargs)
def info(msg, *args, **kwargs):
    log(INFO, msg, *args, **kwargs)
def warning(msg, *args, **kwargs):
    log(WARNING, msg, *args, **kwargs)
warn = warning
def error(msg, *args, **kwargs):
    log(ERROR, msg, *args, **kwargs)
def critical(msg, *args, **kwargs):
    log(CRITICAL, msg, *args, **kwargs)
fatal = critical
def exception(msg, *args, exc_info=True, **kwargs):
    log(ERROR, msg, *args, exc_info=exc_info, **kwargs)

def shutdown(handlerList=None):
    for handler in list(_handlers if handlerList is None else handlerList)[::-1]:
        try:
            handler.flush()
        finally:
            handler.close()
