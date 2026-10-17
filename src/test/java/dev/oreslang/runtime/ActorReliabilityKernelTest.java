package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
    void telemetryRejectsClockRegressionRatherThanInventOrdering() {
        var now = new AtomicLong(10);
        var kernel = new ActorReliabilityKernel(4, now::get);
        kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1),
                ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                0);
        now.set(9);
        assertThrows(IllegalStateException.class, () -> kernel.record(
                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                actor(1),
                ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                0));
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
