package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Trusted-host, single-authority route coordinator for an actor's logical mailbox.
 *
 * <p>Mail is admitted to one stable LogicalRef. The coordinator is the ONLY
 * supported dispatch path: it freezes dispatch, waits for authenticated physical
 * completion receipts and separately held execution pins, then publishes one
 * immutable (route, code, state, epoch, watermark) tuple.</p>
 *
 * <p>This is a phase-2 HOST primitive, not a guest-visible ActorRef or a
 * certificate of physical Task/FFI/borrow quiescence. A caller must keep raw
 * physical ActorRefs private, use an actually tracked completion callback, and
 * validate/checkpoint/restore actor state before passing the candidate route.
 * Acknowledging enqueue rather than execution is unsafe. This component never
 * closes a physical actor or frees an old code image.</p>
 */
final class ActorRouteCoordinatorV1<R> implements AutoCloseable {
    static final class StateSnapshot {
        private static final int MAX_BYTES = 1024 * 1024;
        private final String schemaSha256;
        private final byte[] bytes;
        private final String contentSha256;

        StateSnapshot(String schemaSha256, byte[] bytes) {
            if (schemaSha256 == null || !schemaSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("canonical state-schema SHA-256 required");
            }
            Objects.requireNonNull(bytes, "checkpoint bytes");
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("state checkpoint exceeds 1 MiB");
            }
            this.schemaSha256 = schemaSha256;
            this.bytes = bytes.clone();
            try {
                this.contentSha256 = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(this.bytes));
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        String schemaSha256() { return schemaSha256; }
        String contentSha256() { return contentSha256; }
        byte[] bytes() { return bytes.clone(); }
    }

    record Published<R>(long actorIdentity, long routeEpoch,
                        ActorUpgradeEpochGate.Image image, StateSnapshot state,
                        R physicalRoute, long oldDispatchWatermark) {
        Published {
            if (actorIdentity <= 0 || routeEpoch <= 0 || oldDispatchWatermark < 0) {
                throw new IllegalArgumentException("invalid actor or route epoch");
            }
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(state, "checkpoint");
            Objects.requireNonNull(physicalRoute, "physical route");
            if (!image.stateSchemaSha256().equals(state.schemaSha256())) {
                throw new SecurityException("state schema does not match code image");
            }
        }
    }

    record Snapshot<R>(Published<R> published,
                       LogicalActorProxyMailboxV1.Snapshot mailbox,
                       int physicalSubmissions, int oldTaskPins,
                       boolean upgrading, boolean poisoned, boolean closed) { }

    /**
     * A physical transport must call completion.acknowledge() ONLY after the
     * admitted actor turn has physically completed. Asynchronous send returns
     * before this point and does not release the route pin.
     */
    @FunctionalInterface
    interface TrackedTransport<R> {
        void submit(R route, LogicalActorProxyMailboxV1.Envelope message,
                    ActorRouteCoordinatorV1<R>.PhysicalCompletion completion);
    }

    /**
     * Host-only physical cutover witness. Implementations must be issued by
     * the owning runtime, not guest code. A plain HOST simulation need not
     * implement this, but a protected runtime-managed route MUST implement it.
     */
    interface PhysicalRouteWitness {
        boolean readyForPublication();
        boolean sameAuthorityDomain(PhysicalRouteWitness other);
        /** Pre-claim direct ActorRef messages must be processed before V1 retirement. */
        boolean preclaimDirectMailDrained();
        /** Consumed once; no two logical actor identities may own one physical route. */
        void bindToCoordinator(Object coordinator);
        void retireFromLogicalRoute();
    }

    final class PhysicalCompletion {
        private final Published<R> origin;
        private long sequence;
        private boolean submitted;
        private boolean acknowledged;
        private boolean released;
        private boolean physicalAdmissionClaimed;

        private PhysicalCompletion(Published<R> origin) {
            this.origin = origin;
        }

        long originEpoch() { return origin.routeEpoch(); }
        long messageSequence() { return sequence; }

        /**
         * Consumed exactly once by the physical ActorRuntime bridge before
         * actual mailbox enqueue. Host code cannot substitute a sequence or
         * replay a receipt to another actor, route, or published epoch.
         */
        void claimPhysicalAdmission(Object physicalRoute) {
            synchronized (lock) {
                requireOperational();
                if (physicalAdmissionClaimed || released || acknowledged
                        || sequence <= 0 || physicalSubmissions <= 0
                        || active != origin || origin.physicalRoute() != physicalRoute) {
                    throw new SecurityException(
                            "stale, duplicate or foreign physical route send receipt");
                }
                physicalAdmissionClaimed = true;
            }
        }

        void acknowledge() {
            synchronized (lock) {
                if (closed || sequence <= 0 || released || acknowledged) {
                    throw new IllegalStateException("foreign, stale or duplicate physical completion");
                }
                if (active != origin) {
                    poisoned = true;
                    throw new IllegalStateException("physical completion after route change");
                }
                // Account for logical completion BEFORE freeing this physical
                // submission. Failed accounting retains the physical pin.
                mailbox.complete(sequence);
                acknowledged = true;
                releaseIfFinished();
            }
        }

        void quarantine(Throwable reason) {
            Objects.requireNonNull(reason, "physical failure");
            synchronized (lock) {
                poisoned = true; // Never replay ambiguous transport acceptance.
            }
        }

        private void submitReturned() {
            synchronized (lock) {
                submitted = true;
                releaseIfFinished();
            }
        }

        private void releaseIfFinished() {
            if (!submitted || !acknowledged || released) return;
            if (physicalSubmissions <= 0) {
                poisoned = true;
                throw new IllegalStateException("physical submission counter underflow");
            }
            --physicalSubmissions;
            released = true;
        }
    }

    /** Stable process-local logical send handle, independent of the physical route. */
    final class LogicalRef {
        private LogicalRef() { }
        long id() { return generation.actorIdentity(); }
        LogicalActorProxyMailboxV1.Envelope send(byte[] immutablePayload) {
            return ActorRouteCoordinatorV1.this.admit(immutablePayload);
        }
    }

    final class Stage implements AutoCloseable {
        private final Published<R> prior;
        private final ActorUpgradeEpochGate.Image proposed;
        private final ActorUpgradeEpochGate.Candidate gateCandidate;
        private final LogicalActorProxyMailboxV1.FreezeTicket mailboxFreeze;
        private boolean finished;

        private Stage(Published<R> prior, ActorUpgradeEpochGate.Image proposed,
                      ActorUpgradeEpochGate.Candidate gateCandidate,
                      LogicalActorProxyMailboxV1.FreezeTicket mailboxFreeze) {
            this.prior = prior;
            this.proposed = proposed;
            this.gateCandidate = gateCandidate;
            this.mailboxFreeze = mailboxFreeze;
        }

        boolean quiescent(Object seal) {
            synchronized (lock) {
                verifyStage(seal, this);
                return physicalSubmissions == 0 && generation.livePins() == 0
                        && mailboxFreeze.drained()
                        && (!(prior.physicalRoute() instanceof PhysicalRouteWitness physical)
                                || physical.preclaimDirectMailDrained());
            }
        }

        /**
         * The caller MUST have authenticated this fresh physical route, restored
         * the validated state in its own arena and obtained a real readiness
         * barrier. This host-only primitive cannot manufacture that evidence.
         *
         * False means outstanding old execution pins; stage remains frozen.
         * A post-commit failure poisons DATA; there is no silent rollback.
         */
        boolean publish(Object seal, StateSnapshot checkedState, R readyReplacement) {
            synchronized (lock) {
                verifyStage(seal, this);
                Objects.requireNonNull(checkedState, "validated state");
                Objects.requireNonNull(readyReplacement, "ready physical successor");
                if (readyReplacement == prior.physicalRoute()) {
                    throw new IllegalArgumentException("replacement reuses old physical route");
                }
                if (prior.physicalRoute() instanceof PhysicalRouteWitness old) {
                    if (!(readyReplacement instanceof PhysicalRouteWitness next)
                            || !old.sameAuthorityDomain(next)) {
                        throw new SecurityException(
                                "physical actor replacement must be a runtime-minted route from the same owner");
                    }
                    if (!next.readyForPublication()) {
                        throw new IllegalStateException(
                                "physical replacement has not completed its actor initialization barrier");
                    }
                } else if (readyReplacement instanceof PhysicalRouteWitness) {
                    throw new SecurityException(
                            "cannot publish a runtime-minted physical actor over an untracked host route");
                }
                if (!proposed.stateSchemaSha256().equals(checkedState.schemaSha256())) {
                    throw new SecurityException("candidate checkpoint schema mismatch");
                }
                if (!quiescent(seal)) return false;
                LogicalActorProxyMailboxV1.Snapshot snapshot = mailbox.snapshot();
                if (!snapshot.dispatchFrozen()
                        || snapshot.routeEpoch() != prior.routeEpoch()
                        || snapshot.highestDispatchedSequence()
                                != mailboxFreeze.dispatchWatermark()
                        || active != prior
                        || generation.epoch() != prior.routeEpoch()) {
                    poisoned = true;
                    throw new IllegalStateException("old route/freeze authority diverged");
                }
                if (prior.routeEpoch() == Long.MAX_VALUE) {
                    throw new IllegalStateException("actor route epoch exhausted");
                }
                // Materialize all potentially allocating state BEFORE the
                // non-throwing normal commit path. Never call guest code here.
                Published<R> next = new Published<>(
                        prior.actorIdentity(), Math.addExact(prior.routeEpoch(), 1L),
                        proposed, checkedState, readyReplacement,
                        mailboxFreeze.dispatchWatermark());
                // Bind the candidate exactly once before any irreversible
                // mailbox/epoch publication. A separately managed physical
                // actor must not become a route owned by two ActorRefs.
                if (readyReplacement instanceof PhysicalRouteWitness witnessed) {
                    witnessed.bindToCoordinator(ActorRouteCoordinatorV1.this);
                }
                try {
                    // Under this coordinator lock, no dispatch can use the newly
                    // unfrozen mailbox until the single volatile tuple is set.
                    mailboxFreeze.commit();
                    if (!generation.publish(gateCandidate)) {
                        throw new IllegalStateException("Task pins appeared during cutover");
                    }
                    active = next; // THE one route/code/state publication point.
                    // Route retirement is monotonic/non-blocking; this does
                    // NOT free physical actor heap/code/FFI resources.
                    if (prior.physicalRoute() instanceof PhysicalRouteWitness old) {
                        old.retireFromLogicalRoute();
                    }
                    pending = null;
                    finished = true;
                    return true;
                } catch (RuntimeException | Error ambiguousCommit) {
                    poisoned = true; // Never reopen the old route on uncertainty.
                    throw ambiguousCommit;
                }
            }
        }

        void abort(Object seal) {
            synchronized (lock) {
                verifyStage(seal, this);
                try {
                    // Releasing gate authority first is safe: logical dispatch
                    // stays frozen until the explicit old-route rollback.
                    generation.abort(gateCandidate);
                    mailboxFreeze.rollback();
                    pending = null;
                    finished = true;
                } catch (RuntimeException | Error failure) {
                    poisoned = true;
                    throw failure;
                }
            }
        }

        @Override public void close() {
            synchronized (lock) {
                if (finished || closed) return;
                // A close() call is NOT proof that prepared publication did not
                // cross the commit point. Require an explicit trusted abort().
                throw new IllegalStateException("active route stage requires explicit abort or publish");
            }
        }
    }

    private final Object lock = new Object();
    private final Object coordinatorSeal;
    private final ActorUpgradeEpochGate generation;
    private final LogicalActorProxyMailboxV1 mailbox;
    private final LogicalRef logicalRef = new LogicalRef();
    private volatile Published<R> active;
    private Stage pending;
    private int physicalSubmissions;
    private boolean poisoned;
    private boolean closed;

    ActorRouteCoordinatorV1(long actorIdentity, Object coordinatorSeal,
                            ActorUpgradeEpochGate.Image initialImage,
                            StateSnapshot initialState, R initialPhysicalRoute,
                            int maxMessages, int maxMessageBytes, long maxRetainedBytes) {
        this.coordinatorSeal = Objects.requireNonNull(coordinatorSeal, "trusted supervisor seal");
        Objects.requireNonNull(initialImage, "initial code image");
        Objects.requireNonNull(initialState, "initial state");
        Objects.requireNonNull(initialPhysicalRoute, "initial physical route");
        if (!initialImage.stateSchemaSha256().equals(initialState.schemaSha256())) {
            throw new SecurityException("initial checkpoint schema mismatch");
        }
        this.generation = new ActorUpgradeEpochGate(actorIdentity, initialImage);
        this.mailbox = new LogicalActorProxyMailboxV1(
                maxMessages, maxMessageBytes, maxRetainedBytes);
        Published<R> first = new Published<>(actorIdentity, 1, initialImage,
                initialState, initialPhysicalRoute, 0);
        if (initialPhysicalRoute instanceof PhysicalRouteWitness witnessed) {
            witnessed.bindToCoordinator(this);
        }
        this.active = first;
    }

    LogicalRef logicalRef() { return logicalRef; }

    Published<R> current() {
        synchronized (lock) {
            requireOperational();
            return active;
        }
    }

    Snapshot<R> snapshot() {
        synchronized (lock) {
            return new Snapshot<>(active, mailbox.snapshot(), physicalSubmissions,
                    generation.livePins(), pending != null, poisoned, closed);
        }
    }

    ActorUpgradeEpochGate.Lease pinOldTask(Object seal) {
        synchronized (lock) {
            verifySeal(seal);
            requireOperational();
            return generation.pin();
        }
    }

    private LogicalActorProxyMailboxV1.Envelope admit(byte[] payload) {
        synchronized (lock) {
            requireOperational();
            return mailbox.admit(payload);
        }
    }

    /**
     * Dispatches at most one FIFO message. The transport runs OUTSIDE the
     * coordinator lock and must separately report actual physical completion.
     * A throwing submit has ambiguous acceptance and poisons the route.
     */
    boolean dispatchNext(Object seal, TrackedTransport<R> transport) {
        Objects.requireNonNull(transport, "tracked transport");
        final LogicalActorProxyMailboxV1.Envelope message;
        final Published<R> route;
        final PhysicalCompletion completion;
        synchronized (lock) {
            verifySeal(seal);
            requireOperational();
            if (pending != null) return false;
            // One unacknowledged physical submission at a time. Reserving FIFO
            // sequences under a monitor is NOT sufficient: after we release
            // that monitor, concurrent submit callbacks could run in reverse
            // order. Serial physical submission also fences async callback
            // returns until actual completion and transport exit.
            if (physicalSubmissions != 0) return false;
            route = active;
            // Allocate before dequeuing. OOME must not strand a message.
            completion = new PhysicalCompletion(route);
            message = mailbox.pollDispatch();
            if (message == null) return false;
            if (physicalSubmissions == Integer.MAX_VALUE) {
                poisoned = true;
                throw new IllegalStateException("physical submission bound exhausted");
            }
            completion.sequence = message.messageSequence();
            ++physicalSubmissions;
        }

        try {
            transport.submit(route.physicalRoute(), message, completion);
            return true;
        } catch (RuntimeException | Error unconfirmed) {
            completion.quarantine(unconfirmed);
            throw unconfirmed;
        } finally {
            completion.submitReturned();
        }
    }

    Stage stage(Object seal, ActorUpgradeEpochGate.Image proposed) {
        synchronized (lock) {
            verifySeal(seal);
            requireOperational();
            if (pending != null) {
                throw new IllegalStateException("another actor upgrade is active");
            }
            ActorUpgradeEpochGate.Candidate candidate = generation.prepare(proposed);
            try {
                LogicalActorProxyMailboxV1.FreezeTicket ticket =
                        mailbox.freezeDispatchForUpgrade();
                Stage stage = new Stage(active, proposed, candidate, ticket);
                pending = stage;
                return stage;
            } catch (RuntimeException | Error failure) {
                try {
                    generation.abort(candidate);
                } catch (RuntimeException | Error cleanup) {
                    poisoned = true;
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    private void verifyStage(Object seal, Stage stage) {
        verifySeal(seal);
        requireOperational();
        if (stage == null || stage != pending || stage.finished
                || active != stage.prior) {
            throw new IllegalStateException("foreign, stale or finished route stage");
        }
    }

    private void verifySeal(Object seal) {
        if (seal != coordinatorSeal) {
            throw new SecurityException("untrusted actor-route coordinator");
        }
    }

    private void requireOperational() {
        if (closed || poisoned) {
            throw new IllegalStateException("route coordinator is closed or quarantined");
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            LogicalActorProxyMailboxV1.Snapshot before = mailbox.snapshot();
            if (pending != null || physicalSubmissions != 0
                    || before.retainedMessages() != 0
                    || generation.livePins() != 0) {
                throw new IllegalStateException(
                        "live actor mail, stage, or physical/Task pins prevent retirement");
            }
            // This releases only logical accounting. Physical ActorRuntime
            // actor/FFI/image teardown is separately owned and must be proven.
            mailbox.close();
            generation.close();
            closed = true;
        }
    }
}
