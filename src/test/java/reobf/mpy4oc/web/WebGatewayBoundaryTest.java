package reobf.mpy4oc.web;

import static org.junit.jupiter.api.Assertions.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebGatewayBoundaryTest {
    static Socket request(int port, String credentials) throws Exception {
        Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000);
        String http = "GET /ae/api/storage HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + WebGatewayTest.basic(credentials) + "\r\n\r\n";
        socket.getOutputStream().write(http.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        return socket;
    }
    static String read(Socket socket) throws Exception { return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }

    @Test void disconnectedClientReleasesQueueCapacityAndStoppingCompletesEveryWaiter() throws Exception {
        try (var service = WebServices.register("queue-test", "password"); var gateway = new WebGateway("127.0.0.1", 0, null, false)) {
            String route = service.open("/ae");
            List<Socket> sockets = new ArrayList<>(); List<WebServices.Request> requests = new ArrayList<>();
            try {
                for (int i = 0; i < WebServices.PENDING_LIMIT; i++) {
                    sockets.add(request(gateway.port(), "queue-test:password"));
                    requests.add(WebGatewayTest.poll(service, route));
                }
                try (Socket full = request(gateway.port(), "queue-test:password")) { assertTrue(read(full).startsWith("HTTP/1.1 503")); }
                sockets.get(0).close();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (service.pending(requests.get(0).id) && System.nanoTime() < end) Thread.sleep(5);
                assertFalse(service.pending(requests.get(0).id));
                assertFalse(service.finish(requests.get(0).id, WebServices.Response.error(200, "late")));
                try (Socket replacement = request(gateway.port(), "queue-test:password")) {
                    var accepted = WebGatewayTest.poll(service, route);
                    assertTrue(service.pending(accepted.id));
                    service.close();
                    assertTrue(read(replacement).startsWith("HTTP/1.1 503"));
                }
                for (int i = 1; i < sockets.size(); i++) assertTrue(read(sockets.get(i)).startsWith("HTTP/1.1 503"));
                try (Socket stopped = request(gateway.port(), "queue-test:password")) { assertTrue(read(stopped).startsWith("HTTP/1.1 401")); }
            } finally { for (Socket socket : sockets) socket.close(); }
        }
    }

    @Test void malformedUrlsOversizeBodiesAndPrefixLookalikesCannotReachTheApp() throws Exception {
        try (var service = WebServices.register("bounds-test", "password"); var gateway = new WebGateway("127.0.0.1", 0, null, false)) {
            String route = service.open("/ae");
            for (String path : new String[]{"/ae/%ZZ", "/ae/%00", "http://localhost/ae/", "/ae/#fragment"}) {
                String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + WebGatewayTest.basic("bounds-test:password") + "\r\n\r\n";
                String reply = new String(WebGatewayTest.exchange(gateway.port(), request.getBytes(StandardCharsets.US_ASCII)), StandardCharsets.US_ASCII);
                assertTrue(reply.startsWith("HTTP/1.1 400"), path + ": " + reply); assertNull(service.poll(route));
            }
            String auth = "\r\nHost: localhost\r\nAuthorization: " + WebGatewayTest.basic("bounds-test:password");
            String oversized = "POST /ae/api/storage HTTP/1.1" + auth + "\r\nContent-Length: " + (WebServices.BODY_LIMIT + 1) + "\r\nConnection: close\r\n\r\n";
            assertTrue(new String(WebGatewayTest.exchange(gateway.port(), oversized.getBytes(StandardCharsets.US_ASCII)), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 413"));
            // Without a declared length the body is refused once it passes the limit, still with 413.
            byte[] chunk = new byte[16384];
            java.io.ByteArrayOutputStream chunked = new java.io.ByteArrayOutputStream();
            chunked.write(("POST /ae/api/storage HTTP/1.1" + auth + "\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            for (int sent = 0; sent <= WebServices.BODY_LIMIT; sent += chunk.length) {
                chunked.write(("4000\r\n").getBytes(StandardCharsets.US_ASCII)); chunked.write(chunk); chunked.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            chunked.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String refused = new String(WebGatewayTest.exchange(gateway.port(), chunked.toByteArray()), StandardCharsets.US_ASCII);
            assertTrue(refused.startsWith("HTTP/1.1 413"), refused.substring(0, Math.min(80, refused.length())));
            assertNull(service.poll(route));
            String lookalike = "GET /aextra/api/storage HTTP/1.1" + auth + "\r\n\r\n";
            assertTrue(new String(WebGatewayTest.exchange(gateway.port(), lookalike.getBytes(StandardCharsets.US_ASCII)), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 404"));
            assertNull(service.poll(route));
        }
    }
}
