"""Bounded AE crafting orders for the authenticated storage dashboard."""
import json
from aemon import rows


def _text(value, limit=256):
    text = str(value)[:limit]
    return text[:-1] if text and 0xd800 <= ord(text[-1]) <= 0xdbff else text


def _result(value):
    if isinstance(value, tuple):
        if not value or value[0] is None:
            raise OSError(value[1] if len(value) > 1 else 'No AE response')
        return value[0]
    if value is None:
        raise OSError('No AE response')
    return value


class Crafting:
    MAX_AMOUNT = 1000000
    ACTIVE = ('submitting', 'computing', 'running', 'unavailable', 'canceling')

    def __init__(self, inventory):
        self.inventory = inventory
        self.route = None
        self.recipes, self.orders = {}, {}
        self.serial = 0
        self.catalog_time, self.polled = None, None
        self.catalog_address = None
        self.limited = False

    def bind(self, route):
        if self.route is not None and route != self.route:
            # OC CraftingStatus does not persist the live crafting link. Never
            # infer completion or re-submit an order after route/VM restoration.
            for order in self.orders.values():
                if order['state'] in self.ACTIVE:
                    order['state'] = 'unknown'
                    order['hidden'] = True
                    order['message'] = 'Monitoring interrupted. Check the AE CPU before placing another order.'
                elif order['state'] == 'draft':
                    order['state'] = 'expired'
                order.pop('handle', None)
                order.pop('recipe', None)
                order.pop('cancel_requested', None)
            self.recipes = {}
            self.catalog_time = None
        self.route = route

    def _id(self):
        self.serial += 1
        return str(self.serial)

    def catalog(self):
        now = self.inventory.clock()
        if self.catalog_time is None or not 0 <= now - self.catalog_time < 15:
            # Publish atomically; failed reads invalidate old selections.
            self.recipes, self.catalog_time = {}, None
            address, proxy = self.inventory.connection()
            handles = rows(proxy.getCraftables())
            recipes = {}
            for handle in handles[:512]:
                stack = _result(handle.getStack())
                if not isinstance(stack, dict):
                    raise OSError('Unexpected AE recipe output')
                identity = self._id()
                entry = {'id': identity, 'kind': 'fluid' if 'amount' in stack else 'item', 'name': _text(stack.get('name') or '', 128), 'label': _text(stack.get('label') or stack.get('name') or 'Unknown', 128), 'damage': _text(stack.get('damage', 0), 32), 'tagged': bool(stack.get('hasTag') or stack.get('tag') or stack.get('nbt'))}
                # Keep the exact userdata, including hidden NBT. Never look up a
                # recipe again by its truncated display name or inventory key.
                recipes[identity] = {'entry': entry, 'handle': handle}
            self.recipes, self.catalog_address = recipes, address
            self.limited = len(handles) > 512
            self.catalog_time = self.inventory.clock()
        return {'ok': True, 'token': self.route, 'address': self.catalog_address, 'entries': [r['entry'] for r in self.recipes.values()], 'limited': self.limited, 'max_amount': self.MAX_AMOUNT}

    def prepare(self, data):
        amount = data.get('amount')
        if not isinstance(amount, str) or not amount or len(amount) > 7 or any(c not in '0123456789' for c in amount):
            raise ValueError('Quantity must be a whole number from 1 to 1000000')
        count = int(amount)
        if not 1 <= count <= self.MAX_AMOUNT:
            raise ValueError('Quantity must be a whole number from 1 to 1000000')
        identity = data.get('recipe')
        if not isinstance(identity, str):
            raise ValueError('Select a recipe')
        recipe = self.recipes.get(identity)
        now = self.inventory.clock()
        if recipe is None or self.catalog_time is None or not 0 <= now - self.catalog_time <= 60:
            raise ValueError('Recipe list expired. Reload recipes and select again.')
        self.poll()
        if len(self.orders) >= 64:
            for old, order in self.orders.items():
                if order['state'] not in self.ACTIVE:
                    del self.orders[old]
                    break
            if len(self.orders) >= 64:
                raise ValueError('Order history is full')
        order = {'id': self._id(), 'entry': recipe['entry'], 'amount': str(count), 'address': self.catalog_address, 'state': 'draft', 'message': '', 'created': now, 'recipe': recipe['handle']}
        self.orders[order['id']] = order
        return {'ok': True, 'order': self.public(order)}

    def submit(self, identity):
        order = self.orders.get(identity)
        if order is None:
            raise ValueError('Order expired. It cannot be submitted again.')
        # A consumed ID can only report status, never invoke request a second time.
        if order['state'] != 'draft':
            return {'ok': True, 'order': self.public(order)}
        now = self.inventory.clock()
        if not 0 <= now - order['created'] <= 60:
            order['state'], order['message'] = 'expired', 'Confirmation expired. Select a recipe again.'
        elif sum(1 for o in self.orders.values() if o['state'] in self.ACTIVE) >= 8:
            raise ValueError('Eight orders are already active. Wait for them to finish.')
        else:
            address, unused = self.inventory.connection()
            if address != order['address']:
                raise ValueError('AE network changed. Select a recipe again.')
            # Mark consumed BEFORE the potentially yielding component call.
            order['state'] = 'submitting'
            try:
                handle = order['recipe'].request(int(order['amount']))
                if isinstance(handle, tuple) and handle and handle[0] is None:
                    order['state'] = 'failed'
                    order['message'] = _text(handle[1] if len(handle) > 1 else 'AE rejected the request')
                else:
                    order['handle'] = _result(handle)
                    order['state'] = 'computing'
            except Exception as error:
                order['state'] = 'unknown'
                order['message'] = _text('Submission outcome unknown: ' + str(error) + '. Check AE before ordering again.')
        if order['state'] not in self.ACTIVE:
            order.pop('recipe', None)
        return {'ok': True, 'order': self.public(order)}

    def poll(self, force=False):
        now = self.inventory.clock()
        if not force and self.polled is not None and 0 <= now - self.polled < 2:
            return
        self.polled = now
        for order in self.orders.values():
            if order['state'] == 'draft' and not 0 <= now - order['created'] <= 60:
                order['state'], order['message'] = 'expired', 'Confirmation expired.'
                order.pop('recipe', None)
            handle = order.get('handle')
            if handle is None or order['state'] not in self.ACTIVE:
                continue
            try:
                if _result(handle.isComputing()):
                    state, message = 'computing', ''
                else:
                    failed = handle.hasFailed()
                    failure = failed[1] if isinstance(failed, tuple) and len(failed) > 1 else ''
                    if _result(failed) and failure != 'no link':
                        state = 'failed'
                        message = _text(failure or 'AE request failed')
                    else:
                        canceled, done = handle.isCanceled(), handle.isDone()
                        is_canceled, is_done = _result(canceled), _result(done)
                        detail = canceled[1] if isinstance(canceled, tuple) and len(canceled) > 1 else ''
                        if not detail and isinstance(done, tuple) and len(done) > 1:
                            detail = done[1]
                        # OC 1.12.64 save() changes hasFailed even with a live
                        # link. Live isDone/isCanceled return a bare bool; a
                        # missing restored link returns (bool, 'no link').
                        if detail == 'no link':
                            state, message = 'unknown', 'AE tracking link unavailable. Check the AE CPU before ordering again.'
                            order['hidden'] = True
                        elif detail and str(detail).startswith('request failed'):
                            state, message = 'failed', _text(detail)
                        elif is_canceled:
                            state, message = 'canceled', 'Canceled in AE'
                        elif is_done:
                            state, message = 'done', ''
                        else:
                            state, message = 'running', ''
                order['state'], order['message'] = state, message
                if state in self.ACTIVE and order.get('cancel_requested'):
                    self._cancel(order)
                    continue
                if state not in self.ACTIVE:
                    order.pop('handle', None)
                    order.pop('recipe', None)
            except Exception as error:
                order['state'], order['message'] = 'unavailable', _text(error)

    def cancel(self, identity):
        order = self.orders.get(identity)
        if order is None:
            raise ValueError('Order expired or unknown')
        self.poll(force=True)
        if order['state'] in self.ACTIVE and order.get('handle') is not None:
            if not hasattr(order['handle'], 'cancel'):
                raise ValueError('This server cannot cancel individual orders. Update MPY4OC first.')
            order['cancel_requested'] = True
            self._cancel(order)
        return {'ok': True, 'order': self.public(order)}

    def _cancel(self, order):
        # The native extension acts only on this request's ICraftingLink. A
        # deferred cancellation never resolves a CPU by its name or output.
        try:
            result = order['handle'].cancel()
            accepted = _result(result)
            reason = result[1] if isinstance(result, tuple) and len(result) > 1 else ''
            if accepted:
                order['state'], order['message'] = 'canceled', 'Canceled from the browser'
            elif reason == 'done':
                order['state'], order['message'] = 'done', 'Already completed before cancellation'
            elif reason == 'no link':
                order['state'], order['message'] = 'unknown', 'AE tracking link unavailable. Cancellation cannot be confirmed; check the AE CPU.'
                order['hidden'] = True
            else:
                order['state'] = 'canceling'
                order['message'] = 'Cancellation requested; waiting for recipe calculation.' if reason == 'computing' else 'Cancellation requested; waiting for AE.'
            if order['state'] not in self.ACTIVE:
                order.pop('handle', None)
                order.pop('recipe', None)
                order.pop('cancel_requested', None)
        except Exception as error:
            order['state'], order['message'] = 'unavailable', _text('Cancellation not confirmed: ' + str(error))

    def public(self, order):
        result = {key: order[key] for key in ('id', 'entry', 'amount', 'address', 'state', 'message')}
        result['can_cancel'] = order['state'] in self.ACTIVE and not order.get('cancel_requested') and hasattr(order.get('handle'), 'cancel')
        return result

    def history(self):
        self.poll()
        # Retain consumed IDs internally so stale retries never place another order.
        # Lost tracking handles disappear from the UI; the live CPU view replaces them.
        return {'ok': True, 'token': self.route, 'orders': [self.public(o) for o in self.orders.values() if o['state'] != 'draft' and not o.get('hidden')]}


def write_request(environ):
    # Basic credentials are sent automatically by browsers. Require the current
    # route token (provided by the Java gateway), JSON and a custom header.
    # A cross-origin form cannot satisfy these; no CORS permission is granted.
    token = environ.get('mpy.route')
    if not token or environ.get('HTTP_X_AE_TOKEN') != token:
        raise PermissionError('Page session changed. Reload the page before making changes.')
    origin = environ.get('HTTP_ORIGIN')
    expected = environ.get('wsgi.url_scheme', 'http') + '://' + environ.get('HTTP_HOST', '')
    if origin and origin.lower() != expected.lower():
        raise PermissionError('Cross-origin crafting requests are not allowed')
    if environ.get('CONTENT_TYPE', '').split(';')[0].strip().lower() != 'application/json':
        raise ValueError('Expected application/json')
    body = environ['wsgi.input'].read(4097)
    if len(body) > 4096:
        raise ValueError('Request body exceeds 4096 bytes')
    data = json.loads(body.decode('utf-8'))
    if not isinstance(data, dict):
        raise ValueError('Expected a JSON object')
    return data
