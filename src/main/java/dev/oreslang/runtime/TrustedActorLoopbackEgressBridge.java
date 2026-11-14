package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EXPERIMENTAL trusted-host-bound actor-to-egress ingress, not a guest language
 * import or a general NETWORK grant. Each instance binds one real untrusted
 * ActorRef to a host-selected literal 127.0.0.1 endpoint and host-owned
 * child/supervisor/tenant quotas. No guest-supplied actor ID, URL, scope,
 * clock, transport handle, callback or executor is accepted.
 *
 * Host completions update only private atomic state. Never complete a
 * guest-controlled CompletableFuture from a trusted host executor: its
 * synchronous callbacks would run with host authority. Guest turns receive
 * only bounded numeric tickets and immutable polled results.
 *
 * Host-side Java actor test is distinct from a Graal protected-guest test.
 */
final class TrustedActorLoopbackEgressBridge implements AutoCloseable {
    enum State { PENDING, OK, FAILED, CANCELLED }
    record Poll(State state, int httpStatus) {
        Poll { Objects.requireNonNull(state); }
    }

    private static final int MAX_TICKETS = 16;
    // Reserve one cleanup slot per live binding, BEFORE constructing HTTP
    // resources. Consequently a finalizer can always enqueue exactly one
    // cleanup without blocking a runtime lifecycle thread or allocating an
    // unbounded number of host tasks.
    private static final int MAX_LIVE_BINDINGS = 64;
    private static final Semaphore CLEANUP_SLOTS = new Semaphore(MAX_LIVE_BINDINGS);
    private static final ArrayBlockingQueue<TrustedActorLoopbackEgressBridge>
            CLEANUP_QUEUE = new ArrayBlockingQueue<>(MAX_LIVE_BINDINGS);
    // Preserve failed broker instances and their reserved slots for a later
    // host-only retry. Never silently lose a failed cleanup and its capacity.
    private static final Set<TrustedActorLoopbackEgressBridge> CLEANUP_QUARANTINE =
            ConcurrentHashMap.newKeySet();
    private static final System.Logger LOG =
            System.getLogger(TrustedActorLoopbackEgressBridge.class.getName());

    static {
        var cleaner = new Thread(() -> {
            for (;;) {
                try {
                    CLEANUP_QUEUE.take().finishQueuedCleanup();
                } catch (InterruptedException interrupted) {
                    // No untrusted code controls this dedicated daemon. Keep
                    // cleanup available across spurious worker interrupts.
                } catch (Throwable failure) {
                    LOG.log(System.Logger.Level.ERROR, "host egress cleanup worker failure", failure);
                }
            }
        }, "oreslang-trusted-egress-cleanup");
        cleaner.setDaemon(true);
        cleaner.start();
    }
    // Only the host retains bridge objects and their privileged scopes.
    private record ActorKey(ActorRuntime runtime, ActorRuntime.ActorId id) { }
    private static final ConcurrentHashMap<ActorKey, TrustedActorLoopbackEgressBridge>
            HOST_BINDINGS = new ConcurrentHashMap<>();
    private final ActorKey bindingKey;
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorRef<?> actor;
    private final TrustedHttpLoopbackProbe broker;
    private final Executor hostDispatcher;
    private final ConcurrentHashMap<Long, AtomicReference<Poll>> tickets =
            new ConcurrentHashMap<>();
    private final AtomicLong nextTicket = new AtomicLong();
    private volatile boolean closed;
    private final AtomicBoolean cleanupQueued = new AtomicBoolean();
    private final AtomicBoolean cleanupSlotReleased = new AtomicBoolean();

    TrustedActorLoopbackEgressBridge(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor,
            TrustedHierarchicalNetworkGate gate,
            TrustedHierarchicalNetworkGate.Scope child,
            TrustedHierarchicalNetworkGate.Scope supervisor,
            TrustedHierarchicalNetworkGate.Scope tenant,
            int fixedLoopbackPort,
            Executor hostDispatcher) {
        requireHost();
        this.runtime = Objects.requireNonNull(runtime);
        this.actor = Objects.requireNonNull(actor);
        if (actor.kind() != ActorRuntime.ActorKind.UNTRUSTED || !runtime.isAlive(actor)) {
            throw new IllegalArgumentException("only a live untrusted actor may bind");
        }
        this.bindingKey = new ActorKey(this.runtime, this.actor.id());
        this.hostDispatcher = Objects.requireNonNull(hostDispatcher);
        if (!CLEANUP_SLOTS.tryAcquire()) {
            throw new IllegalStateException("host egress binding/cleanup capacity exhausted");
        }
        try {
            this.broker = new TrustedHttpLoopbackProbe(
                    gate, child, supervisor, tenant, fixedLoopbackPort);
        } catch (RuntimeException | Error failure) {
            CLEANUP_SLOTS.release();
            throw failure;
        }
        if (HOST_BINDINGS.putIfAbsent(bindingKey, this) != null) {
            try {
                broker.close();
                releaseCleanupSlot();
            } catch (RuntimeException | Error failure) {
                enqueueTerminatedCleanup();
                throw failure;
            }
            throw new IllegalStateException("untrusted actor already has a host egress binding");
        }
        // A completion callback may execute synchronously if already done.
        // Registration failures must not leak host authority or cleanup slots.
        try {
            actor.done().whenCompleteRuntime((ignored, failure) -> revokeAfterTermination());
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                closed = true;
                broker.fenceNewDispatch();
                HOST_BINDINGS.remove(bindingKey, this);
                tickets.clear();
            }
            try {
                broker.close();
                releaseCleanupSlot();
            } catch (RuntimeException | Error cleanupFailure) {
                enqueueTerminatedCleanup();
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Called only through the explicitly selected actor intrinsic/host seam.
     * No user-controlled identity or arbitrary URL is accepted.
     * Admission is bounded before enqueuing on a trusted host executor.
     */
    static long submitGetFromCurrentActor() {
        return lookupCurrentBinding().submitBoundRequest();
    }

    static Poll pollFromCurrentActor(long id) {
        return lookupCurrentBinding().pollBoundTicket(id);
    }

    private static TrustedActorLoopbackEgressBridge lookupCurrentBinding() {
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        var id = ActorRuntime.currentActorId();
        if (ActorRuntime.currentActorKind() != ActorRuntime.ActorKind.UNTRUSTED
                || runtime == null || id.isEmpty()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("egress requires authenticated untrusted actor turn");
        }
        var found = HOST_BINDINGS.get(new ActorKey(runtime, id.orElseThrow()));
        if (found == null) throw new SecurityException("actor has no host-issued egress binding");
        return found;
    }

    private synchronized long submitBoundRequest() {
        requireBoundActorTurn();
        if (closed) throw new IllegalStateException("egress binding closed");
        if (!actor.isAlive()) throw new SecurityException("actor terminated");
        if (tickets.size() >= MAX_TICKETS) {
            throw new IllegalStateException("too many outstanding egress tickets");
        }
        long id = nextTicket.incrementAndGet();
        if (id <= 0) throw new IllegalStateException("ticket sequence exhausted");
        var slot = new AtomicReference<>(new Poll(State.PENDING, 0));
        tickets.put(id, slot);
        try {
            hostDispatcher.execute(() -> dispatchHost(slot));
        } catch (RuntimeException rejected) {
            tickets.remove(id, slot);
            throw new IllegalStateException("host dispatcher rejected egress request", rejected);
        }
        return id;
    }

    /**
     * Single-consumer poll; consumes terminal state so completed tickets
     * cannot build up indefinitely or be replayed.
     */
    private synchronized Poll pollBoundTicket(long id) {
        requireBoundActorTurn();
        if (closed || !runtime.isAlive(actor)) throw new SecurityException("binding revoked");
        AtomicReference<Poll> slot = tickets.get(id);
        if (slot == null) throw new SecurityException("invalid egress ticket");
        Poll result = slot.get();
        if (result.state() != State.PENDING && !tickets.remove(id, slot)) {
            throw new SecurityException("egress ticket has already been consumed");
        }
        return result;
    }

    private void dispatchHost(AtomicReference<Poll> slot) {
        // An inline executor has inherited the untrusted actor turn:
        // fail closed rather than granting host authority.
        if (ActorRuntime.inActorExecution() ||
                ActorRuntime.inActorGroupMailmanExecution()) {
            slot.set(new Poll(State.FAILED, 0));
            return;
        }
        synchronized (this) {
            if (closed || !runtime.isAlive(actor)) {
                slot.set(new Poll(State.CANCELLED, 0));
                return;
            }
        }
        // Actual HTTP admission/dispatch occurs OUTSIDE the bridge monitor.
        // revokeAfterTermination() and close() synchronously fence the broker
        // under that monitor; getStatus() cannot start after broker fencing.
        try {
            broker.getStatus().whenComplete((status, failure) -> {
                slot.set(failure == null
                        ? new Poll(State.OK, status)
                        : new Poll(State.FAILED, 0));
            });
        } catch (RuntimeException failure) {
            slot.set(new Poll(State.FAILED, 0));
        }
    }

    private void revokeAfterTermination() {
        // A cached lookup may already hold this bridge. Revocation and ticket
        // consumption must linearize on the SAME monitor as pollBoundTicket.
        // In particular, no terminal ticket result may escape after this
        // synchronized section has completed.
        synchronized (this) {
            closed = true;
            // No-I/O synchronous fence closes the check-before-dispatch race.
            // Never issue network requests or transport cancellation here.
            broker.fenceNewDispatch();
            HOST_BINDINGS.remove(bindingKey, this);
            tickets.clear();
        }
        // Non-blocking, capacity-reserved queue; no HTTP I/O on the runtime
        // finalization thread and no unbounded common-pool scheduling.
        enqueueTerminatedCleanup();
    }

    private void releaseCleanupSlot() {
        if (cleanupSlotReleased.compareAndSet(false, true)) {
            CLEANUP_QUARANTINE.remove(this);
            CLEANUP_SLOTS.release();
        }
    }

    private void enqueueTerminatedCleanup() {
        if (cleanupSlotReleased.get() || !cleanupQueued.compareAndSet(false, true)) {
            return;
        }
        // Guaranteed capacity: at most MAX_LIVE_BINDINGS unresolved instances
        // each reserve one slot and enqueue no more than once.
        if (CLEANUP_QUEUE.offer(this)) {
            CLEANUP_QUARANTINE.remove(this);
        } else {
            cleanupQueued.set(false);
            CLEANUP_QUARANTINE.add(this);
            LOG.log(System.Logger.Level.ERROR,
                    "reserved host egress cleanup queue unexpectedly full; binding quarantined");
        }
    }

    private void finishQueuedCleanup() {
        if (cleanupSlotReleased.get()) return;
        // Transient cancellation failures get bounded retries. Permanent
        // failures remain visible and retain their reservation, preventing
        // unbounded replacement of incompletely cleaned-up broker resources.
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                broker.close();
                releaseCleanupSlot();
                return;
            } catch (RuntimeException | Error failure) {
                LOG.log(System.Logger.Level.WARNING,
                        "host egress cleanup failed, attempt " + attempt, failure);
            }
        }
        CLEANUP_QUARANTINE.add(this);
        cleanupQueued.set(false);
        LOG.log(System.Logger.Level.ERROR,
                "host egress cleanup exhausted retries; instance quarantined and capacity retained");
    }

    /** Host-only diagnosis/recovery. Never returns a privileged broker. */
    static int failedCleanupCountForHost() {
        requireHost();
        return CLEANUP_QUARANTINE.size();
    }

    static void retryFailedCleanupForHost() {
        requireHost();
        for (var bridge : CLEANUP_QUARANTINE.toArray(
                new TrustedActorLoopbackEgressBridge[0])) {
            bridge.enqueueTerminatedCleanup();
        }
    }

    /** Host-only diagnostic: never exposes binding objects or network handles. */
    static boolean isRegisteredForHost(ActorRuntime runtime, ActorRuntime.ActorRef<?> actor) {
        requireHost();
        return actor != null && HOST_BINDINGS.containsKey(
                new ActorKey(Objects.requireNonNull(runtime), actor.id()));
    }

    private void requireBoundActorTurn() {
        if (ActorRuntime.currentActorKind() != ActorRuntime.ActorKind.UNTRUSTED
                || ActorRuntime.currentActorRuntime() != runtime
                || !ActorRuntime.currentActorId().equals(java.util.Optional.of(actor.id()))
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("not the authenticated bound untrusted actor turn");
        }
    }

    static void requireHost() {
        if (ActorRuntime.inActorExecution() ||
                ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("network egress bindings belong to trusted host");
        }
    }

    @Override public void close() {
        requireHost();
        synchronized (this) {
            closed = true;
            // No-I/O synchronous fence closes the check-before-dispatch race.
            // Never issue network requests or transport cancellation here.
            broker.fenceNewDispatch();
            HOST_BINDINGS.remove(bindingKey, this);
            tickets.clear();
        }
        // HTTP client cancellation may block. Keep it OUTSIDE the bridge
        // monitor; repeated close still retries unsettled host exchanges.
        try {
            broker.close();
            releaseCleanupSlot();
        } catch (RuntimeException | Error failure) {
            // Explicit host close is allowed to report failure, but must not
            // drop the only reference to a non-quiescent broker resource.
            enqueueTerminatedCleanup();
            throw failure;
        }
    }
}
