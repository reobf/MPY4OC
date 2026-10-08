"""Shared plain-text editor and a read-only, scrolling log console."""
import tkinter as tk

root = tk.Tk()
root.title('Text-lite 编辑器 / 日志')
root.geometry('560x420')
root.configure(bg='#263244')
root.columnconfigure(0, weight=1)
root.rowconfigure(1, weight=3)
root.rowconfigure(4, weight=2)

tk.Label(root, text='共享编辑器：Enter 换行，Tab 缩进，Ctrl+Tab 切焦点',
         bg='#263244', fg='white').grid(row=0, column=0, sticky='w', padx=8, pady=6)
editor_panel = tk.Frame(root)
editor_panel.grid(row=1, column=0, sticky='nsew', padx=8)
editor_panel.columnconfigure(0, weight=1)
editor_panel.rowconfigure(0, weight=1)
editor = tk.Text(editor_panel, width=50, height=7, wrap=tk.WORD)
editor.grid(row=0, column=0, sticky='nsew')
editor_bar = tk.Scrollbar(editor_panel, command=editor.yview)
editor_bar.grid(row=0, column=1, sticky='ns')
editor.configure(yscrollcommand=editor_bar.set)
editor.insert('1.0', 'Hello, Text-lite!\n中文编辑：选择、复制、粘贴。\n'
              'This is a long line that wraps at word boundaries when the window is narrow.\n'
              '\tTab 保留在文本里，显示为制表位。\n')
editor.mark_set(tk.INSERT, '1.0')
editor.edit_modified(False)

toolbar = tk.Frame(root, bg='#263244')
toolbar.grid(row=2, column=0, sticky='w', padx=8, pady=6)
tk.Label(root, text='只读日志：仍可选择、复制、滚动', bg='#263244', fg='white').grid(row=3, column=0, sticky='w', padx=8)
log_panel = tk.Frame(root)
log_panel.grid(row=4, column=0, sticky='nsew', padx=8, pady=4)
log_panel.columnconfigure(0, weight=1)
log_panel.rowconfigure(0, weight=1)
log = tk.Text(log_panel, width=50, height=5, wrap=tk.NONE, bg='#102030', fg='#a4ddbc')
log.grid(row=0, column=0, sticky='nsew')
log_y = tk.Scrollbar(log_panel, command=log.yview)
log_y.grid(row=0, column=1, sticky='ns')
log_x = tk.Scrollbar(log_panel, orient=tk.HORIZONTAL, command=log.xview)
log_x.grid(row=1, column=0, sticky='ew')
log.configure(xscrollcommand=log_x.set, yscrollcommand=log_y.set, state=tk.DISABLED)
status = tk.Label(root, text='就绪', bg='#263244', fg='white')
status.grid(row=5, column=0, sticky='w', padx=8, pady=6)
log_count = 0

def append_log(value):
    log.configure(state=tk.NORMAL)
    log.insert(tk.END, value)
    log.see(tk.END)
    log.configure(state=tk.DISABLED)

def snapshot():
    value = editor.get('1.0', 'end-1c')
    append_log('--- 编辑器快照 ---\n' + value + '\n')
    status.configure(text='已记录快照 / 光标 ' + editor.index(tk.INSERT))
    editor.edit_modified(False)

def toggle_wrap():
    editor.configure(wrap=tk.CHAR if editor['wrap'] == tk.WORD else tk.WORD)
    status.configure(text='折行模式：' + editor['wrap'])

def clear_log():
    log.configure(state=tk.NORMAL)
    log.delete('1.0', tk.END)
    log.configure(state=tk.DISABLED)
    status.configure(text='日志已清空')

tk.Button(toolbar, text='记录快照', command=snapshot).pack(side=tk.LEFT, padx=(0, 6))
tk.Button(toolbar, text='切换折行', command=toggle_wrap).pack(side=tk.LEFT, padx=6)
tk.Button(toolbar, text='清空日志', command=clear_log).pack(side=tk.LEFT, padx=6)

def feed():
    global log_count
    batch = ''
    for i in range(25):
        log_count += 1
        batch += '日志 ' + str(log_count) + ' / 多行文本增量同步 / 可拖选复制\n'
    append_log(batch)
    status.configure(text='已写入 ' + str(log_count) + ' 行日志')
    if log_count < 1000:
        root.after(100, feed)

editor.focus_set()
root.after(100, feed)
root.mainloop()
