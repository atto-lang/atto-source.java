package dev.oreslang.runtime;

/**
 * Bounded primitive FIFO for recovery roots. The coordinator monitor owns all
 * operations. Repeated queued roots coalesce; an already-polled root may be
 * queued again, as in the previous coordinator. Zero is the empty sentinel.
 * The authoritative failure notice remains in escrow on capacity failure.
 */
final class SupervisorRecoveryQueueV1 {
    private final long[] roots;
    private int head;
    private int size;
    private final IllegalStateException capacityFailure =
            new IllegalStateException("reserved supervisor recovery queue exhausted");

    SupervisorRecoveryQueueV1(int capacity) {
        if (capacity < 1 || capacity > SupervisorFailureEscrowV1.MAX_NODES) {
            throw new IllegalArgumentException("invalid recovery queue capacity");
        }
        roots = new long[capacity];
    }

    boolean offer(long root) {
        if (root <= 0) throw new IllegalArgumentException("invalid recovery root");
        for (int i = 0; i < size; i++) {
            if (roots[(head + i) % roots.length] == root) return true;
        }
        if (size == roots.length) return false;
        roots[(head + size) % roots.length] = root;
        size++;
        return true;
    }

    long poll() {
        if (size == 0) return 0;
        long root = roots[head];
        roots[head] = 0;
        head = (head + 1) % roots.length;
        size--;
        return root;
    }

    IllegalStateException capacityFailure() { return capacityFailure; }
}
