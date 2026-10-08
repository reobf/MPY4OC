"""Grid form, removable details, and a relative place overlay on one display."""
import tkinter as tk

root = tk.Tk()
root.title('MPY4OC - grid + place')
root.geometry('400x300')
root.configure(bg='#263244')
root.columnconfigure(0, weight=1)
root.columnconfigure(1, weight=2)
root.rowconfigure(1, weight=1)

tk.Label(root, text='共享表单 / grid', bg='#263244', fg='white').grid(row=0, column=0, columnspan=2, sticky='w', padx=10, pady=8)
form = tk.Frame(root, bg='#dde5ee', padx=8, pady=8)
form.grid(row=1, column=0, sticky='nsew', padx=(10, 4))
form.columnconfigure(1, weight=1)
tk.Label(form, text='名称', bg='#dde5ee').grid(row=0, column=0, sticky='w', pady=6)
name = tk.Entry(form, width=8)
name.insert(0, '共享布局')
name.grid(row=0, column=1, sticky='ew', padx=(6, 0))
tk.Message(form, text='列宽按 1:2 分配额外空间。调整窗口时，输入框跟随伸缩。', width=128, bg='#dde5ee').grid(row=1, column=0, columnspan=2, sticky='nw', pady=8)

details = tk.Frame(root, bg='#e8efdc', padx=8, pady=8)
details.grid(row=1, column=1, sticky='nsew', padx=(4, 10))
details.columnconfigure(0, weight=1)
tk.Label(details, text='跨行列与隐藏恢复', bg='#e8efdc').grid(row=0, column=0, sticky='w')
tk.Message(details, text='grid_remove 保留原位置、间距与 sticky。另一个玩家也会看到布局变化。', width=150, bg='#e8efdc').grid(row=1, column=0, sticky='nw', pady=8)
tk.Label(details, text='静态详情', bg='#cedbbc').grid(row=2, column=0, sticky='ew', ipadx=4, ipady=5)

wide = False
shown = True
def refresh():
    status.configure(text='布局 ' + root.geometry() + ' / 详情' + ('显示' if shown else '隐藏'))

def resize():
    global wide
    wide = not wide
    root.geometry('500x340' if wide else '400x300')
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
tk.Button(toolbar, text='切换尺寸', command=resize).pack(side='left', padx=4)
tk.Button(toolbar, text='显示/隐藏详情', command=toggle).pack(side='left', padx=4)
status = tk.Label(root, bg='#263244', fg='white')
status.grid(row=3, column=0, columnspan=2, pady=(0, 12))
badge = tk.Label(root, text='place 标记', bg='#486d91', fg='white')
badge.place(relx=1, x=-8, y=6, anchor='ne')
clipped = tk.Label(root, text='负坐标裁剪', bg='#cf9950')
clipped.place(x=-24, rely=1, y=-2, anchor='sw')
refresh()
root.mainloop()
