package dev.oreslang.runtime;

/**
 * Fixed-capacity completion scoreboard for actor user-message sequences.
 *
 * <p>Accepted message sequence numbers are monotonic. Completions may arrive
 * out of order when an actor eventually allows more than one executing turn.
 * The frontier advances only when every prior sequence is complete, which is
 * the condition required by drain/barrier semantics.</p>
 *
 * <p>No allocation occurs after construction. The number of out-of-order gaps
 * is bounded by actor execution concurrency, not mailbox depth.</p>
 */
final class ActorCompletionFrontier {
    private final long[] gaps;
    private int gapCount;
    private long contiguousSequence = -1L;
    private long completedCount;

    ActorCompletionFrontier(int maxOutOfOrder) {
        if (maxOutOfOrder < 1 || maxOutOfOrder > 1024) {
            throw new IllegalArgumentException(
                    "maxOutOfOrder must be 1..1024");
        }
        this.gaps = new long[maxOutOfOrder];
    }

    /**
     * Record one unique completed accepted-message sequence and return the
     * highest contiguous completed sequence.
     */
    long complete(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "message completion sequence must be non-negative");
        }
        if (sequence <= contiguousSequence) {
            throw new IllegalStateException(
                    "duplicate or stale actor message completion: " + sequence);
        }
        for (int i = 0; i < gapCount; i++) {
            if (gaps[i] == sequence) {
                throw new IllegalStateException(
                        "duplicate actor message completion: " + sequence);
            }
        }
        if (completedCount == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "actor completed-message counter exhausted");
        }

        completedCount++;
        if (sequence == contiguousSequence + 1L) {
            contiguousSequence = sequence;
            advanceAcrossGaps();
            return contiguousSequence;
        }

        if (gapCount == gaps.length) {
            throw new IllegalStateException(
                    "actor out-of-order completion frontier capacity exceeded");
        }
        gaps[gapCount++] = sequence;
        return contiguousSequence;
    }

    private void advanceAcrossGaps() {
        while (contiguousSequence < Long.MAX_VALUE) {
            long next = contiguousSequence + 1L;
            int found = -1;
            for (int i = 0; i < gapCount; i++) {
                if (gaps[i] == next) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return;
            gaps[found] = gaps[--gapCount];
            contiguousSequence = next;
        }
    }

    long contiguousSequence() {
        return contiguousSequence;
    }

    long contiguousCount() {
        return contiguousSequence + 1L;
    }

    long completedCount() {
        return completedCount;
    }

    int outOfOrderCount() {
        return gapCount;
    }
}
