#!/usr/bin/env pythonw
"""Canvas dashboard: 10 Hz needle + 80-point history, shared by all viewers."""
import tkinter as tk
import math

root = tk.Tk()
# The window (520x320 inside) needs its title bar and edges, and the taskbar, on the display too.
need_w, need_h = 522, 358
screen_w, screen_h = root.winfo_screenwidth(), root.winfo_screenheight()
if screen_w < need_w or screen_h < need_h:
    root.destroy()
    raise RuntimeError('needs a %dx%d display (this one is %dx%d): join more displays with a wrench' % (need_w, need_h, screen_w, screen_h))
root.title('Canvas dashboard')
root.geometry('520x320')
c = tk.Canvas(root, width=520, height=320, bg='#102030', highlightthickness=0)
c.pack(fill=tk.BOTH, expand=True)
c.create_text(20, 14, text='Live dashboard / 10 Hz', fill='#e0efff', anchor=tk.NW)
c.create_oval(26, 58, 202, 234, fill='#182f44', outline='#58758d', width=2)
for tick in range(11):
    angle = math.pi * (1.25 + 1.5 * tick / 10)
    c.create_line(114 + math.cos(angle) * 70, 146 + math.sin(angle) * 70,
                  114 + math.cos(angle) * 80, 146 + math.sin(angle) * 80,
                  fill='#a0c0d0', width=2)
needle = c.create_line(114, 146, 64, 196, fill='#ffbd59', width=4, tags='needle')
c.create_oval(108, 140, 120, 152, fill='#ffbd59', outline='')
value_text = c.create_text(114, 205, text='0.0', fill='#e0efff')
c.create_text(252, 54, text='Last 80 samples', fill='#a0c0d0', anchor=tk.NW)
c.create_rectangle(244, 84, 500, 230, fill='#0b1928', outline='#58758d')
for y in (120, 156, 192):
    c.create_line(245, y, 499, y, fill='#294054')
history = [0.0] * 80
curve = c.create_line(248, 224, 496, 224, fill='#56d9c3', width=2, tags='curve')
# A concave filled polygon also exercises tessellation, alongside the five item types.
c.create_polygon(30, 265, 48, 265, 48, 257, 63, 274, 48, 291, 48, 282, 30, 282,
                 fill='#56d9c3', tags='pause')
c.create_rectangle(74, 258, 216, 292, fill='#26445b', outline='#58758d', tags='pause')
pause_text = c.create_text(145, 275, text='Click to pause', fill='white', tags='pause')
c.create_text(248, 272, text='Click the curve: new color', fill='#a0c0d0', anchor=tk.W)
status = c.create_text(20, 305, text='Running', fill='#a0c0d0', anchor=tk.W)
frames = 0
paused = False
alternate = False
drags = 0


def pause(event):
    global paused
    paused = not paused
    c.itemconfig(pause_text, text='Click to resume' if paused else 'Click to pause')
    c.itemconfig(status, text='Paused' if paused else 'Running')


def color(event):
    global alternate
    alternate = not alternate
    c.itemconfig(curve, fill='#f07ac0' if alternate else '#56d9c3')
    c.itemconfig(status, text='Curve color changed')


def drag(event):
    global drags
    drags += 1
    c.coords(needle, 114, 146, event.x, event.y)
    c.itemconfig(status, text='Needle drags: ' + str(drags))


c.tag_bind('pause', '<Button-1>', pause)
c.tag_bind('curve', '<Button-1>', color)
c.tag_bind('needle', '<B1-Motion>', drag)


def refresh():
    global frames
    if not paused:
        frames += 1
        value = 50 + 42 * math.sin(frames * 0.10)
        angle = math.pi * (1.25 + 1.5 * value / 100)
        c.coords(needle, 114, 146, 114 + math.cos(angle) * 63, 146 + math.sin(angle) * 63)
        c.itemconfig(value_text, text='%.1f' % value)
        history.pop(0)
        history.append(value)
        points = []
        for i in range(80):
            points.extend((248 + i * 248 / 79, 224 - history[i] * 1.34))
        c.coords(curve, points)
    root.after(100, refresh)


refresh()
root.mainloop()
