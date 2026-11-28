package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SoftLaneSelectorTest {
    @Test
    void returnsPreferredOnTiesAndNeverChoosesAnInvalidLane() {
        AtomicIntegerArray queues = new AtomicIntegerArray(512);
        for (int i = 0; i < queues.length(); ++i) queues.set(i, 5);
        for (int seed = -1024; seed < 1024; seed++) {
            assertEquals(17, SoftLaneSelector.select(17, 5, queues, seed));
        }
    }

    @Test
    void aRotatedScanCanFindColdLanesWithoutScanningEveryLane() {
        AtomicIntegerArray queues = new AtomicIntegerArray(8192);
        for (int i = 0; i < queues.length(); i++) queues.set(i, 7);
        queues.set(8191, 0);
        // A cold lane outside a 64-slot window must not be pretended visible.
        assertEquals(0, SoftLaneSelector.select(0, 7, queues, 0));
        assertEquals(8191, SoftLaneSelector.select(0, 7, queues, 8170));
        // The 64th additional candidate is deliberately out of budget.
        queues.set(8191, 7);
        queues.set(64, 0);
        assertEquals(0, SoftLaneSelector.select(0, 7, queues, 0));
        assertEquals(64, SoftLaneSelector.select(0, 7, queues, 2));
    }

    @Test
    void zeroQueueWinsAndOverflowedRotatingSeedIsAccepted() {
        AtomicIntegerArray queues = new AtomicIntegerArray(new int[] { 9, 8, 0, 1 });
        assertEquals(2, SoftLaneSelector.select(0, 9, queues, Integer.MIN_VALUE));
        assertEquals(2, SoftLaneSelector.select(0, 9, queues, Integer.MAX_VALUE));
    }

    @Test
    void impossibleQueueSnapshotsAndNegativeDepthAreRejected() {
        AtomicIntegerArray queues = new AtomicIntegerArray(new int[] { 2, 1 });
        assertThrows(IllegalArgumentException.class,
                () -> SoftLaneSelector.select(-1, 2, queues, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SoftLaneSelector.select(2, 2, queues, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SoftLaneSelector.select(0, -1, queues, 0));
        queues.set(1, -1);
        assertThrows(IllegalStateException.class,
                () -> SoftLaneSelector.select(0, 2, queues, 1));
    }

    @Test
    void paddedColumnsReadOnlyLaneSlotsAndRespectTheSpan() {
        int pad = 32;
        var queues = new java.util.concurrent.atomic.AtomicIntegerArray(8 * pad);
        for (int lane = 0; lane < 8; lane++) queues.set(lane * pad, 9);
        queues.set(5 * pad, 1);
        queues.set(6 * pad + 1, 0); // padding slot: never a lane
        assertEquals(5, SoftLaneSelector.select(0, 9, queues, pad, 8, 1));
        // Lanes past the span (not yet started) are never chosen.
        assertEquals(0, SoftLaneSelector.select(0, 9, queues, pad, 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SoftLaneSelector.select(0, 9, queues, pad, 9, 0));
    }
}
