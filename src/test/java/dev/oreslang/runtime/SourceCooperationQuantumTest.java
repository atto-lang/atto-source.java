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
            assertEquals(i % 128 == 0, due);
            if (due) {
                assertEquals(i == 512, q.shouldCooperateAtCheck());
            }
        }
        // Construction + dispatch + 3 pre-cap time probes at 128, 256, 384.
        assertEquals(5, clock.reads.get());
        assertEquals(512, q.completedBackedgesThisDispatch());
    }

    @Test
    void elapsedTimeIsCheckedOnlyOnBatchedSafePoint() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 0; i < 127; i++) {
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
        for (int i = 0; i < 128; i++) q.completedBackedgeNeedsCheck();
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
        for (int i = 0; i < 127; i++) {
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
    void overdueNoYieldRegionPreserves128StepSampling() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int n = 1; n <= 768; ++n) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(n % 128 == 0, due,
                    "overdue quantum invoked expensive polling on backedge " + n);
            if (due) {
                assertEquals(n >= 512, q.shouldCooperateAtCheck());
            }
        }
        assertEquals(768L, q.completedBackedgesThisDispatch());
        // 2 initialization samples, and only 3 pre-cap clock reads.
        assertEquals(5, clock.reads.get());
    }

    @Test
    void signed64BitReductionCounterCannotDisablePeriodicPolls()
            throws ReflectiveOperationException {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        var counter = SourceCooperationQuantum.class.getDeclaredField("backedgesThisDispatch");
        counter.setAccessible(true);
        counter.setLong(q, Long.MAX_VALUE - 1);
        for (int n = 1; n <= 512; ++n) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(n % 128 == 0, due,
                    "poll phase must continue after the saturated total");
            if (due) assertTrue(q.shouldCooperateAtCheck());
            assertEquals(Long.MAX_VALUE, q.completedBackedgesThisDispatch());
        }
        assertEquals(2, clock.reads.get(), "overdue polls must be clock-free");
    }

    @Test
    void backedgeAccountingDoesNotRollOverAtTheUnsigned32BitBoundary()
            throws ReflectiveOperationException {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        var counter = SourceCooperationQuantum.class.getDeclaredField("backedgesThisDispatch");
        counter.setAccessible(true);
        assertEquals(long.class, counter.getType());
        counter.setLong(q, (1L << 32) - 128);
        for (int n = 1; n <= 128; ++n) {
            assertEquals(n == 128, q.completedBackedgeNeedsCheck(),
                    "signed 32-bit boundary must never change phase");
        }
        assertEquals(1L << 32, q.completedBackedgesThisDispatch());
        assertTrue(q.shouldCooperateAtCheck());
        assertEquals(2, clock.reads.get());
    }

    @Test
    void signedClockWrapCannotHideElapsedSoftQuantum() {
        Clock clock = new Clock();
        clock.nanos.set(Long.MAX_VALUE - 5L);
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();

        // Raw signed subtraction would wrap to +11, incorrectly indicating
        // an 11-nanosecond dispatch rather than a clock anomaly.
        clock.nanos.set(Long.MIN_VALUE + 5L);
        for (int i = 1; i < 128; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.completedBackedgeNeedsCheck());
        assertTrue(q.shouldCooperateAtCheck(),
                "overflowed clock delta must request a safe cooperative handoff");
        assertEquals(Long.MAX_VALUE, q.timeSinceLastCooperateNanos(),
                "diagnostics must not portray a wrapped clock as a fresh quantum");
    }

    @Test
    void backwardsClockSampleCannotEraseAnOverdueQuantum() {
        Clock clock = new Clock();
        clock.nanos.set(10_000L);
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        clock.nanos.set(9_999L);
        for (int i = 1; i < 128; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.completedBackedgeNeedsCheck());
        assertTrue(q.shouldCooperateAtCheck(),
                "regressing clock cannot extend the fairness quantum");
        assertEquals(Long.MAX_VALUE, q.timeSinceLastCooperateNanos());
        assertEquals(4, clock.reads.get(),
                "only the batched slow path and diagnostics read the clock");
    }

}
