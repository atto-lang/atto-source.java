package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Host/supervisor-only logical route for one source actor across distinct
 * HotReloadManager generations/OresContexts.
 *
 * <p>V1 deliberately blocks HOST senders while a migration is in progress
 * instead of buffering raw mutable message graphs. Actor/scheduler carriers
 * may never call this blocking surface. A future guest-visible router should
 * use runtime-owned immutable transport envelopes and nonblocking suspension.</p>
 *
 * <p>The stable logical id/incarnation are distinct from each physical
 * ActorRuntime.ActorRef generation. Old physical refs are sealed during
 * checkpoint and stopped immediately after logical route publication.</p>
 */
public final class LogicalSourceActorRouterV1 implements AutoCloseable {
    public record LogicalAddress(
            UUID actorId,
            long incarnation,
            ActorRuntime.ActorKind kind) {
        public LogicalAddress {
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(kind);
            if (incarnation <= 0)
                throw new IllegalArgumentException("logical incarnation must be positive");
        }
    }

    private record Route(
            HotReloadManager.Generation generation,
            HotReloadManager.ActorGenerationView view,
            ActorRuntime.ActorRef<Object> physical,
            long incarnation) { }

    public final class LogicalActorRef {
        private LogicalActorRef() { }

        public UUID id() { return logicalId; }

        public LogicalAddress address() {
            synchronized (routeLock) {
                requireOpenLocked();
                return new LogicalAddress(logicalId, active.incarnation, kind);
            }
        }

        public long incarnation() {
            synchronized (routeLock) {
                requireOpenLocked();
                return active.incarnation;
            }
        }

        /**
         * Host-only, lossless route admission. The monitor is held through the
         * physical ActorRuntime.send linearization so an upgrade cannot freeze
         * the old actor between route lookup and mailbox admission.
         */
        public void send(Object message) {
            requireBlockingHostSurface("send through a migrating logical actor");
            synchronized (routeLock) {
                awaitUpgradeLocked();
                requireOpenLocked();
                active.physical.send(message);
            }
        }

        /**
         * Output subscriptions are not yet rebound across generations. Callers
         * should pair this with a send on the same stable handle. Migration v1
         * refuses publication while the old physical output queue is nonempty.
         */
        public GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> nextOutput(
                Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("timeout must be positive");
            requireBlockingHostSurface("read logical actor output");
            final ActorRuntime.ActorOutputStream<Object> stream;
            synchronized (routeLock) {
                awaitUpgradeLocked();
                requireOpenLocked();
                stream = active.physical.outputs();
            }
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException overflow) {
                nanos = Long.MAX_VALUE;
            }
            return stream.next().get(nanos, TimeUnit.NANOSECONDS);
        }
    }

    private final Object routeLock = new Object();
    private final UUID logicalId = UUID.randomUUID();
    private final ActorRuntime.ActorKind kind;
    private final String actorTypeName;
    private final LogicalActorRef logicalRef = new LogicalActorRef();
    private Route active;
    private boolean upgrading;
    private boolean closed;

    private LogicalSourceActorRouterV1(
            ActorRuntime.ActorKind kind,
            String actorTypeName,
            Route initial) {
        this.kind = Objects.requireNonNull(kind);
        this.actorTypeName = actorTypeName;
        this.active = Objects.requireNonNull(initial);
    }

    /**
     * Create the first physical incarnation from an actor-only linked
     * generation. This executes the actor's normal v1 initialization exactly
     * once; later migrations restore state instead.
     */
    public static LogicalSourceActorRouterV1 spawn(
            HotReloadManager.Generation generation,
            ActorRuntime.ActorKind kind,
            String actorTypeName,
            Duration readyTimeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(readyTimeout, "readyTimeout");
        if (actorTypeName == null || actorTypeName.isBlank())
            throw new IllegalArgumentException("actor type name cannot be blank");
        if (readyTimeout.isNegative() || readyTimeout.isZero())
            throw new IllegalArgumentException("ready timeout must be positive");
        requireBlockingHostSurface("spawn a logical source actor");

        HotReloadManager.ActorGenerationView view =
                generation.linkActorGeneration(actorTypeName);
        ActorRuntime.ActorRef<Object> physical = view.runtime().spawnCodeActor(
                kind,
                view.policy(),
                view.executor(),
                actorTypeName);
        boolean ready = false;
        try {
            physical.getReady().get(toNanosSaturated(readyTimeout), TimeUnit.NANOSECONDS);
            ready = true;
            return new LogicalSourceActorRouterV1(
                    kind,
                    actorTypeName,
                    new Route(generation, view, physical, 1L));
        } finally {
            if (!ready) physical.stop();
        }
    }

    public LogicalActorRef ref() { return logicalRef; }

    public HotReloadManager.Generation generation() {
        synchronized (routeLock) {
            requireOpenLocked();
            return active.generation;
        }
    }

    /**
     * One nonblocking-at-safe-point migration attempt. Candidate linking and
     * receipt compilation happen before senders are paused. Once migration
     * begins, host sends wait until either rollback reopens the old route or
     * publication exposes the fully ready candidate.
     *
     * @return false only when the old physical actor is not quiescent yet.
     */
    public boolean upgradeTo(
            HotReloadManager.Generation candidateGeneration,
            Duration readyTimeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(candidateGeneration, "candidateGeneration");
        Objects.requireNonNull(readyTimeout, "readyTimeout");
        if (readyTimeout.isNegative() || readyTimeout.isZero())
            throw new IllegalArgumentException("ready timeout must be positive");
        requireBlockingHostSurface("upgrade a logical source actor");

        // Link/compile before pausing message route admission.
        HotReloadManager.ActorGenerationView candidate =
                candidateGeneration.linkActorGeneration(actorTypeName);

        final Route prior;
        synchronized (routeLock) {
            requireOpenLocked();
            if (upgrading)
                throw new IllegalStateException("logical actor upgrade already in progress");
            if (candidateGeneration == active.generation)
                throw new IllegalArgumentException(
                        "candidate generation is already the active logical route");
            if (active.incarnation == Long.MAX_VALUE)
                throw new IllegalStateException("logical actor incarnation exhausted");
            upgrading = true;
            prior = active;
        }

        ActorRuntime.SourceActorTransfer transfer = null;
        ActorRuntime.ActorRef<Object> replacement = null;
        boolean published = false;
        try {
            var started = prior.view.runtime().tryBeginSourceActorTransfer(
                    prior.physical,
                    logicalId,
                    prior.incarnation);
            if (started.isEmpty()) return false;
            transfer = started.get();

            replacement = candidate.runtime().spawnCodeActorFromTransfer(
                    transfer,
                    candidate.executor());
            replacement.getReady().get(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS);

            synchronized (routeLock) {
                if (closed)
                    throw new IllegalStateException(
                            "logical actor router closed while upgrade staged");
                if (!upgrading || active != prior)
                    throw new IllegalStateException(
                            "logical actor route changed while candidate staged");
                active = new Route(
                        candidateGeneration,
                        candidate,
                        replacement,
                        Math.addExact(prior.incarnation, 1L));
                published = true;
                upgrading = false;
                routeLock.notifyAll();
            }

            // Route publication is the commit point. Only now make the stale
            // physical ActorRef terminal and reopen no old ingress.
            transfer.retireSourceAfterPublication();
            transfer = null;

            // The source was already physically drained, so termination should
            // be immediate. Failure here is post-commit cleanup failure; the new
            // logical route remains authoritative.
            if (!prior.physical.awaitTermination(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(
                        "old physical actor did not terminate after route publication");
            }
            replacement = null; // authoritative route now owns it
            return true;
        } finally {
            if (!published) {
                if (replacement != null) {
                    try {
                        replacement.stop();
                    } catch (RuntimeException ignored) {
                        // Old route rollback remains authoritative.
                    }
                }
                if (transfer != null) transfer.close();
                synchronized (routeLock) {
                    if (active == prior) {
                        upgrading = false;
                        routeLock.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * Test/diagnostic physical address only. User routing must use LogicalActorRef.
     */
    ActorRuntime.ActorAddress physicalAddressForTesting() {
        synchronized (routeLock) {
            requireOpenLocked();
            return active.physical.address();
        }
    }

    private void awaitUpgradeLocked() {
        boolean interrupted = false;
        while (upgrading && !closed) {
            try {
                routeLock.wait();
            } catch (InterruptedException interruption) {
                interrupted = true;
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException(
                    "interrupted while waiting for logical actor route migration");
        }
    }

    private void requireOpenLocked() {
        if (closed) throw new IllegalStateException("logical actor router is closed");
    }

    private static void requireBlockingHostSurface(String operation) {
        if (ActorRuntime.inActorExecution()
                || OresScheduler.current() != null) {
            throw new SecurityException(
                    operation
                            + " is a host/supervisor blocking operation; "
                            + "actor/scheduler turns require nonblocking routing");
        }
    }

    private static long toNanosSaturated(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void close() {
        final Route route;
        synchronized (routeLock) {
            if (closed) return;
            closed = true;
            upgrading = false;
            route = active;
            routeLock.notifyAll();
        }
        route.physical.stop();
    }
}
