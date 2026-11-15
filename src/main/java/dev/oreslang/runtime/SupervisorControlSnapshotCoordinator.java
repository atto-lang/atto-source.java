package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded host-only CONTROL-dispatched registry observation coordinator.
 *
 * This intentionally uses ActorRuntime's existing VM-owned CONTROL dispatcher;
 * a standalone runtime without it refuses the request. It does NOT implement
 * per-supervisor CONTROL fanout, guest-source lowering, actor lifecycle
 * observations, or physical cancellation of a started CONTROL turn.
 */
public final class SupervisorControlSnapshotCoordinator implements AutoCloseable {
    public static final int MAX_PENDING_CONTROL_READS = 8;

    /** Stable, non-sensitive error codes; never leak internal runtime exceptions. */
    public static final class SnapshotFailure extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final String code;
        private SnapshotFailure(String code) {
            super(code);
            this.code = code;
        }
        public String code() { return code; }
    }

    private static final class Request {
        private final SupervisorRegistryObservation.Grant grant;
        private final Thread callerThread;
        private final CompletableFuture<SupervisorRegistryObservation.Snapshot> result =
                new CompletableFuture<>();
        private final ControlObservationTurnGate physicalTurn = new ControlObservationTurnGate();
        private final AtomicBoolean released = new AtomicBoolean();
        private Request(SupervisorRegistryObservation.Grant grant, Thread callerThread) {
            this.grant = grant;
            this.callerThread = callerThread;
        }
    }

    private final ActorRuntime runtime;
    private final SupervisorRegistryObservation observation;
    private final Object admissionLock = new Object();
    private final Map<SupervisorRegistryObservation.Grant, Request> pending =
            new IdentityHashMap<>();
    private boolean closed;

    public SupervisorControlSnapshotCoordinator(ActorRuntime runtime) {
        this(runtime, new SupervisorRegistryObservation(runtime));
    }

    SupervisorControlSnapshotCoordinator(ActorRuntime runtime,
                                         SupervisorRegistryObservation observation) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.observation = Objects.requireNonNull(observation, "observation");
        runtime.supervisorRegistryForObservation();
    }

    /** Authority stays with a trusted embedder; never expose this to guest code. */
    public SupervisorRegistryObservation.Grant issueGrant(
            Object authenticatedSession, String authorizedTenant,
            ActorRuntime.ActorSupervisor supervisor, long ttlNanos) {
        requireHost();
        synchronized (admissionLock) {
            if (closed) throw new SnapshotFailure("COORDINATOR_CLOSED");
        }
        return observation.issueGrant(authenticatedSession, authorizedTenant, supervisor, ttlNanos);
    }

    public void revoke(SupervisorRegistryObservation.Grant grant) {
        requireHost();
        observation.revoke(grant);
        Request request;
        synchronized (admissionLock) { request = pending.get(grant); }
        if (request != null) {
            // Publish revocation only after reclaiming QUEUED capacity. An
            // external waiter must not observe DENIED and then find a stale
            // slot when retrying immediately with a fresh grant. A STARTED
            // turn retains its slot until its actual physical finally.
            if (request.physicalTurn.abandonIfQueued()) release(request);
            request.result.completeExceptionally(new SnapshotFailure("DENIED"));
        }
    }

    /**
     * Read without parking an actor carrier. Caller must be a trusted host
     * with a host-minted grant; returned Future is not a guest-visible object.
     * 
     * A timeout without a trusted registry observation is a failed read, NOT a
     * fabricated partial tree. Partial snapshots arise from the real adapter.
     */
    public CompletionStage<SupervisorRegistryObservation.Snapshot> request(
            SupervisorRegistryObservation.Grant grant, Object authenticatedSession,
            String tenantId, long expectedGeneration, int maxNodes, int maxDepth,
            long timeoutNanos) {
        requireHost();
        observation.authorizeControlObservation(grant, authenticatedSession, tenantId,
                expectedGeneration, maxNodes, maxDepth, timeoutNanos);
        Request request = new Request(grant, Thread.currentThread());
        synchronized (admissionLock) {
            if (closed) throw new SnapshotFailure("COORDINATOR_CLOSED");
            if (pending.size() >= MAX_PENDING_CONTROL_READS)
                throw new SnapshotFailure("CONTROL_OBSERVATION_BUSY");
            if (pending.containsKey(grant)) throw new SnapshotFailure("GRANT_BUSY");
            pending.put(grant, request);
        }

        // This future's JDK deadline is an observer timeout, not a new
        // executor/carrier pool or cancellation receipt for the CONTROL work.
        // The timer may abandon a still-queued physical turn atomically;
        // already started work retains its lease until the actual finally.
        // A completed observer timeout must also mean that any QUEUED
        // accounting slot has been reclaimed. CompletableFuture may wake
        // ordinary waiters before executing independent whenComplete actions,
        // so return the dependent completion stage rather than the raw result.
        CompletableFuture<SupervisorRegistryObservation.Snapshot> observed =
                request.result.whenComplete((ignored, failure) -> {
                    if (failure instanceof TimeoutException
                            && request.physicalTurn.abandonIfQueued()) {
                        release(request);
                    }
                });
        request.result.orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
        // A deadline may have expired before dispatch admission. Do not
        // schedule a runnable that has already lost its start authority.
        if (request.result.isDone()) return observed.minimalCompletionStage();

        final OresFuture<Void> dispatch;
        try {
            dispatch = runtime.dispatchSupervisorObservationControl(() ->
                    execute(request, authenticatedSession, tenantId,
                            expectedGeneration, maxNodes, maxDepth, timeoutNanos));
        } catch (RuntimeException | Error error) {
            request.result.completeExceptionally(new SnapshotFailure("CONTROL_UNAVAILABLE"));
            // A dispatch implementation may throw after enqueueing. Claim
            // cancellation against physical start before releasing its slot.
            if (request.physicalTurn.abandonIfQueued()) release(request);
            return observed.minimalCompletionStage();
        }
        try {
            dispatch.whenCompleteRuntime((ignored, failure) -> {
                if (failure == null) return;
                request.result.completeExceptionally(
                        new SnapshotFailure("CONTROL_DISPATCH_FAILED"));
                // The dispatcher claims non-execution on failure. If a task
                // began anyway, only its physical finally may free the slot.
                if (request.physicalTurn.abandonIfQueued()) release(request);
            });
        } catch (RuntimeException | Error error) {
            request.result.completeExceptionally(
                    new SnapshotFailure("CONTROL_DISPATCH_FAILED"));
            if (request.physicalTurn.abandonIfQueued()) release(request);
        }
        return observed.minimalCompletionStage();
    }

    private void execute(Request request, Object session, String tenantId,
                         long generation, int maxNodes, int maxDepth,
                         long timeoutNanos) {
        if (!request.physicalTurn.tryStart()) {
            // This runnable either lost to an already running CONTROL turn
            // or was abandoned by a failed dispatcher. Neither case owns
            // the result or its physical accounting lease. In particular a
            // duplicate must not fail the legitimate in-flight observation.
            return;
        }
        try {
            if (Thread.currentThread() == request.callerThread) {
                throw new SnapshotFailure("INLINE_CONTROL_DISPATCH_FORBIDDEN");
            }
            if (request.result.isDone()) return; // timed out or revoked while queued
            synchronized (admissionLock) {
                if (closed) throw new SnapshotFailure("COORDINATOR_CLOSED");
            }
            var result = observation.capture(request.grant, session, tenantId,
                    generation, maxNodes, maxDepth, timeoutNanos);
            request.result.complete(result);
        } catch (SecurityException denied) {
            request.result.completeExceptionally(new SnapshotFailure("DENIED"));
        } catch (RuntimeException | Error failure) {
            // No actor state, exception message or private stack trace leaves.
            String code = failure instanceof SnapshotFailure sf
                    ? sf.code() : "OBSERVATION_UNAVAILABLE";
            request.result.completeExceptionally(new SnapshotFailure(code));
        } finally {
            release(request);
        }
    }

    private void release(Request request) {
        if (!request.released.compareAndSet(false, true)) return;
        synchronized (admissionLock) {
            pending.remove(request.grant, request);
        }
    }

    public int activeControlRequests() {
        requireHost();
        synchronized (admissionLock) { return pending.size(); }
    }

    private void requireHost() {
        runtime.supervisorRegistryForObservation(); // rejects guest / Mailman
    }

    @Override public void close() {
        requireHost();
        Request[] outstanding;
        synchronized (admissionLock) {
            if (closed) return;
            closed = true;
            outstanding = pending.values().toArray(new Request[0]);
        }
        observation.close();
        for (Request request : outstanding) {
            // An unstarted request may be physically canceled now, before
            // exposing COORDINATOR_CLOSED to any independent host waiter.
            // STARTED work still owns its slot until physical completion.
            if (request.physicalTurn.abandonIfQueued()) release(request);
            request.result.completeExceptionally(
                    new SnapshotFailure("COORDINATOR_CLOSED"));
        }
    }
}
