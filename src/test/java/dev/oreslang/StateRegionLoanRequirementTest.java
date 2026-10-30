package dev.oreslang;

import dev.oreslang.types.StateRegionLoanProvenance;
import dev.oreslang.types.StateRegionLoanRequirement;
import org.junit.jupiter.api.Test;

import static dev.oreslang.types.StateRegionLoanProvenance.Action.BORROW;
import static dev.oreslang.types.StateRegionLoanProvenance.Action.RELEASE;
import static dev.oreslang.types.StateRegionLoanProvenance.Kind.READ;
import static dev.oreslang.types.StateRegionLoanProvenance.Kind.WRITE;
import static dev.oreslang.types.StateRegionLoanRequirement.ExecutionBinding.CURRENT_LOGICAL_EXECUTION;
import static org.junit.jupiter.api.Assertions.*;

final class StateRegionLoanRequirementTest {
    private static StateRegionLoanRequirement borrow() {
        return new StateRegionLoanRequirement(
                "inventory", CURRENT_LOGICAL_EXECUTION, true,
                501, "inventory.stock", WRITE, BORROW);
    }

    @Test
    void matchingReleaseKeepsOnlySymbolicCompilerAuthority() {
        var requirement = borrow();
        var release = requirement.releaseRequirement();
        assertEquals(RELEASE, release.action());
        assertTrue(requirement.matchesRelease(release));
        assertEquals("inventory", requirement.regionBinding());
    }

    @Test
    void wrongRegionLoanPlaceOrModeFailsClosed() {
        var requirement = borrow();
        assertFalse(requirement.matchesRelease(new StateRegionLoanRequirement(
                "orders", CURRENT_LOGICAL_EXECUTION, true,
                501, "inventory.stock", WRITE, RELEASE)));
        assertFalse(requirement.matchesRelease(new StateRegionLoanRequirement(
                "inventory", CURRENT_LOGICAL_EXECUTION, true,
                502, "inventory.stock", WRITE, RELEASE)));
        assertFalse(requirement.matchesRelease(new StateRegionLoanRequirement(
                "inventory", CURRENT_LOGICAL_EXECUTION, true,
                501, "inventory.reserved", WRITE, RELEASE)));
        assertFalse(requirement.matchesRelease(new StateRegionLoanRequirement(
                "inventory", CURRENT_LOGICAL_EXECUTION, true,
                501, "inventory.stock", READ, RELEASE)));
    }

    @Test
    void requirementCannotDisableCurrentExecutionOrGenerationFence() {
        assertThrows(IllegalArgumentException.class, () ->
                new StateRegionLoanRequirement(
                        "inventory", CURRENT_LOGICAL_EXECUTION, false,
                        501, "inventory.stock", WRITE, BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new StateRegionLoanRequirement(
                        "", CURRENT_LOGICAL_EXECUTION, true,
                        501, "inventory.stock", WRITE, BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new StateRegionLoanRequirement(
                        "inventory", CURRENT_LOGICAL_EXECUTION, true,
                        0, "inventory.stock", WRITE, BORROW));
        assertThrows(NullPointerException.class, () ->
                new StateRegionLoanRequirement(
                        "inventory", null, true,
                        501, "inventory.stock", WRITE, BORROW));
    }

    @Test
    void releaseCannotMintAnotherReleaseRequirement() {
        var release = borrow().releaseRequirement();
        assertThrows(IllegalStateException.class, release::releaseRequirement);
    }
}
