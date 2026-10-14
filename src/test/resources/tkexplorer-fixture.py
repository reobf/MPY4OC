import _process
from tkinter import messagebox, simpledialog


class BrowserFs(FakeVfs):
    def __init__(self):
        FakeVfs.__init__(self)
        self.dirs += ['/bin', '/bin/tkinter', '/home/folder', '/mnt', '/mnt/ro', '/mnt/data']
        self.files['/home/notes.txt'] = 'Hello explorer\n'
        self.files['/home/.hidden'] = 'hidden'
        self.files['/home/folder/keep.txt'] = 'keep'
        self.files['/mnt/ro/readme.txt'] = 'read only'
        self.mounts = {'ro': 'readonly', 'data': 'data'}
        self.readonly = False
        self.full = False
        self.offline = False
        self.renames = []
        self.removes = []

    def _route(self, path):
        return (ReadOnlyDisk(), path) if path == '/mnt/ro' or path.startswith('/mnt/ro/') else (self, path)

    def is_readonly(self):
        return self.readonly

    def list(self, path):
        if self.offline:
            raise OSError('disk disconnected')
        prefix = path.rstrip('/') + '/'
        names = []
        for entry in list(self.files) + self.dirs:
            if entry != path and entry.startswith(prefix) and '/' not in entry[len(prefix):]:
                if path != '/' or entry != '/mnt':
                    names.append(entry[len(prefix):] + ('/' if entry in self.dirs else ''))
        return names

    def read_bytes(self, path):
        value = self.files.get(path)
        return value.encode('utf-8') if isinstance(value, str) else value

    def size(self, path):
        value = self.read_bytes(path)
        return len(value) if value is not None else 0

    def last_modified(self, path):
        return 0

    def write_file(self, path, data):
        if self._route(path)[0].is_readonly() or self.offline:
            raise OSError('read-only or disconnected disk')
        if self.full:
            raise OSError('not enough space')
        self.files[path] = data

    def make_directory(self, path):
        if self._route(path)[0].is_readonly():
            raise OSError('read-only filesystem')
        self.dirs.append(path)
        return True

    def rename(self, source, target):
        self.renames.append((source, target))
        if source in self.files:
            self.files[target] = self.files.pop(source)
        else:
            self.dirs = [target + p[len(source):] if p == source or p.startswith(source + '/') else p for p in self.dirs]
            for p in list(self.files):
                if p.startswith(source + '/'):
                    self.files[target + p[len(source):]] = self.files.pop(p)
        return True

    def remove(self, path):
        # OC remove is recursive. The app must use os.rmdir to guard nonempty folders.
        self.removes.append(path)
        self.dirs = [p for p in self.dirs if p != path and not p.startswith(path + '/')]
        for p in list(self.files):
            if p == path or p.startswith(path + '/'):
                self.files.pop(p)
        return True


class ReadOnlyDisk:
    def is_readonly(self):
        return True


FAKE_VFS = BrowserFs()
osfs.install(os, FAKE_VFS)
sh = shell.Shell(tty, 'boot', globals())
os.chdir('/home')
sh.cwd = '/home'
FAKE_VFS.files['/bin/tkinter/tkexplorer.py'] = EXPLORER
FAKE_VFS.files['/bin/tkinter/tkedit.py'] = EDITOR
answers, confirmations, errors, prompts = [], [], [], []


def ask_name(title, prompt, **kw):
    prompts.append((title, prompt))
    return answers.pop(0)


def ask_delete(title, prompt, **kw):
    assert kw['default'] == 'no'
    prompts.append((title, prompt))
    return confirmations.pop(0)


simpledialog.askstring = ask_name
messagebox.askyesno = ask_delete
messagebox.showerror = lambda title, text, **kw: errors.append(text)


def select_path(app, path):
    app.draw(selected=path)
    assert app.selection() is not None and app.selection()[0] == path, path
    app.select()


def launch(path='/home'):
    sh.run_command('tkexplorer', [path])
    assert len(sh.jobs) == 1 and not sh.jobs[0]['done']
    wait(lambda: 'explorer' in SHARED and SHARED['explorer'].root._token)
    return SHARED['explorer']


def finish(app):
    app.root.after_idle(app.root.destroy)
    wait(lambda: sh.jobs[0]['done'])
    assert sh.jobs[0]['error'] is None, sh.jobs[0]['error']
    assert not app.root._callbacks and not app.root._widgets
    sh._report_jobs()
    assert os.getcwd() == '/home'
