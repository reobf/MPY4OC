"""tkinter.messagebox for MPY4OC: CPython's functions (showinfo, showwarning, showerror,
askquestion, askokcancel, askyesno, askyesnocancel, askretrycancel) and Message, with Tk 8.6's
tk_messageBox: a modal dialog (an icon, the message, the detail, a row of buttons) centred over
its parent, the default button holding the keyboard (Return presses it); the close box picks
the cancel answer. Option checks and messages are Tk's. Escape does not cancel here (the Tk
display's view keeps Escape): use the buttons or the close box.
"""
import tkinter
from tkinter import ttk
from tkinter.commondialog import Dialog, _parent, _modal

__all__ = ['showinfo', 'showwarning', 'showerror', 'askquestion', 'askokcancel', 'askyesno',
           'askyesnocancel', 'askretrycancel']

# icons
ERROR = "error"
INFO = "info"
QUESTION = "question"
WARNING = "warning"

# types
ABORTRETRYIGNORE = "abortretryignore"
OK = "ok"
OKCANCEL = "okcancel"
RETRYCANCEL = "retrycancel"
YESNO = "yesno"
YESNOCANCEL = "yesnocancel"

# replies
ABORT = "abort"
RETRY = "retry"
IGNORE = "ignore"
OK = "ok"
CANCEL = "cancel"
YES = "yes"
NO = "no"

# Tk's msgbox.tcl: each type's buttons, and the one the close box (and Escape) chooses.
_TYPES = {
    'abortretryignore': (('abort', 'retry', 'ignore'), 'abort'),
    'ok': (('ok',), 'ok'),
    'okcancel': (('ok', 'cancel'), 'cancel'),
    'retrycancel': (('retry', 'cancel'), 'cancel'),
    'yesno': (('yes', 'no'), 'no'),
    'yesnocancel': (('yes', 'no', 'cancel'), 'cancel'),
}
_LABELS = {'abort': 'Abort', 'retry': 'Retry', 'ignore': 'Ignore', 'ok': 'OK', 'cancel': 'Cancel', 'yes': 'Yes', 'no': 'No'}
# The icon: a letter on a coloured square (Tk shows bitmaps).
_ICONS = {'error': ('x', '#ffffff', '#c0392b'), 'info': ('i', '#ffffff', '#2456a6'),
          'question': ('?', '#ffffff', '#2456a6'), 'warning': ('!', '#000000', '#f0c040')}
_OPTIONS = ('default', 'detail', 'icon', 'message', 'parent', 'title', 'type')


class Message(Dialog):
    "A message box"

    command = "tk_messageBox"

    def _run(self, master):
        options = self.options
        for key in options:
            if key not in _OPTIONS:
                raise tkinter.TclError('bad option "-' + key + '": must be -default, -detail, -icon, -message, -parent, -title, or -type')
        kind = str(options.get('type') or 'ok')
        if kind not in _TYPES:
            raise tkinter.TclError('bad -type value "' + kind + '": must be abortretryignore, ok, okcancel, retrycancel, yesno, or yesnocancel')
        icon = str(options.get('icon') or 'info')
        if icon not in _ICONS:
            raise tkinter.TclError('bad -icon value "' + icon + '": must be error, info, question, or warning')
        names, cancel = _TYPES[kind]
        default = str(options.get('default') or names[0])
        if default not in names:
            raise tkinter.TclError('bad -default value "' + default + '": must be abort, retry, ignore, ok, cancel, no, or yes')
        parent = _parent(options, master)
        return _message_box(parent, str(options.get('title') or ''), str(options.get('message') or ''),
                            str(options.get('detail') or ''), icon, names, cancel, default)


def _message_box(parent, title, message, detail, icon, names, cancel, default):
    answer = [cancel]
    w = tkinter.Toplevel(parent, name='__tk__messagebox')
    w.withdraw()
    w.title(title)
    w.resizable(False, False)

    def choose(name):
        answer[0] = name
        w.destroy()

    w.protocol('WM_DELETE_WINDOW', lambda: choose(cancel))
    bot = ttk.Frame(w, name='bot')
    bot.pack(side='bottom', fill='both')
    top = ttk.Frame(w, name='top')
    top.pack(side='top', fill='both', expand=True)
    # Tk wraps at 3 inches; a small display gets less.
    wrap = max(80, min(288, w.winfo_screenwidth() - 64))
    glyph, fg, bg = _ICONS[icon]
    bitmap = ttk.Label(top, name='bitmap', text=glyph, foreground=fg, background=bg, anchor='center', width=2)
    msg = ttk.Label(top, name='msg', text=message, wraplength=wrap, justify='left', anchor='nw')
    bitmap.grid(row=0, column=0, rowspan=2, sticky='n', padx=6, pady=6)
    msg.grid(row=0, column=1, sticky='nsew', padx=6, pady=6)
    if detail:
        dtl = ttk.Label(top, name='dtl', text=detail, wraplength=wrap, justify='left', anchor='nw')
        dtl.grid(row=1, column=1, sticky='nsew', padx=6, pady=(0, 6))
    focus = None
    for column, name in enumerate(names):
        button = ttk.Button(bot, name=name, text=_LABELS[name], command=lambda name=name: choose(name),
                            default='active' if name == default else 'normal')
        button.grid(row=0, column=column, padx=6, pady=6, sticky='ew')
        if name == default:
            focus = button
    _modal(w, parent, focus)
    return answer[0]


def _show(title=None, message=None, _icon=None, _type=None, **options):
    if _icon and "icon" not in options:    options["icon"] = _icon
    if _type and "type" not in options:    options["type"] = _type
    if title:   options["title"] = title
    if message: options["message"] = message
    res = Message(**options).show()
    # In some Tcl installations, yes/no is converted into a boolean.
    if isinstance(res, bool):
        if res:
            return YES
        return NO
    # In others we get a Tcl_Obj.
    return str(res)


def showinfo(title=None, message=None, **options):
    "Show an info message"
    return _show(title, message, INFO, OK, **options)


def showwarning(title=None, message=None, **options):
    "Show a warning message"
    return _show(title, message, WARNING, OK, **options)


def showerror(title=None, message=None, **options):
    "Show an error message"
    return _show(title, message, ERROR, OK, **options)


def askquestion(title=None, message=None, **options):
    "Ask a question"
    return _show(title, message, QUESTION, YESNO, **options)


def askokcancel(title=None, message=None, **options):
    "Ask if operation should proceed; return true if the answer is ok"
    s = _show(title, message, QUESTION, OKCANCEL, **options)
    return s == OK


def askyesno(title=None, message=None, **options):
    "Ask a question; return true if the answer is yes"
    s = _show(title, message, QUESTION, YESNO, **options)
    return s == YES


def askyesnocancel(title=None, message=None, **options):
    "Ask a question; return true if the answer is yes, None if cancelled."
    s = _show(title, message, QUESTION, YESNOCANCEL, **options)
    # s might be a Tcl index object, so convert it to a string
    s = str(s)
    if s == CANCEL:
        return None
    return s == YES


def askretrycancel(title=None, message=None, **options):
    "Ask if operation should be retried; return true if the answer is yes"
    s = _show(title, message, WARNING, RETRYCANCEL, **options)
    return s == RETRY
