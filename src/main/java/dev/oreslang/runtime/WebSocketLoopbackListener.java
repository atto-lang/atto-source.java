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
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
    /**
     * A shared, fixed four-lane host pool isolates stalled closes. Its
     * notification API does not execute socket I/O on an actor carrier.
     */
    private static final WebSocketHostRetirement HOST_RETIREMENT =
            WebSocketHostRetirement.shared();

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
        future.whenCompleteRuntime((value, failure) -> {
            // Shutdown can discard a queued accept before its worker starts.
            // There is no future worker finally-block to clear admission.
            if (!workStarted.get()) accepting.set(false);
        });
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
        private final AtomicBoolean abortRequested = new AtomicBoolean();
        // Guarded by lifecycleLock. Never published directly to guest source.
        private WebSocketActorConnectionLease actorOwnership;
        // Max 16 explicit tickets. Each keeps its owner loan until actor-turn
        // delivery or scheduler cancellation; no unbounded queued handoffs.
        private int pendingActorTickets;
        // Exactly one outstanding actor-output read per physical connection.
        // Guarded by lifecycleLock. This is an obligation, not another
        // authority ledger: WebSocketActorConnectionLease still owns access.
        private OresFuture<Void> pendingSourceOutputRelay;
        private final WebSocketOutboundGate outbound =
                new WebSocketOutboundGate(maxFrameBytes, 16,
                        maxFrameBytes * 16, 4, this::requestActiveWriteAbort);

        private Connection(Socket socket) { this.socket = socket; }

        /** Host diagnostics; outstanding tickets are still actor lease owners. */
        int pendingActorTicketCount() {
            synchronized (lifecycleLock) { return pendingActorTickets; }
        }

        /**
         * Trusted host/supervisor publication only. ActorRef supplies full
         * runtime identity and incarnation; tenant provenance MUST have been
         * verified by the eventual guest bridge before calling this method.
         */
        WebSocketActorConnectionLease.Handle bindActor(
                OresContext context, ActorRuntime.ActorRef<?> actor) {
            requireContextActor(context, actor, "websocket.bind-actor");
            var handle = bindActorHost(context.contextId(),
                    actor.address(), actor::isAlive);
            watchHostActorDone(actor.done(), actor.address());
            return handle;
        }

        /**
         * A UUID and an ActorAddress are descriptions, not credentials.
         * The sole production authority is a live ActorRef proven to belong
         * to this exact OresContext's actor runtime and native-I/O owner.
         */
        private void requireContextActor(OresContext context,
                ActorRuntime.ActorRef<?> actor, String operation) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket actor bridge requires trusted host");
            }
            Objects.requireNonNull(context, "OresContext authority");
            Objects.requireNonNull(actor, "actor ref capability");
            if (context.nativeIo() != nativeIo
                    || !context.actors().isAlive(actor)) {
                throw new SecurityException("foreign context or retired actor WebSocket authority");
            }
            context.requirePermission(RuntimePermissions.Permission.NET, endpoint, operation);
        }

        /**
         * The observer's callback can run on an actor/notification carrier.
         * Never execute a physical socket close or guest turn there.
         */
        void watchHostActorDone(OresFuture<Void> done,
                ActorRuntime.ActorAddress boundOwner) {
            Objects.requireNonNull(done, "actor termination receipt");
            Objects.requireNonNull(boundOwner, "bound actor address");
            done.whenCompleteRuntime((unused, failure) -> onActorRetired(boundOwner));
        }

        private void onActorRetired(ActorRuntime.ActorAddress expectedOwner) {
            boolean claimed;
            synchronized (lifecycleLock) {
                claimed = actorOwnership != null
                        && actorOwnership.revokeIfOwner(expectedOwner);
                if (claimed) abortRequested.set(true);
            }
            if (claimed) scheduleHostClose();
        }

        private void scheduleHostClose() {
            // CONTROL is a bounded host-only pool; the completion callback
            // neither blocks an actor carrier nor settles physical loans.
            OresFuture<Void> dispatched = nativeIo.submitControl(
                    "websocket.actor-retire", () -> {
                        close();
                        return null;
                    });
            if (dispatched.isCompletedExceptionally()) {
                // A saturated CONTROL pool must not spawn one virtual thread
                // per connection or ever close a socket on an actor carrier.
                // The single host sweeper owns this pending close; duplicate
                // notifications collapse to one identity entry. Context
                // shutdown separately retains and sweeps physical loans.
                if (!retired.get()) HOST_RETIREMENT.request(this);
            }
        }

        /** Package-private host test seam; never callable from guest values. */
        WebSocketActorConnectionLease.Handle bindActorHost(
                UUID authenticatedTenant, ActorRuntime.ActorAddress owner,
                BooleanSupplier ownerAlive) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket host bind inside actor execution");
            }
            synchronized (lifecycleLock) {
                if (retired.get() || closed.get()
                        || !activeConnections.contains(this)) {
                    throw new RejectedExecutionException("WebSocket connection retired");
                }
                if (actorOwnership != null) {
                    throw new RejectedExecutionException("WebSocket connection already bound");
                }
                // Binding must begin from a physically quiescent and logically
                // drained host connection. Otherwise a pre-bind host read,
                // write or queued fragment could be inherited by the actor
                // without an actor-generation lease, bypassing the transfer
                // safety rules on first publication.
                if (abortRequested.get() || reading.get() || writerActive.get()
                        || outbound.hasPending()
                        || protocol.state() != WebSocketSessionState.State.OPEN
                        || protocol.hasPendingUserState()) {
                    throw new RejectedExecutionException(
                            "WebSocket actor bind requires drained host read, write and USER state");
                }
                var ledger = WebSocketActorConnectionLease.bindHost(
                        authenticatedTenant, owner, ownerAlive);
                // Liveness can change during issuance. Do not permanently
                // mark the connection bound if issuance itself rejects.
                var issued = ledger.issue();
                actorOwnership = ledger;
                return issued;
            }
        }

        /**
         * Trusted host only: the authenticated caller is derived from the
         * runtime ActorRef, never from a guest-provided ActorAddress. The
         * returned loan belongs to one actual I/O operation, whose physical
         * worker MUST close it only after completing its native resource loan.
         */
        WebSocketActorConnectionLease.Loan acquireActorLease(
                WebSocketActorConnectionLease.Handle handle,
                UUID authenticatedTenant, ActorRuntime.ActorAddress authenticatedCaller) {
            synchronized (lifecycleLock) {
                if (retired.get() || closed.get() || actorOwnership == null) {
                    throw new SecurityException("WebSocket actor connection unavailable");
                }
                return actorOwnership.acquire(
                        handle, authenticatedTenant, authenticatedCaller);
            }
        }

        WebSocketActorConnectionLease.Handle transferActor(
                OresContext context, WebSocketActorConnectionLease.Handle oldHandle,
                ActorRuntime.ActorRef<?> currentOwner,
                ActorRuntime.ActorRef<?> nextOwner) {
            requireContextActor(context, currentOwner, "websocket.transfer-from");
            requireContextActor(context, nextOwner, "websocket.transfer-to");
            var transferred = transferActorHost(oldHandle, context.contextId(),
                    currentOwner.address(), nextOwner.address(), nextOwner::isAlive);
            watchHostActorDone(nextOwner.done(), nextOwner.address());
            return transferred;
        }

        /** Trusted host seam; transfer requires no unretired physical loans. */
        WebSocketActorConnectionLease.Handle transferActorHost(
                WebSocketActorConnectionLease.Handle oldHandle,
                UUID authenticatedTenant, ActorRuntime.ActorAddress currentOwner,
                ActorRuntime.ActorAddress nextOwner, BooleanSupplier nextAlive) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket transfer belongs to host scheduler");
            }
            synchronized (lifecycleLock) {
                if (retired.get() || closed.get() || actorOwnership == null) {
                    throw new SecurityException("WebSocket actor connection retired");
                }
                if (abortRequested.get() || reading.get() || writerActive.get()
                        || pendingActorTickets != 0 || outbound.hasPending()
                        || protocol.state() != WebSocketSessionState.State.OPEN
                        || protocol.hasPendingUserState()) {
                    throw new RejectedExecutionException(
                            "WebSocket transfer requires drained read, write and USER state");
                }
                return actorOwnership.transferHost(oldHandle, authenticatedTenant,
                        currentOwner, nextOwner, nextAlive);
            }
        }

        /** Raw host operation is unavailable after actor ownership is bound. */
        OresFuture<WebSocketSessionState.Action> readAsync() {
            synchronized (lifecycleLock) {
                if (actorOwnership != null) {
                    throw new SecurityException("actor-owned WebSocket requires authenticated read");
                }
                return readAsyncInternal(null);
            }
        }

        /**
         * Trusted host bridge only. No guest-supplied tenant/actor address.
         * The lease remains held across queued submission and the complete
         * native socket borrow, including cancellation and close races.
         */
        OresFuture<WebSocketSessionState.Action> readForActorAsync(
                WebSocketActorConnectionLease.Handle handle, OresContext context,
                ActorRuntime.ActorRef<?> actor) {
            requireContextActor(context, actor, "websocket.read");
            return readForActorAsync(handle, context.contextId(), actor.address());
        }

        /** Package-private synthetic-address test seam, not guest authority. */
        OresFuture<WebSocketSessionState.Action> readForActorAsync(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress authenticatedCaller) {
            synchronized (lifecycleLock) {
                var ownerLoan = acquireActorLease(handle, tenant, authenticatedCaller);
                try {
                    return readAsyncInternal(ownerLoan);
                } catch (RuntimeException | Error failure) {
                    ownerLoan.close();
                    throw failure;
                }
            }
        }

        private OresFuture<WebSocketSessionState.Action> readAsyncInternal(
                WebSocketActorConnectionLease.Loan ownerLoan) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket raw read belongs to host I/O");
            }
            requireNet.accept(endpoint, "websocket.read");
            if (retired.get() || abortRequested.get()
                    || !reading.compareAndSet(false, true)) {
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
                        if (retired.get()) {
                            throw new IOException("WebSocket retired before incoming frame admission");
                        }
                        var action = protocol.accept(frame);
                        if (retired.get()) {
                            throw new IOException("WebSocket retired during incoming frame admission");
                        }
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
                        if (retired.get()
                                || (ownerLoan != null && !ownerLoan.remainsAuthorized())) {
                            throw new IOException("WebSocket owner retired before read publication");
                        }
                        return action;
                    } catch (IOException | RuntimeException failure) {
                        close();
                        throw failure;
                    } finally {
                        reading.set(false);
                        // Completion belongs to the physical I/O worker.
                        // No Future cancellation or observer can release it.
                        if (ownerLoan != null) ownerLoan.close();
                    }
                });
            } catch (RuntimeException | Error failure) {
                reading.set(false);
                if (ownerLoan != null) ownerLoan.close();
                throw failure;
            }
            future.whenCompleteRuntime((value, failure) -> {
                if (!workStarted.get()) {
                    // Native I/O discarded a queued operation before start,
                    // including during requestShutdown(). This callback is
                    // host accounting only; no actor continuation executes.
                    reading.set(false);
                    if (ownerLoan != null) ownerLoan.close();
                }
            });
            return future;
        }

        /** Host-only bounded send. No guest actor handle is exposed yet. */
        OresFuture<Void> sendTextAsync(String text) {
            Objects.requireNonNull(text, "text");
            return sendUser(WebSocketFrameCodec.OPCODE_TEXT,
                    WebSocketFrameCodec.encodeTextUtf8(text, maxFrameBytes));
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
            WebSocketOutboundGate.Write write;
            synchronized (lifecycleLock) {
                if (actorOwnership != null) {
                    throw new SecurityException("actor-owned WebSocket requires authenticated send");
                }
                if (retired.get() || abortRequested.get() || closed.get()
                        || protocol.state() != WebSocketSessionState.State.OPEN) {
                    throw new RejectedExecutionException("WebSocket is closing or retired");
                }
                write = outbound.enqueueUser(opcode, payload);
            }
            wakeWriter();
            return write.receipt();
        }

        OresFuture<Void> sendTextForActorAsync(
                WebSocketActorConnectionLease.Handle handle, OresContext context,
                ActorRuntime.ActorRef<?> actor, String text) {
            requireContextActor(context, actor, "websocket.send");
            return sendTextForActorAsync(
                    handle, context.contextId(), actor.address(), text);
        }

        /** Package-private synthetic-address test seam, not guest authority. */
        OresFuture<Void> sendTextForActorAsync(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress authenticatedCaller, String text) {
            Objects.requireNonNull(text, "text");
            return sendForActor(WebSocketFrameCodec.OPCODE_TEXT,
                    WebSocketFrameCodec.encodeTextUtf8(text, maxFrameBytes),
                    handle, tenant, authenticatedCaller);
        }

        OresFuture<Void> sendBinaryForActorAsync(
                WebSocketActorConnectionLease.Handle handle, OresContext context,
                ActorRuntime.ActorRef<?> actor, byte[] payload) {
            requireContextActor(context, actor, "websocket.send");
            return sendBinaryForActorAsync(
                    handle, context.contextId(), actor.address(), payload);
        }

        /** Package-private synthetic-address test seam, not guest authority. */
        OresFuture<Void> sendBinaryForActorAsync(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress authenticatedCaller, byte[] payload) {
            return sendForActor(WebSocketFrameCodec.OPCODE_BINARY,
                    Objects.requireNonNull(payload, "payload"),
                    handle, tenant, authenticatedCaller);
        }

        private OresFuture<Void> sendForActor(int opcode, byte[] payload,
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress authenticatedCaller) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket native send requires host bridge");
            }
            requireNet.accept(endpoint, "websocket.send");
            WebSocketOutboundGate.Write write;
            synchronized (lifecycleLock) {
                if (retired.get() || abortRequested.get() || closed.get()
                        || protocol.state() != WebSocketSessionState.State.OPEN) {
                    throw new RejectedExecutionException("WebSocket is closing or retired");
                }
                var ownerLoan = acquireActorLease(handle, tenant, authenticatedCaller);
                try {
                    write = outbound.enqueueUser(opcode, payload, ownerLoan);
                } catch (RuntimeException | Error failure) {
                    ownerLoan.close();
                    throw failure;
                }
            }
            // If the native admission is rejected, close retires all queued
            // writes and returns their actor loans; an active writer returns
            // its own loan strictly after its native SocketChannel operation.
            wakeWriter();
            return write.receipt();
        }

        private void wakeWriter() {
            if (!writerActive.compareAndSet(false, true)) return;
            // Only one writer worker may exist per physical connection.
            // A rejected worker must close rather than retain unsent Futures.
            AtomicBoolean writerStarted = new AtomicBoolean();
            OresFuture<Void> launched = nativeIo.submit("websocket.write", () -> {
                writerStarted.set(true);
                drainWriter();
                return null;
            });
            launched.whenCompleteRuntime((ignored, failure) -> {
                // The native executor can discard a queued writer on context
                // shutdown. Its drainWriter() finally never ran, so retire
                // every queued receipt/actor loan rather than stranding it.
                if (failure != null && !writerStarted.get()) close();
            });
        }

        private void drainWriter() {
            try {
                while (!retired.get()) {
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
                        if (retired.get()) {
                            throw new IOException("WebSocket retired during outbound write");
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
                if (abortRequested.get()) {
                    // Physical close runs on the native writer, never on the
                    // actor carrier that cancelled its receipt.
                    close();
                } else if (!retired.get() && outbound.hasPending()) {
                    wakeWriter();
                }
            }
        }

        /**
         * The receipt-cancellation hook can execute on an actor carrier.
         * Only logical revocation and bounded native CONTROL admission are
         * permitted here; Socket.close() belongs to a native worker.
         */
        private void requestActiveWriteAbort() {
            if (!abortRequested.compareAndSet(false, true)) return;
            synchronized (lifecycleLock) {
                if (actorOwnership != null) actorOwnership.revoke();
            }
            // The same one-shot host close path handles actor termination and
            // active outbound cancellation. A saturated CONTROL pool falls
            // back to bounded host cleanup, never an actor-carrier close.
            scheduleHostClose();
        }

        /**
         * One bounded runtime-owned delivery obligation. The scheduler must
         * close it on task cancellation or dispatch rejection. Delivery itself
         * must happen on the authenticated actor's actual mailbox turn.
         * Neither a host worker nor the guest may invent a caller address.
         */
        final class ActorDeliveryTicket implements AutoCloseable {
            private final WebSocketFrameCodec.Message message;
            private final WebSocketActorConnectionLease.Loan ownerLoan;
            private final WebSocketActorConnectionLease.Handle handle;
            private final UUID tenant;
            private final ActorRuntime.ActorAddress recipient;
            private final ActorRuntime runtime;
            private final ActorRuntime.ActorRef<?> actor;
            private final AtomicBoolean settled = new AtomicBoolean();

            private ActorDeliveryTicket(WebSocketFrameCodec.Message message,
                    WebSocketActorConnectionLease.Loan ownerLoan,
                    WebSocketActorConnectionLease.Handle handle, UUID tenant,
                    ActorRuntime.ActorAddress recipient,
                    ActorRuntime runtime, ActorRuntime.ActorRef<?> actor) {
                this.message = message;
                this.ownerLoan = ownerLoan;
                this.handle = handle;
                this.tenant = tenant;
                this.recipient = recipient;
                this.runtime = runtime;
                this.actor = actor;
            }

            boolean deliverOnActor(java.util.function.Consumer<WebSocketFrameCodec.Message> handler) {
                Objects.requireNonNull(handler, "actor mailbox handler");
                if (!settled.compareAndSet(false, true)) {
                    throw new IllegalStateException("WebSocket delivery ticket already settled");
                }
                try {
                    if (runtime == null || actor == null
                            || ActorRuntime.currentActorRuntime() != runtime
                            || !recipient.equals(ActorRuntime.currentActorAddress().orElse(null))
                            || !runtime.isAlive(actor)) {
                        throw new SecurityException(
                                "WebSocket delivery requires authenticated actor mailbox turn");
                    }
                    if (!ownerLoan.mayDeliver(handle, tenant, recipient)) return false;
                    // STOP and structured cancellation are checked by the
                    // actual actor scheduler before entering guest code.
                    // This never settles the ticket's physical I/O loan.
                    runtime.schedulerSafepoint();
                    if (!ownerLoan.mayDeliver(handle, tenant, recipient)) return false;
                    // Guest execution is outside every ledger/lifecycle lock.
                    // Actor turn ownership and STOP fences belong to ActorRuntime.
                    handler.accept(message);
                    return true;
                } finally {
                    release();
                }
            }

            @Override public void close() {
                if (settled.compareAndSet(false, true)) release();
            }

            private void release() {
                ownerLoan.close();
                synchronized (lifecycleLock) {
                    if (pendingActorTickets <= 0) {
                        throw new IllegalStateException("WebSocket ticket accounting underflow");
                    }
                    pendingActorTickets--;
                }
            }
        }

        ActorDeliveryTicket dequeueForActor(
                WebSocketActorConnectionLease.Handle handle,
                OresContext context, ActorRuntime.ActorRef<?> actor) {
            requireContextActor(context, actor, "websocket.dequeue");
            return dequeueActorTicket(handle, context.contextId(),
                    actor.address(), context.actors(), actor);
        }

        /**
         * Test-only runtime proof: unlike a caller-supplied ActorAddress, the
         * real ActorRef must be live in the selected actor scheduler. Production
         * uses the OresContext-derived bridge above.
         */
        ActorDeliveryTicket dequeueHostActorTicket(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<?> actor) {
            Objects.requireNonNull(runtime, "test actor runtime");
            Objects.requireNonNull(actor, "test ActorRef");
            if (!runtime.isAlive(actor)) {
                throw new SecurityException("foreign or stopped test actor reference");
            }
            return dequeueActorTicket(
                    handle, tenant, actor.address(), runtime, actor);
        }

        /** Test seam with no actor-turn authority: tickets cannot deliver. */
        ActorDeliveryTicket dequeueHostActorTicket(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress address) {
            return dequeueActorTicket(handle, tenant, address, null, null);
        }

        private ActorDeliveryTicket dequeueActorTicket(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress caller, ActorRuntime runtime,
                ActorRuntime.ActorRef<?> actor) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket dequeue requires host scheduler");
            }
            requireNet.accept(endpoint, "websocket.poll");
            synchronized (lifecycleLock) {
                if (retired.get() || closed.get() || pendingActorTickets >= 16) {
                    throw new RejectedExecutionException(
                            "WebSocket delivery tickets closed or quota exhausted");
                }
                var loan = acquireActorLease(handle, tenant, caller);
                try {
                    var next = protocol.pollUserMessage();
                    if (next.isEmpty()) {
                        loan.close();
                        return null;
                    }
                    var ticket = new ActorDeliveryTicket(next.orElseThrow(),
                            loan, handle, tenant, caller, runtime, actor);
                    pendingActorTickets++;
                    return ticket;
                } catch (RuntimeException | Error failure) {
                    loan.close();
                    throw failure;
                }
            }
        }

        /**
         * Trusted supervisor dispatch into the REAL actor continuation lane.
         * The mailbox owns the ticket after admission. STOP/drain and normal
         * execution both invoke its idempotent close() from the envelope.
         * On refusal, this method returns the ticket itself; no guest handler
         * can run from the submitting host I/O worker.
         */
        boolean dispatchNextActorMessage(
                WebSocketActorConnectionLease.Handle handle,
                OresContext context, ActorRuntime.ActorRef<?> actor,
                java.util.function.Consumer<WebSocketFrameCodec.Message> handler) {
            Objects.requireNonNull(handler, "actor message handler");
            requireContextActor(context, actor, "websocket.dispatch");
            return dispatchActorMessage(handle, context.contextId(),
                    context.actors(), actor, handler);
        }

        /**
         * Trusted compiler/runtime adapter, not an AttoLang guest method.
         * Accept exactly one complete validated TEXT message for a compiled
         * code actor. ActorRuntime.send() transfers the value into its normal
         * bounded user mailbox, so the source code executor's receiveActor()
         * runs on an actor turn with ActorInboxMail and actor-owned state.
         *
         * The connection ticket retains the exact actor/tenant/epoch lease
         * until the runtime has accepted the message. A refused send closes
         * the connection instead of silently dropping an admitted USER item.
         * No socket, closure or mutable host state enters the guest message.
         */
        boolean dispatchNextSourceActorText(
                WebSocketActorConnectionLease.Handle handle,
                OresContext context, ActorRuntime.ActorRef<Object> actor) {
            Objects.requireNonNull(context, "runtime context");
            requireContextActor(context, actor, "websocket.source-dispatch");
            return dispatchSourceActorText(
                    handle, context.contextId(), context.actors(), actor);
        }

        /** HOST-ONLY test seam; production must use OresContext authority. */
        boolean dispatchHostSourceActorText(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<Object> actor) {
            return dispatchSourceActorText(handle, tenant, runtime, actor);
        }

        private boolean dispatchSourceActorText(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<Object> actor) {
            Objects.requireNonNull(runtime, "source actor runtime");
            Objects.requireNonNull(actor, "source actor");
            if (ActorRuntime.inActorExecution()
                    || ActorRuntime.inActorGroupMailmanExecution()) {
                throw new SecurityException(
                        "source WebSocket delivery requires trusted host/supervisor");
            }
            if (!runtime.isSourceCodeActor(actor)) {
                throw new SecurityException(
                        "WebSocket source delivery requires live compiler-owned actor");
            }
            requireNet.accept(endpoint, "websocket.source-dispatch");
            ActorDeliveryTicket ticket = dequeueActorTicket(
                    handle, tenant, actor.address(), runtime, actor);
            if (ticket == null) return false;
            boolean transferred = false;
            try {
                if (ticket.message.opcode() != WebSocketFrameCodec.OPCODE_TEXT) {
                    throw new IllegalArgumentException(
                            "TEXT-only source actor bridge refuses binary USER mail");
                }
                if (!ticket.ownerLoan.mayDeliver(handle, tenant, actor.address())) {
                    return false;
                }
                // MessageAssembler already validates complete TEXT UTF-8.
                // Decode strictly again at this guest boundary, never replace
                // malformed bytes with the Unicode replacement character.
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(ticket.message.payload()))
                            .toString();
                } catch (java.nio.charset.CharacterCodingException invalid) {
                    throw new IllegalArgumentException(
                            "invalid complete WebSocket TEXT UTF-8", invalid);
                }
                runtime.send(actor, text); // normal bounded, owned source mailbox
                transferred = true;
                return true;
            } finally {
                // Once admitted, the actor user mailbox owns the copied value.
                // The transport's original ticket is settled exactly once.
                ticket.close();
                if (!transferred) {
                    requestActiveWriteAbort();
                }
            }
        }

        /**
         * Runtime-owned ONE-output relay for a bound compiler/source actor.
         * Its ActorOutputStream cannot be constructed by guest code. Read
         * completion only queues a bounded native-I/O worker; the actor's
         * carrier never submits an outbound socket frame or blocks on it.
         * Consumers may rearm after completion to forward the next output.
         */
        OresFuture<Void> relayNextSourceActorTextOutputAsync(
                WebSocketActorConnectionLease.Handle handle,
                OresContext context, ActorRuntime.ActorRef<Object> actor) {
            Objects.requireNonNull(context, "runtime context");
            requireContextActor(context, actor, "websocket.source-output");
            return relaySourceActorOutput(
                    handle, context.contextId(), context.actors(), actor);
        }

        /** HOST-ONLY source-executor fixture seam, not guest authority. */
        OresFuture<Void> relayHostNextSourceActorTextOutputAsync(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<Object> actor) {
            return relaySourceActorOutput(handle, tenant, runtime, actor);
        }

        private OresFuture<Void> relaySourceActorOutput(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<Object> actor) {
            Objects.requireNonNull(runtime, "source actor runtime");
            Objects.requireNonNull(actor, "source actor");
            if (ActorRuntime.inActorExecution()
                    || ActorRuntime.inActorGroupMailmanExecution()) {
                throw new SecurityException(
                        "source WebSocket output relay requires host supervisor");
            }
            if (!runtime.isSourceCodeActor(actor)) {
                throw new SecurityException(
                        "source WebSocket output relay requires live code actor");
            }
            requireNet.accept(endpoint, "websocket.source-output");
            final OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> next;
            final OresFuture<Void> relay;
            synchronized (lifecycleLock) {
                if (retired.get() || closed.get() || abortRequested.get()) {
                    throw new RejectedExecutionException("WebSocket source relay is retired");
                }
                if (pendingSourceOutputRelay != null) {
                    throw new RejectedExecutionException(
                            "only one source output reader per connection");
                }
                // Acquire/release at registration. The output's actual native
                // writer will acquire a NEW loan and revalidate epoch/owner.
                try (var loan = acquireActorLease(
                        handle, tenant, actor.address())) {
                    var stream = actor.outputs();
                    if (!stream.actorId().equals(actor.id())) {
                        throw new SecurityException("foreign actor output stream");
                    }
                    next = stream.next();
                    // The relay is host-owned and must always cancel on
                    // connection retirement. The underlying actor-channel
                    // read is revoked best-effort in the cancellation hook;
                    // its own admission decision must not prevent retiring
                    // this connection-scoped observer.
                    relay = new OresFuture<>(() -> true,
                            () -> next.cancel(false));
                    pendingSourceOutputRelay = relay;
                }
            }
            relay.whenCompleteRuntime((unused, terminal) -> {
                synchronized (lifecycleLock) {
                    if (pendingSourceOutputRelay == relay) {
                        pendingSourceOutputRelay = null;
                    }
                }
                // Once a source output was consumed, a failed/cancelled
                // relay cannot silently leave the session accepting USER
                // frames: provenance and byte-credit recovery are uncertain.
                // Revocation is logical and host-close is queued on CONTROL;
                // no actor carrier calls Socket.close().
                if (terminal != null && !retired.get()) {
                    requestActiveWriteAbort();
                }
            });
            next.whenCompleteRuntime((step, failure) -> {
                if (relay.isDone()) return;
                if (failure != null) {
                    relay.failFromRuntime(OresFuture.unwrap(failure));
                    return;
                }
                if (step == null || step.done()) {
                    relay.failFromRuntime(new IllegalStateException(
                            "source actor output stream ended before WebSocket reply"));
                    return;
                }
                ActorRuntime.ActorOutput<Object> output = step.value();
                if (output == null || !actor.id().equals(output.actorId())) {
                    relay.failFromRuntime(new SecurityException(
                            "source actor output belongs to a different actor"));
                    return;
                }
                if (!(output.value() instanceof String text)) {
                    relay.failFromRuntime(new IllegalArgumentException(
                            "TEXT WebSocket output must be a source actor String"));
                    return;
                }
                // This callback can fire on an actor carrier. Only enqueue a
                // bounded host worker here; native send rejects actor carriers.
                try {
                    OresFuture<Void> staged = nativeIo.submit(
                            "websocket.source-output-relay", () -> {
                                if (relay.isDone()) return null;
                                try {
                                    OresFuture<Void> write = sendTextForActorAsync(
                                            handle, tenant, actor.address(), text);
                                    write.whenCompleteRuntime((ignored, writeError) -> {
                                        if (writeError == null) {
                                            relay.completeFromRuntime(null);
                                        } else {
                                            relay.failFromRuntime(
                                                    OresFuture.unwrap(writeError));
                                        }
                                    });
                                } catch (RuntimeException | Error invalid) {
                                    relay.failFromRuntime(invalid);
                                }
                                return null;
                            });
                    staged.whenCompleteRuntime((ignored, stageError) -> {
                        if (stageError != null) {
                            relay.failFromRuntime(OresFuture.unwrap(stageError));
                        }
                    });
                } catch (RuntimeException | Error admissionFailure) {
                    relay.failFromRuntime(admissionFailure);
                }
            });
            return relay;
        }

        /** Host-only real-actor fixture with the same mailbox admission logic. */
        boolean dispatchHostActorMessage(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<?> actor,
                java.util.function.Consumer<WebSocketFrameCodec.Message> handler) {
            return dispatchActorMessage(handle, tenant, runtime, actor, handler);
        }

        private boolean dispatchActorMessage(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime runtime, ActorRuntime.ActorRef<?> actor,
                java.util.function.Consumer<WebSocketFrameCodec.Message> handler) {
            Objects.requireNonNull(runtime, "actor runtime");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(handler, "mailbox delivery handler");
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket dispatch must originate on host scheduler");
            }
            if (!runtime.isAlive(actor)) {
                throw new SecurityException("actor mailbox target is foreign or retired");
            }
            ActorDeliveryTicket ticket = dequeueActorTicket(handle, tenant,
                    actor.address(), runtime, actor);
            if (ticket == null) return false;
            boolean admitted = false;
            try {
                admitted = runtime.enqueueHostActorDelivery(actor,
                        () -> ticket.deliverOnActor(handler), ticket::close);
                return admitted;
            } finally {
                if (!admitted) {
                    // The message has already left the protocol's USER queue.
                    // A refused actor mailbox cannot silently drop it and
                    // continue accepting new wire frames: fail closed.
                    ticket.close();
                    if (abortRequested.compareAndSet(false, true)) {
                        synchronized (lifecycleLock) {
                            if (actorOwnership != null) actorOwnership.revoke();
                        }
                        scheduleHostClose();
                    }
                }
            }
        }

        Optional<WebSocketFrameCodec.Message> pollUserMessage() {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket raw poll belongs to host I/O");
            }
            requireNet.accept(endpoint, "websocket.poll");
            synchronized (lifecycleLock) {
                if (retired.get()) return Optional.empty();
                if (actorOwnership != null) {
                    throw new SecurityException("actor-owned WebSocket requires authenticated poll");
                }
                return protocol.pollUserMessage();
            }
        }

        /**
         * Trusted scheduler handoff only: atomically authenticate, dequeue,
         * and enqueue a USER message before allowing ownership transfer.
         * trustedEnqueue MUST be a short, bounded, nonblocking host scheduler
         * queue operation, never direct guest execution. The scheduler must
         * still revalidate the handle on eventual actor delivery.
         */
        boolean pollAndEnqueueForActor(
                WebSocketActorConnectionLease.Handle handle, OresContext context,
                ActorRuntime.ActorRef<?> actor,
                java.util.function.Consumer<WebSocketFrameCodec.Message> trustedEnqueue) {
            requireContextActor(context, actor, "websocket.poll");
            return pollAndEnqueueForActor(handle, context.contextId(),
                    actor.address(), trustedEnqueue);
        }

        /** Synthetic-address test seam; the eventual guest runtime must
         * revalidate the handle at actual actor delivery, not merely enqueue. */
        boolean pollAndEnqueueForActor(
                WebSocketActorConnectionLease.Handle handle, UUID tenant,
                ActorRuntime.ActorAddress authenticatedCaller,
                java.util.function.Consumer<WebSocketFrameCodec.Message> trustedEnqueue) {
            Objects.requireNonNull(trustedEnqueue, "trusted enqueue");
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("WebSocket poll requires host scheduler");
            }
            requireNet.accept(endpoint, "websocket.poll");
            boolean loanAcquired = false;
            try {
                synchronized (lifecycleLock) {
                    var loan = acquireActorLease(handle, tenant, authenticatedCaller);
                    loanAcquired = true;
                    try {
                        var next = protocol.pollUserMessage();
                        if (next.isEmpty()) return false;
                        // No actor guest code may execute in this callback.
                        // Failure to enqueue requires host-side retirement;
                        // never call Socket.close while holding lifecycleLock.
                        if (!loan.enqueueIfCurrent(() -> trustedEnqueue.accept(next.orElseThrow()))) {
                            throw new SecurityException("WebSocket actor retired before dispatch");
                        }
                        return true;
                    } finally {
                        loan.close();
                    }
                }
            } catch (RuntimeException | Error failure) {
                // An invalid/foreign handle is a rejected admission, not a
                // reason to close an otherwise healthy connection. Only an
                // error AFTER acquiring a valid lease retires the socket.
                if (loanAcquired) requestActiveWriteAbort();
                throw failure;
            }
        }

        @Override public void close() {
            // Serialize ledger revocation with host binding, transfer, and
            // authorization. The socket close itself must happen OUTSIDE the
            // lifecycle lock: it can wake the selector/native I/O worker.
            OresFuture<Void> relayToCancel;
            synchronized (lifecycleLock) {
                if (!retired.compareAndSet(false, true)) return;
                if (actorOwnership != null) actorOwnership.revoke();
                relayToCancel = pendingSourceOutputRelay;
                pendingSourceOutputRelay = null;
            }
            // Cancel the actor-output read OUTSIDE lifecycleLock: Future
            // observers can synchronously reenter native/actor retirement.
            if (relayToCancel != null) relayToCancel.cancel(false);
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
                        HOST_RETIREMENT.forget(this);
                    }
                }
            }
        }
    }
}
