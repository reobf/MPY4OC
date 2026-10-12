"""tkedit: a text editor on a Tk display (UTF-8 plain text).

usage: tkedit [file]

It runs in the background (as `tkedit file &`): the shell prompt comes back at once
and the editor stays open until it is closed (-w, the old switch for this, is still
accepted and does nothing). The editor is a maximized window
on the Tk display this computer already uses (else the first one no other running
computer holds); several editors share it, a window each.

Ctrl+S save, Ctrl+Shift+S save as, Ctrl+O open, Ctrl+N new, Ctrl+Q quit,
Ctrl+F find, F3 / Shift+F3 next / previous, Ctrl+H replace, Ctrl+G go to line,
Ctrl+A select all, Ctrl+C / Ctrl+X / Ctrl+V copy / cut / paste.
Click the line:column at the bottom right to go to a line. A file is at most
262143 characters, 512 KB and 20000 lines (the Text widget's limits). Saving
writes a temporary file first, so a failed save leaves the old file as it was.
"""
import tkinter as tk

# LWJGL key codes of the shortcuts: Ctrl combinations arrive as <Key> with state & 4.
KEY_Q, KEY_S, KEY_O, KEY_N, KEY_F, KEY_H, KEY_G, KEY_F3 = 16, 31, 24, 49, 33, 35, 34, 61
TITLE = 18   # a window's title bar
WRAPS = ('word', 'char', 'none')
WRAP_NAMES = {'word': 'Wrap at words', 'char': 'Wrap anywhere', 'none': 'No wrapping'}
HINT = 'Ctrl+S save  Ctrl+F find  Ctrl+G go to line'
LIMITS = 'the Text limits (262143 characters / 512 KB / 20000 lines)'

try:
    files, resolve, start_dir = sh.fs, sh.resolve, sh.cwd
except NameError:  # not run by the MPYOS shell, e.g. python /bin/tkedit.py file
    import osfs
    files, resolve, start_dir = osfs.shared_vfs(), osfs._abs, osfs.getcwd()
    if files is None:
        raise RuntimeError('tkedit needs MPYOS')
try:
    argv = args
except NameError:
    try:
        argv = list(argv)[1:]   # python /bin/tkedit.py file: argv[0] is the script
    except NameError:
        argv = []
argv = [a for a in argv if a != '-w']
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None                # not under the MPYOS shell: run here
if spawn is not None:           # under the shell's prompt: become a background job
    spawn('tkedit', argv)
    raise SystemExit


def points(text):
    # Code points, as Text indices count them (a str here may hold surrogate pairs).
    count = 0
    for ch in text:
        code = ord(ch)
        if code < 0xDC00 or code > 0xDFFF:
            count += 1
    return count


def basename(path):
    return path[path.rfind('/') + 1:] if path else 'untitled'


def dirname(path):
    cut = path.rfind('/')
    return path[:cut] if cut > 0 else '/'


def short(text, limit):
    # At most limit units, not splitting a surrogate pair.
    if len(text) <= limit:
        return text
    cut = limit - 1
    if 0xD800 <= ord(text[cut - 1]) <= 0xDBFF:
        cut -= 1
    return text[:cut] + '…'


class Editor:
    def __init__(self, root):
        self.root = root
        self.path = None
        self.crlf = False
        self.dialog_win = None
        self.message = ''
        self.message_token = 0
        self.shown = None
        # A window on the desktop, maximized; ⧠ restores it to three quarters of the display.
        root.geometry('%dx%d' % (root.winfo_screenwidth() * 3 // 4, root.winfo_screenheight() * 3 // 4))
        root.state('zoomed')
        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)
        root.protocol('WM_DELETE_WINDOW', self.quit)

        # Buttons and scrollbars take no focus, so the keyboard stays in the text or entry.
        bar = tk.Frame(root)
        bar.grid(row=0, column=0, sticky='ew')
        for label, command in (('New', self.new), ('Open', self.open), ('Save', self.save),
                               ('Save as', self.save_as), ('Find', self.show_find), ('Wrap', self.cycle_wrap)):
            tk.Button(bar, text=label, padx=2, takefocus=0, command=command).pack(side=tk.LEFT)

        body = tk.Frame(root)
        body.grid(row=1, column=0, sticky='nsew')
        body.columnconfigure(0, weight=1)
        body.rowconfigure(0, weight=1)
        self.text = tk.Text(body, width=8, height=2, wrap='word', undo=True)
        self.text.grid(row=0, column=0, sticky='nsew')
        ybar = tk.Scrollbar(body, takefocus=0, command=self.text.yview)
        ybar.grid(row=0, column=1, sticky='ns')
        self.xbar = tk.Scrollbar(body, orient=tk.HORIZONTAL, takefocus=0, command=self.text.xview)
        self.xbar.grid(row=1, column=0, sticky='ew')
        self.xbar.grid_remove()
        self.text.configure(yscrollcommand=ybar.set, xscrollcommand=self.xbar.set)

        self.findbar = tk.Frame(root)
        self.findbar.grid(row=2, column=0, sticky='ew')
        self.findbar.columnconfigure(1, weight=1)
        tk.Label(self.findbar, text='Find').grid(row=0, column=0)
        self.find_entry = tk.Entry(self.findbar, width=6)
        self.find_entry.grid(row=0, column=1, sticky='ew')
        tk.Button(self.findbar, text='Next', padx=2, takefocus=0, command=lambda: self.find(1)).grid(row=0, column=2)
        tk.Button(self.findbar, text='Prev', padx=2, takefocus=0, command=lambda: self.find(-1)).grid(row=0, column=3)
        tk.Button(self.findbar, text='×', padx=2, takefocus=0, command=self.hide_find).grid(row=0, column=4)
        tk.Label(self.findbar, text='Replace').grid(row=1, column=0)
        self.replace_entry = tk.Entry(self.findbar, width=6)
        self.replace_entry.grid(row=1, column=1, sticky='ew')
        tk.Button(self.findbar, text='Replace', padx=2, takefocus=0, command=self.replace_one).grid(row=1, column=2)
        tk.Button(self.findbar, text='All', padx=2, takefocus=0, command=self.replace_all).grid(row=1, column=3)
        self.case = tk.IntVar(value=0)
        tk.Checkbutton(self.findbar, text='Aa', takefocus=0, variable=self.case).grid(row=1, column=4)
        self.findbar.grid_remove()
        self.find_entry.bind('<Return>', lambda event: self.find(1))
        self.replace_entry.bind('<Return>', lambda event: self.replace_one())

        status = tk.Frame(root)
        status.grid(row=3, column=0, sticky='ew')
        status.columnconfigure(0, weight=1)
        self.status = tk.Label(status, anchor='w')
        self.status.grid(row=0, column=0, sticky='ew')
        self.position = tk.Label(status, text='1:1')
        self.position.grid(row=0, column=1)
        self.position.bind('<Button-1>', lambda event: self.goto())

        root.bind('<Key>', self.on_key)
        self.text.focus_set()
        self.flash(HINT)
        self.refresh()

    # ---- status ------------------------------------------------------------------------
    def flash(self, message):
        # A message in the status bar for a few seconds, then the file name again.
        self.message = message
        self.message_token += 1
        token = self.message_token
        self.root.after(4000, lambda: self.unflash(token))
        self.shown = None
        self.refresh()

    def unflash(self, token):
        if token == self.message_token:
            self.message = ''
            self.shown = None

    def refresh(self):
        line, column = self.text.index('insert').split('.')
        modified = self.text.edit_modified()
        state = (line, column, modified, self.message, self.path)
        if state == self.shown:
            return
        self.shown = state
        self.position.configure(text=line + ':' + str(int(column) + 1))
        name = basename(self.path) + (' *' if modified else '')
        self.status.configure(text=self.message or name + (' [CRLF]' if self.crlf else ''))
        self.root.title(name + ' - tkedit')

    def poll(self):
        if self.root._alive:
            self.refresh()
            self.root.after(250, self.poll)

    # ---- keys --------------------------------------------------------------------------
    def on_key(self, event):
        state, code = event.state, event.keycode
        if state & 4 and not state & 8:
            shift = state & 1
            action = {KEY_S: self.save_as if shift else self.save, KEY_O: self.open, KEY_N: self.new,
                      KEY_F: self.show_find, KEY_H: self.show_replace, KEY_G: self.goto, KEY_Q: self.quit}.get(code)
            if action is not None:
                action()
                return 'break'
        elif code == KEY_F3:
            self.find(-1 if state & 1 else 1)
            return 'break'

    # ---- files -------------------------------------------------------------------------
    def load(self, path):
        """Show the file at path (None: an empty new document). False if it cannot be edited."""
        data = None
        text = ''
        if path is not None:
            try:
                if files.exists(path) and files.is_directory(path):
                    self.flash(path + ' is a directory')
                    return False
                data = files.read_bytes(path)
            except Exception as error:
                self.flash('Cannot read: ' + str(error))
                return False
            if data is not None:
                try:
                    text = data.decode('utf-8')
                except Exception:
                    self.flash('Not UTF-8 text: cannot edit it')
                    return False
        if not self.replace_text(text):
            self.flash('Cannot edit: control characters, or over ' + LIMITS)
            return False
        self.path = path
        # Text turns CRLF and lone CR into LF; saving writes back the ending most lines had.
        crlf, lf, cr = text.count('\r\n'), text.count('\n'), text.count('\r')
        self.crlf = crlf * 2 > lf
        mixed = cr != crlf or 0 < crlf < lf
        self.text.mark_set('insert', '1.0')
        self.text.see('1.0')
        self.text.edit_reset()
        self.text.edit_modified(False)
        if path is None:
            self.flash('New document')
        elif data is None:
            self.flash('New file ' + basename(path))
        else:
            lines = lf + cr - crlf + 1
            self.flash('%s: %d lines' % (basename(path), lines) + (
                ' (mixed line endings: saving uses %s)' % ('CRLF' if self.crlf else 'LF') if mixed else ''))
        return True

    def replace_text(self, text):
        """Replace the whole document; if Text refuses it, keep the old one as it was."""
        try:
            self.text.replace('1.0', 'end-1c', text)
            return True
        except Exception:
            return False

    def store(self, path, data):
        # A new file is written directly; an existing one through a temporary file renamed
        # over it, so a failed write (a full disk) does not leave it truncated.
        if not files.exists(path):
            files.write_file(path, data)
            return
        temp = path + '.tkedit-tmp'
        try:
            files.write_file(temp, data)
        except Exception:
            try:
                files.remove(temp)
            except Exception:
                pass
            raise
        if not files.rename(temp, path):
            files.remove(path)
            if not files.rename(temp, path):
                raise OSError('the new text is in ' + temp)

    def write(self, path):
        data = self.text.get('1.0', 'end-1c')
        if self.crlf:
            data = data.replace('\n', '\r\n')
        try:
            self.store(path, data)
        except Exception as error:
            self.flash('Save failed: ' + str(error))
            return False
        self.path = path
        self.text.edit_modified(False)
        self.flash('Saved %s (%d bytes)' % (basename(path), len(data.encode('utf-8'))))
        return True

    def save(self, then=None):
        if self.path is None:
            self.save_as(then)
        elif self.write(self.path) and then is not None:
            then()

    def save_as(self, then=None):
        self.file_dialog('Save as', True, self.write, then)

    def open(self):
        self.confirm(lambda: self.file_dialog('Open', False, self.load))

    def new(self):
        self.confirm(lambda: self.load(None))

    def quit(self):
        self.confirm(self.root.destroy)

    def confirm(self, action):
        # Unsaved changes: save (then action, if the save worked), discard, or cancel.
        if not self.text.edit_modified():
            action()
            return
        win = self.dialog('Unsaved', 250, 90)
        tk.Label(win, text=short(basename(self.path), 16) + ' was changed').pack(pady=4)
        row = tk.Frame(win)
        row.pack()

        def save():
            self.close_dialog()
            self.save(action)

        def discard():
            self.close_dialog()
            action()

        keep = tk.Button(row, text='Save', command=save)
        keep.pack(side=tk.LEFT, padx=2)
        tk.Button(row, text="Don't save", command=discard).pack(side=tk.LEFT, padx=2)
        tk.Button(row, text='Cancel', command=self.close_dialog).pack(side=tk.LEFT, padx=2)
        keep.focus_set()   # keys go to the prompt (Enter saves), not into the document

    # ---- dialogs -----------------------------------------------------------------------
    def dialog(self, title, width, height):
        self.close_dialog()
        # Centred on the editor's window (its client area; geometry +X+Y is a window's title bar).
        x, y = self.root.winfo_rootx(), self.root.winfo_rooty()
        area_w, area_h = self.root.winfo_width(), self.root.winfo_height()
        width = max(60, min(width, area_w - 8))
        height = max(30, min(height, area_h - TITLE - 8))
        win = tk.Toplevel(self.root)
        win.title(title)
        win.geometry('%dx%d+%d+%d' % (width, height, x + (area_w - width) // 2, y + max(0, (area_h - height - TITLE) // 2)))
        win.protocol('WM_DELETE_WINDOW', self.close_dialog)
        self.dialog_win = win
        return win

    def close_dialog(self):
        win, self.dialog_win = self.dialog_win, None
        if win is not None and win._alive:
            win.destroy()
            if self.root._alive:
                self.text.focus_set()

    def file_dialog(self, title, saving, done, then=None):
        """Pick a file: a directory listing (double click a folder to enter it) and a name.
        done(path) does the work and says whether it worked; then runs after that."""
        refresh = getattr(files, 'refresh', None)
        if refresh is not None:
            try:
                refresh()   # pick up disks inserted since the editor started
            except Exception:
                pass
        win = self.dialog(title, 300, 230)
        win.columnconfigure(0, weight=1)
        win.rowconfigure(1, weight=1)
        here = tk.Label(win, anchor='w')
        here.grid(row=0, column=0, columnspan=2, sticky='ew')
        listing = tk.Listbox(win, height=3)
        listing.grid(row=1, column=0, sticky='nsew')
        scroll = tk.Scrollbar(win, takefocus=0, command=listing.yview)
        scroll.grid(row=1, column=1, sticky='ns')
        listing.configure(yscrollcommand=scroll.set)
        name = tk.Entry(win)
        name.grid(row=2, column=0, columnspan=2, sticky='ew')
        note = tk.Label(win, anchor='w', fg='#b00000')
        note.grid(row=3, column=0, columnspan=2, sticky='ew')
        row = tk.Frame(win)
        row.grid(row=4, column=0, columnspan=2)
        state = {'dir': dirname(self.path) if self.path else start_dir, 'overwrite': None}

        def show(path):
            # The folder, else its parent, else the root: the dialog always lists something.
            for place in (path, dirname(path), '/'):
                try:
                    entries = files.list(place)
                    if place == '/' and 'mnt/' not in entries and files.is_directory('/mnt'):
                        entries = entries + ['mnt/']   # the other disks are mounted there
                except Exception as error:
                    note.configure(text=str(error))
                    continue
                state['dir'] = place
                here.configure(text=place)
                folders = sorted([e for e in entries if e.endswith('/')], key=lambda e: e.lower())
                plain = sorted([e for e in entries if not e.endswith('/')], key=lambda e: e.lower())
                rows = (['../'] if place != '/' else []) + folders + plain
                listing.delete(0, tk.END)
                if rows:
                    listing.insert(tk.END, *rows)
                return

        def picked(event=None):
            chosen = listing.curselection()
            item = listing.get(chosen[0]) if chosen else ''
            if item and not item.endswith('/'):
                name.delete(0, tk.END)
                name.insert(0, item)
            return item

        def enter(event=None):
            item = picked()
            if item.endswith('/'):
                show(dirname(state['dir']) if item == '../' else resolve(state['dir'].rstrip('/') + '/' + item[:-1]))
            elif item:
                accept()

        def accept():
            value = name.get().strip()
            if not value:
                note.configure(text='Type a file name')
                return
            path = resolve(value if value.startswith('/') else state['dir'].rstrip('/') + '/' + value)
            try:
                exists = files.exists(path)
                folder = exists and files.is_directory(path)
            except Exception as error:
                note.configure(text=str(error))
                return
            if folder:
                name.delete(0, tk.END)
                show(path)
                return
            if not saving and not exists:
                note.configure(text='No such file')
                return
            if saving and exists and path != self.path and state['overwrite'] != path:
                state['overwrite'] = path
                note.configure(text='It exists: OK again replaces it')
                return
            if not done(path):
                note.configure(text=self.message)   # why it failed; the dialog stays open
                return
            self.close_dialog()
            if then is not None:
                then()

        listing.bind('<<ListboxSelect>>', picked)
        listing.bind('<Double-Button-1>', enter)
        listing.bind('<Return>', enter)
        name.bind('<Return>', lambda event: accept())
        tk.Button(row, text='OK', command=accept).pack(side=tk.LEFT, padx=2)
        tk.Button(row, text='Cancel', command=self.close_dialog).pack(side=tk.LEFT, padx=2)
        if saving and self.path:
            name.insert(0, basename(self.path))
        show(state['dir'])
        name.focus_set()
        return win

    def goto(self):
        win = self.dialog('Go to line', 200, 90)
        last = int(self.text.index('end-1c').split('.')[0])
        tk.Label(win, text='Line (1-%d)' % last).pack()
        entry = tk.Entry(win, width=8)
        entry.pack()
        entry.insert(0, self.text.index('insert').split('.')[0])
        entry.selection_range(0, tk.END)

        def go():
            try:
                line = int(entry.get().strip())
            except ValueError:
                return
            line = max(1, min(line, last))
            self.close_dialog()
            self.text.tag_remove('sel', '1.0', 'end')
            self.text.mark_set('insert', '%d.0' % line)
            self.text.see('insert')

        entry.bind('<Return>', lambda event: go())
        tk.Button(win, text='Go', command=go).pack(pady=2)
        entry.focus_set()
        return win

    # ---- find and replace --------------------------------------------------------------
    def show_find(self, target=None):
        self.findbar.grid()
        selected = self.text.tag_ranges('sel')
        if selected:
            text = self.text.get(selected[0], selected[1])
            if text and len(text) < 64 and min(text) >= ' ':   # an Entry takes no tab or newline
                self.find_entry.delete(0, tk.END)
                self.find_entry.insert(0, text)
        target = target or self.find_entry
        target.focus_set()
        target.selection_range(0, tk.END)

    def show_replace(self):
        self.show_find(self.replace_entry)

    def hide_find(self):
        self.findbar.grid_remove()
        self.text.focus_set()

    def fold(self, text):
        # Case-insensitive search compares lower case; İ is the one letter whose lower case
        # is longer, so it is folded by hand to keep offsets in step with the text.
        return text if self.case.get() else text.replace('İ', 'i').lower()

    def index_at(self, text, at):
        """The Text index of text[at], where text is the whole document."""
        start = text.rfind('\n', 0, at) + 1
        return '%d.%d' % (text.count('\n', 0, at) + 1, points(text[start:at]))

    def select(self, first, last):
        self.text.tag_remove('sel', '1.0', 'end')
        self.text.tag_add('sel', first, last)
        self.text.mark_set('insert', last)
        self.text.see(first)

    def find(self, direction):
        # Forward: the first match from the cursor; backward: the last one starting before the
        # selection (or cursor). Either wraps around the whole text, as Tk's search does.
        needle = self.find_entry.get()
        if not needle:
            self.show_find()
            return False
        selected = self.text.tag_ranges('sel')
        start = self.text.index(selected[0] if direction < 0 and selected else 'insert')
        content = self.text.get('1.0', 'end-1c')
        cut = len(self.text.get('1.0', start))
        hay, key = self.fold(content), self.fold(needle)
        wrapped = False
        if direction > 0:
            at = hay.find(key, cut)
            if at < 0:
                at, wrapped = hay.find(key), True
        else:
            at = hay.rfind(key, 0, cut + len(key) - 1)
            if at < 0:
                at, wrapped = hay.rfind(key), True
        if at < 0:
            self.flash('Not found: ' + needle)
            return False
        first = self.index_at(content, at)
        self.select(first, self.text.index('%s+%dc' % (first, points(content[at:at + len(needle)]))))
        if wrapped:
            self.flash('Wrapped around to the ' + ('start' if direction > 0 else 'end'))
        return True

    def replace_one(self):
        needle, replacement = self.find_entry.get(), self.replace_entry.get()
        if not needle:
            return self.show_find()
        selected = self.text.tag_ranges('sel')
        if selected and self.fold(self.text.get(selected[0], selected[1])) == self.fold(needle):
            try:
                self.text.replace(selected[0], selected[1], replacement)
            except Exception:
                self.flash('That would go over ' + LIMITS)
                return
            self.text.mark_set('insert', '%s+%dc' % (selected[0], points(replacement)))
        self.find(1)

    def replace_all(self):
        needle, replacement = self.find_entry.get(), self.replace_entry.get()
        if not needle:
            return self.show_find()
        content = self.text.get('1.0', 'end-1c')
        hay, key = self.fold(content), self.fold(needle)
        parts, at, count = [], 0, 0
        while True:
            found = hay.find(key, at)
            if found < 0:
                break
            parts.append(content[at:found])
            parts.append(replacement)
            at = found + len(needle)
            count += 1
        if not count:
            self.flash('Not found: ' + needle)
            return
        parts.append(content[at:])
        here = self.text.index('insert')
        if not self.replace_text(''.join(parts)):
            self.flash('That would go over ' + LIMITS)
            return
        self.text.mark_set('insert', here)
        self.text.see('insert')
        self.flash('Replaced %d match%s' % (count, '' if count == 1 else 'es'))

    def cycle_wrap(self):
        wrap = WRAPS[(WRAPS.index(self.text.cget('wrap')) + 1) % len(WRAPS)]
        self.text.configure(wrap=wrap)
        if wrap == 'none':
            self.xbar.grid()
        else:
            self.xbar.grid_remove()
        self.flash(WRAP_NAMES[wrap])


root = tk.Tk()
editor = Editor(root)
if argv:
    editor.load(resolve(argv[0]))
editor.poll()
root.mainloop()
