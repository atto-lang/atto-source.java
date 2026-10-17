package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class SupervisorRestartWindowTest {

    @Test
    void rollingBudgetDeniesStormAndRecoversExactlyAtWindowBoundary() {
        var gate = new SupervisorRestartWindow(3, 100);
        assertTrue(gate.tryAcquire(0).allowed());
        assertTrue(gate.tryAcquire(1).allowed());
        assertTrue(gate.tryAcquire(2).allowed());

        var denied = gate.tryAcquire(3);
        assertFalse(denied.allowed());
        assertEquals(3, denied.restartsInWindow());
        assertEquals(97, denied.retryAfterNanos());
        assertEquals(1, gate.suppressedCount());

        assertFalse(gate.tryAcquire(99).allowed());
        assertEquals(1, gate.tryAcquire(99).retryAfterNanos());
        assertEquals(3, gate.suppressedCount());

        assertEquals(2, gate.advance(100));
        var admitted = gate.tryAcquire(100);
        assertTrue(admitted.allowed());
        assertEquals(3, admitted.restartsInWindow());
        assertEquals(0, admitted.retryAfterNanos());
    }

    @Test
    void deniedAttemptsNeverConsumeRestartCapacity() {
        var gate = new SupervisorRestartWindow(1, 50);
        assertTrue(gate.tryAcquire(10).allowed());
        for (int i = 0; i < 100; i++) {
            assertFalse(gate.tryAcquire(10).allowed());
        }
        assertEquals(1, gate.currentAdmissions());
        assertEquals(100, gate.suppressedCount());
        assertEquals(0, gate.advance(60));
        assertTrue(gate.tryAcquire(60).allowed());
    }

    @Test
    void clockRollbackAndInvalidBoundsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupervisorRestartWindow(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SupervisorRestartWindow(4097, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SupervisorRestartWindow(1, 0));

        var gate = new SupervisorRestartWindow(2, 100);
        gate.tryAcquire(50);
        assertThrows(IllegalArgumentException.class, () -> gate.tryAcquire(49));
        assertThrows(IllegalArgumentException.class, () -> gate.advance(-1));
    }

    @Test
    void concurrentAttemptsCannotOversubscribeBudget() throws Exception {
        var gate = new SupervisorRestartWindow(7, 1_000_000);
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        var decisions = new ArrayList<java.util.concurrent.Future<?>>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 64; i++) {
                decisions.add(pool.submit(() -> {
                    start.await();
                    if (gate.tryAcquireFast(77)) allowed.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (var result : decisions) result.get(5, TimeUnit.SECONDS);
        }
        assertEquals(7, allowed.get());
        assertEquals(7, gate.currentAdmissions());
        assertEquals(57, gate.suppressedCount());
    }

    @Test
    void allocationFreeFastPathSharesTheSameDecisionState() {
        var gate = new SupervisorRestartWindow(1, 50);
        assertTrue(gate.tryAcquireFast(10));
        assertEquals(1L, gate.decisionSequence());
        assertEquals(0L, gate.lastRetryAfterNanos());

        assertFalse(gate.tryAcquireFast(11));
        assertEquals(2L, gate.decisionSequence());
        assertEquals(49L, gate.lastRetryAfterNanos());
        assertEquals(1L, gate.suppressedCount());

        assertEquals(0, gate.advance(60));
        var detailed = gate.tryAcquire(60);
        assertTrue(detailed.allowed());
        assertEquals(gate.decisionSequence(), detailed.decisionSequence());
        assertEquals(gate.lastRetryAfterNanos(), detailed.retryAfterNanos());
    }

    @Test
    void repeatedRestartSuppressionIsSampledButExactCountIsRetained() {
        CoreLog.configure(CoreLog.Level.OFF);
        try {
            CoreLog.configure(CoreLog.Level.WARN);
            var gate = new SupervisorRestartWindow(1, 1_000, 9191);
            assertTrue(gate.tryAcquireFast(0));
            for (int i = 0; i < 100; i++) {
                assertFalse(gate.tryAcquireFast(1));
            }
            assertEquals(100L, gate.suppressedCount());

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CoreLog.dumpRetained(
                    new PrintStream(bytes, true, StandardCharsets.UTF_8));
            long samples = bytes.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.contains(
                            "\"event\":\"supervisor.restart.suppressed\""))
                    .count();
            assertEquals(7L, samples,
                    "100 denials should log counts 1,2,4,8,16,32,64 only");
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test
    void deterministicBackoffIsBoundedReplayableAndOverflowSafe() {
        assertEquals(10, SupervisorRestartWindow.exponentialBackoffNanos(0, 10, 1_000));
        assertEquals(20, SupervisorRestartWindow.exponentialBackoffNanos(1, 10, 1_000));
        assertEquals(640, SupervisorRestartWindow.exponentialBackoffNanos(6, 10, 1_000));
        assertEquals(1_000, SupervisorRestartWindow.exponentialBackoffNanos(20, 10, 1_000));
        assertEquals(Long.MAX_VALUE,
                SupervisorRestartWindow.exponentialBackoffNanos(
                        63, Long.MAX_VALUE / 2, Long.MAX_VALUE));

        long first = SupervisorRestartWindow.deterministicJitterNanos(12345, 9, 500);
        long second = SupervisorRestartWindow.deterministicJitterNanos(12345, 9, 500);
        assertEquals(first, second);
        assertTrue(first >= 0 && first <= 500);
        assertNotEquals(first,
                SupervisorRestartWindow.deterministicJitterNanos(54321, 9, 500));

        assertEquals(Long.MAX_VALUE,
                SupervisorRestartWindow.restartDelayNanos(
                        1, 1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void suppressionLogContainsOnlyFixedEventAndNumericSupervisorKey() {
        CoreLog.configure(CoreLog.Level.OFF);
        try {
            CoreLog.configure(CoreLog.Level.WARN);
            var gate = new SupervisorRestartWindow(1, 100, 4242);
            assertTrue(gate.tryAcquire(0).allowed());
            assertFalse(gate.tryAcquire(1).allowed());

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CoreLog.dumpRetained(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            String log = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(log.contains("\"event\":\"supervisor.restart.suppressed\""));
            assertTrue(log.contains("\"value\":4242"));
            assertFalse(log.contains("Exception"));
            assertFalse(log.contains("actor"));
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test
    void saturatingAdditionNeverWrapsNegative() {
        assertEquals(12, SupervisorRestartWindow.saturatingAdd(5, 7));
        assertEquals(Long.MAX_VALUE,
                SupervisorRestartWindow.saturatingAdd(Long.MAX_VALUE - 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> SupervisorRestartWindow.saturatingAdd(-1, 0));
    }
    @Test
    void signedNanoTimeOriginWrapAndMaxFailureOrdinalAreDeterministic() {
        var negative = new SupervisorRestartWindow(2, 100);
        assertTrue(negative.tryAcquire(-100).allowed());
        assertTrue(negative.tryAcquire(-50).allowed());
        assertThrows(IllegalArgumentException.class, () -> negative.tryAcquire(-51));

        var crossing = new SupervisorRestartWindow(2, 100);
        assertTrue(crossing.tryAcquire(Long.MAX_VALUE - 5).allowed());
        assertTrue(crossing.tryAcquire(Long.MIN_VALUE + 4).allowed());
        assertEquals(2, crossing.currentAdmissions());

        long first = SupervisorRestartWindow.deterministicJitterNanos(
                77, Integer.MAX_VALUE, 1_000);
        long replay = SupervisorRestartWindow.deterministicJitterNanos(
                77, Integer.MAX_VALUE, 1_000);
        assertEquals(first, replay);
        assertTrue(first >= 0 && first <= 1_000);
    }

    @Test
    void exhaustedDecisionSequenceDoesNotAdvanceClockOrExpireAdmissions() throws Exception {
        var gate = new SupervisorRestartWindow(1, 100);
        assertTrue(gate.tryAcquire(0).allowed());

        var sequence = SupervisorRestartWindow.class.getDeclaredField("decisionSequence");
        sequence.setAccessible(true);
        sequence.setLong(gate, Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> gate.tryAcquire(1_000));
        assertEquals(1, gate.currentAdmissions());

        // A failed attempt at a later timestamp must not commit the clock.
        // Reset the injected exhaustion to verify the original timestamp is valid.
        sequence.setLong(gate, 1L);
        assertFalse(gate.tryAcquire(1).allowed());
        assertEquals(99L, gate.lastRetryAfterNanos());
        assertEquals(1, gate.currentAdmissions());
    }


    @Test
    void elapsedOverflowDoesNotCommitRejectedRawTimestamp() throws Exception {
        var gate = new SupervisorRestartWindow(1, 100);
        assertTrue(gate.tryAcquire(10).allowed());
        var elapsed = SupervisorRestartWindow.class.getDeclaredField("elapsedNanos");
        elapsed.setAccessible(true);
        elapsed.setLong(gate, Long.MAX_VALUE - 2);

        assertThrows(IllegalStateException.class, () -> gate.advance(13));
        // If the rejected sample had advanced lastRawNanos to 13, this
        // previously valid sample at 11 would be mistaken for a rollback.
        assertEquals(1, gate.advance(11));
        assertEquals(Long.MAX_VALUE - 1, elapsed.getLong(gate));
        assertEquals(1, gate.currentAdmissions());
    }


}
