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
        // Raw-byte and RESP2 reads are mutually exclusive for each physical socket.
        private final AtomicInteger readerMode = new AtomicInteger();
        private ByteBuffer respInput = ByteBuffer.allocate(4096);

        private Connection(OresContext context, AsynchronousSocketChannel channel) {
            this.context = context;
            this.channel = channel;
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
                    channel.read(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS, null,
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
                                    channel.read(buffer, IO_TIMEOUT_SECONDS,
                                            TimeUnit.SECONDS, null, this);
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
                channel.read(respInput, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS, null,
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

        /**
         * Read one bounded RESP2 request as owned Bytes, not hex strings.
         * Tuple positions: [kind, canonicalOpcode, key, value].
         * kind 0=EOF,1=PING,2=GET,3=SET,4=DEL,5=QUIT,6=unsupported.
         * Invalid arities receive kind 6 and cannot mutate actor storage.
         * Read method modes are exclusive (raw / hex / binary).
         */
        public OresFuture<List<Object>> readResp2BinaryAsync() {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.read_resp2_binary_async");
            if (!readerMode.compareAndSet(0, 3) && readerMode.get() != 3) {
                return OresFuture.failed(new IllegalStateException(
                        "cannot mix binary RESP2, hex RESP2 and raw socket reads"));
            }
            if (closed.get() || !reading.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "closed socket or concurrent binary RESP2 reader"));
            }
            return OresFuture.fromCallback(this::continueBinaryRespRead, this::closeQuietly);
        }

        private void continueBinaryRespRead(OresFuture.Callback<List<Object>> callback) {
            if (closed.get()) {
                reading.set(false);
                callback.reject(new IOException("socket retired before binary RESP2 read"));
                return;
            }
            respInput.flip();
            AsyncNioResp2.BinaryParsed frame;
            try {
                frame = AsyncNioResp2.parseBinary(respInput);
            } catch (IOException invalid) {
                reading.set(false);
                closeQuietly();
                callback.reject(invalid);
                return;
            }
            if (frame != null) {
                respInput.position(frame.consumed());
                respInput.compact();
                reading.set(false);
                List<OresBytes> arguments = frame.arguments();
                int kind = switch (frame.opcode()) {
                    case "PING" -> arguments.isEmpty() ? 1 : 6;
                    case "GET" -> arguments.size() == 1 ? 2 : 6;
                    case "SET" -> arguments.size() == 2 ? 3 : 6;
                    case "DEL" -> arguments.size() == 1 ? 4 : 6;
                    case "QUIT" -> arguments.isEmpty() ? 5 : 6;
                    default -> 6;
                };
                OresBytes key = arguments.size() >= 1
                        ? arguments.get(0) : OresBytes.empty();
                OresBytes value = arguments.size() >= 2
                        ? arguments.get(1) : OresBytes.empty();
                callback.resolve(List.of(kind, frame.opcode(), key, value));
                return;
            }
            respInput.compact();
            if (!respInput.hasRemaining()) {
                if (respInput.capacity() >= AsyncNioResp2.MAX_FRAME) {
                    reading.set(false);
                    closeQuietly();
                    callback.reject(new IOException("binary RESP2 exceeds bounded frame"));
                    return;
                }
                ByteBuffer enlarged = ByteBuffer.allocate(Math.min(
                        AsyncNioResp2.MAX_FRAME, respInput.capacity() * 2));
                respInput.flip();
                enlarged.put(respInput);
                respInput = enlarged;
            }
            try {
                channel.read(respInput, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS, null,
                        new CompletionHandler<Integer, Void>() {
                    @Override public void completed(Integer count, Void ignored) {
                        if (count == null || count < 0) {
                            int buffered = respInput.position();
                            reading.set(false);
                            if (buffered != 0) {
                                closeQuietly();
                                callback.reject(new IOException("truncated binary RESP2 frame"));
                            } else {
                                callback.resolve(List.of(
                                        0, "", OresBytes.empty(), OresBytes.empty()));
                            }
                            return;
                        }
                        // A zero-count completion is not EOF.
                        continueBinaryRespRead(callback);
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

        /** One bounded binary RESP2 write through NIO2 completion callbacks. */
        public OresFuture<Void> writeResp2BinaryAsync(String status, OresBytes payload) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.write_resp2_binary_async");
            try {
                // Non-bulk replies never serialize a payload: caller may
                // supply a harmless placeholder from its typed tuple.
                OresBytes data = "VALUE".equals(status) ? payload : null;
                return writeDetachedAsync(AsyncNioResp2.encodeBinary(status, data));
            } catch (IOException failure) {
                return OresFuture.failed(failure);
            }
        }

        /**
         * Encode one bounded actor reply and write through the same NIO2
         * CompletionHandler. One guest actor output per GET, no blocking IO.
         */
        public OresFuture<Void> writeResp2ReplyAsync(String reply) {
            context.requireCapability(IsolatePolicy.Capability.NETWORK,
                    "socket.write_resp2_reply_async");
            try {
                return writeDetachedAsync(AsyncNioResp2.encodeCompositeReply(reply));
            } catch (IOException failure) {
                return OresFuture.failed(failure);
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
                                channel.write(buffer, IO_TIMEOUT_SECONDS,
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
                        channel.write(buffer, IO_TIMEOUT_SECONDS, TimeUnit.SECONDS,
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

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            context.nativeIo().unregister(this);
            channel.close();
        }
    }
}
