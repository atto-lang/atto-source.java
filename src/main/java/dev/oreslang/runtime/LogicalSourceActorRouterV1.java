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
        ImageReceipts firstImage = ImageReceipts.from(view, actorTypeName);
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
                            1L,
                            firstImage));
        } finally {
            if (!ready) {
                if (physical != null) physical.stop();
                generationLease.close();
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
            if (upgrading || rawAdmissionAttempted)
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
            LogicalActorProxyMailboxV1.Snapshot mailbox) { }

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
                    mailbox.snapshot());
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
                if (owner.closed || owner.upgrading || owner.active != routed
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
                if (owner.closed || owner.upgrading || owner.active != routed
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
        return upgradeInternal(candidateGeneration, readyTimeout, null, null);
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
            LogicalActorProxyMailboxV1 mailbox,
            LogicalActorProxyMailboxV1.FreezeTicket ticket)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(mailbox, "mailbox");
        Objects.requireNonNull(ticket, "ticket");
        return upgradeInternal(candidateGeneration, readyTimeout, mailbox, ticket);
    }

    /**
     * Nonallocating supervisor preflight under routeLock. In particular, a
     * caller lacking the exact proxy freeze cannot cause candidate linking
     * as a side effect. Called both before linking and at route freeze.
     */
    private void requireUpgradeAuthorityLocked(
            HotReloadManager.Generation candidateGeneration,
            LogicalActorProxyMailboxV1 proxyMailbox,
            LogicalActorProxyMailboxV1.FreezeTicket proxyTicket) {
        if (!Thread.holdsLock(routeLock))
            throw new IllegalStateException("upgrade preflight requires route monitor");
        requireOpenLocked();
        if (upgrading)
            throw new IllegalStateException(
                    "logical actor upgrade already in progress");
        if (proxyMailbox == null) {
            if (boundProxyMailbox != null)
                throw new SecurityException(
                        "bound proxy route requires supervisor-owned upgrade");
        } else {
            if (boundProxyMailbox != proxyMailbox)
                throw new SecurityException("foreign proxy upgrade authority");
            proxyMailbox.requireFreezeTicketForRouteEpoch(
                    proxyTicket, active.routeEpoch);
        }
        if (candidateGeneration == active.generation)
            throw new IllegalArgumentException(
                    "candidate generation is already the active logical route");
        if (active.routeEpoch == Long.MAX_VALUE)
            throw new IllegalStateException("logical actor route epoch exhausted");
    }

    private boolean upgradeInternal(
            HotReloadManager.Generation candidateGeneration,
            Duration readyTimeout,
            LogicalActorProxyMailboxV1 proxyMailbox,
            LogicalActorProxyMailboxV1.FreezeTicket proxyTicket)
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

        // Refuse unauthorized, frozen-ticket, stale-route or same-generation
        // requests BEFORE invoking the candidate loader/compiler. Otherwise
        // a denied host request could still compile/link/pin arbitrary code.
        // Revalidate after linking: another supervisor may have closed or
        // upgraded the logical route while the loader worked.
        synchronized (routeLock) {
            requireUpgradeAuthorityLocked(
                    candidateGeneration, proxyMailbox, proxyTicket);
        }

        // Authorized candidate linking is deliberately outside routeLock:
        // message admission on the old route must not wait for compilation.
        HotReloadManager.ActorGenerationView candidate =
                candidateGeneration.linkActorGeneration(actorTypeName);
        ImageReceipts candidateImage =
                ImageReceipts.from(candidate, actorTypeName);
        // A compiler-ABI-bound proxy retains already-admitted immutable
        // messages across hot replacement. Reject a different receive ABI
        // before acquiring a generation lease, freezing physical ingress or
        // restoring state: old envelopes must never be interpreted under
        // the candidate's different source message contract.
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

        Route prior = null;
        CandidateRetirement candidateRetirement = null;
        boolean pauseOwned = false;
        ActorRuntime.SourceActorTransfer transfer = null;
        ActorRuntime.ActorRef<Object> replacement = null;
        boolean published = false;
        boolean candidateLeaseTransferred = false;
        Throwable primaryFailure = null;
        try {
            synchronized (routeLock) {
                // Repeat authorization with the latest route under the
                // same publication monitor, after potentially slow linking.
                requireUpgradeAuthorityLocked(
                        candidateGeneration, proxyMailbox, proxyTicket);
                reapRetiredLocked();
                prior = active;

                // Preallocate and register the candidate's retry owner before
                // any physical actor is spawned. From this point onward no
                // cleanup path needs to allocate ownership merely to keep the
                // candidate generation pinned. Failure here is pre-admission:
                // no candidate physical actor exists yet.
                candidateRetirement = new CandidateRetirement(
                        candidateGeneration,
                        candidateLease,
                        candidate,
                        prior.incarnation,
                        prior.routeEpoch,
                        candidateImage);
                retiredCandidates.add(candidateRetirement);

                upgrading = true;
                pauseOwned = true;
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
            synchronized (routeLock) {
                if (candidateRetirement == null
                        || candidateRetirement.physical != null) {
                    throw new IllegalStateException(
                            "candidate retirement ownership was not uniquely reserved");
                }
                candidateRetirement.physical = replacement;
            }
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
                // Allocate the next route BEFORE committing the proxy epoch.
                // Route lookup, physical send, and receipt retirement all take
                // routeLock. The proxy bridge serializes pollDispatch against
                // this same transaction with dispatchLock. Neither can observe
                // one epoch changed without the other.
                Route next = new Route(
                        candidateGeneration,
                        candidateLease,
                        candidate,
                        replacement,
                        prior.incarnation,
                        Math.addExact(prior.routeEpoch, 1L),
                        candidateImage);
                // Reserve the *old* generation's retirement ownership BEFORE
                // the irreversible proxy/route publication. List growth can
                // throw OOME; that must happen while the old route is still
                // authoritative, not after the point of no return. This entry
                // remains owned until the exact physical actor is finalized
                // AND generationLease.close() succeeds.
                if (retiredRoutes.contains(prior))
                    throw new IllegalStateException(
                            "active route already registered for retirement");
                retiredRoutes.add(prior);
                try {
                    if (proxyMailbox != null) {
                        proxyMailbox.requireFreezeTicketForRouteEpoch(
                                proxyTicket, prior.routeEpoch);
                        proxyTicket.commit();
                    }
                    // No throwing/allocation-bearing operation between proxy
                    // commit and the authoritative physical route pointer swap.
                    active = next;
                    candidateLeaseTransferred = true;
                    published = true;
                    if (!retiredCandidates.remove(candidateRetirement)) {
                        throw new IllegalStateException(
                                "published candidate lost its pre-reserved retirement owner");
                    }
                } finally {
                    if (!published) retiredRoutes.remove(prior);
                }
                // Keep host route admission paused until the old physical
                // incarnation is terminal and its generation lease is safe to
                // release. The new route is authoritative but not yet open.
            }

            // Route publication is the commit point. Only now make the stale
            // physical ActorRef terminal and reopen no old ingress.
            try {
                transfer.retireSourceAfterPublication(replacement);
            } catch (RuntimeException | Error retirementFailure) {
                // A failed post-publication stop/wait cannot roll back the
                // logical route. The pre-committed retirement record still
                // owns the old code pin for later physical-finalization retry.
                throw retirementFailure;
            } finally {
                transfer = null;
            }

            // The source was already physically drained. Keep its generation
            // pinned until physical finalization is actually observed.
            if (prior.physical.awaitTermination(
                    toNanosSaturated(readyTimeout),
                    TimeUnit.NANOSECONDS)) {
                synchronized (routeLock) {
                    // Never forget a retired code-generation owner until its
                    // physical finalization is verified and the pin debit has
                    // succeeded. A corrupted generation counter or injected
                    // retirement error keeps this record available for retry.
                    if (!prior.view.runtime().physicallyFinalizedForHotUpgrade(
                            prior.physical)) {
                        throw new IllegalStateException(
                                "old source actor terminated without verified "
                                        + "physical finalization");
                    }
                    prior.generationLease.close();
                    retiredRoutes.remove(prior);
                }
            } else {
                synchronized (routeLock) {
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
                            // Ownership was pre-registered before spawn. Do not
                            // allocate a Route or grow a ledger in this failure
                            // path: merely retain the existing owner and pin.
                            candidateLeaseTransferred = true;
                        } else {
                            synchronized (routeLock) {
                                if (candidateRetirement == null
                                        || !retiredCandidates.remove(
                                                candidateRetirement)) {
                                    throw new IllegalStateException(
                                            "finalized unpublished candidate lost "
                                                    + "its pre-reserved retirement owner");
                                }
                            }
                        }
                    } catch (RuntimeException | Error finalizationProbeFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = finalizationProbeFailure;
                        } else {
                            cleanupFailure.addSuppressed(finalizationProbeFailure);
                        }
                        // We cannot prove reclamation. Fail closed by retaining
                        // the already-registered candidate owner; no allocation
                        // or list growth is required after the failure.
                        candidateLeaseTransferred = true;
                    }
                } else if (candidateRetirement != null) {
                    synchronized (routeLock) {
                        if (!retiredCandidates.remove(candidateRetirement)) {
                            throw new IllegalStateException(
                                    "unspawned candidate lost its pre-reserved owner");
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
                // No executable candidate may remain owned by the quarantine
                // ledger when the originating path releases this lease.
                synchronized (routeLock) {
                    if (candidateRetirement != null
                            && retiredCandidates.contains(candidateRetirement)) {
                        throw new IllegalStateException(
                                "candidate generation lease still has a retirement owner");
                    }
                }
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
        requireBlockingHostSurface("reap retired source actor generations");
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
        for (int i = retiredCandidates.size() - 1; i >= 0; --i) {
            CandidateRetirement old = retiredCandidates.get(i);
            ActorRuntime.ActorRef<Object> physical = old.physical;
            if (physical != null
                    && old.view.runtime().physicallyFinalizedForHotUpgrade(
                            physical)) {
                old.generationLease.close();
                retiredCandidates.remove(i);
                released++;
            }
        }
        return released;
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
                if (!closed) {
                    awaitUpgradeLocked();
                    closed = true;
                    routeLock.notifyAll();
                } else if (activeRouteLeaseReleased
                        && retiredRoutes.isEmpty()
                        && retiredCandidates.isEmpty()) {
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
                    route.generationLease.close();
                    activeRouteLeaseReleased = true;
                    retiredRoutes.remove(route);
                } else if (!activeTerminated && !activeRouteLeaseReleased
                        && !retiredRoutes.contains(route)) {
                    retiredRoutes.add(route);
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
