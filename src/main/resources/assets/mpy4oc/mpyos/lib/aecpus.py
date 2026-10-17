"""Live CPU view and guarded cancellation for the whole AE network."""
from aemon import rows, quantity


def _text(value, limit=128):
    text = str(value)[:limit]
    return text[:-1] if text and 0xd800 <= ord(text[-1]) <= 0xdbff else text


def _stack(stack):
    if not isinstance(stack, dict):
        raise OSError('Unexpected CPU item')
    return {'kind': 'fluid' if 'amount' in stack else 'item', 'name': _text(stack.get('name') or ''), 'label': _text(stack.get('label') or stack.get('name') or 'Unknown'), 'damage': _text(stack.get('damage', 0), 32), 'amount': str(quantity(stack)), 'tagged': bool(stack.get('hasTag') or stack.get('tag') or stack.get('nbt'))}


def _bool(value):
    if not isinstance(value, bool):
        raise OSError('CPU state unavailable')
    return value


class CpuTasks:
    PAGE_SIZE = 64
    ITEM_LIMIT = 128

    def __init__(self, inventory):
        self.inventory = inventory
        self.route = None
        self.canceled = {}
        self._clear()

    def _clear(self):
        self.scanned = None
        self.address = None
        self.entries = []
        self.details = {}

    def bind(self, route):
        if route != self.route:
            # OC CPU userdata can recover by enumeration index, which can move.
            # Never reuse those saved handles after restoring the service.
            self._clear()
            self.canceled = {}
        self.route = route

    def _scan(self):
        now = self.inventory.clock()
        if self.scanned is not None and 0 <= now - self.scanned < 2:
            return
        self._clear()
        address, proxy = self.inventory.connection()
        entries = rows(proxy.getCpus())
        for entry in entries:
            if not isinstance(entry, dict):
                raise OSError('Unexpected CPU entry')
        self.address, self.entries, self.scanned = address, entries, self.inventory.clock()

    def _summary(self, index):
        row = self.entries[index]
        busy = row.get('busy')
        return {'index': index, 'name': _text(row.get('name') or 'CPU ' + str(index + 1)), 'storage': str(max(0, int(row.get('storage', 0)))), 'coprocessors': str(max(0, int(row.get('coprocessors', 0)))), 'busy': busy if isinstance(busy, bool) else None}

    def snapshot(self, page=0):
        self._scan()
        pages = max(1, (len(self.entries) + self.PAGE_SIZE - 1) // self.PAGE_SIZE)
        # CPUs may disappear while a client is viewing the last page.
        page = min(max(0, page), pages - 1)
        start = page * self.PAGE_SIZE
        return {'ok': True, 'address': self.address, 'total': len(self.entries), 'busy': sum(1 for row in self.entries if row.get('busy') is True), 'unavailable': sum(1 for row in self.entries if not isinstance(row.get('busy'), bool)), 'page': page, 'pages': pages, 'entries': [self._summary(i) for i in range(start, min(start + self.PAGE_SIZE, len(self.entries)))]}

    def detail(self, index):
        self._scan()
        if not 0 <= index < len(self.entries):
            raise ValueError('CPU is no longer listed. Refresh the CPU list.')
        if index in self.details:
            return self.details[index]
        summary = self._summary(index)
        result = {'ok': True, 'address': self.address, 'token': self.route, 'job': None, 'cancel_error': '', 'cpu': summary, 'output': None, 'active': [], 'pending': [], 'stored': [], 'totals': {}, 'limited': [], 'warnings': []}
        cpu = self.entries[index].get('cpu')
        if cpu is None:
            raise OSError('CPU details unavailable; this OC version needs the CPU userdata API')
        summary['busy'] = _bool(cpu.isBusy())
        if summary['busy']:
            try:
                job = cpu.getJobId()
                if isinstance(job, str) and job:
                    result['job'] = job
                else:
                    result['cancel_error'] = 'Current task ID unavailable. Refresh the CPU details.'
            except Exception:
                result['cancel_error'] = 'Safe cancellation unavailable. Update MPY4OC and refresh the CPU details.'
            try:
                output = cpu.finalOutput()
                if isinstance(output, (tuple, list)) and output and output[0] is None:
                    raise OSError(output[1] if len(output) > 1 else 'Final output unavailable')
                result['output'] = _stack(output)
            except Exception as error:
                result['warnings'].append(_text(error, 256))
            for key, method in (('active', 'activeItems'), ('pending', 'pendingItems'), ('stored', 'storedItems')):
                try:
                    values = rows(getattr(cpu, method)())
                    listed, item_count, fluid_amount, types = [], 0, 0, 0
                    for value in values:
                        entry = _stack(value)
                        amount = int(entry['amount'])
                        if not amount:
                            continue
                        types += 1
                        if entry['kind'] == 'fluid':
                            fluid_amount += amount
                        else:
                            item_count += amount
                        if len(listed) < self.ITEM_LIMIT:
                            listed.append(entry)
                    result[key] = listed
                    result['totals'][key] = {'types': types, 'items': str(item_count), 'fluid': str(fluid_amount)}
                    if types > self.ITEM_LIMIT:
                        result['limited'].append(key)
                except Exception as error:
                    result['warnings'].append(key + ': ' + _text(error, 256))
            # Finishing/canceling between calls must not leave old work on an idle CPU.
            summary['busy'] = _bool(cpu.isBusy())
            if not summary['busy']:
                result.update({'output': None, 'active': [], 'pending': [], 'stored': [], 'totals': {}, 'limited': [], 'warnings': []})
                result['job'], result['cancel_error'] = None, ''
            elif result['job']:
                try:
                    if cpu.getJobId() != result['job']:
                        raise OSError('Task changed while reading')
                except Exception:
                    result.update({'output': None, 'active': [], 'pending': [], 'stored': [], 'totals': {}, 'limited': [], 'warnings': []})
                    result['job'] = None
                    result['cancel_error'] = 'Task changed while reading. Refresh the CPU details.'
        self.details[index] = result
        return result

    def cancel(self, index, request):
        job, address = request.get('job'), request.get('address')
        if not isinstance(job, str) or not job or len(job) > 256 or not isinstance(address, str) or not address or len(address) > 128:
            raise ValueError('Expected the task ID and network address from CPU details')
        key = (address, job)
        if key in self.canceled:
            return {'ok': True, 'canceled': True}
        # Re-enumerate even inside the read cache window. Index/name/output alone
        # cannot identify a task; the Java callback checks the link ID atomically.
        self._clear()
        self._scan()
        if address != self.address or not 0 <= index < len(self.entries):
            return {'ok': False, 'error': 'CPU network changed. Refresh the CPU list.'}
        cpu = self.entries[index].get('cpu')
        if cpu is None:
            raise OSError('CPU unavailable')
        try:
            result = cpu.cancelJob(job)
        finally:
            self._clear()
        if result is not True:
            message = result[1] if isinstance(result, (tuple, list)) and len(result) > 1 else 'Task has finished or changed. Refresh the CPU details.'
            return {'ok': False, 'error': _text(message, 256)}
        self.canceled[key] = True
        if len(self.canceled) > 64:
            del self.canceled[next(iter(self.canceled))]
        return {'ok': True, 'canceled': True}


def path_index(path, prefix):
    value = path[len(prefix):]
    if not value or len(value) > 9 or any(c not in '0123456789' for c in value):
        raise ValueError('Invalid CPU index')
    return int(value)
