"""Run the same text1/2/3 cases as TkPythonTest.textMatchesTk in native CPython/Tk.

Regenerates API expectations only; the original random edit/index fixture is separate.
No external filesystem, display server or WSL is required on Windows.
"""
import json
import pathlib
import runpy
import tkinter

repo = pathlib.Path(__file__).resolve().parents[1]
scope = runpy.run_path(str(repo / 'src/test/resources/tk-text-cases.py'))
data = {'tk': tkinter.Tcl().call('info', 'patchlevel'), 'cases': json.loads(scope['result_json'])}
output = repo / 'src/test/resources/tk-text-api-golden.json'
output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Wrote', len(data['cases']), 'native Tk', data['tk'], 'results to', output)
