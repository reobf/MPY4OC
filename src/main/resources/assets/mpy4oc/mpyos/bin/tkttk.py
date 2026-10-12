"""ttk widgets: a menubar (File, Theme) over a notebook with four pages. Widgets: themed buttons,
check and radio buttons, a 0..1 scale followed by a variable trace, a window with a sizegrip,
double clicks. Borders: the six reliefs, a ttk and a classic labelframe, and a canvas moved with
the arrow keys. Inputs: a readonly combobox, a spinbox, an entry that takes digits only, one that
hides its text, and an option menu for the theme (default, clam, alt, classic) every widget draws
with, as the Theme menu does. Tree: a treeview of folders with a scrollbar; its Size heading sorts.
Panes: two panes with a sash to drag (the right one takes the extra room) and a labeled scale.
F10 opens the menubar from the keyboard.

usage: tkttk
"""
# From the MPYOS prompt, run as a job; an existing job or a direct Python run stays here.
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None
if spawn is not None:
    spawn('tkttk', args)
    raise SystemExit

import tkinter as tk
from tkinter import ttk

root = tk.Tk()
root.title('ttk')
style = ttk.Style()
style.configure('Accent.TButton', foreground='white', background='#4a6984')
style.map('Accent.TButton', background=[('pressed', '#2f4a63'), ('active', '#5a7d9c')])

book = ttk.Notebook(root, padding=2)
book.pack(fill=tk.BOTH, expand=True)
book.bind('<<NotebookTabChanged>>', lambda event: root.title('ttk: ' + book.tab('current', 'text')))

# ---- page 1: controls ----
form = ttk.Frame(book, padding=4)
book.add(form, text='Widgets')
status = ttk.Label(form, text='Double-click the button, or open Borders')
status.pack(anchor=tk.W)
ttk.Separator(form).pack(fill=tk.X, pady=2)

row = ttk.Frame(form)
row.pack(fill=tk.X)
check = ttk.Checkbutton(row, text='Option')
check.pack(side=tk.LEFT)
choice = tk.StringVar(value='a')
for value in ('a', 'b'):
    ttk.Radiobutton(row, text=value.upper(), variable=choice, value=value).pack(side=tk.LEFT)

level = tk.DoubleVar(value=0.25)
shown = ttk.Label(form, text='0.25')
level.trace_add('write', lambda *args: shown.configure(text='%.2f' % level.get()))
ttk.Scale(form, variable=level, length=160).pack(anchor=tk.W)
shown.pack(anchor=tk.W)
bar = ttk.Progressbar(form, length=160, maximum=1.0, variable=level)
bar.pack(anchor=tk.W, pady=2)


def open_window():
    win = tk.Toplevel(root)
    win.title('Drag the corner')
    win.geometry('160x90')
    ttk.Label(win, text='It has a Sizegrip').pack(expand=True)
    ttk.Sizegrip(win).pack(side=tk.BOTTOM, anchor=tk.SE)


# A double click is two clicks: the command runs for each, the <Double-1> binding once.
buttons = ttk.Frame(form)
buttons.pack(anchor=tk.W, pady=2)
button = ttk.Button(buttons, text='Double-click me', style='Accent.TButton')
button.pack(side=tk.LEFT)
button.bind('<Double-1>', lambda event: status.configure(text='Double-clicked ' + event.widget.winfo_class()))
ttk.Button(buttons, text='Open window', command=open_window).pack(side=tk.LEFT, padx=4)
clicked = ttk.Label(buttons)
clicked.pack(side=tk.LEFT)
clicks = [0]


def single():
    clicks[0] += 1
    clicked.configure(text='Clicks: %d' % clicks[0])


button.configure(command=single)

# ---- page 2: borders ----
borders = ttk.Frame(book, padding=4)
book.add(borders, text='Borders')
reliefs = ttk.Labelframe(borders, text='relief', padding=2)
reliefs.pack(fill=tk.X)
for i, relief in enumerate((tk.FLAT, tk.RAISED, tk.SUNKEN, tk.GROOVE, tk.RIDGE, tk.SOLID)):
    tk.Label(reliefs, text=relief, relief=relief, bd=2, width=7).grid(row=i // 3, column=i % 3, padx=2, pady=2)
classic = tk.LabelFrame(borders, text='Classic LabelFrame', labelanchor='n')
classic.pack(fill=tk.X, pady=4)
ttk.Label(classic, text='Title centred on top').pack()
board = tk.Canvas(borders, width=120, height=50, bg='#102030', takefocus=1)
board.pack(anchor=tk.W)
dot = board.create_oval(55, 20, 65, 30, fill='#ffbd59')
moves = {'<Up>': (0, -5), '<Down>': (0, 5), '<Left>': (-5, 0), '<Right>': (5, 0)}
for key, (dx, dy) in moves.items():
    board.bind(key, lambda event, dx=dx, dy=dy: board.move(dot, dx, dy))
board.bind('<Button-1>', lambda event: board.focus_set())

# ---- page 3: inputs ----
inputs = ttk.Frame(book, padding=4)
book.add(inputs, text='Inputs')
said = ttk.Label(inputs, text='Pick, spin or type')
said.grid(row=0, column=0, columnspan=2, sticky=tk.W)
ttk.Label(inputs, text='Colour').grid(row=1, column=0, sticky=tk.W)
colour = ttk.Combobox(inputs, values=('red', 'green', 'blue', 'cyan', 'magenta', 'yellow'), state='readonly', width=12)
colour.grid(row=1, column=1, sticky=tk.W, pady=1)
colour.bind('<<ComboboxSelected>>', lambda event: said.configure(text='Colour: ' + colour.get()))
ttk.Label(inputs, text='Count').grid(row=2, column=0, sticky=tk.W)
count = ttk.Spinbox(inputs, from_=0, to=10, increment=0.5, width=12, wrap=True,
                    command=lambda: said.configure(text='Count: ' + count.get()))
count.set('1.0')
count.grid(row=2, column=1, sticky=tk.W, pady=1)
ttk.Label(inputs, text='Digits').grid(row=3, column=0, sticky=tk.W)
digits = ttk.Entry(inputs, width=12, justify=tk.RIGHT, validate='key',
                   validatecommand=(root.register(lambda text: text.isdigit() or text == ''), '%P'),
                   invalidcommand=lambda: said.configure(text='Digits only'))
digits.grid(row=3, column=1, sticky=tk.W, pady=1)
ttk.Label(inputs, text='Secret').grid(row=4, column=0, sticky=tk.W)
ttk.Entry(inputs, width=12, show='*').grid(row=4, column=1, sticky=tk.W, pady=1)
ttk.Label(inputs, text='Theme').grid(row=5, column=0, sticky=tk.W)
themes = tk.StringVar(value=style.theme_use())
theme = ttk.OptionMenu(inputs, themes, themes.get(), *style.theme_names(), command=style.theme_use)
theme.grid(row=5, column=1, sticky=tk.W, pady=1)

# ---- page 4: tree ----
page = ttk.Frame(book, padding=2)
book.add(page, text='Tree')
picked = ttk.Label(page, text='Click a row; the heading sorts')
picked.pack(anchor=tk.W)
holder = ttk.Frame(page)
holder.pack(fill=tk.BOTH, expand=True)
files = ttk.Treeview(holder, columns=('size',), height=5)
files.heading('#0', text='Name')
files.heading('size', text='Size', anchor=tk.E)
files.column('size', width=60, anchor=tk.E, stretch=False)
files.tag_configure('big', foreground='#c02020')
for folder, entries in (('bin', (('tkedit.py', 31), ('tkttk.py', 6))), ('lib', (('motd.py', 1), ('shell.py', 12))),
                        ('usr', (('greetings.txt', 2),))):
    node = files.insert('', 'end', text=folder, open=folder == 'bin')
    for name, size in entries:
        files.insert(node, 'end', text=name, values=('%d KB' % size,), tags=('big',) if size > 10 else ())
scroll = ttk.Scrollbar(holder, orient=tk.VERTICAL, command=files.yview)
files.configure(yscrollcommand=scroll.set)
scroll.pack(side=tk.RIGHT, fill=tk.Y)
files.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)


def show_selection(event):
    chosen = files.selection()
    picked.configure(text=', '.join(files.item(i, 'text') for i in chosen) or 'Nothing selected')


def sort_by_size():
    # Each folder's files, largest first.
    for node in files.get_children():
        kids = sorted(files.get_children(node), key=lambda i: -int(files.set(i, 'size').split()[0]))
        for index, kid in enumerate(kids):
            files.move(kid, node, index)


files.bind('<<TreeviewSelect>>', show_selection)
files.heading('size', command=sort_by_size)

# ---- page 5: panes ----
panes_page = ttk.Frame(book, padding=2)
book.add(panes_page, text='Panes')
panes = ttk.Panedwindow(panes_page, orient=tk.HORIZONTAL)
panes.pack(fill=tk.BOTH, expand=True)
left = ttk.Labelframe(panes, text='Left', padding=2)
ttk.Label(left, text='Drag the sash').pack(anchor=tk.W)
right = ttk.Labelframe(panes, text='Right', padding=2)
ttk.Label(right, text='weight 1').pack(anchor=tk.W)
panes.add(left)
panes.add(right, weight=1)
amount = ttk.LabeledScale(panes_page, from_=0, to=20)
amount.pack(fill=tk.X, pady=2)

# ---- the menubar ----
menubar = tk.Menu(root)
filemenu = tk.Menu(menubar, tearoff=0)
filemenu.add_command(label='New window', command=open_window, underline=0)
filemenu.add_separator()
filemenu.add_command(label='Quit', command=root.destroy, underline=0)
menubar.add_cascade(label='File', menu=filemenu, underline=0)
# The same variable as the option menu's: either one shows the theme in use.
thememenu = tk.Menu(menubar, tearoff=0)
for name in style.theme_names():
    thememenu.add_radiobutton(label=name, variable=themes, value=name, command=lambda: style.theme_use(themes.get()))
menubar.add_cascade(label='Theme', menu=thememenu, underline=0)
root.config(menu=menubar)

root.mainloop()
