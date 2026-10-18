package dev.oreslang.runtime;

/**
 * Opt-in, conservative physical carrier cap for an OresContext.
 *
 * <p>This enforcement slice partitions the configured total among one reserved
 * CONTROL slot, three isolated actor execution domains, and a bounded
 * lifecycle-observer host domain. Together those runtime-owned execution
 * carriers cannot exceed this ceiling, even with native affinity lanes.
 *
 * <p>This is deliberately not yet a work-conserving shared worker pool:
 * unused permits cannot be borrowed between domains without proving
 * affinity/UNTRUSTED isolation. Reactor threads and explicit user-owned
 * OresScheduler(N) threads remain separate resources and are reported
 * independently; they are not actor/CONTROL/lifecycle execution carriers.
 */
record OresCarrierBudget(
        int maximum,
        int controlReserve,
        int privateMaximum,
        int sharedMaximum,
        int untrustedMaximum,
        int lifecycleObserverMaximum) {

    static final String PROPERTY = "ores.runtime.carriers.global.max";

    OresCarrierBudget {
        if (maximum < 5 || maximum > 256 || controlReserve != 1
                || privateMaximum < 1 || sharedMaximum < 1 || untrustedMaximum < 1
                || lifecycleObserverMaximum < 1 || lifecycleObserverMaximum > 4
                || (long) controlReserve + privateMaximum + sharedMaximum
                        + untrustedMaximum + lifecycleObserverMaximum > maximum) {
            throw new IllegalArgumentException("invalid OresVM global carrier budget");
        }
    }

    /** Null means the legacy per-domain configuration remains active.
     * A present but malformed property must fail closed, not silently disable
     * the budget. A five-thread minimum guarantees CONTROL headroom, one
     * isolated slot for each actor domain, and one lifecycle-observer slot.
     */
    static OresCarrierBudget configured() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null) return null;
        // Integer.parseInt accepts "+5" and non-ASCII digits. Keep the
        // runtime topology property in one reproducible, ASCII decimal form.
        if (!raw.matches("[0-9]+"))
            throw new IllegalArgumentException(PROPERTY + " must be ASCII decimal 5..256");
        final int maximum;
        try { maximum = Integer.parseInt(raw); }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PROPERTY + " must be an integer 5..256",
                    invalid);
        }
        if (maximum < 5 || maximum > 256)
            throw new IllegalArgumentException(PROPERTY + " must be 5..256");

        // Preserve equal actor-domain isolation. The 1..3 remainder becomes
        // lifecycle-observer capacity, so every configured permit is assigned
        // without allowing observer threads to escape the advertised ceiling.
        int perDomain = (maximum - 2) / 3;
        int lifecycleObservers = maximum - 1 - (3 * perDomain);
        return new OresCarrierBudget(
                maximum, 1, perDomain, perDomain, perDomain, lifecycleObservers);
    }

    int allocatedCeiling() {
        return controlReserve + privateMaximum + sharedMaximum
                + untrustedMaximum + lifecycleObserverMaximum;
    }
}
