package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.mpy.vm.*;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.*;
import reobf.mpy4oc.web.*;

class WebEndToEndTest {
    @AfterEach void cleanup() { WebServices.shutdown(); }
    private static final String SCRIPT = """
        import webserver
        class Card:
            def open(self,path):
                return __web_open(path)
            def close(self,route):
                return __web_close(route)
            def hasRoute(self,route):
                return __web_has(route)
            def poll(self,route):
                return __web_poll(route)
            def pending(self,request):
                return __web_pending(request)
            def respond(self,*args):
                return __web_reply(*args)
        def application(env,start):
            assert env['REMOTE_USER'] == 'factory'
            assert 'HTTP_AUTHORIZATION' not in env
            start('200 OK',[('Content-Type','application/octet-stream')])
            return [env['wsgi.input'].read(), b'!']
        if MODE == 'cgi':
            FAKE_VFS.files['/home/web.py'] = "import sys, os\\nassert os.getenv('REMOTE_USER') == 'factory'\\nprint('Content-Type: application/octet-stream')\\nprint()\\nsys.stdout.buffer.write(sys.stdin.buffer.read() + b'!')\\n"
            server = webserver.Server(card=Card(), cgi='/home/web.py')
        else:
            server = webserver.Server(application, card=Card())
        ready = True
        while not globals().get('resume'):
            computer._yield_tick()
        while not server.handle_request():
            computer._yield_tick()
        assert server.last_error is None, server.last_error
        server.close()
        passed = True
        """;
    private TkPythonTest.Host host(WebServices.Service service, String mode) throws Exception {
        var h=TkPythonTest.shellHost(SCRIPT);
        Map<String,byte[]> modules=new HashMap<>();
        for(String name:new String[]{"shell","fs","osfs","ospath","event","aemon","webserver"})modules.put(name,net.mpy.compiler.MpyCross.compile(Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/"+name+".py")),name+".py"));
        h.vm.setModuleFinder(modules::get);h.vm.globals.put("MODE",mode);
        Map<String,HostFunction> calls=new HashMap<>();
        calls.put("__web_open", a->service.open((String)a[0]));
        calls.put("__web_close", a->{service.remove((String)a[0]);return PyObj.NONE;});
        calls.put("__web_has", a->service.hasRoute((String)a[0]));
        calls.put("__web_pending", a->service.pending((String)a[0]));
        calls.put("__web_poll", a->{WebServices.Request r=service.poll((String)a[0]);return r==null?PyObj.NONE:h.converter.toPy(li.cil.oc.server.driver.Registry$.MODULE$.convert(new Object[]{r.data()})[0]);});
        calls.put("__web_reply", a->{
            List<String[]> headers=new ArrayList<>();for(String line:((String)a[2]).split("\r\n")){int c=line.indexOf(':');headers.add(new String[]{line.substring(0,c),line.substring(c+1).trim()});}
            return service.finish((String)a[0],new WebServices.Response(((Number)a[1]).intValue(),headers,((PyObj.Bytes)a[3]).data));
        });
        calls.forEach((name,fn)->{h.registry.register(name,fn);h.vm.globals.put(name,fn);h.globals.put(name,fn);});
        return h;
    }
    private void check(String mode,boolean restore) throws Exception {
        WebServices.Service service=WebServices.register("factory","password");
        var h=host(service,mode);h.runUntil("ready");
        if(restore) {
            byte[] saved=h.vm.saveSnapshot(h.registry);service.close();
            service=WebServices.register("factory","password");
            var replacement=host(service,mode);
            replacement.vm=Vm.fromSnapshot(saved,replacement.globals,replacement.registry);
            h=replacement;
        }
        h.vm.globals.put("resume",true);
        // Re-establish the route after restore before sending the external request.
        for(int i=0;i<100;i++)h.vm.step(new int[]{1000});
        try(WebGateway gateway=new WebGateway("127.0.0.1",0,null,false);HttpClient client=HttpClient.newHttpClient()) {
            byte[] body=new byte[]{0,65,-1};
            String auth=Base64.getEncoder().encodeToString("factory:password".getBytes(StandardCharsets.UTF_8));
            var response=client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+gateway.port()+"/test")).header("Authorization","Basic "+auth).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
            h.runUntil("passed");
            HttpResponse<byte[]> result=response.get(5,TimeUnit.SECONDS);assertEquals(200,result.statusCode());assertArrayEquals(new byte[]{0,65,-1,33},result.body());
        }
    }
    @Test void httpThroughOcConversionToWsgiVm() throws Exception {check("wsgi",false);}
    @Test void httpThroughOcConversionToCgiPid() throws Exception {check("cgi",false);}
    @Test void savedWsgiServerReRegistersWithoutReusingOldConnections() throws Exception {check("wsgi",true);}
}
