package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP/1.1 transport. Guest code sees revocable exchange capabilities, never sockets. */
public final class NativeHttp implements AutoCloseable {
    private final OresContext context;
    private final Set<Server> servers = ConcurrentHashMap.newKeySet();
    /** Persistent serial handler processes; lifetime is owned by this HTTP runtime. */
    private final Set<Handler> handlers = ConcurrentHashMap.newKeySet();
    /** Publication and shutdown sealing share one linearization gate. */
    private final TransferPublicationGate<Exchange> transfers = new TransferPublicationGate<>();
    /** Every outstanding unclaimed transfer consumes a bounded reservation. */
    static final int MAX_UNCLAIMED_TRANSFERS_PER_SERVER = 1_024;

    /** One permit per unclaimed transfer, including expired mailbox tombstones. */
    static final class TransferBudget {
        private final int limit;
        // CAS prevents even a transient over-release from inflating capacity.
        private final java.util.concurrent.atomic.AtomicInteger outstanding =
                new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicLong rejected = new AtomicLong();

        TransferBudget(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("invalid transfer capacity");
            limit = Math.min(capacity, MAX_UNCLAIMED_TRANSFERS_PER_SERVER);
        }
        /** A one-use authority bound to precisely one admitted transfer. */
        static final class Reservation {
            private final TransferBudget owner;
            private final java.util.concurrent.atomic.AtomicBoolean released =
                    new java.util.concurrent.atomic.AtomicBoolean();
            private Reservation(TransferBudget owner) { this.owner = owner; }
            void release() {
                if (!released.compareAndSet(false, true))
                    throw new IllegalStateException("HTTP transfer reservation released more than once");
                owner.returnSlot();
            }
        }

        Reservation tryReserve() {
            for (;;) {
                int held = outstanding.get();
                if (held == limit) {
                    rejected.incrementAndGet();
                    return null;
                }
                if (outstanding.compareAndSet(held, held + 1))
                    return new Reservation(this);
            }
        }
        private void returnSlot() {
            for (;;) {
                int held = outstanding.get();
                if (held <= 0) throw new IllegalStateException(
                        "HTTP transfer accounting underflow");
                if (outstanding.compareAndSet(held, held - 1)) return;
            }
        }
        int outstanding() { return outstanding.get(); }
        int limit() { return limit; }
        long rejected() { return rejected.get(); }
    }
    /**
     * A transfer registration must be either visible to the shutdown sweep or
     * rejected. The gate monitor is never held while locking an exchange State.
     * Lookup/claim/removal remain lock-free via the concurrent backing map.
     */
    static final class TransferPublicationGate<V> {
        private final ConcurrentHashMap<String, V> entries = new ConcurrentHashMap<>();
        private boolean sealed;

        synchronized void publish(String token, V value) {
            if (sealed) throw new IllegalStateException("HTTP transfer registry closed");
            if (entries.putIfAbsent(Objects.requireNonNull(token),
                    Objects.requireNonNull(value)) != null)
                throw new IllegalStateException("duplicate HTTP transfer ticket");
        }

        synchronized List<V> sealAndSnapshot() {
            sealed = true;
            return List.copyOf(entries.values());
        }

        V get(String token) { return entries.get(token); }
        /** Claim and seal are mutually exclusive, including stale claimers. */
        synchronized boolean claimIfOpen(String token, V value) {
            return !sealed && entries.remove(token, value);
        }
        boolean remove(String token, V value) { return entries.remove(token, value); }
        int size() { return entries.size(); }
        /** Non-sealing snapshot for listener-scoped retirement. */
        List<V> snapshot() { return List.copyOf(entries.values()); }
        void clear() { entries.clear(); }
    }

    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "ores-http-deadlines"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    // Admission sealing and physical executor retirement are separate phases.
    // A failed actor close must leave already-authorized HTTP cleanup carriers
    // alive so the host can resolve the actor and retry context shutdown.
    private final Object retirementLock = new Object();
    private List<Server> retiringServers;
    private List<Handler> retiringHandlers;
    private List<Exchange> retiringTransfers;
    private boolean retired;
    private Thread retirementOwner;

    public NativeHttp(OresContext context) {
        this.context = Objects.requireNonNull(context);
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public synchronized Server listen(String host, int port, int capacity, int bodyLimit, int deadlineMillis) {
        long perf = CorePerf.start();
        if (closed) throw new IllegalStateException("HTTP runtime closed");
        if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP listeners belong to the supervisor");
        if (host == null || host.isBlank() || port < 0 || port > 65535
                || capacity < 1 || capacity > 65536 || bodyLimit < 1 || bodyLimit > 64 * 1024 * 1024
                || deadlineMillis < 1) throw new IllegalArgumentException("invalid HTTP listener limits");
        context.requirePermission(RuntimePermissions.Permission.NET, host.toLowerCase(Locale.ROOT) + ":" + port, "http.listen");
        configureTransportDefaults();
        try {
            Server server = new Server(HttpServer.create(new InetSocketAddress(host, port), capacity),
                    capacity, bodyLimit, deadlineMillis);
            servers.add(server);
            server.transport.start();
            CorePerf.end(CorePerf.HTTP_LISTEN, perf);
            if (CoreDebug.enabled()) {
                CoreDebug.event(CoreDebug.HTTP_LISTENER_READY, server.transport.getAddress().getPort());
            }
            return server;
        } catch (IOException e) { throw new IllegalStateException("HTTP listen failed", e); }
    }

    // JDK HTTP server limits are JVM-wide and read on its first initialization.
    // Embedders that initialize it earlier must configure these themselves.
    private static void configureTransportDefaults() {
        Map.of("jdk.httpserver.maxConnections", "1024",
               "sun.net.httpserver.maxReqHeaders", "100",
               "sun.net.httpserver.maxReqHeaderSize", "32768",
               "sun.net.httpserver.maxReqTime", "15",
               "sun.net.httpserver.maxRspTime", "30",
               "sun.net.httpserver.drainAmount", "1",
               "sun.net.httpserver.nodelay", "true").forEach((key, value) -> System.getProperties().putIfAbsent(key, value));
    }

    public synchronized Handler handler(ActorRuntime.ActorCodeExecutor code, String type, ActorRuntime.ActorKind kind) {
        if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP handlers are registered by the supervisor");
        if (closed) throw new IllegalStateException("HTTP runtime closed");
        if (kind == ActorRuntime.ActorKind.UNTRUSTED) throw new SecurityException("untrusted HTTP handlers are not supported");
        Handler handler = new Handler(code, type, kind);
        if (handler.serial) handlers.add(handler);
        return handler;
    }

    /** Immutable code registration; no captured guest objects enter the actor heap. */
    public final class Handler {
        private final ActorRuntime.ActorCodeExecutor code;
        private final String type;
        private final ActorRuntime.ActorKind kind;
        private final boolean serial;
        private ActorRuntime.ActorRef<Object> persistentActor;
        private Handler(ActorRuntime.ActorCodeExecutor code, String type, ActorRuntime.ActorKind kind) {
            this.code = Objects.requireNonNull(code, "immutable actor code");
            this.type = Objects.requireNonNull(type, "actor declaration");
            this.kind = Objects.requireNonNull(kind, "actor kind");
            this.serial = code.serialActorDeclaration(type);
        }
        public boolean isolated() { return kind == ActorRuntime.ActorKind.PRIVATE; }
        public boolean serial() { return serial; }

        /** Supervisor-only actor handle for mixed HTTP/message serial routes. */
        public synchronized ActorRuntime.ActorRef<Object> actor() {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("HTTP handler actor access belongs to supervisor");
            }
            if (!serial || persistentActor == null || !persistentActor.isAlive()) {
                throw new IllegalStateException("serial HTTP actor is not running");
            }
            return persistentActor;
        }

        /** Enqueue ordinary mail on the same persistent serial actor lane as HTTP. */
        public void send(String message) {
            Objects.requireNonNull(message, "serial actor mailbox message");
            actor().send(message);
        }

        /**
         * Observe exactly this actor incarnation, not whichever process the
         * handler may spawn for the next request. This is supervisor-only and
         * returns the runtime's non-cancellable termination observer.
         */
        public OresFuture<Void> done() {
            return actor().done();
        }

        public void dispatch(Exchange exchange) {
            long perf = CorePerf.start();
            if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP dispatch belongs to supervisor");
            synchronized (exchange.state) { exchange.checkOwner(); exchange.state.spawnNanos = System.nanoTime(); }
            // Includes nonserial handlers: no actor may be spawned or handed a
            // transfer after this HTTP runtime has been closed.
            if (closed) { exchange.state.abort(503); return; }
            ActorRuntime.ActorRef<Object> actor = null;
            String deliveryToken = null;
            boolean handoffPending = false;
            boolean mailSent = false;
            try {
                long spawnPerf = CorePerf.start();
                if (serial) {
                    synchronized (this) {
                        if (closed) throw new IllegalStateException("HTTP runtime closed");
                        if (persistentActor == null || !persistentActor.isAlive()) {
                            persistentActor = context.actors().spawnCodeActor(kind, code, type);
                        }
                        actor = persistentActor;
                    }
                } else {
                    actor = context.actors().spawnCodeActor(kind, code, type);
                }
                CorePerf.end(CorePerf.ACTOR_SPAWN, spawnPerf);
                // Reserve the bounded transfer slot *before* observing the
                // actor's readiness Future. A stalled actor's pending ready
                // Future otherwise retains one callback per rejected dispatch.
                // Publish the transfer and register its pending handoff
                // together under the State monitor. Actor mailbox submission
                // itself must never execute while this monitor is held.
                String token;
                synchronized (exchange.state) {
                    token = exchange.moveTo(actor, !serial);
                    deliveryToken = token;
                    exchange.state.server.pendingDeliveries.incrementAndGet();
                    handoffPending = true;
                }
                var readyObserver = actor.ready().whenCompleteRuntimeCancellable(
                        (ignored, failure) -> {
                            long spawn;
                            long ready;
                            synchronized (exchange.state) {
                                exchange.state.readyNanos = System.nanoTime();
                                spawn = exchange.state.spawnNanos;
                                ready = exchange.state.readyNanos;
                            }
                            CorePerf.elapsed(CorePerf.ACTOR_READY, spawn, ready);
                        });
                synchronized (exchange.state) {
                    exchange.state.readyObserver = readyObserver;
                }
                // A timer or shutdown may already have settled the exchange.
                exchange.state.detachReadyObserverIfTerminal();
                if (exchange.state.server.stopped)
                    throw new RejectedExecutionException("HTTP listener shut down before actor delivery");
                // Never acquire an actor mailbox/lifecycle lock with a State
                // monitor held. The pending-handoff accounting fences shutdown
                // while the mailbox accepts this tagged message.
                context.actors().sendHttpTransfer(actor, token);
                mailSent = true;
            } catch (RuntimeException failure) {
                synchronized (exchange.state) {
                    exchange.state.revokeTransferLocked();
                }
                if (actor != null && !serial) actor.cancel();
                exchange.state.abort(failure instanceof RejectedExecutionException ? 503 : 500);
            } finally {
                if (handoffPending) {
                    Server server = exchange.state.server;
                    try {
                        if (server.stopped) {
                            // A queued token can be retired safely; an already
                            // delivered token remains a tombstone for actor-side
                            // stale-mail suppression.
                            exchange.state.abort(503);
                            if (mailSent && actor != null
                                    && context.actors().retireQueuedHttpTransfer(actor, deliveryToken)) {
                                synchronized (exchange.state) {
                                    if (deliveryToken.equals(exchange.state.ticket))
                                        exchange.state.revokeTransferLocked();
                                }
                                exchange.state.detachActorDoneObserverIfReleased();
                            }
                        }
                    } finally {
                        // A failed cleanup callback may not strand drain.
                        server.pendingDeliveries.decrementAndGet();
                        server.completeDrain();
                    }
                }
                CorePerf.end(CorePerf.HTTP_DISPATCH, perf);
            }
        }
    }

    /**
     * Trusted bridge used on an admitted serial actor turn before guest code
     * claims its moved HTTP transfer. No guest method can fabricate authority:
     * transfer ticket, actor identity, and persistent-actor flag must agree.
     */
    public boolean consumeAbortedSerialRequest(
            ActorRuntime.ActorId actorId, Object message) {
        if (!(message instanceof String token)) return false;
        Exchange exchange = transfers.get(token);
        if (exchange == null) return false;
        synchronized (exchange.state) {
            State state = exchange.state;
            if (!state.persistentActor || state.actor == null
                    || !state.actor.id().equals(actorId)
                    || !actorId.equals(state.owner)
                    || !Objects.equals(state.ticket, token)
                    || !state.terminal) return false;
            // A deadline may expire before the queued mail is processed.
            // Consume its tombstone instead of killing the persistent actor
            // with an invalid http.claim(token) call.
            state.revokeTransferLocked();
        }
        // A timed-out transfer can survive after the HTTP admission was
        // released. Retire its actor-done waiter once the mailbox tombstone
        // is consumed, rather than pinning this exchange until actor stop.
        exchange.state.detachActorDoneObserverIfReleased();
        return true;
    }

    public OresFuture<?> serialRequestCompletion(
            ActorRuntime.ActorId actorId, Object message) {
        if (!(message instanceof String token)) return null;
        Exchange exchange = transfers.get(token);
        if (exchange == null) return null;
        synchronized (exchange.state) {
            State state = exchange.state;
            if (!state.persistentActor || state.actor == null
                    || !state.actor.id().equals(actorId)
                    || !actorId.equals(state.owner)
                    || state.terminal || !Objects.equals(state.ticket, token)) {
                return null;
            }
            return state.completion;
        }
    }

    public Exchange claim(String token) {
        Exchange exchange = transfers.get(token);
        if (exchange == null) throw new IllegalStateException("unknown or already claimed HTTP transfer");
        synchronized (exchange.state) {
            exchange.checkOwner();
            if (!transfers.claimIfOpen(token, exchange))
                throw new IllegalStateException("HTTP transfer already claimed or runtime closed");
            exchange.state.releaseTransferReservationLocked();
            exchange.state.claimedNanos = System.nanoTime();
            CorePerf.elapsed(CorePerf.HTTP_CLAIM, exchange.state.spawnNanos, exchange.state.claimedNanos);
            return exchange;
        }
    }

    private Object owner() {
        if (ActorRuntime.inActorExecution()) {
            if (ActorRuntime.currentActorRuntime() != context.actors()) throw new SecurityException("foreign actor runtime");
            return ActorRuntime.currentActorId().orElseThrow();
        }
        return context;
    }

    public final class Server implements AutoCloseable {
        private final HttpServer transport;
        private final Semaphore admission;
        private final TransferBudget transferBudget;
        private final int bodyLimit, deadlineMillis;
        private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
        private final Set<State> active = ConcurrentHashMap.newKeySet();
        private OresFuture<Object> waiter;
        private volatile boolean stopped;
        private boolean transportStopped, retirementSweepComplete;
        /** Pending ActorRuntime mailbox handoffs must finish before drain settles. */
        private final java.util.concurrent.atomic.AtomicInteger pendingDeliveries =
                new java.util.concurrent.atomic.AtomicInteger();
        private OresFuture<Object> drained;
        private final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong(), completed = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger tombstones = new java.util.concurrent.atomic.AtomicInteger();

        private Server(HttpServer transport, int capacity, int bodyLimit, int deadlineMillis) {
            this.transport = transport; this.admission = new Semaphore(capacity);
            this.transferBudget = new TransferBudget(capacity);
            this.bodyLimit = bodyLimit; this.deadlineMillis = deadlineMillis;
            // Preserve the original executor when diagnostics are off. In
            // diagnostic mode, measure the JDK-to-virtual-thread handoff.
            if (CorePerf.enabled() || CoreDebug.enabled()) {
                transport.setExecutor(task -> {
                    long queued = CorePerf.start();
                    io.execute(() -> {
                        CorePerf.end(CorePerf.HTTP_EXECUTOR_QUEUE, queued);
                        if (CoreDebug.enabled()) {
                            CoreDebug.event(CoreDebug.HTTP_EXECUTOR_TASK_STARTED, 0L);
                        }
                        task.run();
                    });
                });
            } else {
                transport.setExecutor(io);
            }
            transport.createContext("/", this::receive);
        }
        private void supervisor() {
            if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP server is supervisor-owned");
        }
        public int port() { supervisor(); return transport.getAddress().getPort(); }
        public long accepted() { supervisor(); return accepted.get(); }
        public long rejected() { supervisor(); return rejected.get(); }
        public long completed() { supervisor(); return completed.get(); }
        public int active() { supervisor(); return active.size(); }
        /** Includes unclaimed live transfers and expired queued tombstones. */
        public int transferBacklog() { supervisor(); return transferBudget.outstanding(); }
        public int transferBacklogLimit() { supervisor(); return transferBudget.limit(); }
        public long transferRejected() { supervisor(); return transferBudget.rejected(); }
        public int tombstones() { supervisor(); return tombstones.get(); }

        public synchronized OresFuture<Object> accept() {
            supervisor();
            if (stopped) return OresFuture.failed(new IllegalStateException("HTTP server closed"));
            if (waiter != null) throw new IllegalStateException("only one pending HTTP accept is permitted");
            Exchange exchange = pending.poll();
            while (exchange != null && exchange.state.terminal) exchange = pending.poll();
            if (exchange != null) return OresFuture.completed(exchange);
            waiter = new OresFuture<>(() -> false, () -> { });
            return waiter;
        }
        private void receive(HttpExchange raw) {
            long perf = CorePerf.start();
            try {
            if (!admission.tryAcquire()) { rejected.incrementAndGet(); reject(raw, 503); return; }
            State state = null;
            try {
                validateRequest(raw);
                state = new State(this, raw);
                synchronized (this) {
                    if (stopped) { admission.release(); reject(raw, 503); return; }
                    active.add(state); accepted.incrementAndGet();
                    State timed = state;
                    long schedulePerf = CorePerf.start();
                    state.deadline = timers.schedule(() -> timed.abort(504), deadlineMillis, TimeUnit.MILLISECONDS);
                    CorePerf.end(CorePerf.HTTP_TIMER_SCHEDULE, schedulePerf);
                    Exchange exchange = new Exchange(state, context, 0);
                    if (waiter == null) pending.add(exchange);
                    else { OresFuture<Object> next = waiter; waiter = null; next.completeFromRuntime(exchange); }
                }
            } catch (IllegalArgumentException invalid) {
                admission.release(); reject(raw, invalid.getMessage().equals("body too large") ? 413 : 400);
            } catch (RuntimeException failure) {
                if (state != null && active.contains(state)) state.abort(500);
                else { admission.release(); raw.close(); }
            }
            } finally {
                CorePerf.end(CorePerf.HTTP_RECEIVE, perf);
            }
        }
        private void validateRequest(HttpExchange raw) {
            if (raw.getRequestURI().isAbsolute() || raw.getRequestURI().getRawAuthority() != null || raw.getRequestURI().getRawFragment() != null)
                throw new IllegalArgumentException("only origin-form request targets are supported");
            String target = raw.getRequestURI().toASCIIString();
            if (target.length() > 8192) throw new IllegalArgumentException("request target too long");
            segments(raw.getRequestURI().getRawPath());
            int size = 0;
            for (var entry : raw.getRequestHeaders().entrySet()) {
                size += entry.getKey().length();
                for (String value : entry.getValue()) size += value.length();
            }
            if (size > 32768 || raw.getRequestHeaders().size() > 100) throw new IllegalArgumentException("headers too large");
            String length = raw.getRequestHeaders().getFirst("Content-Length");
            if (length != null && Long.parseLong(length) > bodyLimit) throw new IllegalArgumentException("body too large");
        }
        public OresFuture<Object> shutdown(int graceMillis) {
            supervisor();
            if (graceMillis < 0) throw new IllegalArgumentException("negative drain deadline");
            final OresFuture<Object> result;
            synchronized (this) {
                if (drained != null) return drained;
                drained = new OresFuture<>(() -> false, () -> { });
                stopAccepting();
                result = drained;
            }
            // A concurrent NativeHttp.close() may have shut down the I/O
            // executor. Still stop the listener, settle drain and release all
            // in-flight exchanges instead of leaving this Future pending.
            // In the rejected-executor path, transport.stop() runs inline and
            // may block: never retain the server monitor across this call.
            executeAbortCleanup(io, () -> {
                try { transport.stop((int) (((long) graceMillis + 999) / 1000)); }
                finally {
                    synchronized (this) { transportStopped = true; }
                    try {
                        for (State state : active) state.abort(503);
                        retireQueuedSerialTransfers();
                    } catch (RuntimeException cleanupFailure) {
                        // A failed mailbox-retirement sweep must not leave
                        // server.shutdown() awaiting an impossible drain.
                        result.failFromRuntime(cleanupFailure);
                        throw cleanupFailure;
                    } finally {
                        synchronized (this) { retirementSweepComplete = true; }
                        servers.remove(this);
                        completeDrain();
                    }
                }
            });
            return result;
        }
        private synchronized void completeDrain() {
            if (transportStopped && retirementSweepComplete && active.isEmpty()
                    && pendingDeliveries.get() == 0 && drained != null)
                drained.completeFromRuntime(null);
        }

        /**
         * Revoke only HTTP-origin tokens physically removed from this listener's
         * target actor mailbox. An already-dequeued transfer cannot be safely
         * revoked here: its actor turn may be preparing to claim it.
         *
         * Copy registry entries before inspection. Never hold the server
         * monitor or State monitor while removing actor mailbox envelopes.
         */
        private void retireQueuedSerialTransfers() {
            for (Exchange exchange : transfers.snapshot()) {
                State state = exchange.state;
                ActorRuntime.ActorRef<?> actor;
                String token;
                synchronized (state) {
                    if (state.server != this || !state.persistentActor
                            || state.ticket == null || state.actor == null) continue;
                    actor = state.actor;
                    token = state.ticket;
                }
                if (context.actors().retireQueuedHttpTransfer(actor, token)) {
                    synchronized (state) {
                        if (token.equals(state.ticket)) state.revokeTransferLocked();
                    }
                    state.detachActorDoneObserverIfReleased();
                }
            }
        }
        private synchronized void stopAccepting() {
            stopped = true;
            if (waiter != null) { waiter.failFromRuntime(new IllegalStateException("HTTP server closed")); waiter = null; }
            pending.clear();
        }
        @Override public void close() {
            stopAccepting(); transport.stop(0);
            synchronized (this) { transportStopped = true; }
            try {
                for (State state : active) state.abort(503);
                retireQueuedSerialTransfers();
            } finally {
                synchronized (this) { retirementSweepComplete = true; }
                servers.remove(this);
                completeDrain();
            }
        }
    }

    private final class State {
        final Server server;
        final HttpExchange raw;
        final List<String> path;
        long generation;
        long spawnNanos, readyNanos, movedNanos, claimedNanos;
        Object owner;
        boolean busy, committed, bodyRead, streamingBody, suppressBody;
        int bodyBytes;
        volatile boolean terminal;
        boolean transportClosed;
        String ticket;
        /** Exact registry value installed by this invocation; never revoke by ticket alone. */
        Exchange publishedTransfer;
        /** Distinct one-use admission authority; a stale ticket cannot free another slot. */
        TransferBudget.Reservation reservation;
        /** Counted only when a terminal persistent transfer remains unclaimed. */
        boolean tombstoneCounted;
        final String requestId = UUID.randomUUID().toString();
        final long admittedNanos = System.nanoTime();
        final OresFuture<Object> completion = new OresFuture<>(() -> false, () -> { });
        int responseStatus;
        String operationId = "";
        int pipelineId = -1, contractId = -1;
        final Map<String, List<String>> params = new LinkedHashMap<>();
        ScheduledFuture<?> deadline;
        ActorRuntime.ActorRef<?> actor;
        /** A persistent serial handler cannot be cancelled by one HTTP deadline. */
        boolean persistentActor;
        /** Detachable actor-lifetime observer; do not retain every request forever. */
        OresFuture.RuntimeWaiterRegistration<Void> actorDoneObserver;
        /** Actor startup profiling must not pin timed-out exchanges. */
        OresFuture.RuntimeWaiterRegistration<Void> readyObserver;
        State(Server server, HttpExchange raw) {
            this.server = server; this.raw = raw; this.path = segments(raw.getRequestURI().getRawPath()); this.owner = context;
        }
        /** Called only while holding this State's monitor. */
        void releaseTransferReservationLocked() {
            if (ticket == null || reservation == null)
                throw new IllegalStateException("HTTP transfer reservation already released");
            reservation.release();
            reservation = null;
            publishedTransfer = null;
            ticket = null;
            if (tombstoneCounted) {
                tombstoneCounted = false;
                server.tombstones.decrementAndGet();
            }
        }
        /** Called only while holding this State's monitor. */
        void revokeTransferLocked() {
            if (ticket == null) return;
            if (publishedTransfer == null)
                throw new IllegalStateException("HTTP transfer has no published registry identity");
            // A stale callback must not revoke another incarnation's entry
            // even if a transport ticket were accidentally reused.
            transfers.remove(ticket, publishedTransfer);
            releaseTransferReservationLocked();
        }
        void abort(int status) {
            boolean send, alreadyClosed;
            ActorRuntime.ActorRef<?> cancelActor;
            synchronized (this) {
                alreadyClosed = terminal;
                send = !terminal && !committed && !busy;
                if (!terminal) responseStatus = send ? status : 0;
                terminal = true;
                if (!alreadyClosed && persistentActor && ticket != null && !tombstoneCounted) {
                    tombstoneCounted = true;
                    server.tombstones.incrementAndGet();
                }
                cancelActor = !alreadyClosed && !persistentActor ? actor : null;
            }
            // Startup diagnostics are no longer needed after terminal state;
            // unlink from a stuck actor.ready producer independently of mail.
            detachReadyObserverIfTerminal();
            // An HTTP deadline cancels a per-request actor, never a persistent
            // serial actor processing other HTTP requests and actor messages.
            if (cancelActor != null) cancelActor.cancel();
            if (alreadyClosed) { release(); return; }
            // Executor shutdown may race with a request deadline or actor stop.
            // Every accepted HTTP exchange must close its transport *before*
            // releasing admission, even if the cleanup task is rejected.
            executeAbortCleanup(io, () -> {
                try { if (send) reject(raw, status); else raw.close(); }
                finally {
                    synchronized (this) { transportClosed = true; }
                    release();
                }
            });
        }
        void finish() {
            synchronized (this) { if (terminal) return; terminal = true; }
            detachReadyObserverIfTerminal();
            try { raw.close(); } finally { synchronized (this) { transportClosed = true; } release(); }
        }
        void release() {
            // Admission includes the actor lifetime, even after the response ends.
            // A handler that forgets self.end cannot accumulate unbounded actors.
            synchronized (this) {
                if (!transportClosed
                        || (actor != null && !persistentActor
                                && !actor.done().isDone())) return;
                // Preserve an aborted, unclaimed serial transfer until its
                // queued actor message is consumed. This prevents a timeout
                // from crashing the actor with a stale http.claim token.
                if (ticket != null && !persistentActor) revokeTransferLocked();
                if (deadline != null) deadline.cancel(false);
            }
            if (server.active.remove(this)) {
                if (CorePerf.enabled()) {
                    CorePerf.elapsed(CorePerf.HTTP_LIFETIME, admittedNanos, System.nanoTime());
                }
                synchronized (server) { server.pending.removeIf(exchange -> exchange.state == this); }
                server.admission.release(); server.completed.incrementAndGet(); server.completeDrain();
                completion.completeFromRuntime((long) responseStatus);
            }
            detachReadyObserverIfTerminal();
            detachActorDoneObserverIfReleased();
        }

        void detachReadyObserverIfTerminal() {
            OresFuture.RuntimeWaiterRegistration<Void> observer;
            synchronized (this) {
                if (!terminal) return;
                observer = readyObserver;
                readyObserver = null;
            }
            if (observer != null) observer.detach();
        }

        /**
         * A serial timed-out mail retains its tombstone until consumed by its
         * actor. All other terminal exchanges can immediately release their
         * actor-done observer; in particular a completed HTTP response must
         * not remain reachable from an actor's never-settled done Future.
         */
        void detachActorDoneObserverIfReleased() {
            OresFuture.RuntimeWaiterRegistration<Void> observer;
            synchronized (this) {
                if (server.active.contains(this) || (persistentActor && ticket != null)) return;
                observer = actorDoneObserver;
                actorDoneObserver = null;
            }
            if (observer != null) observer.detach();
        }
    }

    /** A generation-bound capability; moving invalidates every alias of the sender wrapper. */
    public final class Exchange {
        private final State state;
        private final Object owner;
        private final long generation;
        private Exchange(State state, Object owner, long generation) {
            this.state = state; this.owner = owner; this.generation = generation;
        }
        private void checkOwner() {
            if (state.terminal) throw new IllegalStateException("HTTP exchange is closed");
            if (generation != state.generation || !owner.equals(state.owner)) throw new IllegalStateException("HTTP exchange has moved");
            if (!owner.equals(NativeHttp.this.owner())) throw new SecurityException("HTTP exchange belongs to another execution domain");
        }
        public String moveTo(ActorRuntime.ActorRef<?> destination) {
            return moveTo(destination, true);
        }

        // Trusted transport-only option: the long-lived serial actor cannot
        // be terminated by an individual exchange timeout or response failure.
        private String moveTo(ActorRuntime.ActorRef<?> destination,
                              boolean cancelActorOnAbort) {
            synchronized (state) {
                checkOwner();
                if (ActorRuntime.inActorExecution()) throw new SecurityException("only the supervisor may dispatch an HTTP exchange");
                if (closed) throw new IllegalStateException("HTTP runtime closed");
                if (state.server.stopped) throw new RejectedExecutionException("HTTP listener closed");
                if (state.busy || state.committed) throw new IllegalStateException("cannot move an active HTTP exchange");
                if (!context.actors().isAlive(destination)) throw new IllegalStateException("destination actor is not alive");
                if (destination.kind() == ActorRuntime.ActorKind.UNTRUSTED) throw new SecurityException("HTTP exchange transfer to untrusted actors is not supported");
                var lease = state.server.transferBudget.tryReserve();
                if (lease == null)
                    throw new RejectedExecutionException("HTTP unclaimed transfer backlog exhausted");
                state.reservation = lease;
                state.movedNanos = System.nanoTime();
                state.generation++; state.owner = destination.id();
                state.actor = destination;
                state.persistentActor = !cancelActorOnAbort;
                String token = UUID.randomUUID().toString(); state.ticket = token;
                Exchange moved = new Exchange(state, destination.id(), state.generation);
                // If sealing wins after the first closed check, publication
                // fails without consuming a reservation or moving ownership.
                try {
                    transfers.publish(token, moved);
                    state.publishedTransfer = moved;
                } catch (RuntimeException rejected) {
                    state.releaseTransferReservationLocked();
                    state.generation--;
                    state.owner = context;
                    state.actor = null;
                    state.persistentActor = false;
                    throw rejected;
                }
                state.actorDoneObserver = destination.done().whenCompleteRuntimeCancellable(
                        (ignored, failure) -> {
                            synchronized (state) {
                                // Actor teardown drains queued mail; an unclaimed
                                // serial tombstone cannot be consumed afterward.
                                state.revokeTransferLocked();
                            }
                            state.abort(500);
                        });
                // An already-settled actor may invoke its completion callback
                // before registration returns. Recheck after installing the
                // handle so that this race cannot retain a dead observer.
                state.detachActorDoneObserverIfReleased();
                // Closing the runtime may race between the initial closed
                // check and map publication. The shutdown sweep and this
                // second check cover both possible publication orders.
                if (closed || state.server.stopped) {
                    state.revokeTransferLocked();
                    var observer = state.actorDoneObserver;
                    state.actorDoneObserver = null;
                    if (observer != null) observer.detach();
                    throw new IllegalStateException("HTTP runtime closed");
                }
                return token;
            }
        }
        private void supervisorMetadata() {
            checkOwner();
            if (ActorRuntime.inActorExecution() || state.committed || state.busy)
                throw new SecurityException("route metadata is set by supervisor before dispatch");
        }
        public void route(String operation, int pipeline, int contract) {
            synchronized (state) { supervisorMetadata(); state.operationId = operation; state.pipelineId = pipeline; state.contractId = contract; }
        }
        public void addParam(String name, String value) {
            synchronized (state) { supervisorMetadata(); state.params.computeIfAbsent(name, key -> new ArrayList<>()).add(value); }
        }
        public String param(String name) { synchronized (state) { checkOwner(); return state.params.getOrDefault(name, List.of("")).getFirst(); } }
        public int paramSize(String name) { synchronized (state) { checkOwner(); return state.params.getOrDefault(name, List.of()).size(); } }
        public String paramAt(String name, int index) { synchronized (state) { checkOwner(); return state.params.getOrDefault(name, List.of()).get(index); } }
        public boolean canRespond() {
            synchronized (state) {
                return owner.equals(NativeHttp.this.owner()) && generation == state.generation
                    && !state.terminal && !state.busy && !state.committed;
            }
        }
        public String serverTiming() { return "actor-startup;dur=" + startupNanos() / 1_000_000.0 + ", move-to-claim;dur=" + transferNanos() / 1_000_000.0; }
        public long startupNanos() { synchronized (state) { checkOwner(); return state.spawnNanos == 0 ? 0 : Math.max(0, state.readyNanos - state.spawnNanos); } }
        public long transferNanos() { synchronized (state) { checkOwner(); return state.claimedNanos == 0 ? 0 : Math.max(0, state.claimedNanos - state.movedNanos); } }
        public long admittedNanos() { synchronized (state) { checkOwner(); return state.admittedNanos; } }
        public OresFuture<Object> completion() { synchronized (state) { checkOwner(); return state.completion; } }
        public String requestId() { synchronized (state) { checkOwner(); return state.requestId; } }
        public String operationId() { synchronized (state) { checkOwner(); return state.operationId; } }
        public int pipelineId() { synchronized (state) { checkOwner(); return state.pipelineId; } }
        public int contractId() { synchronized (state) { checkOwner(); return state.contractId; } }
        public String cookie(String name) {
            for (String item : header("Cookie").split(";")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) return pair[1];
            }
            return "";
        }
        public String method() { synchronized (state) { checkOwner(); return state.raw.getRequestMethod(); } }
        public String target() { synchronized (state) { checkOwner(); return state.raw.getRequestURI().toASCIIString(); } }
        public int pathSize() { synchronized (state) { checkOwner(); return state.path.size(); } }
        public String pathAt(int index) { synchronized (state) { checkOwner(); return state.path.get(index); } }
        public String header(String name) { synchronized (state) { checkOwner(); return Objects.toString(state.raw.getRequestHeaders().getFirst(name), ""); } }
        public String query(String name) {
            synchronized (state) {
                checkOwner();
                String query = state.raw.getRequestURI().getRawQuery();
                if (query == null) return "";
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (decode(parts[0], true).equals(name)) return parts.length == 1 ? "" : decode(parts[1], true);
                }
                return "";
            }
        }
        public void setHeader(String name, String value) {
            synchronized (state) {
                checkOwner();
                if (state.committed || state.busy) throw new IllegalStateException("response already started");
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.chars().anyMatch(c -> c < 32 || c == 127))
                    throw new IllegalArgumentException("invalid HTTP header");
                if (Set.of("content-length", "transfer-encoding", "connection", "trailer", "upgrade").contains(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("transport-owned response header");
                state.raw.getResponseHeaders().set(name, value);
            }
        }
        public OresFuture<Object> bodyText() {
            synchronized (state) {
                checkOwner();
                if (state.busy) throw new IllegalStateException("await the pending HTTP operation first");
                if (state.bodyRead) throw new IllegalStateException("request body already consumed");
                state.bodyRead = true;
            }
            return operation(() -> {
                byte[] bytes = state.raw.getRequestBody().readNBytes(state.server.bodyLimit + 1);
                if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("body too large");
                return utf8(bytes);
            });
        }
        public OresFuture<Object> readChunk(int maximum) {
            if (maximum < 1 || maximum > 65536) throw new IllegalArgumentException("chunk size must be 1..65536");
            synchronized (state) {
                checkOwner();
                if (state.busy) throw new IllegalStateException("await the pending HTTP operation first");
                if (state.bodyRead && !state.streamingBody) throw new IllegalStateException("request body already consumed");
                state.streamingBody = true; state.bodyRead = true;
            }
            return operation(() -> {
                byte[] bytes = state.raw.getRequestBody().readNBytes(Math.min(maximum, state.server.bodyLimit - state.bodyBytes + 1));
                state.bodyBytes += bytes.length;
                if (state.bodyBytes > state.server.bodyLimit) throw new IllegalArgumentException("body too large");
                List<Object> owned = new ArrayList<>(bytes.length);
                for (byte value : bytes) owned.add((long) (value & 255));
                return owned;
            });
        }
        public OresFuture<Object> respond(int status, String contentType, String body) {
            return respondBuffer(status, contentType, body.getBytes(StandardCharsets.UTF_8));
        }
        public OresFuture<Object> respondBytes(int status, String contentType, List<?> values) {
            return respondBuffer(status, contentType, byteBuffer(values, state.server.bodyLimit));
        }
        private OresFuture<Object> respondBuffer(int status, String contentType, byte[] bytes) {
            validateStatus(status); setHeader("Content-Type", contentType);
            if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("response too large");
            return operation(() -> {
                synchronized (state) {
                    if (state.terminal) throw new IllegalStateException("HTTP exchange is closed");
                    if (state.committed) throw new IllegalStateException("response already started");
                    state.committed = true;
                    state.responseStatus = status;
                }
                boolean noBody = noBody(status);
                if (state.raw.getRequestMethod().equals("HEAD") && status != 204 && status != 205)
                    state.raw.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                state.raw.sendResponseHeaders(status, noBody || bytes.length == 0 ? -1 : bytes.length);
                if (!noBody && bytes.length != 0) state.raw.getResponseBody().write(bytes);
                state.finish(); return null;
            });
        }
        public OresFuture<Object> start(int status, String contentType) {
            validateStatus(status); setHeader("Content-Type", contentType);
            return operation(() -> {
                synchronized (state) {
                    if (state.terminal) throw new IllegalStateException("HTTP exchange is closed");
                    if (state.committed) throw new IllegalStateException("response already started");
                    state.committed = true;
                    state.responseStatus = status;
                }
                state.suppressBody = noBody(status);
                state.raw.sendResponseHeaders(status, state.suppressBody ? -1 : 0);
                return null;
            });
        }
        public OresFuture<Object> write(String text) { return writeBuffer(text.getBytes(StandardCharsets.UTF_8)); }
        public OresFuture<Object> writeBytes(List<?> values) { return writeBuffer(byteBuffer(values, Math.min(65536, state.server.bodyLimit))); }
        private OresFuture<Object> writeBuffer(byte[] bytes) {
            if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("response chunk too large");
            return operation(() -> {
                synchronized (state) {
                    if (state.terminal) throw new IllegalStateException("HTTP exchange is closed");
                    if (!state.committed) throw new IllegalStateException("response not started");
                }
                if (!state.suppressBody) { state.raw.getResponseBody().write(bytes); state.raw.getResponseBody().flush(); }
                return null;
            });
        }
        public OresFuture<Object> finish() { return operation(() -> { if (!state.committed) throw new IllegalStateException("response not started"); state.finish(); return null; }); }
        public void abort() { synchronized (state) { checkOwner(); } state.abort(500); }
        private boolean noBody(int status) { return state.raw.getRequestMethod().equals("HEAD") || status == 204 || status == 205 || status == 304; }
        private OresFuture<Object> operation(Callable<Object> operation) {
            synchronized (state) {
                checkOwner();
                if (state.busy) throw new IllegalStateException("await the pending HTTP operation first");
                state.busy = true;
            }
            OresFuture<Object> result = new OresFuture<>(() -> false, () -> { });
            long queued = CorePerf.start();
            try {
                io.execute(() -> {
                    CorePerf.end(CorePerf.HTTP_IO_QUEUE, queued);
                    long workPerf = CorePerf.start();
                    try {
                        synchronized (state) {
                            // A deadline, disconnect, or context teardown may
                            // have retired the exchange while the operation
                            // was waiting in the I/O executor.
                            if (state.terminal)
                                throw new IllegalStateException("HTTP exchange is closed");
                        }
                        Object value = operation.call();
                        synchronized (state) { state.busy = false; }
                        result.completeFromRuntime(value);
                    } catch (Throwable failure) {
                        synchronized (state) { state.busy = false; }
                        state.abort("body too large".equals(failure.getMessage()) ? 413 : 500);
                        SourceBoundaryTrace.record(failure, "native:http", state.requestId, "I/O");
                        result.failFromRuntime(failure);
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                        if (failure instanceof ThreadDeath fatal) throw fatal;
                        if (failure instanceof LinkageError fatal) throw fatal;
                    } finally {
                        CorePerf.end(CorePerf.HTTP_IO_WORK, workPerf);
                    }
                });
            } catch (RuntimeException failure) { state.abort(500); result.failFromRuntime(failure); }
            return result;
        }
    }

    private static byte[] byteBuffer(List<?> values, int maximum) {
        if (values.size() > maximum) throw new IllegalArgumentException("byte buffer too large");
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Long number) || number < 0 || number > 255) throw new IllegalArgumentException("HTTP bytes must be integers in 0..255");
            bytes[i] = (byte) number.longValue();
        }
        return bytes;
    }

    private static void validateStatus(int status) { if (status < 200 || status > 599) throw new IllegalArgumentException("status must be 200..599"); }
    static List<String> segments(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("expected origin-form path");
        if (path.equals("/")) return List.of();
        String[] parts = path.substring(1).split("/", -1);
        if (parts.length > 128) throw new IllegalArgumentException("too many path segments");
        return Arrays.stream(parts).map(s -> decode(s, false)).toList();
    }
    static String decode(String text, boolean form) {
        // URLDecoder preserves invalid UTF-8 by replacement; round-trip through a strict decoder instead.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i++);
            if (c == '%') {
                if (i + 1 >= text.length()) throw new IllegalArgumentException("malformed percent escape");
                int hi = Character.digit(text.charAt(i++), 16), lo = Character.digit(text.charAt(i++), 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException("malformed percent escape");
                bytes.write((hi << 4) | lo);
            } else {
                String literal;
                if (Character.isHighSurrogate(c) && i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
                    literal = new String(new char[] {c, text.charAt(i++)});
                } else {
                    if (Character.isSurrogate(c)) throw new IllegalArgumentException("invalid Unicode surrogate");
                    literal = form && c == '+' ? " " : String.valueOf(c);
                }
                bytes.writeBytes(literal.getBytes(StandardCharsets.UTF_8));
            }
        }
        String result = utf8(bytes.toByteArray());
        if (result.chars().anyMatch(c -> c < 32 || c == 127 || c == '\\')) throw new IllegalArgumentException("invalid request character");
        return result;
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("invalid UTF-8", invalid); }
    }
    private static void reject(HttpExchange raw, int status) {
        try { raw.sendResponseHeaders(status, -1); } catch (IOException ignored) { } finally { raw.close(); }
    }

    /** Schedule terminal transport cleanup, running it inline if shutdown wins. */
    static void executeAbortCleanup(Executor executor, Runnable cleanup) {
        Objects.requireNonNull(executor, "HTTP I/O executor");
        Objects.requireNonNull(cleanup, "HTTP abort cleanup");
        try {
            executor.execute(cleanup);
        } catch (RejectedExecutionException rejected) {
            // The virtual-thread executor no longer accepts work; never
            // leave transportClosed=false and permanently retain admission.
            cleanup.run();
        }
    }
    /**
     * Phase 1 of context shutdown: seal new HTTP admission, stop listeners,
     * revoke unclaimed transfer capabilities and request persistent actor
     * termination. Keep I/O/timer executors alive for already-authorized actor
     * cleanup until ActorRuntime has crossed its physical exit barrier.
     */
    void beginContextShutdown() {
        synchronized (retirementLock) {
            if (retired) return;
            final List<Server> serversToClose;
            final List<Handler> handlersToStop;
            final List<Exchange> transfersToRevoke;
            synchronized (this) {
                if (retiringServers == null) {
                    retiringTransfers = transfers.sealAndSnapshot();
                    closed = true;
                    retiringServers = List.copyOf(servers);
                    retiringHandlers = List.copyOf(handlers);
                }
                serversToClose = retiringServers;
                handlersToStop = retiringHandlers;
                transfersToRevoke = retiringTransfers;
            }
            for (Server server : serversToClose) server.close();
            for (Handler handler : handlersToStop) {
                synchronized (handler) {
                    if (handler.persistentActor != null) handler.persistentActor.cancel();
                }
            }
            // Every entry came from the sealed registry snapshot, so revocation
            // cannot race publication/claim of a newer capability.
            for (Exchange exchange : transfersToRevoke) {
                synchronized (exchange.state) {
                    if (exchange.state.ticket != null) {
                        exchange.state.revokeTransferLocked();
                    }
                }
            }
        }
    }

    @Override public void close() {
        synchronized (retirementLock) {
            if (retired) return;
            if (retirementOwner == Thread.currentThread()) {
                throw new IllegalStateException(
                        "HTTP retirement cannot re-enter from its own cleanup callback");
            }
            retirementOwner = Thread.currentThread();
            try {
                beginContextShutdown();

                // ActorRuntime has already crossed its physical exit barrier
                // when OresContext invokes this phase. No actor can now begin
                // another HTTP operation or depend on these executors.
                timers.shutdownNow();
                io.shutdown();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try {
                    long left = deadline - System.nanoTime();
                    if (left <= 0 || !io.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException(
                                "HTTP I/O carriers did not physically exit during close");
                    }
                    left = deadline - System.nanoTime();
                    if (left <= 0 || !timers.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException(
                                "HTTP timer carrier did not physically exit during close");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted before HTTP physical carrier exit", interrupted);
                }

                handlers.clear();
                transfers.clear();
                retiringServers = null;
                retiringHandlers = null;
                retiringTransfers = null;
                retired = true;
            } finally {
                retirementOwner = null;
            }
        }
    }
}
