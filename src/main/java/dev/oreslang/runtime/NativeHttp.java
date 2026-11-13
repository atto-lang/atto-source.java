package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP/1.1 transport. Guest code sees revocable exchange capabilities, never sockets. */
public final class NativeHttp implements AutoCloseable {
    private final OresContext context;
    private final Set<Server> servers = ConcurrentHashMap.newKeySet();
    private final Map<String, Exchange> transfers = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "ores-http-deadlines"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    private final Set<Handler> pools = ConcurrentHashMap.newKeySet();

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
    /** Validate the decoded HTTP header collection before a guest sees the request.
     * JDK parser byte limits apply separately before this method is reached.
     * This additional per-field limit rejects oversized-but-otherwise-valid
     * requests that the JDK's aggregate 32 KiB maximum would permit.
     */
    static void validateHeadersForAdmission(
            com.sun.net.httpserver.Headers headers, String protocol, int bodyLimit) {
        List<String> hosts = headers.get("Host");
        if (("HTTP/1.1".equalsIgnoreCase(protocol) && hosts == null)
                || (hosts != null && (hosts.size() != 1 || !validHostValue(hosts.get(0))))) {
            // Host is required for HTTP/1.1 and, whenever present, must not
            // permit duplicate or ambiguous routing targets.
            throw new IllegalArgumentException("invalid Host header");
        }
        List<String> lengths = headers.get("Content-Length");
        List<String> transfers = headers.get("Transfer-Encoding");
        if (lengths != null && (lengths.size() != 1 || lengths.get(0) == null
                || !lengths.get(0).matches("[0-9]+"))) {
            throw new IllegalArgumentException("ambiguous Content-Length");
        }
        if (transfers != null && (lengths != null || transfers.size() != 1
                || transfers.get(0) == null
                || !"chunked".equalsIgnoreCase(transfers.get(0).trim())
                || !"HTTP/1.1".equalsIgnoreCase(protocol))) {
            // Do not let different HTTP parsers disagree on body framing.
            throw new IllegalArgumentException("ambiguous Transfer-Encoding");
        }
        int size = 0;
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            size += name.length();
            for (String value : entry.getValue()) {
                if (value == null || name.length() + value.length() > 8192) {
                    throw new IllegalArgumentException("HTTP header field too large");
                }
                size += value.length();
            }
        }
        if (size > 32768 || headers.size() > 100) {
            throw new IllegalArgumentException("headers too large");
        }
        if (lengths != null) {
            long parsed = Long.parseLong(lengths.get(0));
            if (parsed > bodyLimit) throw new IllegalArgumentException("body too large");
        }
    }

    private static boolean validHostValue(String host) {
        if (host == null || host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            // Reject ASCII whitespace, controls, non-ASCII bytes and URI
            // delimiters that could produce a different downstream authority.
            // Bracketed IPv6 literals and host:port are still permitted.
            if (c <= 0x20 || c >= 0x7f || c == '/' || c == '\\'
                    || c == '?' || c == '#' || c == '@' || c == ',') return false;
        }
        return true;
    }

    /** An invalid request-body framing stream is a client error, not HTTP 500.
     * Keep response-side I/O failures as server errors, and preserve 413 limits.
     */
    static int operationFailureStatus(Throwable failure, boolean requestBodyRead) {
        if ("body too large".equals(failure.getMessage())) return 413;
        if (requestBodyRead && failure instanceof IOException) return 400;
        return 500;
    }

    private static void configureTransportDefaults() {
        Map.of("jdk.httpserver.maxConnections", "1024",
               "sun.net.httpserver.maxReqHeaders", "100",
               "sun.net.httpserver.maxReqHeaderSize", "32768",
               "sun.net.httpserver.maxReqTime", "15",
               "sun.net.httpserver.maxRspTime", "30",
               "sun.net.httpserver.drainAmount", "1",
               "sun.net.httpserver.nodelay", "true").forEach((key, value) -> System.getProperties().putIfAbsent(key, value));
    }

    /** A preflight predicate, not a physical carrier quiescence receipt. */
    static boolean mayBeginDispatch(boolean runtimeClosed, boolean listenerStopping) {
        return !runtimeClosed && !listenerStopping;
    }

    /**
     * Per-request permit retirement predicate, NOT listener or pooled-worker
     * physical shutdown certification. Pooled workers own a separate carrier
     * lifetime even after their individual request permit is released.
     */
    static boolean canRetireRequest(boolean transportClosed, boolean dispatchInProgress,
                                    boolean freshActorExitedOrPoolTracked) {
        return transportClosed && !dispatchInProgress && freshActorExitedOrPoolTracked;
    }

    /** A removed request has not completed until its Future callback has returned. */
    static boolean readyForCarrierBarrier(boolean stoppedTransport, boolean noActive,
                                           int publishingReleases) {
        return stoppedTransport && noActive && publishingReleases == 0;
    }

    static boolean claimForcedClose(AtomicBoolean claim) {
        return claim.compareAndSet(false, true);
    }

    /** Publish the terminal Future after leaving the HTTP listener monitor. */
    static void failOutsideMonitor(Object lock, OresFuture<Object> future, Throwable failure) {
        if (Thread.holdsLock(lock)) {
            throw new IllegalStateException("HTTP terminal callback under listener monitor");
        }
        if (future != null) future.failFromRuntime(failure);
    }

    static void publishOutsideMonitor(Object lock, OresFuture<Object> future, Object exchange) {
        if (Thread.holdsLock(lock)) {
            throw new IllegalStateException("HTTP accept callback under listener monitor");
        }
        if (future != null) future.completeFromRuntime(exchange);
    }

    public Handler handler(ActorRuntime.ActorCodeExecutor code, String type, ActorRuntime.ActorKind kind) {
        if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP handlers are registered by the supervisor");
        if (kind == ActorRuntime.ActorKind.UNTRUSTED) throw new SecurityException("untrusted HTTP handlers are not supported");
        return new Handler(code, type, kind);
    }

    /** Supervisor-owned code registration and optional bounded reusable worker pool. */
    public final class Handler {
        private final ActorRuntime.ActorCodeExecutor code;
        private final String type;
        private final ActorRuntime.ActorKind kind;
        private Handler(ActorRuntime.ActorCodeExecutor code, String type, ActorRuntime.ActorKind kind) {
            this.code = code; this.type = type; this.kind = kind;
        }
        private Worker[] workers;
        // Replaced incarnations can outlive their pool slot while serving
        // other listeners. Keep every generation until physical retirement.
        private final HttpPooledWorkerGenerationRegistry<Worker> generations =
                new HttpPooledWorkerGenerationRegistry<>();
        private int cursor;
        private long created, reused;
        public boolean isolated() { return kind == ActorRuntime.ActorKind.PRIVATE; }
        private void supervisor() {
            if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP pools belong to the supervisor");
        }
        public Handler pooled(int size) {
            supervisor();
            if (size < 1 || size > 4096) throw new IllegalArgumentException("HTTP pool size must be 1..4096");
            synchronized (NativeHttp.this) {
                if (closed) throw new IllegalStateException("HTTP runtime closed");
                Handler pool = new Handler(code, type, kind);
                pool.workers = new Worker[size];
                pools.add(pool);
                return pool;
            }
        }
        public synchronized long created() { supervisor(); return created; }
        public synchronized long reused() { supervisor(); return reused; }
        private synchronized Worker worker() {
            if (closed || generations.stopping()) {
                throw new IllegalStateException("HTTP runtime or worker pool closed");
            }
            int slot = cursor;
            cursor = (cursor + 1) % workers.length;
            Worker worker = workers[slot];
            if (worker != null && !worker.actor.done().isDone()) {
                // FIRST check the admission fence. A worker sealed by another
                // listener is intentionally stopping and MUST be replaced,
                // not classified as a generic 500 "worker stopping" error.
                // A successful reservation prevents a concurrent shutdown
                // seal from overtaking this dispatch before its handoff.
                if (worker.admission.tryReserve()) {
                    if (context.actors().isAlive(worker.actor)) {
                        reused++;
                        return worker;
                    }
                    // The old actor can be STOPPING before doneFuture has
                    // finished publishing. Seal it, release our reservation
                    // and replace the slot without trying a doomed send.
                    worker.admission.seal();
                    worker.admission.release();
                }
                // The sealed or stopping generation remains tracked by the
                // historical registry until physical retirement. This slot
                // can immediately point to a fresh incarnation.
            }
            worker = new Worker(context.actors().spawnCodeActor(kind, code, type));
            if (!worker.admission.tryReserve()) {
                throw new IllegalStateException("new HTTP worker was unexpectedly sealed");
            }
            // Registration precedes publishing the replacement slot. stopPool
            // takes the Handler monitor before closing the registry; a worker
            // cannot spawn after its final-generation snapshot.
            if (!generations.register(worker)) {
                worker.admission.seal();
                worker.actor.cancel();
                throw new RejectedExecutionException("HTTP worker pool stopping");
            }
            workers[slot] = worker;
            created++;
            Worker attached = worker;
            worker.actor.done().whenCompleteRuntime((ignored, failure) -> {
                // DONE may publish while the last carrier is still executing
                // finalization callbacks. Stop any fresh worker reservation
                // NOW, but only detach historical listener tracking after
                // actual physical cleanup has crossed its final boundary.
                attached.admission.seal();
                HttpResponseCleanupDispatcher.dispatch(io, () -> {
                    try {
                        for (State state : List.copyOf(attached.requests)) {
                            try { state.abort(500, false); }
                            catch (RuntimeException abortFailure) {
                                state.server.failDrain(abortFailure);
                            }
                        }
                    } finally {
                        retireWorkerWhenPhysicallyExited(attached);
                    }
                });
            });
            return worker;
        }

        private void retireWorkerWhenPhysicallyExited(Worker attached) {
            // Actor finalization Future publication and actual ThreadLocal
            // carrier exit are separate boundaries. Removing the listener's
            // worker generation before BOTH are complete can make shutdown
            // observe an empty set and claim a false successful drain.
            final boolean canRetire;
            try {
                canRetire = attached.admission.quiescent()
                        && context.actors().httpCarrierQuiescent(attached.actor);
            } catch (RuntimeException observationFailure) {
                // Fail closed: preserve the listener's tracked generation.
                for (Server server : List.copyOf(attached.served)) {
                    server.failDrain(observationFailure);
                }
                return;
            }
            if (!canRetire) {
                try {
                    timers.schedule(() -> retireWorkerWhenPhysicallyExited(attached),
                            10L, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException timerClosed) {
                    // Listener close/forced shutdown will explicitly detach
                    // worker references. Never detach early on timer failure.
                }
                return;
            }
            for (Server server : List.copyOf(attached.served)) {
                server.pooledWorkers.remove(attached);
                attached.served.remove(server);
            }
            attached.requests.clear();
            // Only now may the retired generation disappear from global
            // pool accounting. It is no longer a live physical carrier.
            generations.retire(attached);
        }

        private void stopPool() {
            List<Worker> snapshot;
            synchronized (this) {
                snapshot = generations.stopAndSnapshot();
            }
            RuntimeException failure = null;
            for (Worker worker : snapshot) {
                try {
                    worker.admission.seal();
                    worker.actor.cancel();
                } catch (RuntimeException cancelFailure) {
                    if (failure == null) failure = cancelFailure;
                    else failure.addSuppressed(cancelFailure);
                }
            }
            if (failure != null) throw failure;
        }
        private final class Worker {
            final ActorRuntime.ActorRef<Object> actor;
            final Set<State> requests = ConcurrentHashMap.newKeySet();
            final Set<Server> served = ConcurrentHashMap.newKeySet();
            final HttpPooledWorkerAdmissionFence admission =
                    new HttpPooledWorkerAdmissionFence();
            // Only one host thread can initiate actor shutdown for a sealed
            // generation. The ActorRuntime then seals ALL actor messages,
            // including non-HTTP continuations, before finalizing physically.
            final AtomicBoolean shutdownStopRequested = new AtomicBoolean();
            Worker(ActorRuntime.ActorRef<Object> actor) { this.actor = actor; }
        }
        public void dispatch(Exchange exchange) {
            if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP dispatch belongs to supervisor");
            // The request has already reserved an HTTP admission permit.
            // Reserve handoff ownership before selecting/spawning a worker:
            // timeout/abort must not retire the request during actor startup.
            State state = exchange.state;
            boolean denyDispatch;
            synchronized (state) {
                exchange.checkOwner();
                if (state.dispatchInProgress) {
                    throw new IllegalStateException("HTTP exchange already dispatching");
                }
                denyDispatch = !mayBeginDispatch(closed, state.server.stopped);
                if (!denyDispatch) {
                    state.dispatchInProgress = true;
                    state.spawnNanos = System.nanoTime();
                }
            }
            if (denyDispatch) {
                state.abort(503, false);
                return;
            }
            long perf = CorePerf.start();
            try {
                if (workers != null) {
                    Worker worker = null;
                    try {
                        worker = worker();
                        boolean denyWorkerDispatch;
                        synchronized (state) {
                            // Recheck after choosing the worker; a concurrent
                            // timeout/stop must not transfer a stale exchange.
                            denyWorkerDispatch = state.terminal
                                    || !mayBeginDispatch(closed, state.server.stopped);
                            if (!denyWorkerDispatch) {
                                state.poolWorker = worker;
                                worker.requests.add(state);
                                // Track ALL previously associated pooled workers
                                // until listener shutdown witnesses their carrier
                                // exit, including generations later replaced.
                                state.server.pooledWorkers.add(worker);
                                worker.served.add(state.server);
                                state.readyNanos = state.spawnNanos;
                            }
                        }
                        if (denyWorkerDispatch) {
                            state.abort(503, false);
                            return;
                        }
                        // Never unlink this generation merely because DONE
                        // published: the last TurnExecutor callback may still
                        // own a physical carrier and the handoff lease is
                        // still outstanding. Retirement is handled only after
                        // the lease is released and physical exit is observed.
                        worker.actor.send(exchange.moveTo(worker.actor));
                    } catch (RuntimeException failure) {
                        state.abort(mayBeginDispatch(closed, state.server.stopped)
                                ? 500 : 503, false);
                    } finally {
                        // Every successful worker() reservation is released on
                        // success, abort, cancellation, send failure and close.
                        // Seal and release arbitrate under the same fence lock.
                        if (worker != null) {
                            worker.admission.release();
                            if (worker.actor.done().isDone()) {
                                // DONE can beat the association in the block
                                // above, even if its callback has already
                                // examined an empty served set. Recheck after
                                // releasing this dispatch lease and retire
                                // using the same physical-exit gate as the
                                // terminal callback. Never remove inline.
                                worker.admission.seal();
                                Worker retired = worker;
                                HttpResponseCleanupDispatcher.dispatch(io,
                                        () -> retireWorkerWhenPhysicallyExited(retired));
                            }
                        }
                    }
                    return;
                }

                ActorRuntime.ActorRef<Object> actor = null;
                try {
                    long spawnPerf = CorePerf.start();
                    try {
                        actor = context.actors().spawnCodeActor(kind, code, type);
                    } finally {
                        CorePerf.end(CorePerf.ACTOR_SPAWN, spawnPerf);
                    }
                    ActorRuntime.ActorRef<Object> spawned = actor;
                    // Attach actor identity as soon as spawn returns, even if
                    // an HTTP timeout already closed the transport. The DONE
                    // observer is needed when moveTo never installs its own
                    // completion callback due to cancellation during spawn.
                    synchronized (state) {
                        state.actor = spawned;
                    }
                    // Actor DONE can be published while holding the actor's
                    // lifecycle monitor. Schedule the release off that stack
                    // so HTTP completion callbacks cannot reenter the actor.
                    spawned.done().whenCompleteRuntime((ignored, failure) ->
                            HttpResponseCleanupDispatcher.dispatch(io,
                                    () -> state.observeUnpooledCarrierExit(spawned)));
                    spawned.ready().whenCompleteRuntime((ignored, failure) -> {
                        long spawn;
                        long ready;
                        synchronized (state) {
                            state.readyNanos = System.nanoTime();
                            spawn = state.spawnNanos;
                            ready = state.readyNanos;
                        }
                        CorePerf.elapsed(CorePerf.ACTOR_READY, spawn, ready);
                    });
                    boolean denyAfterSpawn;
                    synchronized (state) {
                        denyAfterSpawn = state.terminal
                                || !mayBeginDispatch(closed, state.server.stopped);
                    }
                    if (denyAfterSpawn) {
                        spawned.cancel();
                        state.abort(503, false);
                        return;
                    }
                    spawned.send(exchange.moveTo(spawned));
                } catch (RuntimeException failure) {
                    if (actor != null) actor.cancel();
                    state.abort(mayBeginDispatch(closed, state.server.stopped)
                            ? 500 : 503);
                }
            } finally {
                // Publication must follow the handoff association, even if
                // transport abort/response close occurred on another thread.
                synchronized (state) {
                    state.dispatchInProgress = false;
                }
                try { state.release(); }
                finally { CorePerf.end(CorePerf.HTTP_DISPATCH, perf); }
            }
        }
    }

    public Exchange claim(String token) {
        Exchange exchange = transfers.get(token);
        if (exchange == null) throw new IllegalStateException("unknown or already claimed HTTP transfer");
        synchronized (exchange.state) {
            exchange.checkOwner();
            if (!transfers.remove(token, exchange)) throw new IllegalStateException("HTTP transfer already claimed");
            exchange.state.ticket = null;
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
        private final HttpResponseBodyOpenGate bodyOpenGate;
        private final int bodyLimit, deadlineMillis;
        private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
        private final Set<State> active = ConcurrentHashMap.newKeySet();
        // A request may leave active before its completion callback returns.
        // Shutdown must join callback publication before declaring success.
        private final AtomicInteger releasesInProgress = new AtomicInteger();
        // The listener owns historical worker incarnations, not only the
        // Handler's current pool slots (which may already be replaced).
        private final Set<Handler.Worker> pooledWorkers = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean forcedCloseClaimed = new AtomicBoolean();
        private boolean pooledBarrierStarted, drainOutcomeClaimed;
        private OresFuture<Object> waiter;
        private volatile boolean stopped;
        private boolean transportStopped;
        private OresFuture<Object> drained;
        private final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong(), completed = new AtomicLong();

        private Server(HttpServer transport, int capacity, int bodyLimit, int deadlineMillis) {
            this.transport = transport; this.admission = new Semaphore(capacity);
            this.bodyLimit = bodyLimit; this.deadlineMillis = deadlineMillis;
            // A timed-out host Supplier can ignore interrupts. Retain its
            // opener slot until it *actually returns*, even after HTTP admission
            // is released, so hostile suppliers cannot create unbounded tasks.
            this.bodyOpenGate = new HttpResponseBodyOpenGate(Math.min(32, capacity), io);
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
                if (!admission.tryAcquire()) {
                    rejected.incrementAndGet();
                    reject(raw, 503);
                    return;
                }
                State state = null;
                // Registered requests own the permit until State.release,
                // even if another thread removes them during publication.
                boolean registered = false;
                try {
                    validateRequest(raw);
                    state = new State(this, raw);
                    OresFuture<Object> acceptToComplete = null;
                    Exchange acceptedExchange = null;
                    boolean denied;
                    synchronized (this) {
                        denied = stopped;
                        if (!denied) {
                            active.add(state);
                            registered = true;
                            accepted.incrementAndGet();
                            State timed = state;
                            long scheduled = CorePerf.start();
                            try {
                                state.deadline = timers.schedule(
                                        () -> timed.abort(504), deadlineMillis, TimeUnit.MILLISECONDS);
                            } finally {
                                CorePerf.end(CorePerf.HTTP_TIMER_SCHEDULE, scheduled);
                            }
                            Exchange exchange = new Exchange(state, context, 0);
                            if (waiter == null) pending.add(exchange);
                            else {
                                acceptToComplete = waiter;
                                waiter = null;
                                acceptedExchange = exchange;
                            }
                        }
                    }
                    if (denied) {
                        admission.release();
                        reject(raw, 503);
                        return;
                    }
                    // OresFuture listeners can synchronously reenter listener
                    // operations. Never invoke them under Server's monitor.
                    publishOutsideMonitor(this, acceptToComplete, acceptedExchange);
                } catch (IllegalArgumentException invalid) {
                    int status = "body too large".equals(invalid.getMessage()) ? 413 : 400;
                    if (registered) state.abort(status);
                    else { admission.release(); reject(raw, status); }
                } catch (RuntimeException failure) {
                    if (registered) state.abort(500);
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
            validateHeadersForAdmission(raw.getRequestHeaders(), raw.getProtocol(), bodyLimit);
        }
        public OresFuture<Object> shutdown(int graceMillis) {
            supervisor();
            if (graceMillis < 0) throw new IllegalArgumentException("negative drain deadline");
            OresFuture<Object> acceptToFail;
            OresFuture<Object> result;
            synchronized (this) {
                if (drained != null) return drained;
                if (stopped) return OresFuture.failed(
                        new IllegalStateException("HTTP listener already closed"));
                drained = new OresFuture<>(() -> false, () -> { });
                result = drained;
                acceptToFail = stopAcceptingLocked();
            }
            failOutsideMonitor(this, acceptToFail,
                    new IllegalStateException("HTTP server closed"));
            // A listener may retain an accepted exchange forever if socket
            // abort or its completion callback stalls. The carrier observer
            // only starts AFTER all requests retire, so also bound the whole
            // shutdown interval, not merely the pooled-carrier final phase.
            long shutdownLimitMillis = Math.max(1_000L,
                    Math.min(30_000L, (long) deadlineMillis + graceMillis));
            final ScheduledFuture<?> overallDeadline;
            try {
                overallDeadline = timers.schedule(() -> failDrain(
                        new TimeoutException("HTTP shutdown did not quiesce accepted requests"
                                + " and physical actor carriers")),
                        shutdownLimitMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException unavailable) {
                failDrain(unavailable);
                return result;
            }
            result.whenCompleteRuntime((ignored, failure) -> overallDeadline.cancel(false));
            // The dispatcher has a virtual-thread fallback when the I/O pool
            // was already closed, so no shutdown Future is silently stranded.
            HttpResponseCleanupDispatcher.dispatch(io, () -> {
                try {
                    transport.stop((int) (((long) graceMillis + 999L) / 1000L));
                } catch (RuntimeException failure) {
                    failDrain(failure);
                } finally {
                    // Do NOT expose transportStopped until every accepted
                    // exchange has been offered abort cleanup; otherwise a
                    // concurrent State.release could falsely publish success.
                    try {
                        abortAllAcceptedOnShutdown();
                    } finally {
                        synchronized (this) { transportStopped = true; }
                        servers.remove(this);
                        completeDrain();
                    }
                }
            });
            return result;
        }

        private void abortAllAcceptedOnShutdown() {
            for (State state : List.copyOf(active)) {
                try { state.abort(503); }
                catch (RuntimeException | Error failure) {
                    // Keep aborting every other accepted exchange even when an
                    // individual socket or actor cancellation fails.
                    failDrain(failure);
                }
            }
        }

        private void completeDrain() {
            List<Handler.Worker> snapshot;
            synchronized (this) {
                if (!readyForCarrierBarrier(
                        transportStopped, active.isEmpty(), releasesInProgress.get())
                        || drained == null || drainOutcomeClaimed || pooledBarrierStarted) {
                    return;
                }
                pooledBarrierStarted = true;
                snapshot = List.copyOf(pooledWorkers);
                // Linearize shutdown with ALL HTTP dispatch reservations,
                // including those coming from other live listeners sharing a
                // Handler pool. No new admission may use this worker generation
                // after seal; a different listener creates a replacement.
                for (Handler.Worker worker : snapshot) worker.admission.seal();
                pooledWorkers.clear();
            }
            for (Handler.Worker worker : snapshot) worker.served.remove(this);
            if (snapshot.isEmpty()) {
                succeedDrain();
                return;
            }
            // Point-in-time host-only observation. The listener has sealed its
            // admission and finished publishing all accepted exchanges, but a
            // reusable worker can still own a physical Truffle carrier.
            // Bound waiting to avoid success on a stalled/uncooperative actor.
            long limitMillis = Math.max(1_000L, Math.min(30_000L, (long) deadlineMillis));
            inspectPooledCarriers(snapshot, System.nanoTime(),
                    TimeUnit.MILLISECONDS.toNanos(limitMillis));
        }

        private void inspectPooledCarriers(
                List<Handler.Worker> workers, long startedNanos, long allowedNanos) {
            synchronized (this) {
                if (drainOutcomeClaimed || drained == null) return;
            }
            try {
                for (Handler.Worker worker : workers) {
                    // Reservation sealing is the HTTP dispatch linearization
                    // point; wait out any pre-seal handoffs before closing the
                    // actor mailbox. Never strand an accepted send by sealing
                    // its actor before it has finished transferring the token.
                    if (worker.admission.quiescent()) {
                        // A Handler pool may be used by multiple HTTP listeners.
                        // Stopping this actor would ALSO seal another listener's
                        // in-flight continuations. Once all pre-seal handoffs
                        // have settled, the historical association set is a
                        // conservative cross-listener ownership receipt.
                        // Until per-listener actor ownership is implemented,
                        // fail this shutdown explicitly rather than canceling
                        // unrelated live guest work or claiming false success.
                        if (worker.served.stream().anyMatch(other -> other != this)) {
                            failDrain(new IllegalStateException(
                                    "HTTP pooled actor generation still shared with another"
                                    + " listener; independent physical drain is unsupported"));
                            return;
                        }
                        if (worker.shutdownStopRequested.compareAndSet(false, true)) {
                            // For an exclusively associated generation,
                            // ActorRuntime.gracefulStop seals future messages,
                            // drains accepted mail and waits for finalization.
                            context.actors().gracefulStop(worker.actor);
                        }
                    }
                    // A point-in-time idle snapshot is NOT a shutdown proof:
                    // require a terminal ActorRuntime DONE after the durable
                    // actor admission seal, plus the physical carrier gate.
                    if (!worker.admission.quiescent()
                            || !worker.actor.done().isDone()
                            || !context.actors().httpCarrierQuiescent(worker.actor)) {
                        if (System.nanoTime() - startedNanos >= allowedNanos) {
                            failDrain(new TimeoutException(
                                    "HTTP pooled actor carrier did not quiesce before shutdown deadline"));
                        } else {
                            try {
                                timers.schedule(() -> inspectPooledCarriers(
                                        workers, startedNanos, allowedNanos),
                                        10L, TimeUnit.MILLISECONDS);
                            } catch (RejectedExecutionException unavailable) {
                                failDrain(unavailable);
                            }
                        }
                        return;
                    }
                }
                succeedDrain();
            } catch (RuntimeException | Error failure) {
                failDrain(failure);
            }
        }

        private void succeedDrain() {
            OresFuture<Object> completedDrain;
            synchronized (this) {
                if (forcedCloseClaimed.get() || drainOutcomeClaimed || drained == null) return;
                drainOutcomeClaimed = true;
                completedDrain = drained;
            }
            completedDrain.completeFromRuntime(null);
        }

        private void failDrain(Throwable failure) {
            OresFuture<Object> failedDrain;
            synchronized (this) {
                if (drainOutcomeClaimed || drained == null) return;
                drainOutcomeClaimed = true;
                failedDrain = drained;
            }
            failedDrain.failFromRuntime(failure);
        }

        /** Caller must hold the listener monitor. */
        private OresFuture<Object> stopAcceptingLocked() {
            stopped = true;
            OresFuture<Object> pendingAccept = waiter;
            waiter = null;
            pending.clear();
            return pendingAccept;
        }

        @Override public void close() {
            OresFuture<Object> acceptToFail;
            OresFuture<Object> drainToFail = null;
            synchronized (this) {
                if (!claimForcedClose(forcedCloseClaimed)) return;
                acceptToFail = stopAcceptingLocked();
                if (drained != null && !drainOutcomeClaimed) {
                    drainOutcomeClaimed = true;
                    drainToFail = drained;
                }
            }
            if (drainToFail != null) {
                failOutsideMonitor(this, drainToFail,
                        new IllegalStateException(
                                "HTTP listener force-closed before carrier quiescence"));
            }
            failOutsideMonitor(this, acceptToFail,
                    new IllegalStateException("HTTP server closed"));
            try {
                transport.stop(0);
            } finally {
                try {
                    abortAllAcceptedOnShutdown();
                } finally {
                    synchronized (this) { transportStopped = true; }
                    for (Handler.Worker worker : List.copyOf(pooledWorkers)) {
                        worker.served.remove(this);
                    }
                    pooledWorkers.clear();
                    servers.remove(this);
                    completeDrain();
                }
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
        // Response ownership and terminal cancellation share the State lock
        // with the first, irreversible HTTP header attempt.
        final HttpResponseWirePhase wirePhase = new HttpResponseWirePhase();
        boolean busy, committed, bodyRead, streamingBody, suppressBody;
        /** Guarded by State monitor; permit survives timeout until handoff completes. */
        boolean dispatchInProgress;
        int bodyBytes;
        volatile boolean terminal;
        volatile InputStream activeResponseInput;
        boolean transportClosed;
        String ticket;
        final String requestId = UUID.randomUUID().toString();
        final long admittedNanos = System.nanoTime();
        final OresFuture<Object> completion = new OresFuture<>(() -> false, () -> { });
        int responseStatus;
        String operationId = "";
        int pipelineId = -1, contractId = -1;
        final Map<String, List<String>> params = new LinkedHashMap<>();
        ScheduledFuture<?> deadline;
        ActorRuntime.ActorRef<?> actor;
        Handler.Worker poolWorker;
        // A terminal actor Future can publish before the TurnExecutor has
        // finished its last carrier exit and ThreadLocal cleanup. Only a
        // host-side physical observation may set this receipt.
        boolean unpooledPhysicalExitObserved;
        State(Server server, HttpExchange raw) {
            this.server = server; this.raw = raw; this.path = segments(raw.getRequestURI().getRawPath()); this.owner = context;
        }
        // Linearize cancellation against success header emission. Never
        // hold this monitor while performing blocking socket I/O.
        boolean beginHeaders() {
            synchronized (this) {
                return wirePhase.begin(committed, terminal);
            }
        }
        void noteHeadersSent() {
            synchronized (this) { wirePhase.markSent(); }
        }
        void abort(int status) { abort(status, true); }
        void abort(int status, boolean cancelOwner) {
            boolean send, alreadyClosed;
            synchronized (this) {
                alreadyClosed = terminal;
                // A reserved response that never reached the wire may still
                // fail precommit. Once an emission attempt starts, never
                // synthesize a second HTTP status.
                send = wirePhase.maySendError(terminal, busy);
                if (!terminal) responseStatus = send ? status : 0;
                terminal = true;
            }
            // Never perform socket I/O or actor cancellation while holding the ownership monitor.
            if (alreadyClosed && poolWorker != null) { release(); return; }
            if (actor != null && cancelOwner) actor.cancel();
            if (alreadyClosed) { release(); return; }
            InputStream responseInput = activeResponseInput;
            // A hostile InputStream.close() can block. Never wait for producer
            // cleanup before closing the network exchange or releasing admission.
            if (responseInput != null) {
                HttpResponseCleanupDispatcher.dispatch(io, () -> {
                    try { responseInput.close(); }
                    catch (IOException ignored) { }
                });
            }
            HttpResponseCleanupDispatcher.dispatch(io, () -> {
                try {
                    if (send) reject(raw, status); else raw.close();
                } finally {
                    synchronized (this) { transportClosed = true; }
                    release();
                }
            });
        }
        void finish() throws IOException {
            synchronized (this) {
                // An abort/deadline may win after the wire writer finishes.
                // Do not resolve the actor's respond()/finish() Future as
                // successful when the request has already been terminated.
                HttpResponseWirePhase.requireLiveFinish(terminal);
                terminal = true;
            }
            try { raw.close(); } finally { synchronized (this) { transportClosed = true; } release(); }
        }
        void observeUnpooledCarrierExit(ActorRuntime.ActorRef<?> destination) {
            // Actor DONE notifications may run while ActorRuntime still holds
            // its finalization lock. Never call this from the DONE subscriber
            // or sample the runtime while holding State's ownership lock.
            final boolean alreadyObserved;
            synchronized (this) {
                if (poolWorker != null || actor != destination) return;
                alreadyObserved = unpooledPhysicalExitObserved;
            }
            if (alreadyObserved) {
                // HTTP completion can synchronously notify guest waiters.
                // Do not call release while holding the State monitor.
                release();
                return;
            }
            final boolean exited;
            try {
                exited = destination.done().isDone()
                        && context.actors().httpCarrierQuiescent(destination);
            } catch (RuntimeException observationFailure) {
                server.failDrain(observationFailure);
                return;
            }
            if (!exited) {
                try {
                    timers.schedule(() -> observeUnpooledCarrierExit(destination),
                            10L, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException unavailable) {
                    // Never convert an unknown physical exit into successful
                    // request retirement merely because the timer closed.
                    server.failDrain(unavailable);
                }
                return;
            }
            synchronized (this) {
                if (poolWorker != null || actor != destination) return;
                unpooledPhysicalExitObserved = true;
            }
            release();
        }

        void release() {
            // Fresh handlers hold admission through actor finalization. Explicit
            // pools bound worker count separately and release closed exchanges;
            // subsequent mail still waits for the current receive to finish.
            synchronized (this) {
                // Pool reuse releases per-exchange admission after the socket
                // closes. It does NOT certify the pooled worker's carrier exit;
                // a separate listener shutdown barrier is still required.
                boolean freshActorExitedOrPoolTracked = poolWorker != null
                        || actor == null || unpooledPhysicalExitObserved;
                if (!canRetireRequest(transportClosed, dispatchInProgress,
                        freshActorExitedOrPoolTracked)) return;
                if (ticket != null) { transfers.remove(ticket); ticket = null; }
                if (deadline != null) deadline.cancel(false);
            }
            // Claim the publication lease BEFORE removing from active.
            // Otherwise a concurrent last-removal observer can incorrectly
            // see active.empty && zero in-flight publishers and drain early.
            server.releasesInProgress.incrementAndGet();
            try {
                if (server.active.remove(this)) {
                    if (poolWorker != null) poolWorker.requests.remove(this);
                    if (CorePerf.enabled()) {
                        CorePerf.elapsed(CorePerf.HTTP_LIFETIME, admittedNanos, System.nanoTime());
                    }
                    synchronized (server) {
                        server.pending.removeIf(exchange -> exchange.state == this);
                    }
                    server.admission.release();
                    server.completed.incrementAndGet();
                    completion.completeFromRuntime((long) responseStatus);
                }
            } catch (RuntimeException | Error failure) {
                server.failDrain(failure);
                throw failure;
            } finally {
                server.releasesInProgress.decrementAndGet();
                server.completeDrain();
            }
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
            synchronized (state) {
                checkOwner();
                if (ActorRuntime.inActorExecution()) throw new SecurityException("only the supervisor may dispatch an HTTP exchange");
                if (state.busy || state.committed) throw new IllegalStateException("cannot move an active HTTP exchange");
                if (!context.actors().isAlive(destination)) throw new IllegalStateException("destination actor is not alive");
                if (destination.kind() == ActorRuntime.ActorKind.UNTRUSTED) throw new SecurityException("HTTP exchange transfer to untrusted actors is not supported");
                state.movedNanos = System.nanoTime();
                state.generation++; state.owner = destination.id(); state.actor = destination;
                String token = UUID.randomUUID().toString(); state.ticket = token;
                Exchange moved = new Exchange(state, destination.id(), state.generation);
                transfers.put(token, moved);
                if (state.poolWorker == null) destination.done().whenCompleteRuntime((ignored, failure) ->
                        HttpResponseCleanupDispatcher.dispatch(io, () -> state.abort(500)));
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
                if (name == null || value == null
                        || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                        || value.chars().anyMatch(c -> c < 32 || c == 127 || c > 255))
                    throw new IllegalArgumentException("invalid HTTP header");
                if (Set.of("content-length", "transfer-encoding", "connection", "trailer", "upgrade",
                        "te", "host", "keep-alive", "proxy-connection", "http2-settings",
                        "proxy-authenticate", "proxy-authorization").contains(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("transport-owned response header");
                int fields = 1, bytes = name.length() + value.length() + 4;
                for (Map.Entry<String, List<String>> existing : state.raw.getResponseHeaders().entrySet()) {
                    if (existing.getKey().equalsIgnoreCase(name)) continue;
                    for (String item : existing.getValue()) {
                        fields++;
                        bytes += existing.getKey().length() + item.length() + 4;
                    }
                }
                if (fields > 100 || bytes > 32768)
                    throw new IllegalArgumentException("response headers exceed configured bounds");
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
            return bodyOperation(() -> {
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
            return bodyOperation(() -> {
                byte[] bytes = state.raw.getRequestBody().readNBytes(Math.min(maximum, state.server.bodyLimit - state.bodyBytes + 1));
                state.bodyBytes += bytes.length;
                if (state.bodyBytes > state.server.bodyLimit) throw new IllegalArgumentException("body too large");
                List<Object> owned = new ArrayList<>(bytes.length);
                for (byte value : bytes) owned.add((long) (value & 255));
                return owned;
            });
        }
        /**
         * Trusted host transport adapter for the planned unary HttpResponse result.
         * Caller ownership is still enforced by operation(...). The response is
         * consumed once and the stream runs on a virtual I/O worker. This does
         * not expose HttpResponseDraft to Oreslang guest code.
         */
        public OresFuture<Object> respond(HttpResponseDraft draft) {
            Objects.requireNonNull(draft, "response");
            if (!draft.streaming() && draft.bufferedLength() > state.server.bodyLimit)
                throw new IllegalArgumentException("buffered response exceeds configured limit");
            return operation(() -> {
                synchronized (state) {
                    if (state.terminal) throw new IllegalStateException("HTTP request was cancelled before response commitment");
                    if (state.committed) throw new IllegalStateException("response already started");
                    // Preflight must be non-I/O, non-consuming, and serialized
                    // with setHeader, cancellation and exchange ownership.
                    draft.preflight(state.raw);
                    draft.claim();
                    state.committed = true;
                    state.responseStatus = draft.status();
                }
                draft.writeClaimedTo(state.raw, () -> state.terminal,
                        input -> state.activeResponseInput = input,
                        () -> draft.streaming()
                                ? state.server.bodyOpenGate.open(draft, () -> state.terminal,
                                TimeUnit.MILLISECONDS.toNanos(state.server.deadlineMillis)
                                        - (System.nanoTime() - state.admittedNanos))
                                : draft.openBody(),
                        state::beginHeaders, state::noteHeadersSent);
                state.finish();
                return null;
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
                    if (state.committed) throw new IllegalStateException("response already started");
                    state.committed = true;
                    state.responseStatus = status;
                }
                boolean noBody = noBody(status);
                if (state.raw.getRequestMethod().equals("HEAD") && status != 204 && status != 205)
                    state.raw.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                if (!state.beginHeaders()) throw new IOException("HTTP request cancelled before response headers");
                state.raw.sendResponseHeaders(status, noBody || bytes.length == 0 ? -1 : bytes.length);
                state.noteHeadersSent();
                if (!noBody && bytes.length != 0) state.raw.getResponseBody().write(bytes);
                state.finish(); return null;
            });
        }
        public OresFuture<Object> start(int status, String contentType) {
            validateStatus(status); setHeader("Content-Type", contentType);
            return operation(() -> {
                synchronized (state) {
                    if (state.committed) throw new IllegalStateException("response already started");
                    state.committed = true;
                    state.responseStatus = status;
                }
                state.suppressBody = noBody(status);
                if (!state.beginHeaders()) throw new IOException("HTTP request cancelled before response headers");
                state.raw.sendResponseHeaders(status, state.suppressBody ? -1 : 0);
                state.noteHeadersSent();
                return null;
            });
        }
        public OresFuture<Object> write(String text) { return writeBuffer(text.getBytes(StandardCharsets.UTF_8)); }
        public OresFuture<Object> writeBytes(List<?> values) { return writeBuffer(byteBuffer(values, Math.min(65536, state.server.bodyLimit))); }
        private OresFuture<Object> writeBuffer(byte[] bytes) {
            if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("response chunk too large");
            return operation(() -> {
                if (!state.committed) throw new IllegalStateException("response not started");
                if (!state.suppressBody) { state.raw.getResponseBody().write(bytes); state.raw.getResponseBody().flush(); }
                return null;
            });
        }
        public OresFuture<Object> finish() { return operation(() -> { if (!state.committed) throw new IllegalStateException("response not started"); state.finish(); return null; }); }
        public void abort() { synchronized (state) { checkOwner(); } state.abort(500); }
        private boolean noBody(int status) { return state.raw.getRequestMethod().equals("HEAD") || status == 204 || status == 205 || status == 304; }
        private OresFuture<Object> bodyOperation(Callable<Object> operation) {
            return operation(operation, true);
        }
        private OresFuture<Object> operation(Callable<Object> operation) {
            return operation(operation, false);
        }
        private OresFuture<Object> operation(Callable<Object> operation, boolean requestBodyRead) {
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
                        Object value = operation.call();
                        synchronized (state) { state.busy = false; }
                        result.completeFromRuntime(value);
                    } catch (Throwable failure) {
                        synchronized (state) { state.busy = false; }
                        state.abort(operationFailureStatus(failure, requestBodyRead));
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
    @Override public void close() {
        List<Server> acceptedServers;
        List<Handler> acceptedPools;
        synchronized (this) {
            if (closed) return;
            closed = true;
            acceptedServers = List.copyOf(servers);
            acceptedPools = List.copyOf(pools);
            pools.clear();
        }
        // Never publish a listener's OresFuture failure while holding the
        // enclosing NativeHttp monitor. One failing socket cleanup must not
        // strand siblings or leave executors alive.
        RuntimeException firstFailure = null;
        try {
            for (Server server : acceptedServers) {
                try { server.close(); }
                catch (RuntimeException failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
            for (Handler pool : acceptedPools) {
                try { pool.stopPool(); }
                catch (RuntimeException failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
        } finally {
            timers.shutdownNow();
            io.shutdownNow();
            transfers.clear();
        }
        if (firstFailure != null) throw firstFailure;
    }
}
