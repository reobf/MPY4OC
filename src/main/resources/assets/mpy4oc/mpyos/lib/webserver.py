"""HTTP(S) and uwsgi applications on a Web Server Card.

WSGI callable interface with bounded buffering (1 MiB response, 256 KiB request).
CGI runs on the OC filesystem in its own virtual PID, cwd, environment and stdio.
No native host processes, no direct TCP listen, no assumption that CPython web
frameworks and their dependencies are available in this VM.
"""
import io
import os
import sys
import time
import _program

_servers = []
_HOP = ('connection', 'transfer-encoding', 'upgrade', 'keep-alive', 'trailer', 'te', 'proxy-authenticate', 'proxy-authorization')


def _cleanup(program):
    if program is None or _program.live(program):
        return
    for server in list(_servers):
        if server.owner == program:
            server.close()


_program._on_end.append(_cleanup)


def _headers(headers):
    lines = []
    for name, value in headers:
        if not isinstance(name, str) or not isinstance(value, str):
            raise TypeError('response headers must be strings')
        if name.lower() in _HOP:
            raise ValueError('hop-by-hop headers are not supported')
        if not name or any(c not in "!#$%&'*+.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-" for c in name):
            raise ValueError('invalid response header name')
        if '\r' in value or '\n' in value or '\x00' in value:
            raise ValueError('invalid response header value')
        value.encode('latin-1')
        lines.append(name + ': ' + value)
    result = '\r\n'.join(lines)
    if len(lines) > 64 or len(result) > 16384:
        raise ValueError('response headers too large')
    return result


def run_wsgi(application, request):
    environ = dict(request['environ'])
    environ.update({'wsgi.version': (1, 0), 'wsgi.input': io.BytesIO(request['body']), 'wsgi.errors': io.StringIO(), 'wsgi.multithread': False, 'wsgi.multiprocess': False, 'wsgi.run_once': False})
    state = {'status': None, 'headers': None, 'size': 0, 'parts': []}
    def write(data):
        if state['status'] is None:
            raise RuntimeError('start_response was not called')
        if not isinstance(data, bytes):
            raise TypeError('WSGI applications must yield bytes')
        state['size'] += len(data)
        if state['size'] > 1048576:
            raise OSError('response exceeds 1 MiB')
        state['parts'].append(data)
    def start_response(status, headers, exc_info=None):
        if exc_info is None and state['status'] is not None:
            raise RuntimeError('start_response called twice')
        if not isinstance(status, str) or len(status) < 4 or status[3] != ' ' or not status[:3].isdigit() or '\r' in status or '\n' in status:
            raise ValueError('invalid WSGI status')
        code = int(status[:3])
        if code < 200 or code > 599:
            raise ValueError('invalid WSGI status')
        state['headers'] = _headers(headers)
        state['status'] = code
        if exc_info is not None:
            state['parts'], state['size'] = [], 0
        return write
    result = application(environ, start_response)
    try:
        for part in result:
            write(part)
        if state['status'] is None:
            raise RuntimeError('start_response was not called')
        return state['status'], state['headers'], b''.join(state['parts'])
    finally:
        close = getattr(result, 'close', None)
        if close is not None:
            close()


def parse_cgi(output):
    separator = b'\r\n\r\n'
    split = output.find(separator)
    if split < 0:
        separator = b'\n\n'
        split = output.find(separator)
    if split < 0 or split > 16384:
        raise ValueError('CGI response has no valid header section')
    status, headers, has_type, location = 200, [], False, False
    seen_status = False
    for line in output[:split].decode('latin-1').replace('\r\n', '\n').split('\n'):
        if ':' not in line:
            raise ValueError('invalid CGI header')
        name, value = line.split(':', 1)
        value = value.strip()
        if name.lower() == 'status':
            if seen_status or len(value) < 4 or value[3] != ' ' or not value[:3].isdigit():
                raise ValueError('invalid CGI Status')
            status, seen_status = int(value[:3]), True
        else:
            headers.append((name, value))
            has_type = has_type or name.lower() == 'content-type'
            location = location or name.lower() == 'location'
    if not has_type and not location:
        raise ValueError('CGI response requires Content-Type or Location')
    if location and not seen_status:
        status = 302
    if status < 200 or status > 599:
        raise ValueError('invalid CGI Status')
    return status, _headers(headers), output[split+len(separator):]


class Server:
    def __init__(self, application=None, path='/', card=None, cgi=None):
        if (application is None) == (cgi is None):
            raise ValueError('specify either a WSGI application or a CGI script')
        if application is not None and not callable(application):
            raise TypeError('application must be callable')
        self.card = component.webserver if card is None else card
        self.application, self.path = application, path
        self.cgi = os.path.abspath(cgi) if cgi is not None else None
        self.owner = _program.current()
        self.route = self.card.open(path)
        self.closed = False
        self.last_error = None
        _servers.append(self)

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                self.card.close(self.route)
            except (OSError, ValueError):
                pass
            if self in _servers:
                _servers.remove(self)

    def _cgi(self, request):
        import _process
        from _webstdio import TextStream, print_to_stream
        env = os.environ.copy()
        for key, value in request['environ'].items():
            if isinstance(value, str) and '.' not in key:
                env[key] = value
        env['GATEWAY_INTERFACE'] = 'CGI/1.1'
        env['SCRIPT_FILENAME'] = self.cgi
        runtime = _process.runtime()
        prepared = runtime.prepare([self.cgi], os.path.dirname(self.cgi), env, True)
        stdin, stdout, stderr = TextStream(request['body']), TextStream(), TextStream(limit=65536)
        prepared['stdio'] = {'stdin': stdin, 'stdout': stdout, 'stderr': stderr, '_print': print_to_stream}
        job = runtime.spawn(prepared, background=True, line='cgi ' + self.cgi)
        try:
            while job['returncode'] is None:
                _program.check()
                if not self.card.pending(request['id']):
                    raise OSError('HTTP client disconnected or request timed out')
                time.sleep(0.05)
            if job['returncode'] != 0:
                raise RuntimeError('CGI exited with status ' + str(job['returncode']) + ': ' + str(job['error']))
            return parse_cgi(stdout.buffer.getvalue())
        finally:
            if job['returncode'] is None:
                runtime.terminate(job, 9)

    def handle_request(self):
        if self.closed:
            raise OSError('server is closed')
        if not self.card.hasRoute(self.route):
            # Route registrations are volatile; never replay an already accepted request.
            self.route = self.card.open(self.path)
        request = self.card.poll(self.route)
        if request is None:
            return False
        try:
            response = self._cgi(request) if self.cgi is not None else run_wsgi(self.application, request)
        except Exception as error:
            self.last_error = str(error)
            response = (500, 'Content-Type: text/plain; charset=utf-8', b'Application error')
        self.card.respond(request['id'], response[0], response[1], response[2])
        return True

    def serve_forever(self):
        try:
            while not self.closed:
                _program.check()
                if not self.handle_request():
                    time.sleep(0.05)
        finally:
            self.close()

    def __enter__(self):
        return self
    def __exit__(self, *error):
        self.close()


def serve_wsgi(application, path='/', card=None):
    Server(application, path, card).serve_forever()


def serve_cgi(filename, path='/', card=None):
    Server(path=path, card=card, cgi=filename).serve_forever()


class WebSocket:
    def __init__(self, card, connection):
        self.card, self.id = card, connection['id']
        self.environ = connection['environ']
        self.closed = False
    def recv(self, timeout=None):
        deadline = None if timeout is None else time.monotonic() + timeout
        while not self.closed:
            _program.check()
            event = self.card.wsReceive(self.id)
            if event is not None:
                if event['type'] == 'close':
                    self.closed = True
                    break
                return event['data']
            if deadline is not None and time.monotonic() >= deadline:
                raise TimeoutError('WebSocket receive timed out')
            time.sleep(0.05)
        raise OSError('WebSocket is closed')
    def send(self, data):
        if self.closed:
            raise OSError('WebSocket is closed')
        if not isinstance(data, (str, bytes)):
            raise TypeError('WebSocket messages must be str or bytes')
        binary = isinstance(data, bytes)
        self.card.wsSend(self.id, data if binary else data.encode('utf-8'), binary)
    def close(self):
        if not self.closed:
            self.closed = True
            try:
                self.card.wsClose(self.id)
            except (OSError, ValueError):
                pass
    def __enter__(self):
        return self
    def __exit__(self, *error):
        self.close()


class WebSocketServer(Server):
    def __init__(self, handler, path='/', card=None):
        if not callable(handler):
            raise TypeError('handler must be callable')
        self.card = component.webserver if card is None else card
        self.handler, self.path = handler, path
        self.owner = _program.current()
        self.route = self.card.open(path, True)
        self.closed, self.last_error = False, None
        self.connections = []
        _servers.append(self)
    def _run(self, connection):
        try:
            self.handler(connection)
        except Exception as error:
            self.last_error = str(error)
        finally:
            connection.close()
            self.connections.remove(connection)
    def handle_request(self):
        import threading
        if self.closed:
            raise OSError('server is closed')
        if not self.card.hasRoute(self.route):
            self.route = self.card.open(self.path, True)
        connection = self.card.wsPoll(self.route)
        if connection is None:
            return False
        socket = WebSocket(self.card, connection)
        self.connections.append(socket)
        threading.Thread(target=self._run, args=(socket,), daemon=True).start()
        return True
    def close(self):
        for connection in list(self.connections):
            connection.close()
        Server.close(self)


def serve_websocket(handler, path='/', card=None):
    WebSocketServer(handler, path, card).serve_forever()
