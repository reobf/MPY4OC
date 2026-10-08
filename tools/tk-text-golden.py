"""Regenerate bounded Text edit/index cases using the local CPython Tk implementation."""
import json
import pathlib
import random
import tkinter as tk

root = tk.Tk()
root.withdraw()
text = tk.Text(root)
rng = random.Random(70310)
cases = []
alphabet = 'abc 123中文\n\t'
for case in range(100):
    initial = ''.join(rng.choice(alphabet) for _ in range(rng.randrange(1, 120)))
    text.delete('1.0', 'end')
    text.insert('1.0', initial)
    operations = []
    for step in range(12):
        size = len(text.get('1.0', 'end'))
        a = rng.randrange(size + 5)
        index = '1.0+' + str(a) + 'c'
        if rng.randrange(2):
            value = ''.join(rng.choice(alphabet) for _ in range(rng.randrange(1, 9)))
            text.insert(index, value)
            operations.append(['insert', index, value])
        else:
            end = '1.0+' + str(a + rng.randrange(12)) + 'c'
            text.delete(index, end)
            operations.append(['delete', index, end])
    queries = ['1.0', '0.9', '1.9999', '2.end', 'end', 'end-1c', 'end-2 chars', 'insert',
               'end linestart', 'end lineend', '2.0 - 1 c', '1.0+7c', 'end-999999c']
    cases.append(dict(initial=initial, operations=operations, content=text.get('1.0', 'end'),
                      indices={index: text.index(index) for index in queries}))
path = pathlib.Path(__file__).resolve().parents[1] / 'src/test/resources/tk-text-golden.json'
path.write_text(json.dumps(dict(tk=root.tk.call('info', 'patchlevel'), cases=cases), ensure_ascii=False, indent=2), encoding='utf-8')
root.destroy()
print('Wrote', len(cases), 'cases / 1200 edits:', path)
