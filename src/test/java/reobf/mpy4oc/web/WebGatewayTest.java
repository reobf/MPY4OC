package reobf.mpy4oc.web;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class WebGatewayTest {
    @AfterEach void clean() { WebServices.shutdown(); }
    static String basic(String value) { return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    static Map<String,Object> env(String path) { Map<String,Object> e = new HashMap<>(); e.put("PATH_INFO", path); return e; }

    @Test void firstRegistrationWinsAndOldOwnerCannotUnregisterNewOwner() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<WebServices.Service>> tasks = new ArrayList<>();
            for (int i=0; i<8; i++) tasks.add(threads.submit(() -> { start.await(); try { return WebServices.register("factory", "secret"); } catch (IllegalArgumentException occupied) { return null; } }));
            start.countDown();
            WebServices.Service winner = null; int wins=0;
            for (Future<WebServices.Service> task : tasks) { WebServices.Service s=task.get(2,TimeUnit.SECONDS); if(s!=null) { winner=s; wins++; } }
            assertEquals(1,wins); assertSame(winner,WebServices.authenticate(basic("factory:secret")));
            assertNull(WebServices.authenticate(basic("factory:wrong")));
            winner.close(); WebServices.Service next = WebServices.register("factory","new"); winner.close();
            assertSame(next,WebServices.authenticate(basic("factory:new")));
            assertNull(WebServices.authenticate(basic("factory:secret")));
        } finally { threads.shutdownNow(); }
    }
    @Test void prefixRoutingQueuesAndShutdownAreBounded() {
        WebServices.Service s = WebServices.register("f","p");
        String all=s.open("/"), api=s.open("/api");
        List<WebServices.Response> replies=new ArrayList<>();
        Map<String,Object> e=env("/api/test"); e.put("HTTP_AUTHORIZATION","secret"); e.put("REMOTE_USER","spoof");
        String id=s.submit(e,new byte[]{0,-1},replies::add);
        assertNull(s.poll(all)); WebServices.Request r=s.poll(api);
        assertEquals(id,r.id); assertEquals("/test",r.environment.get("PATH_INFO")); assertEquals("/api",r.environment.get("SCRIPT_NAME"));
        assertEquals("f",r.environment.get("REMOTE_USER")); assertFalse(r.environment.containsKey("HTTP_AUTHORIZATION"));
        assertArrayEquals(new byte[]{0,-1},r.body);
        s.remove(api); assertEquals(503,replies.get(0).status); assertFalse(s.finish(id,WebServices.Response.error(200,"late")));
        replies.clear();
        for(int i=0;i<17;i++) s.submit(env("/x"),new byte[0],replies::add);
        assertEquals(1,replies.size()); assertEquals(503,replies.get(0).status);
        s.close(); assertEquals(17,replies.size()); assertFalse(s.hasRoute(all));
    }
    @Test void headerInjectionAndOversizeResponsesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WebServices.Response(200, Collections.singletonList(new String[]{"X-Test","a\r\nInjected: yes"}),new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new WebServices.Response(200, Collections.singletonList(new String[]{"Transfer-Encoding","chunked"}),new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new WebServices.Response(200, Collections.emptyList(),new byte[WebServices.RESPONSE_LIMIT+1]));
    }
    static byte[] exchange(int port, byte[] request) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000); socket.getOutputStream().write(request); socket.getOutputStream().flush();
            return socket.getInputStream().readAllBytes();
        }
    }
    static WebServices.Request poll(WebServices.Service service,String route) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end) { WebServices.Request r=service.poll(route); if(r!=null)return r; Thread.sleep(10); }
        fail("request did not reach card"); return null;
    }
    @Test void realHttpAuthenticatesAndRoundTripsBinaryBodyAndRepeatedHeaders() throws Exception {
        WebServices.Service s=WebServices.register("factory","p:word"); String route=s.open("/api");
        try(WebGateway gateway=new WebGateway("127.0.0.1",0,null,false)) {
            String unauth=new String(exchange(gateway.port(),"GET /api HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)),StandardCharsets.ISO_8859_1);
            assertTrue(unauth.startsWith("HTTP/1.1 401")); assertTrue(unauth.contains("WWW-Authenticate:")); assertNull(s.poll(route));
            String headers="POST /api/a%20b?q=x HTTP/1.1\r\nHost: localhost\r\nAuthorization: "+basic("factory:p:word")+"\r\nContent-Length: 3\r\n\r\n";
            ByteArrayOutputStream request=new ByteArrayOutputStream(); request.write(headers.getBytes(StandardCharsets.US_ASCII)); request.write(new byte[]{0,10,-1});
            CompletableFuture<byte[]> response=CompletableFuture.supplyAsync(() -> { try{return exchange(gateway.port(),request.toByteArray());} catch(Exception e){throw new CompletionException(e);} });
            WebServices.Request r=poll(s,route); assertEquals("/a b",r.environment.get("PATH_INFO")); assertEquals("q=x",r.environment.get("QUERY_STRING"));
            assertArrayEquals(new byte[]{0,10,-1},r.body);
            s.finish(r.id,new WebServices.Response(201,Arrays.asList(new String[]{"Set-Cookie","a=1"},new String[]{"Set-Cookie","b=2"}),r.body));
            byte[] data=response.get(5,TimeUnit.SECONDS); String text=new String(data,StandardCharsets.ISO_8859_1);
            assertTrue(text.startsWith("HTTP/1.1 201")); assertTrue(text.contains("Set-Cookie: a=1")); assertTrue(text.contains("Set-Cookie: b=2"));
            assertArrayEquals(r.body,Arrays.copyOfRange(data,data.length-3,data.length));
        }
    }
    @Test void uwsgiUsesTheSameAuthenticationAndRouting() throws Exception {
        WebServices.Service s=WebServices.register("factory","pwd");String route=s.open("/");
        try(WebGateway gateway=new WebGateway("127.0.0.1",0,null,true)) {
            Map<String,String> vars=new LinkedHashMap<>(); vars.put("REQUEST_METHOD","POST");vars.put("REQUEST_URI","/test");vars.put("CONTENT_LENGTH","3");vars.put("HTTP_AUTHORIZATION",basic("factory:pwd"));
            ByteArrayOutputStream header=new ByteArrayOutputStream();
            for(Map.Entry<String,String> v:vars.entrySet()) for(String str:new String[]{v.getKey(),v.getValue()}) {byte[] b=str.getBytes(StandardCharsets.ISO_8859_1);header.write(b.length&255);header.write(b.length>>8);header.write(b);}
            ByteArrayOutputStream packet=new ByteArrayOutputStream();packet.write(0);packet.write(header.size()&255);packet.write(header.size()>>8);packet.write(0);packet.write(header.toByteArray());packet.write(new byte[]{0,1,-1});
            CompletableFuture<byte[]> response=CompletableFuture.supplyAsync(() -> {try{return exchange(gateway.port(),packet.toByteArray());}catch(Exception e){throw new CompletionException(e);}});
            WebServices.Request request=poll(s,route);assertEquals("/test",request.environment.get("PATH_INFO"));assertArrayEquals(new byte[]{0,1,-1},request.body);
            s.finish(request.id,WebServices.Response.error(200,"uwsgi ok"));
            assertTrue(new String(response.get(5,TimeUnit.SECONDS),StandardCharsets.ISO_8859_1).endsWith("uwsgi ok"));
        }
    }
}
