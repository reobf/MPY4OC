"""Ten-thousand-row Listbox demo. Controls and rows are shared by all viewers."""
import tkinter as tk

root = tk.Tk()
root.title('MPY4OC Tk / 10,000 rows')
root.geometry('420x320')
tk.Message(root, text='万行日志：滚轮或拖动右侧滚动条。勾选、单选和滑块均由服务器维护。', width=400).pack(padx=8, pady=4)

follow = tk.BooleanVar(value=True)
level = tk.IntVar(value=0)
batch_size = tk.IntVar(value=100)
status = tk.StringVar(value='准备日志')
loaded = 0


def refresh(*args):
    status.set(str(loaded) + ' 行 / 跟随 ' + str(int(follow.get()))
               + ' / 级别 ' + str(level.get()) + ' / 批量 ' + str(batch_size.get()))


toolbar = tk.Frame(root)
toolbar.pack(fill=tk.X, padx=8)
tk.Checkbutton(toolbar, text='跟随', variable=follow, command=refresh).pack(side=tk.LEFT)
tk.Radiobutton(toolbar, text='全部', variable=level, value=0, command=refresh).pack(side=tk.LEFT)
tk.Radiobutton(toolbar, text='警告', variable=level, value=1, command=refresh).pack(side=tk.LEFT)
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
    box.insert(tk.END, *[str(i) + ' 日志：' + ('WARN' if i % 10 == 0 else 'INFO') + ' / 中文正常' for i in range(loaded, stop)])
    loaded = stop
    if follow.get():
        box.see(tk.END)
    refresh()
    if loaded < 10000:
        root.after(10, fill)


root.after(10, fill)
root.mainloop()
