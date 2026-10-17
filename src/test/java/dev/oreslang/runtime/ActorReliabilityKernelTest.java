package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

final class ActorReliabilityKernelTest {
    private static ActorReliabilityKernel.ActorKey actor(long incarnation) {
        return new ActorReliabilityKernel.ActorKey(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                incarnation);
    }

    @Test
    void eventHistoryIsBoundedButCountersRetainTotal() {
        var clock = new AtomicLong(1);
        var kernel = new ActorReliabilityKernel(8, clock::get);
        for (int i = 0; i < 100; i++) {
            kernel.record(
                    ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                    actor(1),
                    ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                    i);
            clock.incrementAndGet();
        }
        var snapshot = kernel.snapshot();
        assertEquals(8, snapshot.recentEvents().size());
        assertEquals(92, snapshot.overwrittenEvents());
        assertEquals(100L, snapshot.counters()
                .get(ActorReliabilityKernel.EventKind.ACTOR_FAILED));
        assertEquals(92, snapshot.recentEvents().getFirst().sequence());
        assertEquals(99, snapshot.recentEvents().getLast().sequence());
    }

    @Test
    void restartStormTripsBudgetAndCannotBusyLoop() {
        var now = new AtomicLong(0);
        var budget = new ActorReliabilityKernel.RestartBudget(
                3, 100, 50, now::get);
        assertTrue(budget.tryAcquire().admitted());
        now.set(10); assertTrue(budget.tryAcquire().admitted());
        now.set(20); assertTrue(budget.tryAcquire().admitted());
        now.set(30);
        var denied = budget.tryAcquire();
        assertFalse(denied.admitted());
        assertEquals(50, denied.retryAfterNanos());
        now.set(79);
        assertFalse(budget.tryAcquire().admitted());
        now.set(120);
        assertTrue(budget.tryAcquire().admitted());
    }

    @Test
    void restartSafetyFailsClosedOnRegressingClock() {
        var now = new AtomicLong(10);
        var budget = new ActorReliabilityKernel.RestartBudget(
                2, 100, 50, now::get);
        assertTrue(budget.tryAcquire().admitted());
        now.set(9);
        assertThrows(IllegalStateException.class, budget::tryAcquire);
    }

    @Test
    void overloadUsesHysteresisAndNeverShedsControl() {
        var gate = new ActorReliabilityKernel.OverloadGate(100, 80, 40);
        assertEquals(ActorReliabilityKernel.OverloadGate.Admission.ACCEPT,
                gate.admit(79, false));
        assertEquals(ActorReliabilityKernel.OverloadGate.Admission.SHED,
                gate.admit(80, false));
        assertEquals(ActorReliabilityKernel.OverloadGate.Admission.SHED,
                gate.admit(60, false));
        assertEquals(ActorReliabilityKernel.OverloadGate.Admission.CONTROL_ACCEPT,
                gate.admit(100, true));
        assertEquals(ActorReliabilityKernel.OverloadGate.Admission.ACCEPT,
                gate.admit(40, false));
    }

    @Test
    void backoffIsReplayableBoundedAndDesynchronizable() {
        var policy = new ActorReliabilityKernel.BackoffPolicy(10, 1_000);
        boolean observedDifferentSalt = false;
        long previousUpperBound = 10;
        for (int attempt = 0; attempt < 30; attempt++) {
            long a = policy.delayNanos(attempt, 42);
            long replay = policy.delayNanos(attempt, 42);
            long other = policy.delayNanos(attempt, 43);
            assertEquals(a, replay);
            assertTrue(a >= 1 && a <= 1_000);
            if (a != other) observedDifferentSalt = true;
            previousUpperBound = Math.min(1_000, previousUpperBound * 2);
            assertTrue(a <= previousUpperBound);
        }
        assertTrue(observedDifferentSalt);
    }

    @Test
    void telemetryClockRegressionNeverAbortsActorAndIsVisible() {
        var now = new AtomicLong(10);
        var kernel = new ActorReliabilityKernel(4, now::get);
        kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1),
                ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                0);
        now.set(9);
        assertDoesNotThrow(() -> kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1),
                ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                0));
        var snapshot = kernel.snapshot();
        assertEquals(1, snapshot.clockRegressions());
        assertEquals(2L, snapshot.counters()
                .get(ActorReliabilityKernel.EventKind.ACTOR_FAILED));
        assertEquals(0, snapshot.recentEvents().getFirst().monotonicNanos());
        assertEquals(0, snapshot.recentEvents().getLast().monotonicNanos());
        now.set(11);
        kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1),
                ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                0);
        assertEquals(1, kernel.snapshot().recentEvents().getLast().monotonicNanos());
    }

    @Test
    void rawNanoTimeMayBeNegativeAndSignedWrapMustRemainPredictable() {
        var now = new AtomicLong(-5L);
        var kernel = new ActorReliabilityKernel(4, now::get);
        var budget = new ActorReliabilityKernel.RestartBudget(3, 100, 50, now::get);
        kernel.record(ActorReliabilityKernel.EventKind.RESTART_ADMITTED,
                actor(2), ActorReliabilityKernel.FaultClass.NONE, 1);
        assertTrue(budget.tryAcquire().admitted());
        assertEquals(0, kernel.snapshot().recentEvents().getLast().monotonicNanos());
        now.set(-3L);
        kernel.record(ActorReliabilityKernel.EventKind.RESTART_ADMITTED,
                actor(2), ActorReliabilityKernel.FaultClass.NONE, 2);
        assertTrue(budget.tryAcquire().admitted());
        assertEquals(2, kernel.snapshot().recentEvents().getLast().monotonicNanos());

        // System.nanoTime may naturally cross signed Long.MAX_VALUE into
        // Long.MIN_VALUE without regressing physical monotonic time.
        var crossing = new AtomicLong(Long.MAX_VALUE - 1L);
        var wrappedEvents = new ActorReliabilityKernel(4, crossing::get);
        var wrappedBudget = new ActorReliabilityKernel.RestartBudget(
                3, 100, 50, crossing::get);
        wrappedEvents.record(ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1), ActorReliabilityKernel.FaultClass.WATCHDOG, 0);
        assertTrue(wrappedBudget.tryAcquire().admitted());
        crossing.set(Long.MIN_VALUE);
        wrappedEvents.record(ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1), ActorReliabilityKernel.FaultClass.WATCHDOG, 0);
        assertTrue(wrappedBudget.tryAcquire().admitted());
        assertEquals(2, wrappedEvents.snapshot().recentEvents().getLast().monotonicNanos());
        assertEquals(0, wrappedEvents.snapshot().clockRegressions());
    }

    @Test
    void healthyResetRequiresNoRecentCrashesAndNoActiveQuarantine() {
        var clock = new AtomicLong(-100);
        var budget = new ActorReliabilityKernel.RestartBudget(2, 100, 80, clock::get);
        assertTrue(budget.tryAcquire().admitted());
        clock.set(-90);
        assertTrue(budget.tryAcquire().admitted());
        clock.set(-80);
        assertFalse(budget.tryAcquire().admitted());
        // A forged "healthy" callback cannot clear the actual crash budget.
        assertThrows(IllegalStateException.class, budget::resetAfterHealthyPeriod);
        clock.set(-1);
        assertFalse(budget.tryAcquire().admitted());
        assertThrows(IllegalStateException.class, budget::resetAfterHealthyPeriod);
        clock.set(0);
        // Quarantine ended, but last admitted restart is still recent.
        assertThrows(IllegalStateException.class, budget::resetAfterHealthyPeriod);
        clock.set(11);
        assertDoesNotThrow(budget::resetAfterHealthyPeriod);
        assertTrue(budget.tryAcquire().admitted());
    }

    @Test
    @Timeout(25)
    void concurrentTelemetryIsTotallyOrderedAndBounded() throws Exception {
        var tick = new AtomicLong(-123_000L);
        // Sample is taken under the serialization lock. If it were sampled
        // before lock acquisition, scheduling could reorder timestamps.
        var kernel = new ActorReliabilityKernel(64, tick::getAndIncrement);
        int threads = 8;
        int perThread = 750;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var fault = new AtomicReference<Throwable>();
        List<Thread> carriers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        kernel.record(ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                                actor(7), ActorReliabilityKernel.FaultClass.TRANSPORT, i);
                    }
                } catch (Throwable error) {
                    fault.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "actor-telemetry-test-" + t);
            carriers.add(worker);
            worker.start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertNull(fault.get(), () -> String.valueOf(fault.get()));
        carriers.forEach(worker -> {
            try { worker.join(1_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var snapshot = kernel.snapshot();
        long total = (long) threads * perThread;
        assertEquals(total, snapshot.nextSequence());
        assertEquals(total - 64, snapshot.overwrittenEvents());
        assertEquals(64, snapshot.recentEvents().size());
        assertEquals(total, snapshot.counters()
                .get(ActorReliabilityKernel.EventKind.ACTOR_FAILED));
        assertEquals(0, snapshot.clockRegressions());
        assertEquals(total - 1, snapshot.recentEvents().getLast().monotonicNanos());
        for (int i = 1; i < snapshot.recentEvents().size(); i++) {
            assertEquals(snapshot.recentEvents().get(i - 1).sequence() + 1,
                    snapshot.recentEvents().get(i).sequence());
            assertTrue(snapshot.recentEvents().get(i).monotonicNanos()
                    >= snapshot.recentEvents().get(i - 1).monotonicNanos());
        }
    }

    @Test
    void saturatedDiagnosticsNeverForgeASecondSequenceNumber() throws Exception {
        var kernel = new ActorReliabilityKernel(4, () -> -33L);
        // Drive the finite-counter terminal state directly; this would need
        // more than 2^63 event publications to reach in production.
        var next = ActorReliabilityKernel.class.getDeclaredField("nextSequence");
        next.setAccessible(true);
        next.setLong(kernel, Long.MAX_VALUE);
        var counters = ActorReliabilityKernel.class.getDeclaredField("counters");
        counters.setAccessible(true);
        long[] values = (long[]) counters.get(kernel);
        values[ActorReliabilityKernel.EventKind.ACTOR_FAILED.ordinal()] = Long.MAX_VALUE;
        assertDoesNotThrow(() -> kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(7), ActorReliabilityKernel.FaultClass.CRASH_LOOP, 0));
        assertDoesNotThrow(() -> kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(7), ActorReliabilityKernel.FaultClass.CRASH_LOOP, 0));
        var snapshot = kernel.snapshot();
        assertEquals(Long.MAX_VALUE, snapshot.nextSequence());
        assertEquals(Long.MAX_VALUE, snapshot.counters()
                .get(ActorReliabilityKernel.EventKind.ACTOR_FAILED));
        assertEquals(2, snapshot.saturatedSequenceDrops());
        assertTrue(snapshot.recentEvents().isEmpty(),
                "never issue two records with the same terminal sequence ID");
    }

    @Test
    void invalidBoundsFailBeforeRuntimeUse() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorReliabilityKernel(0, System::nanoTime));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorReliabilityKernel.RestartBudget(
                        0, 1, 1, System::nanoTime));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorReliabilityKernel.OverloadGate(10, 5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorReliabilityKernel.BackoffPolicy(10, 9));
    }
}
