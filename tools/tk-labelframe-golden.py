"""Golden labelframe geometry from Tk 8.6 for TkLabelframeTest.

The label is a Frame of a given pixel size (-labelwidget), so fonts play no part. Each case
records the labelframe's requested and actual size, where its 50x20 packed child and the label
widget end up (relative to the labelframe), for the classic tk.LabelFrame and ttk.Labelframe
(default theme), every -labelanchor, with and without a forced size, padding and border width.

Run with a display:  python3 tools/tk-labelframe-golden.py
Writes src/test/resources/tk-labelframe-golden.json.
"""
import json
import os
import tkinter as tk
from tkinter import ttk

ANCHORS = ['nw', 'n', 'ne', 'en', 'e', 'es', 'se', 's', 'sw', 'ws', 'w', 'wn']
LABELS = [(40, 17), (30, 12), (8, 3)]


def geometry(w):
    return [w.winfo_x(), w.winfo_y(), w.winfo_width(), w.winfo_height()]


def measure(root, cls, anchor, label, size=None, padding=None, bd=None):
    options = {'labelanchor': anchor}
    if padding is not None:
        options['padding' if cls == 'ttk' else 'padx'] = padding if cls == 'ttk' else padding[0]
        if cls == 'tk':
            options['pady'] = padding[1]
    if bd is not None:
        options['borderwidth'] = bd
    frame = (ttk.Labelframe if cls == 'ttk' else tk.LabelFrame)(root, **options)
    child = tk.Frame(frame, width=50, height=20)
    child.pack()
    box = tk.Frame(frame, width=label[0], height=label[1])
    frame.configure(labelwidget=box)
    if size:
        frame.place(x=0, y=0, width=size[0], height=size[1])
    else:
        frame.place(x=0, y=0)
    root.update()
    case = {'cls': cls, 'anchor': anchor, 'label': list(label), 'size': size, 'padding': padding, 'bd': bd,
            'req': [frame.winfo_reqwidth(), frame.winfo_reqheight()], 'actual': [frame.winfo_width(), frame.winfo_height()],
            'child': geometry(child), 'box': geometry(box)}
    frame.destroy()
    return case


def main():
    root = tk.Tk()
    root.geometry('400x300+40+40')
    ttk.Style(root).theme_use('default')
    cases = []
    for cls in ('tk', 'ttk'):
        for anchor in ANCHORS:
            for label in LABELS:
                for size in (None, [200, 120]):
                    cases.append(measure(root, cls, anchor, label, size))
        for anchor in ('nw', 'e', 's', 'wn'):
            cases.append(measure(root, cls, anchor, (40, 17), padding=[1, 2, 3, 4] if cls == 'ttk' else [3, 5]))
            for bd in (0, 1, 5):
                cases.append(measure(root, cls, anchor, (40, 17), bd=bd))
    out = {'tk': root.tk.call('info', 'patchlevel'), 'cases': cases}
    path = os.path.join(os.path.dirname(__file__), '..', 'src', 'test', 'resources', 'tk-labelframe-golden.json')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('{"tk": %s, "cases": [\n' % json.dumps(out['tk']))
        f.write(',\n'.join(json.dumps(case, separators=(',', ':')) for case in cases))
        f.write('\n]}\n')
    print(len(cases), 'cases written for Tk', out['tk'])
    root.destroy()


if __name__ == '__main__':
    main()
