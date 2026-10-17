package reobf.mpy4oc.web;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/**
 * One bounded HTTP(S) listener for all cards; optionally accepts uwsgi modifier 0. It runs on the
 * Netty 4.0 that Minecraft ships, with event loops of its own.
 */
public final class WebGateway implements AutoCloseable {
    private final EventLoopGroup boss = new NioEventLoopGroup(1), workers = new NioEventLoopGroup(2);
    private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicInteger count = new AtomicInteger();
    private Channel listener;
    public final boolean tls;

    public WebGateway(String host, int port, SSLContext ssl, boolean uwsgi) throws InterruptedException {
        tls = ssl != null;
        try {
            listener = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    protected void initChannel(SocketChannel c) {
                        if (count.incrementAndGet() > 256) { count.decrementAndGet(); c.close(); return; }
                        connections.add(c); c.closeFuture().addListener(f -> count.decrementAndGet());
                        if (ssl != null) { SSLEngine engine = ssl.createSSLEngine(); engine.setUseClientMode(false); c.pipeline().addLast(new SslHandler(engine)); }
                        c.pipeline().addLast(new ReadTimeoutHandler(40));
                        c.pipeline().addLast(new Detect(uwsgi));
                    }
                }).bind(host, port).sync().channel();
        } catch (Throwable error) { close(); throw error; }
    }
    public int port() { return ((InetSocketAddress) listener.localAddress()).getPort(); }
    public void close() {
        if (listener != null) listener.close().awaitUninterruptibly();
        connections.close().awaitUninterruptibly();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
    }
    public static SSLContext ssl(String filename, String password) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(filename)) { keys.load(in, password.toCharArray()); }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS"); context.init(factory.getKeyManagers(), null, null); return context;
    }
    private final class Detect extends ByteToMessageDecoder {
        final boolean uwsgi;
        Detect(boolean enabled) { uwsgi = enabled; }
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) return;
            if (in.getUnsignedByte(in.readerIndex()) == 0 && uwsgi) {
                ctx.pipeline().addLast(new Uwsgi());
            } else {
                ctx.pipeline().addLast(new HttpServerCodec(4096, 16384, 8192));
                ctx.pipeline().addLast(new Oversize());
                ctx.pipeline().addLast(new HttpObjectAggregator(WebServices.BODY_LIMIT));
                ctx.pipeline().addLast(new Http());
            }
            ctx.pipeline().remove(this);
        }
    }
    private Map<String,Object> environment(ChannelHandlerContext ctx, String method, String uri, String host, String protocol) {
        URI target = URI.create(uri);
        if (target.isAbsolute() || target.getRawFragment() != null || target.getRawPath() == null || !target.getRawPath().startsWith("/")) throw new IllegalArgumentException("invalid request target");
        Map<String,Object> env = new LinkedHashMap<>();
        // WSGI native strings preserve URL bytes via latin-1. Applications decode UTF-8 themselves.
        String path = target.getRawPath();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i=0; i<path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%') { bytes.write(Integer.parseInt(path.substring(i+1,i+3),16)); i+=2; }
            else if (c <= 127) bytes.write(c); else throw new IllegalArgumentException("URL must be percent encoded");
        }
        String decoded = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        if (decoded.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid URL");
        env.put("REQUEST_METHOD", method); env.put("PATH_INFO", decoded);
        env.put("QUERY_STRING", target.getRawQuery() == null ? "" : target.getRawQuery());
        env.put("SERVER_PROTOCOL", protocol); env.put("SERVER_NAME", ((InetSocketAddress)ctx.channel().localAddress()).getHostString());
        env.put("SERVER_PORT", Integer.toString(port())); env.put("wsgi.url_scheme", tls ? "https" : "http");
        env.put("REMOTE_ADDR", ((InetSocketAddress)ctx.channel().remoteAddress()).getAddress().getHostAddress());
        return env;
    }
    private static WebServices.Response unauthorized() {
        return new WebServices.Response(401, Arrays.asList(new String[]{"WWW-Authenticate", "Basic realm=\"MPY4OC\", charset=\"UTF-8\""}, new String[]{"Content-Type", "text/plain"}), "Authentication required".getBytes(StandardCharsets.UTF_8));
    }
    /** One response per connection: write it, then close. */
    private static void sendHttp(ChannelHandlerContext ctx, WebServices.Response r, boolean head) {
        boolean empty = head || r.status == 204 || r.status == 304;
        FullHttpResponse reply = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(r.status), Unpooled.wrappedBuffer(empty ? new byte[0] : r.body));
        for (String[] h : r.headers) if (!h[0].equalsIgnoreCase("Content-Length")) reply.headers().add(h[0], h[1]);
        if (r.status != 204 && r.status != 304) reply.headers().set("Content-Length", r.body.length);
        reply.headers().set("Connection", "close");
        ctx.writeAndFlush(reply).addListener(ChannelFutureListener.CLOSE);
    }
    private static WebServices.Response tooLarge() { return WebServices.Response.error(413, "request body exceeds 256 KiB"); }
    /** Netty 4.0's aggregator fails only once a body arrives: refuse a declared oversize body at once. */
    private static final class Oversize extends ChannelInboundHandlerAdapter {
        boolean refused;
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (refused) { ReferenceCountUtil.release(message); return; }
            if (message instanceof HttpRequest && HttpHeaders.getContentLength((HttpRequest) message, -1) > WebServices.BODY_LIMIT) {
                refused = true;
                ReferenceCountUtil.release(message);
                sendHttp(ctx, tooLarge(), false);
                return;
            }
            ctx.fireChannelRead(message);
        }
    }
    private abstract class Exchange {
        WebServices.Service service;
        String request;
        boolean submitted;
        void submit(ChannelHandlerContext ctx, Map<String,Object> env, byte[] body, String authorization, java.util.function.Consumer<WebServices.Response> send) {
            submitted = true;
            service = WebServices.authenticate(authorization);
            if (service == null) { send.accept(unauthorized()); return; }
            request = service.submit(env, body, response -> ctx.executor().execute(() -> { if (ctx.channel().isActive()) send.accept(response); }));
            if (request != null) {
                ctx.channel().closeFuture().addListener(f -> service.cancel(request));
                ctx.executor().schedule(() -> service.expire(), 31, TimeUnit.SECONDS);
            }
        }
    }
    private final class Http extends SimpleChannelInboundHandler<FullHttpRequest> {
        final Exchange exchange = new Exchange() {};
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest message) {
            if (exchange.submitted) return; // One request per connection; Connection: close is advertised.
            try {
                if (!message.getDecoderResult().isSuccess()) throw new IllegalArgumentException("invalid HTTP request");
                Map<String,Object> env = environment(ctx, message.getMethod().name(), message.getUri(), message.headers().get("Host"), message.getProtocolVersion().text());
                for (Map.Entry<String,String> h : message.headers()) {
                    String name = h.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
                    if (!name.equals("CONTENT_TYPE") && !name.equals("CONTENT_LENGTH")) name = "HTTP_" + name;
                    env.put(name, env.containsKey(name) ? env.get(name) + "," + h.getValue() : h.getValue());
                }
                byte[] body = new byte[message.content().readableBytes()]; message.content().getBytes(message.content().readerIndex(), body);
                env.put("CONTENT_LENGTH", Integer.toString(body.length));
                if ("websocket".equalsIgnoreCase(message.headers().get("Upgrade"))) {
                    upgrade(ctx,message,env); return;
                }
                boolean head = message.getMethod().equals(HttpMethod.HEAD);
                exchange.submit(ctx, env, body, message.headers().get("Authorization"), response -> sendHttp(ctx, response, head));
            } catch (IllegalArgumentException invalid) { sendHttp(ctx, WebServices.Response.error(400, "invalid HTTP request"), false); }
        }
        private void upgrade(ChannelHandlerContext ctx,FullHttpRequest request,Map<String,Object> env) {
            exchange.submitted=true;
            WebServices.Service service=WebServices.authenticate(request.headers().get("Authorization"));
            if(service==null) {sendHttp(ctx,unauthorized(),false);return;}
            String origin=request.headers().get("Origin"), host=request.headers().get("Host");
            if(origin!=null) {
                URI parsed=URI.create(origin);
                if(host==null || !host.equalsIgnoreCase(parsed.getRawAuthority()) || !(tls?"https":"http").equals(parsed.getScheme())) {sendHttp(ctx,WebServices.Response.error(403,"WebSocket origin rejected"),false);return;}
            }
            WebSocketServerHandshaker handshaker=new WebSocketServerHandshakerFactory((tls?"wss://":"ws://")+host+request.getUri(),null,false,65536).newHandshaker(request);
            if(handshaker==null) {WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel());return;}
            AtomicInteger outgoing=new AtomicInteger();
            WebServices.SocketPeer peer=service.openSocket(env,(bytes,binary)->{
                if(outgoing.incrementAndGet()>16) {outgoing.decrementAndGet();ctx.close();throw new IllegalStateException("WebSocket send queue is full");}
                ctx.executor().execute(()->{
                    if(!ctx.channel().isActive() || !ctx.channel().isWritable()) {outgoing.decrementAndGet();ctx.close();return;}
                    ctx.writeAndFlush(binary?new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)):new TextWebSocketFrame(new String(bytes,StandardCharsets.UTF_8))).addListener(f->outgoing.decrementAndGet());
                });
            },()->ctx.executor().execute(()->ctx.writeAndFlush(new CloseWebSocketFrame(1001,"service closed")).addListener(ChannelFutureListener.CLOSE)));
            if(peer==null) {sendHttp(ctx,WebServices.Response.error(503,"WebSocket route unavailable or full"),false);return;}
            ctx.channel().closeFuture().addListener(f->service.closeSocket(peer.id));
            ctx.pipeline().remove(Oversize.class);
            handshaker.handshake(ctx.channel(),request).addListener(f->{if(!f.isSuccess())ctx.close();});
            ctx.pipeline().replace(ReadTimeoutHandler.class,"web-read-timeout",new ReadTimeoutHandler(60));
            ctx.pipeline().addBefore(ctx.name(),"web-idle",new IdleStateHandler(0,20,0));
            ctx.pipeline().addBefore(ctx.name(),"web-frame-aggregate",new WebSocketFrameAggregator(65536));
            ctx.pipeline().replace(this,"web-messages",new SimpleChannelInboundHandler<WebSocketFrame>() {
                protected void channelRead0(ChannelHandlerContext context,WebSocketFrame frame) {
                    if(frame instanceof CloseWebSocketFrame) {handshaker.close(context.channel(),((CloseWebSocketFrame)frame).retain());return;}
                    if(frame instanceof PingWebSocketFrame) {context.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));return;}
                    if(frame instanceof PongWebSocketFrame)return;
                    if(!(frame instanceof TextWebSocketFrame) && !(frame instanceof BinaryWebSocketFrame)) {context.close();return;}
                    byte[] bytes=new byte[frame.content().readableBytes()];frame.content().getBytes(frame.content().readerIndex(),bytes);
                    if(!peer.incoming(bytes,frame instanceof BinaryWebSocketFrame))context.close();
                }
                public void exceptionCaught(ChannelHandlerContext context,Throwable error) {context.close();}
                public void userEventTriggered(ChannelHandlerContext context,Object event) {if(event instanceof IdleStateEvent)context.writeAndFlush(new PingWebSocketFrame());}
            });
        }
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
            // A chunked body over the limit fails in the aggregator, after it has been read.
            if (error instanceof TooLongFrameException && !exchange.submitted && ctx.channel().isActive()) {
                exchange.submitted = true;
                sendHttp(ctx, tooLarge(), false);
            } else ctx.close();
        }
    }
    private static int shortLE(ByteBuf buffer) { return buffer.readUnsignedByte() | buffer.readUnsignedByte() << 8; }
    private static String latin1(ByteBuf buffer, int length) {
        byte[] bytes = new byte[length]; buffer.readBytes(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
    private final class Uwsgi extends ByteToMessageDecoder {
        final Exchange exchange = new Exchange() {};
        Map<String,String> vars;
        int bodyLength;
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            try {
                if (exchange.submitted) { in.skipBytes(in.readableBytes()); return; }
                if (vars == null) {
                    if (in.readableBytes() < 4) return;
                    int pos = in.readerIndex(), length = in.getUnsignedByte(pos+1) | in.getUnsignedByte(pos+2) << 8;
                    if (in.getUnsignedByte(pos) != 0 || in.getUnsignedByte(pos+3) != 0 || length > 16384) throw new IllegalArgumentException();
                    if (in.readableBytes() < length + 4) return;
                    in.skipBytes(4); ByteBuf header = in.readSlice(length); vars = new LinkedHashMap<>();
                    while (header.isReadable()) {
                        String key = latin1(header, shortLE(header));
                        String value = latin1(header, shortLE(header));
                        if (vars.put(key,value) != null) throw new IllegalArgumentException();
                    }
                    bodyLength = Integer.parseInt(vars.getOrDefault("CONTENT_LENGTH", "0"));
                    if (bodyLength < 0 || bodyLength > WebServices.BODY_LIMIT) throw new IllegalArgumentException();
                }
                if (in.readableBytes() < bodyLength) return;
                String uri = vars.get("REQUEST_URI");
                if (uri == null) throw new IllegalArgumentException();
                Map<String,Object> env = environment(ctx, vars.getOrDefault("REQUEST_METHOD", "GET"), uri, vars.get("HTTP_HOST"), "HTTP/1.1");
                for (Map.Entry<String,String> h : vars.entrySet()) if (h.getKey().startsWith("HTTP_") || h.getKey().equals("CONTENT_TYPE")) env.put(h.getKey(),h.getValue());
                env.put("CONTENT_LENGTH", Integer.toString(bodyLength));
                byte[] body = new byte[bodyLength]; in.readBytes(body);
                exchange.submit(ctx, env, body, vars.get("HTTP_AUTHORIZATION"), response -> send(ctx, response));
            } catch (RuntimeException malformed) { send(ctx, WebServices.Response.error(400, "invalid uwsgi request")); }
        }
        void send(ChannelHandlerContext ctx, WebServices.Response r) {
            boolean empty = vars != null && "HEAD".equals(vars.get("REQUEST_METHOD")) || r.status == 204 || r.status == 304;
            StringBuilder text = new StringBuilder("HTTP/1.1 ").append(r.status).append(' ').append(HttpResponseStatus.valueOf(r.status).reasonPhrase()).append("\r\n");
            for (String[] h : r.headers) if (!h[0].equalsIgnoreCase("Content-Length")) text.append(h[0]).append(": ").append(h[1]).append("\r\n");
            if (r.status != 204 && r.status != 304) text.append("Content-Length: ").append(r.body.length).append("\r\n");
            text.append("Connection: close\r\n\r\n");
            ByteBuf reply = Unpooled.buffer(); reply.writeBytes(text.toString().getBytes(StandardCharsets.ISO_8859_1)); if (!empty) reply.writeBytes(r.body);
            ctx.writeAndFlush(reply).addListener(ChannelFutureListener.CLOSE);
        }
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
    }
}
