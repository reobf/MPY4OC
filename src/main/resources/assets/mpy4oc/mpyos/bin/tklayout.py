"""Grid form, removable details, and a relative place overlay on one display."""
# From the MPYOS prompt, run as a job; an existing job or a direct Python run stays here.
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None
if spawn is not None:
    spawn('tklayout', args)
    raise SystemExit

import tkinter as tk

root = tk.Tk()
# The window (500x320 inside) needs its title bar and edges, and the taskbar, on the display too.
need_w, need_h = 502, 358
screen_w, screen_h = root.winfo_screenwidth(), root.winfo_screenheight()
if screen_w < need_w or screen_h < need_h:
    root.destroy()
    raise RuntimeError('needs a %dx%d display (this one is %dx%d): join more displays with a wrench' % (need_w, need_h, screen_w, screen_h))
root.title('MPY4OC - grid + place')
root.geometry('400x300')
root.configure(bg='#263244')
root.columnconfigure(0, weight=1)
root.columnconfigure(1, weight=2)
root.rowconfigure(1, weight=1)

tk.Label(root, text='Shared form / grid', bg='#263244', fg='white').grid(row=0, column=0, columnspan=2, sticky='w', padx=10, pady=8)
form = tk.Frame(root, bg='#dde5ee', padx=8, pady=8)
form.grid(row=1, column=0, sticky='nsew', padx=(10, 4))
form.columnconfigure(1, weight=1)
tk.Label(form, text='Name', bg='#dde5ee').grid(row=0, column=0, sticky='w', pady=6)
name = tk.Entry(form, width=8)
name.insert(0, 'shared layout')
name.grid(row=0, column=1, sticky='ew', padx=(6, 0))
tk.Message(form, text='Columns share extra room 1:2: the entry stretches with the window.', width=128, bg='#dde5ee').grid(row=1, column=0, columnspan=2, sticky='nw', pady=8)

details = tk.Frame(root, bg='#e8efdc', padx=8, pady=8)
details.grid(row=1, column=1, sticky='nsew', padx=(4, 10))
details.columnconfigure(0, weight=1)
tk.Label(details, text='Spans, hide and restore', bg='#e8efdc').grid(row=0, column=0, sticky='w')
tk.Message(details, text='grid_remove keeps the cell, padding and sticky. Other players see the change too.', width=150, bg='#e8efdc').grid(row=1, column=0, sticky='nw', pady=8)
tk.Label(details, text='Static details', bg='#cedbbc').grid(row=2, column=0, sticky='ew', ipadx=4, ipady=5)

wide = False
shown = True
def refresh():
    status.configure(text='Layout ' + root.geometry() + ' / details ' + ('shown' if shown else 'hidden'))

def resize():
    global wide
    wide = not wide
    root.geometry('500x320' if wide else '400x300')
    refresh()

def toggle():
    global shown
    shown = not shown
    if shown:
        details.grid()
    else:
        details.grid_remove()
    refresh()

toolbar = tk.Frame(root, bg='#263244')
toolbar.grid(row=2, column=0, columnspan=2, pady=8)
tk.Button(toolbar, text='Resize', command=resize).pack(side='left', padx=4)
tk.Button(toolbar, text='Show/hide details', command=toggle).pack(side='left', padx=4)
status = tk.Label(root, bg='#263244', fg='white')
status.grid(row=3, column=0, columnspan=2, pady=(0, 12))
badge = tk.Label(root, text='place badge', bg='#486d91', fg='white')
badge.place(relx=1, x=-8, y=6, anchor='ne')
clipped = tk.Label(root, text='clipped at -24', bg='#cf9950')
clipped.place(x=-24, rely=1, y=-2, anchor='sw')
refresh()
root.mainloop()
