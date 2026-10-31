package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-only, NET-scoped, bounded, single-accept-at-a-time loopback transport.
 * This is NOT an Oreslang guest API or an actor connection owner. Exactly one
 * host I/O worker may hold the accept loan and each connection read loan.
 */
final class WebSocketLoopbackListener implements AutoCloseable {
    private final AsyncNativeIo nativeIo;
    private final BiConsumer<String, String> requireNet;
    private final ServerSocket server;
    private final String endpoint;
    private final Set<String> allowedOrigins;
    private final WebSocketRawUpgrade.Limits limits;
    private final int maxFrameBytes;
    private final int maxMessageBytes;
    private final Semaphore connections;
    // One deadline dispatcher per bounded listener. Socket.close() interrupts a
    // blocked classic Socket output write; this is worker-bounded, NOT kernel NIO.
    private final ScheduledExecutorService writeDeadlines =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("ores-ws-write-deadline-", 0).factory());
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    // Accept publication and listener retirement share one lock. An accepted
    // Socket must either be published into this owned set or closed locally;
    // it must never be published after shutdown has snapshotted the set.
    private final Object lifecycleLock = new Object();
    private final java.util.Set<Connection> activeConnections =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private WebSocketLoopbackListener(AsyncNativeIo nativeIo,
            BiConsumer<String, String> requireNet, ServerSocket server,
            String endpoint, Set<String> allowedOrigins,
            WebSocketRawUpgrade.Limits limits, int maxConnections,
            int maxFrameBytes, int maxMessageBytes) {
        this.nativeIo = nativeIo;
        this.requireNet = requireNet;
        this.server = server;
        this.endpoint = endpoint;
        this.allowedOrigins = allowedOrigins;
        this.limits = limits;
        this.connections = new Semaphore(maxConnections);
        this.maxFrameBytes = maxFrameBytes;
        this.maxMessageBytes = maxMessageBytes;
    }

    /** Port 0 and wildcard/DNS binds deliberately unsupported in v1. */
    static WebSocketLoopbackListener listen(OresContext context, int port,
            int backlog, int maxConnections, int maxFrameBytes,
            int maxMessageBytes, Set<String> allowedOrigins,
            WebSocketRawUpgrade.Limits limits) throws IOException {
        Objects.requireNonNull(context, "OresContext");
        return bind(context.nativeIo(),
                (endpoint, action) -> context.requirePermission(
                        RuntimePermissions.Permission.NET, endpoint, action),
                port, backlog, maxConnections, maxFrameBytes,
                maxMessageBytes, allowedOrigins, limits);
    }

    // Package-private test seam: the production entrypoint supplies the
    // scoped runtime NET authorization above. It shares exactly the same bind,
    // admission, native-loan, and shutdown implementation as host tests.
    static WebSocketLoopbackListener bind(AsyncNativeIo nativeIo,
            BiConsumer<String, String> requireNet, int port,
            int backlog, int maxConnections, int maxFrameBytes,
            int maxMessageBytes, Set<String> allowedOrigins,
            WebSocketRawUpgrade.Limits limits) throws IOException {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("WebSocket listener belongs to the supervisor");
        }
        Objects.requireNonNull(nativeIo, "native I/O owner");
        Objects.requireNonNull(requireNet, "NET authority");
        Objects.requireNonNull(limits, "WebSocket handshake limits");
        if (port < 1 || port > 65535 || backlog < 1 || backlog > 128
                || maxConnections < 1 || maxConnections > 16
                || maxFrameBytes < 125 || maxFrameBytes > 64 * 1024
                || maxMessageBytes < 1 || maxMessageBytes > 64 * 1024) {
            throw new IllegalArgumentException("invalid bounded WebSocket listener configuration");
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("WebSocket listener requires explicit Origins");
        }
        Set<String> origins = Set.copyOf(allowedOrigins);
        for (String origin : origins) {
            WebSocketHandshake.requireAllowedOrigin(origin, origins);
        }
        String endpoint = "127.0.0.1:" + port;
        requireNet.accept(endpoint, "websocket.listen");
        var listener = new ServerSocket();
        try {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), port), backlog);
            nativeIo.register(listener);
            WebSocketLoopbackListener owned = new WebSocketLoopbackListener(
                    nativeIo, requireNet, listener, endpoint, origins, limits,
                    maxConnections, maxFrameBytes, maxMessageBytes);
            // Retire the *logical* owner too. When context shutdown joins
            // native workers, this callback closes every active accepted
            // connection and retires the USER queues, not only its TCP fd.
            try {
                nativeIo.register(owned);
            } catch (Exception registrationFailure) {
                if (!nativeIo.ownsResource(owned)) {
                    try { owned.close(); }
                    catch (RuntimeException cleanup) {
                        registrationFailure.addSuppressed(cleanup);
                    }
                }
                throw registrationFailure;
            }
            return owned;
        } catch (Exception failure) {
            // A shutdown race may take responsibility for closing this
            // resource, whereas a *fully retired* context rejects without
            // ownership. Probe the actual native-I/O ledger: neither blindly
            // close a borrowed resource nor leak an unowned bound listener.
            if (!nativeIo.ownsResource(listener)) {
                try { listener.close(); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException("WebSocket listener registration failed", failure);
        }
    }

    /** Admission reserves a connection slot before protocol upgrade starts. */
    OresFuture<Connection> acceptAsync() {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("WebSocket raw accept belongs to host I/O");
        }
        requireNet.accept(endpoint, "websocket.accept");
        if (closed.get() || !accepting.compareAndSet(false, true)) {
            throw new RejectedExecutionException("WebSocket listener unavailable or accept outstanding");
        }
        AtomicBoolean workStarted = new AtomicBoolean();
        OresFuture<Connection> future;
        try {
            future = nativeIo.submit("websocket.accept", () -> {
                workStarted.set(true);
                boolean handedOff = false;
                boolean admitted = false;
                try (var listening = nativeIo.borrow(server)) {
                    Socket accepted = server.accept();
                    if (!connections.tryAcquire()) {
                        accepted.close();
                        throw new RejectedExecutionException("WebSocket connection admission is full");
                    }
                    admitted = true;
                    boolean registered = false;
                    try {
                        nativeIo.register(accepted);
                        registered = true;
                        try (var loan = nativeIo.borrow(accepted)) {
                            WebSocketRawUpgrade.upgrade(
                                    accepted, endpoint, allowedOrigins, limits);
                        }
                        Connection connection = new Connection(accepted);
                        synchronized (lifecycleLock) {
                            if (closed.get()) {
                                throw new RejectedExecutionException(
                                        "listener retired before connection publication");
                            }
                            activeConnections.add(connection);
                            handedOff = true;
                        }
                        return connection;
                    } catch (Exception failure) {
                        // Only a transferred native-I/O resource may survive
                        // a concurrent close for the later physical sweep.
                        // Fully retired contexts retain no such ownership.
                        if (registered || !nativeIo.ownsResource(accepted)) {
                            try { accepted.close(); }
                            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                        }
                        if (registered) nativeIo.unregister(accepted);
                        throw failure;
                    }
                } finally {
                    accepting.set(false);
                    if (admitted && !handedOff) connections.release();
                }
            });
        } catch (RuntimeException failure) {
            accepting.set(false);
            throw failure;
        }
        if (future.isDone() && !workStarted.get()) {
            // Synchronous queue rejection started no worker. A worker-completed
            // Future may still have terminal callbacks; do not reset early.
            accepting.set(false);
        }
        return future;
    }

    /** Snapshot of physical children still owned; host diagnostics only. */
    int activeConnectionCount() {
        synchronized (lifecycleLock) { return activeConnections.size(); }
    }

    @Override public void close() {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("WebSocket listener retirement belongs to host I/O");
        }
        List<Connection> retiring;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            retiring = new ArrayList<>(activeConnections);
        }
        RuntimeException firstFailure = null;
        try {
            server.close(); // Wake the one pending accept.
        } catch (IOException failure) {
            firstFailure = new IllegalStateException(
                    "WebSocket listener close failed", failure);
        } finally {
            nativeIo.unregister(server);
        }
        // Do not hold lifecycleLock across native close callbacks. A parallel
        // connection.close() wins one retirement CAS and releases one permit.
        for (Connection connection : retiring) {
            try { connection.close(); }
            catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        writeDeadlines.shutdownNow();
        // The tracked owner stays in AsyncNativeIo until every native
        // close callback actually returns. Failed closes must be retried or
        // quarantined by the physical context-retirement sweep.
        if (firstFailure == null) nativeIo.unregister(this);
        else throw firstFailure;
    }

    /** Host-only connection: never return Socket or JVM transport state to a guest. */
    final class Connection implements AutoCloseable {
        private final Socket socket;
        private final WebSocketSessionState protocol =
                new WebSocketSessionState(16, maxMessageBytes);
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean writerActive = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final WebSocketOutboundGate outbound =
                new WebSocketOutboundGate(maxFrameBytes, 16,
                        maxFrameBytes * 16, 4, this::close);

        private Connection(Socket socket) { this.socket = socket; }

        OresFuture<WebSocketSessionState.Action> readAsync() {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket raw read belongs to host I/O");
            }
            requireNet.accept(endpoint, "websocket.read");
            if (retired.get() || !reading.compareAndSet(false, true)) {
                throw new RejectedExecutionException("WebSocket read closed or already outstanding");
            }
            AtomicBoolean workStarted = new AtomicBoolean();
            OresFuture<WebSocketSessionState.Action> future;
            try {
                future = nativeIo.submit("websocket.read", () -> {
                    workStarted.set(true);
                    try (var loan = nativeIo.borrow(socket)) {
                        // This timeout is the one-frame *absolute* budget,
                        // not a re-armed per-read idle timeout. A slow-drip
                        // masked payload cannot monopolize an I/O worker.
                        var frame = WebSocketFrameCodec.readClientFrame(
                                new WebSocketSocketDeadlineInput(
                                        socket, limits.handshakeTimeoutMillis()),
                                maxFrameBytes);
                        var action = protocol.accept(frame);
                        if (action.kind() == WebSocketSessionState.ActionKind.SEND_PONG
                                || action.kind() == WebSocketSessionState.ActionKind.SEND_CLOSE) {
                            int opcode = action.kind()
                                    == WebSocketSessionState.ActionKind.SEND_PONG
                                    ? WebSocketFrameCodec.OPCODE_PONG
                                    : WebSocketFrameCodec.OPCODE_CLOSE;
                            // Read workers never write directly. CONTROL has
                            // separate bounded admission and precedes queued
                            // USER frames in the single physical write lane.
                            outbound.enqueueControl(opcode, action.payload());
                            wakeWriter();
                        }
                        return action;
                    } catch (IOException | RuntimeException failure) {
                        close();
                        throw failure;
                    } finally {
                        reading.set(false);
                    }
                });
            } catch (RuntimeException failure) {
                reading.set(false);
                throw failure;
            }
            if (future.isDone() && !workStarted.get()) reading.set(false);
            return future;
        }

        /** Host-only bounded send. No guest actor handle is exposed yet. */
        OresFuture<Void> sendTextAsync(String text) {
            Objects.requireNonNull(text, "text");
            // Length in UTF-16 code units is a lower bound on UTF-8 output
            // bytes for valid scalars. Reject huge input before allocating.
            if (text.length() > maxFrameBytes) {
                throw new RejectedExecutionException("WebSocket outbound text exceeds frame limit");
            }
            return sendUser(WebSocketFrameCodec.OPCODE_TEXT,
                    text.getBytes(StandardCharsets.UTF_8));
        }

        OresFuture<Void> sendBinaryAsync(byte[] payload) {
            return sendUser(WebSocketFrameCodec.OPCODE_BINARY,
                    Objects.requireNonNull(payload, "payload"));
        }

        private OresFuture<Void> sendUser(int opcode, byte[] payload) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket send belongs to host I/O");
            }
            requireNet.accept(endpoint, "websocket.send");
            if (retired.get() || protocol.state() != WebSocketSessionState.State.OPEN) {
                throw new RejectedExecutionException("WebSocket is closing or retired");
            }
            WebSocketOutboundGate.Write write = outbound.enqueueUser(opcode, payload);
            wakeWriter();
            return write.receipt();
        }

        private void wakeWriter() {
            if (!writerActive.compareAndSet(false, true)) return;
            // Only one writer worker may exist per physical connection.
            // A rejected worker must close rather than retain unsent Futures.
            OresFuture<Void> launched = nativeIo.submit("websocket.write", () -> {
                drainWriter();
                return null;
            });
            if (launched.isCompletedExceptionally()) close();
        }

        private void drainWriter() {
            try {
                while (!retired.get()) {
                    WebSocketOutboundGate.Write write = outbound.poll();
                    if (write == null) return;
                    Throwable failure = null;
                    ScheduledFuture<?> deadline = null;
                    try (var loan = nativeIo.borrow(socket)) {
                        // Classic Socket output is allowed only on a host I/O
                        // worker. Absolute timeout closes its physical socket.
                        // A future SocketChannel/NIO adapter is still required.
                        deadline = writeDeadlines.schedule(this::close,
                                limits.handshakeTimeoutMillis(), TimeUnit.MILLISECONDS);
                        WebSocketFrameCodec.Frame frame = write.frame();
                        WebSocketFrameCodec.writeServerFrame(
                                socket.getOutputStream(), frame.fin(), frame.opcode(),
                                frame.payloadUnsafe(), maxFrameBytes);
                        if (retired.get()) {
                            throw new IOException("WebSocket retired during outbound write");
                        }
                    } catch (IOException | RuntimeException failureCaught) {
                        failure = failureCaught;
                    } finally {
                        if (deadline != null) deadline.cancel(false);
                    }
                    try {
                        outbound.finish(write, failure);
                    } catch (RuntimeException | Error completionFailure) {
                        close();
                        throw completionFailure;
                    }
                    if (failure != null
                            || write.frame().opcode() == WebSocketFrameCodec.OPCODE_CLOSE) {
                        close();
                        return;
                    }
                }
            } finally {
                writerActive.set(false);
                if (!retired.get() && outbound.hasPending()) wakeWriter();
            }
        }

        Optional<WebSocketFrameCodec.Message> pollUserMessage() {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket raw poll belongs to host I/O");
            }
            requireNet.accept(endpoint, "websocket.poll");
            if (retired.get()) return Optional.empty();
            return protocol.pollUserMessage();
        }

        @Override public void close() {
            if (!retired.compareAndSet(false, true)) return;
            protocol.transportClosed();
            try { socket.close(); }
            catch (IOException failure) {
                throw new IllegalStateException("WebSocket peer close failed", failure);
            } finally {
                try {
                    outbound.retire();
                } finally {
                    try { nativeIo.unregister(socket); }
                    finally {
                        synchronized (lifecycleLock) {
                            activeConnections.remove(this);
                        }
                        connections.release();
                    }
                }
            }
        }
    }
}
