#!/usr/bin/env pythonw
"""Show an image file (PNG, JPEG, GIF, BMP) in a maximized window, scaled to fit, or a generated picture.

usage: tkimage [file]
"""
import tkinter as tk

root = tk.Tk()
root.title('Image')
width, height = root.winfo_screenwidth(), root.winfo_screenheight()
import sys
path = sys.argv[1] if len(sys.argv) > 1 else None
if path:
    # The box: the maximized window (the display less the taskbar and the title bar, 36
    # pixels) less the caption, within an image's limits (1024 on a side, 524288 pixels on
    # one display).
    root.state('zoomed')
    fw, fh = min(width - 8, 1024), min(height - 36 - 28, 1024)
    if fw * fh > 524288:
        k = (524288 / (fw * fh)) ** 0.5
        fw, fh = int(fw * k), int(fh * k)
    try:
        img = tk.PhotoImage(file=path, fit=(max(1, fw), max(1, fh)))
    except Exception as error:
        root.destroy()
        raise RuntimeError(str(error))
else:
    img = tk.PhotoImage(width=96, height=64)
    for y in range(64):
        img.put([['#%02x%02x%02x' % (x * 255 // 95, y * 4, 160) for x in range(96)]], to=(0, y))
tk.Label(root, text=(path or 'a generated gradient') + '  ' + str(img.width()) + 'x' + str(img.height())).pack(side=tk.BOTTOM)
tk.Label(root, image=img).pack(expand=True)
root.mainloop()
