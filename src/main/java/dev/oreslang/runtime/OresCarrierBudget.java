package dev.oreslang.runtime;

/**
 * Opt-in, conservative physical carrier cap for an OresContext.
 *
 * <p>This first enforcement slice partitions the configured total among one
 * reserved CONTROL slot and the three actor execution domains. Each domain
 * has its own bounded executor; together their physical carrier maxima cannot
 * exceed this ceiling, even with native affinity lanes. This is deliberately
 * not yet a work-conserving shared worker pool: unused permits cannot be
 * borrowed between domains without proving affinity/UNTRUSTED isolation.
 *
 * <p>Reactor threads, the bounded lazy lifecycle-observer host domain, and
 * explicit user-owned OresScheduler(N) threads are separate resources, not
 * hidden actor/CONTROL execution carriers. In particular, this v1 budget does
 * NOT claim to cap lifecycle-observer workers; ActorRuntime exposes their live
 * carrier and backlog counts separately. A future work-conserving VM-wide
 * topology should fold every runtime-owned host worker into one physical
 * budget rather than silently widening this property.
 */
record OresCarrierBudget(
        int maximum,
        int controlReserve,
        int privateMaximum,
        int sharedMaximum,
        int untrustedMaximum) {

    static final String PROPERTY = "ores.runtime.carriers.global.max";

    OresCarrierBudget {
        if (maximum < 4 || maximum > 256 || controlReserve != 1
                || privateMaximum < 1 || sharedMaximum < 1 || untrustedMaximum < 1
                || (long) controlReserve + privateMaximum + sharedMaximum
                        + untrustedMaximum > maximum) {
            throw new IllegalArgumentException("invalid OresVM global carrier budget");
        }
    }

    /** Null means the legacy per-domain configuration remains active.
     * A present but malformed property must fail closed, not silently disable
     * the budget. A four-thread minimum guarantees CONTROL headroom and
     * one isolated slot for each actor domain.
     */
    static OresCarrierBudget configured() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null) return null;
        // Integer.parseInt accepts "+4" and non-ASCII digits. Keep the
        // runtime topology property in one reproducible, ASCII decimal form.
        if (!raw.matches("[0-9]+"))
            throw new IllegalArgumentException(PROPERTY + " must be ASCII decimal 4..256");
        final int maximum;
        try { maximum = Integer.parseInt(raw); }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PROPERTY + " must be an integer 4..256",
                    invalid);
        }
        if (maximum < 4 || maximum > 256)
            throw new IllegalArgumentException(PROPERTY + " must be 4..256");
        int perDomain = (maximum - 1) / 3;
        return new OresCarrierBudget(maximum, 1, perDomain, perDomain, perDomain);
    }

    int allocatedCeiling() {
        return controlReserve + privateMaximum + sharedMaximum + untrustedMaximum;
    }
}
