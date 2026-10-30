package dev.oreslang.types;

import java.util.Objects;

/**
 * Symbolic compile-time requirement for one state-region loan.
 *
 * <p>Concrete runtime execution IDs, slots, and generations are deliberately
 * absent. Lowering must bind this requirement to the authenticated current
 * logical execution and live region incarnation at runtime.</p>
 */
public record StateRegionLoanRequirement(
        String regionBinding,
        ExecutionBinding executionBinding,
        boolean generationFence,
        long loanId,
        String place,
        StateRegionLoanProvenance.Kind kind,
        StateRegionLoanProvenance.Action action) {

    public enum ExecutionBinding {
        CURRENT_LOGICAL_EXECUTION
    }

    public StateRegionLoanRequirement {
        if (regionBinding == null || regionBinding.isBlank()) {
            throw new IllegalArgumentException(
                    "state-region requirement needs a nonblank region binding");
        }
        Objects.requireNonNull(executionBinding, "executionBinding");
        if (executionBinding != ExecutionBinding.CURRENT_LOGICAL_EXECUTION) {
            throw new IllegalArgumentException(
                    "state-region requirement must bind current logical execution");
        }
        if (!generationFence) {
            throw new IllegalArgumentException(
                    "state-region requirement must require generation fencing");
        }
        if (loanId <= 0) {
            throw new IllegalArgumentException(
                    "state-region requirement loan id must be positive");
        }
        if (place == null || place.isBlank()) {
            throw new IllegalArgumentException(
                    "state-region requirement needs a nonblank place");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(action, "action");
    }

    public boolean matchesRelease(StateRegionLoanRequirement release) {
        Objects.requireNonNull(release, "release");
        return action == StateRegionLoanProvenance.Action.BORROW &&
                release.action == StateRegionLoanProvenance.Action.RELEASE &&
                regionBinding.equals(release.regionBinding) &&
                executionBinding == release.executionBinding &&
                generationFence == release.generationFence &&
                loanId == release.loanId &&
                place.equals(release.place) &&
                kind == release.kind;
    }

    public StateRegionLoanRequirement releaseRequirement() {
        if (action != StateRegionLoanProvenance.Action.BORROW) {
            throw new IllegalStateException(
                    "only a borrow requirement can derive a release requirement");
        }
        return new StateRegionLoanRequirement(
                regionBinding, executionBinding, generationFence, loanId,
                place, kind, StateRegionLoanProvenance.Action.RELEASE);
    }
}
