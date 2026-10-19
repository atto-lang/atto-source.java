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
            long routeEpoch,
            ActorRuntime.ActorKind kind) {
        public LogicalAddress {
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(kind);
            if (incarnation <= 0)
                throw new IllegalArgumentException("logical incarnation must be positive");
            if (routeEpoch <= 0)
                throw new IllegalArgumentException("logical route epoch must be positive");
        }
    }

    private record Route(
            HotReloadManager.Generation generation,
            HotReloadManager.Generation.ActorRouteLease generationLease,
            HotReloadManager.ActorGenerationView view,
            ActorRuntime.ActorRef<Object> physical,
            long incarnation,
            long routeEpoch) { }

    public final class LogicalActorRef {
        private LogicalActorRef() { }

        public UUID id() { return logicalId; }

        public LogicalAddress address() {
            synchronized (routeLock) {
                requireOpenLocked();
                reapRetiredLocked();
                return new LogicalAddress(
                        logicalId,
                        active.incarnation,
                        active.routeEpoch,
                        kind);
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
         * Monotonic physical-route epoch for the current logical incarnation.
         * A successful transparent hot replacement advances this value without
         * changing actor incarnation. Crash/restart semantics use incarnation
         * instead and must never be conflated with a route change.
         */
        public long routeEpoch() {
            synchronized (routeLock) {
                requireOpenLocked();
                reapRetiredLocked();
                return active.routeEpoch;
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
            final OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> pending;
            synchronized (routeLock) {
                awaitUpgradeLocked();
                requireOpenLocked();
                reapRetiredLocked();
                // Reader registration is part of route lookup linearization.
                // The transfer gate checks physical pending output readers; if
                // next() happened after releasing routeLock an upgrade could
                // observe zero readers, publish, and strand this caller on the
                // retired physical stream.
                pending = active.physical.outputs().next();
            }
            // Waiting is deliberately outside routeLock. Completion callbacks
            // and actor lifecycle work must never execute under the logical
            // route publication monitor.
            try {
                return pending.get(
                        toNanosSaturated(timeout),
                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException | TimeoutException abandonedRead) {
                // The physical output channel owns the pending subscription.
                // A timed-out/interrupted host reader must revoke that
                // subscription before the router can safely migrate actors.
                // Cancellation is best-effort if completion already won.
                pending.cancel(false);
                throw abandonedRead;
            }
        }
    }

    private final Object routeLock = new Object();
    // Serializes multiple supervisor close attempts; unlike the routeLock,
    // it may be held while waiting for physical actor finalization.
    private final Object closeLock = new Object();
    private final UUID logicalId = UUID.randomUUID();
    private final HotReloadManager manager;
    private final ActorRuntime.ActorKind kind;
    private final String actorTypeName;
    private final LogicalActorRef logicalRef = new LogicalActorRef();
    private final ArrayList<Route> retiredRoutes = new ArrayList<>();
    private Route active;
    private boolean upgrading;
    private boolean closed;
    // Under routeLock. A close timeout may retain the active physical actor
    // in retiredRoutes until another supervisor cleanup attempt reaps it.
    private boolean activeRouteLeaseReleased;

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
        // This V1 source router runs actors in same-JVM ActorRuntime pools.
        // Distinct Truffle contexts are NOT proof of a protected per-actor
        // address space, and selecting UNTRUSTED does not instantiate a new
        // protected worker for each actor. Never silently downgrade private
        // or untrusted actor isolation during a hot-upgrade route.
        if (kind != ActorRuntime.ActorKind.SHARED) {
            throw new SecurityException(
                    "logical actor hot-upgrade v1 requires SHARED actor kind; "
                            + "PRIVATE and UNTRUSTED require a verified per-actor "
                            + "protected worker/isolate migration backend");
        }

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
                    new Route(
                            generation,
                            generationLease,
                            view,
                            physical,
                            1L,
                            1L));
        } finally {
            if (!ready) {
                if (physical != null) physical.stop();
                generationLease.close();
            }
        }
    }

    public LogicalActorRef ref() { return logicalRef; }

    /**
     * Opaque, trusted-host-only cumulative physical-drain acknowledgment.
     * This is bound to the exact immutable envelope AND the route instance
     * that accepted it. It is NOT a remote authenticated mail.done() receipt:
     * serialized send+drain is required until compiler-owned message IDs and
     * authenticated per-message physical completions are available.
     */
    static final class ProxyDrainReceipt {
        private final LogicalSourceActorRouterV1 owner;
        private final Route routed;
        private final LogicalActorProxyMailboxV1.Envelope envelope;
        private final OresFuture<Void> drained;
        private boolean consumed;

        private ProxyDrainReceipt(
                LogicalSourceActorRouterV1 owner,
                Route routed,
                LogicalActorProxyMailboxV1.Envelope envelope,
                OresFuture<Void> drained) {
            this.owner = owner;
            this.routed = routed;
            this.envelope = envelope;
            this.drained = drained;
        }

        void awaitAndComplete(
                LogicalActorProxyMailboxV1 mailbox,
                LogicalActorProxyMailboxV1.Envelope exactEnvelope,
                Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(mailbox, "mailbox");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("drain deadline must be positive");
            if (exactEnvelope != envelope)
                throw new SecurityException("foreign immutable proxy envelope receipt");

            // Completion may run guest turns: NEVER wait while holding the
            // route monitor. A timeout/interrupt is ambiguous physical work
            // and the calling bridge must fail sealed rather than retry.
            drained.get(toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
            synchronized (owner.routeLock) {
                if (consumed)
                    throw new IllegalStateException("consumed physical drain receipt");
                if (owner.closed || owner.upgrading || owner.active != routed)
                    throw new SecurityException("physical actor route changed before completion");
                if (mailbox.snapshot().routeEpoch() != routed.routeEpoch())
                    throw new SecurityException("physical and proxy route epochs differ");
                // Consume BEFORE touching mailbox accounting. If completion
                // rejects or faults, no caller may replay the receipt; its
                // logical delivery must be reconciled by the supervisor.
                consumed = true;
                mailbox.complete(envelope.messageSequence());
            }
        }
    }

    /**
     * The only V1 wire format is a canonical big-endian signed 64-bit integer.
     * Decode copied immutable bytes in the trusted runtime, not in an
     * arbitrary producer callback, before physical mailbox admission.
     */
    ProxyDrainReceipt sendAndDrainForProxy(
            LogicalActorProxyMailboxV1.Envelope envelope, long expectedRouteEpoch) {
        requireBlockingHostSurface("proxy-send a logical source actor");
        Objects.requireNonNull(envelope, "envelope");
        byte[] wire = envelope.payload();
        if (wire.length != Long.BYTES)
            throw new SecurityException("invalid canonical scalar proxy wire length");
        long value = 0L;
        for (byte octet : wire) value = (value << Byte.SIZE) | (octet & 255L);
        synchronized (routeLock) {
            awaitUpgradeLocked();
            requireOpenLocked();
            reapRetiredLocked();
            Route current = active;
            if (expectedRouteEpoch != current.routeEpoch)
                throw new SecurityException("stale proxy dispatch route epoch");
            current.physical.send(value);
            // The bridge dispatches only ONE outstanding message at a time;
            // its cumulative drain therefore acknowledges this exact prefix.
            OresFuture<Void> drained = current.physical.drain();
            return new ProxyDrainReceipt(this, current, envelope, drained);
        }
    }

    /**
     * Host-only acceptance seam: launch a physically pinned scheduler frame
     * against the currently authoritative source actor under routeLock.
     * Route observation and ActorRuntime Task-pin admission linearize before
     * an upgrade may freeze or replace this physical actor. A resumed await
     * retains this pin until the scheduler's physical quiescence callback.
     *
     * This is NOT source task {} lowering or scheduler/code-domain attestation;
     * compiler-owned TaskScope and actor-affinity gates are still required.
     */
    <T> OresFuture<T> startHostPinnedTaskForUpgrade(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        requireBlockingHostSurface("start pinned source actor Task proof");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        synchronized (routeLock) {
            awaitUpgradeLocked();
            requireOpenLocked();
            reapRetiredLocked();
            return ActorHotUpgradeTaskBridgeV1.startPinned(
                    active.view.runtime(), active.physical, scheduler, frame);
        }
    }

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
        Throwable primaryFailure = null;
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
                if (active.routeEpoch == Long.MAX_VALUE)
                    throw new IllegalStateException(
                            "logical actor route epoch exhausted");
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
                        prior.incarnation,
                        Math.addExact(prior.routeEpoch, 1L));
                candidateLeaseTransferred = true;
                published = true;
                // Keep host route admission paused until the old physical
                // incarnation is terminal and its generation lease is safe to
                // release. The new route is authoritative but not yet open.
            }

            // Route publication is the commit point. Only now make the stale
            // physical ActorRef terminal and reopen no old ingress.
            try {
                transfer.retireSourceAfterPublication(replacement);
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
        } catch (InterruptedException
                | ExecutionException
                | TimeoutException
                | RuntimeException
                | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;

            if (!published) {
                if (replacement != null) {
                    try {
                        replacement.stop();
                    } catch (RuntimeException | Error stopFailure) {
                        cleanupFailure = stopFailure;
                    }

                    try {
                        if (!candidate.runtime().physicallyFinalizedForHotUpgrade(
                                replacement)) {
                            synchronized (routeLock) {
                                retiredRoutes.add(new Route(
                                        candidateGeneration,
                                        candidateLease,
                                        candidate,
                                        replacement,
                                        prior == null ? 1L : prior.incarnation,
                                        prior == null ? 1L : prior.routeEpoch));
                            }
                            // The candidate context must stay pinned until its
                            // unpublished physical actor actually finalizes.
                            candidateLeaseTransferred = true;
                        }
                    } catch (RuntimeException | Error finalizationProbeFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = finalizationProbeFailure;
                        } else {
                            cleanupFailure.addSuppressed(finalizationProbeFailure);
                        }
                        // We cannot prove reclamation. Fail closed by retaining
                        // the candidate generation rather than closing code
                        // beneath an unknown physical actor.
                        candidateLeaseTransferred = true;
                        ActorRuntime.ActorRef<Object> quarantinedCandidate =
                                replacement;
                        synchronized (routeLock) {
                            if (quarantinedCandidate != null
                                    && retiredRoutes.stream().noneMatch(
                                            route -> route.physical
                                                    == quarantinedCandidate)) {
                                retiredRoutes.add(new Route(
                                        candidateGeneration,
                                        candidateLease,
                                        candidate,
                                        quarantinedCandidate,
                                        prior == null ? 1L : prior.incarnation,
                                        prior == null ? 1L : prior.routeEpoch));
                            }
                        }
                    }
                }

                if (transfer != null) {
                    try {
                        transfer.close();
                    } catch (RuntimeException | Error rollbackFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = rollbackFailure;
                        } else {
                            cleanupFailure.addSuppressed(rollbackFailure);
                        }
                    }
                }

                // Route-level admission must never remain wedged merely
                // because physical rollback/quarantine reported a failure.
                // The old physical actor may still be ingress-frozen by the
                // transfer, which correctly makes later sends fail sealed
                // rather than block forever.
                synchronized (routeLock) {
                    if (pauseOwned) {
                        upgrading = false;
                        pauseOwned = false;
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
                    pauseOwned = false;
                    routeLock.notifyAll();
                }
            }

            if (!candidateLeaseTransferred) {
                try {
                    candidateLease.close();
                } catch (RuntimeException | Error leaseFailure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = leaseFailure;
                    } else {
                        cleanupFailure.addSuppressed(leaseFailure);
                    }
                }
            }

            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                } else {
                    throw (Error) cleanupFailure;
                }
            }
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
                // Close the lease BEFORE forgetting its owner. If lease
                // release fails, the route remains tracked for a retry.
                old.generationLease.close();
                retiredRoutes.remove(i);
                if (old == active) activeRouteLeaseReleased = true;
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
        synchronized (closeLock) {
            final Route route;
            synchronized (routeLock) {
                if (!closed) {
                    awaitUpgradeLocked();
                    closed = true;
                    routeLock.notifyAll();
                } else if (activeRouteLeaseReleased && retiredRoutes.isEmpty()) {
                    return;
                }
                route = active;
            }

            // ActorRuntime.stop() has a bounded host wait and can throw after
            // requesting stop. The generation must remain pinned even when
            // that wait times out. Record it before propagating the failure,
            // rather than leaving a sealed, unreapable logical router.
            try {
                route.physical.stop();
            } catch (RuntimeException | Error stopFailure) {
                synchronized (routeLock) {
                    if (!activeRouteLeaseReleased
                            && !retiredRoutes.contains(route)) {
                        retiredRoutes.add(route);
                    }
                }
                throw stopFailure;
            }

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
                if (activeTerminated && !activeRouteLeaseReleased) {
                    route.generationLease.close();
                    activeRouteLeaseReleased = true;
                    retiredRoutes.remove(route);
                } else if (!activeTerminated && !activeRouteLeaseReleased
                        && !retiredRoutes.contains(route)) {
                    retiredRoutes.add(route);
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (!activeTerminated || !retiredRoutes.isEmpty()) {
                    throw new IllegalStateException(
                            "logical actor close did not observe complete physical retirement; "
                                    + "generation lease(s) remain pinned for supervisor retry");
                }
            }
        }
    }
}
