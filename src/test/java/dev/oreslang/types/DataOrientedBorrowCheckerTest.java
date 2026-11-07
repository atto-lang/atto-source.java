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

    private static DataOrientedBorrowChecker.Place tiled(long start, long end, long granule) {
        return new DataOrientedBorrowChecker.Place(AOSOA, "world", "moving", "Position", "x",
                new DataOrientedBorrowChecker.Range(start, end), 7, granule);
    }

    @Test
    void writesThatSplitAnAosoaTileConflictEvenWhenRowsAreDisjoint() {
        // 8-lane tiles: rows 12..15 share tile [8,16) with rows 8..11.
        var low = loan(tiled(0, 12, 8), WRITE);
        var high = loan(tiled(12, 24, 8), WRITE);
        assertFalse(DataOrientedBorrowChecker.overlaps(low.place(), high.place()),
                "logically the rows are disjoint");
        assertTrue(DataOrientedBorrowChecker.conflicts(low, high));
        assertFalse(DataOrientedBorrowChecker.canRunConcurrently(List.of(low), List.of(high)));
        // A tile-split write against a read is still a conflict.
        assertTrue(DataOrientedBorrowChecker.conflicts(low, loan(tiled(12, 24, 8), READ)));
    }

    @Test
    void tileAlignedWritePartitionsStayIndependent() {
        assertFalse(DataOrientedBorrowChecker.conflicts(
                loan(tiled(0, 16, 8), WRITE), loan(tiled(16, 32, 8), WRITE)));
        assertFalse(DataOrientedBorrowChecker.conflicts(
                loan(tiled(0, 16, 16), WRITE), loan(tiled(16, 32, 16), WRITE)));
    }

    @Test
    void coarserGranuleOfEitherSideDecides() {
        // One side is scalar, the other writes 32-row vectors: [0,16) and [16,32)
        // share the 32-row granule.
        assertTrue(DataOrientedBorrowChecker.conflicts(
                loan(tiled(0, 16, 1), WRITE), loan(tiled(16, 32, 32), WRITE)));
        assertTrue(DataOrientedBorrowChecker.conflicts(
                loan(tiled(16, 32, 32), READ), loan(tiled(0, 16, 1), WRITE)));
    }

    @Test
    void readersNeverConflictButStaleEpochStillFailsClosed() {
        assertFalse(DataOrientedBorrowChecker.conflicts(
                loan(tiled(0, 12, 8), READ), loan(tiled(12, 24, 8), READ)));
        var stale = new DataOrientedBorrowChecker.Place(AOSOA, "world", "moving", "Position", "x",
                new DataOrientedBorrowChecker.Range(0, 8), 6, 8);
        assertThrows(IllegalArgumentException.class, () -> DataOrientedBorrowChecker.conflicts(
                loan(tiled(0, 8, 8), READ), loan(stale, READ)));
    }

    @Test
    void granuleWideningSaturatesAtTheTopOfTheRowSpace() {
        long top = Long.MAX_VALUE;
        assertTrue(DataOrientedBorrowChecker.conflicts(
                loan(tiled(top - 3, top, 1024), WRITE), loan(tiled(top - 1030, top - 1020, 1024), WRITE)));
    }

    @Test
    void malformedGranulesAreRejected() {
        for (long g : new long[] {0, -1, 3, 12, 2048, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> tiled(0, 8, g), Long.toString(g));
        }
        assertEquals(1, place(SOA, null, null, null, null).granule(), "legacy constructor is scalar");
    }
}
