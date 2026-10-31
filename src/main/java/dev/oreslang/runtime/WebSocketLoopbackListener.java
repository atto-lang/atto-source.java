package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-only, NET-scoped, bounded, single-accept-at-a-time loopback transport.
 * This is NOT an Oreslang guest API or an actor connection owner. Exactly one
 * host I/O worker may hold the accept loan and each connection read loan.
 */
final class WebSocketLoopbackListener implements AutoCloseable {
    /**
     * One daemon retirement lane for ALL loopback listeners, never one thread
     * per actor or connection. A listener owns at most 16 child sockets and
     * contributes one cancellable periodic sweep. No work is dispatched onto
     * actor/CONTROL carriers. Host I/O operations retain their own loans.
     */
    private static final ScheduledThreadPoolExecutor ACTOR_RETIREMENT =
            new ScheduledThreadPoolExecutor(1, task -> {
                Thread worker = new Thread(task, "ores-ws-actor-retirement");
                worker.setDaemon(true);
                return worker;
            });

    static {
        ACTOR_RETIREMENT.setRemoveOnCancelPolicy(true);
        ACTOR_RETIREMENT.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private volatile ScheduledFuture<?> retirementSweep;
    private final AsyncNativeIo nativeIo;
    private final BiConsumer<String, String> requireNet;
    private final ServerSocket server;
    private final String endpoint;
    private final Set<String> allowedOrigins;
    private final WebSocketRawUpgrade.Limits limits;
    private final int maxFrameBytes;
    private final int maxMessageBytes;
    private final Semaphore connections;
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    // Accept publication and listener retirement share one lock. An accepted
    // Socket must either be published into this owned set or closed locally;
    // it must never be published after shutdown has snapshotted the set.
    private final Object lifecycleLock = new Object();
    private final java.util.Set<Connection> activeConnections =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * No actor callback may physically close a socket. The finite, host-only
     * sweep inspects just this listener's existing registered children.
     */
    private void retireStoppedActorConnections() {
        // Common case is no revoked claim: scan without heap allocation.
        // Snapshot only stale children so no socket close occurs under lock.
        List<Connection> candidates = null;
        synchronized (lifecycleLock) {
            if (closed.get()) return;
            for (Connection connection : activeConnections) {
                WebSocketActorClaim claim = connection.actorClaim;
                if (connection.abortRequested.get()
                        || (claim != null && claim.revoked())) {
                    if (candidates == null) candidates = new ArrayList<>(2);
                    candidates.add(connection);
                }
            }
        }
        if (candidates == null) return;
        for (Connection connection : candidates) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // The connection's retirement CAS and finally block own
                // logical cleanup, even when the OS reports close failure.
                // Do not kill the periodic supervisor lane on one peer.
            }
        }
    }

    private void startActorRetirementSweep() {
        ScheduledFuture<?> scheduled = ACTOR_RETIREMENT.scheduleWithFixedDelay(
                this::retireStoppedActorConnections, 50, 50, TimeUnit.MILLISECONDS);
        retirementSweep = scheduled;
        if (closed.get()) scheduled.cancel(false);
    }

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
        // Accept must produce a real SocketChannel: a classic ServerSocket
        // cannot become nonblocking after an HTTP/1.1 upgrade.
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(true);
        ServerSocket listener = serverChannel.socket();
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
            // Schedule only after native-I/O has successfully registered the
            // physical listener. Startup failure closes the logical owner.
            try {
                owned.startActorRetirementSweep();
            } catch (RuntimeException schedulerFailure) {
                owned.close();
                throw schedulerFailure;
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
                        if (accepted.getChannel() == null
                                || accepted.getChannel().isBlocking()) {
                            throw new IOException(
                                    "WebSocket listener requires an upgraded nonblocking channel");
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
        ScheduledFuture<?> sweep = retirementSweep;
        if (sweep != null) sweep.cancel(false);
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
        private final AtomicBoolean closureComplete = new AtomicBoolean();
        private final AtomicBoolean abortRequested = new AtomicBoolean();
        // Set once by a host supervisor. No guest socket or actor-ref escape.
        private volatile WebSocketActorClaim actorClaim;
        private final WebSocketOutboundGate outbound =
                new WebSocketOutboundGate(maxFrameBytes, 16,
                        maxFrameBytes * 16, 4, this::requestRetirement);

        private Connection(Socket socket) { this.socket = socket; }

        /** Actor-safe, atomic logical retirement signal, no I/O locks. */
        boolean isRetired() { return retired.get() || abortRequested.get(); }

        /**
         * Host-only gate for reusing an opaque handle slot. Socket.close()
         * initiation is insufficient: both listener-permit cleanup and all
         * native resource loans must have physically retired.
         */
        boolean isFullyRetired() {
            if (ActorRuntime.inActorExecution() || ActorRuntime.isActorCarrierThread()) {
                throw new SecurityException("physical retirement proof is host-only");
            }
            return closureComplete.get() && socket.isClosed()
                    && !nativeIo.ownsResource(socket);
        }

        /**
         * Active Future cancellation can occur on an actor carrier. Request
         * abort atomically and let the shared host retirement lane perform
         * physical close, never do kernel I/O inside a Future callback.
         */
        private void requestRetirement() {
            abortRequested.set(true);
            WebSocketActorClaim claim = actorClaim;
            if (claim != null) claim.revoke();
        }

        /**
         * One-time supervisor transfer from an accepted host connection to a
         * live actor incarnation. A second claim (including a rebind after
         * actor termination) fails closed; the listener must close/reaccept.
         */
        WebSocketActorClaim claimActor(
                ActorRuntime runtime, ActorRuntime.ActorRef<?> owner) {
            if (ActorRuntime.inActorExecution() || ActorRuntime.isActorCarrierThread()) {
                throw new SecurityException("WebSocket claim requires supervisor context");
            }
            synchronized (lifecycleLock) {
                if (closed.get() || retired.get() || actorClaim != null) {
                    throw new RejectedExecutionException(
                            "WebSocket connection retired or already claimed");
                }
                WebSocketActorClaim issued = WebSocketActorClaim.issue(runtime, owner);
                if (issued.revoked()) {
                    throw new RejectedExecutionException(
                            "WebSocket actor incarnation already stopped");
                }
                actorClaim = issued;
                return issued;
            }
        }

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
                        // The upgraded socket is channel-backed and in
                        // nonblocking mode. The codec reads via one finite
                        // selector deadline; never call Socket.getInputStream
                        // after configureBlocking(false).
                        var frame = WebSocketNioDuplex.readClientFrame(
                                socket.getChannel(), maxFrameBytes,
                                limits.handshakeTimeoutMillis());
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
            WebSocketActorClaim claim = actorClaim;
            if (claim == null) {
                if (ActorRuntime.inActorExecution()) {
                    throw new SecurityException("unclaimed WebSocket send is host I/O only");
                }
            } else {
                // A claimed connection can only send during its owner's exact
                // active actor turn; host and other actors lose USER authority.
                claim.requireOwnerTurn();
            }
            requireNet.accept(endpoint, "websocket.send");
            // The claim gate and revoke share one monitor. No USER enqueue
            // can slip between checking this actor turn and revocation.
            java.util.function.Supplier<WebSocketOutboundGate.Write> enqueue = () -> {
                if (retired.get() || abortRequested.get()
                        || protocol.state() != WebSocketSessionState.State.OPEN) {
                    throw new RejectedExecutionException("WebSocket is closing or retired");
                }
                return outbound.enqueueUser(opcode, payload);
            };
            WebSocketOutboundGate.Write write =
                    claim == null ? enqueue.get() : claim.admitOwnerTurn(enqueue);
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
                while (!retired.get() && !abortRequested.get()) {
                    WebSocketOutboundGate.Write write = outbound.poll();
                    if (write == null) return;
                    Throwable failure = null;
                    try (var loan = nativeIo.borrow(socket)) {
                        // Host worker can park on OP_WRITE readiness, but the
                        // kernel write is nonblocking and every frame has an
                        // absolute monotonic deadline. No per-write helper
                        // thread may forge physical loan retirement.
                        WebSocketFrameCodec.Frame frame = write.frame();
                        WebSocketNioDuplex.writeServerFrame(
                                socket.getChannel(), frame.fin(), frame.opcode(),
                                frame.payloadUnsafe(), maxFrameBytes,
                                limits.handshakeTimeoutMillis());
                        if (retired.get() || abortRequested.get()) {
                            throw new IOException("WebSocket retired or aborted during outbound write");
                        }
                    } catch (IOException | RuntimeException failureCaught) {
                        failure = failureCaught;
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
                if (!retired.get() && !abortRequested.get()
                        && outbound.hasPending()) wakeWriter();
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
            WebSocketActorClaim claim = actorClaim;
            if (claim != null) claim.revoke();
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
                        // No permit is released twice. Native-I/O ownership
                        // may nevertheless remain until active loans exit;
                        // isFullyRetired() checks both facts before reuse.
                        closureComplete.set(true);
                    }
                }
            }
        }
    }
}
