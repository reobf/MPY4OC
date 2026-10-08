"""Small Tk-compatible API for networked MPY4OC display blocks.

One Tk/Toplevel owns one physical display. Layout and editing run on the server;
callbacks, variables and timers live in Python and survive VM snapshots.
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
TclError = ValueError

_roots = {}
_default_root = None
_next_variable = 0
_aliases = {'bg': 'background', 'fg': 'foreground', 'bd': 'borderwidth', 'from_': 'from'}
_colors = {'black': 0, 'white': 0xffffff, 'red': 0xff0000,
           'green': 0x008000, 'blue': 0x0000ff, 'yellow': 0xffff00,
           'gray': 0x808080, 'grey': 0x808080}
_sequences = ('<Button-1>', '<ButtonRelease-1>', '<Button-3>', '<ButtonRelease-3>',
              '<Motion>', '<B1-Motion>', '<MouseWheel>', '<Key>', '<KeyRelease>',
              '<Return>', '<space>', '<Tab>', '<BackSpace>', '<Delete>', '<Left>',
              '<Right>', '<Home>', '<End>', '<<Paste>>', '<<Copy>>', '<<Cut>>', '<<ListboxSelect>>')
_event_aliases = {'<KeyPress>': '<Key>', '<ButtonPress-1>': '<Button-1>', '<ButtonPress-3>': '<Button-3>'}


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


def _default():
    if _default_root is None or not _default_root._alive:
        return Tk()
    return _default_root


class Event:
    pass


class Variable:
    _default_value = ''

    def __init__(self, master=None, value=None, name=None):
        global _next_variable
        if name is not None:
            raise ValueError('named variables are not supported')
        _next_variable += 1
        self._key = _next_variable
        self._value = self._default_value if value is None else value
        self._widgets = []
        self._value_widgets = []

    def _coerce(self, value):
        return value

    def get(self):
        for widget in self._value_widgets:
            if widget._alive:
                if isinstance(widget, Scale):
                    self._value = widget.get()
                    return self._coerce(self._value)
                if widget._call('cget', widget._id, 'selected'):
                    self._value = widget._choice if isinstance(widget, Radiobutton) else widget._onvalue
                    return self._coerce(self._value)
        for widget in self._value_widgets:
            if widget._alive and isinstance(widget, Checkbutton) and not isinstance(widget, Radiobutton):
                self._value = widget._offvalue
                return self._coerce(self._value)
        for widget in self._widgets:
            if isinstance(widget, Entry) and widget._alive:
                self._value = widget.get()
                break
        return self._coerce(self._value)

    def set(self, value):
        self._set(value, None)

    def _set(self, value, source):
        for widget in list(self._widgets):
            if widget._alive and widget is not source:
                widget._call('configure', widget._id, {'text': str(value)})
        for widget in list(self._value_widgets):
            if widget._alive and widget is not source:
                widget._apply_value(value)
        self._value = value


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
        if not master._alive or not isinstance(master, (Tk, Frame)):
            raise ValueError('master must be a live Tk or Frame')
        self.master = master
        self._root = master._root
        self._alive = True
        self._children = []
        self._variable = None
        self._command = None
        self._command_id = 0
        self._bindings = {}
        self._extra_callbacks = {}
        self._id = self._call('create', kind, master._id, {})
        master._children.append(self)
        self._root._widgets[self._id] = self
        try:
            self.configure(cnf, **kw)
        except Exception:
            self.destroy()
            raise

    def _call(self, operation, *args):
        if not self._alive:
            raise ValueError('widget was destroyed')
        root = self._root
        return _tk.call(root._screen, operation, root._token, *args)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
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

    config = configure

    def cget(self, key):
        key = _aliases.get(key, key)
        if key == 'textvariable':
            return self._variable
        if key == 'command':
            return self._command
        if key in ('xscrollcommand', 'yscrollcommand'):
            return self._extra_callbacks.get(key, (0, None))[1]
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

    pack_configure = pack

    def pack_forget(self):
        self._layout('pack_forget')

    def _layout(self, action, options=None):
        return self._call('layout', self._id, action, {} if options is None else options)

    def pack_propagate(self, flag=None):
        return self._layout('pack_propagate', {} if flag is None else {'flag': bool(flag)})

    def grid(self, cnf=None, **kw):
        self._layout('grid', self._layout_options(cnf, kw))

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

    def bind(self, sequence, func, add=None):
        sequence = _event_aliases.get(sequence, sequence)
        if sequence not in _sequences:
            raise ValueError('unsupported event: ' + sequence)
        if not callable(func):
            raise TypeError('binding must be callable')
        old = self._bindings.get(sequence, 0)
        if add == '+' and old:
            previous = self._root._callbacks[old]
            current = func
            def combined(event):
                previous(event)
                current(event)
            func = combined
        callback = self._root._register(func)
        try:
            self._call('bind', self._id, sequence, callback)
        except Exception:
            self._root._callbacks.pop(callback, None)
            raise
        self._root._callbacks.pop(old, None)
        self._bindings[sequence] = callback
        return str(callback)

    def unbind(self, sequence, funcid=None):
        sequence = _event_aliases.get(sequence, sequence)
        old = self._bindings.get(sequence, 0)
        if funcid is not None and str(old) != str(funcid):
            return
        self._call('bind', self._id, sequence, 0)
        self._root._callbacks.pop(old, None)
        self._bindings.pop(sequence, None)

    def focus_set(self):
        self._call('focus', self._id)

    def winfo_screen(self):
        return self._root._screen

    def winfo_screenwidth(self):
        return self._call('geometry', {})[0]

    def winfo_screenheight(self):
        return self._call('geometry', {})[1]

    def winfo_width(self):
        return self._call('bounds', self._id)[2]

    def winfo_x(self):
        return self._call('bounds', self._id)[0] - (self.master._call('bounds', self.master._id)[0] if self.master else 0)

    def winfo_y(self):
        return self._call('bounds', self._id)[1] - (self.master._call('bounds', self.master._id)[1] if self.master else 0)

    def winfo_ismapped(self):
        bounds = self._call('bounds', self._id)
        return bounds[2] > 0 and bounds[3] > 0

    def update_idletasks(self):
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
            while computer.uptime() < deadline:
                computer._yield_tick()
            return None
        if not callable(func):
            raise TypeError('after callback must be callable')
        root = self._root
        root._next_timer += 1
        name = 'after#' + str(root._next_timer)
        root._timers[name] = (deadline, self, func, args)
        return name

    def after_cancel(self, name):
        self._root._timers.pop(name, None)

    def update(self):
        _update()

    def mainloop(self):
        root = self._root
        root._quit = False
        while root._alive and not root._quit:
            _update()
            computer._yield_tick()

    def quit(self):
        self._root._quit = True

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

    def destroy(self):
        if self._alive:
            self._call('destroy', self._id)
            self._detach()


class Tk(Widget):
    def __init__(self, screenName=None, **kw):
        global _default_root
        screens = _tk.screens()
        if screenName is None:
            matches = [s for s in screens if s not in _roots]
        elif screenName in screens:
            matches = [screenName]
        else:
            matches = [s for s in screens if s.startswith(screenName)]
            if len(matches) > 1:
                raise OSError('ambiguous tk display prefix')
        if not matches:
            raise OSError('no tk display')
        self._screen = matches[0]
        if self._screen in _roots:
            raise OSError('tk display is already in use')
        self._root = self
        self.master = None
        self._id = 0
        self._alive = True
        self._children = []
        self._variable = None
        self._command = None
        self._command_id = 0
        self._bindings = {}
        self._extra_callbacks = {}
        self._callbacks = {}
        self._next_callback = 0
        self._widgets = {0: self}
        self._timers = {}
        self._next_timer = 0
        self._quit = False
        self._token = _tk.call(self._screen, 'claim', '')
        try:
            if kw:
                self.configure(**kw)
        except Exception:
            self._call('release')
            self._alive = False
            raise
        _roots[self._screen] = self
        if _default_root is None:
            _default_root = self

    def _register(self, callback):
        self._next_callback += 1
        self._callbacks[self._next_callback] = callback
        return self._next_callback

    def geometry(self, value=None):
        options = {}
        if value is not None:
            parts = value.split('x')
            if len(parts) != 2:
                raise ValueError('geometry must be WIDTHxHEIGHT')
            options = {'width': int(parts[0]), 'height': int(parts[1])}
        result = self._call('geometry', options)
        return str(result[0]) + 'x' + str(result[1])

    def title(self, value=None):
        options = {} if value is None else {'title': str(value)}
        return self._call('geometry', options)[2]

    def destroy(self):
        global _default_root
        if self._alive:
            self._call('release')
            self._detach()
            _roots.pop(self._screen, None)
            if _default_root is self:
                _default_root = None


class Toplevel(Tk):
    def __init__(self, master=None, screen=None, **kw):
        # A new physical display, not an overlapping window on the same canvas.
        Tk.__init__(self, screenName=screen, **kw)


class Frame(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'frame', cnf, kw)


class Label(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'label', cnf, kw)


class Button(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'button', cnf, kw)

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
        self._init(master, 'canvas', cnf, kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if options.pop('highlightthickness', 0) != 0:
            raise ValueError('Canvas highlightthickness supports only zero')
        Widget.configure(self, options)

    config = configure

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

    def canvasx(self, screenx, gridspacing=None):
        return float(screenx) if gridspacing is None else round(screenx / gridspacing) * gridspacing

    def canvasy(self, screeny, gridspacing=None):
        return self.canvasx(screeny, gridspacing)

    def tag_bind(self, tagOrId, sequence, func, add=None):
        sequence = _event_aliases.get(sequence, sequence)
        if not callable(func):
            raise TypeError('binding must be callable')
        key = (str(tagOrId), sequence)
        old = self._tag_bindings.get(key, 0)
        if add == '+' and old:
            previous = self._root._callbacks[old]
            current = func
            def combined(event):
                previous(event)
                current(event)
            func = combined
        callback = self._root._register(func)
        try:
            self._canvas('bind', tagOrId, sequence=sequence, callback=callback)
        except Exception:
            self._root._callbacks.pop(callback, None)
            raise
        self._root._callbacks.pop(old, None)
        self._tag_bindings[key] = callback
        return str(callback)

    def tag_unbind(self, tagOrId, sequence, funcid=None):
        sequence = _event_aliases.get(sequence, sequence)
        key = (str(tagOrId), sequence)
        old = self._tag_bindings.get(key, 0)
        if funcid is not None and str(old) != str(funcid):
            return
        self._canvas('bind', tagOrId, sequence=sequence, callback=0)
        self._root._callbacks.pop(old, None)
        self._tag_bindings.pop(key, None)

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
        self._value_variable = IntVar()
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


class Scrollbar(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'scrollbar', cnf, kw)

    def set(self, first, last):
        self._call('control', self._id, 'set', (float(first), float(last)))

    def get(self):
        return tuple(self._call('control', self._id, 'get', ()))


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
        return tuple(self._list('get', first, last))

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


class Text(Widget):
    """Plain text with one selection; rich tags, custom marks and undo are omitted."""
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'text', cnf, kw)

    def _text(self, action, *args):
        return self._call('text', self._id, action, args)

    def index(self, index):
        return self._text('index', str(index))

    def get(self, index1, index2=None):
        if index2 is None:
            return self._text('get', str(index1))
        return self._text('get', str(index1), str(index2))

    def insert(self, index, chars):
        self._text('insert', str(index), str(chars))

    def delete(self, index1, index2=None):
        if index2 is None:
            self._text('delete', str(index1))
        else:
            self._text('delete', str(index1), str(index2))

    def mark_set(self, markName, index):
        self._text('mark_set', markName, str(index))

    def tag_add(self, tagName, index1, index2=None):
        self._text('tag_add', tagName, str(index1), str(index1) + '+1c' if index2 is None else str(index2))

    def tag_remove(self, tagName, index1, index2=None):
        self._text('tag_remove', tagName, str(index1), str(index1) + '+1c' if index2 is None else str(index2))

    def tag_ranges(self, tagName):
        return tuple(self._text('tag_ranges', tagName))

    def selection_get(self):
        return self._text('selection_get')

    def compare(self, index1, op, index2):
        return self._text('compare', str(index1), op, str(index2))

    def see(self, index):
        self._text('see', str(index))

    def edit_modified(self, arg=None):
        if arg is None:
            return self._text('edit_modified')
        self._text('edit_modified', bool(arg))

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


class Entry(Widget):
    def __init__(self, master=None, cnf=None, **kw):
        self._init(master, 'entry', cnf, kw)

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

    def insert(self, index, text):
        value = self._call('entry', self._id, 'insert', index, str(text))
        if self._variable is not None:
            self._variable._set(value, self)

    def delete(self, first, last=None):
        if last is None:
            first = self.index(first)
            last = first + 1
        value = self._call('entry', self._id, 'delete', first, last)
        if self._variable is not None:
            self._variable._set(value, self)


def _dispatch(signal):
    if len(signal) < 4:
        return
    root = _roots.get(signal[1])
    if root is None or root._token != signal[2]:
        return
    widget = root._widgets.get(signal[3])
    if widget is None or not widget._alive:
        return
    if signal[0] == 'tk_changed':
        if widget._variable is not None:
            # Read current state: an older queued edit must not overwrite a newer one.
            widget._variable._set(widget.get(), widget)
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
        event.keysym = {28: 'Return', 156: 'Return', 57: 'space', 15: 'Tab',
                        14: 'BackSpace', 211: 'Delete', 203: 'Left', 205: 'Right',
                        199: 'Home', 207: 'End'}.get(event.keycode, event.char)
        # Tk state masks: Shift=1, Control=4, Mod1/Alt=8, Button1=256.
        flags = signal[10]
        event.state = (flags & 1) | ((flags & 2) << 1) | ((flags & 4) << 1)
        if len(signal) > 14 and signal[14]:
            event.state |= 256
        event.delta = signal[12] if len(signal) > 12 else 0
        event.player = signal[11]
        event.num = 1 if event.type in ('<Button-1>', '<ButtonRelease-1>') else 3 if event.type in ('<Button-3>', '<ButtonRelease-3>') else 0
        event.item = signal[15] if len(signal) > 15 else 0
        canvas = event.widget if isinstance(event.widget, Canvas) else None
        previous_item = canvas._event_item if canvas is not None else None
        if canvas is not None:
            canvas._event_item = event.item
        try:
            callback(event)
        finally:
            if canvas is not None:
                canvas._event_item = previous_item


def _update():
    for unused in range(256):
        signal = _tk.poll()
        if signal is None:
            break
        try:
            _dispatch(signal)
        except Exception as error:
            print('Tk callback error:', error)
    now = computer.uptime()
    for root in list(_roots.values()):
        for name in list(root._timers):
            timer = root._timers.get(name)
            if timer is not None and timer[0] <= now:
                root._timers.pop(name)
                if timer[1]._alive:
                    try:
                        timer[2](*timer[3])
                    except Exception as error:
                        print('Tk timer error:', error)


def mainloop():
    _default().mainloop()
