package net.mpy.vm;

import org.junit.jupiter.api.Test;

class WebPythonTest {
    @Test void websocketPythonHandlerReceivesTypedMessagesAndCleansUp() throws Exception {
        StdlibTestSupport.run("""
            import webserver, time
            class Card:
                def __init__(self):
                    self.route = True
                    self.accepted = False
                    self.events = [{'type':'message', 'data':'hello'}, {'type':'message', 'data':b'\\x00\\xff'}, {'type':'close'}]
                    self.sent = []
                    self.closed = []
                def open(self, path, websocket):
                    assert path == '/events' and websocket
                    return 'route'
                def hasRoute(self, route):
                    return self.route
                def wsPoll(self, route):
                    if self.accepted:
                        return None
                    self.accepted = True
                    return {'id':'connection', 'environ':{'REMOTE_USER':'test'}}
                def wsReceive(self, ident):
                    return self.events.pop(0)
                def wsSend(self, ident, data, binary):
                    self.sent.append((data,binary))
                def wsClose(self, ident):
                    self.closed.append(ident)
                def close(self, route):
                    self.route = False
            card = Card()
            done = []
            def echo(connection):
                assert connection.environ['REMOTE_USER'] == 'test'
                try:
                    while True:
                        connection.send(connection.recv())
                except OSError:
                    done.append(True)
            server = webserver.WebSocketServer(echo, '/events', card)
            assert server.handle_request()
            while not done or server.connections:
                time.sleep(0)
            assert card.sent == [(b'hello',False),(b'\\x00\\xff',True)]
            server.close()
            assert not card.route and not webserver._servers
            passed = True
            """, "webserver");
    }
    @Test void cgiTextReadsCountCharactersAndNormalizeNewlines() throws Exception {
        StdlibTestSupport.run("""
            from _webstdio import TextStream
            text = TextStream('Aé水\\r\\nZ'.encode('utf-8'))
            assert text.read(2) == 'Aé'
            assert text.readline() == '水\\n'
            assert text.read() == 'Z'
            passed = True
            """);
    }
    @Test void wsgiBodyHeadersGeneratorCloseAndErrorReplacement() throws Exception {
        StdlibTestSupport.run("""
            import webserver
            request = {'environ': {'REQUEST_METHOD': 'POST', 'wsgi.url_scheme': 'https'}, 'body': b'hello\\x00\\xff'}
            closed = []
            def application(env, start):
                assert env['wsgi.input'].read() == request['body']
                assert env['wsgi.version'] == (1,0)
                write = start('201 Created', [('Content-Type','application/octet-stream'), ('Set-Cookie','a=1'), ('Set-Cookie','b=2')])
                write(b'A')
                try:
                    yield b'B'
                    yield request['body']
                finally:
                    closed.append(True)
            code, headers, body = webserver.run_wsgi(application, request)
            assert code == 201 and body == b'ABhello\\x00\\xff'
            assert headers.count('Set-Cookie:') == 2 and closed == [True]
            def failed(env, start):
                write = start('200 OK', [])
                write(b'partial')
                start('500 Error', [], (ValueError, ValueError('bad'), None))
                return [b'error']
            assert webserver.run_wsgi(failed, request) == (500, '', b'error')
            passed = True
            """, "webserver");
    }
    @Test void invalidWsgiOutputAndCgiHeadersAreRejected() throws Exception {
        StdlibTestSupport.run("""
            import webserver
            def fails(fn):
                try:
                    fn()
                except (ValueError, TypeError, RuntimeError, OSError):
                    return
                raise AssertionError('accepted invalid response')
            request = {'environ': {}, 'body': b''}
            fails(lambda: webserver.run_wsgi(lambda e,s: ['text'], request))
            def bad(e,s):
                s('200 OK', [])
                return ['not bytes']
            fails(lambda: webserver.run_wsgi(bad,request))
            fails(lambda: webserver._headers([('X-Test', 'a\\r\\nInjected: yes')]))
            fails(lambda: webserver._headers([('Transfer-Encoding', 'chunked')]))
            fails(lambda: webserver.parse_cgi(b'hello'))
            assert webserver.parse_cgi(b'Status: 201 Created\\r\\nContent-Type: application/octet-stream\\r\\n\\r\\n\\x00\\xff') == (201, 'Content-Type: application/octet-stream', b'\\x00\\xff')
            assert webserver.parse_cgi(b'Location: https://example.org/\\n\\n')[0] == 302
            passed = True
            """, "webserver");
    }
    @Test void printAndInputRedirectByProgramAndSurviveSnapshot() throws Exception {
        String source="""
            import sys, _program
            from _webstdio import TextStream, print_to_stream
            a, b = TextStream(), TextStream()
            sys._contexts['cgi-a'] = {'stdout': a, 'stdin': TextStream(b'hello\\n'), '_print': print_to_stream}
            sys._contexts['cgi-b'] = {'stdout': b, '_print': print_to_stream}
            _program.set_current('cgi-a')
            print('A', 1, sep=':', end='!')
            assert input() == 'hello'
            _program.set_current('cgi-b')
            print('B')
            assert a.buffer.getvalue() == b'A:1!' and b.buffer.getvalue() == b'B\\n'
            ready = True
            while not globals().get('resume'):
                pass
            print('after')
            assert b.buffer.getvalue() == b'B\\nafter\\n'
            _program.set_current(None)
            passed = True
            """;
        Vm vm=StdlibTestSupport.start(source);
        while(!Boolean.TRUE.equals(vm.globals.get("ready")))vm.step(new int[]{1000});
        Vm restored=Vm.fromSnapshot(vm.saveSnapshot(),new java.util.HashMap<>(),null);
        restored.globals.put("resume",true);
        StdlibTestSupport.finish(restored);
        org.junit.jupiter.api.Assertions.assertEquals(true,restored.globals.get("passed"));
    }
}
