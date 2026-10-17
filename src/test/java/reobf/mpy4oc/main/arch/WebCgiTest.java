package reobf.mpy4oc.main.arch;

import java.nio.file.*;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class WebCgiTest {
    private TkPythonTest.Host host(String source) throws Exception {
        var h = TkPythonTest.shellHost("import webserver\n" + source);
        java.util.Map<String,byte[]> modules = new java.util.HashMap<>();
        for (String name : new String[]{"shell", "fs", "osfs", "ospath", "event", "aemon", "webserver"})
            modules.put(name, net.mpy.compiler.MpyCross.compile(Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/" + name + ".py")),name+".py"));
        h.vm.setModuleFinder(modules::get);
        return h;
    }
    private static String quote(String text) { return "'"+text.replace("\\","\\\\").replace("'","\\'").replace("\r","\\r").replace("\n","\\n")+"'"; }

    @Test void cgiGetsOwnPidCwdEnvironmentAndBinaryStdio() throws Exception {
        var h=host("""
            import _process, _program, sys
            FAKE_VFS.files['/home/hello.py'] = '''
            import sys, os
            assert os.getenv('REQUEST_METHOD') == 'POST'
            assert os.getenv('REMOTE_USER') == 'factory'
            assert os.getcwd() == '/home'
            assert os.getpid() != int(os.getenv('PARENT_PID'))
            print('Status: 201 Created')
            print('Content-Type: application/octet-stream')
            print()
            sys.stdout.buffer.write(sys.stdin.buffer.read())
            '''
            class Card:
                def __init__(self):
                    self.requests = [{'id':'req-1', 'environ':{'REQUEST_METHOD':'POST','REMOTE_USER':'factory','CONTENT_LENGTH':'3'}, 'body':b'A\\x00\\xff'}]
                    self.responses = []
                    self.active = True
                def open(self, path):
                    return 'route'
                def hasRoute(self, route):
                    return self.active
                def poll(self, route):
                    return self.requests.pop(0) if self.requests else None
                def pending(self, request):
                    return True
                def respond(self, *args):
                    self.responses.append(args)
                def close(self, route):
                    self.active = False
            os.environ['PARENT_PID'] = str(os.getpid())
            os.environ['REQUEST_METHOD'] = 'parent'
            card = Card()
            server = webserver.Server(card=card, cgi='/home/hello.py')
            assert server.handle_request()
            assert card.responses == [('req-1', 201, 'Content-Type: application/octet-stream', b'A\\x00\\xff')], (card.responses, server.last_error)
            assert os.getenv('REQUEST_METHOD') == 'parent'
            assert getattr(sys, 'stdout', None) is None
            assert not server.handle_request()
            server.close()
            assert not card.active and not webserver._servers
            passed = True
            """);
        h.runUntil("passed");
    }
}
