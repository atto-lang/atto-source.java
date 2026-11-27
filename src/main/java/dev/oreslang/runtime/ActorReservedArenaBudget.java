package dev.oreslang.runtime;

import java.util.Objects;

/** Host-only native arena credit. Aggregate capacity is charged exactly once by
 * ActorRuntime before publication. Allocation refunds debit local committed
 * capacity; only successful physical retirement refunds the aggregate promise. */
final class ActorReservedArenaBudget {
    private final long limit;
    private final Runnable releasePromise;
    private long used;
    private boolean released;
    ActorReservedArenaBudget(long limit, Runnable releasePromise) {
        if (limit <= 0) throw new IllegalArgumentException("E_SPAWN_HEAP_LIMIT");
        this.limit = limit;
        this.releasePromise = Objects.requireNonNull(releasePromise);
    }
    synchronized Runnable reserve(int bytes) {
        if (released || bytes <= 0 || bytes > limit - used)
            throw new IllegalStateException("E_SPAWN_HEAP_CREDIT");
        // Allocate receipt before incrementing the accounting.
        Refund refund = new Refund(bytes);
        used += bytes;
        return refund;
    }
    private final class Refund implements Runnable {
        private final int bytes;
        private boolean done;
        Refund(int bytes) { this.bytes = bytes; }
        @Override public void run() {
            synchronized (ActorReservedArenaBudget.this) {
                if (done) return;
                if (released || bytes > used) throw new IllegalStateException("E_SPAWN_HEAP_REFUND");
                used -= bytes;
                done = true;
            }
        }
    }
    synchronized void releaseAfterArenaClose() {
        if (released || used != 0) throw new IllegalStateException("E_SPAWN_HEAP_IN_FLIGHT");
        // Throwing leaves this receipt retryable.
        releasePromise.run();
        released = true;
    }
}
