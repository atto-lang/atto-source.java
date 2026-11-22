package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host CONTROL-owned integration between a process-stable NativeHttp listener
 * and independently versioned, pooled HTTP actors.
 *
 * <p>Each accepted HTTP/1.1 request captures an immutable generation lease
 * before dispatch. The lease is held until the exchange finishes, not until
 * the handler is scheduled. A failed/late candidate never changes the route.
 * This is NOT proof of actor/Task/native-I/O physical quiescence: the supervisor
 * must separately certify those lifetimes before acknowledging retirement.</p>
 *
 * <p>The control executor is intentionally independent of actor carrier
 * threads; it installs one pending accept at a time. No route locks are held
 * during handler dispatch, future callbacks or guest work.</p>
 */
public final class NativeHttpRollingDispatcher implements AutoCloseable {
    private final NativeHttp.Server listener;
    private final ActorRollingGenerationRoute<NativeHttp.Handler> routes;
    private final ExecutorService control = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("ores-http-rolling-control", 0).factory());
    private final Set<NativeHttp.Handler> staged =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> fatal = new AtomicReference<>();

    /** Handler must be a supervisor-created, pooled handler. */
    public NativeHttpRollingDispatcher(NativeHttp.Server listener,
                                       long logicalId,
                                       NativeHttp.Handler initialReadyHandler,
                                       int maximumLiveGenerations) {
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(initialReadyHandler, "initialReadyHandler");
        if (listener.ownerHttp() != initialReadyHandler.ownerHttp())
            throw new SecurityException("foreign HTTP handler/acceptor context");
        if (!initialReadyHandler.pooled() || !initialReadyHandler.readyNow())
            throw new IllegalArgumentException("initial rolling HTTP pool must be warmed and ready");
        this.routes = new ActorRollingGenerationRoute<>(
                logicalId, initialReadyHandler, maximumLiveGenerations);
        this.listener.claimRollingDispatcher(this);
    }

    public int port() { return listener.port(); }
    public ActorRollingGenerationRoute.Snapshot snapshot() { return routes.snapshot(); }
    public java.util.List<Long> drainingEpochs() { return routes.drainingEpochs(); }
    public boolean admissionsDrained(long epoch) { return routes.admissionsDrained(epoch); }
    public Throwable failure() { return fatal.get(); }

    /** Starts accept admission without ever re-binding the listening socket. */
    public void start() {
        requireSupervisor();
        if (closed.get()) throw new IllegalStateException("rolling dispatcher is closed");
        if (!started.compareAndSet(false, true))
            throw new IllegalStateException("rolling dispatcher already started");
        submit(this::acceptOne);
    }

    /**
     * Stage the target by starting all its pooled workers and awaiting each
     * real actor get_ready() receipt. A failed stage cannot update the route.
     * If the compare-and-switch loses, the unpublished pool is cancelled.
     */
    OresFuture<Boolean> promoteAfterWarmup(long expectedEpoch,
                                                   NativeHttp.Handler replacement) {
        requireSupervisor();
        Objects.requireNonNull(replacement, "replacement");
        if (closed.get() || !started.get())
            throw new IllegalStateException("rolling dispatcher not serving");
        if (listener.ownerHttp() != replacement.ownerHttp())
            throw new SecurityException("foreign HTTP reload candidate");
        if (!replacement.pooled()) throw new IllegalArgumentException("replacement must be pooled");
        synchronized (staged) {
            if (staged.size() >= 8) throw new RejectedExecutionException("too many staging HTTP generations");
            if (routes.containsTarget(replacement) || !staged.add(replacement))
                throw new IllegalArgumentException("worker pool already active, draining, or staging");
        }
        OresFuture<Boolean> outcome = new OresFuture<>();
        try {
            replacement.warmup().whenCompleteRuntime((ignored, error) -> {
                try {
                    submit(() -> {
                        try {
                            if (error != null) throw new IllegalStateException(
                                    "replacement worker startup failed", OresFuture.unwrap(error));
                            if (closed.get()) throw new IllegalStateException("rolling dispatcher closed");
                            if (!replacement.readyNow())
                                throw new IllegalStateException("replacement HTTP workers not healthy");
                            boolean promoted = routes.publishReady(expectedEpoch, replacement);
                            if (!promoted) replacement.stopUnpublishedPool();
                            outcome.completeFromRuntime(promoted);
                        } catch (RuntimeException | Error failure) {
                            replacement.stopUnpublishedPool();
                            outcome.failFromRuntime(failure);
                        } finally {
                            staged.remove(replacement);
                        }
                    });
                } catch (RuntimeException | Error rejected) {
                    // The completion may run on an actor carrier. Never do
                    // privileged pool shutdown on that carrier.
                    Thread.ofVirtual().start(() -> {
                        try { replacement.stopUnpublishedPool(); }
                        finally { staged.remove(replacement); outcome.failFromRuntime(rejected); }
                    });
                }
            });
        } catch (RuntimeException | Error failure) {
            try { replacement.stopUnpublishedPool(); }
            finally { staged.remove(replacement); outcome.failFromRuntime(failure); }
        }
        return outcome;
    }

    /**
     * Called by the trusted transaction owner when a candidate was created
     * but rejected BEFORE the dispatcher accepted it for warming. Never
     * cancel a pool which is already serving or being staged elsewhere.
     */
    void discardRejectedCandidate(NativeHttp.Handler candidate) {
        requireSupervisor();
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.ownerHttp() != listener.ownerHttp()) return;
        synchronized (staged) {
            if (staged.contains(candidate) || routes.containsTarget(candidate)) return;
            candidate.stopUnpublishedPool();
        }
    }

    /**
     * Close admission already happened at route promotion. Only once the
     * old exchange count is zero may the supervisor ask old actors to stop;
     * Task/FFI/I/O and code lifetimes still require independent proof.
     */
    public boolean requestDrainAfterAdmissions(long epoch) {
        requireSupervisor();
        if (!routes.admissionsDrained(epoch)) return false;
        NativeHttp.Handler old = routes.drainingTarget(epoch);
        if (old == null) return false;
        old.requestGracefulPoolStop();
        return true;
    }

    /**
     * DISABLED UNTIL PHYSICAL QUIESCENCE EVIDENCE EXISTS.
     *
     * Exchange completion and ActorRef.done() cannot prove that callbacks,
     * suspended Task frames, FFI loans, native I/O, or code-image consumers
     * have released the old physical generation. The old implementation
     * accepted a caller's unverified assertion and could drop the last host
     * registry reference. Fail CLOSED: keep the draining route and pool
     * retained rather than risk use-after-free.
     *
     * A later integration must replace this API with a one-shot receipt
     * validated against this exact epoch and target under the route lock;
     * the receipt must come from the actual physical lifetime authority.
     */
    @Deprecated(forRemoval = true)
    public boolean acknowledgePhysicallyRetired(long epoch) {
        requireSupervisor();
        throw new IllegalStateException(
                "physical-retirement proof unavailable: generation retained/quarantined");
    }

    private void acceptOne() {
        if (closed.get()) return;
        try {
            listener.acceptForRolling(this).whenCompleteRuntime((raw, failure) -> {
                if (failure != null) {
                    if (!closed.get()) fail(OresFuture.unwrap(failure));
                    return;
                }
                if (!(raw instanceof NativeHttp.Exchange exchange)) {
                    fail(new IllegalStateException("HTTP accept yielded a non-exchange"));
                    return;
                }
                try { submit(() -> dispatchOne(exchange)); }
                catch (RuntimeException | Error rejected) {
                    abort(exchange);
                    fail(rejected);
                }
            });
        } catch (RuntimeException | Error failure) { fail(failure); }
    }

    private void dispatchOne(NativeHttp.Exchange exchange) {
        try {
            if (closed.get()) { abort(exchange); return; }
            ActorRollingGenerationRoute<NativeHttp.Handler>.Lease lease = null;
            boolean completionAttached = false;
            try {
                lease = routes.acquire();
                NativeHttp.Handler target = lease.target();
                // Register exchange completion before moving exchange to actor.
                OresFuture<Object> done = exchange.completion();
                var pinned = lease;
                done.whenCompleteRuntime((ignored, failure) -> pinned.close());
                completionAttached = true;
                if (!done.isDone()) target.dispatch(exchange);
            } catch (RuntimeException | Error error) {
                if (!completionAttached && lease != null) lease.close();
                // A timed-out request or failed handler is request-local. Do
                // not take the listener offline for an exchange failure.
                abort(exchange);
            }
        } finally {
            if (!closed.get()) acceptOne();
        }
    }

    private void abort(NativeHttp.Exchange exchange) {
        try { exchange.abortFromRollingDispatcher(this); }
        catch (RuntimeException ignored) { /* listener already shutting down */ }
    }

    private void submit(Runnable command) {
        if (closed.get()) throw new RejectedExecutionException("rolling dispatcher closed");
        control.execute(command);
    }

    private void fail(Throwable cause) {
        if (fatal.compareAndSet(null, cause)) {
            // May be invoked by an HTTP accept callback while holding the
            // server monitor. Never synchronously stop transport inside it.
            Thread.ofVirtual().start(this::close);
        }
    }

    private static void requireSupervisor() {
        if (ActorRuntime.inActorExecution())
            throw new SecurityException("HTTP rolling reload is CONTROL-owned");
    }

    /** Explicit service shutdown is distinct from rolling promotion/drain. */
    @Override public void close() {
        requireSupervisor();
        if (!closed.compareAndSet(false, true)) return;
        routes.close();
        listener.close();
        listener.releaseRollingDispatcher(this);
        control.shutdown();
    }
}
