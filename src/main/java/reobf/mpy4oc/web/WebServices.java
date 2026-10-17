package reobf.mpy4oc.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe, bounded bridge. No Minecraft objects or Python calls on network threads. */
public final class WebServices {
    public static final int BODY_LIMIT = 262144, RESPONSE_LIMIT = 1048576, PENDING_LIMIT = 16;
    private static final Map<String, Service> services = new ConcurrentHashMap<>();
    private static final String epoch = UUID.randomUUID().toString();
    private static final AtomicLong sequence = new AtomicLong();
    private static String token() { return epoch + ":" + sequence.incrementAndGet(); }

    public static Service register(String id, String password) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("ID must contain 1-64 letters, digits, _, . or -");
        if (password == null || password.isEmpty() || password.length() > 256 || password.indexOf('\0') >= 0)
            throw new IllegalArgumentException("password must contain 1-256 characters");
        Service service = new Service(id, password);
        if (services.putIfAbsent(id, service) != null)
            throw new IllegalArgumentException("ID '" + id + "' is already in use");
        return service;
    }

    public static Service authenticate(String authorization) {
        if (authorization == null || authorization.length() > 2048 || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            String value = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int colon = value.indexOf(':');
            if (colon < 1) return null;
            Service s = services.get(value.substring(0, colon));
            return s != null && s.active && MessageDigest.isEqual(s.password.getBytes(StandardCharsets.UTF_8),
                    value.substring(colon + 1).getBytes(StandardCharsets.UTF_8)) ? s : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public static void shutdown() { for (Service service : new ArrayList<>(services.values())) service.close(); }

    public static final class Response {
        public final int status;
        public final List<String[]> headers;
        public final byte[] body;
        public Response(int status, List<String[]> headers, byte[] body) {
            if (status < 200 || status > 599) throw new IllegalArgumentException("invalid response status");
            if (body.length > RESPONSE_LIMIT) throw new IllegalArgumentException("response exceeds 1 MiB");
            if (headers.size() > 64) throw new IllegalArgumentException("too many response headers");
            int size = 0;
            for (String[] h : headers) {
                if (h.length != 2 || !h[0].matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || h[1].indexOf('\r') >= 0 || h[1].indexOf('\n') >= 0 || h[1].indexOf('\0') >= 0)
                    throw new IllegalArgumentException("invalid response header");
                String lower = h[0].toLowerCase(Locale.ROOT);
                if (Arrays.asList("connection", "transfer-encoding", "upgrade", "keep-alive", "trailer", "te", "proxy-authenticate", "proxy-authorization").contains(lower))
                    throw new IllegalArgumentException("hop-by-hop response header is not supported");
                size += h[0].length() + h[1].length();
            }
            if (size > 16384) throw new IllegalArgumentException("response headers too large");
            this.status = status; this.headers = headers; this.body = body;
        }
        public static Response error(int status, String message) {
            return new Response(status, Collections.singletonList(new String[]{"Content-Type", "text/plain; charset=utf-8"}), message.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class Request {
        public final String id = token();
        public final Map<String, Object> environment;
        public final byte[] body;
        final Consumer<Response> reply;
        final long deadline = System.nanoTime() + 30_000_000_000L;
        Request(Map<String,Object> env, byte[] body, Consumer<Response> reply) { this.environment = env; this.body = body; this.reply = reply; }
        public Map<String,Object> data() {
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("id", id); data.put("environ", environment); data.put("body", body); return data;
        }
    }

    public static final class Route {
        public final String id = token(), path;
        final boolean websocket;
        final ArrayDeque<Request> queue = new ArrayDeque<>();
        final ArrayDeque<SocketPeer> sockets = new ArrayDeque<>();
        Route(String path, boolean websocket) { this.path = path; this.websocket = websocket; }
    }

    public static final class SocketPeer {
        public final String id = token(), route;
        public final Map<String,Object> environment;
        private final ArrayDeque<Map<String,Object>> messages = new ArrayDeque<>();
        private final java.util.function.BiConsumer<byte[],Boolean> sender;
        private final Runnable closer;
        private boolean active = true;
        SocketPeer(String route, Map<String,Object> env, java.util.function.BiConsumer<byte[],Boolean> send, Runnable close) {
            this.route=route; environment=env; sender=send; closer=close;
        }
        public synchronized boolean incoming(byte[] bytes, boolean binary) {
            if (!active || bytes.length > 65536 || messages.size() >= 16) return false;
            Map<String,Object> event=new LinkedHashMap<>(); event.put("type","message"); event.put("data",binary ? bytes : new String(bytes,StandardCharsets.UTF_8));
            messages.add(event); return true;
        }
        public synchronized Map<String,Object> receive() { return messages.poll(); }
        public synchronized void send(byte[] bytes, boolean binary) {
            if (!active) throw new IllegalStateException("WebSocket is closed");
            if (bytes.length > 65536) throw new IllegalArgumentException("WebSocket message exceeds 64 KiB");
            sender.accept(bytes,binary);
        }
        synchronized void close() { if (active) { active=false; messages.clear(); closer.run(); } }
        Map<String,Object> data() { Map<String,Object> r=new LinkedHashMap<>(); r.put("id",id); r.put("environ",environment); return r; }
    }

    public static final class Service implements AutoCloseable {
        public final String id, password;
        public volatile boolean active = true;
        private final Map<String, Route> routes = new LinkedHashMap<>();
        private final Map<String, Request> pending = new LinkedHashMap<>();
        private final Map<String, SocketPeer> peers = new LinkedHashMap<>();
        Service(String id, String password) { this.id = id; this.password = password; }

        public synchronized String open(String path) {
            return open(path, false);
        }
        public synchronized String open(String path, boolean websocket) {
            if (!active) throw new IllegalStateException("service is stopped");
            if (path == null || !path.startsWith("/") || path.contains("?") || path.contains("#") || path.length() > 256)
                throw new IllegalArgumentException("route must be an absolute URL path");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length()-1);
            for (Route r : routes.values()) if (r.path.equals(path)) throw new IllegalArgumentException("route is already in use: " + path);
            if (routes.size() >= 16) throw new IllegalArgumentException("too many routes");
            Route route = new Route(path, websocket); routes.put(route.id, route); return route.id;
        }
        public synchronized boolean hasRoute(String token) { return active && routes.containsKey(token); }
        public synchronized void remove(String token) {
            Route route = routes.remove(token);
            if (route == null) return;
            for (Request r : new ArrayList<>(pending.values())) if (token.equals(r.environment.get("mpy.route"))) finish(r.id, Response.error(503, "service stopped"));
            for (SocketPeer peer : new ArrayList<>(peers.values())) if (peer.route.equals(token)) closeSocket(peer.id);
        }
        public synchronized String submit(Map<String,Object> env, byte[] body, Consumer<Response> reply) {
            expire();
            if (!active) { reply.accept(Response.error(503, "service stopped")); return null; }
            if (body.length > BODY_LIMIT) { reply.accept(Response.error(413, "request body exceeds 256 KiB")); return null; }
            String path = String.valueOf(env.get("PATH_INFO"));
            Route best = null;
            for (Route r : routes.values()) if ((r.path.equals("/") || path.equals(r.path) || path.startsWith(r.path + "/")) && (best == null || r.path.length() > best.path.length())) best = r;
            if (best == null) { reply.accept(Response.error(404, "no application registered for this path")); return null; }
            if (best.websocket) { reply.accept(Response.error(400, "this route requires a WebSocket upgrade")); return null; }
            if (pending.size() >= PENDING_LIMIT) { reply.accept(Response.error(503, "request queue is full")); return null; }
            Map<String,Object> safe = new LinkedHashMap<>(env);
            safe.remove("HTTP_AUTHORIZATION"); safe.remove("HTTP_PROXY_AUTHORIZATION"); safe.remove("HTTP_PROXY");
            safe.put("REMOTE_USER", id); safe.put("AUTH_TYPE", "Basic");
            safe.put("SCRIPT_NAME", best.path.equals("/") ? "" : best.path);
            safe.put("PATH_INFO", best.path.equals("/") ? path : path.substring(best.path.length()));
            safe.put("mpy.route", best.id);
            Request request = new Request(safe, body, reply);
            pending.put(request.id, request); best.queue.add(request); return request.id;
        }
        public synchronized Request poll(String token) {
            expire();
            Route r = routes.get(token);
            if (!active || r == null) throw new IllegalArgumentException("route is closed");
            while (!r.queue.isEmpty()) { Request request = r.queue.remove(); if (pending.containsKey(request.id)) return request; }
            return null;
        }
        public synchronized boolean pending(String id) { expire(); return pending.containsKey(id); }
        public synchronized boolean finish(String id, Response response) {
            Request request = pending.remove(id);
            if (request == null) return false;
            Route route = routes.get(request.environment.get("mpy.route"));
            if (route != null) route.queue.remove(request);
            request.reply.accept(response); return true;
        }
        public synchronized void cancel(String id) {
            Request r = pending.remove(id);
            if (r != null) { Route route = routes.get(r.environment.get("mpy.route")); if (route != null) route.queue.remove(r); }
        }
        public synchronized void expire() {
            long now = System.nanoTime();
            for (Request r : new ArrayList<>(pending.values())) if (now >= r.deadline) finish(r.id, Response.error(504, "application timed out"));
        }
        public synchronized void close() {
            active = false;
            services.remove(id, this);
            for (String request : new ArrayList<>(pending.keySet())) finish(request, Response.error(503, "service stopped"));
            for (String peer : new ArrayList<>(peers.keySet())) closeSocket(peer);
            routes.clear();
        }
        public synchronized SocketPeer openSocket(Map<String,Object> env, java.util.function.BiConsumer<byte[],Boolean> sender, Runnable closer) {
            if (!active || peers.size() >= 16) return null;
            String path=String.valueOf(env.get("PATH_INFO")); Route best=null;
            for(Route r:routes.values()) if((r.path.equals("/") || path.equals(r.path) || path.startsWith(r.path+"/")) && (best==null || r.path.length()>best.path.length())) best=r;
            if(best==null || !best.websocket) return null;
            Map<String,Object> safe=new LinkedHashMap<>(env);
            safe.remove("HTTP_AUTHORIZATION"); safe.remove("HTTP_PROXY_AUTHORIZATION"); safe.remove("HTTP_PROXY");
            safe.put("REMOTE_USER",id);safe.put("AUTH_TYPE","Basic");safe.put("SCRIPT_NAME",best.path.equals("/")?"":best.path);
            safe.put("PATH_INFO",best.path.equals("/")?path:path.substring(best.path.length()));
            SocketPeer peer=new SocketPeer(best.id,safe,sender,closer); peers.put(peer.id,peer);best.sockets.add(peer);return peer;
        }
        public synchronized Map<String,Object> pollSocket(String route) {
            Route r=routes.get(route);
            if(r==null || !r.websocket) throw new IllegalArgumentException("WebSocket route is closed");
            while(!r.sockets.isEmpty()) {SocketPeer p=r.sockets.remove();if(peers.containsKey(p.id))return p.data();}
            return null;
        }
        public synchronized Map<String,Object> receiveSocket(String id) {
            SocketPeer peer=peers.get(id);
            if(peer!=null)return peer.receive();
            Map<String,Object> closed=new HashMap<>();closed.put("type","close");return closed;
        }
        public synchronized void sendSocket(String id,byte[] data,boolean binary) {
            SocketPeer peer=peers.get(id);if(peer==null)throw new IllegalStateException("WebSocket is closed");peer.send(data,binary);
        }
        public synchronized void closeSocket(String id) {
            SocketPeer p=peers.remove(id);if(p!=null) {Route r=routes.get(p.route);if(r!=null)r.sockets.remove(p);p.close();}
        }
    }
}
