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
                "a consumed ticket must not re-run the cold path");
        assertEquals(2, clock.reads.get(),
                "a duplicate poll cannot read the clock again");
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
    void eightShortPhysicalDispatchesCannotEvade128BackedgeChecks() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        int polls = 0;
        for (int dispatch = 0; dispatch < 8; dispatch++) {
            q.beginDispatch();
            for (int i = 0; i < 127; i++) {
                int total = dispatch * 127 + i + 1;
                boolean due = q.completedBackedgeNeedsCheck();
                assertEquals(total % 128 == 0, due,
                        "physical dispatch restart skipped logical checkpoint at " + total);
                if (due) {
                    polls++;
                    assertFalse(q.shouldCooperateAtCheck(),
                            "physical dispatch clock is fresh, with no elapsed soft quantum");
                } else {
                    int before = clock.reads.get();
                    assertFalse(q.shouldCooperateAtCheck(true),
                            "watchdog must not bypass the authorized cold poll phase");
                    assertEquals(before, clock.reads.get(),
                            "off-cycle calls must never read monotonic clock");
                }
            }
            assertEquals(127L, q.completedBackedgesThisDispatch());
        }
        assertEquals(7, polls, "8 * 127 committed backedges require 7 cold polls");
        assertEquals(1 + 8 + 7, clock.reads.get(),
                "one constructor, eight new dispatches and seven cold polls");
    }

    @Test
    void coldPollTicketIsSingleUseAndCannotSurvivePhysicalRedispatch() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i < 128; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.completedBackedgeNeedsCheck());
        assertFalse(q.shouldCooperateAtCheck());
        assertEquals(3, clock.reads.get());
        assertFalse(q.shouldCooperateAtCheck(true));
        assertEquals(3, clock.reads.get(),
                "replayed poll ticket must not sample time");
        q.beginDispatch();
        assertFalse(q.shouldCooperateAtCheck(true),
                "new dispatch must not inherit stale poll authorization");
        assertEquals(4, clock.reads.get());
        for (int i = 1; i <= 128; i++) {
            assertEquals(i == 128, q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.shouldCooperateAtCheck(true));
        assertFalse(q.shouldCooperateAtCheck(true));
        assertEquals(4, clock.reads.get(),
                "watchdog still does not read clock at an authorized boundary");
    }

    @Test
    void watchdogRequestIsOnlyActedOnAtCompletedBackedgeTicket() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i <= 127; i++) {
            assertFalse(q.completedBackedgeNeedsCheck());
            assertFalse(q.shouldCooperateAtCheck(true));
        }
        assertEquals(2, clock.reads.get());
        assertTrue(q.completedBackedgeNeedsCheck());
        assertTrue(q.shouldCooperateAtCheck(true));
        assertFalse(q.shouldCooperateAtCheck(true));
        assertEquals(2, clock.reads.get());
    }

    @Test
    void guardHeldReductionDebtSurvivesPhysicalRedispatchUntilRecordedCooperate() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int n = 1; n <= 512; n++) {
            assertEquals(n % 128 == 0, q.completedBackedgeNeedsCheck());
            // Simulate a verified no-yield scope: no cooperation while held.
        }
        assertTrue(q.pendingHandoffOnNoYieldExit(),
                "512 committed backedges must retain a cold handoff request");
        q.beginDispatch();
        assertTrue(q.pendingHandoffOnNoYieldExit(),
                "a different physical carrier must not forgive pending debt");
        for (int i = 1; i <= 128; i++) {
            assertEquals(i == 128, q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.shouldCooperateAtCheck(), "pending debt wins with fresh clock");
        assertEquals(3, clock.reads.get(), "debt check must not read the clock");
        q.recordedCooperate();
        assertFalse(q.pendingHandoffOnNoYieldExit(),
                "only a genuine cooperation acknowledges pending debt");
        q.beginDispatch();
        assertFalse(q.pendingHandoffOnNoYieldExit(),
                "acknowledged dispatch must not relatch the old 512 cap");
        for (int i = 1; i <= 128; i++) q.completedBackedgeNeedsCheck();
        assertFalse(q.shouldCooperateAtCheck(),
                "fresh dispatch has no pending debt or elapsed time");
    }

    @Test
    void pendingTimeAndWatchdogRequestsRemainTaskOwnedAcrossAwait() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        clock.nanos.set(2_000_001L);
        for (int i = 1; i <= 128; i++) {
            assertEquals(i == 128, q.completedBackedgeNeedsCheck());
        }
        assertTrue(q.shouldCooperateAtCheck());
        assertTrue(q.pendingHandoffOnNoYieldExit(),
                "unserved time-budget debt cannot disappear in an await");
        q.beginDispatch();
        for (int i = 1; i <= 128; i++) {
            assertEquals(i == 128, q.completedBackedgeNeedsCheck());
        }
        int before = clock.reads.get();
        assertTrue(q.shouldCooperateAtCheck(),
                "the new physical dispatch cannot erase a logical request");
        assertEquals(before, clock.reads.get(),
                "an existing handoff request requires no clock sample");
        q.recordedCooperate();
        assertFalse(q.pendingHandoffOnNoYieldExit());
    }

    @Test
    void millionCommittedBackedgesKeepSlowPathCold() {
        Clock clock = new Clock();
        SourceCooperationQuantum q = new SourceCooperationQuantum(clock::read);
        q.beginDispatch();
        for (int i = 1; i <= 1_048_576; i++) {
            assertEquals(i % 128 == 0, q.completedBackedgeNeedsCheck());
        }
        assertEquals(2, clock.reads.get(),
                "every committed backedge is cheap, including after 512");
        assertEquals(1_048_576L, q.completedBackedgesThisDispatch());
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
