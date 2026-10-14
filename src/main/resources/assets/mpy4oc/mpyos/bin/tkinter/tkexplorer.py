#!/usr/bin/env pythonw
"""MPYOS file browser. Usage: tkexplorer [directory or file]."""
import os
import sys
import osfs
import subprocess
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog

PAGE_SIZE = 100
files = osfs.shared_vfs()
if files is None:
    raise RuntimeError('tkexplorer needs MPYOS')
if len(sys.argv) > 2:
    raise ValueError('usage: tkexplorer [directory or file]')


def display(text):
    return str(text).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')


def size_text(size):
    if size is None:
        return '?'
    if size < 1024:
        return str(size) + ' B'
    if size < 1048576:
        return '%.1f KiB' % (size / 1024)
    return '%.1f MiB' % (size / 1048576)


def make_icons(root):
    # Four shared 16x16 pixel pictures; transparent pixels keep the row highlight.
    palette = {'.': -1, 'o': '#a56b12', 'y': '#f5c744', 'Y': '#ffe694', 'B': '#28577e', 'b': '#4b9aca', 'w': '#ffffff', 'g': '#8798a6', 'p': '#eef4f8', 'l': '#a8b8c6', 'n': '#182f91', 'N': '#0e1c60'}
    pixels = {}
    pixels['folder'] = ['................', '..ooooo.........', '.oYYYYYo........', '.oYYYYYYooooooo.', '.oyyyyyyyyyyyyo.', '.oyyyyyyyyyyyyo.', '.oooooooooooooo.', '.oYYYYYYYYYYYYo.', '.oyyyyyyyyyyyYo.', '.oyyyyyyyyyyyYo.', '.oyyyyyyyyyyyYo.', '.oyyyyyyyyyyyYo.', '.oyyyyyyyyyyyYo.', '.oooooooooooooo.', '................', '................']
    pixels['python'] = ['................', '.....BBBBBB.....', '....BbbbbbbB....', '....BbwbbbbB....', '....BbbbbbbB....', '....BBBBbbbB....', '..BBBBBBbbbBoo..', '.BbbbbbbbbbByyo.', '.BbbbBooooooyyo.', '.BbbbByyyyyyyyo.', '..BBBByyyooooo..', '....oyyyoooo....', '....oyyyyyyo....', '....oyyyywyo....', '.....oooooo.....', '................']
    pixels['lua'] = ['.............ww.', '.....NNNNN...ww.', '...NNnnnnnNN....', '..NnnnnnnnnnN...', '.NnnnnnnnnnnnN..', '.NnnnnnnnnnnnN..', 'NnwnnnnnnnnnnnN.', 'NnwnnnwnwnnwwnN.', 'NnwnnnwnwnwnwnN.', 'NnwnnnwnwnwnwnN.', 'NnwwwnnwwnnwwnN.', '.NnnnnnnnnnnnN..', '.NnnnnnnnnnnnN..', '..NnnnnnnnnnN...', '...NNnnnnnNN....', '.....NNNNN......']
    pixels['file'] = ['................', '...ggggggg......', '..gwwwwwwwg.....', '..gwppppppwgg...', '..gwppppppwllg..', '..gwppppppgggg..', '..gwpppppppppg..', '..gwpllllllppg..', '..gwpppppppppg..', '..gwpllllllppg..', '..gwpppppppppg..', '..gwpllllppppg..', '..gwpppppppppg..', '..gwwwwwwwwwwg..', '...gggggggggg...', '................']
    icons = {}
    for kind, rows in pixels.items():
        icon = tk.PhotoImage(master=root, width=16, height=16)
        icon.put([[palette[c] for c in row] for row in rows])
        icons[kind] = icon
    return icons


class Explorer:
    def __init__(self, initial):
        self.path = os.getcwd()
        self.history, self.history_at = [], -1
        self.entries, self.rows = [], {}
        self.page, self.reverse = 0, False
        self.ready, self.readonly = False, True
        self.root = tk.Tk()
        self.root.title('File browser')
        self.root.minsize(290, 240)
        self.root.state('zoomed')
        self.icons = make_icons(self.root)
        self.hidden = tk.BooleanVar(value=False)
        nav = ttk.Frame(self.root)
        nav.pack(fill=tk.X, padx=3, pady=2)
        self.back_button = ttk.Button(nav, text='<', width=2, command=lambda: self.history_go(-1))
        self.back_button.pack(side=tk.LEFT)
        self.forward_button = ttk.Button(nav, text='>', width=2, command=lambda: self.history_go(1))
        self.forward_button.pack(side=tk.LEFT)
        self.up_button = ttk.Button(nav, text='Up', command=self.up)
        self.up_button.pack(side=tk.LEFT)
        ttk.Button(nav, text='Refresh', command=self.refresh).pack(side=tk.LEFT)
        ttk.Checkbutton(nav, text='Hidden', variable=self.hidden, command=self.apply_filter).pack(side=tk.RIGHT)
        location = ttk.Frame(self.root)
        location.pack(fill=tk.X, padx=3)
        ttk.Button(location, text='Go', width=3, command=self.go_address).pack(side=tk.RIGHT)
        self.address = ttk.Entry(location)
        self.address.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.address.bind('<Return>', self.go_address)
        actions = ttk.Frame(self.root)
        actions.pack(fill=tk.X, padx=3, pady=2)
        self.open_button = ttk.Button(actions, text='Open', width=4, command=self.open_selected)
        self.file_button = ttk.Button(actions, text='File +', width=6, command=lambda: self.create(False))
        self.folder_button = ttk.Button(actions, text='Folder +', width=8, command=lambda: self.create(True))
        self.rename_button = ttk.Button(actions, text='Rename', width=6, command=self.rename)
        self.delete_button = ttk.Button(actions, text='Delete', width=6, command=self.delete)
        for button in (self.open_button, self.file_button, self.folder_button, self.rename_button, self.delete_button):
            button.pack(side=tk.LEFT)
        search = ttk.Frame(self.root)
        search.pack(fill=tk.X, padx=3)
        ttk.Label(search, text='Filter:').pack(side=tk.LEFT)
        ttk.Button(search, text='Apply', command=self.apply_filter).pack(side=tk.RIGHT)
        self.filter = ttk.Entry(search)
        self.filter.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.filter.bind('<Return>', self.apply_filter)
        self.status = ttk.Label(self.root, text='', anchor=tk.W)
        self.status.pack(side=tk.BOTTOM, fill=tk.X, padx=3)
        pages = ttk.Frame(self.root)
        pages.pack(side=tk.BOTTOM, fill=tk.X, padx=3, pady=2)
        self.previous = ttk.Button(pages, text='Prev', command=lambda: self.turn_page(-1))
        self.previous.pack(side=tk.LEFT)
        self.next = ttk.Button(pages, text='Next', command=lambda: self.turn_page(1))
        self.next.pack(side=tk.RIGHT)
        self.page_label = ttk.Label(pages, text='', anchor=tk.CENTER)
        self.page_label.pack(fill=tk.X, expand=True)
        body = ttk.Frame(self.root)
        body.pack(fill=tk.BOTH, expand=True, padx=3, pady=2)
        self.tree = ttk.Treeview(body, columns=('kind', 'size'), selectmode='browse', showindicator=False, height=5)
        self.tree.heading('#0', text='Name', command=self.sort)
        self.tree.column('#0', width=170, minwidth=100)
        self.tree.heading('kind', text='Type')
        self.tree.column('kind', width=55, minwidth=55, stretch=False)
        self.tree.heading('size', text='Size')
        self.tree.column('size', width=74, minwidth=74, stretch=False, anchor=tk.E)
        horizontal = ttk.Scrollbar(body, orient=tk.HORIZONTAL, command=self.tree.xview)
        horizontal.pack(side=tk.BOTTOM, fill=tk.X)
        vertical = ttk.Scrollbar(body, orient=tk.VERTICAL, command=self.tree.yview)
        vertical.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.configure(xscrollcommand=horizontal.set, yscrollcommand=vertical.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.tree.bind('<<TreeviewSelect>>', self.select)
        self.tree.bind('<Double-Button-1>', self.double_click)
        self.tree.bind('<Key>', self.key)
        self.root.bind('<Key>', self.global_key)
        self.root.protocol('WM_DELETE_WINDOW', self.root.destroy)
        initial = os.path.abspath(initial)
        selected = None
        if os.path.isfile(initial):
            selected, initial = initial, os.path.dirname(initial)
        if not self.navigate(initial, selected=selected):
            self.navigate(os.getcwd())

    def note(self, text):
        limit = max(18, (self.root.winfo_width() - 10) // 7)
        text = display(text)
        self.status.configure(text=text if len(text) <= limit else text[:limit - 3] + '...')

    def error(self, error):
        self.note(error)
        messagebox.showerror('File browser', str(error), parent=self.root)

    def protected(self, path):
        return path in ('/', '/mnt') or path in ['/mnt/' + name for name in files.mounts]

    def writable(self, path):
        if path == '/mnt':
            return False
        disk, sub = files._route(path)
        return not disk.is_readonly()

    def navigate(self, path, remember=True, selected=None):
        target = os.path.normpath(os.path.join(self.path, path))
        try:
            files.refresh()
            if not os.path.isdir(target):
                raise OSError('Not a directory: ' + target)
            names = list(files.list(target))
            if target == '/' and 'mnt/' not in names:
                names.append('mnt/')
            entries = []
            for name in names:
                folder = name.endswith('/')
                name = name.rstrip('/')
                if name and name not in ('.', '..') and '/' not in name and '\\' not in name:
                    entries.append((name, folder))
            readonly = not self.writable(target)
        except Exception as error:
            if target == self.path:
                self.ready = False
                self.select()
            self.error(error)
            return False
        self.path, self.entries, self.readonly, self.ready = target, entries, readonly, True
        if remember and (self.history_at < 0 or self.history[self.history_at] != target):
            self.history = self.history[:self.history_at + 1] + [target]
            self.history_at = len(self.history) - 1
        self.address.delete(0, tk.END)
        self.address.insert(0, target)
        self.root.title('Files - ' + display(target))
        self.draw(selected=selected)
        return True

    def visible(self):
        needle = self.filter.get().lower()
        entries = [e for e in self.entries if (self.hidden.get() or not e[0].startswith('.')) and needle in e[0].lower()]
        folders = sorted([e for e in entries if e[1]], key=lambda e: e[0].lower(), reverse=self.reverse)
        regular = sorted([e for e in entries if not e[1]], key=lambda e: e[0].lower(), reverse=self.reverse)
        return folders + regular

    def draw(self, selected=None):
        entries = self.visible()
        if selected is not None:
            for index, entry in enumerate(entries):
                if os.path.join(self.path, entry[0]) == selected:
                    self.page = index // PAGE_SIZE
                    break
        pages = max(1, (len(entries) + PAGE_SIZE - 1) // PAGE_SIZE)
        self.page = max(0, min(self.page, pages - 1))
        children = self.tree.get_children()
        if children:
            self.tree.delete(*children)
        self.rows = {}
        # Navigation entry, outside the file page and its item count.
        if self.path != '/':
            self.tree.insert('', tk.END, iid='..', text='..', image=self.icons['folder'], values=('Parent', ''))
            if selected == os.path.dirname(self.path):
                self.tree.selection_set('..')
                self.tree.focus('..')
        for index, entry in enumerate(entries[self.page * PAGE_SIZE:(self.page + 1) * PAGE_SIZE]):
            name, folder = entry
            path = os.path.join(self.path, name)
            size = None
            if not folder:
                try:
                    size = files.size(path)
                except OSError:
                    pass
            item = str(index)
            self.rows[item] = (path, folder, size)
            extension = os.path.splitext(name)[1].lower()
            kind = 'folder' if folder else 'python' if extension in ('.py', '.pyw') else 'lua' if extension == '.lua' else 'file'
            self.tree.insert('', tk.END, iid=item, text=display(name), image=self.icons[kind], values=('Folder' if folder else 'File', '' if folder else size_text(size)))
            if path == selected:
                self.tree.selection_set(item)
                self.tree.focus(item)
                self.tree.see(item)
        self.page_label.configure(text='%d/%d | %d items' % (self.page + 1, pages, len(entries)))
        self.previous.configure(state='normal' if self.page > 0 else 'disabled')
        self.next.configure(state='normal' if self.page + 1 < pages else 'disabled')
        self.back_button.configure(state='normal' if self.history_at > 0 else 'disabled')
        self.forward_button.configure(state='normal' if self.history_at + 1 < len(self.history) else 'disabled')
        self.up_button.configure(state='disabled' if self.path == '/' else 'normal')
        self.select()

    def selection(self):
        selected = self.tree.selection()
        if selected and selected[0] == '..' and self.path != '/':
            return (os.path.dirname(self.path), True, None)
        return self.rows.get(selected[0]) if selected else None

    def parent_selected(self):
        return self.tree.selection() == ('..',)

    def select(self, event=None):
        row = self.selection() if self.ready else None
        mutable = row is not None and not self.parent_selected() and not self.readonly and not self.protected(row[0])
        self.open_button.configure(state='normal' if row else 'disabled')
        for button in (self.rename_button, self.delete_button):
            button.configure(state='normal' if mutable else 'disabled')
        for button in (self.file_button, self.folder_button):
            button.configure(state='normal' if self.ready and not self.readonly else 'disabled')
        self.note(('Read-only | ' if self.readonly else '') + (row[0] if row else 'Double-click a folder or file'))

    def refresh(self):
        row = self.selection()
        self.navigate(self.path, remember=False, selected=row[0] if row else None)

    def apply_filter(self, event=None):
        self.page = 0
        self.draw()
        return 'break'

    def sort(self):
        row = self.selection()
        self.reverse = not self.reverse
        self.draw(selected=row[0] if row else None)

    def turn_page(self, direction):
        self.page += direction
        self.draw()

    def history_go(self, direction):
        index = self.history_at + direction
        if 0 <= index < len(self.history):
            previous = self.history_at
            self.history_at = index
            self.page = 0
            if not self.navigate(self.history[index], remember=False):
                self.history_at = previous
                self.select()

    def up(self):
        old = self.path
        self.page = 0
        self.navigate(os.path.dirname(self.path), selected=old)

    def go_address(self, event=None):
        self.page = 0
        self.navigate(self.address.get())
        return 'break'

    def open_selected(self):
        row = self.selection()
        if not row or not self.ready:
            return
        if self.parent_selected():
            self.up()
            return
        path = row[0]
        try:
            if os.path.isdir(path):
                self.page = 0
                self.navigate(path)
            elif os.path.isfile(path):
                subprocess.Popen(['tkedit', path], cwd=self.path)
                self.note('Opened in tkedit: ' + path)
            else:
                raise OSError('File no longer exists: ' + path)
        except Exception as error:
            self.error(error)

    def double_click(self, event):
        item = self.tree.identify_row(event.y)
        if item:
            self.tree.selection_set(item)
            self.open_selected()
        return 'break'

    def destination(self, name):
        if not name or name in ('.', '..') or '/' in name or '\\' in name or any(ord(c) < 32 for c in name):
            raise ValueError('Enter a single file or folder name without / or \\.')
        if self.path == '/mnt' or not self.writable(self.path):
            raise OSError('Read-only directory: ' + self.path)
        path = os.path.join(self.path, name)
        if os.path.exists(path):
            raise OSError('Already exists: ' + path)
        return path

    def create(self, folder):
        if not self.ready or self.readonly:
            return
        name = simpledialog.askstring('New folder' if folder else 'New file', 'Name:', parent=self.root)
        if name is None:
            return
        try:
            path = self.destination(name)
            if folder:
                os.mkdir(path)
            else:
                files.write_file(path, b'')
            self.navigate(self.path, remember=False, selected=path)
        except Exception as error:
            self.error(error)

    def rename(self):
        row = self.selection()
        if not row or not self.ready or self.parent_selected() or self.readonly or self.protected(row[0]):
            return
        name = simpledialog.askstring('Rename', 'New name:', initialvalue=os.path.basename(row[0]), parent=self.root)
        if name is None or name == os.path.basename(row[0]):
            return
        try:
            path = self.destination(name)
            os.rename(row[0], path)
            self.navigate(self.path, remember=False, selected=path)
        except Exception as error:
            self.error(error)

    def delete(self):
        row = self.selection()
        if not row or not self.ready or self.parent_selected() or self.readonly or self.protected(row[0]):
            return
        if not messagebox.askyesno('Delete', 'Permanently delete ' + row[0] + '?\nFolders must be empty. This cannot be undone.', parent=self.root, default='no'):
            return
        try:
            if self.protected(row[0]) or not self.writable(self.path):
                raise OSError('Read-only location: ' + row[0])
            if os.path.isdir(row[0]):
                os.rmdir(row[0])
            else:
                os.remove(row[0])
            self.refresh()
        except Exception as error:
            self.error(error)

    def key(self, event):
        if event.keysym == 'Return':
            self.open_selected()
        elif event.keysym == 'BackSpace':
            self.up()
        elif event.keysym == 'Delete':
            self.delete()
        elif event.keysym == 'F2':
            self.rename()
        else:
            return self.global_key(event)
        return 'break'

    def global_key(self, event):
        if event.keysym == 'F5':
            self.refresh()
        elif event.state & 4 and event.keysym.lower() == 'l':
            self.address.focus_set()
            self.address.selection_range(0, tk.END)
        else:
            return
        return 'break'


explorer = Explorer(sys.argv[1] if sys.argv[1:] else os.getcwd())
explorer.root.mainloop()
