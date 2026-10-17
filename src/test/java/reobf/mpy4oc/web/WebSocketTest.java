package reobf.mpy4oc.web;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;

class WebSocketTest {
    @AfterEach void cleanup() { WebServices.shutdown(); }
    static final class Listener implements WebSocket.Listener {
        final BlockingQueue<Object> messages=new LinkedBlockingQueue<>();
        public void onOpen(WebSocket ws) { ws.request(1); }
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {messages.add(data.toString());ws.request(1);return null;}
        public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last) {byte[] b=new byte[data.remaining()];data.get(b);messages.add(b);ws.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason) {messages.add(code);return null;}
        public void onError(WebSocket ws,Throwable error) {messages.add(error);}
    }
    @Test void handshakeAuthenticationFragmentationBinaryAndServiceShutdown() throws Exception {
        WebServices.Service service=WebServices.register("factory","pwd");String route=service.open("/events",true);
        try(WebGateway gateway=new WebGateway("127.0.0.1",0,null,false); HttpClient client=HttpClient.newHttpClient()) {
            String uri="ws://127.0.0.1:"+gateway.port()+"/events";
            assertThrows(CompletionException.class,()->client.newWebSocketBuilder().buildAsync(URI.create(uri),new Listener()).join());
            Listener listener=new Listener();
            WebSocket ws=client.newWebSocketBuilder().header("Authorization",WebGatewayTest.basic("factory:pwd")).buildAsync(URI.create(uri),listener).get(5,TimeUnit.SECONDS);
            Map<String,Object> connection=service.pollSocket(route);assertNotNull(connection);String id=(String)connection.get("id");
            ws.sendText("hello ",false).join();ws.sendText("world",true).join();
            Map<String,Object> message=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(message==null && System.nanoTime()<until) {message=service.receiveSocket(id);if(message==null)Thread.sleep(10);}
            assertNotNull(message);assertEquals("hello world",message.get("data"));
            service.sendSocket(id,"reply".getBytes(StandardCharsets.UTF_8),false);
            assertEquals("reply",listener.messages.poll(4,TimeUnit.SECONDS));
            service.sendSocket(id,new byte[]{0,-1},true);assertArrayEquals(new byte[]{0,-1},(byte[])listener.messages.poll(4,TimeUnit.SECONDS));
            service.close();assertEquals(1001,listener.messages.poll(4,TimeUnit.SECONDS));
        }
    }
    static SSLContext clientTls() throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(Path.of("src/test/resources/web-localhost.p12"))) {keys.load(in,"test-password".toCharArray());}
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(keys);
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,factory.getTrustManagers(),null);return context;
    }
    @Test void httpsAndWssShareTlsPort() throws Exception {
        WebServices.Service service=WebServices.register("tls","pwd");String route=service.open("/events",true);
        SSLContext ssl=WebGateway.ssl("src/test/resources/web-localhost.p12","test-password");
        try(WebGateway gateway=new WebGateway("127.0.0.1",0,ssl,false); HttpClient client=HttpClient.newBuilder().sslContext(clientTls()).build()) {
            HttpResponse<String> response=client.send(HttpRequest.newBuilder(URI.create("https://localhost:"+gateway.port()+"/")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,response.statusCode());
            Listener listener=new Listener();
            WebSocket socket=client.newWebSocketBuilder().header("Authorization",WebGatewayTest.basic("tls:pwd")).buildAsync(URI.create("wss://localhost:"+gateway.port()+"/events"),listener).get(5,TimeUnit.SECONDS);
            Map<String,Object> connection=service.pollSocket(route);assertNotNull(connection);
            service.sendSocket((String)connection.get("id"),"secure".getBytes(StandardCharsets.UTF_8),false);
            assertEquals("secure",listener.messages.poll(5,TimeUnit.SECONDS));socket.sendClose(1000,"done").get(5,TimeUnit.SECONDS);
        }
    }
}
