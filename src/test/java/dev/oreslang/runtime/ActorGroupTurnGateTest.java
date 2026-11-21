package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorGroupTurnGateTest {
    @Test
    void boundedParallelTurnsAndFifoWakeupNeverPark() {
        try (var gate = new ActorGroupTurnGate(2, 3)) {
            List<ActorGroupTurnGate.Ticket> admitted = new ArrayList<>();
            List<Integer> order = new ArrayList<>();
            List<ActorGroupTurnGate.Ticket> returned = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int value = i;
                returned.add(gate.submit(permit -> {
                    admitted.add(permit);
                    order.add(value);
                }, () -> fail("unexpected cancellation")));
            }
            assertEquals(List.of(0, 1), order);
            assertEquals(2, gate.activeTurns());
            assertEquals(3, gate.queuedTurns());
            assertThrows(RejectedExecutionException.class,
                    () -> gate.submit(ignored -> fail("over-admitted"), () -> { }));
            assertTrue(admitted.get(0).release());
            assertEquals(List.of(0, 1, 2), order);
            assertEquals(2, gate.activeTurns());
            assertEquals(2, gate.queuedTurns());
            assertTrue(admitted.get(1).release());
            assertTrue(admitted.get(2).release());
            assertEquals(List.of(0, 1, 2, 3, 4), order);
            assertEquals(2, gate.activeTurns());
            assertEquals(0, gate.queuedTurns());
            assertTrue(returned.get(3).release());
            assertTrue(returned.get(4).release());
            assertFalse(returned.get(4).release());
            assertEquals(0, gate.activeTurns());
        }
    }

    @Test
    void cancelledQueuedWorkDoesNotRunOrConsumeASecondPermit() {
        try (var gate = new ActorGroupTurnGate(1, 2)) {
            List<Integer> run = new ArrayList<>();
            AtomicInteger cancelled = new AtomicInteger();
            var first = gate.submit(ignored -> run.add(1), () -> fail());
            var second = gate.submit(ignored -> run.add(2), cancelled::incrementAndGet);
            var third = gate.submit(ignored -> run.add(3), () -> fail());
            assertTrue(second.cancelWaiting());
            assertFalse(second.cancelWaiting());
            assertEquals(1, cancelled.get());
            assertEquals(1, gate.queuedTurns());
            assertTrue(first.release());
            assertEquals(List.of(1, 3), run);
            assertEquals(1, gate.activeTurns());
            assertTrue(third.release());
            assertEquals(0, gate.activeTurns());
        }
    }

    @Test
    void closeWithdrawsWaitingAndAllowsOnlyExistingPhysicalTurnsToExit() {
        var gate = new ActorGroupTurnGate(1, 4);
        List<Integer> admitted = new ArrayList<>();
        AtomicInteger cancelled = new AtomicInteger();
        var first = gate.submit(ignored -> admitted.add(1), () -> fail());
        var second = gate.submit(ignored -> admitted.add(2), cancelled::incrementAndGet);
        var third = gate.submit(ignored -> admitted.add(3), cancelled::incrementAndGet);
        gate.close();
        gate.close();
        assertEquals(2, cancelled.get());
        assertTrue(gate.isClosed());
        assertEquals(0, gate.queuedTurns());
        assertFalse(second.cancelWaiting());
        assertFalse(third.release());
        assertThrows(RejectedExecutionException.class,
                () -> gate.submit(ignored -> fail(), () -> fail()));
        assertTrue(first.release());
        assertEquals(0, gate.activeTurns());
        assertEquals(List.of(1), admitted);
    }

    @Test
    void failedImmediateDispatcherSubmissionReleasesPermit() {
        try (var gate = new ActorGroupTurnGate(1, 1)) {
            assertThrows(RejectedExecutionException.class,
                    () -> gate.submit(ignored -> {
                        throw new RejectedExecutionException("dispatcher shutdown");
                    }, () -> fail()));
            assertEquals(0, gate.activeTurns());
            var good = gate.submit(ignored -> { }, () -> fail());
            assertEquals(1, gate.activeTurns());
            assertTrue(good.release());
        }
    }

    @Test
    void concurrentDispatcherSubmissionsRespectGlobalTurnBound() throws Exception {
        final int tasks = 500;
        try (var gate = new ActorGroupTurnGate(3, tasks);
             var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            CountDownLatch finished = new CountDownLatch(tasks);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            for (int i = 0; i < tasks; i++) {
                gate.submit(permit -> executor.execute(() -> {
                    int currentlyRunning = active.incrementAndGet();
                    peak.accumulateAndGet(currentlyRunning, Math::max);
                    try {
                        if (currentlyRunning > 3) {
                            error.compareAndSet(null, new AssertionError(
                                    "group exceeded three physical turns"));
                        }
                        Thread.onSpinWait();
                    } finally {
                        active.decrementAndGet();
                        try {
                            permit.release();
                        } finally {
                            finished.countDown();
                        }
                    }
                }), () -> error.compareAndSet(null,
                        new AssertionError("unexpected queued turn cancellation")));
            }
            assertTrue(finished.await(15, TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue(peak.get() >= 1 && peak.get() <= 3);
            assertEquals(0, gate.activeTurns());
            assertEquals(0, gate.queuedTurns());
        }
    }

    @Test
    void rejectedQueuedDispatchCancelsThousandsWithoutRecursivePermitHandoff() {
        final int queuedAfterReject = 4_096;
        try (var gate = new ActorGroupTurnGate(1, queuedAfterReject + 1)) {
            AtomicInteger cancelled = new AtomicInteger();
            var first = gate.submit(permit -> { }, () -> fail("active was cancelled"));
            gate.submit(permit -> {
                throw new RejectedExecutionException("existing carrier pool closed");
            }, () -> fail("granted ticket was never queued-cancelled"));
            for (int i = 0; i < queuedAfterReject; i++) {
                gate.submit(permit -> fail("closed gate dispatched queued ticket"),
                        cancelled::incrementAndGet);
            }
            assertEquals(queuedAfterReject + 1, gate.queuedTurns());
            assertThrows(RejectedExecutionException.class, first::release);
            assertTrue(gate.isClosed());
            assertEquals(queuedAfterReject, cancelled.get());
            assertEquals(0, gate.queuedTurns());
            assertEquals(0, gate.activeTurns());
            assertFalse(first.release(), "physical lease can be released only once");
        }
    }

    @Test
    void brokenCancellationObserverCannotPreventOtherTicketsFromBeingReclaimed() {
        var gate = new ActorGroupTurnGate(1, 4);
        var inFlight = gate.submit(permit -> { }, () -> fail());
        gate.submit(permit -> fail(), () -> {
            throw new IllegalStateException("synthetic observer failure");
        });
        AtomicInteger otherCancelled = new AtomicInteger();
        gate.submit(permit -> fail(), otherCancelled::incrementAndGet);
        assertThrows(IllegalStateException.class, gate::close);
        assertEquals(1, otherCancelled.get(),
                "a failing observer cannot strand the next queued ticket");
        assertEquals(0, gate.queuedTurns());
        assertTrue(inFlight.release());
        assertEquals(0, gate.activeTurns());
        gate.close();
    }

    @Test
    void cancellationRacingReleaseIsSingleWinnerWithNoDoubleDispatch() throws Exception {
        final int iterations = 160;
        for (int i = 0; i < iterations; i++) {
            try (var gate = new ActorGroupTurnGate(1, 1)) {
                var running = gate.submit(ignored -> { }, () -> fail());
                AtomicInteger started = new AtomicInteger();
                AtomicInteger cancelled = new AtomicInteger();
                AtomicReference<ActorGroupTurnGate.Ticket> resumed = new AtomicReference<>();
                var waiting = gate.submit(permit -> {
                    resumed.set(permit);
                    started.incrementAndGet();
                }, cancelled::incrementAndGet);
                CountDownLatch start = new CountDownLatch(1);
                Thread canceller = new Thread(() -> {
                    try {
                        start.await();
                        waiting.cancelWaiting();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                canceller.start();
                start.countDown();
                assertTrue(running.release());
                canceller.join(2_000);
                assertFalse(canceller.isAlive());
                assertEquals(1, started.get() + cancelled.get());
                if (resumed.get() != null) assertTrue(resumed.get().release());
                assertEquals(0, gate.activeTurns());
                assertEquals(0, gate.queuedTurns());
            }
        }
    }
}
