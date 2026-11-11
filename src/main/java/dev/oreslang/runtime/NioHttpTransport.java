package dev.oreslang.runtime;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Selector-driven HTTP/1.1. No socket streams and no guest execution on event loops. */
final class NioHttpTransport {
    private final EventLoopGroup acceptors = new NioEventLoopGroup(1,
            Thread.ofPlatform().daemon().name("ores-http-accept-", 0).factory());
    private final EventLoopGroup events = new NioEventLoopGroup(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            Thread.ofPlatform().daemon().name("ores-http-nio-", 0).factory());
    private final Set<Channel> connections = ConcurrentHashMap.newKeySet();
    private final int bodyLimit;
    private final Consumer<Exchange> receiver;
    private final Channel listener;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean stopping;

    NioHttpTransport(String host, int port, int capacity, int bodyLimit, int deadlineMillis,
                     Consumer<Exchange> receiver) {
        this.bodyLimit = bodyLimit;
        this.receiver = receiver;
        try {
            // Startup registration only; all accepted socket reads/writes use NIO.
            listener = new ServerBootstrap().group(acceptors, events)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, capacity)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.ALLOCATOR, io.netty.buffer.UnpooledByteBufAllocator.DEFAULT)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            connections.add(channel);
                            channel.closeFuture().addListener(ignored -> connections.remove(channel));
                            channel.pipeline().addLast(new ReadTimeoutHandler(Math.max(15000, deadlineMillis), TimeUnit.MILLISECONDS));
                            channel.pipeline().addLast(new HttpServerCodec(8192, 32768, 8192));
                            channel.pipeline().addLast(new Connection());
                        }
                    }).bind(host, port).syncUninterruptibly().channel();
        } catch (RuntimeException failure) {
            acceptors.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            events.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            throw failure;
        }
    }

    int port() { return ((InetSocketAddress) listener.localAddress()).getPort(); }

    synchronized CompletionStage<Void> stop(int graceMillis) {
        if (stopping) return stopped;
        stopping = true;
        listener.close();
        // Drain sockets asynchronously; never park a CONTROL/actor carrier.
        events.next().schedule(() -> {
            for (Channel channel : connections) channel.close();
            acceptors.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            events.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).addListener(done -> {
                if (done.isSuccess()) stopped.complete(null);
                else stopped.completeExceptionally(done.cause());
            });
        }, graceMillis, TimeUnit.MILLISECONDS);
        return stopped;
    }

    private final class Connection extends ChannelInboundHandlerAdapter {
        private Exchange current;
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            try {
                if (message instanceof HttpRequest request) {
                    if (current != null || !request.decoderResult().isSuccess()) {
                        reject(ctx, 400); return;
                    }
                    try {
                        // Host ambiguity must be rejected before creating a guest exchange.
                        var hosts = request.headers().getAll(HttpHeaderNames.HOST);
                        if (request.protocolVersion().equals(HttpVersion.HTTP_1_1)
                                && (hosts.size() != 1 || hosts.get(0).isBlank()
                                    || hosts.get(0).indexOf(',') >= 0)) {
                            reject(ctx, 400); return;
                        }
                        for (var field : request.headers()) {
                            if (field.getKey().length() + field.getValue().length() > 8192) {
                                reject(ctx, 400); return;
                            }
                        }
                        long length = HttpUtil.getContentLength(request, -1);
                        if (length > bodyLimit) { reject(ctx, 413); return; }
                        current = new Exchange(ctx, request);
                        receiver.accept(current);
                    } catch (IllegalArgumentException malformed) { reject(ctx, 400); return; }
                }
                if (message instanceof HttpContent content && current != null) {
                    if (!content.decoderResult().isSuccess()) { reject(ctx, 400); return; }
                    int size = content.content().readableBytes();
                    if ((long) current.received + size > bodyLimit) {
                        current.failBody(new IllegalArgumentException("body too large"));
                        reject(ctx, 413); return;
                    }
                    byte[] bytes = new byte[size];
                    content.content().readBytes(bytes);
                    current.received += size;
                    current.chunks.add(bytes);
                    if (message instanceof LastHttpContent) {
                        current.eof = true;
                        // One active exchange per connection. Bound retained
                        // pipelined input and wait for the response's flush.
                        ctx.channel().config().setAutoRead(false);
                    }
                    current.deliverBody();
                }
            } finally { ReferenceCountUtil.release(message); }
        }
        private void reject(ChannelHandlerContext ctx, int status) {
            if (current != null) current.failBody(new IllegalStateException("HTTP connection rejected"));
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(status), Unpooled.EMPTY_BUFFER);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            if (current != null) current.failBody(new IllegalStateException("HTTP peer disconnected"));
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
            if (current != null) current.failBody(failure);
            ctx.close();
        }
    }

    final class Exchange {
        private final ChannelHandlerContext ctx;
        private final String method;
        private final URI uri;
        private final HttpHeaders requestHeaders;
        private final HttpHeaders responseHeaders = new DefaultHttpHeaders();
        private final boolean keepAlive;
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int offset, received;
        private boolean eof, started, finished, suppressBody;
        private Throwable bodyFailure;
        private CompletableFuture<byte[]> reader;
        private int maximum;
        private boolean all;
        Exchange(ChannelHandlerContext ctx, HttpRequest request) {
            this.ctx = ctx;
            method = request.method().name();
            uri = URI.create(request.uri());
            requestHeaders = request.headers().copy();
            keepAlive = HttpUtil.isKeepAlive(request);
        }
        String getRequestMethod() { return method; }
        URI getRequestURI() { return uri; }
        HttpHeaders getRequestHeaders() { return requestHeaders; }
        HttpHeaders getResponseHeaders() { return responseHeaders; }

        CompletionStage<byte[]> read(int maximum, boolean all) {
            CompletableFuture<byte[]> result = new CompletableFuture<>();
            execute(result, () -> {
                if (reader != null) throw new IllegalStateException("HTTP body read already pending");
                reader = result; this.maximum = maximum; this.all = all; deliverBody();
            });
            return result;
        }
        private void failBody(Throwable failure) { bodyFailure = failure; deliverBody(); }
        private void deliverBody() {
            if (reader == null) return;
            if (bodyFailure != null) {
                CompletableFuture<byte[]> pending = reader; reader = null;
                pending.completeExceptionally(bodyFailure); return;
            }
            int available = -offset;
            for (byte[] bytes : chunks) available += bytes.length;
            if (!eof && (all || available < maximum)) return;
            int size = Math.min(maximum, available);
            byte[] result = new byte[size]; int copied = 0;
            while (!chunks.isEmpty() && copied < size) {
                byte[] head = chunks.peek();
                int n = Math.min(size - copied, head.length - offset);
                System.arraycopy(head, offset, result, copied, n);
                offset += n; copied += n;
                if (offset == head.length) { chunks.remove(); offset = 0; }
            }
            // Empty HTTP content frames must not accumulate for chunk readers.
            while (!chunks.isEmpty() && chunks.peek().length == offset) { chunks.remove(); offset = 0; }
            CompletableFuture<byte[]> pending = reader; reader = null;
            pending.complete(result);
        }
        CompletionStage<Void> respond(int status, byte[] bytes, boolean noBody) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            execute(result, () -> {
                if (started) throw new IllegalStateException("response already started");
                started = true;
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(status), noBody ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes));
                response.headers().set(responseHeaders);
                if (status != 204 && status != 304) response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                        status == 205 ? 0 : bytes.length);
                HttpUtil.setKeepAlive(response, keepAlive);
                ctx.writeAndFlush(response).addListener(done -> completeResponse(result, done));
            });
            return result;
        }
        CompletionStage<Void> start(int status, boolean noBody) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            execute(result, () -> {
                if (started) throw new IllegalStateException("response already started");
                started = true; suppressBody = noBody;
                HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
                response.headers().set(responseHeaders);
                if (!noBody) HttpUtil.setTransferEncodingChunked(response, true);
                HttpUtil.setKeepAlive(response, keepAlive);
                ctx.writeAndFlush(response).addListener(done -> completeWrite(result, done));
            });
            return result;
        }
        CompletionStage<Void> write(byte[] bytes) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            execute(result, () -> {
                if (!started || finished) throw new IllegalStateException("response not writable");
                if (suppressBody) { result.complete(null); return; }
                ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)))
                        .addListener(done -> completeWrite(result, done));
            });
            return result;
        }
        CompletionStage<Void> finish() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            execute(result, () -> {
                if (!started || finished) throw new IllegalStateException("response not writable");
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(done -> completeResponse(result, done));
            });
            return result;
        }
        CompletionStage<Void> abort(int status) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            execute(result, () -> {
                if (!started && ctx.channel().isActive()) {
                    started = true;
                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(status), Unpooled.EMPTY_BUFFER);
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                    ctx.writeAndFlush(response).addListener(done -> ctx.close().addListener(closed -> result.complete(null)));
                } else ctx.close().addListener(done -> result.complete(null));
            });
            return result;
        }
        void close() { ctx.close(); }
        private void completeResponse(CompletableFuture<Void> result, io.netty.util.concurrent.Future<?> done) {
            finished = true;
            if (keepAlive && eof && done.isSuccess()) {
                Connection handler = ctx.pipeline().get(Connection.class);
                if (handler.current == this) handler.current = null;
                ctx.channel().config().setAutoRead(true);
            } else ctx.close();
            completeWrite(result, done);
        }
        private void completeWrite(CompletableFuture<Void> result, io.netty.util.concurrent.Future<?> done) {
            if (done.isSuccess()) result.complete(null);
            else result.completeExceptionally(done.cause());
        }
        private void execute(CompletableFuture<?> result, Runnable work) {
            try {
                ctx.executor().execute(() -> {
                    try { work.run(); }
                    catch (Throwable failure) { result.completeExceptionally(failure); }
                });
            } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        }
    }
}
