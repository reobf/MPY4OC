#!/usr/bin/env pythonw
"""AE2 crafting CPU monitor. Usage: tkaemon [ME component address/prefix]."""
import tkinter as tk
from tkinter import ttk
import aemon

import sys
options = sys.argv[1:]
if len(options) > 1:
    raise ValueError('usage: tkaemon [ME component address/prefix]')

root = tk.Tk()
root.title('AE crafting monitor')
root.state('zoomed')
model = aemon.Monitor()
selected = [None]
networks = []
requested = options[0] if options else None
ticks = [0]
timer = [None]
last_sample = [None]
detail_rows = []
cards = []
page = [0]
chosen_key = [None]
page_size = [1]
progress_running = [False]

toolbar = ttk.Frame(root)
toolbar.pack(fill=tk.X, padx=3, pady=2)
network = ttk.Combobox(toolbar, state='readonly', width=20)
network.pack(side=tk.LEFT, fill=tk.X, expand=True)
rescan = ttk.Button(toolbar, text='Rescan')
rescan.pack(side=tk.RIGHT, padx=(3, 0))

book = ttk.Notebook(root)
book.pack(fill=tk.BOTH, expand=True, padx=3)
overview = ttk.Frame(book)
book.add(overview, text='CPUs')
summary = ttk.Label(overview, text='Select a crafting CPU', anchor=tk.W)
summary.pack(side=tk.BOTTOM, fill=tk.X)
cpu_list = ttk.Treeview(overview, columns=('state', 'storage'), height=3, selectmode='browse')
cpu_list.heading('#0', text='CPU')
cpu_list.heading('state', text='State')
cpu_list.heading('storage', text='Storage')
cpu_list.column('#0', width=120, minwidth=60)
cpu_list.column('state', width=55, minwidth=45, stretch=False)
cpu_list.column('storage', width=68, minwidth=50, stretch=False)
cpu_scroll = ttk.Scrollbar(overview, orient=tk.VERTICAL, command=cpu_list.yview)
cpu_scroll.pack(side=tk.RIGHT, fill=tk.Y)
cpu_list.configure(yscrollcommand=cpu_scroll.set)
cpu_list.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)


details = ttk.Frame(book)
book.add(details, text='Tasks')
book.select(details)
detail_toolbar = ttk.Frame(details)
detail_toolbar.pack(fill=tk.X)
category = ttk.Combobox(detail_toolbar, values=('All', 'Crafting', 'Waiting', 'Done~', 'Stored'), state='readonly', width=9)
category.current(0)
category.pack(side=tk.LEFT)
next_page = ttk.Button(detail_toolbar, text='>', width=2)
next_page.pack(side=tk.RIGHT)
page_label = ttk.Label(detail_toolbar, text='1/1', width=7, anchor=tk.CENTER)
page_label.pack(side=tk.RIGHT)
previous_page = ttk.Button(detail_toolbar, text='<', width=2)
previous_page.pack(side=tk.RIGHT)
card_area = ttk.Frame(details)
card_area.pack(fill=tk.BOTH, expand=True)
empty = ttk.Label(card_area, text='No live tasks', anchor=tk.CENTER)

preview = ttk.Frame(root, height=24)
preview.pack(fill=tk.X, padx=3, pady=(1, 0))
preview.pack_propagate(False)
icon_box = ttk.Frame(preview, width=24, height=24)
icon_box.place(x=0, y=0, width=24, height=24)
item_icon = tk.ItemIcon(icon_box, width=24, height=24)
fluid_icon = tk.FluidIcon(icon_box, width=24, height=24)
caption = ttk.Label(preview, text='Select a CPU', anchor=tk.W)
caption.place(x=28, y=0, relwidth=1, width=-128, height=24)
progress_label = ttk.Label(preview, text='Waiting', anchor=tk.E)
progress_label.place(relx=1, x=-100, y=0, width=100, height=24)
progress = ttk.Progressbar(root, maximum=100, height=6)
progress.pack(fill=tk.X, padx=3, pady=(0, 1))
status = ttk.Label(root, text='W wait | A craft | D~ done (observed)', anchor=tk.W)
status.pack(fill=tk.X, padx=3)


class TaskCard:
    def __init__(self):
        self.record = None
        self.frame = tk.Frame(card_area, height=40, bg='#f5f7fa')
        self.frame.pack_propagate(False)
        self.item = tk.ItemIcon(self.frame, width=24, height=24, bg='#f5f7fa')
        self.fluid = tk.FluidIcon(self.frame, width=24, height=24, bg='#f5f7fa')
        self.name = tk.Label(self.frame, anchor=tk.W, bg='#f5f7fa', fg='#182534')
        self.name.place(x=30, y=0, relwidth=1, width=-32, height=16)
        self.counts = tk.Label(self.frame, anchor=tk.W, bg='#f5f7fa', fg='#36475b')
        self.counts.place(x=30, y=16, relwidth=1, width=-32, height=16)
        self.bar = tk.Canvas(self.frame, height=4, bg='#d4dce5', highlightthickness=0)
        self.bar.place(x=30, y=33, relwidth=1, width=-32, height=4)
        self.segments = [self.bar.create_rectangle(0, 0, 0, 4, fill=color, outline='') for color in ('#2c9c69', '#d78c19', '#8996a5')]
        for widget in (self.frame, self.item, self.fluid, self.name, self.counts, self.bar):
            widget.bind('<Button-1>', self.choose)
            widget.bind('<MouseWheel>', wheel)

    def choose(self, event=None):
        if self.record is not None:
            chosen_key[0] = self.record['key']
            show_task(self.record)

    def draw(self, record):
        self.record = record
        stack = record['stack']
        self.item.place_forget()
        self.fluid.place_forget()
        self.item.configure(item=None)
        self.fluid.configure(fluid=None)
        icon = self.fluid if aemon.is_fluid(stack) else self.item
        try:
            icon.configure(**{'fluid' if aemon.is_fluid(stack) else 'item': stack})
            icon.place(x=2, y=5, width=24, height=24)
        except Exception:
            pass
        self.name.configure(text=short(aemon.label(stack), max(8, (root.winfo_width() - 44) // 8)))
        if 'stored' in record:
            text = aemon.compact_count(record['stored']) + (' mB' if aemon.is_fluid(stack) else '') + ' in CPU'
            values = (0, 0, record['stored'])
        else:
            text = 'W %s  A %s  D~ %s' % tuple(aemon.compact_count(record[k]) for k in ('pending', 'active', 'done'))
            if aemon.is_fluid(stack):
                text += ' mB'
            values = (record['done'], record['active'], record['pending'])
        self.counts.configure(text=text)
        width = max(1, card_area.winfo_width() - 34)
        total = max(1, sum(values))
        left = 0
        for ident, amount in zip(self.segments, values):
            right = left + width * amount / total
            self.bar.coords(ident, left, 0, right, 4)
            left = right
        self.frame.pack(fill=tk.X, pady=(0, 2))


def short(text, limit=None):
    # Long AE labels must not grow the requested window size beyond a single display.
    if limit is None:
        limit = max(16, (root.winfo_width() - 16) // 8)
    text = str(text).replace('\n', ' ').replace('\r', ' ')
    return text if len(text) <= limit else text[:limit - 3] + '...'


def show_icon(stack, suffix=None):
    item_icon.pack_forget()
    fluid_icon.pack_forget()
    item_icon.configure(item=None)
    fluid_icon.configure(fluid=None)
    if stack is None:
        caption.configure(text='No output selected')
        return
    if suffix is None:
        suffix = ' x ' + aemon.count_text(stack)
    limit = max(8, (root.winfo_width() - 134) // 8)
    caption.configure(text=short(aemon.label(stack), max(3, limit - len(suffix))) + suffix)
    icon = fluid_icon if aemon.is_fluid(stack) else item_icon
    try:
        if aemon.is_fluid(stack):
            icon.configure(fluid=stack)
        else:
            icon.configure(item=stack)
        icon.pack(side=tk.LEFT)
    except Exception:
        # Unregistered or unusually large descriptors still have a readable label.
        pass


def show_task(record):
    kind = ('all', 'active', 'pending', 'done', 'stored')[max(0, category.current())]
    if kind == 'all':
        amount, state = record['active'] + record['pending'], 'left'
    else:
        amount = record[kind]
        state = {'active': 'A', 'pending': 'W', 'done': 'D~', 'stored': 'CPU'}[kind]
    suffix = ' ' + state + ' ' + str(amount) + (' mB' if aemon.is_fluid(record['stack']) else '')
    show_icon(record['stack'], suffix)


def set_progress(value, busy):
    if progress_running[0]:
        progress.stop()
        progress_running[0] = False
    progress.configure(mode='determinate', value=0 if value is None else value)
    if busy is True and value is None:
        progress.configure(mode='indeterminate')
        progress.start(100)
        progress_running[0] = True


def draw_details(event=None):
    global detail_rows
    if event is not None:
        page[0] = 0
        chosen_key[0] = None
    sample = last_sample[0]
    kind = ('all', 'active', 'pending', 'done', 'stored')[max(0, category.current())]
    if sample is None:
        detail_rows = []
    elif kind == 'stored':
        detail_rows = [{'key': aemon.stack_key(s), 'stack': s, 'stored': aemon.quantity(s)} for s in sample['stored'] if aemon.quantity(s)]
    else:
        detail_rows = [r for r in sample['tasks'] if kind == 'all' or r[kind] > 0]
    detail_rows = sorted(detail_rows, key=lambda r: (aemon.label(r['stack']).lower(), str(r['key'])))
    page_size[0] = max(1, min(8, card_area.winfo_height() // 42))
    pages = max(1, (len(detail_rows) + page_size[0] - 1) // page_size[0])
    page[0] = max(0, min(page[0], pages - 1))
    visible = detail_rows[page[0] * page_size[0]:(page[0] + 1) * page_size[0]]
    while len(cards) < len(visible):
        cards.append(TaskCard())
    empty.pack_forget()
    for index, card in enumerate(cards):
        card.frame.pack_forget()
        card.record = None
        if index < len(visible):
            card.draw(visible[index])
    if not visible:
        empty.configure(text='No entries' if sample and sample['busy'] else 'No live tasks')
        empty.pack(fill=tk.BOTH, expand=True)
    page_label.configure(text=str(page[0] + 1) + '/' + str(pages))
    previous_page.configure(state=tk.NORMAL if page[0] > 0 else tk.DISABLED)
    next_page.configure(state=tk.NORMAL if page[0] + 1 < pages else tk.DISABLED)
    chosen = None
    for record in detail_rows:
        if record['key'] == chosen_key[0]:
            chosen = record
            break
    if chosen is None:
        chosen_key[0] = None
    if chosen is not None:
        show_task(chosen)
    else:
        show_icon(sample['output'] if sample else None)


def turn_page(delta):
    page[0] += delta
    draw_details()


def wheel(event):
    turn_page(-1 if event.delta > 0 else 1)


def resized(event=None):
    if root._alive:
        draw_details()


def draw_cpus():
    for index, cpu in enumerate(model.cpus):
        state = 'Busy' if cpu['busy'] is True else 'Idle' if cpu['busy'] is False else 'Error'
        values = (state, str(cpu['storage'] // 1024) + ' KiB')
        iid = str(index)
        if cpu_list.exists(iid):
            cpu_list.item(iid, text=cpu['name'], values=values)
        else:
            cpu_list.insert('', 'end', iid=iid, text=cpu['name'], values=values)


def clear_view(message):
    selected[0] = None
    last_sample[0] = None
    page[0] = 0
    chosen_key[0] = None
    for iid in cpu_list.get_children():
        cpu_list.delete(iid)
    summary.configure(text=short(message))
    progress_label.configure(text='No data')
    set_progress(None, False)
    draw_details()


def connect(address):
    clear_view('Connecting...')
    try:
        model.connect(address)
        draw_cpus()
        if model.cpus:
            cpu_list.selection_set('0')
            selected[0] = 0
        else:
            summary.configure(text='No crafting CPUs; build one, then Rescan')
        status.configure(text='W wait | A craft | D~ done (observed)')
    except Exception as error:
        model.disconnect()
        clear_view('AE connection unavailable')
        status.configure(text=short(error))


def discover(force=False):
    global networks
    try:
        found = model.networks()
        if found != networks:
            networks = found
            network.configure(values=tuple(kind + ' ' + address[:8] for address, kind in found))
        available = [address for address, kind in networks]
        target = model.address if model.address in available else None
        if target is None:
            matches = [address for address in available if requested is None or address.startswith(requested)]
            if requested is not None and len(matches) > 1:
                raise OSError('Ambiguous ME address; use a longer prefix')
            target = matches[0] if matches else None
        if target is None:
            model.disconnect()
            network.set('No AE network')
            clear_view('Connect OC Adapter to ME Controller/Interface')
            status.configure(text='Waiting for ' + (requested or 'an AE network'))
        else:
            network.current(available.index(target))
            if force or target != model.address:
                connect(target)
    except Exception as error:
        model.disconnect()
        clear_view('AE discovery unavailable')
        status.configure(text=short(error))


def choose_network(event=None):
    global requested
    index = network.current()
    if 0 <= index < len(networks):
        requested = networks[index][0]
        connect(requested)


def choose_cpu(event=None):
    choices = cpu_list.selection()
    index = int(choices[0]) if choices else None
    if index != selected[0]:
        selected[0] = index
        model.reset_progress()
        last_sample[0] = None
        page[0] = 0
        chosen_key[0] = None
        draw_details()


def refresh():
    timer[0] = None
    try:
        if ticks[0] % 5 == 0:
            discover()
        ticks[0] += 1
        index = selected[0]
        if index is not None and index < len(model.cpus):
            model.poll_others(index)
            sample = model.sample(index)
            last_sample[0] = sample
            cpu = model.cpus[index]
            summary.configure(text=short(cpu['name'] + ' | ' + str(cpu['coprocessors']) + ' co-processors'))
            busy, value = sample['busy'], sample['progress']
            if value is not None:
                text = '~%.1f%% seen' % value
            elif busy is True:
                text = 'Crafting...'
            else:
                text = 'Idle' if busy is False else 'Unavailable'
            progress_label.configure(text=text)
            set_progress(value, busy)
            draw_details()
            draw_cpus()
            status.configure(text=short(sample['warning'] or ('Idle: finished or cancelled' if busy is False else 'W wait | A craft | D~ done (observed)')))
    except Exception as error:
        last_sample[0] = None
        model.reset_progress()
        set_progress(None, False)
        progress_label.configure(text='Read error')
        draw_details()
        status.configure(text=short(error))
    if root._alive:
        timer[0] = root.after(1000, refresh)


def close():
    if timer[0] is not None:
        root.after_cancel(timer[0])
    progress.stop()
    root.destroy()


network.bind('<<ComboboxSelected>>', choose_network)
cpu_list.bind('<<TreeviewSelect>>', choose_cpu)
category.bind('<<ComboboxSelected>>', draw_details)
previous_page.configure(command=lambda: turn_page(-1))
next_page.configure(command=lambda: turn_page(1))
card_area.bind('<Configure>', resized)
card_area.bind('<MouseWheel>', wheel)
rescan.configure(command=lambda: discover(True))
root.protocol('WM_DELETE_WINDOW', close)
refresh()
root.mainloop()
