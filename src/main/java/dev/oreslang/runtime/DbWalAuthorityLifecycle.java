package dev.oreslang.runtime;

/**
 * Host-only physical WAL authority lifecycle. An admitted operation holds
 * its slot through the entire worker callback, not merely Future completion.
 * A never-started WRITE can retire its exclusive slot only through the
 * atomic AsyncNativeIo admission hook. Actual in-flight work is held until
 * the executing worker exits, never a logical Future cancellation.
 *
 * This is local retirement bookkeeping, NOT a tenant capability, durable
 * commit receipt, or safe-to-ACK predicate.
 */
final class DbWalAuthorityLifecycle {
    enum Phase { OPEN, DRAINING, CLOSING, CLOSED }

    private Phase phase = Phase.OPEN;
    private boolean physicallyActive;

    synchronized boolean beginWrite() {
        if (phase != Phase.OPEN || physicallyActive) return false;
        physicallyActive = true;
        return true;
    }

    /**
     * Called only by AsyncNativeIo's atomic never-started admission hook.
     * It is NOT a Future cancellation callback: if a kernel write may have
     * started, this transition is forbidden and physical retirement is needed.
     */
    synchronized void neverStartedWrite() {
        if (!physicallyActive) throw new IllegalStateException(
                "DB WAL duplicate/spurious never-started retirement");
        physicallyActive = false;
    }

    /** Only the owning I/O worker may report its actual physical exit. */
    synchronized void physicalWriteRetired() {
        if (!physicallyActive) throw new IllegalStateException(
                "DB WAL duplicate or spurious physical retirement");
        physicallyActive = false;
    }

    /**
     * Seal new admissions irrevocably. An in-flight write can finish safely,
     * and the owner can repeat this method after observing worker retirement.
     */
    synchronized boolean requestDrain() {
        if (phase == Phase.OPEN) phase = Phase.DRAINING;
        return phase == Phase.DRAINING && !physicallyActive;
    }

    /** Reserve exactly one close attempt before submitting CONTROL work. */
    synchronized boolean beginClose() {
        if (phase != Phase.DRAINING || physicallyActive) return false;
        phase = Phase.CLOSING;
        return true;
    }

    /** Called only after the physical file close and loan exit both succeed. */
    synchronized void physicalCloseRetired() {
        if (phase != Phase.CLOSING || physicallyActive) throw new IllegalStateException(
                "DB WAL close completion without exclusive physical retirement");
        phase = Phase.CLOSED;
    }

    synchronized Phase phase() { return phase; }
    synchronized boolean physicallyActive() { return physicallyActive; }
}
