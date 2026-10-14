#!/usr/bin/env pythonw
"""Scrolled Canvas: a long line chart on a 1200x600 canvas, with scrollbars and the mouse wheel.

usage: tkscroll
Click the chart to mark a point; the label shows it in canvas coordinates.
"""
import tkinter as tk
import math

root = tk.Tk()
root.title('Canvas scrolling')
frame = tk.Frame(root)
frame.pack(fill=tk.BOTH, expand=True)
c = tk.Canvas(frame, width=280, height=180, bg='#102030', scrollregion=(0, 0, 1200, 600))
ys = tk.Scrollbar(frame, orient=tk.VERTICAL, command=c.yview)
xs = tk.Scrollbar(root, orient=tk.HORIZONTAL, command=c.xview)
c.configure(xscrollcommand=xs.set, yscrollcommand=ys.set)
ys.pack(side=tk.RIGHT, fill=tk.Y)
c.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
xs.pack(fill=tk.X)

for x in range(0, 1201, 100):
    c.create_line(x, 0, x, 600, fill='#294054')
    c.create_text(x + 4, 4, text=str(x), fill='#a0c0d0', anchor=tk.NW)
for y in range(0, 601, 100):
    c.create_line(0, y, 1200, y, fill='#294054')
points = []
for i in range(241):
    points += [i * 5, 300 - 200 * math.sin(i / 12) * math.exp(-i / 300)]
c.create_line(*points, fill='#56d9c3', width=2)
marks = []


def clicked(event):
    x, y = c.canvasx(event.x), c.canvasy(event.y)
    if len(marks) >= 64:
        c.delete(marks.pop(0))
    marks.append(c.create_oval(x - 4, y - 4, x + 4, y + 4, fill='#ffbd59', outline=''))
    status.configure(text='Canvas point (%d, %d)' % (int(x), int(y)))


c.bind('<Button-1>', clicked)
status = tk.Label(root, text='Drag the scrollbars, use the wheel, or click the chart', anchor=tk.W)
status.pack(fill=tk.X)
root.mainloop()
