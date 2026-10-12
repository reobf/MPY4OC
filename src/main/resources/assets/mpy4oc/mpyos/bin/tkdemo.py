# From the MPYOS prompt, run as a job; an existing job or a direct Python run stays here.
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None
if spawn is not None:
    spawn('tkdemo', args)
    raise SystemExit

import tkinter as tk

root = tk.Tk()
root.title('MPY4OC Tk demo')
root.geometry('280x200')   # the window's inside; with its title bar it fits one display block
counter = tk.StringVar(value='0')
text = tk.StringVar(value='Hello, MPY4OC')

tk.Label(root, text='Python callbacks, one shared screen').pack(pady=8)
tk.Label(root, textvariable=counter).pack()

def increment():
    counter.set(str(int(counter.get()) + 1))

tk.Button(root, text='Count +1', command=increment).pack(pady=8)
entry = tk.Entry(root, textvariable=text, width=26)
entry.pack(padx=12, fill=tk.X)
tk.Label(root, textvariable=text).pack(pady=8)
entry.focus_set()
root.mainloop()
