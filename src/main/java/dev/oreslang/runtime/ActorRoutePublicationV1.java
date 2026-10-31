package dev.oreslang.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Trusted CONTROL-only, SHARED-only logical ActorRef publication authority.
 *
 * <p>Exactly one immutable Published tuple is authoritative: actor identity,
 * incarnation, route epoch, code image, validated checkpoint bytes, physical
 * route, Task admission gate and old-mailbox dispatch watermark. The prior
 * generation gate and proxy freeze are subordinate barriers, never alternate
 * sources of routing authority. All DATA dispatch MUST pass through this owner,
 * not call mailbox.pollDispatch() directly.</p>
 *
 * <p>This is a host-side integration primitive. The caller still MUST attest
 * that the checkpoint was decoded/validated with the actor schema, candidate
 * physical actor was restored and became ready, and the old actor is quiescent.
 * This class alone does not implement protected PRIVATE/UNTRUSTED migration or
 * guest Task lowering. Do not expose it or its transport to guest code.</p>
 */
final class ActorRoutePublicationV1<R> implements AutoCloseable {
    private static final int MAX_CHECKPOINT_BYTES = 1024 * 1024;

    static final class StateSnapshot {
        private final String schemaSha256;
        private final byte[] bytes;
        private final String contentSha256;

        StateSnapshot(String schemaSha256, byte[] bytes) {
            if (schemaSha256 == null || !schemaSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("canonical state schema SHA-256 required");
            Objects.requireNonNull(bytes, "checkpoint bytes");
            if (bytes.length > MAX_CHECKPOINT_BYTES)
                throw new IllegalArgumentException("state checkpoint exceeds bounded transport");
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

    /** An indivisible route/code/state/mailbox cutover observation. */
    record Published<R>(
            long actorIdentity,
            long incarnation,
            long routeEpoch,
            ActorUpgradeEpochGate.Image image,
            StateSnapshot state,
            R physicalRoute,
            ActorUpgradeEpochGate taskAdmission,
            long oldDispatchWatermark) {
        Published {
            if (actorIdentity <= 0 || incarnation <= 0 || routeEpoch <= 0
                    || oldDispatchWatermark < 0)
                throw new IllegalArgumentException("invalid logical publication coordinates");
            Objects.requireNonNull(image);
            Objects.requireNonNull(state);
            Objects.requireNonNull(physicalRoute);
            Objects.requireNonNull(taskAdmission);
            if (!image.stateSchemaSha256().equals(state.schemaSha256()))
                throw new SecurityException("checkpoint schema does not match route image");
            if (taskAdmission.actorIdentity() != actorIdentity
                    || !taskAdmission.image().equals(image))
                throw new SecurityException("Task generation gate does not match route image");
        }
    }

    final class Stage implements AutoCloseable {
        private final Published<R> prior;
        private final ActorUpgradeEpochGate.Image image;
        private final ActorUpgradePreflightV1 preflight;
        private final long dispatchWatermark;
        private boolean finished;

        private Stage(Published<R> prior, ActorUpgradeEpochGate.Image image,
                      ActorUpgradePreflightV1 preflight, long dispatchWatermark) {
            this.prior = prior;
            this.image = image;
            this.preflight = preflight;
            this.dispatchWatermark = dispatchWatermark;
        }

        boolean quiescent() {
            synchronized (lock) {
                requireStage(this);
                return preflight.quiescentSnapshot();
            }
        }

        /**
         * Commit exactly one immutable logical route. A false return means
         * outstanding old Task/message pins; the stage remains active.
         */
        boolean publish(StateSnapshot validatedState, R readyPhysicalRoute) {
            synchronized (lock) {
                requireStage(this);
                Objects.requireNonNull(validatedState, "validated checkpoint");
                Objects.requireNonNull(readyPhysicalRoute, "ready physical route");
                if (Objects.equals(readyPhysicalRoute, prior.physicalRoute()))
                    throw new IllegalArgumentException("replacement must be a new physical route");
                if (!validatedState.schemaSha256().equals(image.stateSchemaSha256()))
                    throw new SecurityException("checkpoint schema mismatch");
                return preflight.withPreparedAuthority(() -> {
                if (!preflight.quiescentSnapshot()) return false;
                var snapshot = mailbox.snapshot();
                if (snapshot.closed() || !snapshot.dispatchFrozen()
                        || snapshot.routeEpoch() != prior.routeEpoch()
                        || snapshot.highestDispatchedSequence() != dispatchWatermark)
                    throw new IllegalStateException("mailbox lost frozen route authority");
                if (prior.routeEpoch() == Long.MAX_VALUE)
                    throw new IllegalStateException("logical route epoch exhausted");

                // All allocation and validation occur before the commit point.
                var nextGate = new ActorUpgradeEpochGate(
                        prior.actorIdentity(), prior.taskAdmission().placement(), image);
                final Published<R> next;
                try {
                    next = new Published<>(
                            prior.actorIdentity(), prior.incarnation(),
                            Math.addExact(prior.routeEpoch(), 1L),
                            image, validatedState, readyPhysicalRoute, nextGate,
                            dispatchWatermark);
                } catch (RuntimeException | Error failure) {
                    nextGate.close();
                    throw failure;
                }

                // ONE volatile publication, while DATA dispatch is blocked by
                // this monitor AND the physical mailbox freeze.
                active = next;
                pending = null;
                finished = true;
                try {
                    // Retire the old Task authority before unfreezing DATA.
                    // If post-commit cleanup fails, NEVER roll back to old.
                    preflight.finishPublished();
                } catch (RuntimeException | Error failure) {
                    poisoned = true;
                    nextGate.close();
                    throw new IllegalStateException(
                            "route published but finalization failed; DATA and Task admission fenced",
                            failure);
                }
                return true;
                });
            }
        }

        @Override public void close() {
            synchronized (lock) {
                if (finished) return;
                requireStage(this);
                try {
                    preflight.close(); // old image abort precedes old DATA reopen
                    finished = true;
                    pending = null;
                } catch (RuntimeException | Error failure) {
                    poisoned = true; // ambiguous barrier ownership: stop dispatch
                    throw failure;
                }
            }
        }
    }

    private final Object lock = new Object();
    private final LogicalActorProxyMailboxV1 mailbox;
    private volatile Published<R> active;
    private Stage pending;
    // Reservation is acquired under the coordinator lock, but the external
    // transport callback executes OUTSIDE it. Stage/teardown is forbidden
    // until every in-flight physical submit returns or throws.
    private int physicalSubmissions;
    private boolean poisoned;
    private boolean closed;

    ActorRoutePublicationV1(ActorUpgradeEpochGate initial,
                            LogicalActorProxyMailboxV1 mailbox,
                            StateSnapshot initialState, R initialPhysicalRoute) {
        Objects.requireNonNull(initial, "initial generation authority");
        this.mailbox = Objects.requireNonNull(mailbox, "logical mailbox");
        if (!initial.admissionOpen() || initial.livePins() != 0
                || initial.epoch() != 1L)
            throw new IllegalStateException("initial Task generation must be unpinned and open");
        if (initial.placement().actorKind() != ActorUpgradeEpochGate.ActorKind.SHARED
                || initial.placement().protection()
                        != ActorUpgradeEpochGate.ProtectionDomain.TRUSTED_HOST)
            throw new SecurityException(
                    "host coordinator requires trusted SHARED placement; protected workers need attestations");
        var snapshot = mailbox.snapshot();
        if (snapshot.closed() || snapshot.dispatchFrozen()
                || snapshot.routeEpoch() != 1L || snapshot.highestDispatchedSequence() != 0L
                || snapshot.nextMessageSequence() != 1L
                || snapshot.retainedMessages() != 0 || snapshot.completionWatermark() != 0L)
            throw new IllegalStateException("new logical authority requires unused mailbox");
        this.active = new Published<>(
                initial.actorIdentity(), 1L, 1L, initial.image(),
                initialState, initialPhysicalRoute, initial, 0L);
    }

    Published<R> current() {
        synchronized (lock) {
            requireOperational();
            return active;
        }
    }

    Stage stage(ActorUpgradeEpochGate.Image candidateImage) {
        synchronized (lock) {
            requireOperational();
            if (pending != null)
                throw new IllegalStateException("logical route upgrade already staged");
            if (physicalSubmissions != 0)
                throw new IllegalStateException(
                        "cannot freeze route while physical submit is in flight");
            // preflight takes the only old-generation and mailbox DATA freezes.
            var preflight = ActorUpgradePreflightV1.prepare(
                    active.taskAdmission(), mailbox, candidateImage);
            try {
                // Stage/ticket allocation can fail AFTER the independent
                // generation and mailbox freezes have succeeded. Never
                // strand a frozen actor without an owned rollback handle.
                Stage ready = new Stage(active, candidateImage, preflight,
                        preflight.dispatchWatermark());
                pending = ready;
                return ready;
            } catch (RuntimeException | Error admissionFailure) {
                try {
                    preflight.close();
                } catch (RuntimeException | Error rollbackFailure) {
                    // An ambiguous rollback is terminal for DATA. Do not
                    // reopen an unknown old image or hide the primary fault.
                    poisoned = true;
                    if (admissionFailure != rollbackFailure)
                        admissionFailure.addSuppressed(rollbackFailure);
                }
                throw admissionFailure;
            }
        }
    }

    /** Host-only bounded admission; allowed even while DATA is frozen. */
    LogicalActorProxyMailboxV1.Envelope admit(byte[] payload) {
        synchronized (lock) {
            requireOperational();
            return mailbox.admit(payload);
        }
    }

    /**
     * Reserve a pinned old-generation dispatch under CONTROL, then invoke the
     * trusted transport submitter with NO coordinator/epoch/mailbox lock held.
     * The in-flight reservation forbids stage and close until the submitter
     * returns. An ambiguous failure remains outstanding and quarantines DATA;
     * it must never be implicitly requeued or credited.
     */
    boolean dispatchNext(BiConsumer<R, LogicalActorProxyMailboxV1.Envelope> submit) {
        Objects.requireNonNull(submit, "trusted transport submit");
        final LogicalActorProxyMailboxV1.Envelope envelope;
        final R physicalRoute;
        synchronized (lock) {
            requireOperational();
            if (pending != null) return false;
            if (physicalSubmissions == Integer.MAX_VALUE)
                throw new IllegalStateException("physical submit reservation budget exhausted");
            envelope = mailbox.pollDispatch();
            if (envelope == null) return false;
            // Route and reservation are captured atomically. A concurrent
            // upgrade cannot freeze or retire this route until finally runs.
            ++physicalSubmissions;
            physicalRoute = active.physicalRoute();
        }
        try {
            submit.accept(physicalRoute, envelope);
            return true;
        } catch (RuntimeException | Error unknownDelivery) {
            synchronized (lock) {
                poisoned = true;
            }
            throw new IllegalStateException(
                    "ambiguous physical submit; logical DATA quarantined",
                    unknownDelivery);
        } finally {
            synchronized (lock) {
                --physicalSubmissions;
            }
        }
    }

    /** Only authenticated old-route completion receipts may invoke this. */
    void complete(long messageSequence) {
        synchronized (lock) {
            requireOperational();
            mailbox.complete(messageSequence);
        }
    }

    private void requireStage(Stage stage) {
        requireOperational();
        if (stage.finished || pending != stage || active != stage.prior)
            throw new IllegalStateException("stale/foreign actor route publication stage");
    }

    private void requireOperational() {
        if (closed || poisoned)
            throw new IllegalStateException("logical actor authority closed or quarantined");
        // Legacy host helpers can still mutate the generation gate and mailbox
        // independently. Detect that divergence before admitting or dispatching
        // any DATA: a route snapshot alone cannot authorize mismatched epochs.
        var mailboxState = mailbox.snapshot();
        var gate = active.taskAdmission();
        boolean expectedFreeze = pending != null;
        if (mailboxState.closed()
                || mailboxState.routeEpoch() != active.routeEpoch()
                || mailboxState.dispatchFrozen() != expectedFreeze
                || gate.isClosed() || gate.actorIdentity() != active.actorIdentity()
                || gate.epoch() != 1L || !gate.image().equals(active.image())
                || gate.frozen() != expectedFreeze) {
            poisoned = true;
            throw new IllegalStateException(
                    "logical route/generation/mailbox authority diverged; DATA quarantined");
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            if (physicalSubmissions != 0)
                throw new IllegalStateException(
                        "cannot retire logical route while physical submit is in flight");
            closed = true;
            active.taskAdmission().close();
            mailbox.close();
            // Do not attempt rollback or reopen a mailbox after terminal close.
            pending = null;
        }
    }
}
