package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class SourceCooperationQuantumTest {
    private static final class Clock {
        final AtomicLong nanos = new AtomicLong();
        final AtomicInteger reads = new AtomicInteger();
        long read() { reads.incrementAndGet(); return nanos.get(); }
    }

    @Test
    void zeroClockReadsBetweenChecksAndCooperateAtReductionCap() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        assertEquals(2, clock.reads.get());
        for (int i = 1; i <= 512; i++) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(i % 64 == 0, due);
            if (due) {
                assertEquals(i == 512, q.shouldCooperateAtCheck());
            }
        }
        // Construction + dispatch + only 7 time probes. The final reduction
        // cap is decided without even reading the clock.
        assertEquals(9, clock.reads.get());
        assertEquals(512, q.completedBackedgesThisDispatch());
    }

    @Test
    void elapsedTimeIsCheckedOnlyOnBatchedSafePoint() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 0; i < 63; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
        }
        clock.nanos.set(2_000_001L);
        assertEquals(2, clock.reads.get());
        assertTrue(q.completedBackedgeNeedsCheck());
        assertTrue(q.shouldCooperateAtCheck());
        assertEquals(3, clock.reads.get());
    }

    @Test
    void pendingAwaitDoesNotResetLastCooperateButResetsDispatchQuantum() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        q.recordedCooperate();
        clock.nanos.set(2_000_000_000L); // time parked on a Future
        q.beginDispatch();
        assertEquals(2_000_000_000L, q.timeSinceLastCooperateNanos());
        for (int i = 0; i < 64; i++) q.completedBackedgeNeedsCheck();
        assertFalse(q.shouldCooperateAtCheck(), "parked time is not dispatch running time");
        q.recordedCooperate();
        assertEquals(0L, q.timeSinceLastCooperateNanos());
    }

    @Test
    void repeatedFreshDispatchesUseSameLogicalQuantumObject() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        for (int turn = 0; turn < 32; turn++) {
            clock.nanos.addAndGet(2_000_000L);
            q.beginDispatch();
            for (int i = 0; i < 512; i++) {
                if (q.completedBackedgeNeedsCheck()) {
                    if (i == 511) assertTrue(q.shouldCooperateAtCheck());
                    else assertFalse(q.shouldCooperateAtCheck());
                }
            }
            q.recordedCooperate();
        }
        assertEquals(512, q.completedBackedgesThisDispatch());
        assertEquals(0L, q.timeSinceLastCooperateNanos());
    }

    @Test
    void independentWatchdogRequestWinsAtNextBatchedSafepointWithoutClockRead() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 0; i < 63; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.completedBackedgeNeedsCheck());
        assertEquals(2, clock.reads.get(), "hot-path backedges must not read the clock");
        assertTrue(q.shouldCooperateAtCheck(true),
                "watchdog should request cooperation at the next resumable boundary");
        assertEquals(2, clock.reads.get(),
                "watchdog requests must not perform an extra clock read");
        assertFalse(q.shouldCooperateAtCheck(false),
                "a watchdog notice is not a persistent per-task time charge");
        assertEquals(3, clock.reads.get());
    }

    @Test
    void backedgeAccountingDoesNotRollOverAtTheUnsigned32BitBoundary() throws ReflectiveOperationException {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        var counter = SourceCooperationQuantum.class.getDeclaredField("backedgesThisDispatch");
        counter.setAccessible(true);
        assertEquals(long.class, counter.getType());
        counter.setLong(q, (1L << 32) - 64);
        for (int i = 1; i <= 64; i++) {
            assertEquals(i == 64, q.completedBackedgeNeedsCheck());
        }
        assertEquals(1L << 32, q.completedBackedgesThisDispatch());
        assertTrue(q.shouldCooperateAtCheck());
        // Reduction-cap fast path still avoids an extra monotonic clock read.
        assertEquals(2, clock.reads.get());
    }
}
