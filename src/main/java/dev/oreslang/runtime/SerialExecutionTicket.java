package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Runtime-issued, per-task continuation authority. A ticket is bound to one
 * actor incarnation, one monotonic scheduler task ID and, when serial,
 * exactly the handler lease that was active when that task was started.
 *
 * This is a host-runtime primitive, never an Oreslang guest capability.
 * It does not grant a physical carrier: scheduling and actor-context entry
 * remain the responsibility of ActorRuntime and OresScheduler.
 */
final class SerialExecutionTicket {
    /** A benign canceled task may already have a queued mailbox segment. */
    static final class RetiredTaskException extends IllegalStateException {
        RetiredTaskException() { super("source task execution ticket retired"); }
    }

    private final Object actorId;
    private final Object incarnation;
    private final long taskId;
    private final SerialActorAdmission admission;
    private final SerialActorAdmission.Lease handlerLease;
    private final BooleanSupplier actorAlive;
    /** A canceled/completed source task must not borrow its still-live handler. */
    private final AtomicBoolean retired = new AtomicBoolean();

    SerialExecutionTicket(
            Object actorId,
            Object incarnation,
            long taskId,
            SerialActorAdmission admission,
            SerialActorAdmission.Lease handlerLease,
            BooleanSupplier actorAlive) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.incarnation = Objects.requireNonNull(incarnation, "incarnation");
        if (taskId <= 0L) throw new IllegalArgumentException("invalid task ID");
        if (handlerLease != null
                && (admission == null || !admission.issued(handlerLease))) {
            throw new IllegalArgumentException(
                    "task ticket handler was issued by another actor");
        }
        this.taskId = taskId;
        this.admission = admission;
        this.handlerLease = handlerLease;
        this.actorAlive = Objects.requireNonNull(actorAlive, "actorAlive");
    }

    long taskId() { return taskId; }

    /**
     * Cheap immutable provenance check before a continuation can consume any
     * CONTROL mailbox slot. Do not resolve by numeric task ID or handler
     * generation: both may be reused in another actor/incarnation.
     *
     * Runtime actor IDs are value identities; the incarnation is a
     * deliberately unforgeable, reference-identity execution domain.
     */
    boolean issuedFor(Object candidateActorId, Object candidateIncarnation) {
        return actorId.equals(candidateActorId)
                && incarnation == candidateIncarnation;
    }

    /** Revoke this task's authority independently of its enclosing actor handler. */
    void retire() { retired.set(true); }

    /** Distinguish ordinary task cancellation from invalid foreign authority. */
    boolean isRetired() { return retired.get(); }

    /** Producer/IO threads can enqueue, but they cannot execute guest code. */
    boolean mayEnqueue() {
        if (retired.get() || !actorAlive.getAsBoolean()) return false;
        if (admission != null && !admission.acceptsActorScopedCallbacks()) return false;
        return handlerLease == null
                ? admission == null || admission.canQueueUnownedContinuation()
                : handlerLease.state() != SerialActorAdmission.State.FINISHED;
    }

    /** Check ownership before ActorCell resumes a physically suspended lease.
     * A suspended handler is still authorized to enqueue its own continuation. */
    void requireAdmissible(
            Object executingActorId,
            Object executingIncarnation,
            SerialActorAdmission.Lease activeReceive) {
        if (retired.get()) throw new RetiredTaskException();
        // A foreign actor/incarnation is an authority violation even if the
        // source actor happens to have stopped. A correctly issued ticket
        // revoked by STOP is instead a benign retired continuation to drop:
        // normal cancellation must not poison the old actor's done() result.
        if (!actorId.equals(executingActorId)
                || incarnation != executingIncarnation) {
            throw new IllegalStateException("foreign actor continuation ticket");
        }
        if (!actorAlive.getAsBoolean()) {
            throw new RetiredTaskException();
        }
        // A finished receive can leave an already-queued continuation behind
        // before the scheduler's own terminal callback retires the ticket.
        // It is a stale completion to drop, not a foreign guest failure that
        // should poison the next actor turn. Check this BEFORE comparing the
        // now-cleared/replaced activeReceive, while keeping a *live* foreign
        // lease mismatch fatal.
        if (handlerLease != null
                && handlerLease.state() == SerialActorAdmission.State.FINISHED) {
            throw new RetiredTaskException();
        }
        if (handlerLease != activeReceive) {
            throw new IllegalStateException(
                    "scheduler task cannot borrow a different serial receive's authority");
        }
        if (handlerLease == null && admission != null) {
            admission.requireIdleActorScopedCallback();
        }
    }

    /** Called on the actor's entered execution-context immediately before run. */
    void requireRunnable(
            Object executingActorId,
            Object executingIncarnation,
            SerialActorAdmission.Lease activeReceive) {
        requireAdmissible(executingActorId, executingIncarnation, activeReceive);
        if (handlerLease != null) handlerLease.requireActive();
    }
}
