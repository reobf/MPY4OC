"""Shared plain-text editor and a read-only, scrolling log console."""
# From the MPYOS prompt, run as a job; an existing job or a direct Python run stays here.
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None
if spawn is not None:
    spawn('tktext', args)
    raise SystemExit

import tkinter as tk

root = tk.Tk()
# The window (560x420 inside) needs its title bar and edges, and the taskbar, on the display too.
need_w, need_h = 562, 458
screen_w, screen_h = root.winfo_screenwidth(), root.winfo_screenheight()
if screen_w < need_w or screen_h < need_h:
    root.destroy()
    raise RuntimeError('needs a %dx%d display (this one is %dx%d): join more displays with a wrench' % (need_w, need_h, screen_w, screen_h))
root.title('Text-lite editor / log')
root.geometry('560x420')
root.configure(bg='#263244')
root.columnconfigure(0, weight=1)
root.rowconfigure(1, weight=3)
root.rowconfigure(4, weight=2)

tk.Label(root, text='Shared editor: Enter new line, Tab indent, Ctrl+Tab next widget',
         bg='#263244', fg='white').grid(row=0, column=0, sticky='w', padx=8, pady=6)
editor_panel = tk.Frame(root)
editor_panel.grid(row=1, column=0, sticky='nsew', padx=8)
editor_panel.columnconfigure(0, weight=1)
editor_panel.rowconfigure(0, weight=1)
editor = tk.Text(editor_panel, width=50, height=7, wrap=tk.WORD)
editor.grid(row=0, column=0, sticky='nsew')
editor_bar = tk.Scrollbar(editor_panel, command=editor.yview)
editor_bar.grid(row=0, column=1, sticky='ns')
editor.configure(yscrollcommand=editor_bar.set)
editor.insert('1.0', 'Hello, Text-lite!\nSelect, copy and paste as usual.\n'
              'This is a long line that wraps at word boundaries when the window is narrow.\n'
              '\tA Tab stays in the text and shows as a tab stop.\n')
editor.mark_set(tk.INSERT, '1.0')
editor.edit_modified(False)

toolbar = tk.Frame(root, bg='#263244')
toolbar.grid(row=2, column=0, sticky='w', padx=8, pady=6)
tk.Label(root, text='Read-only log: you can still select, copy and scroll', bg='#263244', fg='white').grid(row=3, column=0, sticky='w', padx=8)
log_panel = tk.Frame(root)
log_panel.grid(row=4, column=0, sticky='nsew', padx=8, pady=4)
log_panel.columnconfigure(0, weight=1)
log_panel.rowconfigure(0, weight=1)
log = tk.Text(log_panel, width=50, height=5, wrap=tk.NONE, bg='#102030', fg='#a4ddbc')
log.grid(row=0, column=0, sticky='nsew')
log_y = tk.Scrollbar(log_panel, command=log.yview)
log_y.grid(row=0, column=1, sticky='ns')
log_x = tk.Scrollbar(log_panel, orient=tk.HORIZONTAL, command=log.xview)
log_x.grid(row=1, column=0, sticky='ew')
log.configure(xscrollcommand=log_x.set, yscrollcommand=log_y.set, state=tk.DISABLED)
status = tk.Label(root, text='Ready', bg='#263244', fg='white')
status.grid(row=5, column=0, sticky='w', padx=8, pady=6)
log_count = 0

def append_log(value):
    log.configure(state=tk.NORMAL)
    log.insert(tk.END, value)
    log.see(tk.END)
    log.configure(state=tk.DISABLED)

def snapshot():
    value = editor.get('1.0', 'end-1c')
    append_log('--- editor snapshot ---\n' + value + '\n')
    status.configure(text='Snapshot taken / cursor ' + editor.index(tk.INSERT))
    editor.edit_modified(False)

def toggle_wrap():
    editor.configure(wrap=tk.CHAR if editor['wrap'] == tk.WORD else tk.WORD)
    status.configure(text='Wrap mode: ' + editor['wrap'])

def clear_log():
    log.configure(state=tk.NORMAL)
    log.delete('1.0', tk.END)
    log.configure(state=tk.DISABLED)
    status.configure(text='Log cleared')

tk.Button(toolbar, text='Snapshot', command=snapshot).pack(side=tk.LEFT, padx=(0, 6))
tk.Button(toolbar, text='Toggle wrap', command=toggle_wrap).pack(side=tk.LEFT, padx=6)
tk.Button(toolbar, text='Clear log', command=clear_log).pack(side=tk.LEFT, padx=6)

def feed():
    global log_count
    batch = ''
    for i in range(25):
        log_count += 1
        batch += 'log ' + str(log_count) + ' / sent as changes, not in full / drag to select\n'
    append_log(batch)
    status.configure(text='Wrote ' + str(log_count) + ' log lines')
    if log_count < 1000:
        root.after(100, feed)

editor.focus_set()
root.after(100, feed)
root.mainloop()
