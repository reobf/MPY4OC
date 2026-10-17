"""AE inventory grid, bounded history and confirmed crafting orders."""
import json
import time
import webserver
from aemon import Monitor, rows, quantity, stack_key
from aecrafting import Crafting, write_request
from aecpus import CpuTasks, path_index


def _text(value, limit=128):
    text = str(value)[:limit]
    # VM slices use UTF-16 units; don't leave half of a supplementary character.
    if text and 0xd800 <= ord(text[-1]) <= 0xdbff:
        text = text[:-1]
    return text


class Inventory:
    def __init__(self, components=None, address=None, clock=None, interval=5, history_points=120):
        if interval < 2 or not 2 <= history_points <= 120:
            raise ValueError('interval must be >= 2; history_points must be between 2 and 120')
        self.networks = Monitor(components)
        self.address = address
        self.clock = clock or time.monotonic
        self.cached = None
        self.sampled = None
        self.revision = 0
        self.interval, self.capacity = interval, int(history_points)
        self.tracks = {}
        self.next_id = 0
        self.history_address = None
        self._reset_history()

    def _reset_history(self):
        self.times = [None] * self.capacity
        self.cursor, self.length, self.recorded = 0, 0, None
        for track in self.tracks.values():
            track['values'] = [None] * self.capacity

    def _track(self, entries, keys):
        # At most 512 histories, including recently vanished resources. Evict an
        # oldest non-visible resource when a new visible one needs its slot.
        for key, entry in entries:
            if key not in self.tracks:
                if len(self.tracks) >= 512:
                    for old in self.tracks:
                        if old not in keys:
                            del self.tracks[old]
                            break
                self.next_id += 1
                self.tracks[key] = {'id': str(self.next_id), 'values': [None] * self.capacity}
            track = self.tracks[key]
            track['entry'] = entry
            entry['id'] = track['id']

    def _record(self, now, counts):
        if self.recorded is not None and now - self.recorded < self.interval:
            return
        self.times[self.cursor] = now
        for key, track in self.tracks.items():
            # A complete read distinguishes absence (zero) from failure (None).
            track['values'][self.cursor] = None if counts is None else counts.get(key, 0)
        self.cursor = (self.cursor + 1) % self.capacity
        self.length = min(self.length + 1, self.capacity)
        self.recorded = now

    def poll(self):
        now = self.clock()
        if self.recorded is None or now < self.recorded or now - self.recorded >= self.interval:
            self.snapshot(force=True)

    def connection(self):
        address = self.address
        if not address:
            found = self.networks.networks()
            if not found:
                raise OSError('No AE network connected')
            if len(found) > 1:
                raise OSError('Multiple AE interfaces found; pass a component address to web_ae.py')
            address = found[0][0]
        return address, self.networks.api().proxy(address)

    def snapshot(self, force=False):
        now = self.clock()
        if not force and self.cached is not None and 0 <= now - self.sampled < 2:
            return self.cached
        if self.sampled is not None and now < self.sampled:
            self._reset_history()
        self.revision += 1
        result = {'ok': False, 'error': '', 'address': self.address or '', 'revision': self.revision, 'entries': [], 'item_types': 0, 'fluid_types': 0, 'item_count': '0', 'fluid_amount': '0', 'limited': False, 'history_interval': self.interval, 'history_capacity': self.capacity}
        counts = None
        try:
            address, proxy = self.connection()
            result['address'] = address
            entries, visible, read_counts = [], set(), {}
            for kind, method, total_key in [('item', 'getItemsInNetwork', 'item_count'), ('fluid', 'getFluidsInNetwork', 'fluid_amount')]:
                total, types, selected = 0, 0, 0
                for stack in rows(getattr(proxy, method)()):
                    if not isinstance(stack, dict):
                        raise OSError('Unexpected AE inventory entry')
                    count = quantity(stack)
                    if not count:
                        continue
                    total += count
                    types += 1
                    key = (kind, stack_key(stack))
                    if key in self.tracks or key in visible or selected < 256:
                        read_counts[key] = read_counts.get(key, 0) + count
                    if key in visible:
                        continue
                    if selected < 256:
                        entry = {'kind': kind, 'name': _text(stack.get('name') or ''), 'label': _text(stack.get('label') or stack.get('name') or 'Unknown'), 'amount': str(count), 'damage': _text(stack.get('damage', 0), 32), 'tagged': bool(stack.get('hasTag') or stack.get('tag') or stack.get('nbt'))}
                        entries.append((key, entry))
                        visible.add(key)
                        selected += 1
                    else:
                        result['limited'] = True
                result[kind + '_types'] = types
                result[total_key] = str(total)
            if address != self.history_address:
                self.tracks = {}
                self._reset_history()
                self.history_address = address
            self._track(entries, visible)
            for key, entry in entries:
                entry['amount'] = str(read_counts[key])
                result['entries'].append(entry)
            counts = read_counts
            result['ok'] = True
        except Exception as error:
            result['error'] = _text(error, 256)
            result['entries'] = []
        finished = self.clock()
        self._record(finished, counts)
        self.cached, self.sampled = result, finished
        return result

    def history(self, identity):
        for track in self.tracks.values():
            if track['id'] == identity:
                points = []
                for offset in range(self.length):
                    index = (self.cursor - self.length + offset) % self.capacity
                    value = track['values'][index]
                    points.append([self.times[index], None if value is None else str(value)])
                return {'ok': True, 'entry': track['entry'], 'now': self.clock(), 'interval': self.interval, 'capacity': self.capacity, 'points': points}
        return None


class Server(webserver.Server):
    def __init__(self, inventory, path='/ae', card=None):
        self.inventory = inventory
        self.crafting = Crafting(inventory)
        self.cpus = CpuTasks(inventory)
        webserver.Server.__init__(self, application(inventory, self.crafting, self.cpus), path=path, card=card)
        self.crafting.bind(self.route)

    def handle_request(self):
        self.inventory.poll()
        handled = webserver.Server.handle_request(self)
        self.crafting.bind(self.route)
        self.crafting.poll()
        return handled


def serve(inventory, path='/ae', card=None):
    Server(inventory, path, card).serve_forever()


def application(inventory, crafting=None, cpus=None):
    crafting = crafting or Crafting(inventory)
    cpus = cpus or CpuTasks(inventory)
    def app(environ, start_response):
        crafting.bind(environ.get('mpy.route'))
        cpus.bind(environ.get('mpy.route'))
        headers = [('Cache-Control', 'no-store'), ('X-Content-Type-Options', 'nosniff')]
        method = environ.get('REQUEST_METHOD', 'GET')
        path = environ.get('PATH_INFO', '')
        data = None
        cpu_write = path.startswith('/api/cpus/cancel/')
        write = cpu_write or path == '/api/orders/prepare' or path.startswith('/api/orders/')
        if (write and method != 'POST') or (not write and method not in ('GET', 'HEAD')):
            status, content_type, body = '405 Method Not Allowed', 'text/plain', b'Method not allowed\n'
            headers.append(('Allow', 'POST' if write else 'GET, HEAD'))
        elif path == '/api/cpus' or path.startswith('/api/cpus/'):
            try:
                if cpu_write:
                    request = write_request(environ)
                    data = cpus.cancel(path_index(path, '/api/cpus/cancel/'), request)
                elif path == '/api/cpus':
                    data = cpus.snapshot()
                elif path.startswith('/api/cpus/page/'):
                    data = cpus.snapshot(path_index(path, '/api/cpus/page/'))
                elif path.startswith('/api/cpus/detail/'):
                    data = cpus.detail(path_index(path, '/api/cpus/detail/'))
                else:
                    raise ValueError('Unknown CPU endpoint')
                status = '200 OK' if data['ok'] else '409 Conflict'
            except PermissionError as error:
                status, data = '403 Forbidden', {'ok': False, 'error': _text(error, 256)}
            except (ValueError, TypeError, UnicodeError) as error:
                status, data = '400 Bad Request', {'ok': False, 'error': _text(error, 256)}
            except Exception as error:
                status, data = '503 Service Unavailable', {'ok': False, 'error': _text(error, 256)}
        elif write or path in ('/api/craftables', '/api/orders'):
            try:
                if write:
                    request = write_request(environ)
                    if path == '/api/orders/prepare':
                        data = crafting.prepare(request)
                    elif path.endswith('/cancel'):
                        data = crafting.cancel(path[len('/api/orders/'):-len('/cancel')])
                    else:
                        data = crafting.submit(path[len('/api/orders/'):])
                else:
                    data = crafting.catalog() if path == '/api/craftables' else crafting.history()
                status = '200 OK'
            except PermissionError as error:
                status, data = '403 Forbidden', {'ok': False, 'error': _text(error, 256)}
            except (ValueError, TypeError, UnicodeError) as error:
                status, data = '400 Bad Request', {'ok': False, 'error': _text(error, 256)}
            except Exception as error:
                status, data = '503 Service Unavailable', {'ok': False, 'error': _text(error, 256)}
        elif path in ('', '/'):
            status, content_type, body = '200 OK', 'text/html; charset=utf-8', PAGE.encode('utf-8')
            headers.append(('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src https://huiji-thumb.huijistatic.com https://huiji-public.huijistatic.com; connect-src 'self' https://gtnh.huijiwiki.com; base-uri 'none'; frame-ancestors 'none'"))
            headers.append(('Referrer-Policy', 'no-referrer'))
        elif path == '/api/storage':
            data = inventory.snapshot()
            status = '200 OK' if data['ok'] else '503 Service Unavailable'
        elif path.startswith('/api/history/'):
            data = inventory.history(path[len('/api/history/'):])
            status = '200 OK' if data is not None else '404 Not Found'
            if data is None:
                data = {'ok': False, 'error': 'History expired or resource not tracked'}
        else:
            status, content_type, body = '404 Not Found', 'text/plain', b'Not found\n'
        if data is not None:
            content_type, body = 'application/json; charset=utf-8', json.dumps(data, ensure_ascii=False).encode('utf-8')
        headers.append(('Content-Type', content_type))
        start_response(status, headers)
        # The gateway suppresses HEAD bodies, preserving GET Content-Length.
        return [body]
    return app


PAGE = r'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AE Storage Monitor</title>
<style>
:root{font:15px system-ui,sans-serif;color:#243735;background:#f2f6f4}*{box-sizing:border-box}body{margin:0}main{max-width:1200px;margin:36px auto;padding:0 24px}header{display:flex;align-items:center;justify-content:space-between;gap:20px}h1{font-size:32px;margin:8px 0;letter-spacing:-1px}.eyebrow{color:#337466;letter-spacing:2px;font-size:12px;font-weight:700}.muted{color:#647a75;font-size:13px}#state{border:1px solid #b7d1c6;padding:8px 14px;border-radius:30px;background:#e3f2e9;flex-shrink:0}#state.offline{background:#fff1d9;border-color:#e7cb91}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin:28px 0 20px}.stat{background:white;border:1px solid #dbe5df;border-radius:12px;padding:18px;min-width:0}.stat strong{display:block;font-size:24px;margin-top:10px;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.toolbar{display:flex;flex-wrap:wrap;gap:12px;padding:16px;background:white;border:1px solid #dbe5df;border-radius:12px;align-items:center}input[type=search]{flex:1;min-width:150px}input,select,button{font:inherit;padding:9px 12px;border:1px solid #c7d8cf;border-radius:6px;background:white;color:inherit}button{cursor:pointer}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #60a994;outline-offset:2px}#refresh{background:#186652;color:white;border-color:#186652}button:disabled{opacity:.55;cursor:wait}input[type=checkbox]{accent-color:#186652}.options{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin:14px 0}.options label{font-size:13px}#entries{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(168px,100%),1fr));gap:12px}.resource{display:flex;align-items:center;flex-direction:column;position:relative;min-width:0;min-height:172px;padding:16px 12px;border:1px solid #dbe5df;border-radius:12px;box-shadow:0 2px 3px #203b2f04;text-align:center}.resource:hover{border-color:#6fae97;background:#fcfffd}.icon{width:48px;height:48px;display:grid;place-items:center;margin:2px 0 12px;position:relative}.placeholder{font-size:27px;color:#6a8a7d;line-height:1}.icon img{width:48px;height:48px;image-rendering:pixelated;position:absolute;inset:0}.resource .name{font-size:14px;font-weight:600;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.resource .amount{font-size:19px;font-weight:700;margin-top:8px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere;max-width:100%}.resource .kind{color:#647a75;font-size:11px;margin-top:5px}.resource[data-kind=fluid] .placeholder{color:#548ab5}#notice{white-space:pre-wrap;color:#83551c;overflow-wrap:anywhere}footer{display:flex;justify-content:space-between;gap:16px;margin-top:18px;font-size:12px;color:#647a75}#empty{padding:32px;text-align:center;color:#647a75}#network{overflow-wrap:anywhere}dialog{border:1px solid #dbe5df;border-radius:16px;padding:24px;width:min(900px,calc(100% - 28px));max-height:90vh;overflow:auto;color:inherit;box-shadow:0 24px 80px #132b3e30}dialog::backdrop{background:#173a3060}dialog header{align-items:start}h2{margin:0 0 6px;font-size:23px;overflow-wrap:anywhere}#detail-id{overflow-wrap:anywhere}#close-detail{flex-shrink:0}#detail-amount{font-size:28px;font-weight:700;margin:18px 0 4px;overflow-wrap:anywhere}#history-state{min-height:20px;color:#647a75;font-size:13px}#chart{display:block;width:100%;height:280px;touch-action:pan-y}#sample-detail{min-height:24px;margin:10px 0 0;font-variant-numeric:tabular-nums}#history-caption{margin-top:6px}#latest-samples{max-height:220px;overflow:auto}#latest-samples td{padding:5px 14px 5px 0;font-variant-numeric:tabular-nums}#latest-samples th{text-align:left;padding:8px 14px 5px 0}details{margin-top:16px}summary{cursor:pointer}.credit{font-size:12px;color:#647a75;margin:20px 0}.credit a{color:inherit}
@media(max-width:650px){main{margin:24px auto;padding:0 14px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}h1{font-size:26px}.stat strong{font-size:20px}header{align-items:start}footer{display:grid;gap:6px}dialog{padding:16px}#chart{height:240px}.options{align-items:start}}

.orders-panel{margin-top:28px;padding:22px;background:white;border:1px solid #dbe5df;border-radius:12px}.orders-panel h2{font-size:20px}.order-line{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px 20px;padding:13px 0;border-top:1px solid #e6ede9;overflow-wrap:anywhere}.order-line small{width:100%;color:#83551c}.order-line strong{font-size:13px;color:#337466}.order-line[data-state=failed] strong,.order-line[data-state=unknown] strong{color:#9b4a27}#open-craft,#review-order,#submit-order{background:#186652;color:white;border-color:#186652}#recipes{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(230px,100%),1fr));gap:8px;max-height:280px;overflow:auto;padding:3px}.recipe{text-align:left;min-width:0;padding:12px}.recipe span{display:block;font-size:11px;overflow-wrap:anywhere;margin-top:5px}.recipe strong{overflow-wrap:anywhere}.recipe[aria-pressed=true]{border-color:#186652;background:#e3f2e9;box-shadow:inset 0 0 0 1px #186652}#order-form{border-top:1px solid #dbe5df;margin-top:20px;padding-top:4px}#order-form h3{margin-bottom:6px}#craft-identity,#craft-result{overflow-wrap:anywhere}.quantity-row{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin:16px 0}#craft-quantity{width:140px}#craft-confirm{padding:18px;background:#edf6ef;border:1px solid #c4ddcb;border-radius:10px}#craft-confirm[hidden]{display:none}#submit-order{margin-top:14px}#craft-result{min-height:22px;white-space:pre-wrap}
.cpu-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(235px,100%),1fr));gap:10px}.cpu-card{text-align:left;display:grid;gap:8px;padding:16px;border-radius:10px}.cpu-name{font-weight:650;overflow-wrap:anywhere}.cpu-state{font-size:12px;color:#647a75}.cpu-card[data-busy=true]{background:#edf7f1;border-color:#96c7ae}.cpu-card[data-busy=true] .cpu-state{color:#186652;font-weight:700}.cpu-hardware{font-size:12px;color:#647a75}#cpu-pages{display:flex;align-items:center;justify-content:center;gap:16px;margin-top:16px}#cpu-pages[hidden]{display:none}.cpu-work{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:18px}.cpu-work h3{font-size:16px;margin-bottom:8px}.cpu-list{max-height:330px;overflow:auto;border-top:1px solid #dbe5df;margin-top:12px;min-height:45px}.cpu-item{display:grid;grid-template-columns:32px minmax(0,1fr);gap:2px 10px;align-items:center;padding:10px 0;border-bottom:1px solid #e6ede9;font-size:13px;overflow-wrap:anywhere}.cpu-item strong{grid-column:2;font-variant-numeric:tabular-nums}.cpu-item .icon{grid-row:span 2;width:32px;height:32px;margin:0}.cpu-item .icon img{width:32px;height:32px}#cpu-output{max-width:450px}#cpu-warning{color:#83551c;font-size:13px;white-space:pre-wrap}#cpu-detail-state{margin-top:16px}#cpu-empty{padding:20px;color:#647a75}@media(max-width:650px){.cpu-work{grid-template-columns:1fr}.cpu-list{max-height:220px}}
</style>
<main>
<header><div><div class="eyebrow">MPY4OC / STORAGE &amp; CRAFTING</div><h1>AE Storage Monitor</h1><div id="network" class="muted">Connecting to AE network...</div></div><span id="state" role="status">Connecting</span></header>
<section class="stats" aria-label="Inventory totals">
<div class="stat"><span class="muted">Item types</span><strong id="item_types">--</strong></div>
<div class="stat"><span class="muted">Stored items</span><strong id="item_count">--</strong></div>
<div class="stat"><span class="muted">Fluid types</span><strong id="fluid_types">--</strong></div>
<div class="stat"><span class="muted">Stored fluid / mB</span><strong id="fluid_amount">--</strong></div>
</section>
<p id="notice" role="alert" hidden></p>
<div class="toolbar"><input type="search" id="search" aria-label="Search inventory" placeholder="Search name or item ID...">
<select id="kind" aria-label="Resource type"><option value="all">All resources</option><option value="item">Items</option><option value="fluid">Fluids</option></select>
<select id="sort" aria-label="Sort inventory"><option value="amount">Quantity: high to low</option><option value="label">Name: A to Z</option></select>
<label><input type="checkbox" id="auto" checked> Auto refresh</label><button id="refresh">Refresh now</button><button id="open-craft">Craft an item</button><button id="open-cpus" type="button">View CPUs</button></div>
<div class="options"><label><input type="checkbox" id="images" autocomplete="off"> Load textures from Huiji Wiki CDN</label><button id="refresh-icons" type="button" hidden>Refresh icon index</button><span id="icon-status" class="muted" role="status" hidden></span><span class="muted">Click a resource to view its history.</span></div>
<section id="entries" aria-label="Inventory grid"></section><div id="empty">Waiting for inventory...</div>
<footer><span id="shown">0 resources</span><span id="updated">Polling every 2 seconds</span></footer>
<section id="cpu-panel" class="orders-panel" aria-labelledby="cpus-title"><header><h2 id="cpus-title">Network crafting CPUs</h2><button id="refresh-cpus" type="button">Refresh CPU list</button></header><p class="muted">Live tasks from every source, including terminals, Lua and this page. Select a CPU to inspect its current work.</p><p id="cpu-state" class="muted" role="status">Loading CPUs...</p><div id="cpus" class="cpu-grid"></div><div id="cpu-empty" hidden>No crafting CPUs in this network</div><div id="cpu-pages" hidden><button id="cpu-prev">Previous CPUs</button><span id="cpu-page"></span><button id="cpu-next">Next CPUs</button></div></section>
<section class="orders-panel" aria-labelledby="orders-title"><h2 id="orders-title">Web orders</h2><p class="muted">Orders submitted through this service. Lost tracking records are removed after loading a save; use the live CPU list above.</p><p id="orders-state" class="muted" role="status">Loading orders...</p><div id="orders" aria-live="polite"></div></section>
<p class="credit">Optional textures: <a href="https://gtnh.huijiwiki.com/p/350622" target="_blank" rel="noopener noreferrer">GTNH Huiji Wiki</a>. Enabling textures also allows a fluid icon index from gtnh.huijiwiki.com, cached in this browser for 7 days. Images load from huiji-thumb.huijistatic.com; fluid originals can fall back to huiji-public.huijistatic.com. Resources without a known texture use placeholders.</p>
</main>
<dialog id="cancel-cpu-dialog" aria-labelledby="cancel-cpu-title"><header><h2 id="cancel-cpu-title">Cancel CPU task?</h2></header><p id="cancel-cpu-summary"></p><p id="cancel-cpu-job" class="muted"></p><p>This cancels the selected task, including work submitted from a terminal or another program. Processing already performed cannot be undone. If the task changes, cancellation will be refused.</p><p id="cancel-cpu-error" role="alert"></p><div class="actions"><button id="keep-cpu">Keep crafting</button><button id="confirm-cpu-cancel">Cancel task</button></div></dialog>
<dialog id="cpu-detail" aria-labelledby="cpu-detail-title"><header><div><h2 id="cpu-detail-title">CPU current work</h2><div id="cpu-detail-meta" class="muted"></div></div><button id="close-cpu" aria-label="Close CPU details">Close</button></header><div id="cpu-detail-state" role="status"></div><p><button id="cancel-cpu" disabled>Cancel CPU task</button> <span id="cpu-cancel-state" class="muted" role="status"></span></p><h3>Final output</h3><div id="cpu-output"></div><p id="cpu-warning" role="status"></p><div class="cpu-work"><section><h3>In progress</h3><div id="cpu-active-count" class="muted"></div><div id="cpu-active" class="cpu-list"></div></section><section><h3>Pending</h3><div id="cpu-pending-count" class="muted"></div><div id="cpu-pending" class="cpu-list"></div></section><section><h3>CPU storage</h3><div id="cpu-stored-count" class="muted"></div><div id="cpu-stored" class="cpu-list"></div></section></div><p class="muted">CPU storage includes ingredients, not a completed-task history. This view follows the current CPU slot; CPU order can change when the network changes.</p></dialog>
<dialog id="detail" aria-labelledby="detail-title">
<header><div><h2 id="detail-title">Resource history</h2><div id="detail-id" class="muted"></div></div><button id="close-detail" aria-label="Close history">Close</button></header>
<div id="detail-amount">--</div><div id="history-state" role="status">Loading history...</div>
<canvas id="chart" role="img" aria-label="Inventory quantity history; exact values are listed below"></canvas>
<div id="sample-detail">Move over the chart to inspect a sample.</div>
<div id="history-caption" class="muted"></div>
<details><summary>Exact sample values</summary><div id="latest-samples"></div></details>
</dialog>
<dialog id="craft" aria-labelledby="craft-title">
<header><div><div class="eyebrow">AE AUTO CRAFTING</div><h2 id="craft-title">Craft an item</h2><p class="muted">Choose a recipe, then review the quantity. AE selects a CPU automatically.</p></div><button id="close-craft" aria-label="Close crafting">Close</button></header>
<div class="toolbar"><input type="search" id="recipe-search" aria-label="Search recipes" placeholder="Search craftable outputs..."><button id="reload-recipes">Reload recipes</button></div>
<p id="recipe-state" class="muted" role="status"></p><div id="recipes" aria-label="Craftable outputs"></div>
<form id="order-form"><h3 id="craft-selected">Select a recipe above</h3><div id="craft-identity" class="muted"></div><div class="quantity-row"><label for="craft-quantity" id="quantity-label">Quantity</label><input id="craft-quantity" type="number" min="1" max="1000000" step="1" value="1" required><button id="review-order" type="submit" disabled>Review order</button></div></form>
<section id="craft-confirm" hidden><strong>Confirm crafting order</strong><p id="craft-summary"></p><div class="muted">This requests additional output, regardless of the amount already in storage. Confirmation expires after 60 seconds.</div><button id="submit-order" type="button">Confirm and submit</button></section>
<p id="craft-result" role="status"></p>
</dialog>
<dialog id="cancel-order-dialog" aria-labelledby="cancel-title">
<h2 id="cancel-title">Cancel crafting order?</h2><p id="cancel-summary"></p>
<p class="muted">Only this order will be canceled. If AE is still calculating, cancellation will wait for the job link. Materials already processed will not be rolled back.</p>
<div class="quantity-row"><button id="keep-order">Keep crafting</button><button id="confirm-cancel">Cancel order</button></div><p id="cancel-error" role="alert"></p>
</dialog>
<script>
'use strict';
// Local filename hashing; optional public Wiki metadata and images load only after opt-in.
function md5(text){
 const bytes=new TextEncoder().encode(text),words=new Int32Array(((bytes.length+8>>>6)+1)*16);
 for(let i=0;i<bytes.length;i++)words[i>>2]|=bytes[i]<<((i%4)*8);
 words[bytes.length>>2]|=128<<((bytes.length%4)*8);words[words.length-2]=bytes.length*8;
 const shifts=[7,12,17,22,5,9,14,20,4,11,16,23,6,10,15,21];
 let a0=0x67452301,b0=0xefcdab89|0,c0=0x98badcfe|0,d0=0x10325476;
 for(let offset=0;offset<words.length;offset+=16){
  let a=a0,b=b0,c=c0,d=d0;
  for(let i=0;i<64;i++){
   let f,g;if(i<16){f=(b&c)|(~b&d);g=i;}else if(i<32){f=(d&b)|(~d&c);g=(5*i+1)%16;}else if(i<48){f=b^c^d;g=(3*i+5)%16;}else{f=c^(b|~d);g=(7*i)%16;}
   const shift=shifts[(i>>4)*4+i%4],x=(a+f+(Math.floor(Math.abs(Math.sin(i+1))*4294967296)|0)+words[offset+g])|0;
   a=d;d=c;c=b;b=(b+((x<<shift)|(x>>>(32-shift))))|0;
  }
  a0=(a0+a)|0;b0=(b0+b)|0;c0=(c0+c)|0;d0=(d0+d)|0;
 }
 return [a0,b0,c0,d0].map(n=>[0,8,16,24].map(s=>((n>>>s)&255).toString(16).padStart(2,'0')).join('')).join('');
}
// Only public metadata is cached. The version selects the Wiki dataset, not local Forge IDs.
const WIKI_VERSION='GTNH/2.9.0-RC-1',ICON_CACHE_KEY='mpy4oc.huiji-fluid-icons.v1.'+WIKI_VERSION,ICON_CACHE_TTL=7*24*60*60*1000;
let fluidIcons=new Map();
function iconEntries(entries){
 if(!Array.isArray(entries)||!entries.length||entries.length>8192)throw Error('Invalid icon index');
 const result=new Map();
 for(const pair of entries){
  if(!Array.isArray(pair)||pair.length!==2)throw Error('Invalid icon record');
  const [name,file]=pair;
  if(typeof name!=='string'||name.length>256||!/[\S]/.test(name)||!/^[- A-Za-z0-9_.:,()']+$/.test(name)||typeof file!=='string'||!/^Icon_gregtech_gt[.]GregTech_FluidDisplay_\d{1,10}_[A-Za-z0-9]{1,64}[.]png$/.test(file)||result.has(name))throw Error('Invalid icon record');
  result.set(name,file);
 }
 return result;
}
function cachedIcons(raw,now){
 if(typeof raw!=='string'||raw.length>2*1024*1024)throw Error('Invalid icon cache');
 const data=JSON.parse(raw);
 if(data.schema!==1||data.version!==WIKI_VERSION||!Number.isSafeInteger(data.savedAt)||data.savedAt<=0||data.savedAt>now)throw Error('Invalid icon cache');
 return {map:iconEntries(data.entries),savedAt:data.savedAt,persistent:true};
}
class FluidIconIndex{
 constructor({request=(...args)=>fetch(...args),storage=()=>localStorage,origin=()=>location.origin,now=()=>Date.now(),changed=()=>{},timeout=30000}={}){
  this.request=request;this.storage=storage;this.origin=origin;this.now=now;this.changed=changed;this.timeout=timeout;
  this.enabled=false;this.record=null;this.controller=null;this.pending=null;this.generation=0;
 }
 notify(phase,error=''){
  this.changed(this.record?.map||null,{phase,error,count:this.record?.map.size||0,savedAt:this.record?.savedAt||0,persistent:this.record?.persistent||false});
 }
 setEnabled(enabled){
  if(!enabled){this.enabled=false;this.generation++;this.controller?.abort();this.controller=null;this.pending=null;this.notify('off');return Promise.resolve();}
  this.enabled=true;return this.load(false);
 }
 refresh(){return this.enabled?this.load(true):Promise.resolve();}
 load(force){
  if(this.pending)return this.pending;
  if(!this.record){try{this.record=cachedIcons(this.storage().getItem(ICON_CACHE_KEY),this.now());}catch(error){/* Empty, denied or invalid cache: fetch only after opt-in. */}}
  if(!force&&this.record&&this.now()-this.record.savedAt>=0&&this.now()-this.record.savedAt<ICON_CACHE_TTL){this.notify('cached');return Promise.resolve();}
  const generation=++this.generation,controller=new AbortController();this.controller=controller;
  this.notify(this.record?'refreshing':'loading');
  const task=this.download(controller).then(map=>{
   if(!this.enabled||generation!==this.generation)return;
   const record={map,savedAt:this.now(),persistent:false};
   try{this.storage().setItem(ICON_CACHE_KEY,JSON.stringify({schema:1,version:WIKI_VERSION,savedAt:record.savedAt,entries:[...map]}));record.persistent=true;}catch(error){/* Private mode/quota: keep a usable session cache. */}
   this.record=record;this.notify('updated');
  }).catch(error=>{
   if(this.enabled&&generation===this.generation)this.notify('error',controller.signal.aborted?'Request timed out':String(error.message||'Wiki unavailable'));
  }).finally(()=>{if(generation===this.generation){this.pending=null;this.controller=null;}});
  this.pending=task;return task;
 }
 async api(params,controller){
  if(controller.signal.aborted)throw Error('Request canceled');
  const url=new URL('https://gtnh.huijiwiki.com/api.php');
  // Use the exact origin: Huiji can cache a reflected ACAO header incorrectly for origin=*.
  for(const [key,value] of Object.entries({action:'query',format:'json',formatversion:2,origin:this.origin(),maxlag:5,...params}))url.searchParams.set(key,value);
  const timer=setTimeout(()=>controller.abort(),this.timeout);
  try{
   const response=await this.request(url.href,{credentials:'omit',referrerPolicy:'no-referrer',signal:controller.signal});
   if(!response.ok)throw Error('Wiki HTTP '+response.status);
   const raw=await response.text();if(raw.length>16*1024*1024)throw Error('Wiki response too large');
   const data=JSON.parse(raw);
   if(!data||data.error||data.warnings||!data.query)throw Error('Wiki API unavailable');
   return data;
  }finally{clearTimeout(timer);}
 }
 async download(controller){
  const prefix='Data:Versions/'+WIKI_VERSION+'/Items/',titles=new Set(),seen=new Set();let continuation={},total=null;
  for(let page=0;;page++){
   if(page===16)throw Error('Too many Wiki search pages');
   const data=await this.api({list:'search',srsearch:'"mFluidDisplayHeat" prefix:'+prefix,srlimit:50,srprop:'',...continuation},controller);
   if(!Array.isArray(data.query.search))throw Error('Missing Wiki search results');
   const hits=data.query.searchinfo?.totalhits;
   if(hits!==undefined){if(!Number.isSafeInteger(hits)||hits<1||hits>16||(total!==null&&total!==hits))throw Error('Invalid Wiki search count');total=hits;}
   for(const row of data.query.search){
    if(typeof row.title!=='string'||!row.title.startsWith(prefix)||!/^\d+[.]json$/.test(row.title.slice(prefix.length)))throw Error('Unexpected Wiki data page');
    titles.add(row.title);if(titles.size>16)throw Error('Too many Wiki data pages');
   }
   if(!data.continue)break;
   continuation=data.continue;
   if(typeof continuation!=='object'||Object.keys(continuation).some(key=>!['continue','sroffset'].includes(key)))throw Error('Invalid Wiki continuation');
   const marker=JSON.stringify(continuation);if(seen.has(marker))throw Error('Repeated Wiki continuation');seen.add(marker);
  }
  if(!titles.size||(total!==null&&titles.size!==total))throw Error('Incomplete Wiki search');
  const wanted=[...titles].sort(),icons=new Map();
  for(let offset=0;offset<wanted.length;offset+=2){
   const batch=wanted.slice(offset,offset+2),received=new Set();
   const data=await this.api({prop:'revisions',titles:batch.join('|'),rvprop:'ids|content',rvslots:'main'},controller);
   if(data.continue||!Array.isArray(data.query.pages))throw Error('Incomplete Wiki data');
   for(const page of data.query.pages){
    if('missing' in page||!batch.includes(page.title)||received.has(page.title))throw Error('Missing or unexpected Wiki data page');
    const content=page.revisions?.[0]?.slots?.main?.content;
    if(typeof content!=='string')throw Error('Missing Wiki page content');
    const document=JSON.parse(content);if(!Array.isArray(document.items))throw Error('Invalid Wiki items');
    for(const row of document.items){
     if(typeof row.key!=='string')throw Error('Invalid Wiki item key');
     if(!row.key.startsWith('fluid.'))continue;
     if(typeof row.iconName!=='string'||!row.iconName.startsWith('icon_'))throw Error('Missing fluid filename');
     const name=row.key.slice(6),file='I'+row.iconName.slice(1);
     if(icons.has(name)&&icons.get(name)!==file)throw Error('Conflicting fluid filenames');
     icons.set(name,file);if(icons.size>8192)throw Error('Too many fluid icons');
    }
    received.add(page.title);
   }
   if(received.size!==batch.length)throw Error('Incomplete Wiki data pages');
  }
  return iconEntries([...icons]);
 }
}
function wikiFile(row){
 let name=row.name,damage=row.damage;
 if(row.kind==='fluid'){
  if(['water','minecraft:water','minecraft:flowing_water'].includes(name))return 'Icon_minecraft_flowing_water.png';
  return fluidIcons.get(name)||null;
 }else if(row.kind!=='item')return null;
 if(typeof name!=='string'||!name.includes(':')||!/^[A-Za-z0-9_.:-]+$/.test(name)||!/^\d+$/.test(damage))return null;
 const suffix=BigInt(damage)===0n?'':'_'+damage;
 return 'Icon_'+name.replace(/:/g,'_')+suffix+'.png';
}
function wikiIcon(row){
 const file=wikiFile(row);if(!file)return null;const hash=md5(file);
 return 'https://huiji-thumb.huijistatic.com/gtnh/uploads/thumb/'+hash[0]+'/'+hash.slice(0,2)+'/'+file+'/64px-'+file;
}
function wikiOriginal(row){
 if(row.kind!=='fluid')return null;
 const file=wikiFile(row);if(!file)return null;const hash=md5(file);
 return 'https://huiji-public.huijistatic.com/gtnh/uploads/'+hash[0]+'/'+hash.slice(0,2)+'/'+file;
}
function fraction(value,min,max){return max===min?.5:Number((value-min)*1000000n/(max-min))/1000000;}
// DOM application starts here.
const $=id=>document.getElementById(id),fmt=v=>BigInt(v).toLocaleString('en-US');
let snapshot=null,busy=false,lastSuccess=null,selected=null,historyData=null,historyAt=0,historyController=null,chartPoints=[];
const tiles=new Map(),cpuIconTiles=new Set(),failedIcons=new Set(),imageObserver=new IntersectionObserver(entries=>{
 for(const entry of entries)if(entry.isIntersecting){
  const tile=entry.target;imageObserver.unobserve(tile);
  if($('images').checked&&tile.isConnected)loadIcon(tile);
 }
},{rootMargin:'80px'});
$('images').checked=false;
const iconIndex=new FluidIconIndex({changed:(map,state)=>{
 const enabled=$('images').checked,loading=['loading','refreshing'].includes(state.phase);
 $('refresh-icons').hidden=!enabled;$('refresh-icons').disabled=loading;$('icon-status').hidden=!enabled;
 const count=state.count.toLocaleString('en-US'),date=state.savedAt?new Date(state.savedAt).toLocaleString('en-US'):'';
 let message='';
 if(state.phase==='loading')message='Loading fluid icon index...';
 else if(state.phase==='refreshing')message='Updating icon index; using cached icons...';
 else if(state.phase==='cached')message='Using '+count+' cached fluid icons';
 else if(state.phase==='updated')message='Updated '+count+' fluid icons';
 else if(state.phase==='error')message='Icon index unavailable: '+state.error+'. '+(state.count?'Using cached icons. ':'')+'Use Refresh icon index to retry.';
 if(['cached','updated'].includes(state.phase))message+=state.persistent?' · Saved for 7 days':' · This tab only (browser storage unavailable)';
 $('icon-status').textContent=message;$('icon-status').title=date?'Last updated: '+date:'';
 if(map&&map!==fluidIcons){
  fluidIcons=map;failedIcons.clear();
  for(const tile of [...tiles.values(),...cpuIconTiles])if(tile.row.kind==='fluid'){
   tile.querySelector('img')?.remove();tile.querySelector('.placeholder').hidden=false;imageMode(tile);
  }
 }
}});
function textureMode(){
 iconIndex.setEnabled($('images').checked);
 for(const tile of [...tiles.values(),...cpuIconTiles])imageMode(tile);
}
function apiUrl(path){const url=new URL(location.href);url.username='';url.password='';url.search='';url.hash='';url.pathname=url.pathname.replace(/\/$/,'')+'/api/'+path;return url;}
async function getJson(path,signal){
 const response=await fetch(apiUrl(path),{cache:'no-store',credentials:'same-origin',signal});
 if(response.status===401)throw Error('Authentication required. Reload this page to sign in.');
 const data=await response.json();if(!response.ok||!data.ok)throw Error(data.error||'HTTP '+response.status);return data;
}
function loadIcon(tile){
 if(tile.querySelector('img'))return;
 const urls=[wikiIcon(tile.row),wikiOriginal(tile.row)].filter(url=>url&&!failedIcons.has(url));if(!urls.length)return;
 const image=document.createElement('img');image.alt='';image.width=48;image.height=48;image.referrerPolicy='no-referrer';image.decoding='async';
 image.addEventListener('load',()=>{if(image.isConnected)tile.querySelector('.placeholder').hidden=true;});
 let current=urls.shift();
 image.addEventListener('error',()=>{
  failedIcons.add(current);
  if(urls.length&&image.isConnected&&$('images').checked){current=urls.shift();image.src=current;return;}
  image.remove();tile.querySelector('.placeholder').hidden=false;
 });
 tile.querySelector('.icon').append(image);image.src=current;
}
function imageMode(tile){
 imageObserver.unobserve(tile);
 if(!$('images').checked){const image=tile.querySelector('img');if(image)image.remove();tile.querySelector('.placeholder').hidden=false;}
 else if(!tile.querySelector('img'))imageObserver.observe(tile);
}
function createTile(row){
 const tile=document.createElement('button');tile.className='resource';tile.type='button';tile.dataset.id=row.id;tile.dataset.kind=row.kind;
 const icon=document.createElement('span');icon.className='icon';icon.setAttribute('aria-hidden','true');
 const placeholder=document.createElement('span');placeholder.className='placeholder';placeholder.textContent=row.kind==='fluid'?'◉':'▧';icon.append(placeholder);
 for(const cls of ['name','amount','kind']){const span=document.createElement('span');span.className=cls;tile.append(span);}
 tile.prepend(icon);tile.addEventListener('click',()=>openHistory(tile.row));return tile;
}
function draw(){
 if(!snapshot)return;
 const q=$('search').value.trim().toLowerCase(),kind=$('kind').value;
 const rows=snapshot.entries.filter(r=>(kind==='all'||r.kind===kind)&&(r.label.toLowerCase().includes(q)||r.name.toLowerCase().includes(q)));
 rows.sort((a,b)=>$('sort').value==='label'?a.label.localeCompare(b.label):(BigInt(a.amount)===BigInt(b.amount)?a.label.localeCompare(b.label):BigInt(a.amount)>BigInt(b.amount)?-1:1));
 const shown=new Set(rows.map(r=>r.id));
 for(const [id,tile] of tiles)if(!shown.has(id)){imageObserver.unobserve(tile);tile.remove();tiles.delete(id);}
 let position=$('entries').firstElementChild;
 for(const row of rows){
  let tile=tiles.get(row.id);if(!tile){tile=createTile(row);tiles.set(row.id,tile);}
  tile.row=row;tile.querySelector('.name').textContent=row.label;tile.querySelector('.amount').textContent=fmt(row.amount)+(row.kind==='fluid'?' mB':'');
  tile.querySelector('.kind').textContent=(row.kind==='fluid'?'Fluid':'Item')+(row.tagged?' · NBT':'');
  tile.title=row.label+'\n'+row.name+' / '+row.damage;tile.setAttribute('aria-label',row.label+' — '+fmt(row.amount)+(row.kind==='fluid'?' mB':'')+' — View history');
  if(tile!==position)$('entries').insertBefore(tile,position);else position=position.nextElementSibling;
  imageMode(tile);
 }
 $('empty').hidden=rows.length>0;$('empty').textContent='No matching resources';$('shown').textContent=rows.length+' of '+snapshot.entries.length+' listed resources';
}
function notice(message){$('notice').textContent=message;$('notice').hidden=!message;}
async function refresh(){
 if(busy)return;busy=true;$('refresh').disabled=true;
 try{
  const data=await getJson('storage',AbortSignal.timeout(12000));snapshot=data;lastSuccess=new Date();$('state').textContent='Live';$('state').className='';$('network').textContent='Network '+data.address;
  for(const key of ['item_types','item_count','fluid_types','fluid_amount'])$(key).textContent=fmt(data[key]);
  notice(data.limited?'List limited to 256 item types and 256 fluid types. Totals include all stored resources.':'');
  $('updated').textContent='Updated '+lastSuccess.toLocaleTimeString()+' / sample '+data.revision;draw();
 }catch(error){$('state').textContent='Offline';$('state').className='offline';notice(error.message+(lastSuccess?' | Showing last successful snapshot; values are stale.':''));}
 finally{busy=false;$('refresh').disabled=false;}
 if(selected)loadHistory();
 refreshOrders();refreshCpus();
}
function openHistory(row){
 selected=row;historyData=null;chartPoints=[];if(historyController)historyController.abort();historyController=null;
 $('detail-title').textContent=row.label;$('detail-id').textContent=row.name+' / '+row.damage+(row.tagged?' / NBT':'');
 $('detail-amount').textContent=fmt(row.amount)+(row.kind==='fluid'?' mB':'');$('history-state').textContent='Loading history...';$('sample-detail').textContent='Move over the chart to inspect a sample.';
 $('latest-samples').replaceChildren();$('history-caption').textContent='';$('detail').showModal();drawChart();loadHistory();
}
function closeHistory(){selected=null;historyData=null;if(historyController)historyController.abort();historyController=null;}
async function loadHistory(){
 if(!selected||historyController)return;
 const controller=new AbortController();historyController=controller;
 const identity=selected.id,timer=setTimeout(()=>controller.abort(),12000);
 try{
  const data=await getJson('history/'+encodeURIComponent(identity),controller.signal);
  if(!selected||selected.id!==identity||historyController!==controller)return;
  historyData=data;historyAt=Date.now();const last=data.points.at(-1),known=data.points.filter(p=>p[1]!==null);
  $('detail-amount').textContent=last&&last[1]!==null?fmt(last[1])+(selected.kind==='fluid'?' mB':''):'No reading';
  $('history-state').textContent=last&&last[1]===null?'Latest sample unavailable — gaps are not zero.':known.length<2?'Collecting samples...':'Server samples · '+known.length+' recorded values';
  $('history-caption').textContent='Every '+data.interval+' seconds · up to '+data.capacity+' samples · sampling continues while this page is closed.';
  drawChart();drawSamples();
 }catch(error){if(historyController===controller&&selected)$('history-state').textContent=(historyData?'History is stale. ':'')+(error.name==='AbortError'?'Request timed out.':error.message);}
 finally{clearTimeout(timer);if(historyController===controller)historyController=null;}
}
function sampleTime(t){return new Date(historyAt-(historyData.now-t)*1000).toLocaleTimeString();}
function drawSamples(){
 const table=document.createElement('table'),head=document.createElement('tr');
 for(const title of ['Time','Quantity']){const th=document.createElement('th');th.textContent=title;head.append(th);}table.append(head);
 for(const point of [...historyData.points].reverse()){const tr=document.createElement('tr');for(const text of [sampleTime(point[0]),point[1]===null?'Unavailable':fmt(point[1])+(selected.kind==='fluid'?' mB':'')]){const td=document.createElement('td');td.textContent=text;tr.append(td);}table.append(tr);}
 $('latest-samples').replaceChildren(table);
}
function drawChart(){
 const canvas=$('chart'),width=canvas.clientWidth,height=canvas.clientHeight;if(!width)return;
 const dpr=devicePixelRatio||1;canvas.width=Math.round(width*dpr);canvas.height=Math.round(height*dpr);const ctx=canvas.getContext('2d');ctx.scale(dpr,dpr);ctx.clearRect(0,0,width,height);chartPoints=[];
 if(!historyData||!historyData.points.length)return;
 const points=historyData.points,values=points.filter(p=>p[1]!==null).map(p=>BigInt(p[1]));if(!values.length)return;
 let min=values.reduce((a,b)=>a<b?a:b),max=values.reduce((a,b)=>a>b?a:b);if(min===max){if(min>0n)min--;max++;}
 ctx.font='12px system-ui';const left=Math.min(width*.52,Math.max(48,ctx.measureText(fmt(max)).width+14)),right=width-18,top=16,bottom=height-36;
 const first=points[0][0],last=points.at(-1)[0],span=Math.max(historyData.interval,last-first);
 ctx.strokeStyle='#e0e9e4';ctx.fillStyle='#647a75';ctx.textAlign='right';
 const ticks=new Set();for(let i=0;i<=4;i++){const value=min+(max-min)*BigInt(i)/4n;if(ticks.has(value))continue;ticks.add(value);const y=bottom-fraction(value,min,max)*(bottom-top);ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(right,y);ctx.stroke();ctx.fillText(fmt(value),left-9,y+4);}
 ctx.textAlign='left';ctx.fillText(sampleTime(first),left,height-10);ctx.textAlign='right';ctx.fillText(sampleTime(last),right,height-10);
 ctx.strokeStyle='#186652';ctx.fillStyle='#186652';ctx.lineWidth=2;ctx.beginPath();let previous=null;
 for(const point of points){
  const x=left+(point[0]-first)/span*(right-left),y=point[1]===null?null:bottom-fraction(BigInt(point[1]),min,max)*(bottom-top);chartPoints.push({x,y,point});
  if(y===null){previous=null;continue;}if(previous&&point[0]-previous[0]<=historyData.interval*2)ctx.lineTo(x,y);else ctx.moveTo(x,y);previous=point;
 }ctx.stroke();for(const p of chartPoints)if(p.y!==null){ctx.beginPath();ctx.arc(p.x,p.y,2.5,0,Math.PI*2);ctx.fill();}
 canvas.setAttribute('aria-label','Inventory history: '+values.length+' values, minimum '+fmt(values.reduce((a,b)=>a<b?a:b))+', maximum '+fmt(values.reduce((a,b)=>a>b?a:b))+'. Exact sample values below.');
}
$('chart').addEventListener('pointermove',event=>{if(!chartPoints.length)return;const x=event.clientX-$('chart').getBoundingClientRect().left,p=chartPoints.reduce((a,b)=>Math.abs(a.x-x)<Math.abs(b.x-x)?a:b).point;$('sample-detail').textContent=sampleTime(p[0])+' · '+(p[1]===null?'Unavailable':fmt(p[1])+(selected.kind==='fluid'?' mB':''));});
new ResizeObserver(drawChart).observe($('chart'));
$('close-detail').addEventListener('click',()=>$('detail').close());$('detail').addEventListener('close',closeHistory);
$('search').addEventListener('input',draw);$('kind').addEventListener('change',draw);$('sort').addEventListener('change',draw);$('images').addEventListener('change',textureMode);
$('refresh-icons').addEventListener('click',()=>iconIndex.refresh());$('refresh').addEventListener('click',refresh);
// Never restore an opt-in checkbox automatically on reload/back navigation.
addEventListener('pageshow',()=>{$('images').checked=false;textureMode();});
addEventListener('pagehide',()=>iconIndex.setEnabled(false));
const stateNames={draft:'Awaiting confirmation',submitting:'Submitting',computing:'Calculating',running:'Crafting',canceling:'Cancel requested',done:'Completed',failed:'Failed',canceled:'Canceled',expired:'Expired',unknown:'Unknown — check AE',unavailable:'Status unavailable'};
let recipes=[],craftToken=null,chosenRecipe=null,draft=null,craftBusy=false,ordersBusy=false;
function craftControls(locked){
 craftBusy=locked;
 for(const id of ['reload-recipes','recipe-search','craft-quantity','submit-order'])$(id).disabled=locked;
 $('review-order').disabled=locked||!chosenRecipe;
 for(const button of $('recipes').children)button.disabled=locked;
}
function clearDraft(){draft=null;$('craft-confirm').hidden=true;}
function drawRecipes(){
 const query=$('recipe-search').value.trim().toLowerCase(),matches=recipes.filter(r=>(r.label+' '+r.name).toLowerCase().includes(query));
 $('recipes').replaceChildren();
 for(const row of matches){
  const button=document.createElement('button');button.type='button';button.className='recipe';button.disabled=craftBusy;button.setAttribute('aria-pressed',String(chosenRecipe?.id===row.id));
  const name=document.createElement('strong'),detail=document.createElement('span');name.textContent=row.label;detail.className='muted';detail.textContent=row.name+' / '+row.damage+(row.tagged?' / NBT':'')+(row.kind==='fluid'?' / mB':'');button.append(name,detail);
  button.addEventListener('click',()=>{chosenRecipe=row;clearDraft();$('craft-selected').textContent=row.label;$('craft-identity').textContent=detail.textContent+' · Recipe '+row.id;$('quantity-label').textContent=row.kind==='fluid'?'Quantity (mB)':'Quantity (items)';$('craft-result').textContent='';craftControls(false);drawRecipes();});
  $('recipes').append(button);
 }
 if(!matches.length){const empty=document.createElement('p');empty.className='muted';empty.textContent='No matching recipes';$('recipes').append(empty);}
}
async function loadRecipes(){
 if(craftBusy)return;craftControls(true);clearDraft();chosenRecipe=null;recipes=[];drawRecipes();$('craft-selected').textContent='Select a recipe above';$('craft-identity').textContent='';$('recipe-state').textContent='Loading recipes...';$('craft-result').textContent='';
 try{const data=await getJson('craftables',AbortSignal.timeout(20000));craftToken=data.token;recipes=data.entries;$('recipe-state').textContent=recipes.length+' craftable outputs · includes items with zero stock'+(data.limited?' · Limited to the first 512 outputs.':'');drawRecipes();}
 catch(error){$('recipe-state').textContent=error.message;}
 finally{craftControls(false);}
}
async function postOrder(path,body,token=craftToken){
 const response=await fetch(apiUrl(path),{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-AE-Token':token},body:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
 if(response.status===401)throw Error('Authentication required. Reload this page to sign in.');
 const data=await response.json();if(!response.ok||!data.ok)throw Error(data.error||'HTTP '+response.status);return data;
}
function orderDescription(order){return order.entry.label+' × '+fmt(order.amount)+(order.entry.kind==='fluid'?' mB':' items');}
async function refreshOrders(){
 if(ordersBusy)return;ordersBusy=true;
 try{
  const data=await getJson('orders',AbortSignal.timeout(12000)),active=o=>['submitting','computing','running','unavailable','canceling'].includes(o.state),pending=data.orders.filter(active).reverse(),recent=data.orders.filter(o=>!active(o)).slice(-8).reverse();
  $('orders-state').textContent=data.orders.length?pending.length+' active · '+recent.length+' recent · '+data.orders.length+' orders recorded':'No orders yet';$('orders').replaceChildren();
  for(const order of [...pending,...recent]){
   const line=document.createElement('div');line.className='order-line';line.dataset.state=order.state;
   const description=document.createElement('span'),status=document.createElement('strong');description.textContent='#'+order.id+' · '+orderDescription(order);status.textContent=stateNames[order.state]||order.state;line.append(description,status);
   if(order.can_cancel){const button=document.createElement('button');button.type='button';button.textContent='Cancel';button.setAttribute('aria-label','Cancel order #'+order.id);button.addEventListener('click',()=>openCancel(order,data.token));line.append(button);}
   if(order.message){const message=document.createElement('small');message.textContent=order.message;line.append(message);}$('orders').append(line);
  }
 }catch(error){$('orders-state').textContent='Order status unavailable: '+error.message+' Previous status may be stale.';}
 finally{ordersBusy=false;}
}
$('open-craft').addEventListener('click',()=>{$('craft').showModal();if(!draft)loadRecipes();});
$('close-craft').addEventListener('click',()=>$('craft').close());
$('reload-recipes').addEventListener('click',loadRecipes);$('recipe-search').addEventListener('input',drawRecipes);$('craft-quantity').addEventListener('input',clearDraft);
$('order-form').addEventListener('submit',async event=>{
 event.preventDefault();if(craftBusy||!chosenRecipe)return;clearDraft();craftControls(true);$('craft-result').textContent='Preparing confirmation...';
 try{const data=await postOrder('orders/prepare',{recipe:chosenRecipe.id,amount:$('craft-quantity').value});draft=data.order;$('craft-summary').textContent=orderDescription(draft);$('craft-confirm').hidden=false;$('craft-result').textContent='Nothing has been ordered yet.';$('submit-order').textContent='Confirm and submit';}
 catch(error){$('craft-result').textContent=error.message;}
 finally{craftControls(false);}
});
$('submit-order').addEventListener('click',async()=>{
 if(craftBusy||!draft)return;craftControls(true);$('craft-result').textContent='Submitting order #'+draft.id+'...';
 try{const data=await postOrder('orders/'+encodeURIComponent(draft.id),{});$('craft-result').textContent='Order #'+data.order.id+' · '+orderDescription(data.order)+' · '+stateNames[data.order.state]+(data.order.message?' — '+data.order.message:'');clearDraft();refreshOrders();}
 catch(error){$('craft-result').textContent=error.message+' Submission may have reached AE. Check the orders below, or retry this same order safely.';$('submit-order').textContent='Check / retry same order';}
 finally{craftControls(false);}
});
let cancelSelection=null,cancelBusy=false;
function openCancel(order,token){
 if(cancelBusy)return;cancelSelection={order,token};$('cancel-summary').textContent='Order #'+order.id+' · '+orderDescription(order);$('cancel-error').textContent='';$('cancel-order-dialog').showModal();
}
$('keep-order').addEventListener('click',()=>$('cancel-order-dialog').close());
$('confirm-cancel').addEventListener('click',async()=>{
 if(cancelBusy||!cancelSelection)return;cancelBusy=true;$('confirm-cancel').disabled=true;
 const {order,token}=cancelSelection;
 try{
  await postOrder('orders/'+encodeURIComponent(order.id)+'/cancel',{},token);
  $('cancel-order-dialog').close();refreshOrders();
 }catch(error){$('cancel-error').textContent=error.message+' Check the order status before retrying.';}
 finally{cancelBusy=false;$('confirm-cancel').disabled=false;}
});
let cpuPage=0,cpuBusy=false,cpuSnapshot=null,cpuDetail=null,cpuDetailController=null,cpuCurrent=null,cpuCancelSelection=null,cpuCancelBusy=false;
const cpuButtons=new Map(),cpuItemMaps={output:new Map(),active:new Map(),pending:new Map(),stored:new Map()};
function cpuStatus(value){return value===true?'Crafting':value===false?'Idle':'Unavailable';}
function drawCpus(data){
 cpuSnapshot=data;cpuPage=data.page;
 $('cpu-state').textContent=data.total+' CPUs · '+data.busy+' crafting'+(data.unavailable?' · '+data.unavailable+' unavailable':'')+' · All submissions';
 $('cpu-pages').hidden=data.pages<=1;$('cpu-page').textContent='Page '+(data.page+1)+' of '+data.pages;
 $('cpu-prev').disabled=data.page===0;$('cpu-next').disabled=data.page+1>=data.pages;
 const shown=new Set(data.entries.map(row=>row.index));
 for(const [index,button] of cpuButtons)if(!shown.has(index)){button.remove();cpuButtons.delete(index);}
 for(const row of data.entries){
  let button=cpuButtons.get(row.index);
  if(!button){
   button=document.createElement('button');button.type='button';button.className='cpu-card';
   for(const cls of ['cpu-name','cpu-state','cpu-hardware']){const span=document.createElement('span');span.className=cls;button.append(span);}
   button.addEventListener('click',()=>openCpu(button.row));cpuButtons.set(row.index,button);$('cpus').append(button);
  }
  button.row=row;button.dataset.busy=String(row.busy);button.querySelector('.cpu-name').textContent=row.name;
  button.querySelector('.cpu-state').textContent=cpuStatus(row.busy);button.querySelector('.cpu-hardware').textContent=fmt(row.storage)+' bytes · '+fmt(row.coprocessors)+' co-processors';
  button.setAttribute('aria-label',row.name+' — '+cpuStatus(row.busy)+' — View CPU');
 }
 $('cpu-empty').hidden=data.total>0;
}
async function refreshCpus(){
 if(cpuBusy)return;cpuBusy=true;$('refresh-cpus').disabled=true;
 try{drawCpus(await getJson('cpus/page/'+cpuPage,AbortSignal.timeout(20000)));}
 catch(error){$('cpu-state').textContent='CPU list unavailable: '+error.message+(cpuSnapshot?' Previous list is stale.':'');}
 finally{cpuBusy=false;$('refresh-cpus').disabled=false;}
 if(cpuDetail!==null)refreshCpuDetail();
}
function clearCpuItems(){
 for(const tile of cpuIconTiles)imageObserver.unobserve(tile);
 cpuIconTiles.clear();for(const key of ['output','active','pending','stored']){cpuItemMaps[key].clear();$('cpu-'+key).replaceChildren();}
 for(const key of ['active','pending','stored'])$('cpu-'+key+'-count').textContent='';
}
function cpuItem(row){
 const tile=document.createElement('div');tile.className='cpu-item';tile.row=row;
 const icon=document.createElement('span');icon.className='icon';icon.setAttribute('aria-hidden','true');
 const placeholder=document.createElement('span');placeholder.className='placeholder';placeholder.textContent=row.kind==='fluid'?'◉':'▧';icon.append(placeholder);
 const label=document.createElement('span'),amount=document.createElement('strong');label.className='cpu-item-name';amount.className='cpu-item-amount';label.textContent=row.label;amount.textContent=fmt(row.amount)+(row.kind==='fluid'?' mB':'');tile.title=row.name+' / '+row.damage;
 tile.append(icon,label,amount);cpuIconTiles.add(tile);return tile;
}
function drawCpuItems(group,rows,empty){
 const map=cpuItemMaps[group],container=$('cpu-'+group),seen=new Set(),occurrences=new Map();
 if(!map.size)container.replaceChildren();
 let position=container.firstElementChild;
 for(const row of rows){
  const base=JSON.stringify([row.kind,row.name,row.damage,row.label,row.tagged]),occurrence=occurrences.get(base)||0,key=base+'|'+occurrence;occurrences.set(base,occurrence+1);seen.add(key);
  let tile=map.get(key);if(!tile){tile=cpuItem(row);map.set(key,tile);}
  tile.row=row;tile.querySelector('.cpu-item-amount').textContent=fmt(row.amount)+(row.kind==='fluid'?' mB':'');
  if(tile!==position)container.insertBefore(tile,position);else position=position.nextElementSibling;
 }
 for(const [key,tile] of map)if(!seen.has(key)){imageObserver.unobserve(tile);cpuIconTiles.delete(tile);tile.remove();map.delete(key);}
 if(!rows.length)container.textContent=empty;
}
function openCpu(row){
 cpuCurrent=null;$('cancel-cpu').disabled=true;$('cpu-cancel-state').textContent='';cpuDetail=row.index;cpuDetailController?.abort();cpuDetailController=null;clearCpuItems();
 $('cpu-detail-title').textContent=row.name;$('cpu-detail-meta').textContent='Current CPU slot '+(row.index+1);$('cpu-detail-state').textContent='Loading current work...';$('cpu-warning').textContent='';
 if(!$('cpu-detail').open)$('cpu-detail').showModal();refreshCpuDetail();
}
function closeCpu(){cpuCurrent=null;$('cancel-cpu').disabled=true;cpuDetail=null;cpuDetailController?.abort();cpuDetailController=null;clearCpuItems();}
async function refreshCpuDetail(){
 if(cpuDetail===null||cpuDetailController)return;
 const index=cpuDetail,controller=new AbortController();cpuDetailController=controller;
 const timer=setTimeout(()=>controller.abort(),20000);
 try{
  const data=await getJson('cpus/detail/'+index,controller.signal);
  if(cpuDetail!==index||cpuDetailController!==controller)return;
  cpuCurrent=data;$('cancel-cpu').disabled=cpuCancelBusy||!data.job;$('cpu-cancel-state').textContent=data.cancel_error||'';
  $('cpu-detail-title').textContent=data.cpu.name;
  $('cpu-detail-meta').textContent='Current CPU slot '+(data.cpu.index+1)+' · '+fmt(data.cpu.storage)+' bytes · '+fmt(data.cpu.coprocessors)+' co-processors';
  $('cpu-detail-state').textContent=cpuStatus(data.cpu.busy)+' · Updated '+new Date().toLocaleTimeString('en-US');
  drawCpuItems('output',data.output?[data.output]:[],data.cpu.busy?'Final output unavailable; a crafting monitor may be required.':'No active task');
  for(const key of ['active','pending','stored']){
   const total=data.totals[key];$('cpu-'+key+'-count').textContent=total?fmt(total.items)+' items · '+fmt(total.fluid)+' mB · '+total.types+' types':'';
   drawCpuItems(key,data[key],total||!data.cpu.busy?'Empty':'Unavailable');
  }
  $('cpu-warning').textContent=[...data.warnings,...data.limited.map(key=>key+': showing the first 128 resource types; totals include all types.')].join(' ');
  for(const tile of cpuIconTiles)imageMode(tile);
 }catch(error){
  if(cpuDetail===index&&cpuDetailController===controller){cpuCurrent=null;$('cancel-cpu').disabled=true;$('cpu-cancel-state').textContent='';clearCpuItems();$('cpu-detail-state').textContent='CPU unavailable: '+(error.name==='AbortError'?'Request timed out':error.message);$('cpu-warning').textContent='Refresh the CPU list if the network changed.';}
 }finally{clearTimeout(timer);if(cpuDetailController===controller)cpuDetailController=null;}
}
$('cancel-cpu').addEventListener('click',()=>{
 if(cpuCancelBusy||!cpuCurrent?.job)return;
 // Freeze the task and route shown at confirmation, even as live polling continues.
 cpuCancelSelection=cpuCurrent;
 const data=cpuCancelSelection,output=data.output;
 $('cancel-cpu-summary').textContent=data.cpu.name+' · CPU slot '+(data.cpu.index+1)+' · '+(output?output.label+' × '+fmt(output.amount)+(output.kind==='fluid'?' mB':' items'):'Final output unavailable');
 $('cancel-cpu-job').textContent='Task ID: '+data.job;$('cancel-cpu-error').textContent='';$('cancel-cpu-dialog').showModal();
});
$('keep-cpu').addEventListener('click',()=>$('cancel-cpu-dialog').close());
$('cancel-cpu-dialog').addEventListener('cancel',event=>{if(cpuCancelBusy)event.preventDefault();});
$('confirm-cpu-cancel').addEventListener('click',async()=>{
 if(cpuCancelBusy||!cpuCancelSelection)return;
 const data=cpuCancelSelection;cpuCancelBusy=true;$('confirm-cpu-cancel').disabled=true;$('keep-cpu').disabled=true;$('cancel-cpu').disabled=true;
 try{
  await postOrder('cpus/cancel/'+data.cpu.index,{job:data.job,address:data.address},data.token);
  $('cancel-cpu-dialog').close();$('cpu-cancel-state').textContent='Task canceled.';
 }catch(error){$('cancel-cpu-error').textContent=error.message+' Refresh the details before choosing another task.';}
 finally{
  cpuCancelBusy=false;$('confirm-cpu-cancel').disabled=false;$('keep-cpu').disabled=false;
  cpuCurrent=null;$('cancel-cpu').disabled=true;cpuDetailController?.abort();cpuDetailController=null;refreshCpus();refreshOrders();
 }
});
$('refresh-cpus').addEventListener('click',refreshCpus);
$('open-cpus').addEventListener('click',()=>$('cpu-panel').scrollIntoView({behavior:'smooth',block:'start'}));
$('cpu-prev').addEventListener('click',()=>{if(!cpuBusy&&cpuPage>0){cpuPage--;refreshCpus();}});
$('cpu-next').addEventListener('click',()=>{if(!cpuBusy&&cpuSnapshot&&cpuPage+1<cpuSnapshot.pages){cpuPage++;refreshCpus();}});
$('close-cpu').addEventListener('click',()=>$('cpu-detail').close());
$('cpu-detail').addEventListener('close',closeCpu);

setInterval(()=>{if($('auto').checked&&!document.hidden)refresh();},2000);refresh();

</script></html>
'''
