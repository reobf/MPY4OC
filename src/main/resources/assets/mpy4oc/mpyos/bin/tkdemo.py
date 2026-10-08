import tkinter as tk

root = tk.Tk()
root.title('MPY4OC Tk demo')
root.geometry('320x240')
counter = tk.StringVar(value='0')
text = tk.StringVar(value='你好，MPY4OC')

tk.Label(root, text='Python 回调与共享画布').pack(pady=8)
tk.Label(root, textvariable=counter).pack()

def increment():
    counter.set(str(int(counter.get()) + 1))

tk.Button(root, text='计数 +1', command=increment).pack(pady=8)
entry = tk.Entry(root, textvariable=text, width=26)
entry.pack(padx=12, fill=tk.X)
tk.Label(root, textvariable=text).pack(pady=8)
entry.focus_set()
root.mainloop()
