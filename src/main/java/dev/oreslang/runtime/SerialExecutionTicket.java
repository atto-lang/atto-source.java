package dev.oreslang.runtime;

import java.util.Objects;
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
    private final Object actorId;
    private final Object incarnation;
    private final long taskId;
    private final SerialActorAdmission admission;
    private final SerialActorAdmission.Lease handlerLease;
    private final BooleanSupplier actorAlive;

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

    /** Producer/IO threads can enqueue, but they cannot execute guest code. */
    boolean mayEnqueue() {
        if (!actorAlive.getAsBoolean()) return false;
        if (admission != null && !admission.acceptsActorScopedCallbacks()) return false;
        return handlerLease == null
                ? admission == null || admission.canQueueUnownedContinuation()
                : handlerLease.state() != SerialActorAdmission.State.FINISHED;
    }

    /** Called on the actor's entered execution-context immediately before run. */
    void requireRunnable(
            Object executingActorId,
            Object executingIncarnation,
            SerialActorAdmission.Lease activeReceive) {
        if (!actorAlive.getAsBoolean()
                || !actorId.equals(executingActorId)
                || incarnation != executingIncarnation) {
            throw new IllegalStateException("stale or foreign actor continuation ticket");
        }
        if (handlerLease != activeReceive) {
            throw new IllegalStateException(
                    "scheduler task cannot borrow a different serial receive's authority");
        }
        if (handlerLease != null) {
            handlerLease.requireActive();
        } else if (admission != null) {
            admission.requireIdleActorScopedCallback();
        }
    }
}
