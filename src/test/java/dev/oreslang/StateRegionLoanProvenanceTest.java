package dev.oreslang;

import dev.oreslang.types.StateRegionLoanProvenance;
import org.junit.jupiter.api.Test;

import static dev.oreslang.types.StateRegionLoanProvenance.Action.BORROW;
import static dev.oreslang.types.StateRegionLoanProvenance.Action.RELEASE;
import static dev.oreslang.types.StateRegionLoanProvenance.Kind.READ;
import static dev.oreslang.types.StateRegionLoanProvenance.Kind.WRITE;
import static org.junit.jupiter.api.Assertions.*;

final class StateRegionLoanProvenanceTest {
    private static StateRegionLoanProvenance borrow() {
        return new StateRegionLoanProvenance(
                9, 3, 7, 1001, 4, 8, 501,
                "inventory.stock", WRITE, BORROW);
    }

    @Test
    void exactReleaseIsAuthorizedWithoutChangingCompilerIdentity() {
        var borrowed = borrow();
        var release = borrowed.releaseRecord();
        assertEquals(RELEASE, release.action());
        assertTrue(borrowed.authorizesRelease(release));
    }

    @Test
    void foreignTaskStaleGenerationWrongRegionAndWrongModeAreRejected() {
        var borrowed = borrow();
        assertFalse(borrowed.authorizesRelease(new StateRegionLoanProvenance(
                9, 3, 7, 1002, 4, 8, 501,
                "inventory.stock", WRITE, RELEASE)));
        assertFalse(borrowed.authorizesRelease(new StateRegionLoanProvenance(
                9, 3, 7, 1001, 4, 7, 501,
                "inventory.stock", WRITE, RELEASE)));
        assertFalse(borrowed.authorizesRelease(new StateRegionLoanProvenance(
                10, 3, 7, 1001, 4, 8, 501,
                "inventory.stock", WRITE, RELEASE)));
        assertFalse(borrowed.authorizesRelease(new StateRegionLoanProvenance(
                9, 3, 7, 1001, 4, 8, 501,
                "inventory.stock", READ, RELEASE)));
    }

    @Test
    void lexicalLoanAndPlaceCannotBeLaundered() {
        var borrowed = borrow();
        assertFalse(borrowed.authorizesRelease(new StateRegionLoanProvenance(
                9, 3, 7, 1001, 4, 8, 502,
                "inventory.stock", WRITE, RELEASE)));
        assertFalse(borrowed.authorizesRelease(new StateRegionLoanProvenance(
                9, 3, 7, 1001, 4, 8, 501,
                "inventory.reserved", WRITE, RELEASE)));
    }

    @Test
    void releaseCannotAuthorizeAnotherRelease() {
        var release = borrow().releaseRecord();
        assertFalse(release.authorizesRelease(release));
        assertThrows(IllegalStateException.class, release::releaseRecord);
    }

    @Test
    void malformedIdentityFailsClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                new StateRegionLoanProvenance(
                        0, 3, 7, 1001, 4, 8, 501,
                        "inventory.stock", WRITE, BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new StateRegionLoanProvenance(
                        9, 3, 7, 1001, 4, 0, 501,
                        "inventory.stock", WRITE, BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new StateRegionLoanProvenance(
                        9, 3, 7, 1001, 4, 8, 501,
                        " ", WRITE, BORROW));
        assertThrows(NullPointerException.class, () ->
                new StateRegionLoanProvenance(
                        9, 3, 7, 1001, 4, 8, 501,
                        "inventory.stock", null, BORROW));
    }
}
