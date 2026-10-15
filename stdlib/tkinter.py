"""Small Tk-compatible API for networked MPY4OC display blocks.

Tk() owns one physical display, which is its desktop; Toplevel() opens a draggable
window on that desktop (Toplevel(screen=...) on another display opens a new root there).
Layout and editing run on the server; callbacks, variables and timers live in Python
and survive VM snapshots.
"""

TOP = 'top'
BOTTOM = 'bottom'
LEFT = 'left'
RIGHT = 'right'
X = 'x'
Y = 'y'
BOTH = 'both'
NONE = 'none'
N = 'n'
S = 's'
E = 'e'
W = 'w'
NE = 'ne'
NW = 'nw'
SE = 'se'
SW = 'sw'
CENTER = 'center'
NORMAL = 'normal'
DISABLED = 'disabled'
END = 'end'
INSERT = 'insert'
HORIZONTAL = 'horizontal'
VERTICAL = 'vertical'
SINGLE = 'single'
BROWSE = 'browse'
MULTIPLE = 'multiple'
EXTENDED = 'extended'
ACTIVE = 'active'
ALL = 'all'
CURRENT = 'current'
HIDDEN = 'hidden'
CHAR = 'char'
WORD = 'word'
SEL = 'sel'
ANCHOR = 'anchor'
MOVETO = 'moveto'
SCROLL = 'scroll'
UNITS = 'units'
PAGES = 'pages'
FLAT = 'flat'
RAISED = 'raised'
SUNKEN = 'sunken'
GROOVE = 'groove'
RIDGE = 'ridge'
SOLID = 'solid'
# The rest of CPython's tkinter.constants.
NO = FALSE = OFF = 0
YES = TRUE = ON = 1
NS = 'ns'
EW = 'ew'
NSEW = 'nsew'
NUMERIC = 'numeric'
BASELINE = 'baseline'
INSIDE = 'inside'
OUTSIDE = 'outside'
SEL_FIRST = 'sel.first'
SEL_LAST = 'sel.last'
CASCADE = 'cascade'
CHECKBUTTON = 'checkbutton'
COMMAND = 'command'
RADIOBUTTON = 'radiobutton'
SEPARATOR = 'separator'
DOTBOX = 'dotbox'
UNDERLINE = 'underline'
PIESLICE = 'pieslice'
CHORD = 'chord'
ARC = 'arc'
FIRST = 'first'
LAST = 'last'
BUTT = 'butt'
PROJECTING = 'projecting'
ROUND = 'round'
BEVEL = 'bevel'
MITER = 'miter'
TclError = ValueError

try:
    import _program
except ImportError:   # this file copied onto a disk for a VM whose stdlib has no _program
    _program = None

# This computer's Tk roots (one per program window set, several may share a display), by token.
_roots = {}
# Tk signals taken from the queue for another program's windows, by token, until that
# program's own loop runs them (see _update).
_pending = {}
_PENDING_LIMIT = 256
# The threads parked in _idle (mainloop, wait_*), by the program whose windows they serve:
# an event filed for one of its windows, a timer queued for one, or quit() wakes them.
_parked = {}
_next_timer = 0
_default_root = None
_next_variable = 0
_aliases = {'bg': 'background', 'fg': 'foreground', 'bd': 'borderwidth', 'from_': 'from',
            'vcmd': 'validatecommand', 'invcmd': 'invalidcommand'}
_colors = {'black': 0, 'white': 0xffffff, 'red': 0xff0000,
           'green': 0x008000, 'blue': 0x0000ff, 'yellow': 0xffff00,
           'gray': 0x808080, 'grey': 0x808080}
_sequences = ('<Button-1>', '<ButtonRelease-1>', '<Button-3>', '<ButtonRelease-3>',
              '<Motion>', '<B1-Motion>', '<MouseWheel>', '<Key>', '<KeyRelease>',
              '<Return>', '<space>', '<Tab>', '<BackSpace>', '<Delete>', '<Left>',
              '<Right>', '<Home>', '<End>', '<<Paste>>', '<<Copy>>', '<<Cut>>', '<<ListboxSelect>>', '<Configure>',
              '<Up>', '<Down>', '<Prior>', '<Next>', '<Double-Button-1>')
_event_aliases = {'<KeyPress>': '<Key>', '<ButtonPress-1>': '<Button-1>', '<ButtonPress-3>': '<Button-3>',
                  '<Double-1>': '<Double-Button-1>', '<Double-ButtonPress-1>': '<Double-Button-1>',
                  '<Page_Up>': '<Prior>', '<Page_Down>': '<Next>'}
for _key in ('Up', 'Down', 'Prior', 'Next', 'Left', 'Right', 'Home', 'End', 'Return', 'Tab', 'BackSpace', 'Delete', 'space'):
    _event_aliases['<Key-' + _key + '>'] = _event_aliases['<KeyPress-' + _key + '>'] = '<' + _key + '>'
_keysyms = {28: 'Return', 156: 'Return', 57: 'space', 15: 'Tab', 14: 'BackSpace', 211: 'Delete', 203: 'Left',
            205: 'Right', 199: 'Home', 207: 'End', 200: 'Up', 208: 'Down', 201: 'Prior', 209: 'Next',
            210: 'Insert', 87: 'F11', 88: 'F12'}
for _key in range(10):
    _keysyms[59 + _key] = 'F' + str(_key + 1)
# Letter keys (LWJGL codes): with Ctrl the character is a control code, but Tk's keysym is the letter.
_letter_keys = {}
for _key, _letters in ((16, 'qwertyuiop'), (30, 'asdfghjkl'), (44, 'zxcvbnm')):
    for _offset in range(len(_letters)):
        _letter_keys[_key + _offset] = _letters[_offset]
_key_sequences = ('<Return>', '<space>', '<Tab>', '<BackSpace>', '<Delete>', '<Left>', '<Right>', '<Home>',
                  '<End>', '<Up>', '<Down>', '<Prior>', '<Next>')
_key_chars = {'Return': '\r', 'space': ' ', 'Tab': '\t', 'BackSpace': '\b', 'Delete': '\x7f'}
# event_generate options of Tk's that mean nothing here: accepted and ignored.
_ignored_event_fields = ('rootx', 'rooty', 'time', 'serial', 'count', 'override', 'sendevent', 'root',
                         'subwindow', 'detail', 'focus', 'mode', 'place', 'warp', 'above', 'borderwidth')
_next_trace = 0
_trace_names = set()
_TRACE_ORDER = ('array', 'read', 'write', 'unset')
_next_command = 0
# wm attributes Tk has on other platforms: accepted and ignored, so ported programs run.
_ignored_attributes = ('-alpha', '-topmost', '-fullscreen', '-toolwindow', '-disabled', '-transparentcolor',
                       '-type', '-transparent', '-modified', '-notify', '-titlepath', '-zoomed')


def _flag(value):
    # A Tk boolean: True/False, a number, or '1'/'true'/'yes'/'on' and '0'/'false'/'no'/'off'.
    if isinstance(value, str):
        text = value.lower()
        if text in ('1', 'true', 'yes', 'on'):
            return True
        if text in ('0', 'false', 'no', 'off'):
            return False
        raise ValueError('expected boolean value but got "' + value + '"')
    return bool(value)


def _choice(value, choices, what):
    # Tcl_GetIndexFromObj: one of choices, or a start of exactly one ('c' for 'center'); Tk's message otherwise.
    value = str(value)
    if value in choices:
        return value
    matches = [c for c in choices if c.startswith(value)] if value else list(choices)
    if len(matches) == 1:
        return matches[0]
    raise TclError(('ambiguous ' if len(matches) > 1 else 'bad ') + what + ' "' + value + '": must be '
                   + ', '.join(choices[:-1]) + (',' if len(choices) > 2 else '') + ' or ' + choices[-1])


_BOOLEAN_WORDS = (('true', True), ('false', False), ('yes', True), ('no', False), ('on', True), ('off', False))


def _tcl_boolean(value):
    # Tcl_GetBoolean: a number (not 0 is true), or true/false, yes/no, on/off in any case and any
    # unambiguous start of them; what a Python function returns to Tcl is its str().
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value)
    number = _tcl_number(text)
    if number is not None:
        return number != 0
    low = text.lower()
    matches = [result for word, result in _BOOLEAN_WORDS if low and word.startswith(low)]
    if len(matches) == 1:
        return matches[0]
    raise TclError('expected boolean value but got "' + text + '"')


def _tcl_number(text):
    # A Tcl number written as text: an int (0x/0o/0b too) or a float; None if it is not one.
    text = str(text)
    body = text[1:] if text[:1] in '+-' else text
    if not body or body != body.strip():
        return None
    try:
        if body[:2].lower() in ('0x', '0o', '0b'):
            return int(text, 0)
        if all('0' <= c <= '9' for c in body):
            return int(text)
        if any(c in 'nN' for c in body):     # not inf, nan, or anything float() reads that Tcl does not
            return None
        return float(text)
    except ValueError:
        return None


def _tcl_split(text):
    # The elements of a Tcl list: words between blanks, {braced} (nested braces kept) or "quoted",
    # with backslash escapes outside braces.
    items, i, n = [], 0, len(text)
    escapes = {'n': '\n', 't': '\t', 'r': '\r', 'v': '\v', 'f': '\f', 'a': '\a', 'b': '\b'}
    while True:
        while i < n and text[i] in ' \t\n\r\v\f':
            i += 1
        if i >= n:
            return items
        if text[i] == '{':
            depth, j = 1, i + 1
            while j < n and depth:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '{':
                    depth += 1
                elif text[j] == '}':
                    depth -= 1
                j += 1
            if depth:
                raise TclError('unmatched open brace in list')
            if j < n and text[j] not in ' \t\n\r\v\f':
                raise TclError('list element in braces followed by "' + text[j:].split()[0] + '" instead of space')
            items.append(text[i + 1:j - 1])
            i = j
            continue
        quoted = text[i] == '"'
        if quoted:
            i += 1
        word = []
        while i < n and (text[i] != '"' if quoted else text[i] not in ' \t\n\r\v\f'):
            if text[i] == '\\' and i + 1 < n:
                word.append(escapes.get(text[i + 1], text[i + 1]))
                i += 2
            else:
                word.append(text[i])
                i += 1
        if quoted:
            if i >= n:
                raise TclError('unmatched open quote in list')
            i += 1
            if i < n and text[i] not in ' \t\n\r\v\f':
                raise TclError('list element in quotes followed by "' + text[i:].split()[0] + '" instead of space')
        items.append(''.join(word))


def _tcl_join(items):
    # A Tcl list as Tcl writes it: braces around an element with blanks or special characters
    # (backslashes where braces will not do), {} for an empty one.
    out = []
    for item in items:
        s = str(item)
        if s == '':
            out.append('{}')
            continue
        if not any(c in ' \t\n\r\v\f{}[]$";\\' for c in s) and not (not out and s[0] == '#'):
            out.append(s)
            continue
        depth = 0
        for c in s:
            depth += 1 if c == '{' else -1 if c == '}' else 0
            if depth < 0:
                break
        if depth == 0 and not s.endswith('\\'):
            out.append('{' + s + '}')
        else:
            out.append(''.join('\\' + c if c in ' {}[]$";\\' else '\\n' if c == '\n' else '\\t' if c == '\t' else c for c in s))
    return ' '.join(out)


def _percent(arg, subs):
    # Tk's %-substitution in one word of a -validatecommand: %d %i %P %s %S %v %V %W; %% and
    # any other %x give the character.
    if '%' not in arg:
        return arg
    out, i = [], 0
    while i < len(arg):
        if arg[i] == '%' and i + 1 < len(arg):
            out.append(subs.get(arg[i + 1], arg[i + 1]))
            i += 2
        else:
            out.append(arg[i])
            i += 1
    return ''.join(out)


def _attributes(args, names, read, write):
    # wm attributes: () lists them, (name,) reads one, (name, value, ...) sets them.
    # names are the supported ones; read(name) and write(name, value) handle those.
    if not args:
        result = []
        for name in names:
            result += [name, read(name)]
        return tuple(result)
    for name in args[::2]:
        if name not in names and name not in _ignored_attributes:
            raise ValueError('bad attribute "' + str(name) + '": must be ' + ', '.join(names))
    if len(args) == 1:
        name = args[0]
        if name in names:
            return read(name)
        return 1.0 if name == '-alpha' else 0
    if len(args) % 2:
        raise ValueError('wm attributes: missing value for ' + str(args[-1]))
    for i in range(0, len(args), 2):
        if args[i] in names:
            write(args[i], args[i + 1])
    return None


def _color(value):
    if isinstance(value, str):
        value = value.lower()
        if value in _colors:
            return _colors[value]
        if value.startswith('#'):
            digits = value[1:]
            if len(digits) == 3:
                digits = ''.join([c + c for c in digits])
            if len(digits) == 6:
                return int(digits, 16)
        else:
            # Tk's other names ('SteelBlue', 'grey50', ...), loaded the first time one is asked for.
            from tkinter import _tkcolors
            if value in _tkcolors.COLORS:
                return _tkcolors.COLORS[value]
        raise ValueError('unsupported color: ' + value)
    return value


def _options(cnf, kw):
    result = {}
    if cnf is not None:
        result.update(cnf)
    result.update(kw)
    for key in list(result):
        if key in _aliases:
            result[_aliases[key]] = result.pop(key)
    for key in ('background', 'foreground'):
        if key in result:
            result[key] = _color(result[key])
    return result


def _digits(text):
    return text != '' and all('0' <= c <= '9' for c in text)


def _parse_geometry(value):
    # WIDTHxHEIGHT, optionally followed by +X+Y; a '-' measures from the right/bottom edge
    # and an offset may itself be negative ("+-5"), as in Tk.
    value = str(value).strip()
    if value == '':
        return {'width': 0, 'height': 0}  # back to the natural size
    split = len(value)
    for i in range(len(value)):
        if value[i] in '+-':
            split = i
            break
    size, rest, result = value[:split], value[split:], {}
    if size:
        parts = [part.strip() for part in size.split('x')]
        if len(parts) != 2 or not _digits(parts[0]) or not _digits(parts[1]):
            raise ValueError('bad geometry specifier: ' + value)
        result['width'], result['height'] = int(parts[0]), int(parts[1])
    offsets, i = [], 0
    while i < len(rest):
        sign = rest[i]
        if sign not in '+-':
            raise ValueError('bad geometry specifier: ' + value)
        j = i + 1
        if j < len(rest) and rest[j] == '-':
            j += 1
        k = j
        while k < len(rest) and '0' <= rest[k] <= '9':
            k += 1
        if k == j:
            raise ValueError('bad geometry specifier: ' + value)
        offsets.append((sign == '-', int(rest[i + 1:k])))
        i = k
    if len(offsets) not in (0, 2) or not result and not offsets:
        raise ValueError('bad geometry specifier: ' + value)
    if offsets:
        result['right'], result['x'] = offsets[0]
        result['bottom'], result['y'] = offsets[1]
    return result


def _offset(n):
    return '+' + str(n)


_MAX_IMAGE_FILE = 1048576


_image_names = [0]


def _locate_file(path):
    # (filesystem address, path on it) of a file on the computer's disks. Under MPYOS: its
    # filesystem (mount points, the shell's working directory). Bare metal: the boot disk,
    # else any filesystem that has the file.
    path = str(path)
    try:
        import osfs
        vfs = osfs.shared_vfs()
    except Exception:
        vfs = None
    if vfs is not None:
        absolute = osfs._abs(path)
        if not vfs.exists(absolute) or vfs.is_directory(absolute):
            raise OSError('no such file: ' + absolute)
        disk, sub = vfs._route(absolute)
        return disk.address, sub
    if not path.startswith('/'):
        path = '/' + path
    candidates = []
    try:
        boot = computer.getBootAddress()
    except Exception:
        boot = None
    if boot:
        candidates.append(boot)
    for address in component.list('filesystem'):
        if address not in candidates:
            candidates.append(address)
    for address in candidates:
        try:
            if component.invoke(address, 'exists', path) and not component.invoke(address, 'isDirectory', path):
                return address, path
        except Exception:
            pass
    raise OSError('no such file: ' + path)


def _upload_file(root, path):
    # Sends an image file to the display in pieces and returns its size. The program never
    # holds the whole file: one can be larger than this computer's memory.
    address, path = _locate_file(path)
    if (component.invoke(address, 'size', path) or 0) > _MAX_IMAGE_FILE:
        raise ValueError('image file larger than 1 MiB')
    handle = component.invoke(address, 'open', path, 'rb')
    sent = 0
    pending = []
    size = 0
    try:
        while True:
            chunk = component.invoke(address, 'read', handle, 16384)
            if not chunk:
                chunk = None
            else:
                if isinstance(chunk, str):
                    chunk = chunk.encode('utf-8')
                pending.append(chunk)
                size += len(chunk)
            if pending and (chunk is None or size >= 32768):
                piece = b''.join(pending)
                pending = []
                size = 0
                root._call('image', 0, 'upload', {'data': piece, 'offset': sent})
                sent += len(piece)
            if chunk is None:
                break
    finally:
        component.invoke(address, 'close', handle)
    if not sent:
        raise ValueError('image file is empty')
    return sent


def _image_rows(data):
    # Tk's put data: one color, a row "#f00 #0f0", rows "{#f00 #0f0} {#00f #fff}",
    # or a list/tuple of rows (each a list of colors or a row string).
    if isinstance(data, str):
        text = data.strip()
        if text.startswith('{'):
            rows = []
            for part in text.split('}'):
                part = part.strip()
                if part.startswith('{'):
                    part = part[1:]
                if part.strip():
                    rows.append(part.split())
        else:
            rows = [text.split()]
    else:
        rows = []
        for row in data:
            rows.append(row.split() if isinstance(row, str) else list(row))
    if not rows or not rows[0]:
        raise ValueError('put needs at least one color')
    return [[_color(c) for c in row] for row in rows]


def _owner():
    # Which program a window belongs to (see _program): None for the main program, else the
    # MPYOS command or background job that opened it -- its threads and coroutines too.
    # Each program finds its own default window, runs only its own windows' events and
    # timers, and the shell closes exactly a finished program's windows.
    return _program.current() if _program is not None else None


def _here():
    try:
        return __thread_current()
    except NameError:
        return None


def _wake(owner):
    # Wake owner's threads parked in _idle, except the caller (it re-checks on its own).
    waiting = _parked.get(owner)
    if waiting:
        me = _here()
        for thread in list(waiting):
            if thread is not me:
                _program.unpark(thread)


def _has_events(owner):
    # Something for owner's loop to run now: an event filed for one of its windows, or a
    # Tk signal still in the computer's queue (for whichever window).
    for token, root in list(_roots.items()):
        if getattr(root, '_owner', None) == owner and _pending.get(token):
            return True
    for signal in list(computer._signals):
        if len(signal) > 2 and signal[0] in ('tk_command', 'tk_event', 'tk_changed', 'tk_close'):
            return True
    return False


def _idle(owner, cap_ms=None):
    # Nothing to run until the next of owner's timers, an event (Tk events are signals,
    # queued with the computer's others: a queued signal wakes us) or a wake: park there,
    # where Tk's event loop would block in select. Registered before the last look at the
    # queues, so a wake between that look and the park is not lost (it leaves a permit).
    if _program is None:    # a copied file on a VM without _program: poll a tick
        computer._yield_tick()
        return
    now = computer.uptime()
    soonest = None
    for root in list(_roots.values()):
        if getattr(root, '_owner', None) != owner:
            continue
        for timer in list(root._timers.values()):
            if soonest is None or timer[0] < soonest:
                soonest = timer[0]
    deadline = None
    if soonest is not None:
        left = int((soonest - now) * 1000) + 1
        if left <= 0:
            return
        deadline = __time_ms() + left
    if cap_ms is not None:
        cap = __time_ms() + cap_ms
        if deadline is None or deadline > cap:
            deadline = cap
    me = _here()
    waiting = _parked.setdefault(owner, [])
    waiting.append(me)
    try:
        if not _has_events(owner):
            _program.park(deadline, True)
    finally:
        waiting.remove(me)
        if not waiting and _parked.get(owner) is waiting:
            _parked.pop(owner, None)


def _child_name(widget, master, name):
    # Tk's path names, numbered as CPython does: '.!frame', '.!frame.!label2', or the name= given.
    if not name:
        base = type(widget).__name__.lower()
        if base[-1:].isdigit():
            base += '!'
        count = master._last_child_ids.get(base, 0) + 1
        master._last_child_ids[base] = count
        name = '!' + base if count == 1 else '!' + base + str(count)
    name = str(name)
    widget._name = name
    widget._w = '.' + name if master._w == '.' else master._w + '.' + name
    widget.children = {}
    widget._last_child_ids = {}
    old = master.children.get(name)
    if old is not None and old is not widget:
        old.destroy()       # as in tkinter: a new widget of the same name replaces the old one
    master.children[name] = widget


def _own_default():
    # This program's default window: _default_root if it is ours, else the first live one we opened.
    owner = _owner()
    if _default_root is not None and _default_root._alive and getattr(_default_root, '_owner', None) == owner:
        return _default_root
    for root in _roots.values():
        if root._alive and root._token is not None and getattr(root, '_owner', None) == owner:
            return root
    return None


def _forget(root):
    # Drop root from _roots (it let its display go, or lost it).
    if root._token is not None and _roots.get(root._token) is root:
        _roots.pop(root._token)
        _pending.pop(root._token, None)
        _wake(getattr(root, '_owner', None))    # a mainloop on it ends


def _default():
    root = _own_default()
    return Tk() if root is None else root


def _get_default_root(what=None):
    # CPython's: the default root, or an error saying what needed it.
    root = _own_default()
    if root is None:
        raise RuntimeError('Too early to ' + what + ': no default root window' if what else 'No master specified and tkinter is configured to not support default root')
    return root


def _get_temp_root():
    # A root for a dialog that has no parent: the default one, else a new hidden one (destroyed after).
    root = _own_default()
    if root is None:
        root = Tk()
        root._temporary = True
        root.withdraw()
    return root


def _destroy_temp_root(master):
    if getattr(master, '_temporary', False):
        try:
            master.destroy()
        except Exception:
            pass


def _live_changed():
    # pullSignal leaves Tk events queued for us while we have windows (see MpyArchitecture.pollSignal).
    try:
        computer._tk_live = bool(_roots)
    except Exception:
        pass


class Event:
    pass


def _virtual(sequence):
    return sequence.startswith('<<') and sequence.endswith('>>') and len(sequence) > 4


class _Handlers:
    # The functions bound to one sequence, as Tk's binding script: run in order until one
    # returns 'break'. A failing one is reported and the rest still run.
    def __init__(self):
        self.entries = []
        self.before = None   # run first (a Text's tag bindings)

    def add(self, root, func, add):
        funcid = root._add_command(func)
        if not add:  # tkinter appends for any true add, not only '+'
            self.entries = []
        self.entries.append((funcid, func))
        return funcid

    def remove(self, funcid):
        self.entries = [entry for entry in self.entries if entry[0] != str(funcid)]

    def __call__(self, event):
        if self.before is not None and self.before(event) == 'break':
            return 'break'
        for entry in list(self.entries):
            try:
                result = entry[1](event)
            except Exception as error:
                print('Tk callback error:', error)
                continue
            if result == 'break':
                return 'break'


class Variable:
    _default_value = ''

    def __init__(self, master=None, value=None, name=None):
        global _next_variable
        if name is not None:
            raise ValueError('named variables are not supported')
        _next_variable += 1
        self._key = _next_variable
        self._name = 'PY_VAR' + str(_next_variable - 1)
        self._value = self._default_value if value is None else value
        self._widgets = []
        self._value_widgets = []
        self._menus = []          # menus with check or radio entries on this variable
        self._traces = []
        self._tracing = False

    def __str__(self):
        return self._name

    def _coerce(self, value):
        return value

    # Traces, as Tcl's: callback(name, index, mode) after each write (or before each read).
    # The newest trace runs first; while a variable's traces run they do not fire again (Tcl
    # disables them too). One list holds both forms: entry[3] marks a trace_variable trace,
    # whose callback gets the mode letter.
    def trace_add(self, mode, callback):
        return self._trace(_trace_modes(mode), callback, False)

    def _trace(self, modes, callback, legacy):
        if not callable(callback):
            raise TypeError('trace callback must be callable')
        global _next_trace
        _next_trace += 1
        name = str(_next_trace) + getattr(callback, '__name__', 'trace')
        _trace_names.add(name)
        self._traces.append([tuple(m for m in _TRACE_ORDER if m in modes), callback, name, legacy])
        return name

    def _untrace(self, modes, cbname):
        # Tcl removes only a trace whose operations match exactly; tkinter then deletes the
        # callback once no trace uses it (an unknown name: "can't delete Tcl command").
        for entry in self._traces:
            if entry[2] == cbname and set(entry[0]) == set(modes):
                self._traces.remove(entry)
                break
        if not any(entry[2] == cbname for entry in self._traces):
            if cbname not in _trace_names:
                raise TclError("can't delete Tcl command")
            _trace_names.discard(cbname)

    def trace_remove(self, mode, cbname):
        self._untrace(_trace_modes(mode), cbname)

    def trace_info(self):
        return [(entry[0], entry[2]) for entry in reversed(self._traces)]

    # The deprecated forms: mode letters 'r', 'w', 'u', 'a'; callbacks get the letter.
    def trace_variable(self, mode, callback):
        return self._trace(_trace_letters(mode), callback, True)

    trace = trace_variable

    def trace_vdelete(self, mode, cbname):
        self._untrace(_trace_letters(mode), cbname)

    def trace_vinfo(self):
        return [(''.join(m[0] for m in ('read', 'write', 'unset', 'array') if m in entry[0]), entry[2])
                for entry in reversed(self._traces)]

    def _fire(self, mode):
        if not self._traces or self._tracing:
            return
        self._tracing = True
        try:
            for entry in reversed(list(self._traces)):
                # A trace an earlier one removed does not run.
                if mode in entry[0] and any(e is entry for e in self._traces):
                    try:
                        entry[1](self._name, '', mode[0] if entry[3] else mode)
                    except Exception as error:
                        print('Tk callback error:', error)
        finally:
            self._tracing = False

    def get(self):
        self._fire('read')
        for widget in self._value_widgets:
            if widget._alive:
                if isinstance(widget, Scale) and widget._ttk:
                    continue   # ttk::scale shows the variable, clamped, but never writes it back
                if isinstance(widget, (Scale, Progressbar)):
                    self._value = widget.get() if isinstance(widget, Scale) else widget['value']
                    return self._coerce(self._value)
                if widget._call('cget', widget._id, 'selected'):
                    self._value = widget._choice if isinstance(widget, Radiobutton) else widget._onvalue
                    return self._coerce(self._value)
        for widget in self._value_widgets:
            if widget._alive and isinstance(widget, Checkbutton) and not isinstance(widget, Radiobutton):
                self._value = widget._offvalue
                return self._coerce(self._value)
        for widget in self._widgets:
            if isinstance(widget, _EntryBase) and widget._alive:
                self._value = widget.get()
                break
        return self._coerce(self._value)

    def set(self, value):
        self._set(value, None)

    def _set(self, value, source):
        for widget in list(self._widgets):
            if widget._alive and widget is not source:
                if isinstance(widget, _EntryBase):
                    widget._variable_written(value)
                else:
                    widget._call('configure', widget._id, {'text': str(value)})
        for widget in list(self._value_widgets):
            if widget._alive and widget is not source:
                widget._apply_value(value)
        self._value = value
        for menu in list(self._menus):
            if menu._alive:
                menu._sync()
        self._fire('write')


def _trace_modes(mode):
    modes = (mode,) if isinstance(mode, str) else tuple(mode)
    for m in modes:
        if m not in _TRACE_ORDER:
            raise ValueError('bad operation "' + str(m) + '": must be array, read, unset, or write')
    if not modes:
        raise ValueError('bad operation list "": must be one or more of array, read, unset, or write')
    return modes


def _trace_letters(mode):
    letters = {'r': 'read', 'w': 'write', 'u': 'unset', 'a': 'array'}
    if not mode or any(m not in letters for m in mode):
        raise ValueError('bad operations "' + str(mode) + '": should be one or more of rwua')
    return tuple(letters[m] for m in mode)


class StringVar(Variable):
    def _coerce(self, value):
        return str(value)


class IntVar(Variable):
    _default_value = 0

    def _coerce(self, value):
        return int(value)


class DoubleVar(Variable):
    _default_value = 0.0

    def _coerce(self, value):
        return float(value)


class BooleanVar(Variable):
    _default_value = False

    def _coerce(self, value):
        if value in (True, 1, '1', 'true', 'yes', 'on'):
            return True
        if value in (False, 0, '0', 'false', 'no', 'off'):
            return False
        raise ValueError('expected boolean value')


class Widget:
    def _init(self, master, kind, cnf, kw):
        if master is None:
            master = _default()
        if not master._alive or not isinstance(master, (Tk, Frame, PanedWindow)):
            raise ValueError('master must be a live Tk, Frame or PanedWindow')
        self.master = master
        self._root = master._root
        self._alive = True
        self._children = []
        self._variable = None
        self._command = None
        self._command_id = 0
        self._bindings = {}
        self._extra_callbacks = {}
        name = kw.pop('name', None)
        if isinstance(cnf, dict) and 'name' in cnf:
            cnf = dict(cnf)
            name = cnf.pop('name')
        self._id = self._call('create', kind, master._id, {})
        master._children.append(self)
        self._root._widgets[self._id] = self
        _child_name(self, master, name)
        try:
            self.configure(cnf, **kw)
        except Exception:
            self.destroy()
            raise

    def __str__(self):
        return self._w

    def winfo_name(self):
        return self._name

    def nametowidget(self, name):
        """The widget with path name name: from the root when it starts with '.', else below this one."""
        parts = str(name).split('.')
        widget = self
        if not parts[0]:
            widget = self._root
            parts = parts[1:]
        for part in parts:
            if not part:
                break
            widget = widget.children[part]
        return widget

    _nametowidget = nametowidget

    def _call(self, operation, *args):
        if not self._alive:
            raise ValueError('widget was destroyed')
        root = self._root
        return _tk.call(root._screen, operation, root._token, *args)

    def _image_ref(self, value):
        if value is None or value == '':
            return 0
        if isinstance(value, str):
            for image in self._root._images.values():
                if image.name == value:
                    value = image
                    break
            else:
                raise ValueError('image "' + value + '" does not exist')
        if not isinstance(value, PhotoImage) or value._root is not self._root or value._id is None:
            raise ValueError('image must be a live PhotoImage of the same display')
        return value._id

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if 'padding' in options and not self._ttk:
            raise TclError('unknown option "-padding"')   # ttk's; classic widgets have padx/pady
        # Options Tk takes that the display does not draw: kept for cget.
        if 'justify' in options and not self._ttk and not isinstance(self, _EntryBase):
            self._keep('justify', _choice(options.pop('justify'), ('left', 'right', 'center'), 'justification'))
        if 'aspect' in options and isinstance(self, Message):
            self._keep('aspect', int(options.pop('aspect')))
        if 'exportselection' in options and isinstance(self, (Listbox, _EntryBase, Text)):
            self._keep('exportselection', 1 if _tcl_boolean(options.pop('exportselection')) else 0)
        if 'text' in options and not isinstance(options['text'], str):
            # Tk keeps the value it was given: cget('text') gives back 5, not '5'.
            self._text_value = options['text']
            options['text'] = '' if options['text'] is None else str(options['text'])
        elif 'text' in options:
            self._text_value = None
        if 'anchor' in options and len(str(options['anchor'])) > 1 and 'center'.startswith(str(options['anchor'])) or options.get('anchor') == 'c':
            options['anchor'] = 'center'   # Tk takes any start of "center"
        menu = getattr(self, '_menu_widget', None)
        if 'menu' in options:
            # A window's menubar, a Menubutton's menu: a Menu of this display (or its path name).
            menu = options['menu']
            if isinstance(menu, str) and menu:
                menu = self.nametowidget(menu)
            if menu in (None, ''):
                menu = None
            elif not isinstance(menu, Menu) or menu._root is not self._root or not menu._alive:
                raise TclError('bad menu "' + str(options['menu']) + '"')
            options['menu'] = 0 if menu is None else menu._id
        if 'image' in options:
            options['image'] = self._image_ref(options['image'])
        variable = options.pop('textvariable', self._variable)
        if variable is not None and not isinstance(variable, Variable):
            raise TypeError('textvariable must be a Variable')
        if variable is not None:
            options['text'] = str(variable.get())
        command = options.get('command', self._command)
        callback = 0
        if 'command' in options:
            if command is not None and not callable(command):
                raise TypeError('command must be callable')
            callback = self._root._register(command) if command is not None else 0
            options['command'] = callback
        extra = {}
        try:
            for name in ('xscrollcommand', 'yscrollcommand'):
                if name in options:
                    func = options[name]
                    if func is not None and not callable(func):
                        raise TypeError(name + ' must be callable')
                    key = self._root._register(func) if func is not None else 0
                    extra[name] = (key, func)
                    options[name] = key
            self._call('configure', self._id, options)
        except Exception:
            self._root._callbacks.pop(callback, None)
            for pair in extra.values():
                self._root._callbacks.pop(pair[0], None)
            raise
        for name in extra:
            self._root._callbacks.pop(self._extra_callbacks.get(name, (0, None))[0], None)
            self._extra_callbacks[name] = extra[name]
        if variable is not self._variable:
            if self._variable is not None:
                self._variable._widgets.remove(self)
            self._variable = variable
            if variable is not None:
                variable._widgets.append(self)
        if 'command' in options:
            self._root._callbacks.pop(self._command_id, None)
            self._command_id = callback
            self._command = command
        if 'menu' in options:
            self._menu_widget = menu

    config = configure

    def _keep(self, key, value):
        try:
            self._kept[key] = value
        except AttributeError:
            self._kept = {key: value}

    def cget(self, key):
        key = _aliases.get(key, key)
        if key in getattr(self, '_kept', ()):
            return self._kept[key]
        if key == 'menu':
            menu = getattr(self, '_menu_widget', None)
            return menu._w if menu is not None and menu._alive else ''
        if key == 'text' and getattr(self, '_text_value', None) is not None and self._variable is None:
            text = self._call('cget', self._id, 'text')
            return self._text_value if text == str(self._text_value) else text
        if key == 'textvariable':
            return self._variable
        if key == 'command':
            return self._command
        if key in ('xscrollcommand', 'yscrollcommand'):
            return self._extra_callbacks.get(key, (0, None))[1]
        if key == 'image':
            image = self._root._images.get(self._call('cget', self._id, 'image'))
            return image.name if image is not None else ''
        return self._call('cget', self._id, key)

    def __getitem__(self, key):
        return self.cget(key)

    def __setitem__(self, key, value):
        self.configure({key: value})

    def _layout_options(self, cnf, kw):
        options = _options(cnf, kw)
        for key, a, b in (('padx', 'left', 'right'), ('pady', 'top', 'bottom')):
            if key in options:
                value = options.pop(key)
                if isinstance(value, (tuple, list)):
                    if len(value) != 2:
                        raise ValueError('padding needs two values')
                    options[a], options[b] = value
                else:
                    options[a] = value
                    options[b] = value
        return options

    def pack(self, cnf=None, **kw):
        self._call('pack', self._id, self._layout_options(cnf, kw))
        self._left_notebook()

    def _left_notebook(self):
        # A notebook's page given to another geometry manager (or destroyed) loses its tab, and the
        # notebook may select another one: it says so with <<NotebookTabChanged>>.
        master = self.master
        if master is not None and master._notebook and master._alive:
            master._sync()

    pack_configure = pack

    def pack_forget(self):
        self._layout('pack_forget')

    def _layout(self, action, options=None):
        return self._call('layout', self._id, action, {} if options is None else options)

    def pack_propagate(self, flag=None):
        return self._layout('pack_propagate', {} if flag is None else {'flag': bool(flag)})

    def grid(self, cnf=None, **kw):
        self._layout('grid', self._layout_options(cnf, kw))
        self._left_notebook()

    grid_configure = grid
    grid_config = grid

    def grid_remove(self):
        self._layout('grid_remove')

    def grid_forget(self):
        self._layout('grid_forget')

    def grid_propagate(self, flag=None):
        return self._layout('grid_propagate', {} if flag is None else {'flag': bool(flag)})

    def grid_anchor(self, anchor=None):
        return self._layout('grid_anchor', {} if anchor is None else {'anchor': anchor})

    def _slotconfigure(self, action, index, cnf, kw):
        options = _options(cnf if isinstance(cnf, dict) else None, kw)
        if isinstance(index, (tuple, list)):
            for i in index:
                self._slotconfigure(action, i, cnf, kw)
            return None
        options['index'] = index
        result = dict(self._layout(action, options))
        return result[cnf] if isinstance(cnf, str) else result

    def grid_rowconfigure(self, index, cnf=None, **kw):
        return self._slotconfigure('rowconfigure', index, cnf, kw)

    def grid_columnconfigure(self, index, cnf=None, **kw):
        return self._slotconfigure('columnconfigure', index, cnf, kw)

    rowconfigure = grid_rowconfigure
    columnconfigure = grid_columnconfigure

    def grid_size(self):
        return tuple(self._layout('grid_size'))

    def grid_bbox(self, column=None, row=None, col2=None, row2=None):
        options = {}
        for key, value in (('column', column), ('row', row), ('column2', col2), ('row2', row2)):
            if value is not None:
                options[key] = value
        return tuple(self._layout('grid_bbox', options))

    def grid_info(self):
        info = dict(self._layout('grid_info'))
        if info:
            for key, a, b in (('padx', 'left', 'right'), ('pady', 'top', 'bottom')):
                first, last = info.pop(a), info.pop(b)
                info[key] = first if first == last else (first, last)
            info['in'] = self.master
        return info

    def place(self, cnf=None, **kw):
        self._layout('place', _options(cnf, kw))
        self._left_notebook()

    place_configure = place
    place_config = place

    def place_forget(self):
        self._layout('place_forget')

    def place_info(self):
        info = dict(self._layout('place_info'))
        if info:
            info['in'] = self.master
        return info

    def pack_slaves(self):
        return [self._root._widgets[i] for i in self._layout('pack_slaves')]

    def grid_slaves(self, row=None, column=None):
        widgets = [self._root._widgets[i] for i in self._layout('grid_slaves')]
        if row is not None:
            widgets = [w for w in widgets if w.grid_info()['row'] <= row < w.grid_info()['row'] + w.grid_info()['rowspan']]
        if column is not None:
            widgets = [w for w in widgets if w.grid_info()['column'] <= column < w.grid_info()['column'] + w.grid_info()['columnspan']]
        return widgets

    def place_slaves(self):
        return [self._root._widgets[i] for i in self._layout('place_slaves')]

    def winfo_manager(self):
        return self._layout('manager')

    def winfo_reqwidth(self):
        return self._layout('request')[0]

    def winfo_reqheight(self):
        return self._layout('request')[1]

    def _local_bindings(self):
        # Virtual events (<<Name>>) the server never generates: bound and raised here only.
        try:
            return self._local
        except AttributeError:
            self._local = {}
            return self._local

    def _handlers(self, sequence):
        # The handlers bound to sequence on this widget, or None.
        if sequence in _sequences:
            return self._root._callbacks.get(self._bindings.get(sequence, 0))
        return self._local_bindings().get(sequence)

    def bind(self, sequence, func, add=None):
        sequence = _event_aliases.get(sequence, sequence)
        if not callable(func):
            raise TypeError('binding must be callable')
        handlers = self._handlers(sequence)
        if handlers is None:
            handlers = _Handlers()
            if sequence in _sequences:
                callback = self._root._register(handlers)
                try:
                    self._call('bind', self._id, sequence, callback)
                except Exception:
                    self._root._callbacks.pop(callback, None)
                    raise
                self._bindings[sequence] = callback
            elif _virtual(sequence) or sequence == '<Escape>':
                # <Escape>: the Tk display's view keeps the key (it closes the view), so only
                # event_generate reaches such a binding (CPython's dialogs bind it to cancel).
                self._local_bindings()[sequence] = handlers
            else:
                raise ValueError('unsupported event: ' + sequence)
        return handlers.add(self._root, func, add)

    def unbind(self, sequence, funcid=None):
        # Without funcid every handler goes; with it only that one (as CPython 3.12).
        sequence = _event_aliases.get(sequence, sequence)
        handlers = self._handlers(sequence)
        if funcid is not None:
            if handlers is not None:
                handlers.remove(funcid)
            self._root._delete_command(str(funcid))
        if handlers is None or funcid is not None and handlers.entries:
            return
        if sequence in _sequences:
            self._call('bind', self._id, sequence, 0)
            self._root._callbacks.pop(self._bindings.pop(sequence), None)
        else:
            self._local_bindings().pop(sequence, None)

    def focus_set(self):
        self._call('focus', self._id)

    def getvar(self, name='PY_VAR'):
        # Only the globals menu entries name by string (a checkbutton's label, 'selectedButton').
        var = _menu_variables(self._root).get(str(name))
        if var is None or getattr(var, '_unset', False):
            raise TclError('can\'t read "' + str(name) + '": no such variable')
        return var.get()

    def setvar(self, name='PY_VAR', value='1'):
        variables = _menu_variables(self._root)
        var = variables.get(str(name))
        if var is None:
            var = variables[str(name)] = StringVar()
        var._unset = False
        var.set(value)

    focus = focus_set

    def focus_get(self):
        # The widget of this program that has the keyboard (in any of its windows), or None.
        focused = self._call('focused', self._root._id)
        return self._root._widgets.get(focused) if focused else None

    def event_generate(self, sequence, **kw):
        # Runs the handlers bound to sequence: the widget's, then its window's (Tk, Toplevel),
        # unless one returns 'break'. when='now' (the default) runs them at once, the other
        # whens after the events already queued. Fields come from kw; a key event runs the
        # key's own binding where there is one, else <Key> (as a real key press does).
        sequence = _event_aliases.get(sequence, sequence)
        if sequence not in _sequences and not _virtual(sequence) and sequence != '<Escape>':
            raise ValueError('unsupported event: ' + sequence)
        when = kw.pop('when', 'now')
        if when not in ('now', 'tail', 'head', 'mark'):
            raise ValueError('bad -when value "' + str(when) + '": must be now, tail, head, or mark')
        for name in _ignored_event_fields:
            kw.pop(name, None)
        event = self._event(sequence, kw)
        if kw:
            raise ValueError('unsupported event field: ' + sorted(kw)[0])
        specific = sequence
        if sequence == '<Key>' and '<' + event.keysym + '>' in _key_sequences:
            specific = '<' + event.keysym + '>'
        top = self.winfo_toplevel()
        targets = (self,) if top is self else (self, top)

        def run():
            # Tk's bindtags: the widget, its class (what the display does for it), its window.
            for widget in targets:
                if not widget._alive:
                    continue
                handlers = widget._handlers(specific)
                if handlers is None and specific in _key_sequences:
                    handlers = widget._handlers('<Key>')
                if handlers is not None and handlers(event) == 'break':
                    break
                if widget is self:
                    try:
                        self._class_event(specific)
                    except Exception as error:
                        print('Tk callback error:', error)

        if when == 'now':
            run()
        else:
            self.after(0, run)

    def _event(self, sequence, kw):
        # An Event for sequence on this widget, with fields taken from kw (the rest default).
        event = Event()
        event.widget = self
        event.type = sequence
        number = 1 if sequence in ('<Button-1>', '<ButtonRelease-1>', '<Double-Button-1>') else 3 if sequence in ('<Button-3>', '<ButtonRelease-3>') else 0
        number = kw.pop('button', number)
        keysym = kw.pop('keysym', sequence[1:-1] if sequence in _key_sequences else '')
        keycode = 0
        for code in _keysyms:
            if _keysyms[code] == keysym:
                keycode = code
                break
        char = keysym if len(keysym) == 1 else _key_chars.get(keysym, '')
        for name, default in (('x', 0), ('y', 0), ('keycode', keycode), ('char', char), ('state', 0),
                              ('delta', 0), ('num', number), ('width', 0), ('height', 0), ('data', '')):
            setattr(event, name, kw.pop(name, default))
        event.keysym = keysym
        return event

    def _class_event(self, sequence):
        # What the widget's Tk class binding does for an event a program generates (see event_generate).
        pass

    def register(self, func, subst=None, needcleanup=1):
        # A name for func, as Tk's commands have (validatecommand=(name, '%P') and the like).
        if not callable(func):
            raise TypeError('command must be callable')
        return self._root._add_command(func)

    _register_command = register

    def deletecommand(self, name):
        self._root._delete_command(name)

    def after_idle(self, func, *args):
        return self._queue_timer(computer.uptime(), func, args, True)

    def winfo_class(self):
        return self._tk_class

    def _sibling(self, other):
        # Widget ids are per display: a widget of another display must not be mistaken for one here.
        if other is None:
            return {}
        if not isinstance(other, Widget) or not other._alive or other._root is not self._root:
            raise ValueError('stacking needs a live widget on the same display')
        return {'other': other._id}

    def lift(self, aboveThis=None):
        self._layout('lift', self._sibling(aboveThis))

    tkraise = lift

    def lower(self, belowThis=None):
        self._layout('lower', self._sibling(belowThis))

    def winfo_toplevel(self):
        widget = self
        while widget is not None and not isinstance(widget, Tk):
            widget = widget.master
        return widget if widget is not None else self._root

    def winfo_rootx(self):
        return self._call('bounds', self._id)[0]

    def winfo_rooty(self):
        return self._call('bounds', self._id)[1]

    def winfo_screen(self):
        return self._root._screen

    def winfo_screenwidth(self):
        return self._call('screen')[0]

    def winfo_screenheight(self):
        return self._call('screen')[1]

    def winfo_width(self):
        return self._call('bounds', self._id)[2]

    def winfo_x(self):
        return self._call('bounds', self._id)[0] - (self.master._call('bounds', self.master._id)[0] if self.master else 0)

    def winfo_y(self):
        return self._call('bounds', self._id)[1] - (self.master._call('bounds', self.master._id)[1] if self.master else 0)

    def winfo_ismapped(self):
        bounds = self._call('bounds', self._id)
        return bounds[2] > 0 and bounds[3] > 0

    def winfo_vrootwidth(self):
        return self.winfo_screenwidth()

    def winfo_vrootheight(self):
        return self.winfo_screenheight()

    def winfo_vrootx(self):
        return 0

    def winfo_vrooty(self):
        return 0

    def winfo_viewable(self):
        # Mapped with every ancestor mapped: the display maps nothing inside an unmapped widget.
        return 1 if self.winfo_ismapped() else 0

    def bell(self, displayof=0):
        pass    # a Tk display has no bell

    # ---- waiting, as Tk's tkwait: the program's other events and timers run meanwhile ----

    def _wait(self, done):
        owner = getattr(self._root, '_owner', None)
        while not done():
            if _program is not None:
                _program.check()
            _update(owner)
            if done():
                break
            _idle(owner, 50)    # what done() looks at may change without a wake: look each tick

    def wait_window(self, window=None):
        """Run events until window (this widget by default) is destroyed."""
        window = self if window is None else window
        self._wait(lambda: not window._alive or not window._root._alive)

    def wait_variable(self, name='PY_VAR'):
        """Run events until the variable is written."""
        if not isinstance(name, Variable):
            raise TypeError('wait_variable needs a Variable')
        written = []
        cbname = name.trace_add('write', lambda *args: written.append(1))
        try:
            self._wait(lambda: written or not self._root._alive)
        finally:
            name.trace_remove('write', cbname)

    waitvar = wait_variable

    def wait_visibility(self, window=None):
        """Windows show at once on a Tk display: nothing to wait for."""
        return None

    # ---- grabs: a modal dialog's ----

    def grab_set(self):
        """Confine this program's input to this widget and what is in it (a modal dialog)."""
        self._call('grab', self._id, 'set', {})

    grab_set_global = grab_set

    def grab_release(self):
        self._call('grab', self._id, 'release', {})

    def grab_current(self):
        found = self._call('grab', self._id, 'current', {})
        return self._root._widgets.get(found) if found else None

    def grab_status(self):
        return 'local' if self._call('grab', self._id, 'status', {}) == 'local' else None

    def update_idletasks(self):
        # Tk's idle callbacks (after_idle, a theme change's redraw) run now, those they add too;
        # after() timers wait for update() or mainloop().
        root = self._root
        while True:
            pending = [name for name, timer in root._timers.items() if len(timer) > 4 and timer[4]]
            if not pending:
                break
            for name in pending:
                timer = root._timers.pop(name, None)
                if timer is not None and timer[1]._alive:
                    try:
                        timer[2](*timer[3])
                    except Exception as error:
                        print('Tk timer error:', error)
        self._call('bounds', self._id)

    def winfo_height(self):
        return self._call('bounds', self._id)[3]

    def winfo_exists(self):
        return self._alive

    def after(self, ms, func=None, *args):
        if not self._alive:
            raise ValueError('widget was destroyed')
        deadline = computer.uptime() + max(0, int(ms)) / 1000
        if func is None:
            # Tk's `after ms` without a script: sleep that long (a poll point, as time.sleep).
            import time
            time.sleep(max(0, int(ms)) / 1000)
            return None
        return self._queue_timer(deadline, func, args, False)

    def _queue_timer(self, deadline, func, args, idle):
        if not callable(func):
            raise TypeError('after callback must be callable')
        global _next_timer
        # Ids are unique across windows: a Toplevel is a separate root here, and an id
        # passed to another window's after_cancel must not cancel an unrelated timer.
        # Publish the COMPLETE timer atomically: another task's mainloop may run it
        # (even destroy its window) before after_idle returns to the calling task.
        irq = __machine_disable_irq()
        try:
            if not self._alive:
                raise ValueError('widget was destroyed')
            _next_timer += 1
            name = 'after#' + str(_next_timer)
            self._root._timers[name] = (deadline, self, func, args, idle)
        finally:
            __machine_enable_irq(irq)
        _wake(getattr(self._root, '_owner', None))   # a loop parked until a later timer re-plans
        return name

    def after_cancel(self, name):
        for root in list(_roots.values()):
            if root._timers.pop(name, None) is not None:
                return
        self._root._timers.pop(name, None)

    def update(self):
        if _program is not None:
            _program.check()       # a poll point, as mainloop (a `while ...: update()` loop)
        _update(getattr(self._root, '_owner', None))

    def mainloop(self):
        root = self._root
        root._quit = False
        owner = getattr(root, '_owner', None)
        while root._alive and not root._quit:
            if _program is not None:
                _program.check()   # the program was stopped (MPYOS kill, or its command ended)
            _update(owner)
            if root._alive and not root._quit:
                _idle(owner)
        if _program is not None:
            _program.check()       # kill closed the window: stop here, not in the code after mainloop()

    def quit(self):
        self._root._quit = True
        _wake(getattr(self._root, '_owner', None))

    def _detach(self):
        for child in list(self._children):
            child._detach()
        if self._variable is not None:
            self._variable._widgets.remove(self)
        variable = getattr(self, '_value_variable', None)
        if variable is not None and self in variable._value_widgets:
            variable._value_widgets.remove(self)
        root = self._root
        root._callbacks.pop(self._command_id, None)
        for pair in self._extra_callbacks.values():
            root._callbacks.pop(pair[0], None)
        for callback in self._bindings.values():
            root._callbacks.pop(callback, None)
        for name in list(root._timers):
            if root._timers[name][1] is self:
                root._timers.pop(name)
        root._widgets.pop(self._id, None)
        self._alive = False
        if self.master is not None:
            self.master._children.remove(self)
            if self.master.children.get(self._name) is self:
                del self.master.children[self._name]

    def _windows(self):
        found = []
        for child in self._children:
            if child._window:
                found.append(child)
            else:
                found.extend(child._windows())
        return found

    def destroy(self):
        if self._alive:
            # A Toplevel lives on the display root; destroy it with its master, as Tk does.
            for window in self._windows():
                window.destroy()
            self._call('destroy', self._id)
            self._detach()
            self._left_notebook()


Widget._window = False
Widget._ttk = False
Widget._notebook = False


class Tk(Widget):
    """A program's main window, on a Tk display's desktop like a Toplevel (title bar, –, ⧠, ×).

    The programs of one computer share a display, each with its own windows. With no display
    named, Tk() opens on one this computer already uses, else on the first by address that no
    other running computer holds; a display named by address or unique prefix is taken over
    from another computer.
    """

    def __init__(self, screenName=None, **kw):
        global _default_root
        screens = _tk.screens()
        if screenName is None:
            mine = [root._screen for root in _roots.values() if root._alive]
            matches = sorted(s for s in screens if s in mine) + sorted(s for s in screens if s not in mine)
        elif screenName in screens:
            matches = [screenName]
        else:
            matches = [s for s in screens if s.startswith(screenName)]
            if len(matches) > 1:
                raise OSError('ambiguous tk display prefix')
        if not matches:
            raise OSError('no tk display')
        self._screen = None
        self._token = None
        self._root = self
        self.master = None
        self._id = 0
        self._window = True
        self._alive = True
        self._children = []
        self._variable = None
        self._command = None
        self._command_id = 0
        self._bindings = {}
        self._extra_callbacks = {}
        self._callbacks = {}
        self._next_callback = 0
        self._widgets = {}
        self._images = {}
        self._timers = {}
        self._protocols = {}
        self._quit = False
        self._w = '.'
        self._name = 'tk'
        self.children = {}
        self._last_child_ids = {}
        self._owner = _owner()
        if _program is not None:
            _program.check()      # a stopped program opens no more windows
        for screen in matches:
            try:
                opened = _tk.call(screen, 'claim' if screenName is not None else 'claim_free', '')
            except Exception:
                if screenName is not None:
                    raise
                continue          # it went away meanwhile: try the next
            if opened:            # [token, root window id]; "" when another running computer holds it
                self._screen = screen
                self._token, self._id = opened[0], opened[1]
                self._widgets[self._id] = self
                _roots[self._token] = self
                _live_changed()
                break
        if self._token is None:
            self._alive = False
            raise OSError('no free tk display: every one is in use')
        try:
            if _program is not None and _program.stopping(self._owner):
                # Stopped while opening: the shell's clean-up skipped this window (it had no
                # token yet), so close it here.
                raise SystemExit
            if kw:
                self.configure(**kw)
        except BaseException as error:
            try:
                self._call('release')
            except Exception:
                pass              # the display went away or was taken over: gone anyway
            self._alive = False
            _forget(self)
            raise error
        if _default_root is None or not _default_root._alive:
            _default_root = self
        _live_changed()
        try:
            # Lets the MPYOS shell close a finished command's windows without importing us.
            computer._tk_release = _release_all
            computer._tk_release_owned = _release_owned
        except Exception:
            pass

    def _register(self, callback):
        self._next_callback += 1
        self._callbacks[self._next_callback] = callback
        return self._next_callback

    def _add_command(self, func):
        # Tcl command names (register, bind funcids): deletecommand of an unknown one fails.
        global _next_command
        _next_command += 1
        name = str(_next_command) + getattr(func, '__name__', 'command')
        try:
            self._commands[name] = func
        except AttributeError:
            self._commands = {name: func}
        return name

    def _delete_command(self, name):
        try:
            del self._commands[name]
        except (AttributeError, KeyError):
            raise TclError("can't delete Tcl command")

    # ---- the window manager: the root is a window on the desktop, as each Toplevel is ----
    def _wm(self, action, options=None):
        return self._call('wm', self._id, action, {} if options is None else options)

    def geometry(self, value=None):
        result = self._wm('geometry', None if value is None else _parse_geometry(value))
        return str(result[0]) + 'x' + str(result[1]) + _offset(result[2]) + _offset(result[3])

    wm_geometry = geometry

    def title(self, value=None):
        return self._wm('title', None if value is None else {'title': str(value)})

    wm_title = title

    def transient(self, master=None):
        """Make this window a dialog of master's window: no taskbar button, always above it, hidden
        and shown with it. Without master: the current master's path name, or ''."""
        if master is None:
            found = self._wm('transient')
            widget = self._root._widgets.get(found) if found else None
            return widget._w if widget is not None else ''
        if master == '':
            self._wm('transient', {'master': 0})
            return ''
        if isinstance(master, str):
            master = self.nametowidget(master)
        top = master.winfo_toplevel() if isinstance(master, Widget) else None
        if top is not None and top._root is self._root and top is not self:
            self._wm('transient', {'master': top._id})
        return ''

    wm_transient = transient

    def lift(self, aboveThis=None):
        self._wm('lift', self._sibling(aboveThis))

    tkraise = lift

    def lower(self, belowThis=None):
        self._wm('lower', self._sibling(belowThis))

    def withdraw(self):
        self._wm('withdraw')

    def deiconify(self):
        self._wm('deiconify')

    def iconify(self):
        # Minimize: hidden, with a button on the taskbar that brings it back.
        self._wm('iconify')

    def state(self, newstate=None):
        # 'normal', 'iconic' (minimized), 'withdrawn' or 'zoomed' (maximized).
        if newstate is None:
            return self._wm('state')
        if newstate not in ('normal', 'iconic', 'withdrawn', 'zoomed'):
            raise ValueError('bad window state: ' + str(newstate))
        self._wm('state', {'state': newstate})

    def resizable(self, width=None, height=None):
        # Whether the player may resize the window by its edges, per axis; maximizing
        # needs both.
        options = {}
        if width is not None:
            options['width'] = _flag(width)
        if height is not None:
            options['height'] = _flag(height)
        result = self._wm('resizable', options)
        if not options:
            return (1 if result[0] else 0, 1 if result[1] else 0)

    def _size_limit(self, action, width, height):
        options = {}
        if width is not None:
            options['width'] = int(width)
        if height is not None:
            options['height'] = int(height)
        result = self._wm(action, options)
        if not options:
            return (result[0], result[1])

    def minsize(self, width=None, height=None):
        return self._size_limit('minsize', width, height)

    def maxsize(self, width=None, height=None):
        # Without a maxsize, the limit is the display (as Tk's default is the screen).
        return self._size_limit('maxsize', width, height)

    def attributes(self, *args):
        # '-zoomed' (as on X11): maximized or not. Tk's other attributes are ignored.
        def read(name):
            return 1 if self.state() == 'zoomed' else 0

        def write(name, value):
            if _flag(value):
                self.state('zoomed')
            elif self.state() == 'zoomed':
                self.state('normal')
        return _attributes(args, ('-zoomed',), read, write)

    wm_state = state
    wm_withdraw = withdraw
    wm_deiconify = deiconify

    def iconname(self, newName=None):
        # The name a window manager shows for the minimized window: the taskbar shows the title here.
        if newName is None:
            return getattr(self, '_iconname', '')
        self._iconname = str(newName)
        return ''

    wm_iconname = iconname
    wm_iconify = iconify
    wm_resizable = resizable
    wm_minsize = minsize
    wm_maxsize = maxsize
    wm_attributes = attributes

    def winfo_x(self):
        return self._wm('geometry')[2]

    def winfo_y(self):
        return self._wm('geometry')[3]

    def protocol(self, name=None, func=None):
        # Window-manager protocols. The window's close button sends WM_DELETE_WINDOW;
        # without a handler the window is destroyed, as in tkinter.
        if name is None:
            return tuple(self._protocols)
        if func is None:
            return self._protocols.get(name, '')
        if not callable(func):
            raise TypeError('protocol handler must be callable')
        self._protocols[name] = func

    wm_protocol = protocol

    def _delete_window(self):
        handler = self._protocols.get('WM_DELETE_WINDOW')
        if handler is not None:
            handler()
        else:
            self.destroy()

    def destroy(self):
        global _default_root
        if self._alive:
            try:
                self._call('release')
            except Exception:
                # The display was broken, unloaded or taken over: the window is gone
                # anyway. Still forget it, or mainloop() never ends and this VM keeps
                # the display reserved until reboot.
                pass
            self._detach()
            for image in self._images.values():
                image._id = None  # released with the display
            self._images.clear()
            _forget(self)
            _live_changed()
            if _default_root is self:
                _default_root = None


def _release_all(keep=()):
    # Destroy every window of this VM but those of the programs in keep (owners, see
    # _owner()). MPYOS calls this when a command ends, the way process exit closes
    # CPython's windows: a program that stops without destroy() (an exception, quit()
    # then return, no mainloop at all) would otherwise keep its display claimed, and
    # every later Tk() would fail, until the computer reboots. Background programs
    # (`command &`) are kept until they end themselves.
    for root in list(_roots.values()):
        if root._token is None or any(getattr(root, '_owner', None) == owner for owner in keep):
            continue          # being opened right now, or a background program's
        try:
            root.destroy()
        except Exception:
            _forget(root)
            _live_changed()


def _release_owned(owner):
    # Destroy the windows one program opened (owner as from _owner() in that program); how many.
    count = 0
    for root in list(_roots.values()):
        if root._token is not None and getattr(root, '_owner', None) == owner:
            count += 1
            try:
                root.destroy()
            except Exception:
                _forget(root)
                _live_changed()
    return count


class Toplevel(Tk):
    """A window on its master's display (drag to move, edges to resize, minimize/maximize/close).

    screen= another display opens a new root there instead.
    """

    def __init__(self, master=None, cnf=None, screen=None, **kw):
        if isinstance(cnf, str) and screen is None:
            screen, cnf = cnf, None  # the former Toplevel(master, screen) signature
        if screen is not None:
            screens = _tk.screens()
            matches = [screen] if screen in screens else [s for s in screens if s.startswith(screen)]
            if master is None:
                master = _own_default()
            live = isinstance(master, (Tk, Frame, PanedWindow)) and master._alive
            if not (live and len(matches) == 1 and matches[0] == master._root._screen):
                # Another display: a new root there, without claiming a default one first.
                Tk.__init__(self, screenName=screen, **_options(cnf, kw))
                return
        if master is None:
            master = _default()
        if not master._alive or not isinstance(master, (Tk, Frame, PanedWindow)):
            raise ValueError('master must be a live Tk, Frame or PanedWindow')
        root = master._root
        self._window = True
        self._root = root
        self._screen = root._screen
        self.master = master
        self._alive = True
        self._children = []
        self._variable = None
        self._command = None
        self._command_id = 0
        self._bindings = {}
        self._extra_callbacks = {}
        self._protocols = {}
        self._quit = False
        name = kw.pop('name', None)
        class_ = kw.pop('class_', None)
        if class_:
            self._tk_class = str(class_)   # winfo_class; the option database is not used here
        self._id = _tk.call(root._screen, 'create', root._token, 'window', 0, {})
        master._children.append(self)
        root._widgets[self._id] = self
        _child_name(self, master, name)
        try:
            options = _options(cnf, kw)
            if options:
                self.configure(options)
        except Exception:
            self.destroy()
            raise

    def destroy(self):
        if self._root is self:
            return Tk.destroy(self)   # a root opened on another display (screen=)
        Widget.destroy(self)


class PhotoImage:
    """An image on one display: from a file or data (PNG, JPEG, GIF, BMP) or blank.

    fit=(width, height) scales a file or data down to fit that box (an MPY4OC
    extension). This VM has no __del__: call delete() to free an image you no longer
    need; all of a display's images go when its Tk root is destroyed.
    """

    def __init__(self, name=None, cnf=None, master=None, **kw):
        options = {}
        if cnf:
            options.update(cnf)
        options.update(kw)
        wrap = options.pop('_wrap', None)
        root = master._root if master is not None else _default()
        self._root = root
        if wrap is not None:
            self._id, self._width, self._height = wrap
        else:
            file = options.pop('file', None)
            data = options.pop('data', None)
            fit = options.pop('fit', None)
            options.pop('format', None)
            request = {}
            for key in ('width', 'height'):
                if key in options:
                    request[key] = int(options.pop(key))
            if options:
                raise ValueError('unsupported image option: ' + ', '.join(options))
            if file is not None and data is not None:
                raise ValueError('give file or data, not both')
            if fit is not None:
                fit = tuple(fit)
                if len(fit) != 2:
                    raise ValueError('fit must be (width, height)')
                request['fit'] = [int(fit[0]), int(fit[1])]
            if file is not None:
                request['upload'] = _upload_file(root, file)
            elif data is not None:
                request['data'] = data
            self._id, self._width, self._height = tuple(root._call('image', 0, 'create', request))
        if name is None:
            # Unique across displays, as tkinter's own names are.
            _image_names[0] += 1
            name = 'pyimage' + str(_image_names[0])
        else:
            # As in Tk, a new image under an existing name replaces the old one.
            name = str(name)
            for other in list(root._images.values()):
                if other.name == name:
                    other.delete()
        self.name = name
        root._images[self._id] = self

    def _cmd(self, action, options=None):
        if self._id is None:
            raise ValueError('image was deleted')
        return self._root._call('image', self._id, action, {} if options is None else options)

    def __str__(self):
        return self.name

    def width(self):
        return self._width

    def height(self):
        return self._height

    def put(self, data, to=None):
        options = {'rows': _image_rows(data)}
        if to is not None:
            to = tuple(to)
            options['x'], options['y'] = int(to[0]), int(to[1])
            if len(to) == 4:
                options['x2'], options['y2'] = int(to[2]), int(to[3])
            elif len(to) != 2:
                raise ValueError('to must be (x, y) or (x1, y1, x2, y2)')
        self._cmd('put', options)

    def get(self, x, y):
        return tuple(self._cmd('get', {'x': int(x), 'y': int(y)}))

    def blank(self):
        self._cmd('blank')

    def transparency_get(self, x, y):
        return bool(self._cmd('transparency_get', {'x': int(x), 'y': int(y)}))

    def transparency_set(self, x, y, boolean):
        self._cmd('transparency_set', {'x': int(x), 'y': int(y), 'transparent': bool(boolean)})

    def _derive(self, action, options):
        return PhotoImage(master=self._root, _wrap=tuple(self._cmd(action, options)))

    def copy(self):
        return self._derive('copy', {})

    def zoom(self, x, y=''):
        return self._derive('zoom', {'x': int(x), 'y': int(x if y == '' else y)})

    def subsample(self, x, y=''):
        return self._derive('subsample', {'x': int(x), 'y': int(x if y == '' else y)})

    def cget(self, key):
        if key == 'width':
            return self._width
        if key == 'height':
            return self._height
        raise ValueError('unsupported image option: ' + str(key))

    __getitem__ = cget

    def delete(self):
        if self._id is not None:
            try:
                self._cmd('delete')
            except Exception:
                pass  # the display is gone: so is the image
            self._root._images.pop(self._id, None)
            self._id = None


class Frame(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'frame', cnf, kw)


class LabelFrame(Frame):
    """A frame with a title on its border (text=), or one of its own child widgets in the title's
    place (labelwidget=; Tk also takes a sibling there, this one does not)."""
    _kind = 'labelframe'

    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, self._kind, cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if 'labelwidget' in options:
            options['labelwidget'] = self._label_id(options['labelwidget'])
        return Frame.configure(self, options)

    config = configure

    def _label_id(self, value):
        if value is None or value == '':
            return 0
        if isinstance(value, str):
            try:
                value = self.nametowidget(value)
            except KeyError:
                raise TclError('bad window path name "' + value + '"')
        if not isinstance(value, Widget) or not value._alive:
            raise TclError('bad window path name "' + str(value) + '"')
        if value.master is not self:
            if self._ttk:
                raise TclError("can't add " + str(value) + ' as slave of ' + str(self))
            raise TclError("can't use " + str(value) + ' as label in this frame')
        return value._id

    def cget(self, key):
        if key == 'labelwidget':
            ident = self._call('cget', self._id, 'labelwidget')   # 0: none (0 is also the root's id)
            widget = self._root._widgets.get(ident) if ident else None
            return str(widget) if widget is not None else ''
        return Frame.cget(self, key)

    def __getitem__(self, key):
        return self.cget(key)


class Label(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'label', cnf, kw)


class _ResourceIcon(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, self._kind, cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        key = self._descriptor_option
        if key in options:
            value = options[key]
            if value is None:
                options[key] = ''  # Explicit clear survives OC's table conversion.
            else:
                if not isinstance(value, dict):
                    raise TypeError(key + ' must be a descriptor dict or None')
                name = value.get('name')
                if not isinstance(name, str) or not name or len(name) > 256 or len(name.encode('utf-8')) > 256:
                    raise ValueError('icon name must be a nonempty string of at most 256 UTF-8 bytes')
                descriptor = {'name': name}
                if key == 'item':
                    damage = value.get('damage', 0)
                    if isinstance(damage, bool) or not isinstance(damage, int) or not 0 <= damage <= 65535:
                        raise ValueError('item damage must be an integer in 0..65535')
                    descriptor['damage'] = damage
                    nbt = value.get('nbt')
                    if nbt is not None:
                        if not isinstance(nbt, str) or len(nbt) > 4096 or len(nbt.encode('utf-8')) > 4096:
                            raise ValueError('item nbt must be a compound string of at most 4096 UTF-8 bytes')
                        descriptor['nbt'] = nbt
                # Do not transmit quantities, labels, compressed OC tags or other unrelated fields.
                options[key] = descriptor
        return Widget.configure(self, options)

    config = configure


class ItemIcon(_ResourceIcon):
    """Minecraft icon: item={'name': 'minecraft:stone', 'damage': 0, 'nbt': '{...}' }.

    nbt is an optional Minecraft 1.7 SNBT tag compound (4096 UTF-8 bytes, depth 16).
    width/height are content pixels, default 24; 0 selects 24. The icon fits a square
    inside the padding/border and follows anchor. item=None clears it. Extra OC map
    fields, including size, label and compressed tag, are ignored. No inventory is
    accessed. Unknown resources or failing client renderers show a missing icon.
    """
    _kind = 'itemicon'
    _descriptor_option = 'item'
    _tk_class = 'ItemIcon'


class FluidIcon(_ResourceIcon):
    """Forge still-fluid icon: fluid={'name': 'water'}; fluid=None clears it.

    Uses the registered fluid texture and tint. amount/label and other map fields
    are ignored. Pixel sizing, layout and bindings work as for ItemIcon.
    """
    _kind = 'fluidicon'
    _descriptor_option = 'fluid'
    _tk_class = 'FluidIcon'


class Button(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._default = 'disabled'
        self._init(master, 'button', cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if 'default' in options and not self._ttk:
            # The default button of a dialog (Tk draws it with an extra ring): kept for cget.
            self._default = _choice(options.pop('default'), ('active', 'disabled', 'normal'), 'default')
        return Widget.configure(self, options)

    config = configure

    def cget(self, key):
        if key == 'default' and not self._ttk:
            return self._default
        return Widget.cget(self, key)

    def invoke(self):
        if self.cget('state') != DISABLED and self._command is not None:
            return self._command()


def _canvas_coords(args):
    result = []
    for value in args:
        if isinstance(value, (tuple, list)):
            result.extend(_canvas_coords(value))
        else:
            result.append(value)
    return result


class Canvas(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._tag_bindings = {}
        self._event_item = None
        # Whether the view may have left (0, 0): only then does canvasx/canvasy ask the display.
        self._viewed = False
        self._init(master, 'canvas', cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if options.pop('highlightthickness', 0) != 0:
            raise ValueError('Canvas highlightthickness supports only zero')
        if 'scrollregion' in options:
            region = options['scrollregion']
            if region is None:
                region = ''
            if isinstance(region, (tuple, list)):
                region = [float(n) for n in region]
            options['scrollregion'] = region
            if region not in ('', []):
                self._viewed = True  # the mouse wheel can scroll it now
        Widget.configure(self, options)

    config = configure

    def cget(self, key):
        if key == 'scrollregion':
            region = self._call('cget', self._id, 'scrollregion')
            return ' '.join(str(int(n)) for n in region)
        return Widget.cget(self, key)

    def xview(self, *args):
        if args:
            self._viewed = True
        value = self._canvas('xview', args=list(args))
        return tuple(value) if not args else None

    def yview(self, *args):
        if args:
            self._viewed = True
        value = self._canvas('yview', args=list(args))
        return tuple(value) if not args else None

    def xview_moveto(self, fraction):
        self.xview('moveto', float(fraction))

    def yview_moveto(self, fraction):
        self.yview('moveto', float(fraction))

    def xview_scroll(self, number, what):
        self.xview('scroll', int(number), str(what))

    def yview_scroll(self, number, what):
        self.yview('scroll', int(number), str(what))

    def _canvas(self, action, selector='all', **options):
        if action != 'bind' and selector == 'current' and self._event_item is not None:
            selector = self._event_item
        options['selector'] = str(selector)
        return self._call('canvas', self._id, action, options)

    def _item_options(self, cnf, kw):
        options = {} if cnf is None else dict(cnf)
        options.update(kw)
        for key in ('fill', 'outline'):
            if key in options:
                options[key] = -1 if options[key] == '' else _color(options[key])
        if 'tags' in options and isinstance(options['tags'], str):
            options['tags'] = options['tags'].split()
        if 'image' in options:
            options['image'] = self._image_ref(options['image'])
        return options

    def _create(self, kind, args, kw):
        return self._canvas('create', type=kind, coords=_canvas_coords(args), options=self._item_options(None, kw))

    def create_line(self, *args, **kw):
        return self._create('line', args, kw)

    def create_rectangle(self, *args, **kw):
        return self._create('rectangle', args, kw)

    def create_oval(self, *args, **kw):
        return self._create('oval', args, kw)

    def create_polygon(self, *args, **kw):
        return self._create('polygon', args, kw)

    def create_text(self, *args, **kw):
        return self._create('text', args, kw)

    def create_image(self, *args, **kw):
        return self._create('image', args, kw)

    def coords(self, tagOrId, *args):
        if args:
            self._canvas('coords', tagOrId, coords=_canvas_coords(args))
            return None
        return list(self._canvas('coords', tagOrId))

    def itemconfigure(self, tagOrId, cnf=None, **kw):
        self._canvas('configure', tagOrId, options=self._item_options(cnf, kw))

    itemconfig = itemconfigure

    def itemcget(self, tagOrId, option):
        value = self._canvas('cget', tagOrId, option=option)
        if option in ('fill', 'outline'):
            return '' if value == -1 or value == '' else '#%06x' % value
        if option == 'tags':
            return tuple(value)
        if option == 'image':
            image = self._root._images.get(value)
            return image.name if image is not None else ''
        return str(value)

    def move(self, tagOrId, xAmount, yAmount):
        self._canvas('move', tagOrId, dx=xAmount, dy=yAmount)

    def delete(self, *args):
        for selector in args:
            removed = self.find_withtag(selector)
            self._canvas('delete', selector)
            for key in list(self._tag_bindings):
                if key[0] in [str(item) for item in removed]:
                    self._root._callbacks.pop(self._tag_bindings.pop(key), None)

    def find_all(self):
        return tuple(self._canvas('find'))

    def find_withtag(self, tagOrId):
        return tuple(self._canvas('find', tagOrId))

    def gettags(self, tagOrId):
        return tuple(self._canvas('tags', tagOrId))

    def type(self, tagOrId):
        return self._canvas('type', tagOrId) or None

    def bbox(self, tagOrId):
        result = self._canvas('bbox', tagOrId)
        return tuple(result) if result else None

    def addtag_withtag(self, newtag, tagOrId):
        self._canvas('addtag', tagOrId, tag=newtag)

    def dtag(self, tagOrId, tagToDelete=None):
        self._canvas('dtag', tagOrId, tag=tagOrId if tagToDelete is None else tagToDelete)

    def tag_raise(self, tagOrId, aboveThis=None):
        options = {} if aboveThis is None else {'relative': aboveThis}
        self._canvas('raise', tagOrId, **options)

    lift = tag_raise
    tkraise = tag_raise

    def tag_lower(self, tagOrId, belowThis=None):
        options = {} if belowThis is None else {'relative': belowThis}
        self._canvas('lower', tagOrId, **options)

    lower = tag_lower

    def _canvas_point(self, screen, gridspacing, axis):
        # Window coordinates (event.x/y) to canvas coordinates, as Tk: whole pixels plus the
        # view origin (asked for only once the view can have moved), then GridAlign.
        screen = float(screen)
        value = int(screen + 0.5) if screen >= 0 else int(screen - 0.5)
        if self._viewed:
            value += self._canvas('origin')[axis]
        if gridspacing is not None and gridspacing > 0:
            g = float(gridspacing)
            value = -int(-value / g + 0.5) * g if value < 0 else int(value / g + 0.5) * g
        return float(value)

    def canvasx(self, screenx, gridspacing=None):
        return self._canvas_point(screenx, gridspacing, 0)

    def canvasy(self, screeny, gridspacing=None):
        return self._canvas_point(screeny, gridspacing, 1)

    def tag_bind(self, tagOrId, sequence, func, add=None):
        sequence = _event_aliases.get(sequence, sequence)
        if not callable(func):
            raise TypeError('binding must be callable')
        key = (str(tagOrId), sequence)
        handlers = self._root._callbacks.get(self._tag_bindings.get(key, 0))
        if handlers is None:
            handlers = _Handlers()
            callback = self._root._register(handlers)
            try:
                self._canvas('bind', tagOrId, sequence=sequence, callback=callback)
            except Exception:
                self._root._callbacks.pop(callback, None)
                raise
            self._tag_bindings[key] = callback
        return handlers.add(self._root, func, add)

    def tag_unbind(self, tagOrId, sequence, funcid=None):
        sequence = _event_aliases.get(sequence, sequence)
        key = (str(tagOrId), sequence)
        handlers = self._root._callbacks.get(self._tag_bindings.get(key, 0))
        if funcid is not None:
            if handlers is not None:
                handlers.remove(funcid)
            self._root._delete_command(str(funcid))
        if handlers is None or funcid is not None and handlers.entries:
            return
        self._canvas('bind', tagOrId, sequence=sequence, callback=0)
        self._root._callbacks.pop(self._tag_bindings.pop(key), None)

    def _detach(self):
        for callback in self._tag_bindings.values():
            self._root._callbacks.pop(callback, None)
        self._tag_bindings.clear()
        Widget._detach(self)


class Message(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'message', cnf, kw)


def _equal_value(a, b):
    if isinstance(a, bool):
        a = int(a)
    if isinstance(b, bool):
        b = int(b)
    return str(a) == str(b)


class _ValueWidget(Widget):
    def _control_init(self, master, kind, cnf, kw):
        self._control_kind = kind
        # A Scale's private variable must keep fractions: configure() re-sends its
        # value, and an IntVar truncated 0.7 to 0 on any option change.
        self._value_variable = DoubleVar() if kind == 'scale' else IntVar()
        self._user_command = None
        self._onvalue = 1
        self._offvalue = 0
        self._choice = 1
        self._init(master, kind, cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        variable = options.pop('variable', self._value_variable)
        if not isinstance(variable, Variable):
            raise TypeError('variable must be a Variable')
        command = options.pop('command', self._user_command)
        if command is not None and not callable(command):
            raise TypeError('command must be callable')
        on = options.pop('onvalue', self._onvalue)
        off = options.pop('offvalue', self._offvalue)
        choice = options.pop('value', self._choice) if self._control_kind == 'radiobutton' else self._choice
        if self._control_kind == 'scale':
            options['value'] = float(variable.get())
        else:
            options['selected'] = _equal_value(variable.get(), choice if self._control_kind == 'radiobutton' else on)
            options['group'] = variable._key if self._control_kind == 'radiobutton' else 0
        options['command'] = self._activated
        Widget.configure(self, options)
        old = self._value_variable
        if old is not variable and self in old._value_widgets:
            old._value_widgets.remove(self)
        self._value_variable = variable
        if self not in variable._value_widgets:
            variable._value_widgets.append(self)
        self._user_command = command
        self._onvalue, self._offvalue, self._choice = on, off, choice

    config = configure

    def cget(self, key):
        if key == 'variable':
            return self._value_variable
        if key == 'command':
            return self._user_command
        if key == 'onvalue':
            return self._onvalue
        if key == 'offvalue':
            return self._offvalue
        if key == 'value' and self._control_kind == 'radiobutton':
            return self._choice
        return Widget.cget(self, key)

    def _apply_value(self, value):
        if self._control_kind == 'scale':
            options = {'value': float(value)}
        else:
            choice = self._choice if self._control_kind == 'radiobutton' else self._onvalue
            options = {'selected': _equal_value(value, choice)}
        self._call('configure', self._id, options)

    def _activated(self, *args):
        if self._control_kind == 'scale':
            value = self.get()
        elif self._control_kind == 'radiobutton':
            value = self._choice if self._call('cget', self._id, 'selected') else self._value_variable.get()
        else:
            value = self._onvalue if self._call('cget', self._id, 'selected') else self._offvalue
        self._value_variable._set(value, self)
        if self._user_command is not None:
            if self._control_kind == 'scale':
                return self._user_command(str(value))
            return self._user_command()


class Checkbutton(_ValueWidget):
    def __init__(self, master=None, cnf=None, **kw):
        self._control_init(master, 'checkbutton', cnf, kw)

    def select(self):
        self._value_variable.set(self._choice if self._control_kind == 'radiobutton' else self._onvalue)

    def deselect(self):
        if self._control_kind == 'radiobutton':
            if self._call('cget', self._id, 'selected'):
                self._value_variable.set('')
        else:
            self._value_variable.set(self._offvalue)

    def toggle(self):
        if self._control_kind == 'radiobutton':
            raise ValueError('radiobutton cannot toggle')
        self._value_variable.set(self._offvalue if self._call('cget', self._id, 'selected') else self._onvalue)

    def invoke(self):
        if self.cget('state') != DISABLED:
            self._call('control', self._id, 'invoke', ())
            return self._activated()


class Radiobutton(Checkbutton):
    def __init__(self, master=None, cnf=None, **kw):
        self._control_init(master, 'radiobutton', cnf, kw)


class Scale(_ValueWidget):
    def __init__(self, master=None, cnf=None, **kw):
        self._control_init(master, 'scale', cnf, kw)

    def get(self):
        return self._call('control', self._id, 'get', ())

    def set(self, value):
        actual = self._call('control', self._id, 'set', (float(value),))
        self._value_variable._set(actual, self)

    def coords(self, value=None):
        # The slider centre for value (the current one by default), in widget pixels.
        value = self.get() if value is None else float(value)
        return tuple(self._call('control', self._id, 'coords', (value,)))


class Progressbar(Widget):
    """ttk.Progressbar. mode='determinate' fills to value/maximum; 'indeterminate' moves a
    block back and forth as the value grows. start(interval) steps it on a timer, as Tk
    does; step() past the maximum wraps to the start. variable= follows the value."""

    def __init__(self, master=None, cnf=None, **kw):
        self._value_variable = None
        self._progress_timer = None
        self._progress_interval = 50
        self._init(master, 'progressbar', cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        variable = options.pop('variable', self._value_variable)
        if variable is not None and not isinstance(variable, Variable):
            raise TypeError('variable must be a Variable')
        if 'value' in options:
            options['value'] = float(options['value'])
        elif variable is not None and variable is not self._value_variable:
            options['value'] = float(variable.get())
        if 'maximum' in options:
            options['maximum'] = float(options['maximum'])
        Widget.configure(self, options)
        old = self._value_variable
        if old is not variable:
            if old is not None and self in old._value_widgets:
                old._value_widgets.remove(self)
            if variable is not None:
                variable._value_widgets.append(self)
            self._value_variable = variable
        if variable is not None and 'value' in options:
            variable._set(options['value'], self)

    config = configure

    def cget(self, key):
        if key == 'variable':
            return self._value_variable
        return Widget.cget(self, key)

    def _apply_value(self, value):
        self._call('configure', self._id, {'value': float(value)})

    def step(self, amount=None):
        value = self._call('control', self._id, 'step', (1.0 if amount is None else float(amount),))
        if self._value_variable is not None:
            self._value_variable._set(value, self)

    def start(self, interval=None):
        # As ttk::progressbar::start: nothing if already running, else step now and every interval.
        if self._progress_timer is not None:
            return
        self._progress_interval = 50 if interval is None else max(1, int(interval))
        self._auto_step()

    def _auto_step(self):
        self._progress_timer = None
        if self._alive:
            self._progress_timer = self.after(self._progress_interval, self._auto_step)
            self.step()

    def stop(self):
        # As ttk::progressbar::stop: the timer goes and the value returns to 0 (a bar
        # following a variable keeps the variable's value).
        if self._progress_timer is not None:
            self.after_cancel(self._progress_timer)
            self._progress_timer = None
        if self._value_variable is None and self._alive:
            self.configure(value=0)


class Scrollbar(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'scrollbar', cnf, kw)

    def set(self, first, last):
        self._call('control', self._id, 'set', (float(first), float(last)))

    def get(self):
        return tuple(self._call('control', self._id, 'get', ()))

    def identify(self, x, y):
        # 'arrow1', 'trough1', 'slider', 'trough2', 'arrow2', or '' (classic Tk's names).
        return self._call('control', self._id, 'identify', (x, y))

    def delta(self, deltax, deltay):
        return self._call('control', self._id, 'delta', (deltax, deltay))

    def fraction(self, x, y):
        return self._call('control', self._id, 'fraction', (x, y))


class Listbox(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'listbox', cnf, kw)

    def _list(self, action, *args):
        return self._call('listbox', self._id, action, args)

    def insert(self, index, *elements):
        # Bounded batches keep synchronized server calls short even for large logs.
        at = max(0, min(self.size(), self.index(index)))
        for start in range(0, len(elements), 256):
            batch = tuple(str(item) for item in elements[start:start + 256])
            self._list('insert', at, *batch)
            at += len(batch)

    def delete(self, first, last=None):
        self._list('delete', first, first if last is None else last)

    def get(self, first, last=None):
        if last is None:
            return self._list('get', first)
        # The host reads at most 256 rows per call; batch like insert() does, so
        # get(0, END) works on long lists. For get, END is the last row.
        size = self.size()
        a = max(0, size - 1 if first == END else self.index(first))
        b = min(size - 1, size - 1 if last == END else self.index(last))
        rows = []
        while a <= b:
            stop = min(b, a + 255)
            rows.extend(self._list('get', a, stop))
            a = stop + 1
        return tuple(rows)

    def size(self):
        return self._list('size')

    def index(self, index):
        return self._list('index', index)

    def curselection(self):
        return tuple(self._list('curselection'))

    def selection_set(self, first, last=None):
        self._list('selection_set', first, first if last is None else last)

    def selection_clear(self, first, last=None):
        self._list('selection_clear', first, first if last is None else last)

    def selection_includes(self, index):
        return self._list('selection_includes', index)

    def selection_anchor(self, index):
        self._list('selection_anchor', index)

    select_set = selection_set
    select_clear = selection_clear
    select_includes = selection_includes
    select_anchor = selection_anchor

    def activate(self, index):
        self._list('activate', index)

    def nearest(self, y):
        return self._list('nearest', y)

    def bbox(self, index):
        value = self._list('bbox', index)
        return None if value is None else tuple(value)

    def see(self, index):
        self._list('see', index)

    def xview(self, *args):
        value = self._list('xview', *args)
        return tuple(value) if not args else None

    def yview(self, *args):
        value = self._list('yview', *args)
        return tuple(value) if not args else None

    def xview_moveto(self, fraction):
        self.xview('moveto', float(fraction))

    def yview_moveto(self, fraction):
        self.yview('moveto', float(fraction))

    def xview_scroll(self, number, what):
        self.xview('scroll', number, what)

    def yview_scroll(self, number, what):
        self.yview('scroll', number, what)


_TEXT_TAG_OPTIONS = ('background', 'bgstipple', 'borderwidth', 'elide', 'fgstipple', 'font', 'foreground', 'justify',
                     'lmargin1', 'lmargin2', 'lmargincolor', 'offset', 'overstrike', 'overstrikefg', 'relief', 'rmargin',
                     'rmargincolor', 'selectbackground', 'selectforeground', 'spacing1', 'spacing2', 'spacing3', 'tabs',
                     'tabstyle', 'underline', 'underlinefg', 'wrap')
_TEXT_TAG_COLORS = ('background', 'foreground', 'underlinefg', 'overstrikefg', 'lmargincolor', 'rmargincolor',
                    'selectbackground', 'selectforeground')
# Options Tk's text takes that the display keeps but does not draw (fonts, spacing, tabs, the
# cursor's blinking): their defaults, as Tk 8.6 on X11 gives them.
_TEXT_KEPT = {'blockcursor': 0, 'cursor': 'xterm', 'endline': '', 'startline': '', 'font': 'TkFixedFont',
              'highlightbackground': '#d9d9d9', 'highlightcolor': '#000000', 'highlightthickness': 1,
              'insertborderwidth': 0, 'insertofftime': 300, 'insertontime': 600, 'insertunfocussed': 'none',
              'insertwidth': 2, 'selectborderwidth': 0, 'setgrid': 0, 'spacing1': 0, 'spacing2': 0, 'spacing3': 0,
              'tabs': '', 'tabstyle': 'tabular',
              'selectbackground': '#c3c3c3', 'selectforeground': '#000000',
              'inactiveselectbackground': '#c3c3c3', 'insertbackground': '#000000'}
_TEXT_KEYS = ('autoseparators', 'background', 'bd', 'bg', 'blockcursor', 'borderwidth', 'cursor', 'endline',
              'exportselection', 'fg', 'font', 'foreground', 'height', 'highlightbackground', 'highlightcolor',
              'highlightthickness', 'inactiveselectbackground', 'insertbackground', 'insertborderwidth',
              'insertofftime', 'insertontime', 'insertunfocussed', 'insertwidth', 'maxundo', 'padx', 'pady', 'relief',
              'selectbackground', 'selectborderwidth', 'selectforeground', 'setgrid', 'spacing1', 'spacing2',
              'spacing3', 'startline', 'state', 'tabs', 'tabstyle', 'takefocus', 'undo', 'width', 'wrap',
              'xscrollcommand', 'yscrollcommand')
# Events a tag binding can name: the mouse's (at the character under the pointer) and Enter and
# Leave (the pointer coming onto and off a tag's characters, seen as it moves).
_TEXT_TAG_EVENTS = ('<Button-1>', '<ButtonRelease-1>', '<Button-3>', '<ButtonRelease-3>', '<Motion>',
                    '<B1-Motion>', '<Double-Button-1>', '<Enter>', '<Leave>', '<MouseWheel>',
                    '<Key>', '<KeyRelease>', '<Return>', '<space>', '<Tab>', '<BackSpace>', '<Delete>',
                    '<Left>', '<Right>', '<Home>', '<End>', '<Up>', '<Down>', '<Prior>', '<Next>')


def _tcl_list(value):
    # A value as Tcl shows it: a tuple or list is a list (words with spaces in braces).
    if isinstance(value, (tuple, list)):
        words = []
        for item in value:
            item = str(item)
            words.append('{' + item + '}' if not item or ' ' in item else item)
        return ' '.join(words)
    return value


def _tag_names(tags):
    # An insert's tags: a name, a Tcl list of names ('a b'), or a sequence of names.
    if isinstance(tags, str):
        return tuple(tags.split())
    return tuple(str(tag) for tag in tags)


class Text(Widget):
    """Tk's text widget: indices (line.char, marks, tag.first, @x,y, end, with Tk's modifiers),
    marks with gravity, tags with ranges and options (their colours, underline and overstrike
    are drawn), tag bindings, search, count, dump and the undo stack, as Tk 8.6. Fonts, spacing,
    tabs, margins, elide, embedded images and windows are not drawn: their options are kept."""

    def __init__(self, master=None, cnf=None, **kw):
        self._tag_handlers = {}   # tag -> {sequence: _Handlers}
        self._tag_commands = set()
        self._tag_seen = ()       # the tags under the pointer, for <Enter> and <Leave>
        self._init(master, 'text', cnf, kw)
        self._notify_id = self._root._register(self._notified)
        self._extra_callbacks['notify'] = (self._notify_id, self._notified)
        self._call('configure', self._id, {'notify': self._notify_id})

    def _text(self, action, *args):
        return self._call('text', self._id, action, args)

    def _notified(self, kind, *args):
        # The display's notices: the modified flag changed, a player changed the selection.
        try:
            if kind == 'modified':
                self.event_generate('<<Modified>>')
            elif kind == 'selection':
                self.event_generate('<<Selection>>')
            elif kind == 'leave':
                self._tag_event('<Leave>', self._event('<Leave>', {'x': -1, 'y': -1}))
        except Exception as error:
            print('Tk callback error:', error)

    # ---- options ------------------------------------------------------------------------------

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        server = {}
        for key in list(options):
            if key in ('undo', 'autoseparators'):
                server[key] = _tcl_boolean(options.pop(key))
            elif key == 'maxundo':
                server[key] = int(options.pop(key))
        for key in ('inactiveselectbackground', 'insertbackground'):
            if key in options:
                value = options.pop(key)
                server[key] = _color(value)
                self._keep(key, value)
        sel = {}
        for key, tag_key in (('selectbackground', 'background'), ('selectforeground', 'foreground')):
            if key in options:
                value = options.pop(key)
                self._keep(key, value)
                sel[tag_key] = value
        for key in list(options):
            if key in _TEXT_KEPT:
                self._keep(key, _tcl_list(options.pop(key)))
        if server:
            self._call('configure', self._id, server)
        if sel:
            # -selectbackground and -selectforeground are the sel tag's colours (as Tk).
            self.tag_configure('sel', sel)
        if options or cnf is None and not kw:
            Widget.configure(self, options)

    config = configure

    def cget(self, key):
        key = _aliases.get(key, key)
        if key in _TEXT_KEPT and key not in getattr(self, '_kept', ()):
            return _TEXT_KEPT[key]
        return Widget.cget(self, key)

    def keys(self):
        return list(_TEXT_KEYS)

    # ---- the text -----------------------------------------------------------------------------

    def index(self, index):
        return self._text('index', str(index))

    def get(self, index1, index2=None):
        if index2 is None:
            return self._text('get', str(index1))
        return self._text('get', str(index1), str(index2))

    def _pieces(self, chars, args):
        # chars tags chars tags ...: each tag list as names, False where none was given.
        pieces = (chars,) + tuple(args)
        out = []
        for i in range(0, len(pieces), 2):
            out.append(str(pieces[i]))
            out.append(_tag_names(pieces[i + 1]) if i + 1 < len(pieces) else False)
        return out

    def insert(self, index, chars, *args):
        self._text('insert', str(index), *self._pieces(chars, args))

    def replace(self, index1, index2, chars, *args):
        self._text('replace', str(index1), str(index2), *self._pieces(chars, args))

    def delete(self, index1, index2=None):
        if index2 is None:
            self._text('delete', str(index1))
        else:
            self._text('delete', str(index1), str(index2))

    def compare(self, index1, op, index2):
        return self._text('compare', str(index1), op, str(index2))

    def see(self, index):
        self._text('see', str(index))

    def bbox(self, index):
        box = self._text('bbox', str(index))
        return None if box is None else tuple(box)

    def dlineinfo(self, index):
        box = self._text('dlineinfo', str(index))
        return None if box is None else tuple(box)

    def count(self, index1, index2, *args):
        # As CPython 3.12: a 1-tuple for one count, None for a count of 0.
        counts = tuple(self._text('count', str(index1), str(index2), *[str(a).lstrip('-') for a in args]))
        if len(counts) == 1:
            return counts if counts[0] else None
        return counts

    def search(self, pattern, index, stopindex=None, forwards=None, backwards=None, exact=None,
               regexp=None, nocase=None, count=None, elide=None):
        found = self._text('search', str(pattern), str(index), '' if stopindex is None else str(stopindex),
                           bool(backwards), bool(regexp), bool(nocase))
        if not found:
            return ''
        if count is not None:
            count.set(found[1])
        return found[0]

    def dump(self, index1, index2=None, command=None, **kw):
        kinds = [key for key in ('all', 'image', 'mark', 'tag', 'text', 'window') if kw.pop(key, False)]
        if kw:
            raise TclError('bad option "-' + sorted(kw)[0] + '": must be -all, -command, -image, -mark, -tag, -text, or -window')
        every = not kinds or 'all' in kinds
        rows = self._text('dump', str(index1), '' if not index2 else str(index2),
                          every or 'text' in kinds, every or 'mark' in kinds, every or 'tag' in kinds)
        result = [tuple(row) for row in rows]
        if command is None:
            return result
        if isinstance(command, str):
            command = getattr(self._root, '_commands', {}).get(command)   # a name register() gave
            if command is None:
                raise TclError('invalid command name')
        for row in result:
            command(*row)
        return None

    def selection_get(self):
        return self._text('selection_get')

    # ---- marks ------------------------------------------------------------------------------

    def mark_set(self, markName, index):
        self._text('mark_set', str(markName), str(index))

    def mark_unset(self, *markNames):
        self._text('mark_unset', *[str(name) for name in markNames])

    def mark_names(self):
        return tuple(self._text('mark_names'))

    def mark_gravity(self, markName, direction=None):
        if direction is None:
            return self._text('mark_gravity', str(markName))
        self._text('mark_gravity', str(markName), str(direction))

    def mark_next(self, index):
        return self._text('mark_next', str(index))

    def mark_previous(self, index):
        return self._text('mark_previous', str(index))

    # ---- tags -------------------------------------------------------------------------------

    def tag_add(self, tagName, index1, *args):
        self._text('tag_add', str(tagName), str(index1), *[str(a) for a in args])

    def tag_remove(self, tagName, index1, index2=None):
        if index2 is None:
            self._text('tag_remove', str(tagName), str(index1))
        else:
            self._text('tag_remove', str(tagName), str(index1), str(index2))

    def tag_delete(self, *tagNames):
        self._text('tag_delete', *[str(name) for name in tagNames])
        for name in tagNames:
            # A deleted tag's bindings go with it (the sel tag's stay, as the tag does).
            if str(name) != 'sel':
                for handlers in self._tag_handlers.pop(str(name), {}).values():
                    for funcid, func in handlers.entries:
                        self._root._delete_command(funcid)

    def tag_names(self, index=None):
        if index is None:
            return tuple(self._text('tag_names'))
        return tuple(self._text('tag_names', str(index)))

    def tag_ranges(self, tagName):
        return tuple(self._text('tag_ranges', str(tagName)))

    def tag_nextrange(self, tagName, index1, index2=None):
        args = (str(index1),) if index2 is None else (str(index1), str(index2))
        return tuple(self._text('tag_nextrange', str(tagName), *args))

    def tag_prevrange(self, tagName, index1, index2=None):
        args = (str(index1),) if index2 is None else (str(index1), str(index2))
        return tuple(self._text('tag_prevrange', str(tagName), *args))

    def tag_raise(self, tagName, aboveThis=None):
        if aboveThis is None:
            self._text('tag_raise', str(tagName))
        else:
            self._text('tag_raise', str(tagName), str(aboveThis))

    def tag_lower(self, tagName, belowThis=None):
        if belowThis is None:
            self._text('tag_lower', str(tagName))
        else:
            self._text('tag_lower', str(tagName), str(belowThis))

    def _tag_options(self, tagName, options):
        flat = self._text('tag_configure', str(tagName), options)
        return dict((flat[i], flat[i + 1]) for i in range(0, len(flat), 2))

    def tag_configure(self, tagName, cnf=None, **kw):
        if isinstance(cnf, str) and not kw:
            key = cnf
            if key not in _TEXT_TAG_OPTIONS:
                raise TclError('unknown option "-' + key + '"')
            return (key, '', '', '', self._tag_options(tagName, {}).get(key, ''))
        # Widget._options converts colours to integers; tags must retain the Tcl name.
        options = {} if cnf is None else dict(cnf)
        options.update(kw)
        if not options:
            current = self._tag_options(tagName, {})
            return dict((key, (key, '', '', '', current.get(key, ''))) for key in _TEXT_TAG_OPTIONS)
        server = {}
        for key, value in options.items():
            if key not in _TEXT_TAG_OPTIONS:
                raise TclError('unknown option "-' + key + '"')
            if value is None:
                continue
            if key in _TEXT_TAG_COLORS and value != '':
                try:
                    value = (str(value), _color(value))
                except ValueError:
                    raise TclError('unknown color name "' + str(value) + '"')
            elif isinstance(value, (tuple, list)):
                value = _tcl_list(value)
            server[key] = value
        self._tag_options(tagName, server)

    tag_config = tag_configure

    def tag_cget(self, tagName, option):
        return self._text('tag_cget', str(tagName), str(option).lstrip('-'))

    # ---- tag bindings -----------------------------------------------------------------------

    def tag_bind(self, tagName, sequence=None, func=None, add=None):
        # Run here: the widget's binding for the event finds the tags at the character under
        # the pointer and runs theirs, lowest priority first (Tk's order).
        self._tag_options(str(tagName), {})  # Binding/querying creates the tag, as in Tk.
        if sequence is None:
            return tuple(self._tag_handlers.get(str(tagName), {}))
        sequence = _event_aliases.get(sequence, sequence)
        if sequence not in _TEXT_TAG_EVENTS:
            raise ValueError('unsupported tag event: ' + str(sequence))
        bound = self._tag_handlers.setdefault(str(tagName), {})
        if func is None:
            handlers = bound.get(sequence)
            return '' if handlers is None or not handlers.entries else ' '.join(entry[0] for entry in handlers.entries)
        if not callable(func):
            raise TypeError('binding must be callable')
        handlers = bound.get(sequence)
        if handlers is None:
            handlers = bound[sequence] = _Handlers()
        funcid = handlers.add(self._root, func, add)
        self._tag_commands.add(funcid)
        self._listen('<Motion>' if sequence in ('<Enter>', '<Leave>') else sequence)
        return funcid

    def tag_unbind(self, tagName, sequence, funcid=None):
        sequence = _event_aliases.get(sequence, sequence)
        handlers = self._tag_handlers.get(str(tagName), {}).get(sequence)
        if handlers is None:
            return
        if funcid is None:
            for entry in handlers.entries:
                self._root._delete_command(entry[0])
            del self._tag_handlers[str(tagName)][sequence]
        else:
            handlers.remove(funcid)
            self._root._delete_command(str(funcid))

    def _listen(self, sequence):
        # The widget's own handlers for sequence, with the tag dispatch run before them.
        handlers = self._handlers(sequence)
        if handlers is None:
            handlers = _Handlers()
            callback = self._root._register(handlers)
            try:
                self._call('bind', self._id, sequence, callback)
            except Exception:
                self._root._callbacks.pop(callback, None)
                raise
            self._bindings[sequence] = callback
        handlers.before = lambda event: self._tag_event(sequence, event)

    def unbind(self, sequence, funcid=None):
        Widget.unbind(self, sequence, funcid)
        sequence = _event_aliases.get(sequence, sequence)
        for bound in self._tag_handlers.values():
            if sequence in bound or sequence == '<Motion>' and ('<Enter>' in bound or '<Leave>' in bound):
                self._listen(sequence)   # the tags' bindings stay
                break

    def _tag_event(self, sequence, event):
        try:
            at = self.index('current' if sequence.startswith('<Key') or sequence in _key_sequences else '@%d,%d' % (event.x, event.y))
            here = () if sequence == '<Leave>' else self.tag_names(at)
        except TclError:
            return None
        calls = []
        if sequence in ('<Motion>', '<Leave>'):
            for tag in self._tag_seen:
                if tag not in here:
                    calls.append((tag, '<Leave>'))
            for tag in here:
                if tag not in self._tag_seen:
                    calls.append((tag, '<Enter>'))
            self._tag_seen = here
        calls.extend((tag, sequence) for tag in here)
        for tag, seq in calls:
            handlers = self._tag_handlers.get(tag, {}).get(seq)
            if handlers is not None:
                previous_type = event.type
                event.type = seq
                try:
                    if handlers(event) == 'break':
                        return 'break'
                finally:
                    event.type = previous_type
        return None

    def _detach(self):
        for funcid in self._tag_commands:
            if funcid in self._root._commands:
                self._root._delete_command(funcid)
        self._tag_commands.clear()
        self._tag_handlers.clear()
        self._tag_seen = ()
        Widget._detach(self)

    # ---- the undo stack ---------------------------------------------------------------------

    def edit(self, *args):
        if not args:
            raise TclError('wrong # args: should be ".text edit option ?arg ...?"')
        option, rest = args[0], args[1:]
        if option == 'modified':
            return self.edit_modified(*rest)
        if option in ('undo', 'redo', 'reset', 'separator') and not rest:
            return getattr(self, 'edit_' + option)()
        raise TclError('bad edit option "' + str(option) + '": must be canundo, canredo, modified, redo, reset, separator, or undo')

    def edit_modified(self, arg=None):
        if arg is None:
            return self._text('edit_modified')
        self._text('edit_modified', _tcl_boolean(arg))

    def edit_undo(self):
        self._text('edit_undo')
        return ''

    def edit_redo(self):
        self._text('edit_redo')
        return ''

    def edit_reset(self):
        self._text('edit_reset')
        return ''

    def edit_separator(self):
        self._text('edit_separator')
        return ''

    # ---- the view ---------------------------------------------------------------------------

    def xview(self, *args):
        result = self._text('xview', *args)
        return tuple(result) if not args else result

    def yview(self, *args):
        result = self._text('yview', *args)
        return tuple(result) if not args else result

    def xview_moveto(self, fraction):
        self.xview('moveto', fraction)

    def yview_moveto(self, fraction):
        self.yview('moveto', fraction)

    def xview_scroll(self, number, what):
        self.xview('scroll', number, what)

    def yview_scroll(self, number, what):
        self.yview('scroll', number, what)

    def yview_pickplace(self, *what):
        self.see(what[-1])

    # Embedded images and windows, peers and the debug switch: not on this display.
    def _unsupported(self, *args, **kw):
        raise TclError('embedded images, windows and peers are not supported by this Tk display')

    image_create = image_cget = image_configure = image_names = _unsupported
    window_create = window_cget = window_configure = window_config = window_names = _unsupported
    peer_create = peer_names = _unsupported


class _EntryBase(Widget):
    """What Entry and Spinbox (and ttk's Entry, Spinbox and Combobox) share: a line of text that
    a player edits on the display, -show, -justify and -validate.

    Validation runs here as Tk 8.6 runs it (classic: tkEntry.c, ttk: ttkEntry.c): a program's
    change before it is made, a player's before the display makes it (the display waits for the
    answer, and the player's further input waits behind it), focus going in and out, and for a
    classic widget a value given from outside ('forced': its variable, a step)."""
    _kind = 'entry'
    _spinbox = False

    def __init__(self, master=None, cnf=None, **kw):
        self._entry_state()
        self._init(master, self._kind, cnf, kw)

    def _entry_state(self):
        self._validate = 'none'
        self._vcmd = None             # (function or None, its words, the text cget gives)
        self._invcmd = None
        self._validating = False
        self._changed_inside = False  # the value changed while -validatecommand ran
        self._notify_id = 0
        self._spin_command = None
        self._values_spec = ''
        self._numbers = {'from': 0, 'to': 0, 'increment': 1}

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if 'justify' in options:
            options['justify'] = _choice(options['justify'], ('left', 'right', 'center'), 'justification')
        if 'show' in options:
            options['show'] = '' if options['show'] is None else str(options['show'])
        mode, vcmd, invcmd = self._validate, self._vcmd, self._invcmd
        if 'validate' in options:
            mode = _choice(options.pop('validate'), ('all', 'key', 'focus', 'focusin', 'focusout', 'none'), 'validate')
        if 'validatecommand' in options:
            vcmd = self._command_spec(options.pop('validatecommand'))
        if 'invalidcommand' in options:
            invcmd = self._command_spec(options.pop('invalidcommand'))
        options['validate'] = mode if vcmd is not None else 'none'
        if self._notify_id == 0 and (vcmd is not None or self._kind != 'entry'):
            # The display's notices for this widget (validation, focus, steps, its list) come here.
            self._notify_id = self._root._register(self._notified)
            self._extra_callbacks['notify'] = (self._notify_id, self._notified)
            options['notify'] = self._notify_id
        spin = self._spin_options(options) if self._spinbox else None
        # Classic Tk validates a value its new variable or a Spinbox's new range gives it ('forced').
        before = None
        if not self._ttk and ('textvariable' in options and vcmd is not None and mode != 'none'
                              or spin and any(key in options for key in ('values', 'from', 'to', 'format'))):
            before = self.get()
        Widget.configure(self, options)
        self._validate, self._vcmd, self._invcmd = mode, vcmd, invcmd
        if spin:
            if 'command' in spin:
                self._spin_command = spin['command']
            if 'values' in spin:
                self._values_spec = spin['values']
            for key in ('from', 'to', 'increment'):
                if key in spin:
                    self._numbers[key] = spin[key]
        if before is not None:
            after = self.get()
            if after != before:
                self._forced(before, after)
                if spin:
                    self._sync_variable(after)

    config = configure

    def cget(self, key):
        key = _aliases.get(key, key)
        if key == 'validate':
            return self._validate
        if key in ('validatecommand', 'invalidcommand'):
            spec = self._vcmd if key == 'validatecommand' else self._invcmd
            return spec[2] if spec is not None else ''
        if self._spinbox:
            if key == 'command':
                return self._spin_command if self._spin_command is not None else ''
            if key == 'values':
                spec = self._values_spec
                if not self._ttk:
                    return spec if isinstance(spec, str) else _tcl_join(spec)
                items = tuple(_tcl_split(spec)) if isinstance(spec, str) else tuple(spec)
                return items if items else ''
            if key in self._numbers and self._ttk:
                return self._numbers[key]   # ttk gives back the number as it was given
        return Widget.cget(self, key)

    def _command_spec(self, value):
        # -validatecommand, -invalidcommand: a function (called with no arguments), or a name from
        # register() (a function will do) with its words, as a tuple or one string; None or '' for
        # none. Kept as (function or None, words, the text cget gives).
        if value is None or value == '' or value == ():
            return None
        root = self._root
        if callable(value):
            return (value, (), root._add_command(value))
        words = _tcl_split(value) if isinstance(value, str) else list(value)
        if not words:
            return None
        if callable(words[0]):
            func, name = words[0], root._add_command(words[0])
        else:
            name = str(words[0])
            func = getattr(root, '_commands', {}).get(name)
        args = tuple(str(word) for word in words[1:])
        return (func, args, _tcl_join((name,) + args))

    def _spin_options(self, options):
        # A Spinbox's options kept here (-command, ttk's numbers as given); the rest go on to the display.
        commit = {}
        if 'command' in options:
            command = options.pop('command')
            if command == '':
                command = None
            if command is not None and not callable(command):
                raise TypeError('command must be callable')
            commit['command'] = command
        for key in ('from', 'to', 'increment'):
            if key in options:
                value = options[key]
                number = value if isinstance(value, (int, float)) and not isinstance(value, bool) else _tcl_number(value)
                if number is None:
                    raise TclError('expected floating-point number but got "' + str(value) + '"')
                commit[key] = number
                options[key] = float(number)
        if 'values' in options:
            spec = options['values']
            spec = '' if spec is None else spec
            options['values'] = _tcl_split(spec) if isinstance(spec, str) else [str(item) for item in spec]
            commit['values'] = spec
        if 'wrap' in options:
            options['wrap'] = _flag(options['wrap'])
        if 'format' in options:
            options['format'] = str(options['format'])
        return commit

    # ---- -validate ------------------------------------------------------------------------

    def _needs(self, reason):
        # Whether -validate asks for this validation (Tk's EntryNeedsValidation): a 'forced' value is
        # validated by ttk always, by classic Tk unless -validate is none.
        mode = self._validate
        if reason == 'forced':
            return self._ttk or mode != 'none'
        if mode == 'all':
            return True
        if reason == 'key':
            return mode == 'key'
        return mode in ('focus', reason)

    def _set_validation(self, mode):
        # Tk turning validation off: here and on the display, which stops holding the player's changes.
        self._validate = mode
        if self._alive:
            self._call('configure', self._id, {'validate': mode if self._vcmd is not None else 'none'})

    def _call_validation(self, spec, subs):
        # -validatecommand or -invalidcommand with its %-substitutions: what it returns. A Python
        # error is reported and gives None, as tkinter gives Tcl; an unknown name is Tcl's error.
        if spec[0] is None:
            raise TclError('invalid command name "' + _tcl_split(spec[2])[0] + '"')
        try:
            return spec[0](*[_percent(word, subs) for word in spec[1]])
        except Exception as error:
            print('Tk callback error:', error)
            return None

    def _check(self, reason, action, index, before, after, change):
        # Tk's EntryValidateChange: whether the change may be made (reason: key, forced, focusin,
        # focusout). -invalidcommand runs when it may not. Errors turn validation off; a ttk
        # widget's are raised then (classic Tk reports them in the background).
        if self._vcmd is None or not self._needs(reason):
            return True
        if self._validating:
            # A change made while -validatecommand runs goes through: ttk then rejects the change
            # being validated; classic Tk stops validating, which rejects it too.
            if not self._ttk:
                self._set_validation('none')
            return True
        subs = {'d': str(action), 'i': str(index), 'P': after, 's': before, 'S': change,
                'v': self._validate, 'V': reason, 'W': str(self)}
        self._validating = True
        self._changed_inside = False
        error = None
        try:
            try:
                ok = _tcl_boolean(self._call_validation(self._vcmd, subs))
            except TclError as failure:
                ok, error = False, failure
        finally:
            self._validating = False
        if not self._alive:
            return False
        if self._ttk:
            if error is not None:
                self._set_validation('none')
                raise error
            if not ok and self._invcmd is not None:
                try:
                    self._call_validation(self._invcmd, subs)
                except TclError:
                    self._set_validation('none')
                    raise
            return ok and not self._changed_inside
        if error is not None or self._validate == 'none':
            self._set_validation('none')
            return False
        if not ok:
            if reason == 'forced':
                self._set_validation('none')   # the value has precedence: it goes in, validation stops
            elif self._invcmd is not None:
                try:
                    self._call_validation(self._invcmd, subs)
                except TclError:
                    self._set_validation('none')
            return False
        return True

    def _forced(self, before, after):
        # Classic Tk validates a value it is given from outside ('forced': its variable, a step): the
        # value goes in whatever the answer; a rejection turns validation off.
        if not self._ttk and before != after:
            self._check('forced', -1, -1, before, after, '')

    def _key_validates(self):
        return self._vcmd is not None and self._validate in ('key', 'all')

    def _editable(self):
        return not self._call('flags', self._id) & 3   # neither disabled nor readonly

    def _sync_variable(self, value):
        if self._variable is not None:
            self._variable._set(value, self)

    def _variable_written(self, value):
        # The -textvariable has a new value: it goes in ('forced' validation first, classic).
        text = str(value)
        if self._validating:
            self._changed_inside = True
        if not self._ttk and self._vcmd is not None and self._validate != 'none':
            self._forced(self.get(), text)
        self._call('configure', self._id, {'text': text})

    def _revalidated(self, ok):
        # ttk keeps the outcome of focus validation and validate() in its 'invalid' state.
        if self._ttk:
            if ok:
                self._held.discard('invalid')
            else:
                self._held.add('invalid')
            self._sync_held()

    def _notified(self, kind, *args):
        # The display's notices (TkTree.notice), in order: a player's change waiting for validation,
        # focus in or out, a Spinbox step, a Combobox's list.
        if kind == 'validate':
            seq, action, index, before, after, change = args
            try:
                ok = self._check('key', action, index, before, after, change)
            except TclError as error:
                print('Tk callback error:', error)
                ok = False
            if self._alive:
                value = self._call('entry', self._id, 'validated', seq, bool(ok))
                if value != before:
                    self._sync_variable(value)
        elif kind in ('focusin', 'focusout'):
            if self._vcmd is not None and self._needs(kind):
                try:
                    ok = self._check(kind, -1, -1, args[0], args[0], '')
                except TclError as error:
                    print('Tk callback error:', error)
                    return
                self._revalidated(ok)
        elif kind == 'stepped':
            self._stepped(args[0], args[1], args[2], True)
        elif kind == 'step':
            self.event_generate('<<Increment>>' if args[0] > 0 else '<<Decrement>>')
        else:
            self._notice(kind, args)

    def _notice(self, kind, args):
        pass

    # ---- Spinbox steps ----------------------------------------------------------------------

    def _step(self, direction):
        # One step up (1) or down (-1) on the display; nothing while disabled.
        result = self._call('control', self._id, 'step', (direction,))
        if result is not None:
            self._stepped(direction, result[0], result[1], False)

    def _stepped(self, direction, before, after, player):
        # After a step: classic Tk validates the new value ('forced'), the variable follows, -command
        # runs. A ttk step a player made also reaches the window's <<Increment>>/<<Decrement>>
        # binding (the widget's own would have run before it: see ttk.Spinbox).
        self._forced(before, after)
        self._sync_variable(after)
        if self._spin_command is not None:
            self._spin_command()
        if player and self._ttk and self._alive:
            top = self.winfo_toplevel()
            if top is not self and top._alive:
                handlers = top._handlers('<<Increment>>' if direction > 0 else '<<Decrement>>')
                if handlers is not None:
                    handlers(self._event('<<Increment>>' if direction > 0 else '<<Decrement>>', {}))

    def get(self):
        return self._call('entry', self._id, 'get')

    def index(self, index):
        return self._call('entry', self._id, 'index', index)

    def icursor(self, index):
        self._call('entry', self._id, 'icursor', index)

    def selection_present(self):
        return self._call('entry', self._id, 'selection_present')

    def selection_get(self):
        return self._call('entry', self._id, 'selection_get')

    def selection_clear(self):
        self._call('entry', self._id, 'selection_clear')

    def selection_range(self, first, last):
        self._call('entry', self._id, 'selection_range', first, last)

    def selection_from(self, index):
        self._call('entry', self._id, 'selection_from', index)

    def selection_to(self, index):
        self._call('entry', self._id, 'selection_to', index)

    def selection_adjust(self, index):
        self._call('entry', self._id, 'selection_adjust', index)

    select_present = selection_present
    select_clear = selection_clear
    select_range = selection_range
    select_from = selection_from
    select_to = selection_to
    select_adjust = selection_adjust

    def xview(self):
        return self._call('entry', self._id, 'xview')

    def xview_moveto(self, fraction):
        self._call('entry', self._id, 'xview_moveto', fraction)

    def xview_scroll(self, number, what):
        self._call('entry', self._id, 'xview_scroll', number, what)

    def insert(self, index, string):
        string = str(string)
        if string and self._key_validates():
            # Validated before it is made, at the index as it is now (Tk's InsertChars).
            at = self.index(index)
            if not self._editable():
                return
            before = self.get()
            if not self._check('key', 1, at, before, before[:at] + string + before[at:], string):
                return
            index = at
        if self._validating:
            self._changed_inside = True
        self._sync_variable(self._call('entry', self._id, 'insert', index, string))

    def delete(self, first, last=None):
        if self._key_validates():
            # Validated before it is made (Tk's DeleteChars): nothing to delete, nothing to validate.
            first = self.index(first)
            last = first + 1 if last is None else self.index(last)
            before = self.get()
            count = min(last, len(before)) - first
            if count <= 0 or not self._editable():
                return
            last = first + count
            if not self._check('key', 0, first, before, before[:first] + before[last:], before[first:last]):
                return
        elif last is None:
            first = self.index(first)
            last = first + 1
        if self._validating:
            self._changed_inside = True
        self._sync_variable(self._call('entry', self._id, 'delete', first, last))


class Entry(_EntryBase):
    """One line of text the player edits on the display (-show masks it, -justify places it,
    -validate checks each change, as in Tk)."""


class Spinbox(_EntryBase):
    """An Entry with two arrows that step its value (Up and Down too): through -values, or from
    -from to -to by -increment, formatted by -format (or as Tk computes one); -wrap goes round.
    -command runs after each step. As in Tk, the arrows step on release and do not take the
    keyboard. Holding an arrow steps once (no repeat)."""
    _kind = 'spinbox'
    _spinbox = True

    def invoke(self, element):
        """Step as a click on that arrow does: 'buttonup' or 'buttondown' ('none' does nothing)."""
        element = _choice(element, ('none', 'buttondown', 'buttonup'), 'element')
        if element != 'none':
            self._step(1 if element == 'buttonup' else -1)
        return ''

    def identify(self, x, y):
        """The element at (x, y): 'buttonup', 'buttondown', 'entry', or '' outside."""
        return self._call('control', self._id, 'identify', (int(x), int(y)))

    def selection_element(self, element=None):
        """The arrow drawn selected ('none'), or set it; recorded only."""
        if element is None:
            return getattr(self, '_selected_element', 'none')
        self._selected_element = _choice(element, ('none', 'buttondown', 'buttonup'), 'element')
        return ''

    def selection(self, *args):
        if not args:
            raise TclError('wrong # args: should be ".spinbox selection option ?index?"')
        option = _choice(args[0], ('adjust', 'clear', 'element', 'from', 'present', 'range', 'to'), 'selection option')
        if option == 'element':
            return self.selection_element(*args[1:])
        if option == 'present':
            return self.selection_present()
        return getattr(self, 'selection_' + option)(*args[1:])

    def _class_event(self, sequence):
        # The Spinbox class binding: Up and Down step, as the arrows do.
        if sequence in ('<Up>', '<Down>'):
            self.invoke('buttonup' if sequence == '<Up>' else 'buttondown')


# ---- menus ---------------------------------------------------------------------------------
# Tk's menu entries: their types, the options each takes (Tk 8.6's, measured), and defaults.
_MENU_TYPES = ('cascade', 'checkbutton', 'command', 'radiobutton', 'separator')
_ENTRY_COMMON = ('activebackground', 'activeforeground', 'accelerator', 'background', 'bitmap', 'columnbreak', 'command',
                 'compound', 'font', 'foreground', 'hidemargin', 'image', 'label', 'state', 'underline')
_ENTRY_OPTIONS = {
    'command': _ENTRY_COMMON,
    'cascade': ('menu',) + _ENTRY_COMMON,
    'checkbutton': ('indicatoron', 'offvalue', 'onvalue', 'selectcolor', 'selectimage', 'variable') + _ENTRY_COMMON,
    'radiobutton': ('indicatoron', 'selectcolor', 'selectimage', 'value', 'variable') + _ENTRY_COMMON,
    'separator': ('background',),
    'tearoff': ('background', 'state'),
}
_ENTRY_DEFAULTS = {'columnbreak': 0, 'compound': 'none', 'hidemargin': 0, 'state': 'normal', 'underline': -1,
                   'indicatoron': 1, 'onvalue': '1', 'offvalue': '0'}
# The menu's own options: (database name, class, default).
_MENU_OPTIONS = {
    'activebackground': ('activeBackground', 'Foreground', '#ececec'),
    'activeborderwidth': ('activeBorderWidth', 'BorderWidth', 1),
    'activeforeground': ('activeForeground', 'Background', '#000000'),
    'background': ('background', 'Background', '#d9d9d9'),
    'borderwidth': ('borderWidth', 'BorderWidth', 1),
    'cursor': ('cursor', 'Cursor', 'arrow'),
    'disabledforeground': ('disabledForeground', 'DisabledForeground', '#a3a3a3'),
    'font': ('font', 'Font', 'TkMenuFont'),
    'foreground': ('foreground', 'Foreground', '#000000'),
    'postcommand': ('postCommand', 'Command', ''),
    'relief': ('relief', 'Relief', 'raised'),
    'selectcolor': ('selectColor', 'Background', '#000000'),
    'takefocus': ('takeFocus', 'TakeFocus', 0),
    'tearoff': ('tearOff', 'TearOff', 1),
    'tearoffcommand': ('tearOffCommand', 'TearOffCommand', ''),
    'title': ('title', 'Title', ''),
    'type': ('type', 'Type', 'normal'),
}
_MENU_COLORS = ('activebackground', 'activeforeground', 'background', 'disabledforeground', 'foreground', 'selectcolor')


def _string_match(pattern, text):
    # Tcl's string match: * any run, ? one character, [chars] or [a-z] one of them, \x x itself.
    p = t = 0
    star = -1
    mark = 0
    while t < len(text):
        if p < len(pattern) and pattern[p] == '*':
            star, mark = p, t
            p += 1
            continue
        if p < len(pattern):
            c = pattern[p]
            if c == '?':
                p += 1
                t += 1
                continue
            if c == '[':
                end = pattern.find(']', p + 1)
                if end > p:
                    body, ok, i = pattern[p + 1:end], False, 0
                    while i < len(body):
                        if i + 2 < len(body) and body[i + 1] == '-':
                            ok = ok or body[i] <= text[t] <= body[i + 2] or body[i + 2] <= text[t] <= body[i]
                            i += 3
                        else:
                            ok = ok or body[i] == text[t]
                            i += 1
                    if ok:
                        p = end + 1
                        t += 1
                        continue
            else:
                if c == '\\' and p + 1 < len(pattern):
                    p += 1
                    c = pattern[p]
                if c == text[t]:
                    p += 1
                    t += 1
                    continue
        if star < 0:
            return False
        p = star + 1
        mark += 1
        t = mark
    while p < len(pattern) and pattern[p] == '*':
        p += 1
    return p == len(pattern)


def _menu_variables(root):
    # Tcl global variables a menu entry names by string (a checkbutton's label, 'selectedButton'):
    # per root, as each Tk() is its own interpreter in CPython.
    try:
        return root._menu_vars
    except AttributeError:
        root._menu_vars = {}
        return root._menu_vars


class Menu(Widget):
    """Tk's menu: command, checkbutton, radiobutton, cascade and separator entries, and the
    tearoff line first unless tearoff=0. A window shows one as its menubar (configure(menu=...)),
    a Menubutton posts its own, post() and tk_popup() post one anywhere; a cascade's menu drops
    out to the side. The player chooses with the mouse or the keys (F10 opens the menubar);
    invoke() runs an entry as a choice does: a checkbutton toggles its variable (onvalue/
    offvalue), a radiobutton sets its variable to its value, then the entry's command runs.
    Index forms: a number, 'end'/'last', 'active', 'none', '@y', or a pattern for a label.
    Not drawn: fonts, images, bitmaps, columnbreak; the tearoff line does nothing when chosen.
    """
    _window = True     # a top-level window in Tk: destroyed with its master, never packed

    def __init__(self, master=None, cnf=None, **kw):
        if master is None:
            master = _default()
        frame = master
        while frame is not None and not isinstance(frame, (Tk, Frame)):
            frame = frame.master    # a Menu's or a Menubutton's menu: the display keeps it in their frame
        if frame is None or not master._alive:
            raise ValueError('master must be a live widget')
        self.master = master
        self._root = master._root
        self._alive = True
        self._children = []
        self._variable = None
        self._command = None
        self._command_id = 0
        self._bindings = {}
        self._extra_callbacks = {}
        options = _options(cnf, kw)
        name = options.pop('name', None)
        self._menu_options = dict((key, spec[2]) for key, spec in _MENU_OPTIONS.items())
        self._entries = []
        self._listening = []
        self._id = self._call('create', 'menu', frame._id, {})
        master._children.append(self)
        self._root._widgets[self._id] = self
        _child_name(self, master, name)
        self._notify_id = self._root._register(self._notified)
        self._extra_callbacks['notify'] = (self._notify_id, self._notified)
        try:
            self._call('configure', self._id, {'notify': self._notify_id})
            tearoff = options.pop('tearoff', 1)
            self._menu_options['tearoff'] = 1 if _tcl_boolean(tearoff) else 0
            if self._menu_options['tearoff']:
                self._entries.append({'type': 'tearoff'})
            self.configure(options)
            self._sync()
        except Exception:
            self.destroy()
            raise

    # ---- the menu's options -------------------------------------------------------------------

    def configure(self, cnf=None, **kw):
        if isinstance(cnf, str) and not kw:
            key = _aliases.get(cnf, cnf)
            if key not in _MENU_OPTIONS:
                raise TclError('unknown option "-' + cnf + '"')
            spec = _MENU_OPTIONS[key]
            return (key, spec[0], spec[1], spec[2], self._menu_options[key])
        options = _options(cnf, kw)
        if not options and cnf is None:
            result = {}
            for key, spec in _MENU_OPTIONS.items():
                result[key] = (key, spec[0], spec[1], spec[2], self._menu_options[key])
            for alias, key in (('bd', 'borderwidth'), ('bg', 'background'), ('fg', 'foreground')):
                result[alias] = (alias, '-' + key)
            return result
        for key in options:
            if key not in _MENU_OPTIONS:
                raise TclError('unknown option "-' + key + '"')
        if 'type' in options:
            _choice(options['type'], ('menubar', 'normal', 'tearoff'), 'type')
        if 'postcommand' in options and options['postcommand'] not in (None, '') and not callable(options['postcommand']):
            raise TypeError('postcommand must be callable')
        server = {}
        for key in _MENU_COLORS:
            if key in options:
                server[key] = _color(options[key])
        if 'postcommand' in options:
            server['postcommand'] = options['postcommand'] not in (None, '')
        if server:
            self._call('configure', self._id, server)
        if 'tearoff' in options:
            tearoff = 1 if _tcl_boolean(options['tearoff']) else 0
            has = bool(self._entries) and self._entries[0]['type'] == 'tearoff'
            if tearoff and not has:
                self._entries.insert(0, {'type': 'tearoff'})
            elif not tearoff and has:
                self._entries.pop(0)
            options['tearoff'] = tearoff
        self._menu_options.update(options)
        if 'tearoff' in options:
            self._sync()

    config = configure

    def cget(self, key):
        key = _aliases.get(key, key)
        if key not in _MENU_OPTIONS:
            raise TclError('unknown option "-' + key + '"')
        return self._menu_options[key]

    def keys(self):
        return sorted(list(_MENU_OPTIONS) + ['bd', 'bg', 'fg'])

    def bind(self, sequence, func, add=None):
        result = Widget.bind(self, sequence, func, add)
        if sequence == '<<MenuSelect>>':
            self._call('configure', self._id, {'select': True})
        return result

    # A menu is a top-level window: no geometry manager takes it.
    def _toplevel_error(self, manager):
        raise TclError("can't " + manager + ' "' + self._w + '": it\'s a top-level window')

    def pack(self, cnf=None, **kw):
        self._toplevel_error('pack')

    def grid(self, cnf=None, **kw):
        self._toplevel_error('use geometry manager grid on')

    def place(self, cnf=None, **kw):
        self._toplevel_error('use geometry manager place on')

    pack_configure = pack
    grid_configure = grid
    place_configure = place

    # ---- indices ----------------------------------------------------------------------------

    def _index(self, index):
        # Tk's GetMenuIndex: None for 'none' or an empty menu; numbers past the end are the last.
        n = len(self._entries)
        if isinstance(index, bool):
            index = int(index)
        if isinstance(index, int):
            if index < 0:
                raise TclError('bad menu entry index "' + str(index) + '"')
            return None if n == 0 else min(index, n - 1)
        text = str(index)
        if text == 'active':
            active = self._call('menu', self._id, 'active', ())
            return active if active >= 0 else None
        if text in ('end', 'last'):
            return n - 1 if n else None
        if text == 'none':
            return None
        if text.startswith('@'):
            try:
                y = int(text[1:].split(',')[-1])
            except ValueError:
                raise TclError('bad menu entry index "' + text + '"')
            found = self._call('menu', self._id, 'index_at', (y,))
            return found if found >= 0 else None
        if text.isdigit():
            return None if n == 0 else min(int(text), n - 1)
        for i, entry in enumerate(self._entries):
            label = entry.get('label')
            if label is not None and _string_match(text, str(label)):
                return i
        raise TclError('bad menu entry index "' + text + '"')

    def index(self, index):
        return self._index(index)

    def _entry(self, index):
        i = self._index(index)
        if i is None:
            raise TclError('bad menu entry index "' + str(index) + '"')
        return i, self._entries[i]

    def type(self, index):
        return self._entry(index)[1]['type']

    # ---- entries ----------------------------------------------------------------------------

    def _check_options(self, kind, options):
        allowed = _ENTRY_OPTIONS[kind]
        for key in options:
            if key not in allowed:
                raise TclError('unknown option "-' + key + '"')
        if 'state' in options:
            _choice(options['state'], ('active', 'normal', 'disabled'), 'state')
        if 'command' in options and options['command'] not in (None, '') and not callable(options['command']):
            raise TypeError('command must be callable')
        if 'menu' in options and options['menu'] not in (None, ''):
            menu = options['menu']
            if isinstance(menu, str):
                menu = self.nametowidget(menu)
            if not isinstance(menu, Menu) or menu._root is not self._root:
                raise TclError('bad menu "' + str(options['menu']) + '"')
            options['menu'] = menu
        if 'underline' in options:
            options['underline'] = int(options['underline'])
        for key in ('foreground', 'background', 'activebackground', 'activeforeground', 'selectcolor'):
            if options.get(key) not in (None, ''):
                _color(options[key])

    def _new_entry(self, itemType, cnf, kw):
        if itemType not in _MENU_TYPES:
            raise TclError('bad menu entry type "' + str(itemType) + '": must be cascade, checkbutton, command, radiobutton, or separator')
        options = _options(cnf, kw)
        self._check_options(itemType, options)
        entry = {'type': itemType}
        entry.update(options)
        # Tk's defaults that follow the label: a checkbutton's variable, a radiobutton's value.
        if itemType == 'checkbutton' and 'variable' not in entry:
            entry['variable'] = str(entry.get('label', ''))
        if itemType == 'radiobutton':
            entry.setdefault('variable', 'selectedButton')
            entry.setdefault('value', str(entry.get('label', '')))
        return entry

    def add(self, itemType, cnf={}, **kw):
        self._entries.append(self._new_entry(itemType, cnf, kw))
        self._sync()

    def insert(self, index, itemType, cnf={}, **kw):
        entry = self._new_entry(itemType, cnf, kw)
        n = len(self._entries)
        if index in ('end', 'last') or isinstance(index, int) and not isinstance(index, bool) and index >= n:
            at = n
        else:
            at = self._index(index)
            at = n if at is None else at
        self._entries.insert(at, entry)
        self._sync()

    def add_cascade(self, cnf={}, **kw):
        self.add('cascade', cnf or kw)

    def add_checkbutton(self, cnf={}, **kw):
        self.add('checkbutton', cnf or kw)

    def add_command(self, cnf={}, **kw):
        self.add('command', cnf or kw)

    def add_radiobutton(self, cnf={}, **kw):
        self.add('radiobutton', cnf or kw)

    def add_separator(self, cnf={}, **kw):
        self.add('separator', cnf or kw)

    def insert_cascade(self, index, cnf={}, **kw):
        self.insert(index, 'cascade', cnf or kw)

    def insert_checkbutton(self, index, cnf={}, **kw):
        self.insert(index, 'checkbutton', cnf or kw)

    def insert_command(self, index, cnf={}, **kw):
        self.insert(index, 'command', cnf or kw)

    def insert_radiobutton(self, index, cnf={}, **kw):
        self.insert(index, 'radiobutton', cnf or kw)

    def insert_separator(self, index, cnf={}, **kw):
        self.insert(index, 'separator', cnf or kw)

    def delete(self, index1, index2=None):
        first = self._index(index1)
        last = first if index2 is None else self._index(index2)
        if first is None or last is None or last < first:
            return
        del self._entries[first:last + 1]
        self._sync()

    def _entry_value(self, entry, key):
        if key in entry:
            value = entry[key]
            if key == 'menu':
                return '' if value in (None, '') else value._w
            if key == 'variable' and isinstance(value, Variable):
                return str(value)
            return '' if value is None else value
        return _ENTRY_DEFAULTS.get(key, '')

    def entrycget(self, index, option):
        entry = self._entry(index)[1]
        option = _aliases.get(option, option)
        if option not in _ENTRY_OPTIONS[entry['type']]:
            raise TclError('unknown option "-' + option + '"')
        return self._entry_value(entry, option)

    def entryconfigure(self, index, cnf=None, **kw):
        entry = self._entry(index)[1]
        allowed = _ENTRY_OPTIONS[entry['type']]

        def describe(key):
            default = 'selectedButton' if key == 'variable' and entry['type'] == 'radiobutton' else _ENTRY_DEFAULTS.get(key, '')
            names = ('compound', 'Compound') if key == 'compound' else ('', '')
            return (key, names[0], names[1], default, self._entry_value(entry, key))

        if cnf is None and not kw:
            return dict((key, describe(key)) for key in allowed)
        if isinstance(cnf, str) and not kw:
            key = _aliases.get(cnf, cnf)
            if key not in allowed:
                raise TclError('unknown option "-' + cnf + '"')
            return describe(key)
        options = _options(cnf, kw)
        self._check_options(entry['type'], options)
        entry.update(options)
        self._sync()

    entryconfig = entryconfigure

    # ---- variables ----------------------------------------------------------------------------

    def _var(self, entry):
        # The Variable an entry's -variable names (a string names a menu global of this root).
        value = entry.get('variable')
        if isinstance(value, Variable):
            return value
        if value in (None, ''):
            return None
        globals_ = _menu_variables(self._root)
        var = globals_.get(str(value))
        if var is None:
            var = globals_[str(value)] = StringVar()
            var._unset = True
        return var

    def _selected(self, entry):
        var = self._var(entry)
        if var is None or getattr(var, '_unset', False):
            return False
        value = var.get()
        want = entry.get('onvalue', '1') if entry['type'] == 'checkbutton' else entry.get('value', '')
        return _equal_value(value, want)

    def _sync(self):
        # The whole entry list to the display, with each indicator as the variables have it now.
        if not self._alive:
            return
        out = []
        listening = []
        for entry in self._entries:
            kind = entry['type']
            item = {'type': kind}
            if kind not in ('separator', 'tearoff'):
                item['label'] = str(entry.get('label', ''))
                item['accelerator'] = str(entry.get('accelerator', ''))
                item['state'] = str(entry.get('state', 'normal'))
                item['underline'] = int(entry.get('underline', -1))
                if kind in ('checkbutton', 'radiobutton'):
                    var = self._var(entry)
                    if var is not None and var not in listening:
                        listening.append(var)
                    item['selected'] = self._selected(entry)
                    item['indicatoron'] = _tcl_boolean(entry.get('indicatoron', 1))
                if kind == 'cascade' and isinstance(entry.get('menu'), Menu) and entry['menu']._alive:
                    item['menu'] = entry['menu']._id
            elif kind == 'tearoff':
                item['state'] = str(entry.get('state', 'normal'))
            for key in ('foreground', 'background', 'activebackground', 'activeforeground', 'selectcolor'):
                if entry.get(key) not in (None, ''):
                    item[key] = _color(entry[key])
            out.append(item)
        self._call('menu', self._id, 'entries', (out,))
        for var in self._listening:
            if var not in listening and self in var._menus:
                var._menus.remove(self)
        for var in listening:
            if self not in var._menus:
                var._menus.append(self)
        self._listening = listening

    # ---- running entries ------------------------------------------------------------------

    def invoke(self, index):
        i = self._index(index)
        if i is None:
            return ''
        entry = self._entries[i]
        kind = entry['type']
        if kind in ('separator', 'cascade', 'tearoff') or entry.get('state') == 'disabled':
            return ''
        if kind in ('checkbutton', 'radiobutton'):
            var = self._var(entry)
            if var is not None:
                if kind == 'checkbutton':
                    on = entry.get('onvalue', '1')
                    value = entry.get('offvalue', '0') if self._selected(entry) else on
                else:
                    value = entry.get('value', '')
                var._unset = False
                var.set(value)
        command = entry.get('command')
        if command in (None, ''):
            return ''
        return command()

    def _notified(self, kind, *args):
        # The display's notices: a player chose an entry, posted the menu, moved the highlight.
        try:
            if kind == 'invoke':
                self.invoke(args[0])
            elif kind == 'post':
                self._postcommand()
            elif kind == 'select':
                self.event_generate('<<MenuSelect>>')
        except Exception as error:
            print('Tk callback error:', error)

    def _postcommand(self):
        command = self._menu_options.get('postcommand')
        if command not in (None, ''):
            command()

    # ---- posting --------------------------------------------------------------------------

    def post(self, x, y):
        self._postcommand()
        self._call('menu', self._id, 'post', (int(x), int(y), -1))

    def tk_popup(self, x, y, entry=''):
        self._postcommand()
        index = -1 if entry in ('', None) else self._index(entry)
        self._call('menu', self._id, 'post', (int(x), int(y), -1 if index is None else index))

    def unpost(self):
        self._call('menu', self._id, 'unpost', ())

    def activate(self, index):
        i = self._index(index)
        self._call('menu', self._id, 'activate', (-1 if i is None else i,))

    def yposition(self, index):
        i = self._index(index)
        return self._call('menu', self._id, 'yposition', (0 if i is None else i,))

    def xposition(self, index):
        self._index(index)
        return 1   # entries are in one column, inside the 1-pixel border

    def destroy(self):
        for var in self._listening:
            if self in var._menus:
                var._menus.remove(self)
        self._listening = []
        Widget.destroy(self)


class Menubutton(Widget):
    """A label that posts its menu= when pressed (below it, or direction= above/left/right/flush);
    indicatoron=1 shows the classic indicator. Hold the button and release on an entry to choose."""
    _kind = 'menubutton'
    # Tk's options the display does not draw: kept for cget.
    _recorded_options = {'activebackground': '#ececec', 'activeforeground': '#000000', 'bitmap': '', 'compound': 'none',
                         'cursor': '', 'disabledforeground': '#a3a3a3', 'highlightbackground': '#d9d9d9',
                         'highlightcolor': '#000000', 'highlightthickness': 0, 'justify': 'center', 'underline': -1}

    def __init__(self, master=None, cnf=None, **kw):
        self._recorded = {}
        self._menu_widget = None
        self._direction = 'below'
        self._indicator = 0
        self._init(master, self._kind, cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        for key in list(options):
            if key in self._recorded_options:
                self._recorded[key] = options.pop(key)
        if 'direction' in options:
            self._direction = _choice(options['direction'], ('above', 'below', 'flush', 'left', 'right'), 'direction')
        if 'indicatoron' in options:
            self._indicator = 1 if _tcl_boolean(options['indicatoron']) else 0
            options['indicatoron'] = bool(self._indicator)
        Widget.configure(self, options)

    config = configure

    def cget(self, key):
        key = _aliases.get(key, key)
        if key in self._recorded_options:
            return self._recorded.get(key, self._recorded_options[key])
        if key == 'direction':
            return self._direction
        if key == 'indicatoron':
            return self._indicator
        return Widget.cget(self, key)


class _setit:
    """The command of an OptionMenu entry: set the variable to the value, then call callback(value)."""

    def __init__(self, var, value, callback=None):
        self.__value = value
        self.__var = var
        self.__callback = callback

    def __call__(self, *args):
        self.__var.set(self.__value)
        if self.__callback is not None:
            self.__callback(self.__value, *args)


class OptionMenu(Menubutton):
    """A Menubutton showing variable's value, with a menu of the values that sets it (CPython's)."""

    def __init__(self, master, variable, value, *values, **kwargs):
        kw = {'borderwidth': 2, 'textvariable': variable, 'indicatoron': 1, 'relief': RAISED, 'anchor': 'c', 'highlightthickness': 2}
        Menubutton.__init__(self, master, kw)
        self.widgetName = 'tk_optionMenu'
        menu = self._om_menu = Menu(self, name='menu', tearoff=0)
        self.menuname = menu._w
        callback = kwargs.get('command')
        if 'command' in kwargs:
            del kwargs['command']
        if kwargs:
            raise TclError('unknown option -' + next(iter(kwargs)))
        menu.add_command(label=value, command=_setit(variable, value, callback))
        for v in values:
            menu.add_command(label=v, command=_setit(variable, v, callback))
        self['menu'] = menu

    def __getitem__(self, name):
        if name == 'menu':
            return self._om_menu
        return Widget.__getitem__(self, name)

    def destroy(self):
        Menubutton.destroy(self)
        self._om_menu = None


# ---- panedwindows --------------------------------------------------------------------------
# The classic panedwindow's options: (database name, class, default), measured in Tk 8.6.
_PANED_OPTIONS = {
    'background': ('background', 'Background', '#d9d9d9'),
    'borderwidth': ('borderWidth', 'BorderWidth', 1),
    'cursor': ('cursor', 'Cursor', ''),
    'handlepad': ('handlePad', 'HandlePad', 8),
    'handlesize': ('handleSize', 'HandleSize', 8),
    'height': ('height', 'Height', ''),
    'opaqueresize': ('opaqueResize', 'OpaqueResize', 1),
    'orient': ('orient', 'Orient', 'horizontal'),
    'proxybackground': ('proxyBackground', 'ProxyBackground', ''),
    'proxyborderwidth': ('proxyBorderWidth', 'ProxyBorderWidth', 2),
    'proxyrelief': ('proxyRelief', 'Relief', 'flat'),
    'relief': ('relief', 'Relief', 'flat'),
    'sashcursor': ('sashCursor', 'Cursor', ''),
    'sashpad': ('sashPad', 'SashPad', 0),
    'sashrelief': ('sashRelief', 'Relief', 'flat'),
    'sashwidth': ('sashWidth', 'Width', 3),
    'showhandle': ('showHandle', 'ShowHandle', 0),
    'width': ('width', 'Width', ''),
}
# What the display draws of them; the rest are kept for cget.
_PANED_DRAWN = ('background', 'borderwidth', 'handlepad', 'handlesize', 'height', 'orient', 'relief', 'sashpad', 'sashrelief',
                'sashwidth', 'showhandle', 'width')
_PANE_OPTIONS = ('after', 'before', 'height', 'hide', 'minsize', 'padx', 'pady', 'sticky', 'stretch', 'width')
_RELIEFS = ('flat', 'groove', 'raised', 'ridge', 'solid', 'sunken')


class PanedWindow(Widget):
    """Panes side by side (orient='horizontal') or stacked, with a sash between each two that the
    player drags. add(child, minsize=, width=, height=, padx=, pady=, sticky=, stretch=, hide=,
    before=, after=) adds a pane: a child of the panedwindow or of its parent. Extra room goes
    to -stretch panes (the last, by default) in proportion to their sizes, as in Tk.
    opaqueresize=0 still moves the sash at once (no proxy outline)."""
    _kind = 'panedwindow'

    def __init__(self, master=None, cnf={}, **kw):
        self._paned = dict((key, spec[2]) for key, spec in _PANED_OPTIONS.items())
        self._marks = {}
        self._proxy = (0, 0)
        self._init(master, self._kind, cnf, kw)

    # ---- options --------------------------------------------------------------------------

    def configure(self, cnf=None, **kw):
        if cnf is None and not kw:
            result = {}
            for key, spec in _PANED_OPTIONS.items():
                result[key] = (key, spec[0], spec[1], spec[2], self._paned[key])
            for alias, key in (('bd', 'borderwidth'), ('bg', 'background')):
                result[alias] = (alias, '-' + key)
            return result
        if isinstance(cnf, str) and not kw:
            key = _aliases.get(cnf, cnf)
            if key not in _PANED_OPTIONS:
                raise TclError('unknown option "-' + cnf + '"')
            spec = _PANED_OPTIONS[key]
            return (key, spec[0], spec[1], spec[2], self._paned[key])
        options = _options(cnf, kw)
        allowed = ('orient', 'width', 'height', 'takefocus') if self._ttk else tuple(_PANED_OPTIONS) + ('takefocus',)
        server = {}
        for key, value in options.items():
            if key not in allowed:
                raise TclError('unknown option "-' + key + '"')
            if key == 'orient':
                value = _choice(value, ('horizontal', 'vertical'), 'orient')
            elif key in ('sashrelief', 'relief'):
                value = _choice(value, _RELIEFS, 'relief')
            elif key in ('showhandle', 'opaqueresize'):
                value = 1 if _tcl_boolean(value) else 0
            elif key in ('width', 'height'):
                value = '' if value in ('', None) else int(value)
            if key in _PANED_DRAWN or key == 'takefocus':
                if key in ('width', 'height'):
                    server[key] = 0 if value == '' else value
                elif key == 'showhandle':
                    server[key] = bool(value)
                else:
                    server[key] = value
            self._paned[key] = value
        if server:
            Widget.configure(self, server)

    config = configure

    def cget(self, key):
        key = _aliases.get(key, key)
        if key in _PANED_OPTIONS and (not self._ttk or key in ('orient', 'width', 'height')):
            value = self._paned[key]
            if self._ttk and key in ('width', 'height') and value == '':
                return 0
            return value
        return Widget.cget(self, key)

    def keys(self):
        return sorted(list(_PANED_OPTIONS) + ['bd', 'bg'])

    # ---- panes ------------------------------------------------------------------------------

    def _pane_ids(self):
        return list(self._call('paned', self._id, 'panes', ()))

    def panes(self):
        """The panes' path names, in order."""
        return tuple(self._root._widgets[i]._w for i in self._pane_ids() if i in self._root._widgets)

    def _widget(self, child):
        if isinstance(child, str):
            child = self.nametowidget(child)
        if not isinstance(child, Widget) or child._root is not self._root or not child._alive:
            raise TclError('bad window path name "' + str(child) + '"')
        return child

    def _pane_index(self, pane):
        # A pane by index (ttk) or by widget/path name: its index, or Tk's error.
        ids = self._pane_ids()
        if isinstance(pane, int) and not isinstance(pane, bool):
            if not 0 <= pane < len(ids):
                raise TclError('Slave index ' + str(pane) + ' out of bounds')
            return pane
        if isinstance(pane, str) and pane.lstrip('-').isdigit():
            return self._pane_index(int(pane))
        widget = self._widget(pane)
        if widget._id not in ids:
            raise TclError(widget._w + ' is not managed by ' + self._w)
        return ids.index(widget._id)

    def _check_child(self, child):
        if child._window or child is self or child.master is not self and child.master is not self.master:
            raise TclError("can't add " + child._w + ' as slave of ' + self._w)

    def _pane_values(self, options):
        # Classic pane options to the display's (after/before are positions, handled by add).
        out = {}
        for key, value in options.items():
            if key not in _PANE_OPTIONS:
                raise TclError('unknown option "-' + key + '"')
            if key in ('after', 'before'):
                continue
            if key == 'sticky':
                text = str(value)
                if any(c not in 'nesw ,' for c in text.lower()):
                    raise TclError('bad stickyness value "' + text + '": must be a string containing zero or more of n, e, s, and w')
                out[key] = text.lower()
            elif key == 'stretch':
                out[key] = _choice(value, ('always', 'first', 'last', 'middle', 'never'), 'stretch')
            elif key == 'hide':
                out[key] = _tcl_boolean(value)
            elif key in ('width', 'height'):
                out[key] = 0 if value in ('', None) else max(0, int(value))
            else:
                out[key] = max(0, int(value))
        return out

    def add(self, child, **kw):
        """Add child as a pane (classic: before=/after= another pane; else at the end)."""
        child = self._widget(child)
        self._check_child(child)
        ids = self._pane_ids()
        if self._ttk:
            if child._id in ids:
                raise TclError(child._w + ' already added')
            self._call('paned', self._id, 'insert', (-1, child._id, self._weight(kw)))
            return
        options = _options(None, kw)
        values = self._pane_values(options)
        position = -1
        for key in ('before', 'after'):
            if options.get(key) not in (None, ''):
                anchor = self._pane_index(options[key])
                position = anchor + (1 if key == 'after' else 0)
                if child._id in ids and ids.index(child._id) < anchor:
                    position -= 1
        if child._id in ids and position < 0:
            position = ids.index(child._id)
        self._call('paned', self._id, 'insert', (position, child._id, values))

    def forget(self, child):
        """Take a pane away (classic: by widget; ttk: by index or widget)."""
        self._call('paned', self._id, 'forget', (self._pane_index(child),))

    remove = forget

    def panecget(self, child, option):
        values = self._pane_options(self._pane_index(child))
        option = str(option).lstrip('-')
        if option not in values:
            raise TclError('unknown option "-' + option + '"')
        return values[option]

    def _pane_options(self, index, set_=None):
        values = dict(self._call('paned', self._id, 'pane', (index, set_ or {})))
        if not self._ttk:
            for key in ('width', 'height'):
                if values[key] == 0:
                    values[key] = ''
            values['hide'] = 1 if values['hide'] else 0
            values['after'] = values['before'] = ''
        return values

    def paneconfigure(self, tagOrId, cnf=None, **kw):
        index = self._pane_index(tagOrId)
        if cnf is None and not kw:
            values = self._pane_options(index)
            defaults = {'hide': 0, 'minsize': 0, 'padx': 0, 'pady': 0, 'sticky': 'nsew', 'stretch': 'last'}
            names = {'hide': ('hide', 'Hide'), 'stretch': ('stretch', 'Stretch')}
            return dict((key, (key,) + names.get(key, ('', '')) + (defaults.get(key, ''), values[key])) for key in _PANE_OPTIONS)
        if isinstance(cnf, str) and not kw:
            return self.paneconfigure(index)[cnf.lstrip('-')] if cnf.lstrip('-') in _PANE_OPTIONS else self.panecget(index, cnf)
        options = _options(cnf, kw)
        values = self._pane_values(options)
        if any(options.get(key) not in (None, '') for key in ('before', 'after')):
            self.add(self._root._widgets[self._pane_ids()[index]], **options)
            return
        self._pane_options(index, values)

    paneconfig = paneconfigure

    # ---- sashes -----------------------------------------------------------------------------

    def sash_coord(self, index):
        return tuple(self._call('paned', self._id, 'sash_coord', (int(index),)))

    def sash_mark(self, index):
        return self._marks.get(int(index), (0, 0))

    def sash_place(self, index, x, y):
        self._call('paned', self._id, 'sash_place', (int(index), int(x), int(y)))
        return ()

    def sash(self, *args):
        if args and args[0] == 'coord':
            return self.sash_coord(args[1])
        if args and args[0] == 'place':
            return self.sash_place(*args[1:])
        if args and args[0] == 'mark':
            if len(args) > 2:
                self._marks[int(args[1])] = (int(args[2]), int(args[3]))
                return ''
            return self.sash_mark(args[1])
        raise TclError('bad option "' + (str(args[0]) if args else '') + '": must be coord, mark, or place')

    def identify(self, x, y):
        """(index, 'sash' or 'handle') for a sash at (x, y), else ''."""
        found = self._call('paned', self._id, 'identify', (int(x), int(y)))
        if found == -1:
            return ''
        return (found, 'sash') if found >= 0 else (-2 - found, 'handle')

    def proxy_coord(self):
        return self._proxy

    def proxy_forget(self):
        self._proxy = (0, 0)

    def proxy_place(self, x, y):
        self._proxy = (int(x), int(y))

    def proxy(self, *args):
        if args and args[0] == 'coord':
            return self.proxy_coord()
        if args and args[0] == 'forget':
            return self.proxy_forget()
        if args and args[0] == 'place':
            return self.proxy_place(*args[1:])
        raise TclError('bad option "' + (str(args[0]) if args else '') + '": must be coord, forget, or place')

    def _weight(self, kw):
        # ttk: -weight, a nonnegative integer.
        options = {}
        for key, value in kw.items():
            if key != 'weight':
                raise TclError('unknown option "-' + key + '"')
            try:
                weight = int(value)
            except (TypeError, ValueError):
                raise TclError('expected integer but got "' + str(value) + '"')
            if weight < 0:
                raise TclError('-weight must be nonnegative')
            options['weight'] = weight
        return options


PanedWindow._tk_class = 'Panedwindow'

# Tk's class names (winfo_class); `from tkinter import ttk` loads the tkinter.ttk module.
Widget._tk_class = ''
for _class in (Frame, Label, Button, Canvas, Message, Checkbutton, Radiobutton, Scale, Scrollbar, Listbox, Text, Entry, Spinbox, Tk, Toplevel,
               Menu, Menubutton):
    _class._tk_class = _class.__name__
Progressbar._tk_class = 'TProgressbar'
LabelFrame._tk_class = 'Labelframe'


def _dispatch(signal):
    # (kind, display address, token, widget, ...): the token says whose window it is.
    if len(signal) < 4:
        return
    root = _roots.get(signal[2])
    if root is None or root._screen != signal[1]:
        return
    widget = root._widgets.get(signal[3])
    if widget is None or not widget._alive:
        return
    if signal[0] == 'tk_changed':
        if widget._variable is not None:
            # Read current state: an older queued edit must not overwrite a newer one.
            widget._variable._set(widget.get(), widget)
        return
    if signal[0] == 'tk_close':
        # A window's close box: the root's or a Toplevel's.
        (widget if isinstance(widget, Tk) else root)._delete_window()
        return
    callback = root._callbacks.get(signal[4])
    if callback is None:
        return
    if signal[0] == 'tk_command':
        callback(*signal[6:])
    else:
        event = Event()
        event.widget = root._widgets.get(signal[13], widget) if len(signal) > 13 else widget
        event.type = signal[5]
        event.x, event.y = signal[6], signal[7]
        event.keycode = signal[8]
        event.char = chr(signal[9]) if signal[9] else ''
        # Tk state masks: Shift=1, Control=4, Mod1/Alt=8, Button1=256.
        flags = signal[10]
        event.state = (flags & 1) | ((flags & 2) << 1) | ((flags & 4) << 1)
        event.keysym = _keysyms.get(event.keycode, event.char)
        if (event.keysym == '' or event.keysym < ' ') and event.keycode in _letter_keys:
            # Ctrl+S: char is control code 19, keysym 's' ('S' with Shift), as in Tk.
            event.keysym = _letter_keys[event.keycode]
            if event.state & 1:
                event.keysym = event.keysym.upper()
        if len(signal) > 14 and signal[14]:
            event.state |= 256
        event.delta = signal[12] if len(signal) > 12 else 0
        event.player = signal[11]
        event.num = 1 if event.type in ('<Button-1>', '<ButtonRelease-1>', '<Double-Button-1>') else 3 if event.type in ('<Button-3>', '<ButtonRelease-3>') else 0
        event.item = signal[15] if len(signal) > 15 else 0
        # <Configure>: the widget's new size (x and y are its position in its parent).
        event.width = signal[16] if len(signal) > 16 else 0
        event.height = signal[17] if len(signal) > 17 else 0
        canvas = event.widget if isinstance(event.widget, Canvas) else None
        previous_item = canvas._event_item if canvas is not None else None
        if canvas is not None:
            canvas._event_item = event.item
        try:
            callback(event)
        finally:
            if canvas is not None:
                canvas._event_item = previous_item


def _run_signal(signal):
    try:
        _dispatch(signal)
    except Exception as error:
        print('Tk callback error:', error)


def _update(owner=None):
    # Run the Tk events and after() timers of owner's windows (see _program). The signal
    # queue is shared by every program of this computer, so each root has a queue of its
    # own (_pending) that every program's loop fills and only the root's program runs:
    # a callback (and a SystemExit it raises) runs in its own program. A callback can yield
    # (each Tk call goes to the host), and other loops queue newer events meanwhile; one
    # queue per root keeps them behind the older ones, nested update() calls included.
    for unused in range(256):
        signal = _tk.poll()
        if signal is None:
            break
        if len(signal) < 3 or signal[2] not in _roots:
            continue          # a window that is gone
        waiting = _pending.setdefault(signal[2], [])
        waiting.append(signal)
        if len(waiting) > _PENDING_LIMIT:
            waiting.pop(0)    # a program that stopped polling keeps only its latest events
        _wake(getattr(_roots[signal[2]], '_owner', None))    # its loop may be parked
    for token in [t for t, root in _roots.items() if getattr(root, '_owner', None) == owner]:
        waiting = _pending.get(token)
        while waiting:
            try:
                signal = waiting.pop(0)
            except IndexError:
                break         # another thread of this program took the last one
            _run_signal(signal)
    now = computer.uptime()
    for root in list(_roots.values()):
        if getattr(root, '_owner', None) != owner:
            continue
        for name in list(root._timers):
            timer = root._timers.get(name)
            if timer is not None and timer[0] <= now and root._timers.pop(name, None) is not None:
                if timer[1]._alive:
                    try:
                        timer[2](*timer[3])
                    except Exception as error:
                        print('Tk timer error:', error)


def mainloop():
    _default().mainloop()
