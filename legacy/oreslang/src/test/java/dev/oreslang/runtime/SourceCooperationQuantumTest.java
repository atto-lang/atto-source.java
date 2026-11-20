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
    void fixed128BackedgeBoundariesAndSoft512Cap() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i <= 512; i++) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(i % 128 == 0, due);
            if (due) assertEquals(i == 512, q.shouldCooperateAtCheck());
        }
        assertEquals(5, clock.reads.get()); // 1 ctor, 1 dispatch, 3 time reads
        assertEquals(512L, q.completedBackedgesThisDispatch());
    }

    @Test
    void deadlineAndWatchdogOnlyAtOneAuthorizedPoll() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i < 128; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
            assertFalse(q.shouldCooperateAtCheck(true));
        }
        assertEquals(2, clock.reads.get());
        clock.nanos.set(2_000_001L);
        assertTrue(q.completedBackedgeNeedsCheck());
        assertTrue(q.shouldCooperateAtCheck());
        assertFalse(q.shouldCooperateAtCheck(true), "ticket consumed");
        assertEquals(3, clock.reads.get());
    }

    @Test
    void watchdogRequestNeverNeedsClockWhenPollIsDue() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 0; i < 128; i++) q.completedBackedgeNeedsCheck();
        assertTrue(q.shouldCooperateAtCheck(true));
        assertFalse(q.shouldCooperateAtCheck(false));
        assertEquals(2, clock.reads.get());
    }

    @Test
    void pendingAwaitPreservesCooperationClockButNotFairnessDeadline() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        q.recordedCooperate();
        clock.nanos.set(2_000_000_000L);
        q.beginDispatch();
        assertEquals(2_000_000_000L, q.timeSinceLastCooperateNanos());
        for (int i = 0; i < 128; i++) q.completedBackedgeNeedsCheck();
        assertFalse(q.shouldCooperateAtCheck());
        q.recordedCooperate();
        assertEquals(0L, q.timeSinceLastCooperateNanos());
    }

    @Test
    void overdueNoYieldRegionNeverDegeneratesIntoEveryBackedgePolling() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i <= 1_024; i++) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(i % 128 == 0, due);
            if (due) {
                assertEquals(i >= 512, q.shouldCooperateAtCheck());
                assertFalse(q.shouldCooperateAtCheck(), "only one poll per ticket");
            }
        }
        assertEquals(1_024L, q.completedBackedgesThisDispatch());
        assertEquals(5, clock.reads.get(), "overdue checks do not read a clock");
    }

    @Test
    void repeatedShortResumptionsCannotEvadeLifetimePollPhase() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        int polls = 0;
        for (int turn = 0; turn < 8; turn++) {
            q.beginDispatch();
            for (int i = 0; i < 127; i++) {
                final int absolute = turn * 127 + i + 1;
                boolean due = q.completedBackedgeNeedsCheck();
                assertEquals(absolute % 128 == 0, due);
                if (due) {
                    polls++;
                    assertFalse(q.shouldCooperateAtCheck());
                } else {
                    int reads = clock.reads.get();
                    assertFalse(q.shouldCooperateAtCheck());
                    assertEquals(reads, clock.reads.get());
                }
            }
            assertEquals(127, q.completedBackedgesThisDispatch());
        }
        assertEquals(7, polls);
        assertEquals(1 + 8 + 7, clock.reads.get());
    }

    @Test
    void repeatedFullDispatchesReuseOneQuantumWithoutChangingLogicalClock() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        for (int turn = 0; turn < 32; turn++) {
            clock.nanos.addAndGet(2_000_000L);
            q.beginDispatch();
            for (int i = 0; i < 512; i++) {
                if (q.completedBackedgeNeedsCheck()) {
                    assertEquals(i == 511, q.shouldCooperateAtCheck());
                }
            }
            q.recordedCooperate();
        }
        assertEquals(512, q.completedBackedgesThisDispatch());
        assertEquals(0L, q.timeSinceLastCooperateNanos());
    }

    @Test
    void signedLongMaxSaturatesWithoutForcingPerStepColdChecks()
            throws ReflectiveOperationException {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        var field = SourceCooperationQuantum.class.getDeclaredField("backedgesThisDispatch");
        field.setAccessible(true);
        field.setLong(q, Long.MAX_VALUE - 1);
        for (int i = 1; i <= 256; i++) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(i % 128 == 0, due);
            if (due) assertTrue(q.shouldCooperateAtCheck());
            assertEquals(Long.MAX_VALUE, q.completedBackedgesThisDispatch());
        }
        assertEquals(2, clock.reads.get());
    }

    @Test
    void unsigned32BoundaryDoesNotAffectSampling() throws ReflectiveOperationException {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        var field = SourceCooperationQuantum.class.getDeclaredField("backedgesThisDispatch");
        field.setAccessible(true);
        field.setLong(q, (1L << 32) - 64);
        for (int i = 1; i <= 128; i++) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(i == 128, due);
            if (due) assertTrue(q.shouldCooperateAtCheck());
        }
        assertEquals((1L << 32) + 64, q.completedBackedgesThisDispatch());
        assertEquals(2, clock.reads.get());
    }

    @Test
    void millionFastPathBackedgesNeverReadClock() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i <= 1_048_576; i++) {
            assertEquals(i % 128 == 0, q.completedBackedgeNeedsCheck());
        }
        assertEquals(2, clock.reads.get());
    }

    @Test
    void stalePollTicketCannotBeReplayedAfterPhysicalRedispatch() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i <= 128; i++) {
            assertEquals(i == 128, q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.shouldCooperateAtCheck(true));
        assertFalse(q.shouldCooperateAtCheck(true), "ticket is single-use");
        assertEquals(2, clock.reads.get());
        q.beginDispatch();
        assertFalse(q.shouldCooperateAtCheck(true),
                "a new carrier must earn a fresh 128-backedge ticket");
        assertEquals(3, clock.reads.get(),
                "only beginDispatch may sample the clock here");
        for (int i = 1; i <= 128; i++) {
            assertEquals(i == 128, q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.shouldCooperateAtCheck(true),
                "unacknowledged watchdog debt remains task-owned");
        assertFalse(q.shouldCooperateAtCheck(true), "new ticket is also single-use");
        assertEquals(3, clock.reads.get());
    }
}
