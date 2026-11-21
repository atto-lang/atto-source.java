package dev.oreslang.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

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

    /**
     * A trusted asynchronous physical dispatcher. Returning from submit()
     * means handoff finished, NOT that the actor turn or callback finished.
     * The receipt MUST be acknowledged only after actual physical completion.
     */
    @FunctionalInterface
    interface TrackedSubmit<R> {
        void submit(R physicalRoute, LogicalActorProxyMailboxV1.Envelope envelope,
                    ActorRoutePublicationV1<R>.PhysicalCompletion completion);
    }

    /**
     * Opaque CONTROL-owned completion receipt for one physical message. The
     * route reservation is released only when BOTH transport submission has
     * returned and this receipt has been acknowledged. It cannot be closed
     * implicitly, because that could misclassify dropped/ambiguous work.
     */
    final class PhysicalCompletion {
        private final long sequence;
        private boolean submitReturned;
        private boolean acknowledged;
        private boolean released;

        private PhysicalCompletion(long sequence) { this.sequence = sequence; }
        long messageSequence() { return sequence; }

        /** Retain physical ownership and fence DATA if the actual actor fails. */
        void quarantine(Throwable failure) {
            Objects.requireNonNull(failure, "physical actor failure");
            synchronized (lock) {
                poisoned = true;
            }
        }

        void acknowledge() {
            synchronized (lock) {
                requireOperational();
                if (acknowledged)
                    throw new IllegalStateException("duplicate physical completion receipt");
                // A legacy bare sequence acknowledgement is never sufficient
                // to release a tracked physical operation's identity.
                if (!trackedPhysicalSequences.contains(sequence))
                    throw new IllegalStateException("stale or unregistered physical receipt");
                // Capacity/sequence commit first: retain both physical identity
                // and reservation on any failure, so retry is fail-closed.
                mailbox.complete(sequence);
                trackedPhysicalSequences.remove(sequence);
                acknowledged = true;
                releaseIfCompleted();
            }
        }

        private void returned() {
            synchronized (lock) {
                submitReturned = true;
                releaseIfCompleted();
            }
        }

        private void releaseIfCompleted() {
            if (!submitReturned || !acknowledged || released) return;
            if (physicalSubmissions <= 0) {
                poisoned = true;
                throw new IllegalStateException("physical submission accounting underflow");
            }
            --physicalSubmissions;
            released = true;
        }
    }

    /**
     * Trusted host bridge to a real SHARED ActorRuntime mailbox. A successful
     * ActorRef.send() only admits the message; the runtime's physicalDrain()
     * Future proves the prior message was processed AND the actor carrier,
     * source Task and continuation lanes were physically quiescent.
     * Never acknowledge a tracked dispatch at enqueue time.
     *
     * The decoder is a trusted canonical payload adapter: ActorRuntime may
     * freeze arrays as immutable lists, so the caller chooses its actor's
     * actual message type. This does not establish guest Task/FFI quiescence,
     * durable state migration, or physical isolate reclamation.
     */
    static <M> TrackedSubmit<ActorRuntime.ActorRef<M>> actorRuntimeDrainSubmitter(
            Function<byte[], M> decode) {
        Objects.requireNonNull(decode, "trusted message decoder");
        return (physicalRoute, envelope, completion) -> {
            Objects.requireNonNull(physicalRoute, "trusted actor route");
            M message = Objects.requireNonNull(
                    decode.apply(envelope.payload()), "decoded actor message");
            // Neither ActorRef.send() nor caller-return licenses retirement.
            physicalRoute.send(message);
            OresFuture<Void> drained = Objects.requireNonNull(
                    physicalRoute.physicalDrain(), "runtime physical actor drain future");
            drained.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) {
                    completion.quarantine(failure);
                    return;
                }
                try {
                    completion.acknowledge();
                } catch (RuntimeException | Error inconsistentReceipt) {
                    completion.quarantine(inconsistentReceipt);
                }
            });
        };
    }

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
        private final ActorRuntime.UpgradeIngressLease physicalIngress;
        // Physical drain is observed only AFTER the direct-ActorRef send gate
        // has been frozen. A Future that fails never counts as quiescence.
        private final java.util.concurrent.atomic.AtomicBoolean physicalDrained =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final OresFuture<Void> physicalDrain;
        private boolean finished;

        private Stage(Published<R> prior, ActorUpgradeEpochGate.Image image,
                      ActorUpgradePreflightV1 preflight, long dispatchWatermark,
                      ActorRuntime.UpgradeIngressLease physicalIngress,
                      OresFuture<Void> physicalDrain) {
            this.prior = prior;
            this.image = image;
            this.preflight = preflight;
            this.dispatchWatermark = dispatchWatermark;
            this.physicalIngress = physicalIngress;
            this.physicalDrain = physicalDrain;
            if (physicalDrain == null) physicalDrained.set(true);
        }

        private void observePhysicalDrain() {
            if (physicalDrain == null) return; // host-only non-ActorRef route
            physicalDrain.whenCompleteRuntime((ignored, failure) -> {
                // Runtime Future notifications can execute under the old
                // actor's lifecycle monitor. Acquiring the coordinator lock
                // here would invert stage(): coordinator -> actor lifecycle.
                // Both flags are independently publication-safe atomics/
                // volatiles; commit checks them under its own monitor.
                if (failure != null) {
                    poisoned = true; // old actor may have died; no rollback
                } else {
                    physicalDrained.set(true);
                }
            });
        }

        boolean quiescent() {
            synchronized (lock) {
                requireStage(this);
                return physicalDrained.get() && preflight.quiescentSnapshot();
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
                // A bare or foreign physical ActorRef may not become the
                // published V2 route. In particular, an unready candidate can
                // later fail its factory and silently discard admitted mail.
                if (prior.physicalRoute() instanceof ActorRuntime.ActorRef<?> old) {
                    if (!(readyPhysicalRoute instanceof ActorRuntime.ActorRef<?> next))
                        throw new IllegalArgumentException(
                                "physical ActorRef upgrade requires an ActorRef successor");
                    old.requireReadyUpgradeSuccessor(next);
                } else if (readyPhysicalRoute instanceof ActorRuntime.ActorRef<?>) {
                    throw new IllegalArgumentException(
                            "host-only route cannot publish a physical ActorRef successor");
                }
                return preflight.withPreparedAuthority(() -> {
                if (!physicalDrained.get()
                        || !preflight.quiescentSnapshot()) return false;
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

                // One volatile serving-route publication. For real actors,
                // the final assignment executes under V2's lifecycle monitor,
                // so a racing STOP either wins BEFORE the check (reject V2)
                // or follows the successful publication. No arbitrary
                // callbacks, Future notifications or cleanup run under that
                // candidate monitor.
                try {
                    if (prior.physicalRoute() instanceof ActorRuntime.ActorRef<?> old) {
                        old.withReadyUpgradeSuccessorPublication(
                                (ActorRuntime.ActorRef<?>) readyPhysicalRoute,
                                () -> active = next);
                    } else {
                        active = next;
                    }
                } catch (RuntimeException | Error rejected) {
                    // Readiness may have passed before another logical route
                    // claimed this V2 actor. The newly allocated generation
                    // has NOT been published and must not leak an open gate.
                    try {
                        nextGate.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (cleanupFailure != rejected)
                            rejected.addSuppressed(cleanupFailure);
                    }
                    throw rejected;
                }
                pending = null;
                finished = true;
                try {
                    // Retire the old Task authority before unfreezing DATA.
                    // If post-commit cleanup fails, NEVER roll back to old.
                    preflight.finishPublished();
                    if (readyPhysicalRoute instanceof ActorRuntime.ActorRef<?> candidate) {
                        // Actor done() can settle inside its lifecycle monitor.
                        // This callback must NOT acquire the coordinator lock,
                        // or a STOP/publication lock inversion could deadlock.
                        // The volatile poison is fail-closed even if V2 dies
                        // immediately after successful publication.
                        candidate.done().whenCompleteRuntime((ignored, terminal) -> {
                            if (active == next) poisoned = true;
                        });
                    }
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
                    if (physicalIngress != null) physicalIngress.close();
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
    // until every in-flight physical submit returns or throws. Tracked async
    // submissions stay pinned through the authenticated physical completion.
    private int physicalSubmissions;
    // Authenticated callback identity for async paths: bare sequence completion
    // cannot acknowledge one of these in-flight physical deliveries.
    private final Set<Long> trackedPhysicalSequences = new HashSet<>();
    // Terminal V2 observers write this without taking the coordinator lock.
    private volatile boolean poisoned;
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

    /**
     * Bind an external host admission (for example an HTTP exchange lease) to
     * the exact authoritative route observation in one critical section.
     *
     * The callback must perform bounded host bookkeeping only: no guest code,
     * socket I/O, actor dispatch, waiting, or route mutation. A pending staged
     * actor upgrade deliberately still admits against the currently published
     * route until the single publication point changes {@code active}.
     */
    <T> T withCurrentAdmission(Function<Published<R>, T> admit) {
        Objects.requireNonNull(admit, "trusted external admission");
        synchronized (lock) {
            requireOperational();
            return admit.apply(active);
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
            // Freeze the logical proxy first and direct ActorRef ingress
            // second. An uncommitted physical send cannot cross the actor's
            // final mailbox-admission lock after its freeze is acquired.
            var preflight = ActorUpgradePreflightV1.prepare(
                    active.taskAdmission(), mailbox, candidateImage);
            ActorRuntime.UpgradeIngressLease ingress = null;
            try {
                OresFuture<Void> physicalDrain = null;
                if (active.physicalRoute() instanceof ActorRuntime.ActorRef<?> old) {
                    ingress = old.freezeUpgradeIngress();
                    // Snapshot AFTER direct ingress is frozen, so every
                    // previously admitted operation is covered by this barrier.
                    physicalDrain = old.physicalDrain();
                }
                Stage ready = new Stage(active, candidateImage, preflight,
                        preflight.dispatchWatermark(), ingress, physicalDrain);
                pending = ready;
                ready.observePhysicalDrain();
                return ready;
            } catch (RuntimeException | Error admissionFailure) {
                try {
                    preflight.close();
                } catch (RuntimeException | Error rollbackFailure) {
                    poisoned = true;
                    if (admissionFailure != rollbackFailure)
                        admissionFailure.addSuppressed(rollbackFailure);
                }
                try {
                    if (ingress != null) ingress.close();
                } catch (RuntimeException | Error rollbackFailure) {
                    poisoned = true;
                    if (admissionFailure != rollbackFailure)
                        admissionFailure.addSuppressed(rollbackFailure);
                }
                pending = null;
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

    /**
     * Capture one dispatched envelope and hold a physical reservation until
     * its trustworthy completion receipt AND return from submit(). Unlike the
     * legacy synchronous adapter, an async queue handoff cannot end this pin.
     * A failed submit may already have accepted the message: quarantine DATA,
     * preserve the outstanding envelope, and never blindly replay/refund.
     */
    boolean dispatchNextTracked(TrackedSubmit<R> submit) {
        Objects.requireNonNull(submit, "trusted tracked physical submit");
        final LogicalActorProxyMailboxV1.Envelope envelope;
        final R physicalRoute;
        final PhysicalCompletion completion;
        synchronized (lock) {
            requireOperational();
            if (pending != null) return false;
            if (physicalSubmissions == Integer.MAX_VALUE)
                throw new IllegalStateException("physical submit reservation budget exhausted");
            envelope = mailbox.pollDispatch();
            if (envelope == null) return false;
            try {
                completion = new PhysicalCompletion(envelope.messageSequence());
                if (!trackedPhysicalSequences.add(envelope.messageSequence()))
                    throw new IllegalStateException("duplicate tracked physical sequence");
            } catch (RuntimeException | Error allocationFailure) {
                poisoned = true;
                throw allocationFailure; // Envelope already dispatched; never replay.
            }
            ++physicalSubmissions;
            physicalRoute = active.physicalRoute();
        }
        try {
            submit.submit(physicalRoute, envelope, completion);
            completion.returned();
            return true;
        } catch (RuntimeException | Error unknownDelivery) {
            synchronized (lock) {
                poisoned = true;
            }
            throw new IllegalStateException(
                    "ambiguous asynchronous submit; logical DATA quarantined",
                    unknownDelivery);
        }
    }

    /** Legacy host-only completion; async transport MUST use its receipt. */
    void complete(long messageSequence) {
        synchronized (lock) {
            requireOperational();
            if (trackedPhysicalSequences.contains(messageSequence))
                throw new IllegalStateException(
                        "tracked physical message requires its own completion receipt");
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
            // The legacy callback may have returned while its actor turn is
            // still running. Never discard its retained mailbox envelope or
            // retire a pinned Task image merely because submit() returned.
            // A queued-but-never-dispatched message is still an admitted
            // obligation. Do not let AutoCloseable terminalize this logical
            // actor and silently discard its FIFO payload or retained credits.
            // An authorized durable transfer/cancellation path needs its own
            // explicit proof; ordinary close() is DRAIN-ONLY.
            var mail = mailbox.snapshot();
            if (mail.retainedMessages() != 0
                    || mail.queuedMessages() != 0
                    || mail.outstandingMessages() != 0
                    || active.taskAdmission().livePins() != 0)
                throw new IllegalStateException(
                        "cannot retire logical route with admitted mail or Task execution");
            if (!poisoned && pending != null)
                throw new IllegalStateException(
                        "rollback or commit staged route before ordinary shutdown");
            closed = true;
            active.taskAdmission().close();
            mailbox.close();
            // Do not attempt rollback or reopen a mailbox after terminal close.
            pending = null;
        }
    }
}
