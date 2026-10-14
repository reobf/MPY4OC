"""Read-only AE2 CPU monitoring over OpenComputers' NetworkControl API.

CPU values are reused until Rescan/reconnect: repeatedly calling getCpus creates
fresh OC userdata. No crafting, cancellation or inventory mutation is performed.
"""


def rows(value):
    if isinstance(value, tuple) and value and value[0] is None:
        raise OSError(str(value[1]) if len(value) > 1 else 'No data')
    if isinstance(value, dict):
        return [value[k] for k in sorted(k for k in value if isinstance(k, int))]
    if isinstance(value, (list, tuple)):
        return list(value)
    if value is None:
        raise OSError('No data')
    raise OSError('Unexpected AE response')


def quantity(stack):
    try:
        return max(0, int(stack.get('amount', stack.get('size', 0))))
    except (ValueError, TypeError, OverflowError):
        return 0


def is_fluid(stack):
    return 'amount' in stack


def label(stack):
    return str(stack.get('label') or stack.get('name') or 'Unknown')[:256]


def count_text(stack):
    return str(quantity(stack)) + (' mB' if is_fluid(stack) else '')


def _freeze(value):
    if isinstance(value, dict):
        return tuple((str(k), _freeze(value[k])) for k in sorted(value, key=str))
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(v) for v in value)
    return value


def stack_key(stack):
    # Amounts and display labels change during polling; metadata/NBT identify a type.
    hidden_tag = label(stack) if stack.get('hasTag') and not stack.get('tag') and not stack.get('nbt') else None
    return (is_fluid(stack), stack.get('name'), stack.get('damage', 0),
            _freeze(stack.get('nbt')), _freeze(stack.get('tag')), hidden_tag)


def compact_count(value):
    for scale, suffix in ((1000000000, 'G'), (1000000, 'M'), (1000, 'k')):
        if value >= scale:
            return ('%.1f' % (value / scale)).rstrip('0').rstrip('.') + suffix
    return str(value)


class Monitor:
    def __init__(self, components=None):
        self.components = components
        self.address = None
        self.cpus = []
        self.cursor = 0
        self.baseline = None
        self.work = {}
        self.work_index = None
        self.target = None

    def api(self):
        return self.components if self.components is not None else component

    def networks(self):
        found = {}
        for kind in ('me_controller', 'me_interface'):
            for address, actual in self.api().list(kind).items():
                if actual == kind:
                    found[address] = actual
        return [(address, found[address]) for address in sorted(found)]

    def disconnect(self):
        self.address = None
        self.cpus = []
        self.reset_progress()
        self.cursor = 0

    def connect(self, address):
        self.disconnect()
        entries = rows(self.api().proxy(address).getCpus())
        cpus = []
        for index, entry in enumerate(entries):
            if not isinstance(entry, dict) or entry.get('cpu') is None:
                raise OSError('CPU details unavailable; this OC version needs the CPU userdata API')
            cpus.append({'name': str(entry.get('name') or 'CPU ' + str(index + 1))[:256],
                         'storage': int(entry.get('storage', 0)), 'coprocessors': int(entry.get('coprocessors', 0)),
                         'busy': bool(entry.get('busy')), 'handle': entry['cpu'], 'error': ''})
        self.address, self.cpus = address, cpus

    def reset_progress(self):
        self.baseline = None
        self.work = {}
        self.work_index = None
        self.target = None

    def track_work(self, index, active, pending):
        if self.work_index != index:
            self.work = {}
            self.work_index = index
        current = {}
        limited = False
        for state, items in (('active', active), ('pending', pending)):
            for stack in items:
                amount = quantity(stack)
                if not amount:
                    continue
                key = stack_key(stack)
                if key not in self.work:
                    if len(self.work) >= 512:
                        limited = True
                        continue
                    self.work[key] = {'key': key, 'stack': dict(stack), 'active': 0, 'pending': 0, 'done': 0}
                if key not in current:
                    current[key] = {'active': 0, 'pending': 0}
                current[key][state] += amount
                self.work[key]['stack'] = dict(stack)
        for key, record in self.work.items():
            now = current.get(key, {'active': 0, 'pending': 0})
            before = record['active'] + record['pending']
            left = now['active'] + now['pending']
            # This is an observed estimate: STORAGE also includes original ingredients,
            # and OC exposes no completion history or job ID for an arbitrary CPU.
            record['done'] += max(0, before - left)
            record['active'], record['pending'] = now['active'], now['pending']
            record['total'] = record['done'] + left
        return [dict(record) for record in self.work.values()], limited

    def status(self, index):
        row = self.cpus[index]
        try:
            value = row['handle'].isBusy()
            if not isinstance(value, bool):
                raise OSError('CPU state unavailable')
            row['busy'], row['error'] = value, ''
        except Exception as error:
            row['busy'], row['error'] = None, str(error)[:256]
        return row['busy']

    def poll_others(self, selected, limit=4):
        for unused in range(min(limit, len(self.cpus))):
            index = self.cursor % len(self.cpus)
            self.cursor += 1
            if index != selected:
                self.status(index)

    def sample(self, index):
        result = {'busy': self.status(index), 'output': None, 'progress': None, 'remaining': None, 'start': None,
                  'active': [], 'pending': [], 'stored': [], 'tasks': [], 'warning': ''}
        row = self.cpus[index]
        if result['busy'] is not True:
            self.reset_progress()
            result['warning'] = row['error']
            return result
        cpu = row['handle']
        warnings = []
        read = {}
        for key, method in (('active', 'activeItems'), ('pending', 'pendingItems'), ('stored', 'storedItems')):
            try:
                result[key] = [item for item in rows(getattr(cpu, method)()) if isinstance(item, dict)]
                read[key] = True
            except Exception as error:
                warnings.append(key + ': ' + str(error))
        try:
            output = cpu.finalOutput()
            if isinstance(output, (tuple, list)) and output and output[0] is None:
                raise OSError(str(output[1]) if len(output) > 1 else 'No output data')
            if not isinstance(output, dict) or not output.get('name'):
                raise OSError('Final output unavailable')
            result['output'] = output
            target = stack_key(output)
            if self.target is not None and self.target != target:
                self.reset_progress()
            self.target = target
        except Exception as error:
            warnings.append(str(error))
        # A job can finish between OC calls; never infer completion from that empty CPU.
        if self.status(index) is not True:
            self.reset_progress()
            return {'busy': row['busy'], 'output': None, 'progress': None, 'remaining': None, 'start': None,
                    'active': [], 'pending': [], 'stored': [], 'tasks': [], 'warning': row['error']}
        # Progress as AE2's own CPU status counts it (CraftingCPUCluster.prepareStepCount):
        # the work left is everything active plus everything pending; the start is that sum
        # when the job was first seen, and a merged job adds its new work to it. The final
        # output alone does not move until the finished product is delivered.
        if read.get('active') and read.get('pending'):
            remaining = sum(quantity(item) for item in result['active'] + result['pending'])
            previous = self.baseline
            if previous is None or previous[0] != index:
                start = remaining
            else:
                start = previous[1]
                if remaining > previous[2]:
                    start = (previous[1] - previous[2]) + remaining   # done so far + the new total
            self.baseline = (index, start, remaining)
            result['remaining'], result['start'] = remaining, start
            if start > 0:
                result['progress'] = min(100.0, max(0.0, 100.0 * (start - remaining) / start))
            result['tasks'], limited = self.track_work(index, result['active'], result['pending'])
            if limited:
                warnings.append('Task cards limited to 512 types; rescan to reset')
        else:
            self.reset_progress()
        result['warning'] = '; '.join(warnings)[:512]
        return result
