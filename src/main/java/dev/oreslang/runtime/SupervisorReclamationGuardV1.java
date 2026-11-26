package dev.oreslang.runtime;

/** Ledger-monitor-owned issuance fence. Reclaimable ledgers never accept an
 * old numeric node identity, including an unpublished identity after rollback. */
final class SupervisorReclamationGuardV1 {
    private final boolean enabled;
    private long highestIssued;

    SupervisorReclamationGuardV1(boolean enabled) { this.enabled = enabled; }

    void requireFresh(long id) {
        if (enabled && id <= highestIssued)
            throw new IllegalStateException("CONTROL identity already issued or out of order");
    }

    void issued(long id) {
        requireFresh(id);
        if (enabled) highestIssued = id;
    }

    /** The last four inputs are independently verified by trusted host code;
     * neither a guest boolean nor logical DONE is physical-release evidence. */
    boolean canReclaim(boolean guardian, boolean alive, boolean fenced,
            boolean pending, boolean deliveryActive, int dataInFlight,
            boolean descendants, boolean physicalReleased, boolean recoveryDrained,
            boolean decisionsDrained, boolean restartDispositionResolved) {
        return enabled && !guardian && !alive && fenced && !pending && !deliveryActive
                && dataInFlight == 0 && !descendants && physicalReleased && recoveryDrained
                && decisionsDrained && restartDispositionResolved;
    }
}
