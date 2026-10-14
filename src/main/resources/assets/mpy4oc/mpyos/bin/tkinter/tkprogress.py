#!/usr/bin/env pythonw
"""Progress bars: a determinate bar shared with a scale, and an indeterminate one.

usage: tkprogress
"""
import tkinter as tk
from tkinter import ttk

root = tk.Tk()
root.title('Progress bars')
value = tk.DoubleVar(value=30)
tk.Label(root, text='Drag the slider, or press +10 (full starts over)').pack(pady=4)
bar = ttk.Progressbar(root, length=280, maximum=100, variable=value)
bar.pack(pady=4)
tk.Scale(root, from_=0, to=100, orient=tk.HORIZONTAL, length=280, variable=value).pack()
spin = ttk.Progressbar(root, length=280, mode='indeterminate')
spin.pack(pady=8)
row = tk.Frame(root)
row.pack(pady=4)
running = [False]


def toggle():
    if running[0]:
        spin.stop()
        button.configure(text='Start')
    else:
        spin.start(50)
        button.configure(text='Stop')
    running[0] = not running[0]


tk.Button(row, text='+10', command=lambda: bar.step(10)).pack(side=tk.LEFT, padx=4)
button = tk.Button(row, text='Start', command=toggle)
button.pack(side=tk.LEFT, padx=4)
root.mainloop()
