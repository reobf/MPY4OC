"""tkinter.filedialog for MPY4OC: CPython's functions (askopenfilename, asksaveasfilename,
askopenfilenames, askopenfile, askopenfiles, asksaveasfile, askdirectory), the Open, SaveAs and
Directory classes, and the older FileDialog, LoadFileDialog and SaveFileDialog.

The dialogs are Tk 8.6's X11 ones (tk_getOpenFile, tk_getSaveFile, tk_chooseDirectory) drawn
with tkinter: "Directory:" with a menu of the folders above and an up button, the list of
folders and files, "File name:", "Files of type:", and Open/Save with Cancel. Double-click a
folder to go in, a file to choose it; typing a folder or a pattern (*.py) in the name goes there
or filters. Opening a file that is not there, or saving over one that is, asks as Tk does.
Cancelling gives () (as tkinter does with Tk 8.6), asksaveasfilename ''. Files come from the
os module's filesystem (MPYOS installs it); without one the dialogs raise RuntimeError.
"""
import os as _os
import tkinter
from tkinter import ttk, messagebox
from tkinter import (Frame, Button, Entry, Listbox, Scrollbar, Toplevel, END, LEFT, RIGHT, TOP, BOTTOM, BOTH, X, Y, YES)
from tkinter.commondialog import Dialog, _parent, _modal

__all__ = ["FileDialog", "LoadFileDialog", "SaveFileDialog",
           "Open", "SaveAs", "Directory",
           "askopenfilename", "asksaveasfilename", "askopenfilenames",
           "askopenfile", "askopenfiles", "asksaveasfile", "askdirectory"]


# ---- paths (the filesystem's '/' paths; no os.path needed) ------------------------------------

def _fs():
    if not hasattr(_os, 'listdir'):
        raise RuntimeError('filedialog needs a filesystem: the os module has none (boot MPYOS)')
    return _os


def _cwd():
    try:
        return _os.getcwd()
    except Exception:
        return '/'


def _norm(path):
    parts = []
    for part in path.split('/'):
        if part in ('', '.'):
            continue
        if part == '..':
            if parts:
                parts.pop()
            continue
        parts.append(part)
    return '/' + '/'.join(parts)


def _join(directory, name):
    return _norm(name if name.startswith('/') else directory.rstrip('/') + '/' + name)


def _split(path):
    path = _norm(path)
    cut = path.rfind('/')
    return (path[:cut] or '/'), path[cut + 1:]


def _isdir(path):
    try:
        return bool(_fs().stat(path)[0] & 0x4000)
    except OSError:
        return False


def _exists(path):
    try:
        _fs().stat(path)
        return True
    except OSError:
        return False


def _listdir(path):
    try:
        names = [n for n in _fs().listdir(path) if not n.startswith('.')]
    except OSError:
        return [], []
    names.sort(key=lambda n: n.lower())
    dirs, files = [], []
    for name in names:
        (dirs if _isdir(_join(path, name)) else files).append(name)
    return dirs, files


def _match(pattern, name):
    # fnmatch: * any run, ? one character, [seq] / [!seq] one of (not of) them.
    p = t = 0
    star, mark = -1, 0
    while t < len(name):
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
                end = pattern.find(']', p + 2)
                if end > p:
                    body = pattern[p + 1:end]
                    negate = body[:1] == '!'
                    if negate:
                        body = body[1:]
                    ok, i = False, 0
                    while i < len(body):
                        if i + 2 < len(body) and body[i + 1] == '-':
                            ok = ok or body[i] <= name[t] <= body[i + 2]
                            i += 3
                        else:
                            ok = ok or body[i] == name[t]
                            i += 1
                    if ok != negate:
                        p = end + 1
                        t += 1
                        continue
            elif c == name[t]:
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


def _patterns(spec):
    # A filetype's patterns: "*.txt *.py", ('*.txt', '*.py'), or '.txt' (Tk reads it as *.txt).
    words = spec.split() if isinstance(spec, str) else [str(w) for w in spec]
    out = []
    for word in words:
        if word.startswith('.') and '*' not in word:
            word = '*' + word
        out.append(word)
    return out or ['*']


def _tcl_words(text):
    # The names a multiple-selection entry holds: words, or "quoted names" / {braced names}.
    words, i = [], 0
    while i < len(text):
        while i < len(text) and text[i] == ' ':
            i += 1
        if i >= len(text):
            break
        if text[i] in '"{':
            close = '"' if text[i] == '"' else '}'
            end = text.find(close, i + 1)
            end = len(text) if end < 0 else end
            words.append(text[i + 1:end])
            i = end + 1
        else:
            end = text.find(' ', i)
            end = len(text) if end < 0 else end
            words.append(text[i:end])
            i = end
    return words


# ---- the Tk-style dialogs -----------------------------------------------------------------

_OPEN_OPTIONS = ('defaultextension', 'filetypes', 'initialdir', 'initialfile', 'multiple', 'parent', 'title', 'typevariable')
_SAVE_OPTIONS = ('confirmoverwrite', 'defaultextension', 'filetypes', 'initialdir', 'initialfile', 'parent', 'title', 'typevariable')
_DIR_OPTIONS = ('initialdir', 'mustexist', 'parent', 'title')


def _check(options, allowed):
    for key, value in options.items():
        if key not in allowed:
            raise tkinter.TclError('bad option "-' + key + '": must be ' + ', '.join('-' + k for k in allowed[:-1]) + ', or -' + allowed[-1])


class _FileBox:
    """One file dialog (tkfbox.tcl's): mode 'open', 'save' or 'dir'."""

    def __init__(self, parent, mode, options):
        self.mode = mode
        self.options = options
        self.result = () if mode != 'save' else ''
        self.multiple = mode == 'open' and tkinter._tcl_boolean(options.get('multiple', 0))
        start = str(options.get('initialdir') or '') or _cwd()
        start = _join(_cwd(), start)
        if not _isdir(start):
            start = _cwd()
        self.directory = start
        types = list(options.get('filetypes') or ())
        self.types = [(str(label), _patterns(spec)) for label, spec in types] if types else [('All Files', ['*'])]
        self.type = 0
        variable = options.get('typevariable')
        if isinstance(variable, tkinter.Variable):
            for i, (label, _) in enumerate(self.types):
                if label == str(variable.get()):
                    self.type = i
        titles = {'open': 'Open', 'save': 'Save As', 'dir': 'Choose Directory'}
        w = self.window = Toplevel(parent, name='__tk_filedialog' if mode != 'dir' else '__tk_choosedir')
        w.withdraw()
        w.title(str(options.get('title') or titles[mode]))
        w.protocol('WM_DELETE_WINDOW', self.cancel)
        contents = ttk.Frame(w, name='contents')
        contents.pack(fill=BOTH, expand=True)
        f1 = ttk.Frame(contents, name='f1')
        f1.pack(side=TOP, fill=X, padx=4, pady=2)
        ttk.Label(f1, name='lab', text='Directory:').pack(side=LEFT)
        self.dirmenu = ttk.Menubutton(f1, name='menu', width=1)
        self.dirmenu['menu'] = tkinter.Menu(self.dirmenu, name='menu', tearoff=0)
        self.dirmenu.pack(side=LEFT, fill=X, expand=True, padx=2)
        ttk.Button(f1, name='up', text='Up', width=3, command=self.up).pack(side=LEFT)
        f2 = ttk.Frame(contents, name='f2')
        f2.pack(side=BOTTOM, fill=X, padx=4, pady=2)
        icons = ttk.Frame(contents, name='icons')
        icons.pack(side=TOP, fill=BOTH, expand=True, padx=4)
        self.list = Listbox(icons, name='list', height=6, width=24, selectmode='extended' if self.multiple else 'browse',
                            exportselection=0)
        bar = ttk.Scrollbar(icons, name='sbar', orient='vertical', command=self.list.yview)
        self.list.configure(yscrollcommand=bar.set)
        bar.pack(side=RIGHT, fill=Y)
        self.list.pack(side=LEFT, fill=BOTH, expand=True)
        self.list.bind('<<ListboxSelect>>', self.selected)
        self.list.bind('<Double-Button-1>', self.double)
        ttk.Label(f2, name='lab', text='Selection:' if mode == 'dir' else 'File name:').grid(row=0, column=0, sticky='w')
        self.entry = ttk.Entry(f2, name='ent')
        self.entry.grid(row=0, column=1, sticky='ew', padx=2)
        self.entry.bind('<Return>', lambda event: self.ok())
        ok = ttk.Button(f2, name='ok', text={'open': 'Open', 'save': 'Save', 'dir': 'OK'}[mode], default='active', command=self.ok)
        ok.grid(row=0, column=2, sticky='ew')
        if mode != 'dir':
            ttk.Label(f2, name='lab2', text='Files of type:').grid(row=1, column=0, sticky='w')
            self.typemenu = ttk.Menubutton(f2, name='menu', width=1)
            menu = tkinter.Menu(self.typemenu, name='m', tearoff=0)
            for i, (label, patterns) in enumerate(self.types):
                menu.add_command(label=self._type_label(i), command=lambda i=i: self.set_type(i))
            self.typemenu['menu'] = menu
            self.typemenu.grid(row=1, column=1, sticky='ew', padx=2)
            self.typemenu.configure(text=self._type_label(self.type))
        ttk.Button(f2, name='cancel', text='Cancel', command=self.cancel).grid(row=1, column=2, sticky='ew')
        f2.grid_columnconfigure(1, weight=1)
        initial = str(options.get('initialfile') or '')
        if initial:
            self.entry.insert(0, initial)
            self.entry.select_range(0, END)
        self.filter = None
        self.load()
        _modal(w, parent, self.entry)

    def _type_label(self, i):
        label, patterns = self.types[i]
        return label + ' (' + ' '.join(patterns) + ')'

    def set_type(self, i):
        self.type = i
        self.filter = None
        self.typemenu.configure(text=self._type_label(i))
        variable = self.options.get('typevariable')
        if isinstance(variable, tkinter.Variable):
            variable.set(self.types[i][0])
        self.load()

    def load(self):
        # The directory's folders, then the files the filter (or the file type) lets through.
        self.dirmenu.configure(text=self.directory)
        menu = self.dirmenu['menu']
        menu = self.dirmenu.nametowidget(menu) if isinstance(menu, str) else menu
        menu.delete(0, END)
        path = self.directory
        while True:
            menu.add_command(label=path, command=lambda p=path: self.enter(p))
            if path == '/':
                break
            path = _split(path)[0]
        dirs, files = _listdir(self.directory)
        patterns = [self.filter] if self.filter else self.types[self.type][1] if self.mode != 'dir' else []
        shown = [d + '/' for d in dirs]
        if self.mode != 'dir':
            shown += [f for f in files if any(_match(p, f) for p in patterns)]
        self.list.delete(0, END)
        for name in shown:
            self.list.insert(END, name)

    def enter(self, path):
        self.directory = _norm(path)
        self.filter = None
        self.load()

    def up(self):
        self.enter(_split(self.directory)[0])

    def selected(self, event=None):
        names = [self.list.get(i) for i in self.list.curselection()]
        if self.mode == 'dir':
            if names:
                self.entry.delete(0, END)
                self.entry.insert(0, names[0].rstrip('/'))
            return
        files = [n for n in names if not n.endswith('/')]
        if not files:
            return
        self.entry.delete(0, END)
        if self.multiple and len(files) > 1:
            self.entry.insert(0, ' '.join('"' + f + '"' for f in files))
        else:
            self.entry.insert(0, files[0])

    def double(self, event=None):
        names = [self.list.get(i) for i in self.list.curselection()]
        if not names:
            return
        if names[0].endswith('/'):
            self.enter(_join(self.directory, names[0]))
            self.entry.delete(0, END)
        else:
            self.ok()

    def warn(self, message):
        messagebox.showwarning(message=message, parent=self.window, icon='warning')

    def ok(self):
        text = self.entry.get().strip()
        if self.mode == 'dir':
            path = _join(self.directory, text) if text else self.directory
            if text and _isdir(path):
                if path != self.directory:
                    self.enter(path)
                    self.entry.delete(0, END)
                    return
            if tkinter._tcl_boolean(self.options.get('mustexist', 0)) and not _isdir(path):
                self.warn('Directory "' + path + '" does not exist.')
                return
            self.finish(path)
            return
        if not text:
            names = [self.list.get(i) for i in self.list.curselection()]
            if names and names[0].endswith('/'):
                self.enter(_join(self.directory, names[0]))
            return
        names = _tcl_words(text) if self.multiple else [text]
        if len(names) == 1:
            path = _join(self.directory, names[0])
            if _isdir(path):
                self.enter(path)
                self.entry.delete(0, END)
                return
            if any(c in names[0] for c in '*?['):
                folder, pattern = _split(path)
                if _isdir(folder):
                    self.directory, self.filter = folder, pattern
                    self.load()
                    self.entry.delete(0, END)
                    return
        paths = []
        for name in names:
            path = _join(self.directory, name)
            folder, base = _split(path)
            if self.mode == 'save':
                extension = str(self.options.get('defaultextension') or '')
                if extension and '.' not in base:
                    path += extension if extension.startswith('.') else '.' + extension
                if not _isdir(folder):
                    self.warn('Directory "' + folder + '" does not exist.')
                    return
                if _exists(path) and tkinter._tcl_boolean(self.options.get('confirmoverwrite', 1)):
                    if not messagebox.askyesno(message='File "' + path + '" already exists.\nDo you want to overwrite it?',
                                               icon='warning', parent=self.window):
                        return
            elif not _exists(path):
                self.warn('File "' + path + '" does not exist.')
                return
            paths.append(path)
        self.finish(tuple(paths) if self.multiple else paths[0])

    def finish(self, result):
        self.result = result
        self.window.destroy()

    def cancel(self):
        self.window.destroy()


class _Dialog(Dialog):

    def _fixoptions(self):
        try:
            # make sure "filetypes" is a tuple
            self.options["filetypes"] = tuple(self.options["filetypes"])
        except KeyError:
            pass

    def _fixresult(self, widget, result):
        if result:
            # keep directory and filename until next time
            path, file = _split(result)
            self.options["initialdir"] = path
            self.options["initialfile"] = file
        self.filename = result # compatibility
        return result


#
# file dialogs

class Open(_Dialog):
    "Ask for a filename to open"

    command = "tk_getOpenFile"

    def _run(self, master):
        options = dict((k, v) for k, v in self.options.items() if v is not None)
        _check(options, _OPEN_OPTIONS)
        _fs()
        return _FileBox(_parent(options, master), 'open', options).result

    def _fixresult(self, widget, result):
        if isinstance(result, tuple):
            # multiple results:
            result = tuple([getattr(r, "string", r) for r in result])
            if result:
                path, file = _split(result[0])
                self.options["initialdir"] = path
                # don't set initialfile or filename, as we have multiple of these
            return result
        return _Dialog._fixresult(self, widget, result)


class SaveAs(_Dialog):
    "Ask for a filename to save as"

    command = "tk_getSaveFile"

    def _run(self, master):
        options = dict((k, v) for k, v in self.options.items() if v is not None)
        _check(options, _SAVE_OPTIONS)
        _fs()
        return _FileBox(_parent(options, master), 'save', options).result


# the directory dialog has its own _fix routines.
class Directory(Dialog):
    "Ask for a directory"

    command = "tk_chooseDirectory"

    def _run(self, master):
        options = dict((k, v) for k, v in self.options.items() if v is not None)
        _check(options, _DIR_OPTIONS)
        _fs()
        return _FileBox(_parent(options, master), 'dir', options).result

    def _fixresult(self, widget, result):
        if result:
            # keep directory until next time
            self.options["initialdir"] = result
        self.directory = result # compatibility
        return result

#
# convenience stuff


def askopenfilename(**options):
    "Ask for a filename to open"

    return Open(**options).show()


def asksaveasfilename(**options):
    "Ask for a filename to save as"

    return SaveAs(**options).show()


def askopenfilenames(**options):
    """Ask for multiple filenames to open

    Returns a list of filenames or empty list if
    cancel button selected
    """
    options["multiple"]=1
    return Open(**options).show()

# FIXME: are the following  perhaps a bit too convenient?


def askopenfile(mode = "r", **options):
    "Ask for a filename to open, and returned the opened file"

    filename = Open(**options).show()
    if filename:
        return open(filename, mode)
    return None


def askopenfiles(mode = "r", **options):
    """Ask for multiple filenames and return the open file
    objects

    returns a list of open file objects or an empty list if
    cancel selected
    """

    files = askopenfilenames(**options)
    if files:
        ofiles=[]
        for filename in files:
            ofiles.append(open(filename, mode))
        files=ofiles
    return files


def asksaveasfile(mode = "w", **options):
    "Ask for a filename to save as, and returned the opened file"

    filename = SaveAs(**options).show()
    if filename:
        return open(filename, mode)
    return None


def askdirectory (**options):
    "Ask for a directory, and return the file name"
    return Directory(**options).show()


# ---- the older dialogs (CPython's FileDialog family) ------------------------------------------

dialogstates = {}


class FileDialog:

    """Standard file selection dialog -- no checks on selected file.

    Usage:

        d = FileDialog(master)
        fname = d.go(dir_or_file, pattern, default, key)
        if fname is None: ...canceled...
        else: ...open file...

    All arguments to go() are optional.

    The 'key' argument specifies a key in the global dictionary
    'dialogstates', which keeps track of the values for the directory
    and pattern arguments, overriding the values passed in (it does
    not keep track of the default argument!).  If no key is specified,
    the dialog keeps no memory of previous state.  Note that memory is
    kept even when the dialog is canceled.  (All this emulates the
    behavior of the Macintosh file selection dialogs.)

    """

    title = "File Selection Dialog"

    def __init__(self, master, title=None):
        if title is None: title = self.title
        self.master = master
        self.directory = None

        self.top = Toplevel(master)
        self.top.title(title)
        self.top.iconname(title)

        self.botframe = Frame(self.top)
        self.botframe.pack(side=BOTTOM, fill=X)

        self.selection = Entry(self.top)
        self.selection.pack(side=BOTTOM, fill=X)
        self.selection.bind('<Return>', self.ok_event)

        self.filter = Entry(self.top)
        self.filter.pack(side=TOP, fill=X)
        self.filter.bind('<Return>', self.filter_command)

        self.midframe = Frame(self.top)
        self.midframe.pack(expand=YES, fill=BOTH)

        self.filesbar = Scrollbar(self.midframe)
        self.filesbar.pack(side=RIGHT, fill=Y)
        self.files = Listbox(self.midframe, exportselection=0, width=14, height=6,
                             yscrollcommand=self.filesbar.set)
        self.files.pack(side=RIGHT, expand=YES, fill=BOTH)
        self.files.bind('<<ListboxSelect>>', self.files_select_event)
        self.files.bind('<Double-Button-1>', self.files_double_event)
        self.filesbar.config(command=self.files.yview)

        self.dirsbar = Scrollbar(self.midframe)
        self.dirsbar.pack(side=LEFT, fill=Y)
        self.dirs = Listbox(self.midframe, exportselection=0, width=10, height=6,
                            yscrollcommand=self.dirsbar.set)
        self.dirs.pack(side=LEFT, expand=YES, fill=BOTH)
        self.dirsbar.config(command=self.dirs.yview)
        self.dirs.bind('<<ListboxSelect>>', self.dirs_select_event)
        self.dirs.bind('<Double-Button-1>', self.dirs_double_event)

        self.ok_button = Button(self.botframe,
                                 text="OK",
                                 command=self.ok_command)
        self.ok_button.pack(side=LEFT)
        self.filter_button = Button(self.botframe,
                                    text="Filter",
                                    command=self.filter_command)
        self.filter_button.pack(side=LEFT, expand=YES)
        self.cancel_button = Button(self.botframe,
                                    text="Cancel",
                                    command=self.cancel_command)
        self.cancel_button.pack(side=RIGHT)

        self.top.protocol('WM_DELETE_WINDOW', self.cancel_command)

    def go(self, dir_or_file=None, pattern="*", default="", key=None):
        if dir_or_file is None:
            dir_or_file = _cwd()
        if key and key in dialogstates:
            self.directory, pattern = dialogstates[key]
        else:
            dir_or_file = _join(_cwd(), dir_or_file)
            if _isdir(dir_or_file):
                self.directory = dir_or_file
            else:
                self.directory, default = _split(dir_or_file)
        self.set_filter(self.directory, pattern)
        self.set_selection(default)
        self.filter_command()
        self.selection.focus_set()
        self.top.wait_visibility() # window needs to be visible for the grab
        self.top.grab_set()
        self.how = None
        self.master.mainloop()          # Exited by self.quit(how)
        if key:
            directory, pattern = self.get_filter()
            if self.how:
                directory = _split(self.how)[0]
            dialogstates[key] = directory, pattern
        self.top.destroy()
        return self.how

    def quit(self, how=None):
        self.how = how
        self.master.quit()              # Exit mainloop()

    def dirs_double_event(self, event):
        self.filter_command()

    def dirs_select_event(self, event):
        dir, pat = self.get_filter()
        subdir = self.dirs.get('active')
        dir = _join(self.directory, subdir)
        self.set_filter(dir, pat)

    def files_double_event(self, event):
        self.ok_command()

    def files_select_event(self, event):
        file = self.files.get('active')
        self.set_selection(file)

    def ok_event(self, event):
        self.ok_command()

    def ok_command(self):
        self.quit(self.get_selection())

    def filter_command(self, event=None):
        dir, pat = self.get_filter()
        try:
            names = _fs().listdir(dir)
        except OSError:
            self.master.bell()
            return
        self.directory = dir
        self.set_filter(dir, pat)
        names.sort()
        subdirs = ['..']
        matchingfiles = []
        for name in names:
            fullname = _join(dir, name)
            if _isdir(fullname):
                subdirs.append(name)
            elif _match(pat, name):
                matchingfiles.append(name)
        self.dirs.delete(0, END)
        for name in subdirs:
            self.dirs.insert(END, name)
        self.files.delete(0, END)
        for name in matchingfiles:
            self.files.insert(END, name)
        head, tail = _split(self.get_selection())
        if tail == '.': tail = ''
        self.set_selection(tail)

    def get_filter(self):
        filter = self.filter.get()
        filter = _join(_cwd(), filter)
        if filter[-1:] == '/' or _isdir(filter):
            filter = _join(filter, "*")
        return _split(filter)

    def get_selection(self):
        file = self.selection.get()
        file = _join(_cwd(), file)
        return file

    def cancel_command(self, event=None):
        self.quit()

    def set_filter(self, dir, pat):
        dir = _join(_cwd(), dir)
        self.filter.delete(0, END)
        self.filter.insert(END, _join(dir or '/', pat or "*"))

    def set_selection(self, file):
        self.selection.delete(0, END)
        self.selection.insert(END, _join(self.directory, file))


class LoadFileDialog(FileDialog):

    """File selection dialog which checks that the file exists."""

    title = "Load File Selection Dialog"

    def ok_command(self):
        file = self.get_selection()
        if not _exists(file) or _isdir(file):
            self.master.bell()
        else:
            self.quit(file)


class SaveFileDialog(FileDialog):

    """File selection dialog which checks that the file may be created."""

    title = "Save File Selection Dialog"

    def ok_command(self):
        file = self.get_selection()
        if _exists(file):
            if _isdir(file):
                self.master.bell()
                return
            d = messagebox.askyesno(title="Overwrite Existing File Question",
                                    message="Overwrite existing file %r?" % (file,), parent=self.top)
            if not d:
                return
        else:
            head, tail = _split(file)
            if not _isdir(head):
                self.master.bell()
                return
        self.quit(file)
