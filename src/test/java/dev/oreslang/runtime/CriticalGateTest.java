package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalGateTest {
    @Test void fifoAdmissionTracksLogicalTicketsRatherThanThreads() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.newTicket();
        CriticalGate.Ticket second = gate.newTicket();
        CriticalGate.Ticket third = gate.newTicket();
        assertTrue(first.enterAsync().isDone());
        assertFalse(second.enterAsync().isDone());
        assertFalse(third.enterAsync().isDone());
        assertEquals(2, gate.queuedCount());
        Thread releaser = new Thread(first::close, "different-carrier");
        releaser.start(); releaser.join();
        assertFalse(first.isHeld());
        assertTrue(second.isHeld());
        assertFalse(third.isHeld());
        second.close();
        assertTrue(third.isHeld());
        third.close();
        assertFalse(gate.held());
    }

    @Test void cancellationAndFailureReleaseOnlyTheirOwnTicket() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.newTicket();
        CriticalGate.Ticket cancelled = gate.newTicket();
        CriticalGate.Ticket last = gate.newTicket();
        first.enterAsync(); cancelled.enterAsync(); last.enterAsync();
        cancelled.close();
        assertEquals(1, gate.queuedCount());
        first.close();
        assertTrue(last.isHeld());
        last.close();
    }

    @Test void settledTaskCompletionReleasesWithoutReEnteringGuestCode() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        owner.enterAsync();
        owner.releaseOnCompletion(OresFuture.completed(42));
        assertFalse(gate.held());
        assertThrows(IllegalStateException.class, owner::enterAsync);
    }


    @Test void cancellingPendingAdmissionRetractsQueueEntry() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        var cancelled = gate.newTicket();
        var survivor = gate.newTicket();
        owner.enterAsync();
        OresFuture<Void> pending = cancelled.enterAsync();
        survivor.enterAsync();
        assertEquals(2, gate.queuedCount());
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(1, gate.queuedCount());
        owner.close();
        assertFalse(cancelled.isHeld(), "cancelled ticket must never execute");
        assertTrue(survivor.isHeld());
        survivor.close();
        assertFalse(gate.held());
    }

    @Test void hostSynchronousWaiterIsWokenByDirectHandoff() throws Exception {
        CriticalGate gate = new CriticalGate();
        var first = gate.newTicket();
        first.enterAsync();
        var second = gate.newTicket();
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread host = new Thread(() -> {
            try {
                second.enterSync();
                assertTrue(second.isHeld());
                second.close();
            } catch (Throwable failed) {
                error.set(failed);
            } finally {
                completed.countDown();
            }
        }, "host-critical-contender");
        host.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (gate.queuedCount() == 0 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(1, gate.queuedCount(), "host did not enter waiting queue");
        first.close();
        assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "handoff did not wake the waiting host thread");
        host.join();
        assertNull(error.get());
        assertFalse(gate.held());
    }

    @Test void cancellationRacingHandoffCannotResurrectOrLeakTicket() {
        for (int n = 0; n < 250; n++) {
            CriticalGate gate = new CriticalGate();
            var owner = gate.newTicket();
            var candidate = gate.newTicket();
            var following = gate.newTicket();
            owner.enterAsync();
            OresFuture<Void> pending = candidate.enterAsync();
            following.enterAsync();
            // Either cancellation wins or admission wins; never both.
            Thread canceller = new Thread(() -> pending.cancel(false));
            canceller.start();
            owner.close();
            try { canceller.join(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("unexpected test interruption", e);
            }
            if (pending.isCancelled()) assertFalse(candidate.isHeld());
            if (candidate.isHeld()) candidate.close();
            if (following.isHeld()) following.close();
            assertFalse(gate.held(), "orphaned critical owner on iteration " + n);
            assertEquals(0, gate.queuedCount(), "orphaned waiter on iteration " + n);
        }
    }

    @Test void gatesAreIndependentAndDoNotShareAStaticProcessLock() {
        CriticalGate a = new CriticalGate(), b = new CriticalGate();
        var first = a.newTicket();
        var second = b.newTicket();
        assertTrue(first.enterAsync().isDone());
        assertTrue(second.enterAsync().isDone());
        first.close(); second.close();
    }

    @Test void recursiveSyncEntryFailsInsteadOfDeadlockingHostThread() {
        CriticalGate gate = new CriticalGate();
        var first = gate.newTicket();
        first.enterSync();
        var nested = gate.newTicket();
        var failure = assertThrows(IllegalStateException.class, nested::enterSync);
        assertTrue(failure.getMessage().contains("recursive"));
        assertEquals(0, gate.queuedCount());
        first.close();
    }

    @Test void contendedSynchronousAttemptOnSchedulerCarrierFailsClosed() throws Exception {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        owner.enterAsync();
        var next = gate.newTicket();
        AtomicBoolean failed = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> result = scheduler.start(resume -> {
                try { next.enterSync(); }
                catch (IllegalStateException expected) { failed.set(true); }
                return OresScheduler.done(null);
            });
            result.join();
        }
        assertTrue(failed.get());
        assertEquals(0, gate.queuedCount());
        owner.close();
    }

    @Test void admissionTimeoutRetractsQueuedTicketWithoutStealingLaterHandoff() throws Exception {
        CriticalGate gate = new CriticalGate();
        try (var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            var owner = gate.newTicket();
            var expired = gate.newTicket();
            var survivor = gate.newTicket();
            assertTrue(owner.enterAsync().isDone());
            var timed = expired.enterAsyncWithin(java.time.Duration.ofMillis(25), timer);
            var waiting = survivor.enterAsync();
            assertEquals(2, gate.queuedCount());
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> timed.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failed.getCause());
            assertEquals(1, gate.queuedCount());
            assertFalse(expired.isHeld());
            owner.close();
            assertTrue(waiting.isDone());
            assertTrue(survivor.isHeld());
            survivor.close();
            assertFalse(gate.held());
        }
    }

    @Test void zeroBudgetFastPathAdmitsImmediatelyButDoesNotLeaveAQueuedWaiter() throws Exception {
        CriticalGate gate = new CriticalGate();
        try (var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            var first = gate.newTicket();
            var zero = first.enterAsyncWithin(java.time.Duration.ZERO, timer);
            assertTrue(zero.isDone());
            assertTrue(first.isHeld());
            var second = gate.newTicket();
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> second.enterAsyncWithin(java.time.Duration.ZERO, timer)
                            .get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failed.getCause());
            assertEquals(0, gate.queuedCount());
            assertFalse(second.isHeld());
            first.close();
            assertFalse(gate.held());
        }
    }

    @Test void timerFailureFailsClosedAndCannotLeaveAnUnboundedWaiter() throws Exception {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        owner.enterAsync();
        var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        timer.shutdownNow();
        var pending = gate.newTicket();
        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pending.enterAsyncWithin(java.time.Duration.ofSeconds(1), timer)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().contains("could not be scheduled"));
        assertEquals(0, gate.queuedCount());
        assertFalse(pending.isHeld());
        owner.close();
        assertFalse(gate.held());
    }

    @Test void admissionWinningBeforeTimerDoesNotLoseItsPermit() throws Exception {
        CriticalGate gate = new CriticalGate();
        try (var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            var owner = gate.newTicket();
            owner.enterAsync();
            var candidate = gate.newTicket();
            var admission = candidate.enterAsyncWithin(java.time.Duration.ofMillis(75), timer);
            owner.close();
            admission.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(candidate.isHeld());
            Thread.sleep(125);
            assertTrue(candidate.isHeld(), "deadline must not revoke an admitted owner");
            candidate.close();
            assertFalse(gate.held());
        }
    }


    @Test void timedHostWaitFailsWithoutParkingAnActorCarrier() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        owner.enterAsync();
        var timed = gate.newTicket();
        var failed = assertThrows(IllegalStateException.class,
                () -> timed.enterSyncWithin(java.time.Duration.ofMillis(20)));
        assertInstanceOf(java.util.concurrent.TimeoutException.class, failed.getCause());
        assertEquals(0, gate.queuedCount());
        owner.close();
    }

    @Test void expiredTicketIsRejectedAtHandoffEvenWhenTimerThreadIsBusy() throws Exception {
        CriticalGate gate = new CriticalGate();
        try (var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            var blockerStarted = new java.util.concurrent.CountDownLatch(1);
            var unblockTimer = new java.util.concurrent.CountDownLatch(1);
            timer.execute(() -> {
                blockerStarted.countDown();
                try {
                    unblockTimer.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blockerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                var owner = gate.newTicket();
                owner.enterAsync();
                var stale = gate.newTicket();
                var staleWait = stale.enterAsyncWithin(java.time.Duration.ofMillis(20), timer);
                var next = gate.newTicket();
                next.enterAsync();
                Thread.sleep(50);
                owner.close();
                var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> staleWait.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.TimeoutException.class, rejected.getCause());
                assertFalse(stale.isHeld());
                assertTrue(next.isHeld());
                next.close();
                assertEquals(0, gate.queuedCount());
                assertFalse(gate.held());
            } finally {
                unblockTimer.countDown();
            }
        }
    }

}
