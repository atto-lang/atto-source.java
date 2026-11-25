package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Nonblocking TCP operations driven by the VM's selector and root carriers. */
final class ReactorTcp {
    private ReactorTcp() { }

    static AsyncNioTcp.TcpListener listen(NioReadinessGroup reactor, InetSocketAddress address)
            throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        try {
            server.configureBlocking(false);
            server.bind(address, 32);
            return new Listener(reactor, server);
        } catch (IOException | RuntimeException failure) {
            try {
                server.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static final class Listener extends Endpoint implements AsyncNioTcp.TcpListener {
        private final ServerSocketChannel server;

        Listener(NioReadinessGroup reactor, ServerSocketChannel server) {
            super(reactor, server);
            this.server = server;
        }

        @Override public int port() throws IOException {
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        }

        @Override public void accept(CompletionHandler<AsyncNioTcp.TcpSocket, Void> handler) {
            runWhenReady(SelectionKey.OP_ACCEPT, 0, () -> {
                SocketChannel accepted = server.accept();
                if (accepted == null) return null;
                try {
                    accepted.configureBlocking(false);
                    accepted.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    return new Socket(reactor, accepted);
                } catch (IOException | RuntimeException failure) {
                    try {
                        accepted.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }, handler);
        }
    }

    private static final class Socket extends Endpoint implements AsyncNioTcp.TcpSocket {
        private final SocketChannel socket;

        Socket(NioReadinessGroup reactor, SocketChannel socket) {
            super(reactor, socket);
            this.socket = socket;
        }

        @Override public void read(ByteBuffer buffer, CompletionHandler<Integer, Void> handler) {
            runWhenReady(SelectionKey.OP_READ, 0, () -> {
                if (!buffer.hasRemaining()) return 0;
                int read = socket.read(buffer);
                return read == 0 ? null : read;
            }, handler);
        }

        @Override public void write(ByteBuffer buffer, CompletionHandler<Integer, Void> handler) {
            int size = buffer.remaining();
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(AsyncNioTcp.WRITE_TIMEOUT_SECONDS);
            runWhenReady(SelectionKey.OP_WRITE, deadline, () -> {
                while (buffer.hasRemaining()) {
                    if (socket.write(buffer) == 0) return null;
                }
                return size;
            }, handler);
        }

        @Override public void shutdownInput() throws IOException {
            socket.shutdownInput();
        }

        @Override public void shutdownOutput() throws IOException {
            socket.shutdownOutput();
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        /** Returns null if the nonblocking descriptor needs a readiness watch. */
        T attempt() throws IOException;
    }

    private abstract static class Endpoint implements AutoCloseable {
        final NioReadinessGroup reactor;
        private final SelectableChannel channel;
        private final Map<Integer, OresFuture<Integer>> pending = new HashMap<>();
        private boolean closed;

        Endpoint(NioReadinessGroup reactor, SelectableChannel channel) {
            this.reactor = reactor;
            this.channel = channel;
        }

        final <T> void runWhenReady(int interest, long deadline,
                Operation<T> operation, CompletionHandler<T, Void> handler) {
            final T result;
            final OresFuture<Integer> watch;
            try {
                synchronized (this) {
                    if (closed) throw new AsynchronousCloseException();
                    if (deadline != 0 && deadline - System.nanoTime() <= 0) {
                        throw new InterruptedByTimeoutException();
                    }
                    result = operation.attempt();
                    if (result == null) {
                        watch = interest == SelectionKey.OP_ACCEPT
                                ? reactor.watch(channel, interest)
                                : reactor.watchDirection(channel, interest, deadline);
                        pending.put(interest, watch);
                    } else {
                        watch = null;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                handler.failed(failure, null);
                return;
            }
            if (watch == null) {
                handler.completed(result, null);
                return;
            }
            watch.whenCompleteRuntime((ready, failure) -> {
                synchronized (this) {
                    pending.remove(interest, watch);
                }
                if (failure == null) runWhenReady(interest, deadline, operation, handler);
                else handler.failed(failure, null);
            });
        }

        @Override public final void close() throws IOException {
            List<OresFuture<Integer>> retiring;
            synchronized (this) {
                if (closed) return;
                closed = true;
                retiring = List.copyOf(pending.values());
                pending.clear();
            }
            try {
                channel.close();
            } finally {
                for (OresFuture<Integer> watch : retiring) watch.cancel(false);
            }
        }
    }
}
