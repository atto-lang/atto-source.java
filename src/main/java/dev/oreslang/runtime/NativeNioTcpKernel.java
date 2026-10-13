package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * JDK NIO socket/readiness backend substrate for future Oreslang-owned std/net.
 *
 * No HTTP transport, parsing, handler dispatch, blocking stream calls or guest
 * source callbacks. The selector must run on a dedicated I/O reactor, NEVER
 * an actor or ROOT execution carrier. Not yet registered as a guest API.
 * All network channels have configureBlocking(false) before use.
 */
final class NativeNioTcpKernel implements AutoCloseable {
    static final long ACCEPT_TOKEN = 1;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private boolean closed;

    static NativeNioTcpKernel loopback(int port, int backlog) throws IOException {
        if (port < 0 || port > 65535 || backlog < 1 || backlog > 65535)
            throw new IllegalArgumentException("invalid listener limits");
        Selector selector = Selector.open();
        ServerSocketChannel listener = null;
        try {
            listener = ServerSocketChannel.open();
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), backlog);
            listener.register(selector, SelectionKey.OP_ACCEPT, ACCEPT_TOKEN);
            return new NativeNioTcpKernel(selector, listener);
        } catch (IOException | RuntimeException failure) {
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private NativeNioTcpKernel(Selector selector, ServerSocketChannel listener) {
        this.selector = selector;
        this.listener = listener;
    }

    int localPort() throws IOException {
        return ((InetSocketAddress) listener.getLocalAddress()).getPort();
    }

    // A nonblocking accept returns null when no client is ready. Never waits.
    SocketChannel tryAccept() throws IOException {
        ensureOpen();
        SocketChannel client = listener.accept();
        if (client != null) client.configureBlocking(false);
        return client;
    }

    void registerRead(SocketChannel client, long token) throws IOException {
        if (token <= ACCEPT_TOKEN) throw new IllegalArgumentException("invalid readiness token");
        ensureOpen();
        if (client.isBlocking()) throw new IllegalArgumentException("client must be nonblocking");
        client.register(selector, SelectionKey.OP_READ, token);
    }

    // Never use Socket streams or a blocking API; report zero if operation
    // cannot progress. EOF is read=-1. Buffers are caller-owned for this call.
    int tryRead(SocketChannel socket, ByteBuffer buffer) throws IOException {
        ensureOpen();
        if (socket.isBlocking()) throw new IllegalArgumentException("blocking channel forbidden");
        return socket.read(buffer);
    }
    int tryWrite(SocketChannel socket, ByteBuffer buffer) throws IOException {
        ensureOpen();
        if (socket.isBlocking()) throw new IllegalArgumentException("blocking channel forbidden");
        return socket.write(buffer);
    }

    /** Select only on a dedicated reactor thread, never an Oreslang carrier. */
    int poll(long[] tokens, int[] operations, int timeoutMillis) throws IOException {
        if (ActorRuntime.inActorExecution())
            throw new IllegalStateException("network reactor may not park actor execution");
        if (tokens == null || operations == null || tokens.length == 0
                || tokens.length != operations.length || timeoutMillis < 0)
            throw new IllegalArgumentException("invalid readiness batch/timeout");
        ensureOpen();
        if (timeoutMillis == 0) selector.selectNow();
        else selector.select(timeoutMillis);
        int count = 0;
        for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext() && count < tokens.length; ) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) continue;
            tokens[count] = (long) key.attachment();
            operations[count] = key.readyOps();
            count++;
        }
        return count;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("reactor is closed");
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { listener.close(); } finally { selector.close(); }
    }
}
