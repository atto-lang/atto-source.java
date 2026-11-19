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
import java.util.concurrent.atomic.AtomicInteger;

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
    // Reads have NO implicit timeout: idle connections are legitimate (an idle
    // subscriber, a pooled client). Idle/heartbeat timeouts are application
    // policy (process.sleep_async + Future.race; cancelling a read closes the
    // socket). Writes keep a deadline so a peer that stops reading is a slow
    // consumer that fails instead of pinning buffers forever (nats-server
    // uses the same 10 s default write_deadline).
    private static final int WRITE_TIMEOUT_SECONDS = 10;
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
            AsynchronousServerSocketChannel channel =
                    AsynchronousServerSocketChannel.open(context.vm().nioCompletionGroup());
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
        // Raw-byte and RESP2 reads are mutually exclusive for each physical socket.
        private final AtomicInteger readerMode = new AtomicInteger();
        private ByteBuffer respInput = ByteBuffer.allocate(4096);

        private Connection(OresContext context, AsynchronousSocketChannel channel) {
            this.context = context;
            this.channel = channel;
        }

        /**
         * Binary NIO read directly into a bounded byte array, exposing exactly
         * one owning, immutable Bytes handle. Clean EOF returns empty Bytes;
         * zero-byte kernel completions rearm until nonzero bytes or EOF.
         */
        public OresFuture<OresBytes> readOwnedBytesAsync(int maxBytes) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.read_owned_bytes_async");
            if (!readerMode.compareAndSet(0, 1) && readerMode.get() != 1) {
                throw new IllegalStateException("cannot mix framed RESP2 and raw reads");
            }
            if (maxBytes < 1 || maxBytes > MAX_BYTES) {
                throw new IllegalArgumentException("read owned bytes must be 1..1048576");
            }
            if (closed.get() || !reading.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException("closed socket or concurrent owned read"));
            }
            byte[] owned = new byte[maxBytes];
            ByteBuffer buffer = ByteBuffer.wrap(owned);
            return OresFuture.fromCallback(callback -> {
                CompletionHandler<Integer, Void> handler = new CompletionHandler<>() {
                    @Override public void completed(Integer count, Void ignored) {
                        if (closed.get()) {
                            reading.set(false);
                            callback.reject(new IOException("socket retired during owned read"));
                            return;
                        }
                        if (count == null || count < 0) {
                            reading.set(false);
                            callback.resolve(OresBytes.fromDetached(new byte[0]));
                            return;
                        }
                        if (count == 0) {
                            try {
                                channel.read(buffer, null, this);
                            } catch (RuntimeException failure) {
                                reading.set(false);
                                callback.reject(failure);
                            }
                            return;
                        }
                        reading.set(false);
                        // Avoid a second payload copy for full-capacity completions.
                        byte[] result = count == owned.length
                                ? owned : java.util.Arrays.copyOf(owned, count);
                        OresBytes value = OresBytes.fromDetached(result);
                        if (!callback.tryResolve(value)) value.close();
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        reading.set(false);
                        callback.reject(failure);
                    }
                };
                try {
                    channel.read(buffer, null, handler);
                } catch (RuntimeException failure) {
                    reading.set(false);
                    callback.reject(failure);
                }
            }, this::closeQuietly);
        }

        /**
         * One read lease pins the immutable backing array during an async
         * kernel write. Source Bytes may be taken/retired meanwhile; release
         * happens exactly once on completion, failure or Future cancellation.
         */
        public OresFuture<Void> writeOwnedBytesAsync(OresBytes owned) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.write_owned_bytes_async");
            Objects.requireNonNull(owned, "owned Bytes");
            if (closed.get() || !writing.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException("closed socket or concurrent owned write"));
            }
            // Function admission consumes a linear Bytes argument.  Mirror
            // that static ownership transfer in the runtime before kernel IO.
            OresBytes transferred;
            try {
                transferred = owned.take();
            } catch (RuntimeException failure) {
                writing.set(false);
                return OresFuture.failed(failure);
            }
            OresBytes.ReadLease lease = transferred.readLease();
            Runnable retire = () -> {
                lease.close();
                transferred.close();
            };
            ByteBuffer buffer = lease.buffer();
            return OresFuture.fromCallback(callback -> {
                CompletionHandler<Integer, Void> handler = new CompletionHandler<>() {
                    @Override public void completed(Integer count, Void ignored) {
                        if (closed.get() || count == null || count < 0) {
                            writing.set(false);
                            retire.run();
                            callback.reject(new IOException("socket retired during owned write"));
                            return;
                        }
                        if (buffer.hasRemaining()) {
                            try {
                                channel.write(buffer, WRITE_TIMEOUT_SECONDS,
                                        TimeUnit.SECONDS, null, this);
                            } catch (RuntimeException failure) {
                                writing.set(false);
                                retire.run();
                                callback.reject(failure);
                            }
                        } else {
                            writing.set(false);
                            retire.run();
                            callback.resolve(null);
                        }
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        writing.set(false);
                        retire.run();
                        callback.reject(failure);
                    }
                };
                try {
                    if (!buffer.hasRemaining()) {
                        writing.set(false);
                        retire.run();
                        callback.resolve(null);
                    } else {
                        channel.write(buffer, WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS, null, handler);
                    }
                } catch (RuntimeException failure) {
                    writing.set(false);
                    retire.run();
                    callback.reject(failure);
                }
            }, () -> {
                retire.run();
                closeQuietly();
            });
        }

        public OresFuture<List<Integer>> readBytesAsync(int maxBytes) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.read_bytes_async");
            if (!readerMode.compareAndSet(0, 1) && readerMode.get() != 1) {
                throw new IllegalStateException("cannot mix raw reads and RESP2 framed reads");
            }
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
                    channel.read(buffer, null,
                            new CompletionHandler<Integer, Void>() {
                        @Override public void completed(Integer count, Void ignored) {
                            if (closed.get()) {
                                reading.set(false);
                                callback.reject(new IOException("socket retired during read"));
                                return;
                            }
                            if (count == null || count < 0) {
                                reading.set(false);
                                callback.resolve(List.of()); // EOF, zero-count never indicates EOF.
                                return;
                            }
                            if (count == 0) {
                                // Keep the admission claim until a real byte/EOF arrives.
                                try {
                                    channel.read(buffer, null, this);
                                } catch (RuntimeException failure) {
                                    reading.set(false);
                                    callback.reject(failure);
                                }
                                return;
                            }
                            reading.set(false);
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
            }, this::closeQuietly);
        }

        /**
         * Async RESP2 frame reader, returning ["OP", "hex-key", "hex-value"].
         * This codec is transport-only: the Atto actor still owns the map.
         * A zero-length list means clean EOF. Incomplete EOF fails closed.
         */
        public OresFuture<List<String>> readResp2HexAsync() {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.read_resp2_hex_async");
            if (!readerMode.compareAndSet(0, 2) && readerMode.get() != 2) {
                throw new IllegalStateException("cannot mix framed RESP2 and raw socket reads");
            }
            if (closed.get() || !reading.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed socket or concurrent RESP2 reader"));
            }
            return OresFuture.fromCallback(this::continueRespRead, this::closeQuietly);
        }

        private void continueRespRead(OresFuture.Callback<List<String>> callback) {
            if (closed.get()) {
                reading.set(false);
                callback.reject(new IOException("socket retired before RESP2 read"));
                return;
            }
            respInput.flip();
            AsyncNioResp2.Parsed frame;
            try {
                frame = AsyncNioResp2.parse(respInput);
            } catch (IOException invalid) {
                reading.set(false);
                closeQuietly();
                callback.reject(invalid);
                return;
            }
            if (frame != null) {
                respInput.position(frame.consumed());
                respInput.compact(); // Preserve bytes of pipelined subsequent frames.
                reading.set(false);
                callback.resolve(frame.parts());
                return;
            }
            respInput.compact();
            if (!respInput.hasRemaining()) {
                if (respInput.capacity() >= AsyncNioResp2.MAX_FRAME) {
                    reading.set(false);
                    closeQuietly();
                    callback.reject(new IOException("RESP2 frame buffer limit exceeded"));
                    return;
                }
                ByteBuffer enlarged = ByteBuffer.allocate(Math.min(
                        AsyncNioResp2.MAX_FRAME, respInput.capacity() * 2));
                respInput.flip();
                enlarged.put(respInput);
                respInput = enlarged;
            }
            try {
                channel.read(respInput, null,
                        new CompletionHandler<Integer, Void>() {
                    @Override public void completed(Integer count, Void ignored) {
                        if (count == null || count < 0) {
                            int buffered = respInput.position();
                            reading.set(false);
                            if (buffered != 0) {
                                closeQuietly();
                                callback.reject(new IOException("truncated RESP2 frame on EOF"));
                            } else {
                                callback.resolve(List.of());
                            }
                            return;
                        }
                        // A zero-byte completion is not EOF, retry without
                        // reentrant guest code or actor scheduler blocking.
                        continueRespRead(callback);
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        reading.set(false);
                        closeQuietly();
                        callback.reject(failure);
                    }
                });
            } catch (RuntimeException failure) {
                reading.set(false);
                closeQuietly();
                callback.reject(failure);
            }
        }

        public OresFuture<Void> writeResp2HexAsync(String status, String hexPayload) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.write_resp2_hex_async");
            try {
                byte[] frame = AsyncNioResp2.encode(status, hexPayload);
                return writeDetachedAsync(frame);
            } catch (IOException failure) {
                return OresFuture.failed(failure);
            }
        }

        private void closeQuietly() {
            try { close(); } catch (IOException ignored) { }
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
            return writeDetachedAsync(detached);
        }

        private OresFuture<Void> writeDetachedAsync(byte[] detached) {
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
                            try {
                                channel.write(buffer, WRITE_TIMEOUT_SECONDS,
                                        TimeUnit.SECONDS, null, this);
                            } catch (RuntimeException failure) {
                                writing.set(false);
                                callback.reject(failure);
                            }
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
                        channel.write(buffer, WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                                null, handler);
                    }
                } catch (RuntimeException failure) {
                    writing.set(false);
                    callback.reject(failure);
                }
            }, this::closeQuietly);
        }

        public OresFuture<Void> closeAsync() {
            try {
                close();
                return OresFuture.completed(null);
            } catch (IOException failure) {
                return OresFuture.failed(failure);
            }
        }

        // ---- split halves (spec chapter 26, Async TCP v1.1) ----------------
        private final AtomicBoolean split = new AtomicBoolean();
        private final AtomicInteger liveHalves = new AtomicInteger();

        /**
         * Consume this connection into an independently owned read half and
         * write half. The socket already admits one pending read and one
         * pending write at a time, so two tasks may each own one direction.
         * The socket closes once both halves are closed.
         */
        public Halves split() {
            if (closed.get() || !split.compareAndSet(false, true)) {
                throw new IllegalStateException("closed or already split NioConnection");
            }
            liveHalves.set(2);
            return new Halves(new ReadHalf(this), new WriteHalf(this));
        }

        private void releaseHalf(boolean readSide) {
            try {
                if (readSide) channel.shutdownInput(); else channel.shutdownOutput();
            } catch (IOException | RuntimeException ignored) {
                // The peer may already be gone; the full close below still runs.
            }
            if (liveHalves.decrementAndGet() == 0) closeQuietly();
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            context.nativeIo().unregister(this);
            channel.close();
        }
    }

    /** Result of {@link Connection#split()}: guest value Tuple[NioReader, NioWriter]. */
    public record Halves(ReadHalf reader, WriteHalf writer) { }

    /** Read direction of a split connection; affine Send, never shared. */
    public static final class ReadHalf {
        private final Connection connection;
        private final AtomicBoolean released = new AtomicBoolean();
        private ReadHalf(Connection connection) { this.connection = connection; }

        private void requireLive() {
            if (released.get()) throw new IllegalStateException("closed NioReader");
        }
        public OresFuture<OresBytes> readOwnedBytesAsync(int maxBytes) {
            requireLive();
            return connection.readOwnedBytesAsync(maxBytes);
        }
        public OresFuture<List<Integer>> readBytesAsync(int maxBytes) {
            requireLive();
            return connection.readBytesAsync(maxBytes);
        }
        public OresFuture<Void> closeAsync() {
            if (released.compareAndSet(false, true)) connection.releaseHalf(true);
            return OresFuture.completed(null);
        }
    }

    /** Write direction of a split connection; affine Send, never shared. */
    public static final class WriteHalf {
        private final Connection connection;
        private final AtomicBoolean released = new AtomicBoolean();
        private WriteHalf(Connection connection) { this.connection = connection; }

        private void requireLive() {
            if (released.get()) throw new IllegalStateException("closed NioWriter");
        }
        public OresFuture<Void> writeOwnedBytesAsync(OresBytes bytes) {
            requireLive();
            return connection.writeOwnedBytesAsync(bytes);
        }
        public OresFuture<Void> writeBytesAsync(List<?> bytes) {
            requireLive();
            return connection.writeBytesAsync(bytes);
        }
        public OresFuture<Void> closeAsync() {
            if (released.compareAndSet(false, true)) connection.releaseHalf(false);
            return OresFuture.completed(null);
        }
    }
}
