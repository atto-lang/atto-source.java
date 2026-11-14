package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.DataOrientedBorrowChecker.Access.*;
import static dev.oreslang.types.DataOrientedBorrowChecker.Layout.*;
import static org.junit.jupiter.api.Assertions.*;

final class DataOrientedBorrowCheckerTest {
    private static DataOrientedBorrowChecker.Place place(
            DataOrientedBorrowChecker.Layout layout,
            String archetype,
            String component,
            String field,
            DataOrientedBorrowChecker.Range rows) {
        return new DataOrientedBorrowChecker.Place(
                layout, "world", archetype, component, field, rows, 7);
    }

    private static DataOrientedBorrowChecker.Loan loan(
            DataOrientedBorrowChecker.Place place,
            DataOrientedBorrowChecker.Access access) {
        return new DataOrientedBorrowChecker.Loan(place, access);
    }

    @Test
    void siblingFieldsAreIndependentAcrossEveryPhysicalLayout() {
        for (var layout : DataOrientedBorrowChecker.Layout.values()) {
            var x = loan(place(layout, "moving", "Position", "x", null), WRITE);
            var y = loan(place(layout, "moving", "Position", "y", null), READ);
            assertFalse(DataOrientedBorrowChecker.conflicts(x, y), layout.toString());
        }
    }

    @Test
    void wholeComponentOverlapsDescendantField() {
        var all = loan(place(SOA, "moving", "Position", null, null), READ);
        var x = loan(place(SOA, "moving", "Position", "x", null), WRITE);
        assertTrue(DataOrientedBorrowChecker.conflicts(all, x));
    }

    @Test
    void disjointAoSoAChunksCanBeWrittenInParallel() {
        var first = loan(place(AOSOA, "moving", "Position", null,
                new DataOrientedBorrowChecker.Range(0, 16)), WRITE);
        var second = loan(place(AOSOA, "moving", "Position", null,
                new DataOrientedBorrowChecker.Range(16, 32)), WRITE);
        assertFalse(DataOrientedBorrowChecker.conflicts(first, second));
        assertTrue(DataOrientedBorrowChecker.canRunConcurrently(
                List.of(first), List.of(second)));
    }

    @Test
    void overlappingChunkWritesConflict() {
        var first = loan(place(AOSOA, "moving", "Position", "x",
                new DataOrientedBorrowChecker.Range(0, 16)), WRITE);
        var second = loan(place(AOSOA, "moving", "Position", "x",
                new DataOrientedBorrowChecker.Range(8, 24)), READ);
        assertTrue(DataOrientedBorrowChecker.conflicts(first, second));
    }

    @Test
    void differentComponentsAndArchetypesCanBeScheduledIndependently() {
        var position = loan(place(SOA, "moving", "Position", null, null), WRITE);
        var velocity = loan(place(SOA, "moving", "Velocity", null, null), WRITE);
        var sleepingPosition =
                loan(place(SOA, "sleeping", "Position", null, null), WRITE);
        assertFalse(DataOrientedBorrowChecker.conflicts(position, velocity));
        assertFalse(DataOrientedBorrowChecker.conflicts(position, sleepingPosition));
    }

    @Test
    void readReadOverlapIsAllowedButInternalWriteAliasIsRejected() {
        var whole = loan(place(AOS, "moving", "Position", null, null), READ);
        var xRead = loan(place(AOS, "moving", "Position", "x", null), READ);
        assertDoesNotThrow(() ->
                DataOrientedBorrowChecker.requireInternallyValid(List.of(whole, xRead)));

        var xWrite = loan(place(AOS, "moving", "Position", "x", null), WRITE);
        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.requireInternallyValid(
                        List.of(whole, xWrite)));
    }

    @Test
    void staleEpochNeverBecomesFakeDisjointness() {
        var current = place(AOSOA, "moving", "Position", "x",
                new DataOrientedBorrowChecker.Range(0, 16));
        var stale = new DataOrientedBorrowChecker.Place(
                AOSOA, "world", "moving", "Position", "x",
                new DataOrientedBorrowChecker.Range(16, 32), 6);
        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.requireCurrentEpoch(stale, 7));
        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.overlaps(current, stale));
    }

    @Test
    void blankLogicalPlaceSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new DataOrientedBorrowChecker.Place(
                        SOA, "world", " ", "Position", null, null, 7));
        assertThrows(IllegalArgumentException.class, () ->
                new DataOrientedBorrowChecker.Place(
                        SOA, "world", "moving", " ", null, null, 7));
        assertThrows(IllegalArgumentException.class, () ->
                new DataOrientedBorrowChecker.Place(
                        SOA, "world", "moving", "Position", " ", null, 7));
    }
    @Test
    void malformedSingletonIsRejectedEvenWhenOtherSetIsEmpty() {
        var malformed = java.util.Arrays.asList((DataOrientedBorrowChecker.Loan) null);
        assertThrows(NullPointerException.class, () ->
                DataOrientedBorrowChecker.canRunConcurrently(malformed, List.of()));
        assertThrows(NullPointerException.class, () ->
                DataOrientedBorrowChecker.canRunConcurrently(List.of(), malformed));
        assertThrows(NullPointerException.class, () ->
                DataOrientedBorrowChecker.requireInternallyValid(malformed));
    }

    @Test
    void unicodeBlankStoreIsRejectedButNonBreakingSpaceIsAnIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                new DataOrientedBorrowChecker.Place(SOA, "\u3000", null, null, null, null, 0));
        assertDoesNotThrow(() ->
                new DataOrientedBorrowChecker.Place(SOA, "\u00a0", null, null, null, null, 0));
    }
    @Test
    void conflictingLoansInsideOneWorksetCannotBeAdmittedAgainstEmptyPeer() {
        var region = place(SOA, "moving", "Position", "x", null);
        var reader = loan(region, READ);
        var writer = loan(region, WRITE);
        var conflicting = List.of(reader, writer);

        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.canRunConcurrently(conflicting, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.canRunConcurrently(List.of(), conflicting));

        // Valid independent readers remain admitted even in the same workset.
        assertTrue(DataOrientedBorrowChecker.canRunConcurrently(
                List.of(reader, reader), List.of()));
    }

    @Test
    void malformedHierarchyCannotFabricateIndependentProjectedLoans() {
        assertThrows(IllegalArgumentException.class, () ->
                new DataOrientedBorrowChecker.Place(
                        SOA, "world", null, "Position", "x", null, 7));
        assertDoesNotThrow(() ->
                new DataOrientedBorrowChecker.Place(
                        SOA, "world", null, null, null, null, 7));
    }

    @Test
    void oversizedValidReadWorksetFailsAdmissionBeforeQuadraticPairScan() {
        var region = place(AOS, "moving", "Position", "x", null);
        var read = loan(region, READ);
        var excessive = java.util.Collections.nCopies(
                DataOrientedBorrowChecker.MAX_LOANS_PER_WORKSET + 1, read);
        var error = assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.canRunConcurrently(excessive, List.of()));
        assertTrue(error.getMessage().contains("admission budget"));
        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.canRunConcurrently(List.of(), excessive));
        assertThrows(IllegalArgumentException.class, () ->
                DataOrientedBorrowChecker.requireInternallyValid(excessive));

        var bounded = java.util.Collections.nCopies(
                DataOrientedBorrowChecker.MAX_LOANS_PER_WORKSET, read);
        assertTrue(DataOrientedBorrowChecker.canRunConcurrently(
                bounded, List.of()));
    }

}

