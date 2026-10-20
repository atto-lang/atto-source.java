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

    /**
     * Compiler metadata is prepared once per linked image, before the route
     * can become authoritative. A publication observer must never execute a
     * compiler callback while holding the physical route monitor.
     */
    record ImageReceipts(
            String linkedCodeSha256,
            String stateSchemaSha256,
            String messageAbiSha256) {
        ImageReceipts {
            linkedCodeSha256 = canonicalDigest(linkedCodeSha256, "linked code");
            stateSchemaSha256 = canonicalDigest(stateSchemaSha256, "persistent state schema");
            messageAbiSha256 = canonicalDigest(messageAbiSha256, "receive message ABI");
        }

        // Keep the same lowercase SHA-256 syntax enforced by the physical
        // checkpoint header. A malformed compiler receipt must be rejected
        // before a route lease or physical actor can become authoritative.
        static String canonicalDigest(String digest, String field) {
            if (digest == null || digest.length() != 64)
                throw new IllegalArgumentException(
                        field + " digest must be canonical lowercase SHA-256");
            for (int i = 0; i < 64; ++i) {
                char ch = digest.charAt(i);
                if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')))
                    throw new IllegalArgumentException(
                            field + " digest must be canonical lowercase SHA-256");
            }
            return digest;
        }

        static ImageReceipts from(
                HotReloadManager.ActorGenerationView view, String actorTypeName) {
            var compiler = view.executor();
            return new ImageReceipts(
                    compiler.actorCodeSha256(actorTypeName),
                    compiler.actorStateSchemaSha256(actorTypeName),
                    compiler.actorMessageAbiSha256(actorTypeName));
        }
    }

    private record Route(
            HotReloadManager.Generation generation,
            HotReloadManager.Generation.ActorRouteLease generationLease,
            HotReloadManager.ActorGenerationView view,
            ActorRuntime.ActorRef<Object> physical,
            long incarnation,
            long routeEpoch,
            ImageReceipts image) { }

    /**
     * Retry owner for an unpublished candidate. The owner is allocated and
     * inserted into its bounded supervisor ledger BEFORE any candidate physical
     * actor exists, so cleanup never has to allocate ownership after a failure.
     *
     * <p>The physical ref is filled exactly once after spawn. A null physical
     * means no executable candidate was admitted and the lease can be released
     * by the originating upgrade path. Once non-null, the owner stays in this
     * ledger until physical finalization is proven and lease close succeeds.</p>
     */
    private static final class CandidateRetirement {
        final HotReloadManager.Generation generation;
        final HotReloadManager.Generation.ActorRouteLease generationLease;
        final HotReloadManager.ActorGenerationView view;
        final long incarnation;
        final long routeEpoch;
        final ImageReceipts image;
        ActorRuntime.ActorRef<Object> physical;
        /**
         * False while the originating upgrade still owns the right to publish
         * this candidate. Reapers must never consume a staging lease merely
         * because the physical actor finalized before the upgrade thread
         * observed that fact. Cleanup flips this only when publication is
         * definitively abandoned and the ledger becomes the sole owner.
         */
        boolean retirementEligible;
        /**
         * Once true, the newly published active/retired Route owns this lease.
         * This staging entry becomes bookkeeping ONLY; the reaper must never
         * close that lease a second time. Protected by routeLock.
         */
        boolean publishedToRoute;

        CandidateRetirement(
                HotReloadManager.Generation generation,
                HotReloadManager.Generation.ActorRouteLease generationLease,
                HotReloadManager.ActorGenerationView view,
                long incarnation,
                long routeEpoch,
                ImageReceipts image) {
            this.generation = Objects.requireNonNull(generation);
            this.generationLease = Objects.requireNonNull(generationLease);
            this.view = Objects.requireNonNull(view);
            this.incarnation = incarnation;
            this.routeEpoch = routeEpoch;
            this.image = Objects.requireNonNull(image);
        }
    }

    private enum UpgradePhase {
        PREPARING,
        STAGING,
        PUBLISHED,
        RETIRING,
        RETIREMENT_PENDING,
        COMPLETE,
        ABANDONED
    }

    /**
     * One supervisor-owned hot-upgrade transaction. All mutable publication
     * authority between route freeze and commit is represented here.
     */
    private static final class SupervisorUpgradeTxn {
        final Route prior;
        final CandidateRetirement candidateRetirement;
        final LogicalActorProxyMailboxV1 proxyMailbox;
        LogicalActorProxyMailboxV1.FreezeTicket proxyTicket;
        ActorRuntime.SourceActorTransfer transfer;
        ActorRuntime.ActorRef<Object> replacement;
        Route next;
        UpgradePhase phase = UpgradePhase.PREPARING;
        boolean candidateLeaseTransferred;

        SupervisorUpgradeTxn(
                Route prior,
                CandidateRetirement candidateRetirement,
                LogicalActorProxyMailboxV1 proxyMailbox) {
            this.prior = Objects.requireNonNull(prior);
            this.candidateRetirement =
                    Objects.requireNonNull(candidateRetirement);
            this.proxyMailbox = proxyMailbox;
        }

        boolean published() {
            return phase == UpgradePhase.PUBLISHED
                    || phase == UpgradePhase.RETIRING
                    || phase == UpgradePhase.RETIREMENT_PENDING
                    || phase == UpgradePhase.COMPLETE;
        }
    }

    public final class LogicalActorRef {
        private LogicalActorRef() { }

        public UUID id() { return logicalId; }

        public LogicalAddress address() {
            requireBlockingHostSurface("inspect logical actor address");
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
            requireBlockingHostSurface("inspect logical actor incarnation");
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
            requireBlockingHostSurface("inspect logical actor route epoch");
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
                requireAdmissibleRouteLocked();
                if (boundProxyMailbox != null)
                    throw new SecurityException(
                            "raw logical ActorRef.send bypasses bound proxy admission");
                rawAdmissionAttempted = true;
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
                requireAdmissibleRouteLocked();
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

    /**
     * Failed initial readiness has no returned router to own a live physical
     * actor's generation lease. Pre-register this supervisor-owned quarantine
     * BEFORE physical spawn, so failed stop/timeout can never orphan an old
     * executable image or make context close look safe.
     *
     * This is a bounded host-only emergency ledger, NOT an automatic GC
     * proof; supervisors must retry reapFailedInitialSpawns().
     */
    private static final Object failedSpawnLock = new Object();
    private static final ArrayList<FailedInitialSpawn> failedInitialSpawns =
            new ArrayList<>();
    private static final int MAX_FAILED_INITIAL_SPAWNS = 1024;

    private static final class FailedInitialSpawn {
        final HotReloadManager.ActorGenerationView view;
        final HotReloadManager.Generation.ActorRouteLease lease;
        ActorRuntime.ActorRef<Object> physical;
        // Pre-registered but still starting actors are NOT failed spawns.
        // Their admission authority belongs to the original spawn caller.
        // Only the final failed-startup cleanup hands this owner to reaping.
        volatile boolean retryableFailure;

        FailedInitialSpawn(
                HotReloadManager.ActorGenerationView view,
                HotReloadManager.Generation.ActorRouteLease lease) {
            this.view = view;
            this.lease = lease;
        }
    }

    /**
     * Host supervisor retry: release a failed spawn's generation pin ONLY
     * after its exact ActorRuntime reports physical finalization. A failed
     * lease debit keeps the owner recorded for another retry.
     */
    public static int reapFailedInitialSpawns() {
        requireBlockingHostSurface("reap failed initial source actor spawns");

        // Cancellation may notify arbitrary host subscribers synchronously.
        // Never invoke it under the process-wide quarantine monitor: an
        // uncooperative callback could otherwise block ALL failed-spawn
        // registrations and retirement attempts. The immutable snapshot
        // allocates before any mutation to the ledger.
        final java.util.List<FailedInitialSpawn> candidates;
        synchronized (failedSpawnLock) {
            candidates = java.util.List.copyOf(failedInitialSpawns);
        }

        Throwable cancellationFailure = null;
        for (FailedInitialSpawn pending : candidates) {
            try {
                if (pending.retryableFailure && pending.physical != null
                        && !pending.view.runtime().physicallyFinalizedForHotUpgrade(
                                pending.physical)) {
                    // Retry revocation after a previously failed stop; a
                    // cancellation request is never a retirement receipt.
                    pending.physical.cancel();
                }
            } catch (RuntimeException | Error failure) {
                // One failing actor must not starve independent quarantines.
                if (cancellationFailure == null) cancellationFailure = failure;
                else if (failure != cancellationFailure)
                    cancellationFailure.addSuppressed(failure);
            }
        }

        int retired = 0;
        synchronized (failedSpawnLock) {
            for (int i = failedInitialSpawns.size() - 1; i >= 0; --i) {
                FailedInitialSpawn pending = failedInitialSpawns.get(i);
                if (pending.retryableFailure
                        && (pending.physical == null
                        || pending.view.runtime().physicallyFinalizedForHotUpgrade(
                                pending.physical))) {
                    // A failed generation pin debit leaves this exact record
                    // in place for retry rather than certifying false unload.
                    pending.lease.close();
                    failedInitialSpawns.remove(i);
                    retired++;
                }
            }
        }
        if (cancellationFailure instanceof RuntimeException failure) throw failure;
        if (cancellationFailure instanceof Error failure) throw failure;
        return retired;
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
    /**
     * Unpublished candidate owners. Entries are pre-reserved before physical
     * spawn so OOME/fault cleanup cannot lose the only retryable generation pin.
     */
    private final ArrayList<CandidateRetirement> retiredCandidates =
            new ArrayList<>();
    private Route active;
    // Bound once by a trusted supervisor. Once bound, the host raw send()
    // path must not bypass the proxy's immutable bounded admission ledger.
    private LogicalActorProxyMailboxV1 boundProxyMailbox;
    // Only the explicit compiler-ABI-bound scalar bridge may install this.
    // Null retains the original host i64 compatibility path.
    private SourceActorScalarWireV1.Binding scalarWireBinding;
    // Separate explicit ORM2 mode; no implicit protocol downgrade/fallback.
    private SourceActorTypedWireV2.Binding typedWireBinding;
    // Remains set even if physical admission throws: its outcome may be
    // ambiguous. A subsequently bound proxy could otherwise refund a raw
    // delivery with a cumulative physical drain.
    private boolean rawAdmissionAttempted;
    // Sole hot-upgrade publication authority while non-null. Guarded by routeLock.
    private SupervisorUpgradeTxn activeUpgradeTxn;
    private boolean closed;
    // Under routeLock. A close timeout may retain the active physical actor
    // in retiredRoutes until another supervisor cleanup attempt reaps it.
    private boolean activeRouteLeaseReleased;
    // A post-publication failure must not reopen a new physical route while
    // its retired predecessor is still live or its route lease is unsettled.
    // Only the supervisor reaper, after the last retired route is fully
    // finalized AND its generation lease is debited, may clear this fence.
    // Protected by routeLock; diagnostic route observations remain available.
    private boolean retirementQuarantined;
    // Prepublication transfer rollback may itself fail while the staged
    // candidate remains physically live. Do not discard the only handle
    // capable of releasing the old actor's ingress freeze. This supervisor
    // owner is retained until an explicit host reconciliation succeeds.
    // routeLock protects the field; closeLock serializes retry against close.
    private ActorRuntime.SourceActorTransfer pendingRollbackTransfer;
    // A proxy freeze rollback can fail its structural validation while
    // dispatch remains frozen. Preserve the exact ticket for a later
    // host-authorized retry; clearing activeUpgradeTxn is not a thaw receipt.
    // routeLock owns publication, closeLock serializes recovery and shutdown.
    private LogicalActorProxyMailboxV1.FreezeTicket pendingProxyRollbackTicket;

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
        ImageReceipts firstImage = ImageReceipts.from(view, actorTypeName);
        HotReloadManager.Generation.ActorRouteLease generationLease =
                generation.pinActorRoute();
        FailedInitialSpawn ownership = null;
        boolean registered = false;
        boolean successful = false;
        Throwable primaryFailure = null;
        try {
            // Record the pin before an executable actor can possibly start.
            // Quarantine registration may allocate; failure here still has no
            // physical actor and is safe to release in the finalizer.
            ownership = new FailedInitialSpawn(view, generationLease);
            synchronized (failedSpawnLock) {
                if (failedInitialSpawns.size() >= MAX_FAILED_INITIAL_SPAWNS)
                    throw new IllegalStateException(
                            "failed source actor spawn quarantine capacity exhausted");
                failedInitialSpawns.add(ownership);
                registered = true;
            }
            FailedInitialSpawn reservedOwner = ownership;
            ActorRuntime.ActorRef<Object> physical =
                    view.runtime().spawnCodeActor(
                            kind, view.policy(), view.executor(), actorTypeName,
                            ref -> reservedOwner.physical = ref);
            // Reference assignment is nonallocating. It precedes readiness
            // blocking so stop timeout always retains a retriable owner.
            ownership.physical = physical;
            physical.getReady().get(
                    toNanosSaturated(readyTimeout), TimeUnit.NANOSECONDS);
            LogicalSourceActorRouterV1 router = new LogicalSourceActorRouterV1(
                    kind, actorTypeName,
                    new Route(generation, generationLease, view, physical,
                            1L, 1L, firstImage));
            synchronized (failedSpawnLock) {
                failedInitialSpawns.remove(ownership);
            }
            successful = true;
            return router;
        } catch (InterruptedException | ExecutionException | TimeoutException
                | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!successful) {
                Throwable cleanupFailure = null;
                ActorRuntime.ActorRef<Object> physical =
                        ownership == null ? null : ownership.physical;
                if (physical != null) {
                    try {
                        physical.stop();
                    } catch (RuntimeException | Error failure) {
                        cleanupFailure = failure;
                    }
                }
                // Unlike the old implementation, requesting stop alone does
                // not authorize the generation pin debit. Keep the
                // pre-registered owner until physical finalization succeeds.
                try {
                    if (!registered || physical == null
                            || view.runtime().physicallyFinalizedForHotUpgrade(
                                    physical)) {
                        generationLease.close();
                        if (registered) {
                            synchronized (failedSpawnLock) {
                                failedInitialSpawns.remove(ownership);
                            }
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else if (cleanupFailure != failure)
                        cleanupFailure.addSuppressed(failure);
                }
                if (registered && ownership != null) {
                    // Publish failed-startup ownership only AFTER this caller
                    // finishes its stop/finalization cleanup attempt. An
                    // ordinary in-progress startup reservation must never be
                    // mistaken for a physically retired actor by a concurrent
                    // global reaper.
                    ownership.retryableFailure = true;
                }
                if (cleanupFailure != null) {
                    if (primaryFailure != null) {
                        if (cleanupFailure != primaryFailure)
                            primaryFailure.addSuppressed(cleanupFailure);
                    } else if (cleanupFailure instanceof RuntimeException failure) {
                        throw failure;
                    } else {
                        throw (Error) cleanupFailure;
                    }
                }
            }
        }
    }

    public LogicalActorRef ref() { return logicalRef; }

    /**
     * Trusted supervisor binding. Only one authoritative immutable mailbox
     * may ever own this physical route; rebinding would permit credit/ACK
     * forgery and bypass the frozen migration watermark.
     */
    void bindProxyMailboxForSupervisor(LogicalActorProxyMailboxV1 mailbox) {
        bindProxyMailboxForSupervisor(mailbox, false);
    }

    void bindProxyMailboxForSupervisor(
            LogicalActorProxyMailboxV1 mailbox, boolean compilerAbiScalar) {
        bindProxyMailboxForSupervisor(mailbox, compilerAbiScalar, false);
    }

    void bindProxyMailboxForSupervisor(
            LogicalActorProxyMailboxV1 mailbox,
            boolean compilerAbiScalar, boolean typedPrimitive) {
        if (compilerAbiScalar && typedPrimitive)
            throw new IllegalArgumentException("one source actor wire protocol per proxy");
        Objects.requireNonNull(mailbox, "mailbox");
        requireBlockingHostSurface("bind a compiler-ABI actor proxy");
        synchronized (routeLock) {
            requireOpenLocked();
            if (boundProxyMailbox != null)
                throw new IllegalStateException("logical route proxy already bound");
            if (activeUpgradeTxn != null || rawAdmissionAttempted)
                throw new IllegalStateException(
                        "proxy may not bind during upgrade or after raw actor send");
            var snapshot = mailbox.snapshot();
            if (snapshot.closed() || snapshot.dispatchFrozen()
                    || snapshot.retainedMessages() != 0
                    || snapshot.routeEpoch() != active.routeEpoch)
                throw new IllegalStateException(
                        "proxy binding requires an empty, open, epoch-aligned mailbox");
            // Stage and validate the compiler ABI receipt before publishing
            // mailbox ownership. A failed compiler receipt must leave the
            // router unbound rather than half-authorize physical sends.
            if (compilerAbiScalar
                    && !active.view.executor().actorReceivesProxyLong(actorTypeName)) {
                throw new SecurityException(
                        "compiler-ABI scalar proxy requires resolved ActorMail<int> receive");
            }
            SourceActorScalarWireV1.Binding proposed = compilerAbiScalar
                    ? new SourceActorScalarWireV1.Binding(
                            logicalId, active.incarnation,
                            active.view.executor().actorMessageAbiSha256(actorTypeName))
                    : null;
            SourceActorTypedWireV2.Binding proposedTyped = null;
            if (typedPrimitive) {
                var kind = active.view.executor()
                        .actorProxyWireScalarKind(actorTypeName)
                        .orElseThrow(() -> new SecurityException(
                                "ORM2 requires compiler-proven ActorMail<int|bool|string>"));
                proposedTyped = new SourceActorTypedWireV2.Binding(
                        new SourceActorScalarWireV1.Binding(
                                logicalId, active.incarnation,
                                active.view.executor().actorMessageAbiSha256(actorTypeName)),
                        kind);
            }
            // No allocation or throwing step between publishing these
            // internal fields and the authoritative mailbox ownership.
            scalarWireBinding = proposed;
            typedWireBinding = proposedTyped;
            boundProxyMailbox = mailbox;
        }
    }

    SourceActorTypedWireV2.Binding typedWireBindingForProxy(
            LogicalActorProxyMailboxV1 mailbox) {
        requireBlockingHostSurface("inspect ORM2 typed actor proxy binding");
        synchronized (routeLock) {
            requireOpenLocked();
            if (boundProxyMailbox != mailbox || typedWireBinding == null)
                throw new SecurityException("no compiler-verified ORM2 actor proxy bound");
            return typedWireBinding;
        }
    }

    SourceActorScalarWireV1.Binding scalarWireBindingForProxy(
            LogicalActorProxyMailboxV1 mailbox) {
        requireBlockingHostSurface("inspect compiler-ABI proxy binding");
        synchronized (routeLock) {
            requireOpenLocked();
            if (boundProxyMailbox != mailbox || scalarWireBinding == null)
                throw new SecurityException("no compiler-ABI scalar proxy bound");
            return scalarWireBinding;
        }
    }

    /**
     * Coherent diagnostic of code route and proxy epoch: lock ordering is
     * routeLock -> mailbox lock, identical to physical send/receipt/commit.
     * Two independent public snapshots would not form an atomic observation.
     */
    boolean proxyRouteEpochAlignedForTesting(LogicalActorProxyMailboxV1 mailbox) {
        requireBlockingHostSurface("inspect logical actor route alignment");
        synchronized (routeLock) {
            return boundProxyMailbox == mailbox
                    && active.routeEpoch == mailbox.snapshot().routeEpoch();
        }
    }

    /**
     * Immutable supervisor observation of one source-code image, logical and
     * physical incarnation, and bounded transport epoch. All fields are
     * sampled under ONE physical route monitor, nested outside the mailbox
     * ledger monitor. This is an observation witness, NOT yet a global
     * compiler/checkpoint/scheduler publication authorization.
     */
    record SupervisorPublication(
            LogicalAddress address,
            ActorRuntime.ActorAddress physicalAddress,
            long generationId,
            String codeSha256,
            String linkedCodeSha256,
            String stateSchemaSha256,
            String messageAbiSha256,
            LogicalActorProxyMailboxV1.Snapshot mailbox,
            boolean retirementQuarantined) { }

    SupervisorPublication supervisorPublication(
            LogicalActorProxyMailboxV1 mailbox) {
        requireBlockingHostSurface("observe supervisor actor image publication");
        Objects.requireNonNull(mailbox, "mailbox");
        synchronized (routeLock) {
            requireOpenLocked();
            if (boundProxyMailbox != mailbox)
                throw new SecurityException("foreign supervisor publication mailbox");
            Route current = active;
            // The route owns its compiler receipts as immutable values from
            // prepublication preparation. No compiler calls or digest hashes
            // occur while the supervisor observes the live route epoch.
            return new SupervisorPublication(
                    new LogicalAddress(
                            logicalId, current.incarnation, current.routeEpoch, kind),
                    current.physical.address(),
                    current.generation.id(),
                    current.generation.sha256(),
                    current.image.linkedCodeSha256(),
                    current.image.stateSchemaSha256(),
                    current.image.messageAbiSha256(),
                    mailbox.snapshot(), retirementQuarantined);
        }
    }

    /**
     * Opaque, trusted-host physical completion acknowledgment for ONE
     * runtime-admitted message. ActorRuntime mints the physical sequence and
     * the non-cancellable terminal observer before physical admission.
     * This is LOCAL provenance, not remote cryptographic authentication.
     */
    static final class ProxyDrainReceipt {
        private final LogicalSourceActorRouterV1 owner;
        private final Route routed;
        private final LogicalActorProxyMailboxV1 mailbox;
        private final LogicalActorProxyMailboxV1.Envelope envelope;
        // Bound exactly once after ActorRuntime returns its already-prepared
        // exact receipt. The shell itself is allocated before physical-send
        // reservation/admission, so successful physical acceptance is followed
        // only by this volatile reference publication and method return.
        private volatile ActorRuntime.PhysicalMailReceipt physicalReceipt;
        private boolean consumed;

        private ProxyDrainReceipt(
                LogicalSourceActorRouterV1 owner,
                Route routed,
                LogicalActorProxyMailboxV1 mailbox,
                LogicalActorProxyMailboxV1.Envelope envelope) {
            this.owner = owner;
            this.routed = routed;
            this.mailbox = mailbox;
            this.envelope = envelope;
        }

        void awaitAndComplete(
                LogicalActorProxyMailboxV1.Envelope exactEnvelope,
                Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            // The physical terminal Future may suspend indefinitely. An
            // opaque receipt must never become a backdoor blocking API for a
            // guest actor, scheduler terminal callback or async carrier.
            requireBlockingHostSurface("await physical proxy mail completion");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("drain deadline must be positive");
            if (exactEnvelope != envelope)
                throw new SecurityException("foreign immutable proxy envelope receipt");

            ActorRuntime.PhysicalMailReceipt exactPhysical = physicalReceipt;
            if (exactPhysical == null)
                throw new IllegalStateException(
                        "physical mail receipt shell was published before binding");
            // Only the target runtime can mint the accepted physical
            // sequence. It is not the proxy sequence: distinct physical
            // incarnations have independent counters after hot upgrade.
            if (exactPhysical.physical() != routed.physical()
                    || exactPhysical.acceptedSequence() < 0)
                throw new SecurityException("foreign physical mail receipt");
            // Claim this capability BEFORE waiting. In particular timeout,
            // interruption and terminal failure are ambiguous physical work:
            // none may leave a reusable receipt that could later refund
            // credits outside the owning supervisor's quarantine protocol.
            // The route monitor also serializes two racing awaiters so only
            // the first may ever observe/settle this physical acknowledgment.
            synchronized (owner.routeLock) {
                if (consumed)
                    throw new IllegalStateException("consumed physical mail receipt");
                if (owner.closed || owner.activeUpgradeTxn != null || owner.active != routed
                        || owner.boundProxyMailbox != mailbox)
                    throw new SecurityException("physical actor route changed before receipt claim");
                // Consume before any allocation-bearing mailbox observation.
                // An OOME or invariant violation during validation must NOT
                // re-arm an already physically accepted message receipt.
                consumed = true;
                if (mailbox.snapshot().routeEpoch() != routed.routeEpoch())
                    throw new SecurityException("physical and proxy route epochs differ");
                mailbox.requireOutstandingEnvelope(envelope, routed.routeEpoch());
            }
            // Completion may run guest turns: NEVER wait while holding the
            // route monitor. After any failed wait, supervisor reconciliation
            // is mandatory; this receipt must NOT be retried.
            exactPhysical.terminal().get(
                    toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
            if (exactPhysical.terminalDisposition()
                    != ActorRuntime.PhysicalMailDisposition.PROCESSED) {
                throw new IllegalStateException(
                        "successful physical terminal Future lacks PROCESSED disposition");
            }
            synchronized (owner.routeLock) {
                if (owner.closed || owner.activeUpgradeTxn != null || owner.active != routed
                        || owner.boundProxyMailbox != mailbox)
                    throw new SecurityException("physical actor route changed before completion");
                if (mailbox.snapshot().routeEpoch() != routed.routeEpoch())
                    throw new SecurityException("physical and proxy route epochs differ");
                // The mailbox validates exact envelope identity, route epoch
                // and prior physical attempt before refunding any credit.
                mailbox.completeAfterPhysicalReceipt(
                        envelope, routed.routeEpoch());
            }
        }
    }

    /**
     * Decode a trusted scalar proxy envelope against its bound transport mode.
     * In compiler-ABI mode the immutable frame also binds the logical actor
     * UUID/incarnation and compiler-issued message contract digest. Decoding
     * happens before the physical-send attempt is reserved or delivered.
     */
    ProxyDrainReceipt sendAndDrainForProxy(
            LogicalActorProxyMailboxV1 mailbox,
            LogicalActorProxyMailboxV1.Envelope envelope, long expectedRouteEpoch) {
        requireBlockingHostSurface("proxy-send a logical source actor");
        Objects.requireNonNull(mailbox, "mailbox");
        Objects.requireNonNull(envelope, "envelope");
        byte[] wire = envelope.payload();
        synchronized (routeLock) {
            awaitUpgradeLocked();
            requireOpenLocked();
            reapRetiredLocked();
            requireAdmissibleRouteLocked();
            Route current = active;
            if (boundProxyMailbox != mailbox)
                throw new SecurityException("foreign proxy mailbox on physical actor route");
            if (expectedRouteEpoch != current.routeEpoch)
                throw new SecurityException("stale proxy dispatch route epoch");
            final Object value;
            if (typedWireBinding != null) {
                // Decode the exact compiler-proven primitive before reserving
                // a physical send. Never downgrade a malformed ORM2 frame.
                value = SourceActorTypedWireV2.decode(typedWireBinding, wire);
            } else if (scalarWireBinding != null) {
                // An upgrade is only permitted between matching compiler
                // message ABIs; the old immutable frame stays compatible
                // with the new route and never captures an old code pointer.
                value = SourceActorScalarWireV1.decodeLong(
                        scalarWireBinding, wire);
            } else {
                if (wire.length != Long.BYTES)
                    throw new SecurityException(
                            "invalid legacy canonical scalar proxy wire length");
                long decoded = 0L;
                for (byte octet : wire)
                    decoded = (decoded << Byte.SIZE) | (octet & 255L);
                value = decoded;
            }
            // Allocate every router-owned receipt object BEFORE recording a
            // physical-send attempt. An OOME here therefore leaves the logical
            // envelope definitely unsent and eligible for exact-envelope
            // rollback; it cannot strand an attempted/accepted delivery.
            ProxyDrainReceipt receipt =
                    new ProxyDrainReceipt(this, current, mailbox, envelope);

            // Physical submission is reserved exactly once under the owning
            // mailbox's lock BEFORE touching the real actor mailbox. A second
            // trusted caller cannot deliver the same logical envelope while
            // its first drain receipt is still pending.
            mailbox.beginPhysicalSendAttempt(envelope, expectedRouteEpoch);
            ActorRuntime.PhysicalMailReceipt physical =
                    current.view.runtime().sendWithPhysicalReceipt(
                            current.physical, value);
            // ActorRuntime constructs/registers its own exact terminal witness
            // before physical admission. After a successful return, bind that
            // already-owned witness into the preallocated router shell using
            // only a volatile reference write: no router allocation remains.
            receipt.physicalReceipt = physical;
            return receipt;
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
        return startHostPinnedTaskTrackedForUpgrade(scheduler, frame).result();
    }

    <T> ActorHotUpgradeTaskBridgeV1.PinnedTask<T> startHostPinnedTaskTrackedForUpgrade(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        return startHostPinnedTaskTrackedForUpgrade(scheduler, frame, 0L);
    }

    /**
     * Bind a structured child Task scope to exactly one physical code epoch.
     * 0 is reserved for the trusted logical supervisor's root Task owner; an
     * epoch-bound child can never silently retarget an old captured frame to
     * newly published actor code. Check epoch AND acquire the physical lease
     * under the same route monitor, before scheduler admission.
     */
    <T> ActorHotUpgradeTaskBridgeV1.PinnedTask<T> startHostPinnedTaskTrackedForUpgrade(
            OresScheduler scheduler, OresScheduler.Task<T> frame,
            long expectedRouteEpoch) {
        requireBlockingHostSurface("start pinned source actor Task proof");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        if (expectedRouteEpoch < 0L)
            throw new IllegalArgumentException("invalid Task scope route epoch");
        synchronized (routeLock) {
            awaitUpgradeLocked();
            requireOpenLocked();
            reapRetiredLocked();
            requireAdmissibleRouteLocked();
            if (expectedRouteEpoch != 0L
                    && active.routeEpoch != expectedRouteEpoch) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "old actor generation Task scope cannot launch in new route epoch");
            }
            return ActorHotUpgradeTaskBridgeV1.startPinnedTracked(
                    active.view.runtime(), active.physical, scheduler, frame);
        }
    }

    /**
     * Canonical compiler-Task admission for a source actor. Route epoch
     * validation and ActorRuntime generation-pin acquisition happen under the
     * same route monitor that serializes hot publication. OresTaskScope then
     * owns the frame and releases the exact pin only from scheduler physical
     * quiescence (or an independently proven pre-admission rejection).
     */
    <T> OresTask<T> startCompilerTaskForUpgrade(
            OresTaskScope scope,
            OresScheduler scheduler,
            OresScheduler.Task<T> frame,
            long expectedRouteEpoch) {
        requireBlockingHostSurface("start compiler-owned source actor Task");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        if (expectedRouteEpoch < 0L)
            throw new IllegalArgumentException("invalid Task scope route epoch");
        synchronized (routeLock) {
            awaitUpgradeLocked();
            requireOpenLocked();
            reapRetiredLocked();
            requireAdmissibleRouteLocked();
            if (expectedRouteEpoch != 0L
                    && active.routeEpoch != expectedRouteEpoch) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "old actor generation Task scope cannot launch in new route epoch");
            }

            ActorRuntime runtime = active.view.runtime();
            ActorRuntime.ActorRef<Object> physical = active.physical;
            // Same lifecycle fence used by hot-upgrade transfer. If the pin
            // wins, transfer must observe it; if transfer already froze
            // ingress, this acquisition fails before scheduler admission.
            ActorRuntime.HotUpgradeExternalLease generation =
                    runtime.pinExternalWorkForHotUpgrade(physical);

            // Keep a fail-safe router-side lease owner until the canonical
            // scope has successfully returned its admitted Task. Constructing
            // generation::close can itself fail (e.g. OOME) before the scope
            // receives the callback, and pre-admission scope preparation can
            // also throw. In either case do not orphan an untracked pin.
            //
            // OresTaskScope retires the same lease on every verified
            // pre-admission rejection. ExternalLease.close is idempotent, so
            // router cleanup cannot double debit a successfully retired pin.
            // Scheduler.start promises not to throw after registration; a
            // post-registration failure instead settles via onQuiescent.
            try {
                return scope.startCompilerFrame(
                        scheduler, frame, generation::close);
            } catch (RuntimeException | Error admissionFailure) {
                try {
                    generation.close();
                } catch (RuntimeException | Error releaseFailure) {
                    if (releaseFailure != admissionFailure)
                        admissionFailure.addSuppressed(releaseFailure);
                }
                throw admissionFailure;
            }
        }
    }

    public HotReloadManager.Generation generation() {
        requireBlockingHostSurface("inspect logical actor generation");
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
        return upgradeInternal(candidateGeneration, readyTimeout, null);
    }

    /**
     * SHARED proxy route migration. A supervisor-held dispatch freeze is
     * committed under routeLock alongside the physical route replacement.
     * The owning bridge serializes dispatch vs upgrade with dispatchLock;
     * ordinary bounded admission remains independently open.
     */
    boolean upgradeToForProxy(
            HotReloadManager.Generation candidateGeneration,
            Duration readyTimeout,
            LogicalActorProxyMailboxV1 mailbox)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(mailbox, "mailbox");
        return upgradeInternal(candidateGeneration, readyTimeout, mailbox);
    }

    /**
     * Temporary package-level compatibility seam for older tests. The supplied
     * ticket is NEVER used for publication: it is validated and explicitly
     * rolled back under routeLock, then the real supervisor transaction
     * acquires its own fresh freeze. Production bridge code uses the three-
     * argument overload and cannot pass a reusable freeze capability.
     */
    @Deprecated
    boolean upgradeToForProxy(
            HotReloadManager.Generation candidateGeneration,
            Duration readyTimeout,
            LogicalActorProxyMailboxV1 mailbox,
            LogicalActorProxyMailboxV1.FreezeTicket legacyTicket)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(mailbox, "mailbox");
        Objects.requireNonNull(legacyTicket, "legacyTicket");
        synchronized (routeLock) {
            requireOpenLocked();
            if (boundProxyMailbox != mailbox)
                throw new SecurityException("foreign proxy upgrade authority");
            mailbox.requireFreezeTicketForRouteEpoch(
                    legacyTicket, active.routeEpoch);
            // Consume the caller-owned capability before any candidate
            // linking or supervisor transaction can begin.
            legacyTicket.rollback();
        }
        return upgradeInternal(candidateGeneration, readyTimeout, mailbox);
    }

    /**
     * Nonallocating supervisor preflight under routeLock. In particular, a
     * caller lacking the exact proxy freeze cannot cause candidate linking
     * as a side effect. Called both before linking and at route freeze.
     */
    private void requireUpgradeAuthorityLocked(
            HotReloadManager.Generation candidateGeneration,
            LogicalActorProxyMailboxV1 proxyMailbox) {
        if (!Thread.holdsLock(routeLock))
            throw new IllegalStateException("upgrade preflight requires route monitor");
        requireOpenLocked();
        if (activeUpgradeTxn != null)
            throw new IllegalStateException(
                    "logical actor upgrade already in progress");
        if (retirementQuarantined)
            throw new IllegalStateException(
                    "previous actor upgrade requires physical retirement reconciliation");
        if (proxyMailbox == null) {
            if (boundProxyMailbox != null)
                throw new SecurityException(
                        "bound proxy route requires supervisor-owned upgrade");
        } else if (boundProxyMailbox != proxyMailbox) {
            throw new SecurityException("foreign proxy upgrade authority");
        }
        if (candidateGeneration == active.generation)
            throw new IllegalArgumentException(
                    "candidate generation is already the active logical route");
        if (active.routeEpoch == Long.MAX_VALUE)
            throw new IllegalStateException("logical actor route epoch exhausted");
    }

    private void requireActiveUpgradeLocked(SupervisorUpgradeTxn txn) {
        if (!Thread.holdsLock(routeLock))
            throw new IllegalStateException("upgrade state requires route monitor");
        if (activeUpgradeTxn != txn)
            throw new IllegalStateException(
                    "supervisor upgrade transaction lost publication authority");
    }

    private void finishUpgradeLocked(
            SupervisorUpgradeTxn txn, UpgradePhase terminalPhase) {
        requireActiveUpgradeLocked(txn);
        if (terminalPhase != UpgradePhase.COMPLETE
                && terminalPhase != UpgradePhase.RETIREMENT_PENDING
                && terminalPhase != UpgradePhase.ABANDONED) {
            throw new IllegalArgumentException(
                    "nonterminal supervisor upgrade phase");
        }
        // Set the persistent fail-closed fence BEFORE waking route waiters.
        // Clearing activeUpgradeTxn alone would otherwise reopen physical
        // message/Task ingress after an uncertain old-actor retirement.
        if (terminalPhase == UpgradePhase.RETIREMENT_PENDING)
            retirementQuarantined = true;
        txn.phase = terminalPhase;
        activeUpgradeTxn = null;
        routeLock.notifyAll();
    }

    private boolean upgradeInternal(
            HotReloadManager.Generation candidateGeneration,
            Duration readyTimeout,
            LogicalActorProxyMailboxV1 proxyMailbox)
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

        synchronized (routeLock) {
            requireUpgradeAuthorityLocked(candidateGeneration, proxyMailbox);
        }

        HotReloadManager.ActorGenerationView candidate =
                candidateGeneration.linkActorGeneration(actorTypeName);
        ImageReceipts candidateImage =
                ImageReceipts.from(candidate, actorTypeName);
        if (typedWireBinding != null) {
            var candidateKind = candidate.executor()
                    .actorProxyWireScalarKind(actorTypeName)
                    .orElseThrow(() -> new SecurityException(
                            "upgraded ORM2 actor lost its compiler primitive receive proof"));
            if (candidateKind != typedWireBinding.kind()
                    || !typedWireBinding.identity().messageAbiSha256()
                            .equals(candidateImage.messageAbiSha256())) {
                throw new SecurityException(
                        "upgraded ORM2 actor primitive kind or compiler message ABI drift");
            }
        }
        if (scalarWireBinding != null) {
            if (!candidate.executor().actorReceivesProxyLong(actorTypeName)) {
                throw new SecurityException(
                        "upgraded scalar actor lacks compiler ActorMail<int> receive proof");
            }
            if (!scalarWireBinding.messageAbiSha256()
                    .equals(candidateImage.messageAbiSha256())) {
                throw new SecurityException(
                        "source actor compiler message ABI changed across logical route upgrade");
            }
        }

        HotReloadManager.Generation.ActorRouteLease candidateLease =
                candidateGeneration.pinActorRoute();
        SupervisorUpgradeTxn txn = null;
        Throwable primaryFailure = null;
        try {
            synchronized (routeLock) {
                requireUpgradeAuthorityLocked(candidateGeneration, proxyMailbox);
                reapRetiredLocked();
                Route prior = active;
                CandidateRetirement candidateRetirement =
                        new CandidateRetirement(
                                candidateGeneration,
                                candidateLease,
                                candidate,
                                prior.incarnation,
                                prior.routeEpoch,
                                candidateImage);
                txn = new SupervisorUpgradeTxn(
                        prior, candidateRetirement, proxyMailbox);

                // Candidate pin ownership is registered before any executable
                // candidate exists. activeUpgradeTxn is then the single route,
                // mailbox-freeze and publication authority.
                retiredCandidates.add(candidateRetirement);
                activeUpgradeTxn = txn;

                try {
                    if (proxyMailbox != null) {
                        txn.proxyTicket =
                                proxyMailbox.freezeDispatchForUpgrade();
                        if (!txn.proxyTicket.drained()) {
                            txn.proxyTicket.rollback();
                            txn.proxyTicket = null;
                            // Keep the pre-reserved candidate owner until the
                            // finalizer closes the generation lease. Removing
                            // ownership before a fallible lease debit would
                            // turn a failed debit into an ownerless pin.
                            return false;
                        }
                    }
                    txn.phase = UpgradePhase.STAGING;
                } catch (RuntimeException | Error setupFailure) {
                    if (txn.proxyTicket != null) {
                        try {
                            txn.proxyTicket.rollback();
                            txn.proxyTicket = null;
                        } catch (RuntimeException | Error rollbackFailure) {
                            // A failed structural rollback may leave dispatch
                            // frozen. Preserve the exact ticket from setup as
                            // well as from the later transaction finalizer.
                            // Otherwise ABANDONED would clear the only owner
                            // and the supervisor could never retry the thaw.
                            if (pendingProxyRollbackTicket != null
                                    && pendingProxyRollbackTicket != txn.proxyTicket) {
                                throw new IllegalStateException(
                                        "a different unresolved proxy freeze owns setup rollback",
                                        rollbackFailure);
                            }
                            pendingProxyRollbackTicket = txn.proxyTicket;
                            // Transfer sole rollback authority out of the
                            // transaction. The outer finalizer must not retry
                            // and consume this ticket behind the supervisor
                            // owner's back, leaving a stale pending handle.
                            txn.proxyTicket = null;
                            retirementQuarantined = true;
                            if (rollbackFailure != setupFailure)
                                setupFailure.addSuppressed(rollbackFailure);
                        }
                    }
                    // Candidate owner remains registered until the
                    // unified finalizer proves its lease debit succeeded.
                    finishUpgradeLocked(txn, UpgradePhase.ABANDONED);
                    throw setupFailure;
                }
            }

            var started = txn.prior.view.runtime().tryBeginSourceActorTransfer(
                    txn.prior.physical,
                    logicalId,
                    txn.prior.incarnation);
            if (started.isEmpty()) return false;
            synchronized (routeLock) {
                // Publish acquired cleanup ownership before any invariant check
                // that can throw. Even a corrupted transaction state must not
                // strand the old actor ingress freeze outside this txn.
                txn.transfer = started.get();
                requireActiveUpgradeLocked(txn);
                if (txn.phase != UpgradePhase.STAGING) {
                    throw new IllegalStateException(
                            "source transfer acquired outside staging transaction");
                }
            }

            // The callback runs before restoration or registry admission.
            // Pre-register the exact candidate in both existing cleanup
            // owners, including when spawn throws without returning a ref.
            SupervisorUpgradeTxn stagingOwner = txn;
            ActorRuntime.ActorRef<Object> replacement =
                    candidate.runtime().spawnCodeActorFromTransfer(
                            txn.transfer,
                            candidate.executor(),
                            ref -> {
                                stagingOwner.replacement = ref;
                                stagingOwner.candidateRetirement.physical = ref;
                            });
            synchronized (routeLock) {
                // The physical candidate exists now. Store it into BOTH cleanup
                // owners before any validation that can fail; otherwise a
                // faulted invariant could leave executable candidate code with
                // no stop/finalization path in the transaction.
                txn.replacement = replacement;
                txn.candidateRetirement.physical = replacement;
                requireActiveUpgradeLocked(txn);
                if (txn.phase != UpgradePhase.STAGING) {
                    throw new IllegalStateException(
                            "candidate physical publication outside staging transaction");
                }
            }
            replacement.getReady().get(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS);

            synchronized (routeLock) {
                requireActiveUpgradeLocked(txn);
                if (closed)
                    throw new IllegalStateException(
                            "logical actor router closed while upgrade staged");
                if (txn.phase != UpgradePhase.STAGING
                        || active != txn.prior) {
                    throw new IllegalStateException(
                            "logical actor route changed while candidate staged");
                }

                Route next = new Route(
                        candidateGeneration,
                        candidateLease,
                        candidate,
                        replacement,
                        txn.prior.incarnation,
                        Math.addExact(txn.prior.routeEpoch, 1L),
                        candidateImage);
                txn.next = next;

                if (retiredRoutes.contains(txn.prior))
                    throw new IllegalStateException(
                            "active route already registered for retirement");
                retiredRoutes.add(txn.prior);
                try {
                    // All owner validation must precede the irreversible
                    // mailbox/route publication. Never throw an ownership
                    // invariant exception after active becomes authoritative.
                    if (!retiredCandidates.contains(txn.candidateRetirement)
                            || txn.candidateRetirement.retirementEligible
                            || txn.candidateRetirement.publishedToRoute
                            || txn.candidateRetirement.physical != replacement) {
                        throw new IllegalStateException(
                                "candidate staging authority lost before route commit");
                    }
                    if (txn.proxyTicket != null) {
                        txn.proxyMailbox.requireFreezeTicketForRouteEpoch(
                                txn.proxyTicket, txn.prior.routeEpoch);
                        txn.proxyTicket.commit();
                    }

                    // Single point of no return: proxy epoch (when bound) and
                    // physical route publish while this txn exclusively owns
                    // routeLock and all candidate/retirement authority.
                    active = next;
                    txn.candidateRetirement.publishedToRoute = true;
                    txn.candidateLeaseTransferred = true;
                    txn.phase = UpgradePhase.PUBLISHED;
                    // Do not remove staging bookkeeping after the point of
                    // no return. Even a fault-injected remove() exception
                    // cannot invalidate a successfully published route. The
                    // next supervisor reap drops this entry WITHOUT touching
                    // the active/retired Route's generation lease.
                } finally {
                    if (!txn.published()) retiredRoutes.remove(txn.prior);
                }
            }

            synchronized (routeLock) {
                requireActiveUpgradeLocked(txn);
                if (txn.phase != UpgradePhase.PUBLISHED) {
                    throw new IllegalStateException(
                            "old-route retirement started outside published transaction");
                }
                txn.phase = UpgradePhase.RETIRING;
            }
            try {
                txn.transfer.retireSourceAfterPublication(replacement);
            } finally {
                synchronized (routeLock) {
                    txn.transfer = null;
                }
            }

            if (txn.prior.physical.awaitTermination(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS)) {
                synchronized (routeLock) {
                    requireActiveUpgradeLocked(txn);
                    if (!txn.prior.view.runtime().physicallyFinalizedForHotUpgrade(
                            txn.prior.physical)) {
                        throw new IllegalStateException(
                                "old source actor terminated without verified "
                                        + "physical finalization");
                    }
                    txn.prior.generationLease.close();
                    retiredRoutes.remove(txn.prior);
                    finishUpgradeLocked(txn, UpgradePhase.COMPLETE);
                }
            } else {
                synchronized (routeLock) {
                    requireActiveUpgradeLocked(txn);
                    finishUpgradeLocked(
                            txn, UpgradePhase.RETIREMENT_PENDING);
                }
                throw new IllegalStateException(
                        "old physical actor did not terminate after route publication; "
                                + "its generation remains pinned for later reaping");
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

            if (txn != null && !txn.published()) {
                ActorRuntime.ActorRef<Object> replacement = txn.replacement;
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
                                if (!retiredCandidates.contains(
                                        txn.candidateRetirement)) {
                                    throw new IllegalStateException(
                                            "unpublished candidate lost its "
                                                    + "supervisor retirement owner");
                                }
                                txn.candidateRetirement.retirementEligible = true;
                            }
                            txn.candidateLeaseTransferred = true;
                        } else {
                            synchronized (routeLock) {
                                if (!retiredCandidates.contains(
                                        txn.candidateRetirement)) {
                                    throw new IllegalStateException(
                                            "finalized unpublished candidate lost "
                                                    + "its supervisor retirement owner");
                                }
                                // Keep ownership registered. The unified
                                // lease-debit block below closes BEFORE forget.
                            }
                        }
                    } catch (RuntimeException | Error probeFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = probeFailure;
                        } else if (probeFailure != cleanupFailure) {
                            cleanupFailure.addSuppressed(probeFailure);
                        }
                        synchronized (routeLock) {
                            if (!retiredCandidates.contains(
                                    txn.candidateRetirement)) {
                                throw new IllegalStateException(
                                        "ambiguous candidate cleanup lost its "
                                                + "supervisor retirement owner");
                            }
                            txn.candidateRetirement.retirementEligible = true;
                        }
                        txn.candidateLeaseTransferred = true;
                    }
                } else {
                    synchronized (routeLock) {
                        if (!retiredCandidates.contains(
                                txn.candidateRetirement)) {
                            throw new IllegalStateException(
                                    "unspawned candidate lost its supervisor owner");
                        }
                        // Do not publish retirement eligibility yet. The
                        // originating finalizer still owns the first lease
                        // debit attempt and must close-before-forget.
                    }
                }

                if (txn.transfer != null) {
                    try {
                        txn.transfer.close();
                    } catch (RuntimeException | Error rollbackFailure) {
                        // This can fail after the staged candidate was
                        // admitted but before it physically finalized. The
                        // transfer's freeze is STILL OWNED and old ingress
                        // must not resume. Register the exact rollback handle
                        // before releasing supervisor transaction admission.
                        synchronized (routeLock) {
                            if (pendingRollbackTransfer != null
                                    && pendingRollbackTransfer != txn.transfer) {
                                throw new IllegalStateException(
                                        "concurrent actor rollback lost sole freeze owner",
                                        rollbackFailure);
                            }
                            pendingRollbackTransfer = txn.transfer;
                            retirementQuarantined = true;
                        }
                        if (cleanupFailure == null) {
                            cleanupFailure = rollbackFailure;
                        } else if (rollbackFailure != cleanupFailure) {
                            cleanupFailure.addSuppressed(rollbackFailure);
                        }
                    }
                    txn.transfer = null;
                }

                synchronized (routeLock) {
                    if (txn.proxyTicket != null) {
                        try {
                            txn.proxyTicket.rollback();
                            txn.proxyTicket = null;
                        } catch (RuntimeException | Error rollbackFailure) {
                            // The mailbox may have rejected the rollback while
                            // still holding the exact freeze. Never discard
                            // its sole retry capability or reopen Task ingress.
                            if (pendingProxyRollbackTicket != null
                                    && pendingProxyRollbackTicket != txn.proxyTicket) {
                                throw new IllegalStateException(
                                        "a different unresolved proxy freeze owns rollback",
                                        rollbackFailure);
                            }
                            pendingProxyRollbackTicket = txn.proxyTicket;
                            // Ownership has moved to the supervisor recovery
                            // ledger. Keep the transaction from retaining a
                            // second logical owner for the same freeze ticket.
                            txn.proxyTicket = null;
                            retirementQuarantined = true;
                            if (cleanupFailure == null) {
                                cleanupFailure = rollbackFailure;
                            } else if (rollbackFailure != cleanupFailure) {
                                cleanupFailure.addSuppressed(rollbackFailure);
                            }
                        }
                    }
                    if (activeUpgradeTxn == txn) {
                        finishUpgradeLocked(txn, UpgradePhase.ABANDONED);
                    }
                }
            } else if (txn != null) {
                synchronized (routeLock) {
                    if (activeUpgradeTxn == txn) {
                        finishUpgradeLocked(
                                txn, UpgradePhase.RETIREMENT_PENDING);
                    }
                }
            }

            if (txn == null || !txn.candidateLeaseTransferred) {
                if (txn == null) {
                    try {
                        candidateLease.close();
                    } catch (RuntimeException | Error leaseFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = leaseFailure;
                        } else if (leaseFailure != cleanupFailure) {
                            cleanupFailure.addSuppressed(leaseFailure);
                        }
                    }
                } else {
                    synchronized (routeLock) {
                        boolean ledgerOwned = retiredCandidates.contains(
                                txn.candidateRetirement);
                        if (!ledgerOwned) {
                            try {
                                candidateLease.close();
                            } catch (RuntimeException | Error leaseFailure) {
                                if (cleanupFailure == null) {
                                    cleanupFailure = leaseFailure;
                                } else if (leaseFailure != cleanupFailure) {
                                    cleanupFailure.addSuppressed(leaseFailure);
                                }
                            }
                        } else {
                            ActorRuntime.ActorRef<Object> physical =
                                    txn.candidateRetirement.physical;
                            boolean physicallyReleasable = physical == null;
                            if (physical != null) {
                                try {
                                    physicallyReleasable =
                                            candidate.runtime()
                                                    .physicallyFinalizedForHotUpgrade(
                                                            physical);
                                } catch (RuntimeException | Error probeFailure) {
                                    txn.candidateRetirement.retirementEligible = true;
                                    txn.candidateLeaseTransferred = true;
                                    if (cleanupFailure == null) {
                                        cleanupFailure = probeFailure;
                                    } else if (probeFailure != cleanupFailure) {
                                        cleanupFailure.addSuppressed(probeFailure);
                                    }
                                }
                            }

                            if (!txn.candidateLeaseTransferred) {
                                if (!physicallyReleasable) {
                                    // An executable/unknown candidate must
                                    // remain ledger-owned. This should normally
                                    // have been transferred earlier, but fail
                                    // closed rather than debit code under it.
                                    txn.candidateRetirement.retirementEligible = true;
                                    txn.candidateLeaseTransferred = true;
                                    IllegalStateException ownershipFailure =
                                            new IllegalStateException(
                                                    "candidate physical lifetime "
                                                            + "still owns generation lease");
                                    if (cleanupFailure == null) {
                                        cleanupFailure = ownershipFailure;
                                    } else {
                                        cleanupFailure.addSuppressed(
                                                ownershipFailure);
                                    }
                                } else {
                                    try {
                                        // Fallible debit occurs while the
                                        // owner is still registered and under
                                        // the same route monitor as reaping.
                                        candidateLease.close();
                                        retiredCandidates.remove(
                                                txn.candidateRetirement);
                                        txn.candidateLeaseTransferred = true;
                                    } catch (RuntimeException | Error leaseFailure) {
                                        // Only now hand retry authority to
                                        // opportunistic reapers.
                                        txn.candidateRetirement.retirementEligible = true;
                                        txn.candidateLeaseTransferred = true;
                                        if (cleanupFailure == null) {
                                            cleanupFailure = leaseFailure;
                                        } else if (leaseFailure != cleanupFailure) {
                                            cleanupFailure.addSuppressed(
                                                    leaseFailure);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    if (cleanupFailure != primaryFailure)
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
     * Host/supervisor recovery when a physical stop request failed before the
     * retired actor was stopped. Merely polling a finalization marker cannot
     * make progress in this state.
     *
     * <p>Never stop a staging actor or the open active route. Snapshot only
     * previously retired physical refs while holding routeLock, issue stop
     * requests OUTSIDE the route monitor, and retain every retirement owner
     * until a verified physical finalization permits its generation debit.
     * A failed retry remains retryable; caller must not treat it as success.
     */
    public int retryRetiredPhysicalStops() {
        requireBlockingHostSurface("retry retired actor physical stops");
        synchronized (closeLock) {
            final java.util.ArrayList<ActorRuntime.ActorRef<Object>> pending =
                    new java.util.ArrayList<>();
            synchronized (routeLock) {
                if (activeUpgradeTxn != null) {
                    throw new IllegalStateException(
                            "cannot retry physical retirement during active upgrade");
                }
                for (Route old : retiredRoutes) {
                    if ((old != active || closed)
                            && !old.view.runtime().physicallyFinalizedForHotUpgrade(
                                    old.physical)) {
                        pending.add(old.physical);
                    }
                }
                for (CandidateRetirement old : retiredCandidates) {
                    ActorRuntime.ActorRef<Object> physical = old.physical;
                    if (!old.publishedToRoute && old.retirementEligible
                            && physical != null
                            && !old.view.runtime().physicallyFinalizedForHotUpgrade(
                                    physical)) {
                        pending.add(physical);
                    }
                }
            }
            Throwable firstFailure = null;
            for (ActorRuntime.ActorRef<Object> ref : pending) {
                try {
                    ref.stop();
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else if (firstFailure != failure) {
                        try {
                            firstFailure.addSuppressed(failure);
                        } catch (RuntimeException | Error ignored) {
                            // Do not let an OOME in exception diagnostics
                            // skip the remaining independent retirements.
                        }
                    }
                }
            }
            final int released;
            synchronized (routeLock) {
                released = reapRetiredLocked();
            }
            if (firstFailure instanceof RuntimeException failure)
                throw failure;
            if (firstFailure instanceof Error failure)
                throw failure;
            return released;
        }
    }

    /**
     * Release generation leases for previously published physical incarnations
     * once their runtimes report full actor finalization.
     */
    public int reapRetired() {
        requireBlockingHostSurface("reap retired source actor generations");
        synchronized (routeLock) {
            return reapRetiredLocked();
        }
    }

    private int reapRetiredLocked() {
        int released = 0;
        for (int i = retiredRoutes.size() - 1; i >= 0; --i) {
            Route old = retiredRoutes.get(i);
            // The still-active transaction exclusively owns its old route
            // until finishUpgradeLocked commits a terminal phase. An
            // opportunistic reaper must not debit that same generation lease
            // while the upgrader is completing physical retirement.
            if (activeUpgradeTxn != null && old == activeUpgradeTxn.prior)
                continue;
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
        for (int i = retiredCandidates.size() - 1; i >= 0; --i) {
            CandidateRetirement old = retiredCandidates.get(i);
            if (old.publishedToRoute) {
                // A successful commit transferred this lease to the live
                // Route. This is NOT an independently retired candidate.
                retiredCandidates.remove(i);
                continue;
            }
            ActorRuntime.ActorRef<Object> physical = old.physical;
            if (old.retirementEligible
                    && (physical == null
                    || old.view.runtime().physicallyFinalizedForHotUpgrade(
                            physical))) {
                // Close before forgetting ownership. This also retries a
                // pre-spawn candidate lease debit that previously failed.
                old.generationLease.close();
                retiredCandidates.remove(i);
                released++;
            }
        }
        if (retirementQuarantined && activeUpgradeTxn == null
                && pendingRollbackTransfer == null
                && pendingProxyRollbackTicket == null
                && retiredRoutes.isEmpty() && retiredCandidates.isEmpty()) {
            // A failed rollback may leave a physically live, unpublished
            // candidate even after every old route and transfer owner is gone.
            // Do not restore message/Task admission until ALL candidate
            // retirement owners have also closed their code-image leases.
            // Published candidates are nonowning ledger witnesses and were
            // already removed in the loop above.
            retirementQuarantined = false;
        }
        return released;
    }

    /**
     * Host supervisor retry for an unpublished candidate whose physical
     * cleanup failed and left its predecessor ingress frozen. This operation
     * may block while the staged candidate physically terminates, so never
     * call it from a guest/actor/scheduler carrier. It never republishes a
     * candidate or refunds any mailbox credit.
     *
     * A failed retry preserves the exact transfer owner and quarantine.
     * A successful retry releases the old ingress freeze, then permits the
     * existing retirement ledger to reconcile code-generation pins.
     */
    public boolean reconcileFailedUpgradeRollback() {
        requireBlockingHostSurface("reconcile failed actor upgrade rollback");
        synchronized (closeLock) {
            final ActorRuntime.SourceActorTransfer owned;
            synchronized (routeLock) {
                // An absent failed-rollback owner must never turn a live
                // supervisor publication into a false reconciliation success.
                if (activeUpgradeTxn != null) {
                    throw new IllegalStateException(
                            "cannot reconcile rollback during an active publication");
                }
                owned = pendingRollbackTransfer;
                // No rollback owner does NOT imply the separate
                // postpublication retirement quarantine has cleared.
                if (owned == null) return !retirementQuarantined;
            }
            // No route monitor during bounded candidate stop/join. Retained
            // retirementQuarantined still rejects physical sends and Tasks.
            owned.close();
            synchronized (routeLock) {
                if (pendingRollbackTransfer != owned)
                    throw new IllegalStateException(
                            "actor rollback reconciliation owner changed");
                pendingRollbackTransfer = null;
                reapRetiredLocked();
                return !retirementQuarantined;
            }
        }
    }

    /**
     * Retry a failed PRE-publication proxy freeze rollback, retaining its
     * original ticket until the mailbox itself confirms thaw. A physically
     * live candidate or an unresolved source transfer must be reconciled
     * first. This never commits a new route epoch or refunds message credits.
     */
    public boolean reconcileFailedProxyRollback() {
        requireBlockingHostSurface("reconcile failed source proxy rollback");
        synchronized (closeLock) {
            synchronized (routeLock) {
                if (activeUpgradeTxn != null)
                    throw new IllegalStateException(
                            "cannot reconcile proxy rollback during publication");
                LogicalActorProxyMailboxV1.FreezeTicket ticket =
                        pendingProxyRollbackTicket;
                if (ticket == null) return !retirementQuarantined;
                reapRetiredLocked();
                if (pendingRollbackTransfer != null
                        || !retiredRoutes.isEmpty()
                        || !retiredCandidates.isEmpty()) {
                    // Candidate physical completion, not merely rollback
                    // eligibility, authorizes old proxy dispatch to resume.
                    return false;
                }
                ticket.rollback();
                // No fallible operation between successful rollback and
                // forgetting the consumed ticket.
                pendingProxyRollbackTicket = null;
                reapRetiredLocked();
                return !retirementQuarantined;
            }
        }
    }

    /**
     * Test/diagnostic physical address only. User routing must use LogicalActorRef.
     */
    ActorRuntime.ActorAddress physicalAddressForTesting() {
        requireBlockingHostSurface("inspect physical source actor address");
        synchronized (routeLock) {
            requireOpenLocked();
            reapRetiredLocked();
            return active.physical.address();
        }
    }

    ActorRuntime.ActorRef<Object> physicalRefForTesting() {
        requireBlockingHostSurface("inspect physical source actor reference");
        synchronized (routeLock) {
            requireOpenLocked();
            return active.physical;
        }
    }

    private void awaitUpgradeLocked() {
        boolean interrupted = false;
        while (activeUpgradeTxn != null && !closed) {
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

    private void requireAdmissibleRouteLocked() {
        if (retirementQuarantined)
            throw new IllegalStateException(
                    "actor route quarantined until verified physical retirement");
    }

    private static void requireBlockingHostSurface(String operation) {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()
                || ActorRuntime.isActorCarrierThread()
                || OresScheduler.current() != null
                || OresScheduler.isSchedulerCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()) {
            // Terminal/scheduler callbacks may have no active CURRENT task,
            // but still run on a guest carrier that must never block here.
            throw new SecurityException(
                    operation
                            + " is a host/supervisor blocking operation; "
                            + "actor, ActorGroup CONTROL, scheduler and async "
                            + "carriers cannot use host-only routing");
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
                if (!closed) awaitUpgradeLocked();
                if (pendingRollbackTransfer != null) {
                    throw new IllegalStateException(
                            "cannot retire source actor while an unpublished "
                                    + "candidate owns a failed rollback freeze; "
                                    + "reconcileFailedUpgradeRollback first");
                }
                if (pendingProxyRollbackTicket != null) {
                    throw new IllegalStateException(
                            "cannot retire source actor while proxy mailbox "
                                    + "has an unresolved freeze; "
                                    + "reconcileFailedProxyRollback first");
                }
                if (closed && activeRouteLeaseReleased
                        && retiredRoutes.isEmpty()
                        && retiredCandidates.isEmpty()) {
                    return;
                }
                route = active;
                // Reserve the active route's retirement owner BEFORE sealing
                // logical admission or calling stop(). List growth can throw
                // OOME. An allocation failure here leaves the router open and
                // its physical actor/pin unchanged, so cleanup is retryable.
                if (!activeRouteLeaseReleased && !retiredRoutes.contains(route))
                    retiredRoutes.add(route);
                if (!closed) {
                    closed = true;
                    routeLock.notifyAll();
                }
            }

            // Physical stop may throw after a partial shutdown. Retirement
            // ownership already exists; no allocation or registration is
            // needed in the error path. Never silently release this pin.
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
            final ArrayList<CandidateRetirement> oldCandidates;
            synchronized (routeLock) {
                reapRetiredLocked();
                oldRoutes = new ArrayList<>(retiredRoutes);
                oldCandidates = new ArrayList<>(retiredCandidates);
            }
            for (Route old : oldRoutes) {
                try {
                    old.physical.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    break;
                }
            }
            if (!interrupted) {
                for (CandidateRetirement old : oldCandidates) {
                    if (!old.retirementEligible) {
                        throw new IllegalStateException(
                                "router close observed a staging candidate "
                                        + "after upgrade serialization");
                    }
                    ActorRuntime.ActorRef<Object> physical = old.physical;
                    if (physical == null) continue;
                    try {
                        physical.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                        break;
                    }
                }
            }

            synchronized (routeLock) {
                reapRetiredLocked();
                if (activeTerminated && !activeRouteLeaseReleased) {
                    // A termination wait is NOT by itself permission to
                    // unload code. Match the source upgrade and reaper's
                    // independent physical-finalization check before debit.
                    if (!route.view.runtime().physicallyFinalizedForHotUpgrade(
                            route.physical)) {
                        throw new IllegalStateException(
                                "logical actor terminated without verified "
                                        + "physical finalization; generation stays pinned");
                    }
                    route.generationLease.close();
                    activeRouteLeaseReleased = true;
                    retiredRoutes.remove(route);
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (!activeTerminated
                        || !retiredRoutes.isEmpty()
                        || !retiredCandidates.isEmpty()) {
                    throw new IllegalStateException(
                            "logical actor close did not observe complete physical retirement; "
                                    + "generation lease(s) remain pinned for supervisor retry");
                }
            }
        }
    }
}
