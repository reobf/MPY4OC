# Stateful OC filesystem stand-in. All paths still use the real fs.Vfs/osfs.
import fs, os, osfs, io


class Disk:
    def __init__(self, address, readonly=False):
        self.address = address
        self.readonly = readonly
        self.files = {}
        self.dirs = ['/', '/home', '/work', '/bin']
        self.handles = {}
        self.next_handle = 0
    def exists(self, path):
        return path in self.files or path in self.dirs
    def is_directory(self, path):
        return path in self.dirs
    def is_readonly(self):
        return self.readonly
    def read_bytes(self, path):
        return self.files.get(path)
    def read_file(self, path):
        data = self.read_bytes(path)
        return None if data is None else data.decode('utf-8')
    def open(self, path, mode):
        if self.readonly and mode != 'rb':
            raise OSError('read only')
        if mode == 'rb' and path not in self.files:
            raise OSError('not found')
        if mode == 'wb':
            self.files[path] = b''
        elif mode == 'ab' and path not in self.files:
            self.files[path] = b''
        self.next_handle += 1
        handle = self.next_handle
        self.handles[handle] = [path, len(self.files[path]) if mode == 'ab' else 0, mode]
        return handle
    def _read_chunk(self, handle, count):
        path, pos, mode = self.handles[handle]
        assert mode == 'rb'
        if pos >= len(self.files[path]):
            return None
        data = self.files[path][pos:pos + min(count, 2)]
        self.handles[handle][1] += len(data)
        return data
    def _write_chunk(self, handle, data):
        path, pos, mode = self.handles[handle]
        assert mode != 'rb'
        old = self.files[path]
        if mode == 'ab':
            pos = len(old)
        if pos > len(old):
            old += b'\x00' * (pos - len(old))
        self.files[path] = old[:pos] + data + old[pos + len(data):]
        self.handles[handle][1] = pos + len(data)
        return True
    def seek(self, handle, whence, offset):
        path, pos, mode = self.handles[handle]
        result = offset if whence == 'set' else pos + offset if whence == 'cur' else len(self.files[path]) + offset
        if result < 0:
            raise OSError('negative position')
        self.handles[handle][1] = result
        return result
    def close(self, handle):
        del self.handles[handle]


boot, other, readonly = Disk('boot'), Disk('other'), Disk('readonly', True)
disks = {'boot': boot, 'other': other, 'readonly': readonly}
fs.Filesystem = lambda address: disks[address]
fs.build_mounts = lambda address: {'oth': 'other', 'ro': 'readonly'}
vfs = fs.Vfs('boot')
osfs.install(os, vfs)


def raises(kind, callback):
    try:
        callback()
    except kind:
        return
    raise AssertionError('expected ' + str(kind))
