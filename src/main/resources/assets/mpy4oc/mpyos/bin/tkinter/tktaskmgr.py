#!/usr/bin/env pythonw
"""Live MPYOS programs and VM instruction usage. Usage: tktaskmgr."""
import os
import sys
import tkinter as tk
from tkinter import ttk
import _process

if sys.argv[1:]:
    raise ValueError('usage: tktaskmgr')


def compact(value):
    if value is None:
        return '--'
    for limit, suffix in ((1000000000, 'G'), (1000000, 'M'), (1000, 'k')):
        if value >= limit:
            return '%.1f%s' % (value / limit, suffix)
    return '%.0f' % value


class TaskManager:
    def __init__(self):
        self.pid = os.getpid()
        self.model = _process.ProcessMonitor()
        self.rows = {}
        self.timer = None
        self.sort_column, self.descending = 'ops_per_second', True
        self.root = tk.Tk()
        self.root.title('Task manager')
        self.root.state('zoomed')
        self.summary = ttk.Label(self.root, text='Sampling...', anchor=tk.W)
        self.summary.pack(fill=tk.X, padx=3, pady=2)
        toolbar = ttk.Frame(self.root)
        toolbar.pack(side=tk.BOTTOM, fill=tk.X, padx=3, pady=2)
        self.end_button = ttk.Button(toolbar, text='End process', command=self.end_selected, state='disabled')
        self.end_button.pack(side=tk.RIGHT)
        ttk.Button(toolbar, text='Refresh', command=self.refresh).pack(side=tk.LEFT)
        self.status = ttk.Label(self.root, text='Select a process', anchor=tk.W)
        self.status.pack(side=tk.BOTTOM, fill=tk.X, padx=3)
        self.detail = ttk.Label(self.root, text='Share = portion of executed VM ops', anchor=tk.W)
        self.detail.pack(side=tk.BOTTOM, fill=tk.X, padx=3)
        body = ttk.Frame(self.root)
        body.pack(fill=tk.BOTH, expand=True, padx=3)
        columns = ('pid', 'ops_per_second', 'share', 'threads', 'state', 'ops')
        self.tree = ttk.Treeview(body, columns=columns, height=6, selectmode='browse')
        self.tree.column('#0', width=140, minwidth=90)
        self.tree.heading('#0', text='Process', command=lambda: self.sort('command'))
        for key, title, width in (('pid', 'PID', 40), ('ops_per_second', 'ops/s', 68), ('share', 'Share %', 60), ('threads', 'Threads', 60), ('state', 'State', 68), ('ops', 'Total ops', 78)):
            self.tree.column(key, width=width, minwidth=width, stretch=False, anchor=tk.W if key == 'state' else tk.E)
            self.tree.heading(key, text=title, command=lambda column=key: self.sort(column))
        horizontal = ttk.Scrollbar(body, orient=tk.HORIZONTAL, command=self.tree.xview)
        horizontal.pack(side=tk.BOTTOM, fill=tk.X)
        vertical = ttk.Scrollbar(body, orient=tk.VERTICAL, command=self.tree.yview)
        vertical.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.configure(xscrollcommand=horizontal.set, yscrollcommand=vertical.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.tree.bind('<<TreeviewSelect>>', self.select)
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.refresh()

    def short(self, value):
        limit = max(12, (self.root.winfo_width() - 12) // 8)
        value = str(value).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return value if len(value) <= limit else value[:limit - 3] + '...'

    def selection(self):
        selected = self.tree.selection()
        return self.rows.get(int(selected[0])) if selected else None

    def select(self, event=None):
        row = self.selection()
        enabled = row is not None and row['pid'] not in (0, self.pid) and row['state'] == 'Active'
        self.end_button.configure(state='normal' if enabled else 'disabled')
        if row is None:
            self.detail.configure(text='Share = portion of executed VM ops')
        else:
            self.detail.configure(text=self.short('PID %d / PPID %d | %s' % (row['pid'], row['ppid'], 'Background' if row['background'] else 'Foreground')) + '\n%d ops' % row['ops'])

    def sort(self, column):
        self.descending = not self.descending if column == self.sort_column else column not in ('pid', 'command', 'state')
        self.sort_column = column
        self.draw()

    def draw(self):
        def key(row):
            value = row[self.sort_column]
            return (value if value is not None else -1, row['pid'])
        ordered = sorted(self.rows.values(), key=key, reverse=self.descending)
        known = set(self.tree.get_children())
        wanted = set(str(row['pid']) for row in ordered)
        for item in known - wanted:
            self.tree.delete(item)
        for row in ordered:
            item = str(row['pid'])
            label = row['command'].replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            if row['pid'] == self.pid:
                label += ' (this window)'
            values = (row['pid'], compact(row['ops_per_second']), '--' if row['share'] is None else '%.1f' % row['share'], row['threads'], row['state'], compact(row['ops']))
            if item not in known:
                self.tree.insert('', tk.END, iid=item, text=label, values=values)
            elif tuple(self.tree.item(item, 'values')) != tuple(str(v) for v in values) or self.tree.item(item, 'text') != label:
                self.tree.item(item, text=label, values=values)
        order = tuple(str(row['pid']) for row in ordered)
        if self.tree.get_children() != order:
            for index, item in enumerate(order):
                self.tree.move(item, '', index)
        self.select()

    def refresh(self):
        if self.timer is not None:
            self.root.after_cancel(self.timer)
            self.timer = None
        try:
            sample = self.model.sample(computer.uptime())
            self.rows = {row['pid']: row for row in sample['processes']}
            self.summary.configure(text=self.short('VM: %s ops/s | %d processes' % (compact(sample['ops_per_second']), len(self.rows))))
            self.draw()
        except Exception as error:
            self.end_button.configure(state='disabled')
            self.status.configure(text=self.short('Refresh failed: ' + str(error)))
        if self.root._alive:
            self.timer = self.root.after(1000, self.refresh)

    def end_selected(self):
        row = self.selection()
        if row is None or row['pid'] in (0, self.pid) or row['state'] != 'Active':
            return
        try:
            os.kill(row['pid'], 15)
            self.status.configure(text=self.short('Stop requested for PID %d' % row['pid']))
        except ProcessLookupError:
            self.status.configure(text='Process already exited')
        except Exception as error:
            self.status.configure(text=self.short(error))
        self.refresh()

    def close(self):
        if self.timer is not None:
            self.root.after_cancel(self.timer)
            self.timer = None
        self.root.destroy()


manager = TaskManager()
manager.root.mainloop()
