"""Windows on the shared desktop: drag a title bar to move and an edge or corner to resize.

The title bar buttons minimize, maximize (or double-click the title) and close; the
taskbar at the bottom lists every window on the display, other programs' too, and brings
minimized ones back. This program's own window opens the others.
"""
# From the MPYOS prompt, run as a job; an existing job or a direct Python run stays here.
try:
    spawn = None if sh.background else sh.spawn
except (NameError, AttributeError):
    spawn = None
if spawn is not None:
    spawn('tkdesktop', args)
    raise SystemExit

import tkinter as tk

root = tk.Tk()
root.title('Windows demo')
root.configure(bg='#2f6f73')
root.geometry('+8+8')
screen_w = root.winfo_screenwidth()
count = 0


def note(text):
    status.configure(text=text)


def counter_window():
    global count
    count += 1
    offset = 18 * (count % 6)
    win = tk.Toplevel(root, bg='#f0f0f0')
    win.title('Window ' + str(count))
    win.geometry('150x84+' + str(16 + offset) + '+' + str(96 + offset))
    win.minsize(120, 60)
    clicks = tk.IntVar(value=0)
    tk.Label(win, textvariable=clicks, bg='#f0f0f0').pack(pady=4)
    tk.Button(win, text='+1', command=lambda: clicks.set(clicks.get() + 1)).pack()

    def closing():
        note(win.title() + ' closed')
        win.destroy()

    win.protocol('WM_DELETE_WINDOW', closing)


def chart_window():
    # The Canvas fills the window and redraws for its new size whenever the window is resized.
    win = tk.Toplevel(root)
    win.title('Chart')
    win.geometry('150x100+' + str(max(16, screen_w - 170)) + '+60')
    canvas = tk.Canvas(win, bg='#102030', width=150, height=100)
    canvas.pack(fill=tk.BOTH, expand=True)

    def redraw(event):
        w, h = event.width, event.height
        canvas.delete(tk.ALL)
        canvas.create_rectangle(4, 4, w - 5, h - 5, outline='#56d9c3')
        canvas.create_line(6, h - 8, w // 3, h // 2, 2 * w // 3, h * 2 // 3, w - 8, 10, fill='#ffbd59', width=2)
        canvas.create_text(8, 8, text=str(w) + '×' + str(h), fill='#a0c0d0', anchor=tk.NW)

    canvas.bind('<Configure>', redraw)


def fixed_window():
    win = tk.Toplevel(root, bg='#fff6e0')
    win.title('Fixed size')
    win.geometry('+' + str(max(16, screen_w - 150)) + '+190')
    win.resizable(False, False)
    tk.Label(win, text='Cannot be resized,\nso no maximize', bg='#fff6e0').pack(padx=8, pady=8)


bar = tk.Frame(root, bg='#2f6f73')
bar.pack(fill=tk.X)
tk.Button(bar, text='New window', command=counter_window).pack(side=tk.LEFT, padx=6, pady=6)
tk.Button(bar, text='Chart', command=chart_window).pack(side=tk.LEFT, pady=6)
status = tk.Label(root, text='Drag a title bar to move, an edge to resize', bg='#2f6f73', fg='white')
status.pack(anchor=tk.W, padx=6, pady=(0, 6))
counter_window()
fixed_window()
chart_window()
root.mainloop()
