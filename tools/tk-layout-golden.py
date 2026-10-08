"""Regenerate geometry fixtures with CPython's real Tcl/Tk (no MPY code).

Run: python tools/tk-layout-golden.py
Uses pixel-sized Frames so system fonts and DPI cannot change the oracle.
The transient oracle window is transparent; all windows are destroyed on exit.
"""
import json
import random
import tkinter as tk
from pathlib import Path


def cases():
    rng = random.Random(7042026)
    anchors = ['nw', 'n', 'ne', 'w', 'center', 'e', 'sw', 's', 'se']
    for number in range(180):
        cols, rows = rng.randint(1, 6), rng.randint(1, 5)
        spec = dict(name='grid-%03d' % number, width=rng.randint(64, 450), height=rng.randint(64, 310),
                    border=rng.randint(0, 5), padx=rng.randint(0, 6), pady=rng.randint(0, 6),
                    manager='grid', anchor=rng.choice(anchors), columns=[], rows=[], widgets=[])
        for axis, count in [('columns', cols), ('rows', rows)]:
            spec[axis] = [dict(index=i, weight=rng.choice([0, 0, 1, 2, 3]), minsize=rng.randint(0, 40), pad=rng.randint(0, 8)) for i in range(count)]
        for i in range(rng.randint(2, 12)):
            c, r = rng.randrange(cols), rng.randrange(rows)
            options = dict(row=r, column=c, rowspan=rng.randint(1, rows-r), columnspan=rng.randint(1, cols-c),
                           sticky=rng.choice(['', 'n', 'e', 's', 'w', 'ne', 'sw', 'ew', 'ns', 'nsew']),
                           padx=[rng.randint(0, 8), rng.randint(0, 8)], pady=[rng.randint(0, 8), rng.randint(0, 8)],
                           ipadx=rng.randint(0, 4), ipady=rng.randint(0, 4))
            spec['widgets'].append(dict(width=rng.randint(1, 120), height=rng.randint(1, 70), options=options))
        yield spec
    for number in range(72):
        spec = dict(name='place-%03d' % number, width=rng.randint(64, 450), height=rng.randint(64, 310),
                    border=number % 5, padx=number % 7, pady=number % 3, manager='place', widgets=[])
        for i in range(8):
            options = dict(x=rng.randint(-30, 30), y=rng.randint(-30, 30), relx=rng.choice([0, .1, .33, .5, 1, -.2]),
                           rely=rng.choice([0, .1, .3, .5, 1, -.2]), anchor=rng.choice(anchors), bordermode=rng.choice(['inside', 'outside', 'ignore']))
            for axis in ['width', 'height']:
                if rng.randrange(2): options[axis] = rng.randint(-10, 100)
                if rng.randrange(2): options['rel' + axis] = rng.choice([0, .1, .33, .5, .75, 1, 1.2])
            spec['widgets'].append(dict(width=rng.randint(1, 70), height=rng.randint(1, 70), options=options))
        yield spec
    yield dict(name='nested-grid-pack-place', width=321, height=241, border=2, padx=3, pady=4,
               manager='grid', anchor='nw', columns=[dict(index=0, weight=1)], rows=[dict(index=0, weight=1)], widgets=[
                   dict(width=20, height=10, options=dict(row=0, column=0, sticky='nsew', padx=[2, 3], pady=[4, 5])),
                   dict(parent=0, width=73, height=21, options=dict(row=0, column=0, padx=7)),
                   dict(parent=0, width=45, height=31, options=dict(row=1, column=0, sticky='e')),
                   dict(parent=0, manager='place', width=17, height=13, options=dict(relx=1, rely=1, anchor='se')),
                   dict(parent=1, manager='pack', width=62, height=17, options=dict(padx=3, pady=2)),
               ])


def generate():
    root = tk.Tk()
    root.withdraw()
    root.attributes('-alpha', 0)
    root.geometry('500x400+0+0')
    root.deiconify()
    root.update()
    output = dict(tk_version=root.tk.call('info', 'patchlevel'), cases=[])
    try:
        for spec in cases():
            container = tk.Frame(root, borderwidth=spec['border'], padx=spec['padx'], pady=spec['pady'], highlightthickness=0)
            container.place(x=0, y=0, width=spec['width'], height=spec['height'])
            container.grid_anchor(spec.get('anchor', 'nw'))
            for axis in ['columns', 'rows']:
                for slot in spec.get(axis, []):
                    opts = dict(slot); index = opts.pop('index')
                    getattr(container, 'grid_columnconfigure' if axis == 'columns' else 'grid_rowconfigure')(index, **opts)
            widgets = []
            for item in spec['widgets']:
                parent = container if 'parent' not in item else widgets[item['parent']]
                w = tk.Frame(parent, width=item['width'], height=item['height'], borderwidth=0, highlightthickness=0)
                getattr(w, item.get('manager', spec['manager']))(**item['options'])
                widgets.append(w)
            root.update_idletasks()
            for item, w in zip(spec['widgets'], widgets):
                item['bounds'] = [w.winfo_rootx() - container.winfo_rootx(), w.winfo_rooty() - container.winfo_rooty(), w.winfo_width(), w.winfo_height()] if w.winfo_ismapped() else [0, 0, 0, 0]
                item['requested'] = [w.winfo_reqwidth(), w.winfo_reqheight()]
            spec['bbox'] = list(container.grid_bbox())
            output['cases'].append(spec)
            container.destroy()
    finally:
        root.destroy()
    target = Path(__file__).resolve().parents[1] / 'src/test/resources/tk-layout-golden.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Tk %s: %s cases, %s rectangles -> %s' % (output['tk_version'], len(output['cases']), sum(len(s['widgets']) for s in output['cases']), target))


if __name__ == '__main__':
    generate()
