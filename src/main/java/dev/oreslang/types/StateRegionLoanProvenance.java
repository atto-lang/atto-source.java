package dev.oreslang.types;

import java.util.Objects;

/**
 * Concrete runtime/materialized provenance for a shared/actor state-region loan.
 *
 * <p>The compiler must never fabricate these execution IDs or generations.
 * Source/HIR carries {@link StateRegionLoanRequirement}; trusted runtime
 * lowering materializes this record from the authenticated current logical
 * execution and live region incarnation. It is conformance/receipt metadata,
 * not a guest-mintable capability.</p>
 *
 * <p>The runtime fast path should value-lower or erase this information rather
 * than allocate one managed record per mutation.</p>
 */
public record StateRegionLoanProvenance(
        long regionId,
        long regionGeneration,
        long runtimeDomain,
        long executionId,
        long executionSlot,
        long executionGeneration,
        long loanId,
        String place,
        Kind kind,
        Action action) {

    public enum Kind { READ, WRITE }
    public enum Action { BORROW, RELEASE }

    public StateRegionLoanProvenance {
        if (regionId <= 0 || regionGeneration <= 0 ||
                runtimeDomain <= 0 || executionId <= 0 ||
                executionSlot <= 0 || executionGeneration <= 0 ||
                loanId <= 0) {
            throw new IllegalArgumentException(
                    "state-region provenance identity components must be positive");
        }
        if (place == null || place.isBlank()) {
            throw new IllegalArgumentException(
                    "state-region provenance requires a nonblank place");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(action, "action");
    }

    /**
     * A release may discharge a borrow only when its exact region, logical
     * execution, generation-fenced slot, lexical loan and access mode match.
     */
    public boolean authorizesRelease(StateRegionLoanProvenance release) {
        Objects.requireNonNull(release, "release");
        return action == Action.BORROW &&
                release.action == Action.RELEASE &&
                regionId == release.regionId &&
                regionGeneration == release.regionGeneration &&
                runtimeDomain == release.runtimeDomain &&
                executionId == release.executionId &&
                executionSlot == release.executionSlot &&
                executionGeneration == release.executionGeneration &&
                loanId == release.loanId &&
                place.equals(release.place) &&
                kind == release.kind;
    }

    public StateRegionLoanProvenance releaseRecord() {
        if (action != Action.BORROW) {
            throw new IllegalStateException(
                    "only a borrow provenance record can derive a release record");
        }
        return new StateRegionLoanProvenance(
                regionId, regionGeneration, runtimeDomain, executionId,
                executionSlot, executionGeneration, loanId, place, kind,
                Action.RELEASE);
    }
}
