# Additional operations on the stateful Disk from stdio-fixture.py.
def disk_list(self, path):
    if path not in self.dirs:
        raise OSError('not a directory')
    prefix = path.rstrip('/') + '/'
    result = []
    for name in self.dirs:
        if name != path and name.startswith(prefix) and '/' not in name[len(prefix):]:
            result.append(name[len(prefix):] + '/')
    for name in self.files:
        if name.startswith(prefix) and '/' not in name[len(prefix):]:
            result.append(name[len(prefix):])
    return sorted(result)

def disk_mkdir(self, path):
    if self.readonly:
        raise OSError('read only')
    if self.exists(path) or fs.dirname(path) not in self.dirs:
        return False
    self.dirs.append(path)
    return True

def disk_remove(self, path):
    if self.readonly:
        raise OSError('read only')
    if path in self.files:
        del self.files[path]
        return True
    if path in self.dirs and not disk_list(self, path):
        self.dirs.remove(path)
        return True
    return False

def disk_rename(self, source, target):
    if self.readonly:
        raise OSError('read only')
    if fs.dirname(target) not in self.dirs:
        return False
    if source in self.files and target not in self.dirs:
        self.files[target] = self.files.pop(source)
        return True
    if source in self.dirs and not self.exists(target):
        prefix = source.rstrip('/') + '/'
        self.dirs = [target + name[len(source):] if name == source or name.startswith(prefix) else name for name in self.dirs]
        for name in list(self.files):
            if name.startswith(prefix):
                self.files[target + name[len(source):]] = self.files.pop(name)
        return True
    return False

def disk_size(self, path):
    return len(self.files.get(path, b''))

def disk_modified(self, path):
    return 1234000

Disk.list = disk_list
Disk.make_directory = disk_mkdir
Disk.remove = disk_remove
Disk.rename = disk_rename
Disk.size = disk_size
Disk.last_modified = disk_modified
