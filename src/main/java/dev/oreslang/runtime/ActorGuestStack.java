package dev.oreslang.runtime;

/**
 * Actor-cell-owned logical guest invocation budget.
 *
 * <p>Each ActorRuntime.ActorCell allocates a distinct instance for its lifetime.
 * The actor identity follows this object across carrier migration; no global
 * actor-id map or carrier-thread ownership is used. A new supervised actor
 * incarnation receives a fresh instance, so an earlier failure cannot grant
 * execution authority to a restarted incarnation.</p>
 *
 * <p>THIS IS NOT A REPLACEMENT FOR THE CARRIER'S NATIVE STACK. The interpreter
 * still uses Java frames, protected separately by GuestCallDepthGuard. Future
 * compiler-lowered heap-resident frames and suspended continuations must charge
 * the same actor-owned budget before reserving memory.</p>
 */
final class ActorGuestStack {
    private int liveFrames;
    private int highWaterFrames;
    private boolean poisoned;
    /**
     * One bounded logical continuation root per live source Task. These roots
     * survive Await/Cooperate independently of carrier Java stack frames.
     * Actual compiler-lowered lexical frame bytes are a separate M2 gate.
     */
    static final int MAX_TASK_ROOTS = 64;
    private int liveTaskRoots;
    private int highWaterTaskRoots;

    synchronized int reserveFrame() {
        if (poisoned || liveFrames >= GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS) {
            poisoned = true;
            throw new GuestCallDepthExceededException(
                    GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS);
        }
        liveFrames++;
        highWaterFrames = Math.max(highWaterFrames, liveFrames);
        return liveFrames;
    }

    synchronized void releaseFrame(int expectedDepth) {
        if (liveFrames <= 0 || liveFrames != expectedDepth) {
            poisoned = true;
            throw new IllegalStateException("actor logical guest-frame stack imbalance");
        }
        liveFrames--;
    }

    synchronized void poison() {
        poisoned = true;
    }

    synchronized int activeFrames() {
        return liveFrames;
    }

    synchronized int highWaterFrames() {
        return highWaterFrames;
    }

    synchronized boolean isPoisoned() {
        return poisoned;
    }

    synchronized TaskRoot reserveTaskRoot(long actorGeneration) {
        if (actorGeneration <= 0) {
            throw new IllegalArgumentException("actor generation must be positive");
        }
        if (poisoned) {
            throw new GuestCallDepthExceededException(
                    GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS);
        }
        if (liveTaskRoots >= MAX_TASK_ROOTS) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "actor logical source-task stack root capacity exhausted");
        }
        liveTaskRoots++;
        highWaterTaskRoots = Math.max(highWaterTaskRoots, liveTaskRoots);
        return new TaskRoot(this, actorGeneration);
    }

    private synchronized void releaseTaskRoot() {
        if (liveTaskRoots <= 0) {
            poisoned = true;
            throw new IllegalStateException("actor source-task root underflow");
        }
        liveTaskRoots--;
    }

    synchronized int activeTaskRoots() {
        return liveTaskRoots;
    }

    synchronized int highWaterTaskRoots() {
        return highWaterTaskRoots;
    }

    /** Unforgeable runtime-side handle rooted in one ActorCell incarnation. */
    static final class TaskRoot {
        private final ActorGuestStack owner;
        private final long actorGeneration;
        private final java.util.concurrent.atomic.AtomicBoolean retired =
                new java.util.concurrent.atomic.AtomicBoolean();

        private TaskRoot(ActorGuestStack owner, long actorGeneration) {
            this.owner = owner;
            this.actorGeneration = actorGeneration;
        }

        void requireCurrentOwner() {
            if (retired.get() || owner.isPoisoned()
                    || ActorRuntime.currentActorGuestStack() != owner
                    || ActorRuntime.currentActorGeneration() != actorGeneration) {
                throw new SecurityException(
                        "source-task continuation has no current actor-stack authority");
            }
        }

        <T> OresScheduler.Step<T> resume(
                OresScheduler.Task<T> task, OresScheduler.Resume value)
                throws Exception {
            requireCurrentOwner();
            return task.resume(value);
        }

        /** Only physical scheduler quiescence, never Future settlement, retires. */
        void retire() {
            if (retired.compareAndSet(false, true)) owner.releaseTaskRoot();
        }

        boolean isRetired() {
            return retired.get();
        }
    }
}
