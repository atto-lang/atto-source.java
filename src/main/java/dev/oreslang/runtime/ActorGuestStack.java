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
}
