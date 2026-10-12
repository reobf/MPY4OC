"""tkinter.ttk for MPY4OC: the themed widgets, on top of tkinter's classic ones.

Each ttk class is a classic widget plus the ttk base (style=, class_, cursor, padding,
state()/instate(), identify(), winfo_class()), so isinstance(ttk.Button(), tkinter.Button)
holds and classic options (bg=, fg=, ...) keep working. -padding takes four sides and a Label
or Frame draws its -relief, as in Tk. ttk.Style has Tk's built-in themes (default, clam, alt,
classic) with their settings, layouts and elements, answers as Tk does, and the widgets draw
with their style in the theme in use: colours, reliefs, border widths, padding and button
widths, by state (active under the pointer, pressed, disabled, selected...); fonts and the
shapes of a theme's elements stay MPY4OC's. Labelframe places its label as Tk's
ttk::labelframe does; Notebook works as ttk::notebook does in the default theme; Entry,
Spinbox and Combobox validate as ttk does, a Combobox's list drops down over everything, and
Treeview shows a tree of items under column headings as ttk::treeview does.
Menubutton posts a menu (tkinter.Menu) and OptionMenu offers values in one; Panedwindow
has panes with sashes the player drags; LabeledScale shows a Scale's value over its slider.
"""
import tkinter

if not hasattr(tkinter, 'Progressbar') or not hasattr(tkinter, '_EntryBase'):
    # A computer restored from an older save still runs the tkinter it loaded then.
    raise ImportError('tkinter.ttk needs a newer tkinter: reboot the computer')

from tkinter import _options

__all__ = ['Button', 'Checkbutton', 'Combobox', 'Entry', 'Frame', 'Label', 'Labelframe', 'LabelFrame',
           'Menubutton', 'Notebook', 'Panedwindow', 'PanedWindow', 'Progressbar', 'Radiobutton', 'Scale',
           'Scrollbar', 'Separator', 'Sizegrip', 'Spinbox', 'Style', 'Treeview', 'LabeledScale', 'OptionMenu',
           'tclobjs_to_py', 'setup_master']

# Tk 8.6's state flags, in its bit order (state() lists them in this order).
_STATES = ('active', 'disabled', 'focus', 'pressed', 'selected', 'background', 'alternate', 'invalid',
           'readonly', 'hover', 'reserved1', 'reserved2', 'reserved3', 'user3', 'user2', 'user1')
# Flags the display knows (TkTree.flags): selected, focus, pressed, background, and with the
# pointer over the widget hover (and active for buttons, as their <Enter> binding sets it).
_SERVER_FLAGS = (('selected', 4), ('focus', 8), ('pressed', 16), ('background', 32), ('hover', 64), ('active', 128))
_RELIEFS = ('flat', 'raised', 'sunken', 'groove', 'ridge', 'solid')
# Widget options that, set by the program, win over the style (and the look keys they pin).
_PINS = {'background': ('background', 'fieldbackground'), 'foreground': ('foreground',), 'relief': ('relief',),
         'borderwidth': (), 'padding': (), 'anchor': (), 'width': ()}


def _look_rules(styles, chain, option, key):
    # What the style chain gives option, in Tk's lookup order: every map along the chain, then the
    # first plain setting. Each rule is [states on, states off, value] in Tk's state bits; colours
    # become 0xRRGGBB, a relief stays a name; a value the display cannot draw is left out.
    relief = key.endswith('relief')

    def convert(value):
        if relief:
            value = str(value)
            return value if value in _RELIEFS else None
        try:
            color = tkinter._color(value) if isinstance(value, str) else value
        except ValueError:
            return None
        return color if isinstance(color, int) and not isinstance(color, bool) and 0 <= color <= 0xFFFFFF else None

    rules = []
    for name in chain:
        entry = styles.get(name)
        if entry is None:
            continue
        for spec in entry['map'].get(option, ()):
            value = convert(spec[-1])
            if value is None:
                continue
            on = off = 0
            for state in spec[:-1]:
                for word in str(state).split():
                    bit = 1 << _STATES.index(word.lstrip('!'))
                    if word.startswith('!'):
                        off |= bit
                    else:
                        on |= bit
            rules.append([on, off, value])
    for name in chain:
        entry = styles.get(name)
        if entry is not None and option in entry['configure']:
            value = convert(entry['configure'][option])
            if value is not None:
                rules.append([0, 0, value])
            break
    return rules[:32]


def _configured(styles, chain, option):
    # The first plain setting of option along the chain, or None.
    for name in chain:
        entry = styles.get(name)
        if entry is not None and option in entry['configure']:
            return entry['configure'][option]
    return None


def _restyle_root(root):
    # Every ttk widget of root draws with the style and theme as they are now.
    for widget in list(root._widgets.values()):
        if isinstance(widget, Widget) and widget._alive and getattr(widget, '_ttk_ready', False):
            widget._restyle()


def _statespec(spec):
    # 'disabled !focus' or ['disabled', '!focus'] -> [('disabled', True), ('focus', False)]
    if isinstance(spec, str):
        spec = spec.split()
    out = []
    for item in spec:
        item = str(item)
        on = not item.startswith('!')
        name = item if on else item[1:]
        if name not in _STATES:
            raise tkinter.TclError('Invalid state name ' + name)
        out.append((name, on))
    return out


def _padding(value):
    # -padding: 1 to 4 lengths (left top right bottom; missing ones repeat), as a tuple of ints.
    if value == '' or value is None:
        return ''
    parts = value.split() if isinstance(value, str) else list(value) if isinstance(value, (tuple, list)) else [value]
    if not 1 <= len(parts) <= 4:
        raise tkinter.TclError('Wrong # elements in padding spec')
    return tuple(_pixels(part) for part in parts)


# Tk's screen distances: a number, or one with c (cm), m (mm), i (inch) or p (point) after it, at 96 dpi.
_UNITS = {'c': 96 / 2.54, 'm': 96 / 25.4, 'i': 96.0, 'p': 96 / 72}


def _pixels(value):
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return int(value)
    text = str(value).strip()
    factor = _UNITS.get(text[-1:], None)
    try:
        if factor is None:
            return int(float(text))
        return int(float(text[:-1]) * factor + 0.5)
    except ValueError:
        raise tkinter.TclError('bad screen distance "' + text + '"')


def setup_master(master=None):
    return master if master is not None else tkinter._default()


def tclobjs_to_py(adict):
    # Values here are already Python objects.
    return adict


class Widget:
    """The ttk base: a mixin over the classic tkinter widget each ttk class extends."""
    _ttk = True
    _classic = tkinter.Widget
    _ttk_class = ''
    _element = ''
    # ttk options this widget accepts and returns but does not draw yet: name -> default.
    _extra = {}
    # Those of _extra that the display does draw: kept here for cget (Tk returns '' until they
    # are set) and passed on.
    _drawn = ()
    # cget('takefocus') when unset: Tk's default for the class.
    _takefocus_default = ''
    # Whether the class has Tk's -state option, and whether the display honours its
    # disabled flag (a disabled Frame does not disable what is in it, as in Tk).
    _state_option_ok = True
    _server_disabled = True
    # What the display draws from the style (TkLook): (look key, style option, style suffix such
    # as '.Tab'), and the style options that size the widget unless the program set them.
    _look_spec = (('background', 'background', ''), ('foreground', 'foreground', ''), ('relief', 'relief', ''))
    _layout_spec = ()

    def _ttk_init(self, kw):
        options = kw
        self._class_option = str(options.pop('class_', options.pop('class', '')))
        self._style = ''
        self._cursor = ''
        self._padding = ''
        self._state_option = 'normal'
        self._disabled = False
        self._readonly = False
        self._held = set()
        # state(['selected']) / ['!selected']: the flag only, not the variable; None follows the value.
        self._selected = None
        self._extra_values = {}
        self._ttk_ready = False
        self._user_set = set()     # options the program set, which win over the style
        self._held_sent = 0

    def _ttk_done(self):
        self._ttk_ready = True
        self._restyle()

    # ---- the style's look --------------------------------------------------------------------

    def _default_style(self):
        return self._ttk_class

    def _style_name(self):
        return self._style or self._class_option or self._default_style()

    def _restyle(self):
        # Draw with the style as the current theme has it: the look rules (by state) and the
        # sizes (padding, borderwidth, anchor, a minimum width), what the program set winning.
        if not self._alive:
            return
        themes, current = _registry(self)
        styles = themes[current[0]]['styles']
        name = self._style_name()
        pinned = set()
        for option in self._user_set:
            pinned.update(_PINS.get(option, ()))
        look = {}
        for key, option, suffix in self._look_spec:
            if key not in pinned:
                rules = _look_rules(styles, _style_chain(name + suffix), option, key)
                if rules:
                    look[key] = rules
        self._held_sent = self._held_bits()
        options = {'look': look, 'held': self._held_sent}
        chain = _style_chain(name)
        for option in self._layout_spec:
            if option in self._user_set:
                continue
            value = _configured(styles, chain, option)
            try:
                if option == 'padding':
                    sides = _padding(value) if value not in (None, '') else (0,)
                    left = sides[0]
                    top = sides[1] if len(sides) > 1 else left
                    options['padding'] = [left, top, sides[2] if len(sides) > 2 else left, sides[3] if len(sides) > 3 else top]
                elif option == 'borderwidth' and value is not None:
                    options['borderwidth'] = int(float(str(value)))
                elif option == 'anchor' and value is not None:
                    options['anchor'] = str(value)
                elif option == 'width':
                    options['minwidth'] = abs(int(float(str(value)))) if value not in (None, '') else 0
            except (ValueError, TypeError):
                pass
        self._call('configure', self._id, options)

    def _held_bits(self):
        # The states only the program knows, in Tk's bits, for the display's look.
        names = set(self._held)
        if self._disabled and not self._server_disabled:
            names.add('disabled')
        if self._selected:
            names.add('selected')
        bits = 0
        for name in names:
            if name in _STATES:
                bits |= 1 << _STATES.index(name)
        return bits

    def _sync_held(self):
        bits = self._held_bits()
        if bits != self._held_sent and self._alive and self._ttk_ready:
            self._held_sent = bits
            self._call('configure', self._id, {'held': bits})

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if 'class_' in options or 'class' in options:
            raise tkinter.TclError('Attempt to change read-only option')
        restyle = False
        for key in options:
            if key in _PINS and not (key == 'relief' and 'relief' in self._extra):
                self._user_set.add(key)
                restyle = True
        if 'style' in options:
            self._style = str(options.pop('style'))
            restyle = True
        if 'cursor' in options:
            self._cursor = str(options.pop('cursor'))
        if 'padding' in options:
            padding = _padding(options.pop('padding'))
            # Missing sides repeat: right is left, bottom is top, top is left.
            left = padding[0] if padding else 0
            top = padding[1] if len(padding) > 1 else left
            right = padding[2] if len(padding) > 2 else left
            bottom = padding[3] if len(padding) > 3 else top
            options['padding'] = [left, top, right, bottom]
            self._padding = padding
        for name in self._extra:
            if name in options:
                value = options.pop(name)
                if name in self._drawn:
                    # '' is the style's look; anything else is the program's and wins over it.
                    self._classic.configure(self, {name: value or 'flat'})
                    if name == 'relief':
                        (self._user_set.add if value else self._user_set.discard)('relief')
                        restyle = True
                self._extra_values[name] = value
        state = options.pop('state', None)
        if state is not None:
            # ttk's -state compatibility option: sets exactly one of the disabled/readonly/active
            # flags (none for normal or any other word); state() does not change the option.
            if not self._state_option_ok:
                raise tkinter.TclError('unknown option "-state"')
            state = str(state)
            self._state_option = state
            self._disabled = state == 'disabled'
            self._readonly = state == 'readonly'
            if state == 'active':
                self._held.add('active')
            else:
                self._held.discard('active')
            options['state'] = self._server_state()
        if 'orient' in options:
            restyle = True   # Horizontal.TScale or Vertical.TScale
        result = None
        if options or not self._ttk_ready:
            result = self._classic.configure(self, options)
        if restyle and self._ttk_ready:
            self._restyle()
        else:
            self._sync_held()
        return result

    config = configure

    def cget(self, key):
        if key == 'style':
            return self._style
        if key in ('class', 'class_'):
            return self._class_option
        if key == 'cursor':
            return self._cursor
        if key == 'padding':
            return self._padding
        if key == 'state':
            if not self._state_option_ok:
                raise tkinter.TclError('unknown option "-state"')
            return self._state_option
        if key in self._extra:
            return self._extra_values.get(key, self._extra[key])
        if key == 'takefocus':
            value = self._classic.cget(self, key)
            return self._takefocus_default if value == '' else value
        return self._classic.cget(self, key)

    def __getitem__(self, key):
        return self.cget(key)

    def __setitem__(self, key, value):
        self.configure({key: value})

    def winfo_class(self):
        return self._class_option or self._ttk_class

    def _server_state(self):
        if self._disabled and self._server_disabled:
            return 'disabled'
        return 'readonly' if self._readonly and isinstance(self, tkinter.Entry) else 'normal'

    def _flags(self):
        names = set(self._held)
        if self._disabled:
            names.add('disabled')
        if self._readonly:
            names.add('readonly')
        bits = self._call('flags', self._id)
        for name, bit in _SERVER_FLAGS:
            if bits & bit:
                names.add(name)
        if self._selected is not None:
            (names.add if self._selected else names.discard)('selected')
        return tuple(name for name in _STATES if name in names)

    def _set_flag(self, name, on):
        if name in ('disabled', 'readonly'):
            if name == 'disabled':
                self._disabled = on
            else:
                self._readonly = on
            if self._server_disabled:
                self._call('configure', self._id, {'state': self._server_state()})
        elif name == 'selected' and isinstance(self, tkinter.Checkbutton):
            self._selected = on
        elif on:
            self._held.add(name)
        else:
            self._held.discard(name)

    def state(self, statespec=None):
        """The current state flags; or set flags ('!flag' clears one) and return the changes undone."""
        if statespec is None:
            return self._flags()
        spec = _statespec(statespec)
        before = set(self._flags())
        for name, on in spec:
            self._set_flag(name, on)
        self._sync_held()
        after = set(self._flags())
        named = [name for name, on in spec]
        return tuple(name if name in before else '!' + name for name in _STATES
                     if name in named and (name in before) != (name in after))

    def instate(self, statespec, callback=None, *args, **kw):
        current = self._flags()
        ok = all((name in current) == on for name, on in _statespec(statespec))
        if ok and callback is not None:
            return callback(*args, **kw)
        return ok

    def identify(self, x, y):
        """The element at (x, y) in widget pixels, or '' outside the widget."""
        bounds = self._call('bounds', self._id)
        if not (0 <= x < bounds[2] and 0 <= y < bounds[3]):
            return ''
        return self._identify(x, y, bounds[2], bounds[3])

    def _identify(self, x, y, width, height):
        return self._element


class Button(Widget, tkinter.Button):
    _classic = tkinter.Button
    _ttk_class = 'TButton'
    _element = 'label'
    _extra = {'compound': '', 'underline': -1, 'default': 'normal'}
    _takefocus_default = 'ttk::takefocus'
    _layout_spec = ('padding', 'borderwidth', 'anchor', 'width')

    def __init__(self, master=None, **kw):
        if kw.get('default', 'normal') not in ('normal', 'active', 'disabled'):
            raise tkinter.TclError('bad default "' + str(kw['default']) + '": must be normal, active, or disabled')
        self._ttk_init(kw)
        tkinter.Button.__init__(self, master, **kw)
        self._ttk_done()

    def invoke(self):
        # The disabled flag decides, not the -state option (state(['disabled']) sets only the flag).
        if self._disabled or self._command is None:
            return ''
        return self._command()


class _Indicator(Widget):
    # ttk.Checkbutton/Radiobutton: without a variable they start in the 'alternate' (tri-state) state.
    _extra = {'compound': '', 'underline': -1}
    _takefocus_default = 'ttk::takefocus'
    _look_spec = Widget._look_spec + (('indicatorcolor', 'indicatorcolor', ''), ('indicatorbackground', 'indicatorbackground', ''))
    _layout_spec = ('padding',)

    def _indicator_init(self, classic, master, kw):
        self._ttk_init(kw)
        alternate = 'variable' not in kw
        classic.__init__(self, master, **kw)
        if alternate:
            self._held.add('alternate')
        self._ttk_done()
        self._sync_held()

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        if self._ttk_ready and isinstance(options.get('variable'), tkinter.Variable):
            # A variable decides the look from now on, as in Tk.
            self._held.discard('alternate')
            self._selected = None
        return Widget.configure(self, options)

    config = configure

    def invoke(self):
        if self._disabled:
            return ''
        self._call('control', self._id, 'invoke', ())
        return self._activated()

    def _identify(self, x, y, width, height):
        return self._ttk_class[1:] + '.indicator' if x < self.cget('padx') + 16 else 'label'

    def _activated(self, *args):
        self._held.discard('alternate')
        self._selected = None
        self._sync_held()
        return tkinter.Checkbutton._activated(self, *args)

    def _apply_value(self, value):
        self._held.discard('alternate')
        self._selected = None
        tkinter.Checkbutton._apply_value(self, value)
        if getattr(self, '_ttk_ready', False):
            self._sync_held()


class Checkbutton(_Indicator, tkinter.Checkbutton):
    _classic = tkinter.Checkbutton
    _ttk_class = 'TCheckbutton'

    def __init__(self, master=None, **kw):
        self._indicator_init(tkinter.Checkbutton, master, kw)


class Radiobutton(_Indicator, tkinter.Radiobutton):
    _classic = tkinter.Radiobutton
    _ttk_class = 'TRadiobutton'

    def __init__(self, master=None, **kw):
        self._indicator_init(tkinter.Radiobutton, master, kw)


class Label(Widget, tkinter.Label):
    _classic = tkinter.Label
    _ttk_class = 'TLabel'
    _element = 'label'
    _extra = {'compound': '', 'underline': -1, 'justify': '', 'relief': '', 'font': ''}
    _drawn = ('relief',)
    # TLabel: the style's border (1 px from '.', shown by a relief) and padding (none in the
    # built-in themes); -padding adds inside the border, as in Tk.
    _layout_spec = ('padding', 'borderwidth', 'anchor')

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        tkinter.Label.__init__(self, master, **kw)
        self._ttk_done()


class Frame(Widget, tkinter.Frame):
    _classic = tkinter.Frame
    _ttk_class = 'TFrame'
    _extra = {'relief': ''}
    _drawn = ('relief',)
    # No border from the style: Tk 8.6 measures a ttk.Frame with padding '3 3 12 12' round a 50x20 child as 65x35.
    _layout_spec = ('padding',)
    _state_option_ok = False
    _server_disabled = False

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        tkinter.Frame.__init__(self, master, **kw)
        self._ttk_done()


class Labelframe(Widget, tkinter.LabelFrame):
    _classic = tkinter.LabelFrame
    _ttk_class = 'TLabelframe'
    _kind = 'ttk_labelframe'
    _extra = {'relief': '', 'underline': -1}
    _drawn = ('relief',)
    _look_spec = Widget._look_spec + (('labelforeground', 'foreground', '.Label'), ('labelbackground', 'background', '.Label'))
    _layout_spec = ('borderwidth',)
    _state_option_ok = False
    _server_disabled = False

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        tkinter.LabelFrame.__init__(self, master, **kw)
        self._ttk_done()


LabelFrame = Labelframe


class Notebook(Widget, tkinter.Frame):
    """Tabs along the top and, below them, the selected tab's page (ttk::notebook, default theme).
    A page is one of the notebook's own children (Tk also takes other descendants of the
    notebook's parent; not here). A player selects a tab by clicking it, or with Left/Right
    when the notebook has the keyboard."""
    _classic = tkinter.Frame
    _ttk_class = 'TNotebook'
    _takefocus_default = 'ttk::takefocus'
    _look_spec = (('background', 'background', ''), ('tabbackground', 'background', '.Tab'), ('tabforeground', 'foreground', '.Tab'))
    _state_option_ok = False
    _server_disabled = False
    _notebook = True
    _TAB_OPTIONS = ('padding', 'sticky', 'state', 'text', 'image', 'compound', 'underline')

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        self._padding_specs = {}     # page id -> its tab's -padding as given (tab() returns it so)
        self._known = 0              # the page last known to be selected (see _sync)
        self._init(master, 'notebook', None, kw)
        # The display tells us when a player's click or key selects another tab.
        callback = self._root._register(self._sync)
        self._extra_callbacks['notebook'] = (callback, self._sync)
        self._call('configure', self._id, {'command': callback})
        self._ttk_done()

    def _nb(self, action, *args):
        return self._call('notebook', self._id, action, args)

    def _sync(self, *args):
        # <<NotebookTabChanged>>, queued as Tk queues it, whenever the selected tab has changed.
        page = self._nb('selected')
        if page != self._known:
            self._known = page
            self.event_generate('<<NotebookTabChanged>>', when='tail')

    def _pages(self):
        return [self._root._widgets.get(ident) for ident in self._nb('tabs')]

    # Tk's tab identifiers (ttkNotebook.c): a number, a page or its path name (a "slave index"),
    # also 'current' and '@x,y' (FindTabIndex), and for index() 'end'.
    def _slave(self, tab_id):
        pages = self._pages()
        if isinstance(tab_id, tkinter.Widget):
            tab_id = str(tab_id)
        spec = str(tab_id)
        try:
            index = int(spec)
        except ValueError:
            index = None
        if index is not None:
            if not 0 <= index < len(pages):
                raise tkinter.TclError('Slave index ' + str(index) + ' out of bounds')
            return index
        if spec.startswith('.'):
            try:
                widget = self.nametowidget(spec)
            except KeyError:
                raise tkinter.TclError('bad window path name "' + spec + '"')
            if widget in pages:
                return pages.index(widget)
            raise tkinter.TclError(spec + ' is not managed by ' + str(self))
        raise tkinter.TclError('Invalid slave specification ' + spec)

    def _find(self, tab_id):
        spec = str(tab_id)
        if spec.startswith('@'):
            parts = spec[1:].split(',')
            try:
                x, y = int(parts[0]), int(parts[1])
            except (ValueError, IndexError):
                x = None
            if x is not None:
                return self._nb('identify', x, y)[1]
        if spec == 'current':
            return self._nb('current')
        return self._slave(tab_id)

    def _tab(self, tab_id):
        index = self._find(tab_id)
        if index < 0:
            raise tkinter.TclError("tab '" + str(tab_id) + "' not found")
        return index

    def _tab_options(self, kw):
        options = {}
        for key, value in kw.items():
            if key == 'padding':
                padding = _padding(value)
                left = padding[0] if padding else 0
                top = padding[1] if len(padding) > 1 else left
                options['padding'] = [left, top, padding[2] if len(padding) > 2 else left, padding[3] if len(padding) > 3 else top]
            elif key == 'image':
                options['image'] = '' if value is None else str(value)
            else:
                options[key] = value
        return options

    def _page(self, child):
        if isinstance(child, str):
            try:
                child = self.nametowidget(child)
            except KeyError:
                raise tkinter.TclError('bad window path name "' + child + '"')
        if not isinstance(child, tkinter.Widget) or child.master is not self or child._window:
            raise tkinter.TclError("can't add " + str(child) + ' as slave of ' + str(self))
        return child

    def _remember(self, page, kw):
        if 'padding' in kw:
            self._padding_specs[page._id] = _padding(kw['padding']) or (0,)

    def add(self, child, **kw):
        """A new tab for child at the end; for a page that has one, change its options (and show it if hidden)."""
        page = self._page(child)
        self._nb('add', page._id, self._tab_options(kw))
        self._remember(page, kw)
        self._sync()

    def insert(self, pos, child, **kw):
        """A tab for child before the tab pos ('end' after the last), or move child's tab there."""
        page = self._page(child)
        position = -1 if str(pos) == 'end' else self._slave(pos)
        self._nb('insert', position, page._id, self._tab_options(kw))
        self._remember(page, kw)
        self._sync()

    def forget(self, tab_id):
        """Remove the tab; its page stays, unmapped and no longer managed."""
        self._nb('forget', self._tab(tab_id))
        self._sync()

    def hide(self, tab_id):
        """Hide the tab (add() shows it again)."""
        self._nb('hide', self._tab(tab_id))
        self._sync()

    def select(self, tab_id=None):
        """The selected page's path name ('' for none); or select the tab (not a disabled one)."""
        if tab_id is None:
            ident = self._nb('selected')
            page = self._root._widgets.get(ident) if ident else None
            return str(page) if page is not None else ''
        self._nb('select', self._tab(tab_id))
        self._sync()
        return ''

    def tab(self, tab_id, option=None, **kw):
        """A tab's options as a dict, one of them, or (with keywords) set them."""
        index = self._tab(tab_id)
        if option is not None:
            kw = {option: None}
        if kw and any(value is not None for value in kw.values()):
            page = self._pages()[index]
            self._nb('tab', index, self._tab_options(kw))
            self._remember(page, kw)
            self._sync()
            return {}
        text, state, sticky, left, top, right, bottom, underline, image, compound = self._nb('tab', index, {})
        page = self._pages()[index]
        padding = self._padding_specs.get(page._id, (0,))
        values = {'padding': list(padding), 'sticky': sticky, 'state': state, 'text': text, 'image': image,
                  'compound': compound, 'underline': underline}
        if option is None:
            return values
        if option not in values:
            raise tkinter.TclError('unknown option "-' + str(option) + '"')
        return tuple(padding) if option == 'padding' else values[option]

    def tabs(self):
        """The pages' path names, in tab order."""
        return tuple(str(page) for page in self._pages())

    def index(self, tab_id):
        """The tab's number; for 'end', how many tabs there are."""
        if str(tab_id) == 'end':
            return len(self._nb('tabs'))
        index = self._find(tab_id)
        if index < 0:
            raise tkinter.TclError('expected integer but got ""')
        return index

    def identify(self, x, y):
        """The tab element at (x, y): 'tab' (its edge), 'padding', 'focus' or 'label'; '' elsewhere."""
        return self._nb('identify', int(x), int(y))[0]

    def enable_traversal(self):
        """Accepted; Control+Tab between tabs is not supported yet (Left/Right are)."""
        return None

    def _detach(self):
        self._padding_specs.clear()
        tkinter.Frame._detach(self)


class Entry(Widget, tkinter.Entry):
    """ttk::entry. Validation follows ttk's rules, which differ from the classic Entry's: a value
    set through the variable or set() is not validated, a validation error is raised, focus
    validation and validate() keep the outcome in the 'invalid' state."""
    _classic = tkinter.Entry
    _ttk_class = 'TEntry'
    _element = 'textarea'
    _extra = {'font': '', 'exportselection': 1}
    _takefocus_default = 'ttk::takefocus'
    _look_spec = (('fieldbackground', 'fieldbackground', ''), ('foreground', 'foreground', ''), ('relief', 'relief', ''),
                  ('arrowcolor', 'arrowcolor', ''), ('buttonbackground', 'background', ''))

    def __init__(self, master=None, widget=None, **kw):
        self._ttk_init(kw)
        tkinter.Entry.__init__(self, master, **kw)
        self._ttk_done()

    def validate(self):
        """Validate the value now ('forced', whatever -validate says): whether -validatecommand
        accepts it (true without one); the 'invalid' state follows."""
        value = self.get()
        ok = self._check('forced', -1, -1, value, value, '')
        self._revalidated(ok)
        return ok


class Spinbox(Entry):
    """ttk::spinbox: an Entry with two arrows that step its value (Up, Down and the mouse wheel
    too), through -values or from -from to -to by -increment (-wrap goes round); -format, or
    decimals guessed from -increment. A step selects the whole new value and runs -command. The
    steps go through <<Increment>> and <<Decrement>>, which a program may bind (and stop with
    'break'). Holding an arrow steps once (no repeat)."""
    _ttk_class = 'TSpinbox'
    _kind = 'ttk_spinbox'
    _spinbox = True

    def __init__(self, master=None, **kw):
        Entry.__init__(self, master, 'ttk::spinbox', **kw)

    def set(self, value):
        """Sets the value of the Spinbox to value (not validated, as in Tk)."""
        if self._validating:
            self._changed_inside = True
        self._sync_variable(self._call('entry', self._id, 'set', str(value)))

    def _identify(self, x, y, width, height):
        return self._call('control', self._id, 'identify', (x, y))

    def bind(self, sequence, func, add=None):
        funcid = tkinter.Widget.bind(self, sequence, func, add)
        self._delegate()
        return funcid

    def unbind(self, sequence, funcid=None):
        tkinter.Widget.unbind(self, sequence, funcid)
        self._delegate()

    def _delegate(self):
        # While the program binds <<Increment>> or <<Decrement>> here, a player's step comes here
        # first (the binding runs before the class binding steps, and may stop it).
        local = self._local_bindings()
        on = any(local.get(name) is not None and local[name].entries for name in ('<<Increment>>', '<<Decrement>>'))
        if on != getattr(self, '_delegating', False):
            self._delegating = on
            self._call('configure', self._id, {'delegate': on})

    def _class_event(self, sequence):
        # The TSpinbox class bindings: <<Increment>> and <<Decrement>> step; Up and Down generate them.
        if sequence in ('<<Increment>>', '<<Decrement>>'):
            self._step(1 if sequence == '<<Increment>>' else -1)
        elif sequence in ('<Up>', '<Down>'):
            self.event_generate('<<Increment>>' if sequence == '<Up>' else '<<Decrement>>')


class Combobox(Entry):
    """ttk::combobox: an Entry whose -values drop down in a list below it (its arrow or Down
    opens it; anywhere on a readonly one), at most -height rows. Choosing one (a click, Return)
    puts it in and generates <<ComboboxSelected>>; the mouse wheel over it steps through them.
    -postcommand runs before the list opens and may change -values. While the list is open it
    has the pointer and the keyboard; a click outside it or Esc closes it."""
    _ttk_class = 'TCombobox'
    _kind = 'combobox'

    def __init__(self, master=None, **kw):
        self._combo_values = ''
        self._postcommand = None
        Entry.__init__(self, master, 'ttk::combobox', **kw)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        commit = {}
        if 'values' in options:
            spec = '' if options['values'] is None else options['values']
            options['values'] = tkinter._tcl_split(spec) if isinstance(spec, str) else [str(value) for value in spec]
            commit['values'] = spec
        if 'postcommand' in options:
            command = options.pop('postcommand')
            if command == '':
                command = None
            if command is not None and not callable(command):
                raise TypeError('postcommand must be callable')
            commit['postcommand'] = command
            options['postcommand'] = command is not None
        result = Entry.configure(self, options)
        if 'values' in commit:
            self._combo_values = commit['values']
        if 'postcommand' in commit:
            self._postcommand = commit['postcommand']
        return result

    config = configure

    def cget(self, key):
        if key == 'values':
            spec = self._combo_values
            values = tuple(tkinter._tcl_split(spec)) if isinstance(spec, str) else tuple(spec)
            return values if values else ''
        if key == 'postcommand':
            return self._postcommand if self._postcommand is not None else ''
        return Entry.cget(self, key)

    def current(self, newindex=None):
        """The index of the value among -values (-1 if it is none of them); with newindex, put
        the value at that index in."""
        if newindex is None:
            return self._call('control', self._id, 'current', ())
        index = newindex if isinstance(newindex, int) and not isinstance(newindex, bool) else tkinter._tcl_number(newindex)
        if not isinstance(index, int):
            raise tkinter.TclError('expected integer but got "' + str(newindex) + '"')
        if self._validating:
            self._changed_inside = True
        self._call('control', self._id, 'current', (index,))
        self._sync_variable(self.get())
        return ''

    def set(self, value):
        """Sets the value of the combobox to value (not validated, as in Tk)."""
        if self._validating:
            self._changed_inside = True
        self._sync_variable(self._call('entry', self._id, 'set', str(value)))

    def _identify(self, x, y, width, height):
        return self._call('control', self._id, 'identify', (x, y))

    def _post(self):
        # ttk::combobox::Post: nothing while disabled; -postcommand, then the list opens.
        if self._disabled:
            return
        if self._postcommand is not None:
            try:
                self._postcommand()
            except Exception as error:
                print('Tk callback error:', error)
        if self._alive:
            self._call('control', self._id, 'post', ())

    def _notice(self, kind, args):
        if kind == 'post':
            self._post()
        elif kind == 'selected':
            self._sync_variable(self.get())
            self.event_generate('<<ComboboxSelected>>', when='mark')

    def _class_event(self, sequence):
        # The TCombobox class binding: Down opens the list.
        if sequence == '<Down>':
            self._post()


class Scale(Widget, tkinter.Scale):
    """ttk.Scale: a continuous value from 0 to 1.0 by default, horizontal, without the value shown.
    The classic Scale options (resolution, showvalue, ...) are still accepted. As in Tk, a
    variable= wins over value=, set() calls command= at once (and does nothing while disabled),
    and value= never calls it."""
    _classic = tkinter.Scale
    _ttk_class = 'TScale'
    _takefocus_default = 'ttk::takefocus'
    _look_spec = (('background', 'background', ''), ('slider', 'background', ''), ('troughcolor', 'troughcolor', ''))

    def _default_style(self):
        return ('Horizontal.' if self.cget('orient') == tkinter.HORIZONTAL else 'Vertical.') + self._ttk_class

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        self._own_variable = 'variable' not in kw
        value = kw.pop('value', None)
        if 'from' not in kw:
            kw.setdefault('from_', 0)
        kw.setdefault('to', 1.0)
        kw.setdefault('length', 100)
        kw.setdefault('orient', tkinter.HORIZONTAL)
        kw.setdefault('resolution', 0)
        kw.setdefault('showvalue', False)
        tkinter.Scale.__init__(self, master, **kw)
        self._ttk_done()
        if value is not None:
            self._set_value(value)

    def configure(self, cnf=None, **kw):
        options = _options(cnf, kw)
        value = options.pop('value', None)
        if 'variable' in options:
            self._own_variable = False
        result = Widget.configure(self, options)
        if value is not None:
            self._set_value(value)
        if self._ttk_ready and ('from' in options or 'to' in options):
            self.event_generate('<<RangeChanged>>')
        return result

    config = configure

    def cget(self, key):
        if key == 'value':
            return self.get()
        return Widget.cget(self, key)

    def _set_value(self, value):
        # The -value option: a variable= overrides it (Tk re-reads the variable on configure).
        if self._own_variable:
            self._call('configure', self._id, {'value': float(value)})
            self._value_variable._set(self.get(), self)

    def set(self, value):
        if self._disabled:
            return
        self._call('configure', self._id, {'value': float(value)})
        actual = self.get()
        self._value_variable._set(actual, self)
        if self._user_command is not None:
            self._user_command(str(actual))

    def get(self, x=None, y=None):
        # With a point (widget pixels), the value there.
        if x is None and y is None:
            return tkinter.Scale.get(self)
        return self._call('control', self._id, 'valueat', (0 if x is None else x, 0 if y is None else y))

    def _identify(self, x, y, width, height):
        cx, cy = self.coords()
        along, centre = (x, cx) if self.cget('orient') == tkinter.HORIZONTAL else (y, cy)
        return 'slider' if abs(along - centre) <= 7 else 'trough'


class Scrollbar(Widget, tkinter.Scrollbar):
    _classic = tkinter.Scrollbar
    _ttk_class = 'TScrollbar'
    _state_option_ok = False
    _server_disabled = False
    _look_spec = (('background', 'background', ''), ('troughcolor', 'troughcolor', ''), ('arrowcolor', 'arrowcolor', ''))

    def _default_style(self):
        return ('Horizontal.' if self.cget('orient') == tkinter.HORIZONTAL else 'Vertical.') + self._ttk_class

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        tkinter.Scrollbar.__init__(self, master, **kw)
        self._ttk_done()

    def identify(self, x, y):
        part = tkinter.Scrollbar.identify(self, x, y)
        horizontal = self.cget('orient') == tkinter.HORIZONTAL
        return {'arrow1': 'leftarrow' if horizontal else 'uparrow', 'arrow2': 'rightarrow' if horizontal else 'downarrow',
                'slider': 'thumb', 'trough1': 'trough', 'trough2': 'trough'}.get(part, '')


class Progressbar(Widget, tkinter.Progressbar):
    _classic = tkinter.Progressbar
    _ttk_class = 'TProgressbar'
    _extra = {'phase': 0}
    _state_option_ok = False
    _server_disabled = False
    _look_spec = (('bar', 'background', ''), ('troughcolor', 'troughcolor', ''))

    def _default_style(self):
        return ('Horizontal.' if self.cget('orient') == tkinter.HORIZONTAL else 'Vertical.') + self._ttk_class

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        tkinter.Progressbar.__init__(self, master, **kw)
        self._ttk_done()

    def _identify(self, x, y, width, height):
        if self.cget('mode') != 'determinate':
            return 'trough'
        fraction = max(0.0, min(1.0, float(self.cget('value')) / float(self.cget('maximum'))))
        horizontal = self.cget('orient') == tkinter.HORIZONTAL
        return 'pbar' if (x < width * fraction if horizontal else y >= height * (1 - fraction)) else 'trough'


class Separator(Widget, tkinter.Widget):
    """An etched line; pack or grid it with fill along its orient to stretch it."""
    _ttk_class = 'TSeparator'
    _element = 'separator'
    _look_spec = (('background', 'background', ''),)
    _state_option_ok = False
    _server_disabled = False

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        self._init(master, 'separator', None, kw)
        self._ttk_done()


class Sizegrip(Widget, tkinter.Widget):
    """Dragging it resizes the Toplevel it is in (on the desktop itself it does nothing)."""
    _ttk_class = 'TSizegrip'
    _element = 'sizegrip'
    _look_spec = (('background', 'background', ''),)
    _state_option_ok = False

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        self._init(master, 'sizegrip', None, kw)
        self._ttk_done()


def _unavailable(name):
    class Unavailable:
        def __init__(self, *args, **kw):
            raise NotImplementedError('ttk.' + name + ' is not available in MPY4OC yet')
    return Unavailable


class _TreeHandlers(tkinter._Handlers):
    # A Treeview's handlers for one event: the bindings of the tags of the row under the pointer
    # (tag_bind) first, then the widget's own.
    def __init__(self, view, sequence):
        tkinter._Handlers.__init__(self)
        self.view = view
        self.sequence = sequence

    def __call__(self, event):
        view = self.view
        if view._tag_bindings and view._alive:
            item = view.identify_row(getattr(event, 'y', 0))
            if item:
                for tag in view.item(item, 'tags') or ():
                    handlers = view._tag_bindings.get((tag, self.sequence))
                    if handlers is not None:
                        handlers(event)
        return tkinter._Handlers.__call__(self, event)


def _words(value):
    # A Tcl list given as a string, or a sequence: its items as strings.
    if value is None:
        return []
    if isinstance(value, str):
        return tkinter._tcl_split(value)
    return [str(item) for item in value]


class Treeview(Widget, tkinter.Widget):
    """ttk::treeview: items in a tree (text, values in -columns, open or closed, tags) under
    column headings, laid out as Tk's default theme does. A player selects rows (Ctrl toggles,
    Shift extends, as -selectmode allows), opens and closes items (the arrow, a double click,
    Return, Left/Right), moves with the arrow keys, scrolls with the wheel, clicks a heading
    (its command) and drags a heading's edge to resize a column. <<TreeviewSelect>>,
    <<TreeviewOpen>> and <<TreeviewClose>> are generated as in Tk. Tag colours (foreground,
    background) are drawn; images and fonts are recorded only."""
    _ttk_class = 'Treeview'
    _takefocus_default = 'ttk::takefocus'
    _state_option_ok = False
    _server_disabled = False
    _look_spec = (('background', 'background', ''), ('foreground', 'foreground', ''), ('fieldbackground', 'fieldbackground', ''),
                  ('headingbackground', 'background', '.Heading'), ('headingforeground', 'foreground', '.Heading'),
                  ('headingrelief', 'relief', '.Heading'))

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        self._raw_values = {}          # iid -> values as given (item() returns ints as ints)
        self._heading_commands = {}    # column id -> (function, its registered name)
        self._tag_options = {}         # tag -> recorded options
        self._tag_bindings = {}        # (tag, sequence) -> handlers
        self._init(master, 'treeview', None, self._tree_options(kw))
        callback = self._root._register(self._notified)
        self._extra_callbacks['notify'] = (callback, self._notified)
        self._call('configure', self._id, {'notify': callback})
        self._ttk_done()

    def _tree(self, action, *args):
        return self._call('tree', self._id, action, args)

    @staticmethod
    def _tree_options(options):
        if 'columns' in options:
            options['columns'] = _words(options['columns'])
        if 'displaycolumns' in options:
            value = options['displaycolumns']
            options['displaycolumns'] = ['#all'] if value in ('#all', ('#all',), ['#all']) else _words(value)
        if 'show' in options:
            options['show'] = _words(options['show'])
        if 'selectmode' in options:
            options['selectmode'] = str(options['selectmode'])
        return options

    def configure(self, cnf=None, **kw):
        return Widget.configure(self, self._tree_options(_options(cnf, kw)))

    config = configure

    def cget(self, key):
        if key in ('columns', 'show'):
            value = tuple(self._call('cget', self._id, key))
            return value if value else ''
        if key == 'displaycolumns':
            return tuple(self._call('cget', self._id, key))
        return Widget.cget(self, key)

    def _notified(self, kind, *args):
        # The display's notices: a player's selection, opening, closing, a heading clicked.
        if kind == 'select':
            self.event_generate('<<TreeviewSelect>>', when='tail')
        elif kind == 'open':
            self.event_generate('<<TreeviewOpen>>')
        elif kind == 'close':
            self.event_generate('<<TreeviewClose>>')
        elif kind == 'heading':
            command = self._heading_commands.get(self.column(args[0], 'id'))
            if command is not None:
                self.after(0, command[0])

    # ---- items ------------------------------------------------------------------------------

    def _item_options(self, kw):
        options = {}
        for key, value in kw.items():
            if key in ('values', 'tags'):
                options[key] = _words(value) if key == 'tags' or isinstance(value, str) else [str(v) for v in value]
            elif key == 'open':
                options[key] = tkinter._flag(value)
            elif key == 'image':
                options[key] = '' if value is None else str(value)
            elif key == 'text':
                options[key] = str(value)
            else:
                raise tkinter.TclError('unknown option "-' + str(key) + '"')
        return options

    def insert(self, parent, index, iid=None, **kw):
        """A new item under parent ('' for the top level) at index ('end' or a number); its id (iid, or a new I001...)."""
        options = self._item_options(kw)
        if iid is not None:
            options['iid'] = str(iid)
        iid = self._tree('insert', str(parent), index if isinstance(index, int) else str(index), options)
        if 'values' in kw:
            self._raw_values[iid] = list(kw['values']) if not isinstance(kw['values'], str) else tkinter._tcl_split(kw['values'])
        return iid

    def item(self, item, option=None, **kw):
        """The item's options as a dict, one of them, or (with keywords) set them."""
        item = str(item)
        if kw:
            self._tree('item', item, self._item_options(kw))
            if 'values' in kw:
                self._raw_values[item] = list(kw['values']) if not isinstance(kw['values'], str) else tkinter._tcl_split(kw['values'])
            return {}
        text, image, values, is_open, tags = self._tree('item', item)
        raw = self._raw_values.get(item)
        if raw is None or len(raw) != len(values):
            raw = values
        listed = [value if isinstance(value, int) and not isinstance(value, bool) else str(value) for value in raw]
        info = {'text': text, 'image': image, 'values': listed if values else '', 'open': 1 if is_open else 0,
                'tags': list(tags) if tags else ''}
        if option is None:
            return info
        option = str(option).lstrip('-')
        if option not in info:
            raise tkinter.TclError('unknown option "-' + option + '"')
        if option in ('values', 'tags'):
            return tuple(values if option == 'values' else tags) if (values if option == 'values' else tags) else ''
        return info[option]

    def delete(self, *items):
        """Delete the items and everything below them."""
        gone, selection = self._tree('delete', *[str(i) for i in items])
        for iid in gone:
            self._raw_values.pop(iid, None)
        if selection:
            self.event_generate('<<TreeviewSelect>>', when='tail')

    def detach(self, *items):
        """Take the items (and what is below them) out of the tree; they still exist (reattach puts them back)."""
        self._tree('detach', *[str(i) for i in items])

    def move(self, item, parent, index):
        """Move item to parent at index ('end' or a number)."""
        self._tree('move', str(item), str(parent), index if isinstance(index, int) else str(index))

    reattach = move

    def get_children(self, item=None):
        return tuple(self._tree('children', '' if item is None else str(item)))

    def set_children(self, item, *newchildren):
        """Replace item's children; the old ones not among them are detached."""
        self._tree('set_children', str(item), *[str(i) for i in newchildren])

    def parent(self, item):
        return self._tree('parent', str(item))

    def index(self, item):
        return self._tree('index', str(item))

    def next(self, item):
        return self._tree('next', str(item))

    def prev(self, item):
        return self._tree('prev', str(item))

    def exists(self, item):
        return bool(self._tree('exists', str(item)))

    def focus(self, item=None):
        """The focus item ('' for none); or make item the focus item."""
        if item is None:
            return self._tree('focus')
        self._tree('focus', str(item))
        return ''

    def see(self, item):
        """Open item's ancestors and scroll it into view."""
        self._tree('see', str(item))

    def bbox(self, item, column=None):
        """The item's row (or one cell) as (x, y, width, height); '' when it is not in view."""
        args = (str(item),) if column is None else (str(item), str(column))
        box = self._tree('bbox', *args)
        return tuple(box) if box is not None else ''

    # ---- selection ------------------------------------------------------------------------

    def selection(self):
        return tuple(self._tree('selection'))

    def _selection(self, op, items):
        if len(items) == 1 and isinstance(items[0], (tuple, list)):
            items = items[0]
        if len(items) == 1 and isinstance(items[0], str) and ' ' in items[0]:
            items = tkinter._tcl_split(items[0])
        self._tree('selection_' + op, *[str(i) for i in items])
        self.event_generate('<<TreeviewSelect>>', when='tail')

    def selection_set(self, *items):
        self._selection('set', items)

    def selection_add(self, *items):
        self._selection('add', items)

    def selection_remove(self, *items):
        self._selection('remove', items)

    def selection_toggle(self, *items):
        self._selection('toggle', items)

    # ---- columns and headings ----------------------------------------------------------------

    def column(self, column, option=None, **kw):
        """A column's options (width, minwidth, stretch, anchor, id) as a dict or one of them; or set them."""
        column = str(column)
        if kw:
            if 'stretch' in kw:
                kw['stretch'] = tkinter._flag(kw['stretch'])
            self._tree('column', column, kw)
            return {}
        width, minwidth, stretch, anchor, ident = self._tree('column', column)
        info = {'width': width, 'minwidth': minwidth, 'stretch': 1 if stretch else 0, 'anchor': anchor, 'id': ident}
        if option is None:
            return info
        option = str(option).lstrip('-')
        if option not in info:
            raise tkinter.TclError('unknown option "-' + option + '"')
        return info[option]

    def heading(self, column, option=None, **kw):
        """A heading's options (text, image, anchor, command, state) as a dict or one of them; or set them."""
        column = str(column)
        if kw:
            options = dict(kw)
            if 'command' in options:
                command = options['command']
                ident = self._tree('column', column)[4]
                old = self._heading_commands.pop(ident, None)
                if old is not None:
                    try:
                        self._root._delete_command(old[1])
                    except tkinter.TclError:
                        pass
                if command:
                    if not callable(command):
                        raise TypeError('command must be callable')
                    self._heading_commands[ident] = (command, self._root._add_command(command))
                options['command'] = bool(command)
            if 'image' in options:
                options['image'] = '' if options['image'] is None else str(options['image'])
            self._tree('heading', column, options)
            return {}
        text, image, anchor, has_command = self._tree('heading', column)
        ident = self._tree('column', column)[4]
        command = self._heading_commands.get(ident)
        info = {'text': text, 'image': image, 'anchor': anchor, 'command': command[1] if command else '', 'state': ''}
        if option is None:
            return info
        option = str(option).lstrip('-')
        if option not in info:
            raise tkinter.TclError('unknown option "-' + option + '"')
        return info[option]

    def set(self, item, column=None, value=None):
        """All of item's values as {column: value}, one value, or (with value) set it."""
        item = str(item)
        if column is None:
            pairs = self._tree('set', item)
            return dict((pairs[i], pairs[i + 1]) for i in range(0, len(pairs), 2))
        if value is None:
            return self._tree('set', item, str(column))
        self._tree('set', item, str(column), str(value))
        raw = self._raw_values.get(item)
        if raw is not None:
            self._raw_values.pop(item)
        return ''

    # ---- identify ------------------------------------------------------------------------------

    def identify(self, component, x, y):
        return self._tree('identify', str(component), int(x), int(y))

    def identify_row(self, y):
        return self.identify('row', 0, y)

    def identify_column(self, x):
        return self.identify('column', x, 0)

    def identify_region(self, x, y):
        return self.identify('region', x, y)

    def identify_element(self, x, y):
        return self.identify('element', x, y)

    # ---- tags ----------------------------------------------------------------------------------

    def tag_configure(self, tagname, option=None, **kw):
        """A tag's options (foreground and background are drawn; text, image, anchor, font recorded)."""
        tagname = str(tagname)
        recorded = self._tag_options.setdefault(tagname, {'text': '', 'image': '', 'anchor': '', 'background': '', 'foreground': '', 'font': ''})
        if kw:
            colors = {}
            for key, value in kw.items():
                key = tkinter._aliases.get(key, key)
                if key not in recorded:
                    raise tkinter.TclError('unknown option "-' + key + '"')
                if key in ('foreground', 'background'):
                    colors[key] = -1 if value in ('', None) else tkinter._color(value)
                recorded[key] = value
            if colors:
                self._tree('tag_configure', tagname, colors)
            return {}
        if option is None:
            return dict(recorded)
        option = str(option).lstrip('-')
        if option not in recorded:
            raise tkinter.TclError('unknown option "-' + option + '"')
        return recorded[option]

    def tag_has(self, tagname, item=None):
        if item is None:
            return tuple(self._tree('tag_has', str(tagname)))
        return bool(self._tree('tag_has', str(tagname), str(item)))

    def tag_bind(self, tagname, sequence=None, callback=None):
        """Bind callback to sequence on the rows that carry tagname (the row under the pointer)."""
        sequence = tkinter._event_aliases.get(sequence, sequence)
        key = (str(tagname), sequence)
        if callback is None:
            handlers = self._tag_bindings.get(key)
            return handlers.entries[0][1] if handlers is not None and handlers.entries else ''
        if not callable(callback):
            raise TypeError('binding must be callable')
        handlers = self._tag_bindings.get(key)
        if handlers is None:
            handlers = self._tag_bindings[key] = tkinter._Handlers()
        self._ensure(sequence)
        return handlers.add(self._root, callback, False)

    def tag_unbind(self, tagname, sequence, funcid=None):
        sequence = tkinter._event_aliases.get(sequence, sequence)
        handlers = self._tag_bindings.pop((str(tagname), sequence), None)
        if handlers is not None and funcid is not None:
            self._root._delete_command(str(funcid))

    def _ensure(self, sequence):
        # The display reports sequence here, through handlers that also run the tag bindings.
        if sequence in tkinter._sequences and self._handlers(sequence) is None:
            handlers = _TreeHandlers(self, sequence)
            callback = self._root._register(handlers)
            try:
                self._call('bind', self._id, sequence, callback)
            except Exception:
                self._root._callbacks.pop(callback, None)
                raise
            self._bindings[sequence] = callback
        elif not tkinter._virtual(sequence) and sequence not in tkinter._sequences:
            raise ValueError('unsupported event: ' + str(sequence))

    def bind(self, sequence, func, add=None):
        sequence = tkinter._event_aliases.get(sequence, sequence)
        if sequence in tkinter._sequences:
            self._ensure(sequence)
        return tkinter.Widget.bind(self, sequence, func, add)

    def unbind(self, sequence, funcid=None):
        sequence = tkinter._event_aliases.get(sequence, sequence)
        tkinter.Widget.unbind(self, sequence, funcid)
        if any(key[1] == sequence for key in self._tag_bindings):
            self._ensure(sequence)

    # ---- scrolling -------------------------------------------------------------------------------

    def xview(self, *args):
        result = self._tree('xview', *args)
        return tuple(result) if not args else None

    def yview(self, *args):
        result = self._tree('yview', *args)
        return tuple(result) if not args else None

    def xview_moveto(self, fraction):
        self.xview('moveto', float(fraction))

    def yview_moveto(self, fraction):
        self.yview('moveto', float(fraction))

    def xview_scroll(self, number, what):
        self.xview('scroll', int(number), str(what))

    def yview_scroll(self, number, what):
        self.yview('scroll', int(number), str(what))

    def _detach(self):
        for command in self._heading_commands.values():
            try:
                self._root._delete_command(command[1])
            except tkinter.TclError:
                pass
        self._heading_commands.clear()
        self._tag_bindings.clear()
        tkinter.Widget._detach(self)


class Panedwindow(Widget, tkinter.PanedWindow):
    """ttk::panedwindow: panes (add/insert, each with a -weight) separated by sashes the player
    drags; extra room is shared by weight and the last pane takes what is left, as in Tk.
    The sash is the theme's Sash sashthickness wide (5 in default, 6 in clam)."""
    _classic = tkinter.PanedWindow
    _ttk_class = 'TPanedwindow'
    _kind = 'ttk_panedwindow'
    _element = ''
    _state_option_ok = False
    _server_disabled = False
    _look_spec = (('background', 'background', ''),)

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        kw.setdefault('orient', 'vertical')
        tkinter.PanedWindow.__init__(self, master, **kw)
        self._ttk_done()

    forget = tkinter.PanedWindow.forget

    def keys(self):
        return ['class', 'cursor', 'height', 'orient', 'style', 'takefocus', 'width']

    def insert(self, pos, child, **kw):
        """Insert child at pos (an index, 'end', or a pane); a pane moves there."""
        child = self._widget(child)
        ids = self._pane_ids()
        if child._id not in ids:
            self._check_child(child)
        if pos == 'end':
            position = -1
        elif isinstance(pos, int) and not isinstance(pos, bool):
            if not 0 <= pos < max(1, len(ids)) or not ids and pos != 0:
                raise tkinter.TclError('Slave index ' + str(pos) + ' out of bounds')
            position = pos
        else:
            position = self._pane_index(pos)
        self._call('paned', self._id, 'insert', (position, child._id, self._weight(kw)))

    def pane(self, pane, option=None, **kw):
        """A pane's options ({'weight': n}), one of them, or set them (returns {})."""
        index = self._pane_index(pane)
        if option is not None and not kw:
            key = str(option).lstrip('-')
            if key != 'weight':
                raise tkinter.TclError('unknown option "-' + key + '"')
            return self._pane_options(index)[key]
        if kw:
            self._pane_options(index, self._weight(kw))
            return {}
        return self._pane_options(index)

    def sashpos(self, index, newpos=None):
        """Sash index's position; with newpos, move it there (pushing the others) and return where it went."""
        count = len(self._pane_ids()) - 1
        index = int(index)
        if not 0 <= index < count:
            raise tkinter.TclError('sash index ' + str(index) + ' out of range')
        args = (index,) if newpos is None else (index, int(newpos))
        return self._call('paned', self._id, 'sashpos', args)

    def identify(self, x, y):
        """The index of the sash at (x, y), or ''."""
        found = self._call('paned', self._id, 'identify', (int(x), int(y)))
        return found if found >= 0 else ''

    def _restyle(self):
        Widget._restyle(self)
        if not self._alive:
            return
        # The Sash style's thickness (Horizontal.Sash or Vertical.Sash, then Sash, then '.').
        themes, current = _registry(self)
        styles = themes[current[0]]['styles']
        prefix = 'Horizontal.' if str(self._paned['orient']) == 'horizontal' else 'Vertical.'
        value = _configured(styles, _style_chain(prefix + 'Sash'), 'sashthickness')
        try:
            thickness = int(float(str(value))) if value not in (None, '') else 5
        except ValueError:
            thickness = 5
        self._call('configure', self._id, {'sashthickness': max(0, thickness)})


PanedWindow = Panedwindow


class Menubutton(Widget, tkinter.Menubutton):
    """ttk::menubutton: posts its menu= below it (direction=), with a down arrow on the right."""
    _classic = tkinter.Menubutton
    _ttk_class = 'TMenubutton'
    _kind = 'ttk_menubutton'
    _element = 'label'
    _extra = {'compound': '', 'underline': -1}
    _takefocus_default = 'ttk::takefocus'
    _layout_spec = ('padding', 'borderwidth', 'anchor', 'width')

    def __init__(self, master=None, **kw):
        self._ttk_init(kw)
        tkinter.Menubutton.__init__(self, master, **kw)
        self._ttk_done()


class OptionMenu(Menubutton):
    """A ttk Menubutton showing variable's value, with a menu of radiobutton entries for the values
    (CPython's): default is set first; command(value) runs after a choice."""

    def __init__(self, master, variable, default=None, *values, **kwargs):
        kw = {'textvariable': variable}
        for key in ('style', 'direction'):
            value = kwargs.pop(key, None)
            if value is not None:
                kw[key] = value
        Menubutton.__init__(self, master, **kw)
        self['menu'] = tkinter.Menu(self, tearoff=False)
        self._om_variable = variable
        self._callback = kwargs.pop('command', None)
        if kwargs:
            raise tkinter.TclError('unknown option -' + next(iter(kwargs.keys())))
        self.set_menu(default, *values)

    def __getitem__(self, item):
        if item == 'menu':
            return self.nametowidget(Menubutton.__getitem__(self, item))
        return Menubutton.__getitem__(self, item)

    def set_menu(self, default=None, *values):
        """Build a new menu of radiobuttons with *values and optionally a default value."""
        menu = self['menu']
        menu.delete(0, 'end')
        for val in values:
            menu.add_radiobutton(label=val, command=(None if self._callback is None else (lambda val=val: self._callback(val))),
                                 variable=self._om_variable)
        if default:
            self._om_variable.set(default)

    def destroy(self):
        self._om_variable = None
        super().destroy()


def _to_number(x):
    if isinstance(x, str):
        x = float(x) if '.' in x else int(x)
    return x


class LabeledScale(Frame):
    """A ttk Scale with a Label above it (compound='top') or below it ('bottom') showing the value
    over the slider (CPython's). value reads and sets the variable; a value outside from_..to
    is put back to the last valid one."""

    def __init__(self, master=None, variable=None, from_=0, to=10, **kw):
        self._label_top = kw.pop('compound', 'top') == 'top'
        Frame.__init__(self, master, **kw)
        self._ls_variable = variable or tkinter.IntVar(master)
        self._ls_variable.set(from_)
        self._last_valid = from_
        self.label = Label(self)
        self.scale = Scale(self, variable=self._ls_variable, from_=from_, to=to)
        self.scale.bind('<<RangeChanged>>', self._adjust)
        scale_side = 'bottom' if self._label_top else 'top'
        label_side = 'top' if scale_side == 'bottom' else 'bottom'
        self.scale.pack(side=scale_side, fill='x')
        # A spacer of the label's height; the label itself is placed over the slider.
        dummy = Label(self)
        dummy.pack(side=label_side)
        dummy.lower()
        self.label.place(anchor='n' if label_side == 'top' else 's')
        self._tracecb = self._ls_variable.trace_add('write', self._adjust)
        self.bind('<Configure>', self._adjust)
        self._adjust()   # what Tk's <Map> binding does once the frame shows (MPY4OC has no <Map>)

    def destroy(self):
        try:
            self._ls_variable.trace_remove('write', self._tracecb)
        except AttributeError:
            pass
        else:
            self._ls_variable = None
        super().destroy()
        self.label = None
        self.scale = None

    def _adjust(self, *args):
        def adjust_label():
            if self.scale is None or not self.scale._alive:
                return
            self.update_idletasks()
            x, y = self.scale.coords()
            if self._label_top:
                y = self.scale.winfo_y() - self.label.winfo_reqheight()
            else:
                y = self.scale.winfo_reqheight() + self.label.winfo_reqheight()
            self.label.place_configure(x=x, y=y)

        from_ = _to_number(self.scale['from'])
        to = _to_number(self.scale['to'])
        if to < from_:
            from_, to = to, from_
        newval = self._ls_variable.get()
        if not from_ <= newval <= to:
            # The value is out of range: put back the last valid one.
            self.value = self._last_valid
            return
        self._last_valid = newval
        self.label['text'] = newval
        self.after_idle(adjust_label)

    @property
    def value(self):
        """The scale's current value."""
        return self._ls_variable.get()

    @value.setter
    def value(self, val):
        self._ls_variable.set(val)


# ---- styles ----------------------------------------------------------------------------
# Per root (each Tk() is its own Tcl interpreter in CPython): its themes, each with its parent
# theme, styles (name -> {'configure': {...}, 'map': {...}}), layouts and elements (name ->
# options), and the theme in use. The built-in themes start with Tk's own settings
# (tkinter/_ttkdata.py); answers are Tk's, and ttk widgets draw with them (see Widget._restyle).

def _new_theme(parent):
    return {'parent': parent, 'styles': {}, 'layouts': {}, 'elements': {}}


def _registry(master):
    root = master._root
    try:
        return root._ttk_registry
    except AttributeError:
        from tkinter import _ttkdata
        themes = {}
        for name in ('clam', 'alt', 'default', 'classic'):  # Tk lists them in this order
            theme = themes[name] = _new_theme(None if name == 'default' else 'default')
            data = _ttkdata.THEMES[name]
            for style, entry in data['styles'].items():
                theme['styles'][style] = {'configure': dict(entry['configure']),
                                          'map': dict((key, list(spec)) for key, spec in entry['map'].items())}
            theme['layouts'].update(data['layouts'])
            for element, options in data['elements']:
                theme['elements'][element] = options
        # The theme in use, whether a <<ThemeChanged>> round is already queued, a style change
        # waiting to be drawn.
        root._ttk_registry = (themes, ['default', False, False])
        return root._ttk_registry


def _style_chain(style):
    # 'A.B.TButton' -> 'A.B.TButton', 'B.TButton', 'TButton', '.'
    chain = []
    name = str(style)
    while name and name != '.':
        chain.append(name)
        dot = name.find('.')
        name = name[dot + 1:] if dot >= 0 else ''
    chain.append('.')
    return chain


def _option_name(key):
    key = str(key)
    return key[1:] if key.startswith('-') else key


def _map_spec(value):
    # [('pressed', 'blue'), ('active', '!disabled', 'green')]: states..., then the value.
    if isinstance(value, str):
        raise tkinter.TclError('Invalid statespec ' + value)
    spec = []
    for entry in value:
        entry = tuple(entry)
        if not entry:
            raise tkinter.TclError('Invalid statespec')
        states = entry[:-1]
        if len(states) == 1 and isinstance(states[0], (tuple, list)):
            states = tuple(states[0])
        if len(states) == 1 and isinstance(states[0], str) and ' ' in states[0]:
            states = tuple(states[0].split())
        _statespec([s for s in states if s != ''])
        spec.append(states + (entry[-1],))
    return spec


def _matches(states, flags):
    for item in states:
        if item == '':
            continue
        name = item[1:] if item.startswith('!') else item
        if (name in flags) == item.startswith('!'):
            return False
    return True


def _no_theme(name):
    return tkinter.TclError('theme "' + str(name) + '" doesn\'t exist')


class Style:
    """ttk styles and themes, with Tk's built-in themes (default, clam, alt, classic) as Tk has
    them. Styles and their settings belong to the theme in use (a theme does not see its
    parent's); only layouts and elements fall back to the parent theme. ttk widgets draw with
    the colours, reliefs, border widths, padding, anchors and minimum widths of their style in
    the theme in use; a change shows once the queued events are done (Tk redraws when idle)."""
    _name = 'ttk::style'

    def __init__(self, master=None):
        self.master = setup_master(master)
        self._themes, self._theme = _registry(self.master)

    def _changed(self):
        # Every ttk widget of this root draws again with the new settings, once (when idle).
        if self._theme[2]:
            return
        self._theme[2] = True
        root, state = self.master._root, self._theme

        def apply():
            state[2] = False
            _restyle_root(root)
        root.after_idle(apply)

    def _entry(self, style, create=False):
        styles = self._themes[self._theme[0]]['styles']
        entry = styles.get(style)
        if entry is None and create:
            entry = styles[style] = {'configure': {}, 'map': {}}
        return entry

    def configure(self, style, query_opt=None, **kw):
        if query_opt is not None:
            entry = self._entry(style)
            return entry['configure'].get(_option_name(query_opt), '') if entry is not None else ''
        if not kw:
            entry = self._entry(style)
            return dict(entry['configure']) if entry is not None and entry['configure'] else None
        options = self._entry(style, True)['configure']
        for key, value in kw.items():
            options[_option_name(key)] = value
        self._changed()
        return None

    def map(self, style, query_opt=None, **kw):
        if query_opt is not None:
            entry = self._entry(style)
            return list(entry['map'].get(_option_name(query_opt), [])) if entry is not None else []
        if not kw:
            entry = self._entry(style)
            return {key: list(value) for key, value in entry['map'].items()} if entry is not None else {}
        maps = self._entry(style, True)['map']
        for key, value in kw.items():
            maps[_option_name(key)] = _map_spec(value)
        self._changed()
        return {}

    def lookup(self, style, option, state=None, default=None):
        # Tk's order: the maps along the whole style chain first, then the plain settings.
        option = _option_name(option)
        flags = () if not state else [name for name, on in _statespec(state) if on]
        chain = _style_chain(style)
        for name in chain:
            entry = self._entry(name)
            if entry is not None and option in entry['map']:
                for spec in entry['map'][option]:
                    if _matches(spec[:-1], flags):
                        return spec[-1]
        for name in chain:
            entry = self._entry(name)
            if entry is not None and option in entry['configure']:
                return entry['configure'][option]
        return '' if default is None else default

    def layout(self, style, layoutspec=None):
        # A query takes the first layout along the style chain, in this theme and then its
        # parents; '' or [] sets the empty ('null') layout. Setting returns [] as Tk's ''.
        if layoutspec is None:
            theme = self._theme[0]
            seen = []
            while theme is not None and theme not in seen:
                seen.append(theme)
                for name in _style_chain(style)[:-1]:
                    layout = self._themes[theme]['layouts'].get(name)
                    if layout is not None:
                        return list(layout)
                theme = self._themes[theme]['parent']
            raise tkinter.TclError('Layout ' + str(style) + ' not found')
        layout = list(layoutspec) if layoutspec else [('null', {'sticky': 'nswe'})]
        self._themes[self._theme[0]]['layouts'][style] = layout
        return []

    def element_create(self, elementname, etype, *args, **kw):
        if etype != 'from':
            raise tkinter.TclError('element type "' + str(etype) + '" is not supported here')
        if not args or args[0] not in self._themes:
            raise _no_theme(args[0] if args else '')
        source = self._element(args[0], str(args[1]) if len(args) > 1 else str(elementname))
        self._themes[self._theme[0]]['elements'][str(elementname)] = source if source is not None else ()

    def _element(self, theme, name):
        # Tk's element lookup: the name, then without its prefixes ('Button.border', 'border'),
        # each in the theme and then its parents.
        candidates = [name]
        while '.' in name:
            name = name[name.index('.') + 1:]
            candidates.append(name)
        for candidate in candidates:
            current, seen = theme, []
            while current is not None and current not in seen:
                seen.append(current)
                options = self._themes[current]['elements'].get(candidate)
                if options is not None:
                    return options
                current = self._themes[current]['parent']
        return None

    def element_names(self):
        return tuple(self._themes[self._theme[0]]['elements'])

    def element_options(self, elementname):
        options = self._element(self._theme[0], str(elementname))
        return tuple(options) if options is not None else ()

    def theme_create(self, themename, parent=None, settings=None):
        if themename in self._themes:
            raise tkinter.TclError('Theme ' + str(themename) + ' already exists')
        if parent is not None and parent not in self._themes:
            raise _no_theme(parent)
        self._themes[themename] = _new_theme(parent if parent is not None else 'default')
        if settings:
            self.theme_settings(themename, settings)

    def theme_settings(self, themename, settings):
        # Unknown keys are skipped, as CPython's tkinter does.
        if themename not in self._themes:
            raise _no_theme(themename)
        saved = self._theme[0]
        self._theme[0] = themename
        try:
            for style, parts in settings.items():
                for kind, value in parts.items():
                    if kind == 'configure':
                        self.configure(style, **value)
                    elif kind == 'map':
                        self.map(style, **value)
                    elif kind == 'layout':
                        self.layout(style, value)
                    elif kind == 'element create':
                        self.element_create(style, *value)
        finally:
            self._theme[0] = saved
        self._changed()

    def theme_names(self):
        return tuple(self._themes)

    def theme_use(self, themename=None):
        if themename is None:
            return self._theme[0]
        if themename not in self._themes:
            raise tkinter.TclError("can't find package ttk::theme::" + str(themename))
        self._theme[0] = themename
        self._changed()
        # <<ThemeChanged>> goes to every widget of this root, outer ones first, once the
        # queued events are done; several theme changes before then make one round, as in Tk.
        # Each widget runs its own binding and its window's, as Tk's bindtags do.
        if self._theme[1]:
            return
        self._theme[1] = True
        root = self.master._root
        state = self._theme

        def changed():
            state[1] = False
            order = [root]
            index = 0
            while index < len(order):
                order.extend(order[index]._children)
                index += 1
            for widget in order:
                if widget._alive:
                    widget.event_generate('<<ThemeChanged>>')

        root.after(0, changed)
