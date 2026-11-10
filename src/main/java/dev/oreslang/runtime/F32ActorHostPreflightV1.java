package dev.oreslang.runtime;

/**
 * Internal actor-scoped preflight and in-flight reservation. This object MUST
 * be minted by trusted scheduler code, never constructed from guest-provided
 * tenant/actor IDs. The physical lease prevents a cooperative STOP path from
 * reclaiming actor-owned f32 memory until the current host operation returns.
 *
 * This is NOT yet wired into ActorRuntime, compiler lowering or real GEMM.
 */
final class F32ActorHostPreflightV1 {
    enum Result { INVALID, WRONG_ACTOR, NOT_RUNNING, BAD_SHAPE, QUOTA, HOST_PREFLIGHT_ONLY }

    private final ExecutionContext ownerContext;
    private final Thread physicalOwner;
    private boolean running = true;
    private int pins;

    F32ActorHostPreflightV1(ExecutionContext trustedActorContext) {
        if (trustedActorContext == null ||
                trustedActorContext.allocationDomain().kind() != ExecutionContext.AllocationKind.ACTOR ||
                trustedActorContext.allocationDomain().owner() <= 0 ||
                trustedActorContext.allocationDomain().generation() <= 0) {
            throw new IllegalArgumentException("f32 host admission requires bound actor ownership");
        }
        ownerContext = trustedActorContext;
        physicalOwner = Thread.currentThread();
    }

    synchronized Result inspect(ExecutionContext presentedContext,
                                int lhsRows, int lhsCols, int rhsRows, int rhsCols,
                                long maxOutputBytes) {
        if (presentedContext == null) return Result.INVALID;
        if (presentedContext != ownerContext) return Result.WRONG_ACTOR;
        if (!running || Thread.currentThread() != physicalOwner) return Result.NOT_RUNNING;
        if (lhsRows < 1 || lhsCols < 1 || rhsRows < 1 || rhsCols < 1 ||
                lhsRows > 32 || lhsCols > 32 || rhsRows > 32 || rhsCols > 32 ||
                lhsCols != rhsRows || maxOutputBytes > 4096) return Result.BAD_SHAPE;
        long bytes = (long) lhsRows * rhsCols * 4;
        if (maxOutputBytes < bytes) return Result.QUOTA;
        return Result.HOST_PREFLIGHT_ONLY;
    }

    /** Atomically combines current-turn admission and in-flight lifetime pin. */
    synchronized OperationPin pinOperation(ExecutionContext presentedContext,
                                          int lhsRows, int lhsCols, int rhsRows, int rhsCols,
                                          long maxOutputBytes) {
        final Result decision = inspect(presentedContext, lhsRows, lhsCols,
                rhsRows, rhsCols, maxOutputBytes);
        if (decision != Result.HOST_PREFLIGHT_ONLY) {
            throw new IllegalStateException("f32 operation denied: " + decision);
        }
        if (pins == Integer.MAX_VALUE) throw new IllegalStateException("f32 pin count exhausted");
        pins++;
        return new OperationPin(this, physicalOwner);
    }

    static final class OperationPin implements AutoCloseable {
        private final F32ActorHostPreflightV1 owner;
        private final Thread physicalOwner;
        private boolean closed;
        private OperationPin(F32ActorHostPreflightV1 owner, Thread physicalOwner) {
            this.owner = owner;
            this.physicalOwner = physicalOwner;
        }
        @Override public void close() {
            synchronized (owner) {
                if (Thread.currentThread() != physicalOwner)
                    throw new IllegalStateException("f32 pin must be returned by physical owner");
                if (closed) throw new IllegalStateException("f32 pin already returned");
                if (owner.pins <= 0) throw new IllegalStateException("invalid pin count");
                closed = true;
                owner.pins--;
                owner.notifyAll();
            }
        }
    }

    /** STOP closes admission; caller must awaitQuiescent() before memory reclamation. */
    synchronized void retireTurn() {
        running = false;
        notifyAll();
    }
    synchronized int activePins() { return pins; }
    synchronized boolean isQuiescent() { return !running && pins == 0; }

    /** May block only on supervisor/carrier-safe threads, never under actor locks. */
    synchronized void awaitQuiescent() throws InterruptedException {
        if (Thread.currentThread() == physicalOwner && pins != 0)
            throw new IllegalStateException("physical owner cannot wait on its own held pin");
        while (running || pins != 0) wait();
    }
}
