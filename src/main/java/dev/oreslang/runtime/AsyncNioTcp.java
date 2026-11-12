package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * True NIO2 socket completions for Atto guest Future/await: no blocking
 * Socket/InputStream/OutputStream, Future.get, selector wait, or host IO thread.
 *
 * This narrow v1 exposes numeric loopback-only, raw byte vectors (0..255),
 * bounded reads/writes, a single outstanding accept/read/write per direction,
 * and context-owned resource retirement. No unauthenticated remote access.
 */
public final class AsyncNioTcp {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int IO_TIMEOUT_SECONDS = 10;
    private AsyncNioTcp() { }

    public static OresFuture<Listener> listenAsync(
            OresContext context, String host, int port) {
        Objects.requireNonNull(context);
        if (!"127.0.0.1".equals(host) || port < 1 || port > 65535) {
            throw new SecurityException("NIO2 Atto listener permits numeric IPv4 loopback only");
        }
        context.requirePermission(RuntimePermissions.Permission.NET,
                host + ":" + port, "network.listen_async");
        try {
            AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open();
            try {
                channel.bind(new InetSocketAddress(
                        InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 32);
                Listener listener = new Listener(context, channel);
                context.nativeIo().register(listener);
                return OresFuture.completed(listener);
            } catch (Exception failure) {
                channel.close();
                throw failure;
            }
        } catch (Exception failure) {
            return OresFuture.failed(failure);
        }
    }

    public static final class Listener implements AutoCloseable {
        private final OresContext context;
        private final AsynchronousServerSocketChannel channel;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean accepting = new AtomicBoolean();

        private Listener(OresContext context, AsynchronousServerSocketChannel channel) {
            this.context = context;
            this.channel = channel;
        }

        public int port() {
            try {
                return ((InetSocketAddress) channel.getLocalAddress()).getPort();
            } catch (IOException failure) {
                throw new IllegalStateException("listener closed", failure);
            }
        }

        public OresFuture<Connection> acceptAsync() {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "listener.accept_async");
            if (closed.get() || !accepting.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed listener or simultaneous accept"));
            }
            return OresFuture.fromCallback(callback -> {
                try {
                    channel.accept(null,
                            new CompletionHandler<AsynchronousSocketChannel, Void>() {
                        @Override public void completed(
                                AsynchronousSocketChannel accepted, Void ignored) {
                            accepting.set(false);
                            if (closed.get()) {
                                try { accepted.close(); } catch (IOException ignoredClose) { }
                                callback.reject(new IOException("listener retired during accept"));
                                return;
                            }
                            try {
                                accepted.setOption(StandardSocketOptions.TCP_NODELAY, true);
                                Connection connection = new Connection(context, accepted);
                                context.nativeIo().register(connection);
                                callback.resolve(connection);
                            } catch (Exception failure) {
                                try { accepted.close(); } catch (IOException ignoredClose) { }
                                callback.reject(failure);
                            }
                        }
                        @Override public void failed(Throwable failure, Void ignored) {
                            accepting.set(false);
                            callback.reject(failure);
                        }
                    });
                } catch (RuntimeException failure) {
                    accepting.set(false);
                    callback.reject(failure);
                }
            });
        }

        public OresFuture<Void> closeAsync() {
            try {
                close();
                return OresFuture.completed(null);
            } catch (IOException failure) {
                return OresFuture.failed(failure);
            }
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            context.nativeIo().unregister(this);
            channel.close();
        }
    }

    public static final class Connection implements AutoCloseable {
        private final OresContext context;
        private final AsynchronousSocketChannel channel;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean writing = new AtomicBoolean();

        private Connection(OresContext context, AsynchronousSocketChannel channel) {
            this.context = context;
            this.channel = channel;
        }

        public OresFuture<List<Integer>> readBytesAsync(int maxBytes) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.read_bytes_async");
            if (maxBytes < 1 || maxBytes > MAX_BYTES) {
                throw new IllegalArgumentException("read bytes must be 1..1048576");
            }
            if (closed.get() || !reading.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed socket or concurrent read"));
            }
            ByteBuffer buffer = ByteBuffer.allocate(maxBytes);
            return OresFuture.fromCallback(callback -> {
                try {
                    channel.read(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS, null,
                            new CompletionHandler<Integer, Void>() {
                        @Override public void completed(Integer count, Void ignored) {
                            reading.set(false);
                            if (closed.get()) {
                                callback.reject(new IOException("socket retired during read"));
                                return;
                            }
                            if (count == null || count < 0) {
                                callback.resolve(List.of()); // EOF. No zero-length read request.
                                return;
                            }
                            buffer.flip();
                            ArrayList<Integer> bytes = new ArrayList<>(count);
                            while (buffer.hasRemaining()) bytes.add(buffer.get() & 0xff);
                            callback.resolve(List.copyOf(bytes));
                        }
                        @Override public void failed(Throwable failure, Void ignored) {
                            reading.set(false);
                            callback.reject(failure);
                        }
                    });
                } catch (RuntimeException failure) {
                    reading.set(false);
                    callback.reject(failure);
                }
            });
        }

        /**
         * Immutable owned-byte read: no per-byte Integer boxing or List copy.
         * The callback transfers the completed byte[] into the frozen Bytes
         * object, never exposing its writable backing to an Atto guest.
         */
        public OresFuture<OresBytes> readOwnedBytesAsync(int maxBytes) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.read_owned_bytes_async");
            if (maxBytes < 1 || maxBytes > MAX_BYTES) {
                throw new IllegalArgumentException("read bytes must be 1..1048576");
            }
            if (closed.get() || !reading.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed socket or concurrent read"));
            }
            ByteBuffer buffer = ByteBuffer.allocate(maxBytes);
            return OresFuture.fromCallback(callback -> {
                try {
                    channel.read(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS, null,
                            new CompletionHandler<Integer, Void>() {
                        @Override public void completed(Integer count, Void ignored) {
                            reading.set(false);
                            if (closed.get()) {
                                callback.reject(new IOException("socket retired during read"));
                                return;
                            }
                            if (count == null || count < 0 || count == 0) {
                                callback.resolve(OresBytes.empty());
                                return;
                            }
                            // Read only initialized bytes; drop spare capacity.
                            byte[] frozen = count == maxBytes
                                    ? buffer.array()
                                    : java.util.Arrays.copyOf(buffer.array(), count);
                            callback.resolve(OresBytes.fromCompletedNioRead(frozen));
                        }
                        @Override public void failed(Throwable failure, Void ignored) {
                            reading.set(false);
                            callback.reject(failure);
                        }
                    });
                } catch (RuntimeException failure) {
                    reading.set(false);
                    callback.reject(failure);
                }
            });
        }

        /** Writes a frozen immutable Bytes value without converting to List<int>. */
        public OresFuture<Void> writeOwnedBytesAsync(OresBytes bytes) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.write_owned_bytes_async");
            Objects.requireNonNull(bytes, "owned bytes");
            if (closed.get() || !writing.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed socket or concurrent write"));
            }
            // Frozen backing is held strongly by this read-only view until
            // the asynchronous completion settles. No per-byte boxing,
            // byte-vector conversion or second outgoing data copy occurs.
            ByteBuffer buffer = bytes.nioReadOnlyView().asReadOnlyBuffer();
            return OresFuture.fromCallback(callback -> {
                CompletionHandler<Integer, Void> handler = new CompletionHandler<>() {
                    @Override public void completed(Integer count, Void ignored) {
                        if (closed.get() || count == null || count < 0) {
                            writing.set(false);
                            callback.reject(new IOException("socket closed during write"));
                            return;
                        }
                        if (buffer.hasRemaining()) {
                            channel.write(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                                    null, this);
                        } else {
                            writing.set(false);
                            callback.resolve(null);
                        }
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        writing.set(false);
                        callback.reject(failure);
                    }
                };
                try {
                    if (!buffer.hasRemaining()) {
                        writing.set(false);
                        callback.resolve(null);
                    } else {
                        channel.write(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                                null, handler);
                    }
                } catch (RuntimeException failure) {
                    writing.set(false);
                    callback.reject(failure);
                }
            });
        }

        public OresFuture<Void> writeBytesAsync(List<?> input) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.write_bytes_async");
            Objects.requireNonNull(input);
            if (input.size() > MAX_BYTES) {
                throw new IllegalArgumentException("write exceeds 1048576 bytes");
            }
            byte[] detached = new byte[input.size()];
            for (int i = 0; i < input.size(); i++) {
                Object value = input.get(i);
                if (!(value instanceof Number n)
                        || n.doubleValue() != n.intValue()
                        || n.intValue() < 0 || n.intValue() > 255) {
                    throw new IllegalArgumentException("write requires byte values 0..255");
                }
                detached[i] = (byte) ((Number) value).intValue();
            }
            if (closed.get() || !writing.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed socket or concurrent write"));
            }
            ByteBuffer buffer = ByteBuffer.wrap(detached);
            return OresFuture.fromCallback(callback -> {
                CompletionHandler<Integer, Void> handler = new CompletionHandler<>() {
                    @Override public void completed(Integer count, Void ignored) {
                        if (closed.get() || count == null || count < 0) {
                            writing.set(false);
                            callback.reject(new IOException("socket closed during write"));
                            return;
                        }
                        if (buffer.hasRemaining()) {
                            channel.write(buffer, IO_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS, null, this);
                        } else {
                            writing.set(false);
                            callback.resolve(null);
                        }
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        writing.set(false);
                        callback.reject(failure);
                    }
                };
                try {
                    if (!buffer.hasRemaining()) {
                        writing.set(false);
                        callback.resolve(null);
                    } else {
                        channel.write(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                                null, handler);
                    }
                } catch (RuntimeException failure) {
                    writing.set(false);
                    callback.reject(failure);
                }
            });
        }

        public OresFuture<Void> closeAsync() {
            try {
                close();
                return OresFuture.completed(null);
            } catch (IOException failure) {
                return OresFuture.failed(failure);
            }
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            context.nativeIo().unregister(this);
            channel.close();
        }
    }
}
