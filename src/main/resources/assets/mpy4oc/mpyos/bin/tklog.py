"""Ten-thousand-row Listbox demo. Controls and rows are shared by all viewers."""
# From the MPYOS prompt, run as a job; an existing job or a direct Python run stays here.
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None
if spawn is not None:
    spawn('tklog', args)
    raise SystemExit

import tkinter as tk

root = tk.Tk()
# The window (420x320 inside) needs its title bar and edges, and the taskbar, on the display too.
need_w, need_h = 422, 358
screen_w, screen_h = root.winfo_screenwidth(), root.winfo_screenheight()
if screen_w < need_w or screen_h < need_h:
    root.destroy()
    raise RuntimeError('needs a %dx%d display (this one is %dx%d): join more displays with a wrench' % (need_w, need_h, screen_w, screen_h))
root.title('MPY4OC Tk / 10,000 rows')
root.geometry('420x320')
tk.Message(root, text='10,000 log rows: use the wheel or the scrollbar. The checkbox, radio buttons and slider live on the server.', width=400).pack(padx=8, pady=4)

follow = tk.BooleanVar(value=True)
level = tk.IntVar(value=0)
batch_size = tk.IntVar(value=100)
status = tk.StringVar(value='Preparing the log')
loaded = 0


def refresh(*args):
    status.set(str(loaded) + ' rows / follow ' + str(int(follow.get()))
               + ' / level ' + str(level.get()) + ' / batch ' + str(batch_size.get()))


toolbar = tk.Frame(root)
toolbar.pack(fill=tk.X, padx=8)
tk.Checkbutton(toolbar, text='Follow', variable=follow, command=refresh).pack(side=tk.LEFT)
tk.Radiobutton(toolbar, text='All', variable=level, value=0, command=refresh).pack(side=tk.LEFT)
tk.Radiobutton(toolbar, text='Warnings', variable=level, value=1, command=refresh).pack(side=tk.LEFT)
tk.Scale(toolbar, from_=20, to=200, resolution=20, variable=batch_size,
         command=refresh, orient=tk.HORIZONTAL, length=140).pack(side=tk.LEFT)
tk.Label(root, textvariable=status).pack(side=tk.BOTTOM, pady=4)

panel = tk.Frame(root)
panel.pack(fill=tk.BOTH, expand=True, padx=8, pady=4)
bar = tk.Scrollbar(panel)
bar.pack(side=tk.RIGHT, fill=tk.Y)
box = tk.Listbox(panel, width=40, height=10, selectmode=tk.EXTENDED, yscrollcommand=bar.set)
box.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
bar.configure(command=box.yview)


def fill():
    global loaded
    stop = min(10000, loaded + batch_size.get())
    box.insert(tk.END, *['row ' + str(i) + ': ' + ('WARN' if i % 10 == 0 else 'INFO') + ' / all is well' for i in range(loaded, stop)])
    loaded = stop
    if follow.get():
        box.see(tk.END)
    refresh()
    if loaded < 10000:
        root.after(10, fill)


root.after(10, fill)
root.mainloop()
