import tkinter as tk
import json

root = tk.Tk()
root.withdraw()
results = []

def normal(value):
    if isinstance(value, (list, tuple)):
        return [normal(item) for item in value]
    if isinstance(value, dict):
        return dict((key, normal(item)) for key, item in value.items())
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)

def capture(name, call, *args, **kw):
    try:
        value = call(*args, **kw)
        results.append([name, normal(value)])
    except tk.TclError as error:
        results.append([name, {'error': str(error)}])

# text1: indices, marks, tags, inheritance and priority.
t = tk.Text(root)
t.insert('1.0', 'alpha beta\nsecond line\nlast')
for index in ('1.0', 'end', 'end + 2 lines', '2.5 - 1 lines', '1.7 wordstart', '1.7 wordend', '1.0+3indices', '1.-2', 'bogus'):
    capture('text1.index.' + index, t.index, index)
t.mark_set('left', '1.2')
t.mark_gravity('left', 'left')
t.mark_set('right', '1.2')
t.insert('1.2', 'XY')
for mark in ('left', 'right', 'insert', 'current'):
    capture('text1.mark.' + mark, t.index, mark)
    capture('text1.gravity.' + mark, t.mark_gravity, mark)
capture('text1.mark.next', t.mark_next, 'left')
capture('text1.mark.previous', t.mark_previous, 'right')
capture('text1.mark.none', t.mark_next, 'end')
capture('text1.mark.bad', t.mark_gravity, 'missing')
capture('text1.mark.badgravity', t.mark_gravity, 'right', 'up')
t.mark_gravity('insert', 'left')
t.mark_set('insert', '1.0')
t.insert('insert', 'Q')
capture('text1.insert.left', t.index, 'insert')
t.mark_gravity('insert', 'right')
t.delete('1.0', 'end')
t.insert('1.0', 'abcdefgh\nijk')
t.tag_add('red', '1.1', '1.5')
t.tag_add('blue', '1.3', '1.7')
t.tag_configure('red', foreground='#ff0000', underline=True)
t.tag_configure('blue', background='#00ff00', overstrike=True)
capture('text1.tags', t.tag_names)
capture('text1.tags.at', t.tag_names, '1.4')
capture('text1.red.range', t.tag_ranges, 'red')
capture('text1.red.next.inside', t.tag_nextrange, 'red', '1.2')
capture('text1.red.prev.inside', t.tag_prevrange, 'red', '1.2')
capture('text1.red.foreground', t.tag_cget, 'red', 'foreground')
capture('text1.red.underline', t.tag_cget, 'red', 'underline')
capture('text1.red.config', t.tag_configure, 'red', 'foreground')
capture('text1.red.empty', t.tag_cget, 'red', 'font')
t.tag_raise('sel')
t.tag_lower('blue', 'red')
t.tag_add('sel', '1.2', '1.4')
capture('text1.priority', t.tag_names)
capture('text1.selection', t.get, 'sel.first', 'sel.last')
t.tag_raise('red', 'red')
capture('text1.priority.self', t.tag_names)
t.insert('1.3', 'I')
capture('text1.inherit', t.tag_names, '1.3')
t.insert('1.3', 'U', ())
capture('text1.untagged', t.tag_names, '1.3')
t.insert('1.3', 'R', ('red',))
capture('text1.explicit', t.tag_names, '1.3')
t.insert('end', 'X', ('red',), 'Y', (), 'Z')
capture('text1.pieces', t.get, 'end-4c', 'end-1c')
capture('text1.missingrange', t.tag_nextrange, 'missing', '1.0')
capture('text1.badindex', t.index, 'missing.first')
capture('text1.badcolor', t.tag_configure, 'red', foreground='not-a-color')
capture('text1.badoption', t.tag_cget, 'red', 'missing')
t.destroy()

# text2: undo units, clean checkpoints, replace, reset and maxundo.
t = tk.Text(root, undo=True)
capture('text2.clean', t.edit_modified)
t.insert('1.0', 'abc')
t.insert('end', 'def')
capture('text2.dirty', t.edit_modified)
capture('text2.undo', t.edit_undo)
capture('text2.undo.text', t.get, '1.0', 'end')
capture('text2.undo.clean', t.edit_modified)
capture('text2.redo', t.edit_redo)
t.edit_separator()
t.edit_modified(False)
t.replace('1.1', '1.4', 'XYZ')
capture('text2.replace.text', t.get, '1.0', 'end')
t.edit_undo()
capture('text2.replace.undo.text', t.get, '1.0', 'end')
capture('text2.replace.undo.clean', t.edit_modified)
t.edit_redo()
capture('text2.replace.redo.text', t.get, '1.0', 'end')
t.edit_reset()
capture('text2.reset.undo', t.edit_undo)
capture('text2.reset.redo', t.edit_redo)
t.configure(maxundo=2)
for value in ('1', '2', '3'):
    t.insert('end', value)
    t.edit_separator()
for i in range(3):
    capture('text2.limited.' + str(i), t.edit_undo)
capture('text2.limited.text', t.get, '1.0', 'end')
t.edit_modified(True)
t.edit_redo()
t.edit_undo()
capture('text2.explicit.dirty', t.edit_modified)
t.configure(undo=False)
capture('text2.disabled.undo', t.edit_undo)
t.destroy()

# text3: search/count/dump, including no result, explicit None and empty tuples.
t = tk.Text(root)
t.insert('1.0', 'aba ABa\nword 123\naba')
count = tk.IntVar(value=99)
for name, pattern, start, options in (
        ('plain', 'aba', '1.0', {}), ('nocase', 'aba', '1.1', {'nocase': True}),
        ('back', 'aba', 'end', {'backwards': True}), ('wrap', 'aba', 'end', {}),
        ('regexp', '[0-9]+', '1.0', {'regexp': True}), ('multiline', 'ABa\nword', '1.0', {}),
        ('missing', 'never', '1.0', {}), ('stop', 'aba', '1.1', {'stopindex': '2.0'}),
        ('newline', '\n', 'end-1c', {}), ('badregexp', '(', '1.0', {'regexp': True})):
    capture('text3.search.' + name, t.search, pattern, start, count=count, **options)
    capture('text3.countvar.' + name, count.get)
capture('text3.count.default', t.count, '1.0', 'end')
capture('text3.count.zero', t.count, '1.0', '1.0')
capture('text3.count.many', t.count, '1.0', 'end', 'chars', 'indices', 'lines')
capture('text3.count.reverse', t.count, 'end', '1.0', 'chars', 'lines')
capture('text3.count.bad', t.count, '1.0', 'end', 'bad')
t.delete('1.0', 'end')
t.insert('1.0', 'abcd\nef')
t.mark_set('m', '1.2')
t.tag_add('a', '1.0', '1.2')
t.tag_add('b', '1.2', '1.4')
capture('text3.dump.all', t.dump, '1.0', 'end')
capture('text3.dump.partial', t.dump, '1.1', '1.3')
capture('text3.dump.text', t.dump, '1.0', 'end', text=True)
capture('text3.dump.tags', t.dump, '1.0', 'end', tag=True)
capture('text3.dump.marks', t.dump, '1.0', 'end', mark=True)
capture('text3.dump.one', t.dump, '1.2')
dumped = []
def dump_callback(kind, value, index):
    dumped.append((kind, value, index))
capture('text3.dump.callback', t.dump, '1.0', 'end', command=dump_callback)
capture('text3.dump.callback.rows', list, dumped)
# Geometry uses the display's unifont rather than the platform font; covered in Java.
capture('text3.mark.names', sorted, t.mark_names())
t.mark_unset('m')
capture('text3.mark.unset', sorted, t.mark_names())
for name, gravity in (('left1', 'left'), ('left2', 'left'), ('right1', 'right'), ('right2', 'right')):
    t.mark_set(name, '1.2')
    t.mark_gravity(name, gravity)
capture('text3.dump.boundary.order', t.dump, '1.2', '1.3')
capture('text3.mark.next.left', t.mark_next, 'left1')
capture('text3.mark.next.right', t.mark_next, 'right2')
t.mark_set('left1', '1.2')
capture('text3.dump.boundary.moved', t.dump, '1.2', '1.3')
capture('text3.bind.query', t.tag_bind, 'new', None, None)
capture('text3.bind.created.tag', t.tag_names)
t.destroy()
root.destroy()
result_json = json.dumps(results)
passed = True
