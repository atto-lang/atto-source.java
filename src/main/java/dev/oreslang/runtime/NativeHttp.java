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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private static void configureTransportDefaults() {
        Map.of("jdk.httpserver.maxConnections", "1024",
               "sun.net.httpserver.maxReqHeaders", "100",
               "sun.net.httpserver.maxReqHeaderSize", "32768",
               "sun.net.httpserver.maxReqTime", "15",
               "sun.net.httpserver.maxRspTime", "30",
               "sun.net.httpserver.drainAmount", "1",
               "sun.net.httpserver.nodelay", "true").forEach((key, value) -> System.getProperties().putIfAbsent(key, value));
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
            if (closed) throw new IllegalStateException("HTTP runtime closed");
            int slot = cursor;
            cursor = (cursor + 1) % workers.length;
            Worker worker = workers[slot];
            if (worker != null && !worker.actor.done().isDone()) {
                if (!context.actors().isAlive(worker.actor)) throw new RejectedExecutionException("HTTP worker is stopping");
                reused++;
                return worker;
            }
            worker = new Worker(context.actors().spawnCodeActor(kind, code, type));
            workers[slot] = worker;
            created++;
            Worker attached = worker;
            worker.actor.done().whenCompleteRuntime((ignored, failure) -> {
                try {
                    // An exceptional abort must not strand later requests
                    // attached to the same terminated worker generation.
                    RuntimeException cleanupFailure = abortEveryAccepted(
                            List.copyOf(attached.requests), state -> state.abort(500));
                    if (cleanupFailure != null) throw cleanupFailure;
                } finally {
                    // Once actor.done settled, its physical carrier exited.
                    // Release both sides of listener/worker tracking even if
                    // an individual HTTP socket cleanup failed.
                    for (Server server : List.copyOf(attached.served)) {
                        server.pooledWorkers.remove(attached);
                    }
                    attached.served.clear();
                    attached.requests.clear();
                }
            });
            return worker;
        }
        private void stopPool() {
            Worker[] snapshot;
            synchronized (this) { snapshot = workers.clone(); }
            RuntimeException failure = abortEveryAccepted(
                    Arrays.stream(snapshot).filter(Objects::nonNull).toList(),
                    worker -> worker.actor.cancel());
            if (failure != null) throw failure;
        }
        private final class Worker {
            final ActorRuntime.ActorRef<Object> actor;
            final Set<State> requests = ConcurrentHashMap.newKeySet();
            final Set<Server> served = ConcurrentHashMap.newKeySet();
            Worker(ActorRuntime.ActorRef<Object> actor) { this.actor = actor; }
        }
        public void dispatch(Exchange exchange) {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("HTTP dispatch belongs to supervisor");
            }
            State state = exchange.state;
            // Retain this request's admission through worker selection/spawn.
            // The transport may time out while spawning; without this lease,
            // State.release() could remove the last active request and allow
            // shutdown to dispose the polyglot Context before the newly
            // admitted actor's physical carrier exits.
            synchronized (state) {
                if (!mayBeginDispatch(closed, state.server.stopped)) {
                    throw new IllegalStateException("HTTP runtime or listener is closing");
                }
                exchange.checkOwner();
                if (state.dispatchInProgress) {
                    throw new IllegalStateException("HTTP exchange already dispatching");
                }
                state.dispatchInProgress = true;
                state.spawnNanos = System.nanoTime();
            }
            long perf = CorePerf.start();
            try {
                if (workers != null) {
                    try {
                        Worker worker = worker();
                        synchronized (state) {
                            // Track the worker even if a concurrent timeout
                            // already invalidated this exchange during worker().
                            // Final dispatch-lease release keeps the request
                            // active until the server sees this incarnation.
                            state.poolWorker = worker;
                            worker.requests.add(state);
                            state.server.pooledWorkers.add(worker);
                            worker.served.add(state.server);
                            try {
                                if (!mayBeginDispatch(closed, state.server.stopped)) {
                                    throw new IllegalStateException("HTTP listener closed during dispatch");
                                }
                                exchange.checkOwner();
                                state.readyNanos = state.spawnNanos;
                                worker.actor.send(exchange.moveTo(worker.actor));
                            } finally {
                                // A previously retired worker can be selected
                                // just as a timeout revokes the exchange.
                                // Check even when move/send throws, or the
                                // server retains the dead generation forever.
                                if (worker.actor.done().isDone()) {
                                    state.server.pooledWorkers.remove(worker);
                                    worker.served.remove(state.server);
                                }
                            }
                        }
                    } catch (RuntimeException failure) {
                        state.abort(500, false);
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
                    actor.ready().whenCompleteRuntime((ignored, failure) -> {
                        long spawn;
                        long ready;
                        synchronized (state) {
                            state.readyNanos = System.nanoTime();
                            spawn = state.spawnNanos;
                            ready = state.readyNanos;
                        }
                        CorePerf.elapsed(CorePerf.ACTOR_READY, spawn, ready);
                    });
                    synchronized (state) {
                        // Attach the new actor BEFORE checking the ticket.
                        // If timeout won during spawn, its finalization still
                        // fences State.release and therefore server shutdown.
                        state.actor = spawned;
                        boolean moved = false;
                        try {
                            String token = exchange.moveTo(spawned);
                            moved = true; // moveTo installed actor.done callback
                            spawned.send(token);
                        } catch (RuntimeException failure) {
                            if (!moved) {
                                spawned.done().whenCompleteRuntime(
                                        (ignored, terminal) -> state.abort(500));
                            }
                            throw failure;
                        }
                    }
                } catch (RuntimeException failure) {
                    if (actor != null) actor.cancel();
                    state.abort(500);
                }
            } finally {
                synchronized (state) { state.dispatchInProgress = false; }
                // May retire the request ONLY after worker/actor association
                // has become visible. A timed-out unpooled actor additionally
                // waits for its physical finalization before release.
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
        private final int bodyLimit, deadlineMillis;
        private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
        private final Set<State> active = ConcurrentHashMap.newKeySet();
        // State.release removes admission before publishing the completion future.
        // Keep shutdown pending until every such publication has returned.
        private final AtomicInteger releasesInProgress = new AtomicInteger();
        private final Set<Handler.Worker> pooledWorkers = ConcurrentHashMap.newKeySet();
        private OresFuture<Object> waiter;
        private volatile boolean stopped;
        // A graceful shutdown is distinct from a forced close; only the
        // first forced closer may tear down the JDK transport and state.
        private final AtomicBoolean forcedCloseClaimed = new AtomicBoolean();
        private boolean transportStopped, pooledBarrierStarted;
        // Arbitration is under this listener's monitor, but Future callbacks
        // must be invoked only after dropping that monitor. A forced close
        // therefore cannot race an already-selected success into the promise.
        private boolean drainOutcomeClaimed;
        private OresFuture<Object> drained;
        private final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong(), completed = new AtomicLong();

        private Server(HttpServer transport, int capacity, int bodyLimit, int deadlineMillis) {
            this.transport = transport; this.admission = new Semaphore(capacity);
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
                OresFuture<Object> acceptWaiter = null;
                Exchange acceptedExchange = null;
                boolean refused;
                synchronized (this) {
                    refused = stopped;
                    if (!refused) {
                        active.add(state); accepted.incrementAndGet();
                        State timed = state;
                        long schedulePerf = CorePerf.start();
                        try {
                            state.deadline = timers.schedule(
                                    () -> timed.abort(504), deadlineMillis, TimeUnit.MILLISECONDS);
                        } finally {
                            CorePerf.end(CorePerf.HTTP_TIMER_SCHEDULE, schedulePerf);
                        }
                        Exchange exchange = new Exchange(state, context, 0);
                        if (waiter == null) pending.add(exchange);
                        else {
                            acceptWaiter = waiter;
                            waiter = null;
                            acceptedExchange = exchange;
                        }
                    }
                }
                if (refused) {
                    admission.release();
                    reject(raw, 503);
                    return;
                }
                // Future runtime subscribers execute synchronously. Publish
                // after the listener monitor has been released: otherwise a
                // continuation that requires another listener operation can
                // deadlock an HTTP receive carrier.
                if (acceptWaiter != null) {
                    publishOutsideMonitor(this, acceptWaiter, acceptedExchange);
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
            OresFuture<Object> acceptToFail;
            OresFuture<Object> result;
            synchronized (this) {
                if (drained != null) return drained;
                // Server.close() may have already completed forced teardown
                // without a guest shutdown Future. Never resurrect shutdown
                // work or claim a successful drain for that closed listener.
                if (stopped) return OresFuture.failed(
                        new IllegalStateException("HTTP listener already closed"));
                drained = new OresFuture<>(() -> false, () -> { });
                result = drained;
                acceptToFail = stopAcceptingLocked();
            }
            // Failing the outstanding accept Future invokes host subscribers
            // synchronously. They must never run inside Server's monitor.
            if (acceptToFail != null) {
                failOutsideMonitor(this, acceptToFail,
                        new IllegalStateException("HTTP server closed"));
            }
            try {
                io.execute(() -> {
                    RuntimeException stopFailure = null;
                    try {
                        transport.stop((int) (((long) graceMillis + 999) / 1000));
                    } catch (RuntimeException failure) {
                        stopFailure = failure;
                    } finally {
                        // Publish the transport failure BEFORE making the
                        // transportStopped flag visible to concurrent release
                        // callbacks. Otherwise the last State.release() could
                        // settle drained successfully before this thread
                        // reports the failure.
                        if (stopFailure != null) failDrain(stopFailure);
                        // Do not publish transportStopped until every accepted
                        // exchange has been visited by abort cleanup. Otherwise
                        // an asynchronous State.release() can finish a successful
                        // shutdown while a later abort still reports failure.
                        finishShutdownCleanup(this::abortAllActiveOnShutdown, () -> {
                            synchronized (this) { transportStopped = true; }
                            servers.remove(this);
                            completeDrain();
                        });
                    }
                });
            } catch (RejectedExecutionException unavailable) {
                // The HTTP executor can close after the drain Future is
                // published but before the transport-stop task is admitted.
                // Never strand awaiters on a stop operation that cannot run.
                failDrain(unavailable);
            }
            return result;
        }
        private void completeDrain() {
            List<Handler.Worker> workers;
            boolean skipBarrier;
            synchronized (this) {
                if (!readyForCarrierBarrier(
                        transportStopped, active.isEmpty(), releasesInProgress.get())
                        || drained == null || pooledBarrierStarted) {
                    return;
                }
                pooledBarrierStarted = true;
                // Claim the worker snapshot under this listener's monitor,
                // never settle an OresFuture or subscribe a runtime callback
                // while holding it. Stop/close can otherwise deadlock callbacks.
                workers = List.copyOf(pooledWorkers);
                pooledWorkers.clear();
                skipBarrier = drainOutcomeClaimed || drained.isDone();
            }
            for (Handler.Worker worker : workers) worker.served.remove(this);
            // Always retire references, including after exceptional teardown.
            if (skipBarrier) return;
            if (workers.isEmpty()) {
                succeedDrain();
                return;
            }
            // The runtime processes a bounded HTTP deadline, but a user actor
            // may keep executing after the response. Fail explicitly rather
            // than falsely reporting shutdown and racing Context.close().
            long waitMillis = Math.max(1_000L,
                    Math.min(30_000L, Math.max((long) deadlineMillis, 0L)));
            final ScheduledFuture<?> timeout;
            try {
                timeout = timers.schedule(() -> failDrain(
                        new TimeoutException("HTTP pooled actor carrier did not quiesce")),
                        waitMillis, TimeUnit.MILLISECONDS);
                // The actor's physical-quiescence Future may outlive a timed-
                // out listener indefinitely. Detach its runtime callbacks as
                // soon as the listener's drain settles, so stalled actors
                // cannot retain server shutdown closures or old listeners.
                var subscriptions = new ConcurrentLinkedQueue<
                        OresFuture.RuntimeWaiterRegistration<Void>>();
                drained.whenCompleteRuntime((ignored, failure) -> {
                    timeout.cancel(false);
                    for (var registration : subscriptions) registration.detach();
                    subscriptions.clear();
                });
                io.execute(() -> {
                    AtomicInteger pending = new AtomicInteger(workers.size());
                    AtomicReference<Throwable> firstError = new AtomicReference<>();
                    for (Handler.Worker worker : workers) {
                        if (drained.isDone()) break;
                        try {
                            OresFuture<Void> quiescence =
                                    context.actors().quiesceAcceptedMessages(worker.actor);
                            var registration = quiescence.whenCompleteRuntimeCancellable(
                                    (ignored, failure) -> {
                                        if (failure != null) {
                                            firstError.compareAndSet(null, OresFuture.unwrap(failure));
                                        }
                                        if (pending.decrementAndGet() == 0) {
                                            Throwable error = firstError.get();
                                            if (error == null) succeedDrain();
                                            else failDrain(error);
                                        }
                                    });
                            subscriptions.add(registration);
                            // Covers settlement between the drain callback's
                            // iteration and this new registration's enqueue.
                            if (drained.isDone()) registration.detach();
                        } catch (RuntimeException failure) {
                            firstError.compareAndSet(null, failure);
                            if (pending.decrementAndGet() == 0) {
                                failDrain(firstError.get());
                            }
                        }
                    }
                });
            } catch (RejectedExecutionException unavailable) {
                failDrain(unavailable);
            }
        }

        /** Reserve successful shutdown atomically against a concurrent forced close. */
        private void succeedDrain() {
            OresFuture<Object> completion;
            synchronized (this) {
                if (forcedCloseClaimed.get() || drainOutcomeClaimed || drained == null) return;
                drainOutcomeClaimed = true;
                completion = drained;
            }
            completion.completeFromRuntime(null);
        }

        /** Reserve exceptional shutdown without invoking subscribers under the monitor. */
        private void failDrain(Throwable failure) {
            OresFuture<Object> completion;
            synchronized (this) {
                if (drainOutcomeClaimed || drained == null) return;
                drainOutcomeClaimed = true;
                completion = drained;
            }
            completion.failFromRuntime(failure);
        }

        /**
         * Exhaustive shutdown cleanup: ConcurrentHashMap's weakly consistent
         * iterator is unnecessary once admission is sealed. Snapshot all
         * accepted states and continue aborting after an individual failure.
         * A failed abort must never let an unrelated active request escape
         * cleanup or make the shutdown Future appear successful.
         */
        private void abortAllActiveOnShutdown() {
            RuntimeException firstFailure = abortEveryAccepted(
                    List.copyOf(active), state -> state.abort(503));
            if (firstFailure != null) failDrain(firstFailure);
        }

        /** Caller holds this server's monitor; never settle the waiter here. */
        private OresFuture<Object> stopAcceptingLocked() {
            stopped = true;
            OresFuture<Object> pendingAccept = waiter;
            waiter = null;
            pending.clear();
            return pendingAccept;
        }
        @Override public void close() {
            // Claim exactly once, even if multiple supervisors/teardown paths
            // race on the same listener. Duplicate callers must not stop a
            // transport or re-abort already released state a second time.
            OresFuture<Object> acceptToFail;
            OresFuture<Object> drainToFail = null;
            synchronized (this) {
                if (!claimForcedClose(forcedCloseClaimed)) return;
                acceptToFail = stopAcceptingLocked();
                // Seal failure atomically with forced close, but publish
                // outside the monitor so host callbacks never run locked.
                if (drained != null && !drainOutcomeClaimed) {
                    drainOutcomeClaimed = true;
                    drainToFail = drained;
                }
            }
            if (drainToFail != null) {
                drainToFail.failFromRuntime(new IllegalStateException(
                        "HTTP runtime closed before pooled actor quiescence"));
            }
            if (acceptToFail != null) {
                failOutsideMonitor(this, acceptToFail,
                        new IllegalStateException("HTTP server closed"));
            }
            try {
                transport.stop(0);
            } finally {
                finishShutdownCleanup(this::abortAllActiveOnShutdown, () -> {
                    synchronized (this) { transportStopped = true; }
                    // The listener must release its bidirectional references
                    // even when stop or an abort fails exceptionally.
                    for (Handler.Worker worker : List.copyOf(pooledWorkers)) {
                        worker.served.remove(this);
                    }
                    pooledWorkers.clear();
                    servers.remove(this);
                    completeDrain();
                });
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
        /** Dispatch owns admission until actor selection, registration and send finish. */
        boolean dispatchInProgress;
        State(Server server, HttpExchange raw) {
            this.server = server; this.raw = raw; this.path = segments(raw.getRequestURI().getRawPath()); this.owner = context;
        }
        void abort(int status) { abort(status, true); }
        void abort(int status, boolean cancelOwner) {
            boolean send, alreadyClosed;
            synchronized (this) {
                alreadyClosed = terminal;
                send = !terminal && !committed && !busy;
                if (!terminal) responseStatus = send ? status : 0;
                terminal = true;
            }
            // Never perform socket I/O or actor cancellation while holding the ownership monitor.
            if (alreadyClosed && poolWorker != null) { release(); return; }
            // A pooled actor can serve unrelated listeners and concurrent
            // requests. Aborting one exchange must NEVER cancel that worker.
            // Fresh per-request actors, however, are owned by the exchange.
            // Always close/release the socket even if cancellation fails.
            runCancellationThenCleanup(
                    () -> {
                        if (actor != null && shouldCancelActorForAbort(
                                cancelOwner, poolWorker != null)) actor.cancel();
                    },
                    () -> {
                        if (alreadyClosed) { release(); return; }
                        try {
                            io.execute(() -> {
                                try { if (send) reject(raw, status); else raw.close(); }
                                finally {
                                    synchronized (this) { transportClosed = true; }
                                    release();
                                }
                            });
                        } catch (RejectedExecutionException closed) {
                            // An executor rejection must not strand admission.
                            retireRejectedAbort(raw::close, () -> {
                                synchronized (this) { transportClosed = true; }
                                release();
                            });
                        }
                    });
        }
        void finish() {
            synchronized (this) { if (terminal) return; terminal = true; }
            try { raw.close(); } finally { synchronized (this) { transportClosed = true; } release(); }
        }
        void release() {
            // Fresh handlers hold admission through actor finalization. Explicit
            // pools bound worker count separately and release closed exchanges;
            // subsequent mail still waits for the current receive to finish.
            synchronized (this) {
                if (!canRetireRequest(transportClosed, dispatchInProgress,
                        poolWorker == null && actor != null && !actor.done().isDone())) return;
                if (ticket != null) { transfers.remove(ticket); ticket = null; }
                if (deadline != null) deadline.cancel(false);
            }
            // Increment BEFORE removing the last active request: an unrelated
            // shutdown thread must not see active.isEmpty() while the request's
            // completion callback is still being published. Even duplicate
            // release attempts participate in this short-lived fence.
            server.releasesInProgress.incrementAndGet();
            try {
                if (server.active.remove(this)) {
                    if (poolWorker != null) poolWorker.requests.remove(this);
                    if (CorePerf.enabled()) {
                        CorePerf.elapsed(CorePerf.HTTP_LIFETIME, admittedNanos, System.nanoTime());
                    }
                    synchronized (server) { server.pending.removeIf(exchange -> exchange.state == this); }
                    server.admission.release();
                    server.completed.incrementAndGet();
                    completion.completeFromRuntime((long) responseStatus);
                }
            } finally {
                server.releasesInProgress.decrementAndGet();
                // The final release, not active.remove(), is the point at which
                // the shutdown barrier can safely observe request completion.
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
                if (!mayBeginDispatch(closed, state.server.stopped)) {
                    throw new IllegalStateException("HTTP runtime or listener is closing");
                }
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
                if (state.poolWorker == null) destination.done().whenCompleteRuntime((ignored, failure) -> state.abort(500));
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
                if (!state.committed) throw new IllegalStateException("response not started");
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

    /**
     * Transport shutdown is safe to advance to actor quiescence only after
     * all accepted requests have left the completion-publication section.
     * active.isEmpty() alone is insufficient because State.release removes
     * its state before publishing the completion callback.
     */
    static boolean readyForCarrierBarrier(
            boolean transportStopped, boolean activeEmpty, int releasesInProgress) {
        return transportStopped && activeEmpty && releasesInProgress == 0;
    }

    /** Exactly-once forced listener close; independent from graceful shutdown. */
    static boolean claimForcedClose(AtomicBoolean owner) {
        Objects.requireNonNull(owner, "owner");
        return owner.compareAndSet(false, true);
    }

    /** A pooled worker is shared ownership: one aborted HTTP exchange cannot cancel it. */
    static boolean shouldCancelActorForAbort(boolean cancelOwner, boolean pooledWorker) {
        return cancelOwner && !pooledWorker;
    }

    /**
     * Even a failing actor cancellation must not strand the socket and request
     * admission. Preserve cancellation failure and suppress cleanup failure.
     */
    static void runCancellationThenCleanup(Runnable cancel, Runnable cleanup) {
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(cleanup, "cleanup");
        RuntimeException cancellationFailure = null;
        try { cancel.run(); }
        catch (RuntimeException failure) { cancellationFailure = failure; }
        try { cleanup.run(); }
        catch (RuntimeException cleanupFailure) {
            if (cancellationFailure == null) throw cleanupFailure;
            if (cancellationFailure != cleanupFailure) {
                cancellationFailure.addSuppressed(cleanupFailure);
            }
        }
        if (cancellationFailure != null) throw cancellationFailure;
    }

    /** Only an active runtime/listener can admit a new actor transfer. */
    static boolean mayBeginDispatch(boolean runtimeClosed, boolean listenerStopping) {
        return !runtimeClosed && !listenerStopping;
    }

    /**
     * Do not publish a stopped transport (and allow a successful drain) until
     * every accepted exchange has been visited by cleanup. The publication
     * action still runs on failed cleanup to unblock exceptional teardown.
     */
    static void finishShutdownCleanup(Runnable cleanup, Runnable publishStopped) {
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(publishStopped, "publishStopped");
        try { cleanup.run(); }
        finally { publishStopped.run(); }
    }

    /**
     * The ownership linearization rule shared by pooled and fresh dispatch:
     * I/O close alone cannot retire admission while a worker is being chosen
     * or a newly spawned non-pooled actor has not physically finalized.
     */
    static boolean canRetireRequest(
            boolean transportClosed,
            boolean dispatchInProgress,
            boolean pendingFreshActorFinalization) {
        return transportClosed && !dispatchInProgress && !pendingFreshActorFinalization;
    }

    /**
     * Terminal fallback for a rejected I/O task. The completion action must
     * release admission even if the underlying socket close throws.
     * Package-visible so regression tests exercise the production ordering.
     */
    static void retireRejectedAbort(Runnable closeTransport, Runnable finalizeTransport) {
        Objects.requireNonNull(closeTransport);
        Objects.requireNonNull(finalizeTransport);
        try { closeTransport.run(); }
        finally { finalizeTransport.run(); }
    }

    /**
     * Best-effort exhaustive cancellation with first-error precedence.
     * No failed abort may prevent another previously accepted state from
     * being visited; subsequent errors are preserved as suppressed.
     */
    static <T> RuntimeException abortEveryAccepted(
            List<T> accepted, java.util.function.Consumer<? super T> abort) {
        Objects.requireNonNull(accepted);
        Objects.requireNonNull(abort);
        RuntimeException first = null;
        for (T state : accepted) {
            try { abort.accept(state); }
            catch (RuntimeException failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        return first;
    }

    /** Guard the producer side of HTTP accept notifications against lock re-entry. */
    static <T> void publishOutsideMonitor(
            Object monitor, OresFuture<T> observer, T value) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(observer, "observer");
        if (Thread.holdsLock(monitor)) {
            throw new IllegalStateException(
                    "HTTP accept Future cannot settle while listener monitor is held");
        }
        observer.completeFromRuntime(value);
    }

    /** Mirror accept publication's lock boundary for pending accept failures. */
    static <T> void failOutsideMonitor(
            Object monitor, OresFuture<T> observer, Throwable failure) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(failure, "failure");
        if (Thread.holdsLock(monitor)) {
            throw new IllegalStateException(
                    "HTTP accept Future cannot fail while listener monitor is held");
        }
        observer.failFromRuntime(failure);
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
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            // Context teardown must not stop after one listener or actor pool
            // throws during cleanup. Otherwise other transports and actors
            // remain live while the surrounding polyglot Context is closing.
            RuntimeException serverFailure = abortEveryAccepted(
                    List.copyOf(servers), Server::close);
            RuntimeException poolFailure = abortEveryAccepted(
                    List.copyOf(pools), Handler::stopPool);
            failure = serverFailure != null ? serverFailure : poolFailure;
            if (serverFailure != null && poolFailure != null
                    && serverFailure != poolFailure) {
                serverFailure.addSuppressed(poolFailure);
            }
        } finally {
            // Always release host executors and capability tables even if
            // a transport or actor cancellation unexpectedly failed.
            pools.clear();
            timers.shutdownNow();
            io.shutdownNow();
            transfers.clear();
        }
        if (failure != null) throw failure;
    }
}
