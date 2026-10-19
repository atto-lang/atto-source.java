package dev.oreslang.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Experimental CONTROL-owned, SHARED-only actor route publication ledger.
 *
 * This is the ONE authoritative generation/physical/state/route/watermark
 * record for callers of this coordinator. No physical DATA delivery may
 * bypass pollDispatch(), and no Task may bypass the generation gate.
 *
 * This host primitive does NOT independently attest actor state decoding,
 * guest-state ownership, candidate readiness, protected worker identity,
 * output readers or crash recovery. Source actor integration and protected
 * actor replacement must remain disabled until those proofs are supplied.
 */
final class ActorUpgradeAtomicPublicationV1 implements AutoCloseable {
    private static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;

    /**
     * Opaque byte-for-byte checkpoint. The trusted state serializer must
     * validate the state schema BEFORE the coordinator receives these bytes.
     * Copy on ingress/egress prevents mutable caller buffers crossing epochs.
     */
    static final class StateImage {
        private final String schemaSha256;
        private final String payloadSha256;
        private final byte[] bytes;

        StateImage(String schemaSha256, byte[] encoded) {
            if (schemaSha256 == null || !schemaSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid state schema digest");
            Objects.requireNonNull(encoded, "encoded state");
            if (encoded.length > MAX_SNAPSHOT_BYTES)
                throw new IllegalArgumentException("state checkpoint exceeds bound");
            this.schemaSha256 = schemaSha256;
            this.bytes = encoded.clone();
            this.payloadSha256 = sha256(this.bytes);
        }

        String schemaSha256() { return schemaSha256; }
        String payloadSha256() { return payloadSha256; }
        byte[] bytes() { return bytes.clone(); }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError("required SHA-256 unavailable", impossible);
            }
        }
    }

    /**
     * A single immutable authority snapshot, never independently updated
     * generation and mailbox epochs. Physical identity is deliberately an
     * opaque host handle, not a guest-obtainable capability or worker proof.
     */
    record Route(
            long actorIdentity,
            long routeEpoch,
            ActorUpgradeEpochGate.Image image,
            StateImage state,
            Object physical,
            long dispatchWatermark) {
        Route {
            if (actorIdentity <= 0 || routeEpoch <= 0 || dispatchWatermark < 0)
                throw new IllegalArgumentException("invalid route coordinates");
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(physical, "physical handle");
            if (!image.stateSchemaSha256().equals(state.schemaSha256()))
                throw new SecurityException("state checkpoint schema differs from image");
        }
    }

    /** A token bound to the exact route and immutable message admitted. */
    static final class Delivery {
        private final ActorUpgradeAtomicPublicationV1 owner;
        private final Route route;
        private final LogicalActorProxyMailboxV1.Envelope envelope;

        private Delivery(ActorUpgradeAtomicPublicationV1 owner, Route route,
                         LogicalActorProxyMailboxV1.Envelope envelope) {
            this.owner = owner;
            this.route = route;
            this.envelope = envelope;
        }

        Route route() { return route; }
        LogicalActorProxyMailboxV1.Envelope envelope() { return envelope; }
    }

    static final class Staged implements AutoCloseable {
        private final ActorUpgradeAtomicPublicationV1 owner;
        private final Route prior;
        private final Route proposed;
        private final ActorUpgradePreflightV1 preflight;
        private boolean consumed;

        private Staged(ActorUpgradeAtomicPublicationV1 owner, Route prior,
                       Route proposed, ActorUpgradePreflightV1 preflight) {
            this.owner = owner;
            this.prior = prior;
            this.proposed = proposed;
            this.preflight = preflight;
        }

        Route proposed() { return proposed; }

        @Override public void close() { owner.abort(this); }
    }

    private final Object lock = new Object();
    private final ActorUpgradeEpochGate generation;
    private final LogicalActorProxyMailboxV1 mailbox;
    private Route active;
    private Staged staged;
    private boolean closed;
    private boolean poisoned;

    ActorUpgradeAtomicPublicationV1(
            ActorUpgradeEpochGate generation,
            LogicalActorProxyMailboxV1 mailbox,
            StateImage initialState,
            Object initialPhysical) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        Objects.requireNonNull(initialState, "initial state");
        // V1 must not pretend a Java heap/Truffle context is a protected
        // PRIVATE/UNTRUSTED process or independently authenticated sandbox.
        if (generation.placement().actorKind()
                != ActorUpgradeEpochGate.ActorKind.SHARED
                || generation.placement().protection()
                != ActorUpgradeEpochGate.ProtectionDomain.TRUSTED_HOST)
            throw new SecurityException(
                    "atomic host route V1 requires trusted SHARED placement");
        var snapshot = mailbox.snapshot();
        if (generation.isClosed() || snapshot.closed() || snapshot.dispatchFrozen()
                || snapshot.routeEpoch() != generation.epoch()
                || generation.frozen() || generation.livePins() != 0
                || snapshot.outstandingMessages() != 0
                || snapshot.queuedMessages() != 0)
            throw new IllegalStateException(
                    "initial publication requires aligned empty unfrozen authorities");
        active = new Route(generation.actorIdentity(), generation.epoch(),
                generation.image(), initialState, initialPhysical,
                snapshot.highestDispatchedSequence());
    }

    Route snapshot() {
        synchronized (lock) {
            requireOpen();
            return active;
        }
    }

    LogicalActorProxyMailboxV1.Envelope admit(byte[] immutablePayload) {
        synchronized (lock) {
            requireOpen();
            return mailbox.admit(immutablePayload);
        }
    }

    /**
     * Route lookup and logical dispatch reservation linearize under the
     * SAME coordinator monitor as publication. The caller must hold the
     * returned old physical route until it reports an authenticated receipt.
     * Ambiguous transport sends must NOT be replayed as unsent messages.
     */
    Delivery pollDispatch() {
        synchronized (lock) {
            requireOpen();
            var envelope = mailbox.pollDispatch();
            return envelope == null ? null : new Delivery(this, active, envelope);
        }
    }

    /** Trusted completion only. Production transport must authenticate receipts. */
    void complete(Delivery delivery) {
        synchronized (lock) {
            requireOpen();
            if (delivery == null || delivery.owner != this
                    || delivery.route != active)
                throw new SecurityException("foreign or stale physical completion");
            mailbox.complete(delivery.envelope.messageSequence());
        }
    }

    Staged prepare(ActorUpgradeEpochGate.Image proposedImage,
                   StateImage proposedState,
                   Object proposedPhysical) {
        Objects.requireNonNull(proposedImage, "candidate image");
        Objects.requireNonNull(proposedState, "candidate state");
        Objects.requireNonNull(proposedPhysical, "candidate physical handle");
        synchronized (lock) {
            requireOpen();
            if (staged != null)
                throw new IllegalStateException("route upgrade already staged");
            if (proposedPhysical == active.physical())
                throw new IllegalArgumentException(
                        "replacement must have distinct physical identity");
            if (!proposedImage.stateSchemaSha256()
                    .equals(proposedState.schemaSha256()))
                throw new SecurityException("candidate state schema mismatch");
            if (active.routeEpoch() == Long.MAX_VALUE)
                throw new IllegalStateException("route epoch exhausted");

            // Verify both underlying authorities agree with the sole route.
            // Nothing is staged when an unrelated actor/epoch mutated one.
            var mailboxState = mailbox.snapshot();
            if (generation.actorIdentity() != active.actorIdentity()
                    || generation.epoch() != active.routeEpoch()
                    || mailboxState.routeEpoch() != active.routeEpoch()
                    || !generation.image().equals(active.image())
                    || mailboxState.dispatchFrozen() || mailboxState.closed())
                throw new IllegalStateException("route authority divergence");

            // Pre-allocate the entire next record before freezing admission.
            // The authoritative commit callback will be only one field swap.
            var next = new Route(active.actorIdentity(), active.routeEpoch() + 1L,
                    proposedImage, proposedState, proposedPhysical,
                    mailboxState.highestDispatchedSequence());
            var freeze = ActorUpgradePreflightV1.prepare(
                    generation, mailbox, proposedImage);
            try {
                staged = new Staged(this, active, next, freeze);
                return staged;
            } catch (RuntimeException | Error allocationFailure) {
                // Even allocation failure between freeze and ticket ownership
                // must not leave an unowned, permanently frozen actor.
                try {
                    freeze.close();
                } catch (RuntimeException | Error rollbackFailure) {
                    poisoned = true;
                    allocationFailure.addSuppressed(rollbackFailure);
                }
                throw allocationFailure;
            }
        }
    }

    /** Return false for outstanding old work, never claim a partial commit. */
    boolean publish(Staged candidate) {
        synchronized (lock) {
            requireOpen();
            requireStaged(candidate);
            if (!candidate.preflight.quiescentSnapshot()) return false;
            // These checks are observational diagnostics; the gate and
            // mailbox recheck eligibility under BOTH locks during commit.
            if (generation.epoch() != active.routeEpoch()
                    || mailbox.snapshot().routeEpoch() != active.routeEpoch()
                    || active != candidate.prior)
                throw new IllegalStateException("staged origin no longer authoritative");
            try {
                candidate.preflight.commitWithAuthoritativeRoute(
                        () -> active = candidate.proposed);
            } catch (RuntimeException | Error uncertainCommit) {
                // The callback or independent shutdown may have invalidated
                // rollback authority. NEVER dispatch to either route again.
                poisoned = true;
                throw uncertainCommit;
            }
            candidate.consumed = true;
            staged = null;
            return true;
        }
    }

    void abort(Staged candidate) {
        synchronized (lock) {
            if (candidate == null || candidate.owner != this)
                throw new IllegalStateException("foreign route candidate");
            if (candidate.consumed) return;
            requireOpen();
            requireStaged(candidate);
            try {
                candidate.preflight.close();
            } catch (RuntimeException | Error rollbackFailure) {
                poisoned = true;
                throw rollbackFailure;
            }
            candidate.consumed = true;
            staged = null;
        }
    }

    private void requireStaged(Staged candidate) {
        if (candidate == null || candidate.owner != this
                || candidate.consumed || staged != candidate)
            throw new IllegalStateException("stale or foreign route publication");
    }

    private void requireOpen() {
        if (closed || poisoned)
            throw new IllegalStateException("logical actor authority closed/quarantined");
        // Fail closed if any legacy host caller independently publishes
        // either epoch, or terminally tears down one authority. Single-route
        // dispatch MUST never trust a divergent image/physical pairing.
        var mailboxState = mailbox.snapshot();
        boolean expectedFreeze = staged != null;
        if (generation.isClosed() || mailboxState.closed()
                || generation.actorIdentity() != active.actorIdentity()
                || generation.epoch() != active.routeEpoch()
                || mailboxState.routeEpoch() != active.routeEpoch()
                || !generation.image().equals(active.image())
                || generation.frozen() != expectedFreeze
                || mailboxState.dispatchFrozen() != expectedFreeze) {
            poisoned = true;
            throw new IllegalStateException(
                    "actor route, image and mailbox authority diverged; dispatch quarantined");
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            if (staged != null && !poisoned) {
                try {
                    staged.preflight.close();
                    staged.consumed = true;
                    staged = null;
                } catch (RuntimeException | Error rollbackFailure) {
                    poisoned = true;
                    closed = true;
                    throw rollbackFailure;
                }
            }
            // The CONTROL ledger owns terminal logical admission. Do not
            // leave separately accessible legacy primitives dispatchable
            // after its sole authoritative route is closed.
            synchronized (generation) {
                generation.close();
                mailbox.close();
            }
            closed = true;
        }
    }
}
