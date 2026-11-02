package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host runtime evidence only. Guest lowering, transitive effect checking,
 * cancellation fencing, and scheduler dispatch remain separate acceptance gates.
 */
final class CriticalGateTest {
    @Test void immediateTryEnterNeverQueues() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.tryEnter().orElseThrow();
        assertTrue(gate.holds(first));
        assertTrue(gate.tryEnter().isEmpty());
        assertEquals(0, gate.waiting());
        assertTrue(gate.release(first).isEmpty());
        CriticalGate.Ticket next = gate.tryEnter().orElseThrow();
        assertNotSame(first, next);
        assertFalse(gate.holds(first));
        assertTrue(gate.holds(next));
        gate.release(next);
    }

    @Test void queuedCriticalAcquisitionIsFifoAndDoesNotExecuteGuestBody() {
        CriticalGate gate = new CriticalGate();
        var first = gate.request();
        var second = gate.request();
        var third = gate.request();
        assertEquals(CriticalGate.Admission.ACQUIRED, first.admission());
        assertEquals(CriticalGate.Admission.QUEUED, second.admission());
        assertEquals(CriticalGate.Admission.QUEUED, third.admission());
        assertEquals(2, gate.waiting());
        assertFalse(gate.holds(second.ticket()));
        assertFalse(gate.holds(third.ticket()));
        assertTrue(gate.tryEnter().isEmpty());
        assertSame(second.ticket(), gate.release(first.ticket()).orElseThrow());
        assertTrue(gate.holds(second.ticket()));
        assertSame(third.ticket(), gate.release(second.ticket()).orElseThrow());
        assertTrue(gate.holds(third.ticket()));
        assertTrue(gate.release(third.ticket()).isEmpty());
        assertEquals(0, gate.waiting());
    }

    @Test void cancellationBeforeHandoffSkipsWaiter() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        var cancelled = gate.request();
        var survivor = gate.request();
        assertTrue(gate.release(cancelled.ticket()).isEmpty());
        assertEquals(1, gate.waiting());
        assertSame(survivor.ticket(), gate.release(owner.ticket()).orElseThrow());
        assertFalse(gate.holds(cancelled.ticket()));
        assertTrue(gate.release(survivor.ticket()).isEmpty());
    }

    @Test void duplicateReleaseCannotRevokeTheNextHolder() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        var next = gate.request();
        assertSame(next.ticket(), gate.release(owner.ticket()).orElseThrow());
        assertTrue(gate.release(owner.ticket()).isEmpty());
        assertTrue(gate.holds(next.ticket()));
        gate.release(next.ticket());
        assertFalse(gate.holds(next.ticket()));
    }

    @Test void foreignScopeDoesNotAcceptTicket() {
        CriticalGate left = new CriticalGate();
        CriticalGate right = new CriticalGate();
        var ticket = left.request().ticket();
        assertFalse(right.holds(ticket));
        assertThrows(IllegalArgumentException.class, () -> right.release(ticket));
        assertTrue(left.holds(ticket));
        left.release(ticket);
    }

    @Test void retiredActorGenerationCannotResumeOrReacquire() {
        CriticalGate old = new CriticalGate();
        var owner = old.request();
        var queued = old.request();
        old.retire();
        assertTrue(old.retired());
        assertEquals(0, old.waiting());
        assertFalse(old.holds(owner.ticket()));
        assertFalse(old.holds(queued.ticket()));
        assertTrue(old.release(owner.ticket()).isEmpty());
        assertTrue(old.release(queued.ticket()).isEmpty());
        assertEquals(Optional.empty(), old.tryEnter());
        assertThrows(IllegalStateException.class, old::request);
        old.retire();

        CriticalGate replacement = new CriticalGate();
        var newTicket = replacement.request().ticket();
        assertFalse(replacement.holds(owner.ticket()));
        assertTrue(replacement.holds(newTicket));
        replacement.release(newTicket);
    }

    @Test void waitingQuotaRejectsOverflowWithoutEvictingOwner() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        CriticalGate.Ticket firstWaiter = null;
        for (int i = 0; i < CriticalGate.MAX_WAITERS; i++) {
            var request = gate.request();
            if (i == 0) firstWaiter = request.ticket();
            assertEquals(CriticalGate.Admission.QUEUED, request.admission());
        }
        assertEquals(CriticalGate.MAX_WAITERS, gate.waiting());
        assertThrows(IllegalStateException.class, gate::request);
        assertTrue(gate.holds(owner.ticket()));
        gate.release(firstWaiter);
        assertEquals(CriticalGate.MAX_WAITERS - 1, gate.waiting());
        assertEquals(CriticalGate.Admission.QUEUED, gate.request().admission());
        gate.retire();
        assertEquals(0, gate.waiting());
    }

    @Test void cancellingWhileOwnerReleasesAlwaysAdmitsNextLiveTicket()
            throws InterruptedException {
        for (int iteration = 0; iteration < 128; iteration++) {
            CriticalGate gate = new CriticalGate();
            var root = gate.request();
            var cancelled = gate.request();
            var surviving = gate.request();
            Thread releaser = Thread.ofPlatform().start(
                    () -> gate.release(root.ticket()));
            Thread canceller = Thread.ofPlatform().start(
                    () -> gate.release(cancelled.ticket()));
            releaser.join();
            canceller.join();
            assertFalse(gate.holds(cancelled.ticket()), "cancelled ticket held gate");
            assertTrue(gate.holds(surviving.ticket()), "live successor lost during race");
            assertTrue(gate.release(surviving.ticket()).isEmpty());
        }
    }

    @Test void schedulerAwaitableDoesNotBlockCarrierOrSkipFifo() {
        CriticalGate gate = new CriticalGate();
        var first = gate.requestAwaitable();
        var second = gate.requestAwaitable();
        var third = gate.requestAwaitable();
        assertTrue(first.isDone(), "uncontended admission is immediately ready");
        assertFalse(second.isDone(), "contended admission must be awaitable");
        assertFalse(third.isDone());
        assertEquals(2, gate.waiting());
        var a = first.join();
        assertTrue(gate.holds(a));
        assertFalse(second.isDone());
        gate.release(a);
        assertTrue(second.isDone());
        var b = second.join();
        assertTrue(gate.holds(b));
        assertFalse(third.isDone());
        gate.release(b);
        assertTrue(third.isDone());
        var c = third.join();
        assertTrue(gate.holds(c));
        gate.release(c);
        assertEquals(0, gate.waiting());
    }

    @Test void awaitingCancellationRetractsTicketWithoutLosingSuccessor() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.requestAwaitable().join();
        var cancelled = gate.requestAwaitable();
        var survivor = gate.requestAwaitable();
        assertTrue(cancelled.cancel(false));
        assertTrue(cancelled.isCancelled());
        assertEquals(1, gate.waiting());
        assertFalse(survivor.isDone());
        gate.release(owner);
        assertTrue(survivor.isDone());
        assertTrue(gate.holds(survivor.join()));
        gate.release(survivor.join());
    }

    @Test void awaitingCancellationRacingWithOwnerReleasePreservesLiveTicket()
            throws InterruptedException {
        for (int i = 0; i < 128; i++) {
            CriticalGate gate = new CriticalGate();
            var owner = gate.requestAwaitable().join();
            var cancelled = gate.requestAwaitable();
            var survivor = gate.requestAwaitable();
            Thread releasing = Thread.ofPlatform().start(() -> gate.release(owner));
            Thread cancelling = Thread.ofPlatform().start(() -> cancelled.cancel(false));
            releasing.join();
            cancelling.join();
            // Cancellation after an already-successful grant cannot revoke
            // committed ownership. In that ordering, the winner must explicitly
            // finish its critical body and release before the survivor advances.
            if (!cancelled.isCancelled()) {
                assertTrue(cancelled.isDone(), "grant/cancel left waiter unsettled");
                CriticalGate.Ticket granted = cancelled.join();
                assertTrue(gate.holds(granted));
                gate.release(granted);
            }
            assertTrue(survivor.isDone(), "survivor must never starve");
            assertTrue(gate.holds(survivor.join()), "survivor not admitted");
            assertFalse(gate.holds(owner), "old owner survived handoff");
            gate.release(survivor.join());
        }
    }

    @Test void retiringGateFailsPendingFutureAndRevokesOldTickets() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.requestAwaitable().join();
        var pending = gate.requestAwaitable();
        assertFalse(pending.isDone());
        gate.retire();
        assertTrue(pending.isDone());
        assertThrows(RuntimeException.class, pending::join);
        assertFalse(gate.holds(owner));
        assertTrue(gate.requestAwaitable().isDone());
        assertEquals(0, gate.waiting());
    }

    @Test void adversarialCancellationDuringRetirementDoesNotLeaveWaiters()
            throws InterruptedException {
        for (int i = 0; i < 128; i++) {
            CriticalGate gate = new CriticalGate();
            gate.requestAwaitable().join();
            var waiter = gate.requestAwaitable();
            Thread cancelling = Thread.ofPlatform().start(() -> waiter.cancel(false));
            Thread retiring = Thread.ofPlatform().start(gate::retire);
            cancelling.join();
            retiring.join();
            assertTrue(waiter.isDone());
            assertEquals(0, gate.waiting());
            assertTrue(gate.retired());
            assertTrue(gate.tryEnter().isEmpty());
        }
    }

    @Test void singleCarrierSchedulerRunsOtherWorkWhileCriticalWaiterIsParked()
            throws Exception {
        CriticalGate gate = new CriticalGate();
        var owner = gate.requestAwaitable().join();
        java.util.concurrent.CountDownLatch registered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger dispatches =
                new java.util.concurrent.atomic.AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> waiting = scheduler.start(resume -> {
                dispatches.incrementAndGet();
                if (resume.initial()) {
                    OresFuture<CriticalGate.Ticket> admission = gate.requestAwaitable();
                    assertFalse(admission.isDone());
                    registered.countDown();
                    return OresScheduler.await(admission);
                }
                assertNull(resume.failure());
                CriticalGate.Ticket ticket =
                        (CriticalGate.Ticket) resume.value();
                assertTrue(gate.holds(ticket));
                gate.release(ticket);
                return OresScheduler.done(42);
            });
            assertTrue(registered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // The sole scheduler carrier is free to execute another source
            // turn even though the first task has a queued critical ticket.
            OresFuture<Integer> releaser = scheduler.start(resume -> {
                gate.release(owner);
                return OresScheduler.done(7);
            });
            assertEquals(7, releaser.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(42, waiting.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, dispatches.get());
            assertEquals(0, gate.waiting());
        }
    }

    @Test void atomicRetirementCannotRaceNewTicketIntoRetiredActor() throws Exception {
        for (int i = 0; i < 128; i++) {
            CriticalGate gate = new CriticalGate();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            AtomicReference<CriticalGate.Ticket> acquired = new AtomicReference<>();
            AtomicReference<Boolean> retired = new AtomicReference<>();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread requester = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    acquired.set(gate.request().ticket());
                } catch (IllegalStateException closed) {
                    if (!gate.retired()) unexpected.compareAndSet(null, closed);
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    retired.set(gate.retireIfQuiescent());
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            start.countDown();
            requester.join();
            stopper.join();
            assertNull(unexpected.get(), "unexpected worker failure");
            if (Boolean.TRUE.equals(retired.get())) {
                assertNull(acquired.get(), "ticket admitted after atomic retirement");
                assertTrue(gate.retired());
            } else {
                assertNotNull(acquired.get(), "retirement failed with no owner");
                assertTrue(gate.holds(acquired.get()));
                assertFalse(gate.retireIfQuiescent());
                gate.release(acquired.get());
                assertTrue(gate.retireIfQuiescent());
            }
            assertTrue(gate.retired());
            assertTrue(gate.quiescent());
        }
    }

    @Test void actorRetirementRefusesLiveTicketOrQueuedAdmission() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.requestAwaitable().join();
        var pending = gate.requestAwaitable();
        assertFalse(gate.quiescent());
        assertFalse(gate.retireIfQuiescent());
        assertFalse(gate.retired());
        assertTrue(pending.cancel(false));
        assertFalse(gate.retireIfQuiescent());
        gate.release(owner);
        assertTrue(gate.quiescent());
        assertTrue(gate.retireIfQuiescent());
        assertTrue(gate.tryEnter().isEmpty());
        assertThrows(IllegalStateException.class, gate::request);
    }

    @Test void retirementDuringPromotedFutureNotificationDoesNotDeadlockOrLeak()
            throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.requestAwaitable().join();
        OresFuture<CriticalGate.Ticket> promoted = gate.requestAwaitable();
        java.util.concurrent.CountDownLatch notified =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseNotification =
                new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        promoted.whenCompleteRuntime((value, error) -> {
            notified.countDown();
            try {
                if (!releaseNotification.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("notification release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        Thread releasing = Thread.ofPlatform().start(() -> {
            try {
                gate.release(first);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        try {
            assertTrue(notified.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "promoted Future notification never reached");
            // The future has already reported its ticket, but the releasing
            // worker's callback is still running. Retirement must not hold
            // gate metadata while awaiting the callback, and must revoke
            // ownership even across this unsettled physical notification.
            assertTrue(promoted.isDone());
            gate.retire();
            assertTrue(gate.retired());
            assertFalse(gate.holds(promoted.join()));
        } finally {
            releaseNotification.countDown();
            releasing.join(5_000);
        }
        assertFalse(releasing.isAlive(), "notification deadlocked retirement");
        assertNull(failure.get(), "unexpected release/callback failure");
        assertTrue(gate.quiescent());
        assertEquals(0, gate.waiting());
    }

    @Test void retirementRacingAdmissionAndCancellationAlwaysSettlesFutures()
            throws Exception {
        for (int iteration = 0; iteration < 128; iteration++) {
            CriticalGate gate = new CriticalGate();
            CriticalGate.Ticket owner = gate.requestAwaitable().join();
            OresFuture<CriticalGate.Ticket> second = gate.requestAwaitable();
            OresFuture<CriticalGate.Ticket> third = gate.requestAwaitable();
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread releaser = Thread.ofPlatform().start(() -> {
                try { start.await(); gate.release(owner); }
                catch (Throwable thrown) { error.compareAndSet(null, thrown); }
            });
            Thread canceller = Thread.ofPlatform().start(() -> {
                try { start.await(); second.cancel(false); }
                catch (Throwable thrown) { error.compareAndSet(null, thrown); }
            });
            Thread retire = Thread.ofPlatform().start(() -> {
                try { start.await(); gate.retire(); }
                catch (Throwable thrown) { error.compareAndSet(null, thrown); }
            });
            start.countDown();
            releaser.join();
            canceller.join();
            retire.join();
            assertNull(error.get(), "unexpected concurrent gate failure");
            assertTrue(second.isDone(), "promoted or cancelled admission leaked");
            assertTrue(third.isDone(), "queued admission leaked after retirement");
            assertTrue(gate.quiescent());
            assertEquals(0, gate.waiting());
            assertTrue(gate.retired());
        }
    }

    @Test void logicalTicketCanBeReleasedOnAnotherCarrier() throws InterruptedException {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        var queued = gate.request();
        AtomicReference<CriticalGate.Ticket> handedOff = new AtomicReference<>();
        Thread otherCarrier = Thread.ofPlatform().start(
                () -> handedOff.set(gate.release(owner.ticket()).orElseThrow()));
        otherCarrier.join();
        assertSame(queued.ticket(), handedOff.get());
        assertTrue(gate.holds(queued.ticket()));
        gate.release(queued.ticket());
    }
}
