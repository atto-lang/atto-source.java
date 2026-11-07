package dev.oreslang.runtime;

import java.util.Objects;

/**
 * One actor-owned bootstrap frame for a compiler-generated SourceTask.
 *
 * <p>Installed only by an actor-owned OresScheduler.TaskRunner. Admission,
 * suspension and resumption are performed by the scheduler on the owning
 * actor carrier; the frame remains charged while the task is waiting. The
 * underlying payload is reserved managed bytes, NOT lowered guest locals or
 * a native-stack replacement. This object never escapes into guest code.
 */
final class ActorSourceTaskFrame {
    static final int FRAME_CHARGE_BYTES = 64;

    private final ActorFrameArena arena;
    private final ExecutionContext execution;
    private ActorFrameArena.FrameRef frame;
    private boolean waiting;
    private boolean retired;

    ActorSourceTaskFrame(ActorFrameArena arena, ExecutionContext execution) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.execution = Objects.requireNonNull(execution, "execution");
        if (!arena.allocationDomain().equals(execution.allocationDomain()))
            throw new SecurityException("source task frame belongs to a foreign actor generation");
    }

    ActorFrameArena arena() { return arena; }

    private void requireOwnerTurn() {
        if (ActorRuntime.currentActorFrameArena() != arena
                || OresScheduler.currentExecutionContext() != execution) {
            throw new SecurityException(
                    "actor source frame accessed outside its owning scheduler turn");
        }
    }

    /**
     * A source task always re-enters under its actor's physical dispatch.
     * Duplicate carrier execution is forbidden even if the scheduler admitted
     * a stale queued ticket.
     */
    synchronized void beforeResume() {
        requireOwnerTurn();
        if (retired) throw new IllegalStateException("retired actor task frame");
        if (frame == null) {
            frame = arena.push(execution, FRAME_CHARGE_BYTES);
            return;
        }
        if (!waiting) throw new IllegalStateException("actor task frame resumed twice");
        arena.resume(execution, frame);
        waiting = false;
    }

    /** Called after the task returns Await or Cooperate, before carrier exit. */
    synchronized void suspend() {
        requireOwnerTurn();
        if (retired || frame == null || waiting)
            throw new IllegalStateException("actor task suspension without a running frame");
        arena.suspend(execution, frame);
        waiting = true;
    }

    /**
     * Retire only after the scheduler confirms physical guest-turn quiescence.
     * Cancellation and terminal completion share this exact-once path.
     */
    synchronized void retireAfterQuiescence() {
        if (retired) return;
        arena.cancel(execution);
        frame = null;
        waiting = false;
        retired = true;
    }

    synchronized boolean waiting() { return waiting; }
    synchronized boolean retired() { return retired; }
}
