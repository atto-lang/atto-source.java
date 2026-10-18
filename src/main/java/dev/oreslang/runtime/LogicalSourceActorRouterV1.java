package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayList;
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
            HotReloadManager.Generation.ActorRouteLease generationLease,
            HotReloadManager.ActorGenerationView view,
            ActorRuntime.ActorRef<Object> physical,
            long incarnation) { }

    public final class LogicalActorRef {
        private LogicalActorRef() { }

        public UUID id() { return logicalId; }

        public LogicalAddress address() {
            synchronized (routeLock) {
                requireOpenLocked();
                reapRetiredLocked();
                return new LogicalAddress(logicalId, active.incarnation, kind);
            }
        }

        public long incarnation() {
            synchronized (routeLock) {
                requireOpenLocked();
                reapRetiredLocked();
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
                reapRetiredLocked();
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
                reapRetiredLocked();
                stream = active.physical.outputs();
            }
            return stream.next().get(
                    toNanosSaturated(timeout),
                    TimeUnit.NANOSECONDS);
        }
    }

    private final Object routeLock = new Object();
    private final UUID logicalId = UUID.randomUUID();
    private final HotReloadManager manager;
    private final ActorRuntime.ActorKind kind;
    private final String actorTypeName;
    private final LogicalActorRef logicalRef = new LogicalActorRef();
    private final ArrayList<Route> retiredRoutes = new ArrayList<>();
    private Route active;
    private boolean upgrading;
    private boolean closed;

    private LogicalSourceActorRouterV1(
            ActorRuntime.ActorKind kind,
            String actorTypeName,
            Route initial) {
        this.kind = Objects.requireNonNull(kind);
        this.actorTypeName = Objects.requireNonNull(actorTypeName);
        this.active = Objects.requireNonNull(initial);
        this.manager = initial.generation.ownerForActorRouting();
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
        HotReloadManager.Generation.ActorRouteLease generationLease =
                generation.pinActorRoute();
        ActorRuntime.ActorRef<Object> physical = null;
        boolean ready = false;
        try {
            physical = view.runtime().spawnCodeActor(
                    kind,
                    view.policy(),
                    view.executor(),
                    actorTypeName);
            physical.getReady().get(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS);
            ready = true;
            return new LogicalSourceActorRouterV1(
                    kind,
                    actorTypeName,
                    new Route(generation, generationLease, view, physical, 1L));
        } finally {
            if (!ready) {
                if (physical != null) physical.stop();
                generationLease.close();
            }
        }
    }

    public LogicalActorRef ref() { return logicalRef; }

    public HotReloadManager.Generation generation() {
        synchronized (routeLock) {
            requireOpenLocked();
            reapRetiredLocked();
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

        if (candidateGeneration.ownerForActorRouting() != manager) {
            throw new SecurityException(
                    "cross-context logical actor upgrade v1 requires generations "
                            + "from the same HotReloadManager");
        }

        // Link/compile and pin the candidate context before pausing message route
        // admission. Failure here has zero effect on the active logical route.
        HotReloadManager.ActorGenerationView candidate =
                candidateGeneration.linkActorGeneration(actorTypeName);
        HotReloadManager.Generation.ActorRouteLease candidateLease =
                candidateGeneration.pinActorRoute();

        Route prior = null;
        boolean pauseOwned = false;
        ActorRuntime.SourceActorTransfer transfer = null;
        ActorRuntime.ActorRef<Object> replacement = null;
        boolean published = false;
        boolean candidateLeaseTransferred = false;
        try {
            synchronized (routeLock) {
                requireOpenLocked();
                reapRetiredLocked();
                if (upgrading)
                    throw new IllegalStateException(
                            "logical actor upgrade already in progress");
                if (candidateGeneration == active.generation)
                    throw new IllegalArgumentException(
                            "candidate generation is already the active logical route");
                if (active.incarnation == Long.MAX_VALUE)
                    throw new IllegalStateException(
                            "logical actor incarnation exhausted");
                upgrading = true;
                pauseOwned = true;
                prior = active;
            }

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
                        candidateLease,
                        candidate,
                        replacement,
                        Math.addExact(prior.incarnation, 1L));
                candidateLeaseTransferred = true;
                published = true;
                // Keep host route admission paused until the old physical
                // incarnation is terminal and its generation lease is safe to
                // release. The new route is authoritative but not yet open.
            }

            // Route publication is the commit point. Only now make the stale
            // physical ActorRef terminal and reopen no old ingress.
            try {
                transfer.retireSourceAfterPublication();
            } catch (RuntimeException | Error retirementFailure) {
                // stop() marks the cell stopped before its bounded host wait can
                // fail. Keep the old generation pinned until finalization is
                // later observable rather than losing its code/context lease.
                synchronized (routeLock) {
                    if (!retiredRoutes.contains(prior)) retiredRoutes.add(prior);
                }
                throw retirementFailure;
            } finally {
                transfer = null;
            }

            // The source was already physically drained. Keep its generation
            // pinned until physical finalization is actually observed.
            if (prior.physical.awaitTermination(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS)) {
                prior.generationLease.close();
            } else {
                synchronized (routeLock) {
                    retiredRoutes.add(prior);
                    upgrading = false;
                    pauseOwned = false;
                    routeLock.notifyAll();
                }
                throw new IllegalStateException(
                        "old physical actor did not terminate after route publication; "
                                + "its generation remains pinned for later reaping");
            }
            replacement = null; // authoritative route owns it
            synchronized (routeLock) {
                upgrading = false;
                pauseOwned = false;
                routeLock.notifyAll();
            }
            return true;
        } finally {
            if (!published) {
                if (replacement != null) {
                    try {
                        replacement.stop();
                    } catch (RuntimeException ignored) {
                        // Rollback still keeps the old route authoritative.
                    }
                    if (!candidate.runtime().physicallyFinalizedForHotUpgrade(
                            replacement)) {
                        synchronized (routeLock) {
                            retiredRoutes.add(new Route(
                                    candidateGeneration,
                                    candidateLease,
                                    candidate,
                                    replacement,
                                    prior == null ? 0L : prior.incarnation));
                        }
                        // The candidate context must stay pinned until its
                        // unpublished physical actor actually finalizes.
                        candidateLeaseTransferred = true;
                    }
                }
                if (transfer != null) transfer.close();
                synchronized (routeLock) {
                    if (pauseOwned) {
                        upgrading = false;
                        routeLock.notifyAll();
                    }
                }
            }
            if (published && pauseOwned) {
                // Post-commit cleanup failure cannot roll routing back. Reopen
                // the authoritative new route while retaining any unreaped old
                // generation lease.
                synchronized (routeLock) {
                    upgrading = false;
                    routeLock.notifyAll();
                }
            }
            if (!candidateLeaseTransferred) candidateLease.close();
        }
    }

    /**
     * Release generation leases for previously published physical incarnations
     * once their runtimes report full actor finalization.
     */
    public int reapRetired() {
        synchronized (routeLock) {
            return reapRetiredLocked();
        }
    }

    private int reapRetiredLocked() {
        int released = 0;
        for (int i = retiredRoutes.size() - 1; i >= 0; --i) {
            Route old = retiredRoutes.get(i);
            if (old.view.runtime().physicallyFinalizedForHotUpgrade(
                    old.physical)) {
                retiredRoutes.remove(i);
                old.generationLease.close();
                released++;
            }
        }
        return released;
    }

    /**
     * Test/diagnostic physical address only. User routing must use LogicalActorRef.
     */
    ActorRuntime.ActorAddress physicalAddressForTesting() {
        synchronized (routeLock) {
            requireOpenLocked();
            reapRetiredLocked();
            return active.physical.address();
        }
    }

    ActorRuntime.ActorRef<Object> physicalRefForTesting() {
        synchronized (routeLock) {
            requireOpenLocked();
            return active.physical;
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
        if (closed)
            throw new IllegalStateException("logical actor router is closed");
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
        requireBlockingHostSurface("close a logical source actor");
        final Route route;
        synchronized (routeLock) {
            awaitUpgradeLocked();
            if (closed) return;
            closed = true;
            route = active;
            routeLock.notifyAll();
        }

        route.physical.stop();
        boolean interrupted = false;
        boolean activeTerminated = false;
        try {
            activeTerminated =
                    route.physical.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            interrupted = true;
        }

        final ArrayList<Route> oldRoutes;
        synchronized (routeLock) {
            reapRetiredLocked();
            oldRoutes = new ArrayList<>(retiredRoutes);
        }
        for (Route old : oldRoutes) {
            try {
                old.physical.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                interrupted = true;
                break;
            }
        }

        synchronized (routeLock) {
            reapRetiredLocked();
            if (activeTerminated) {
                route.generationLease.close();
            } else if (!retiredRoutes.contains(route)) {
                retiredRoutes.add(route);
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (!activeTerminated || !retiredRoutes.isEmpty()) {
                throw new IllegalStateException(
                        "logical actor close did not observe complete physical retirement; "
                                + "generation lease(s) remain pinned");
            }
        }
    }
}
