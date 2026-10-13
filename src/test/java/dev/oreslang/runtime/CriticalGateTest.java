package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalGateTest {
    @Test
    void domainClosePreservesErrorPriorityAfterEarlierObserverException() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        var queued = gate.newTicket();
        assertTrue(owner.enterAsync().isDone());
        var pending = queued.enterAsync();
        owner.onClose(() -> {
            throw new IllegalStateException("first cleanup failed");
        });
        queued.onClose(() -> {
            throw new AssertionError("fatal cleanup failed");
        });
        AssertionError fatal = assertThrows(AssertionError.class, gate::closeAll);
        assertEquals("fatal cleanup failed", fatal.getMessage());
        assertEquals(1, fatal.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, fatal.getSuppressed()[0]);
        assertTrue(pending.isDone());
        assertFalse(gate.held());
        assertEquals(0, gate.queuedCount());
    }

    @Test
    void actorStopDrainsAllOwnedGatesEvenWhenCloseObserverThrowsError() throws Exception {
        CriticalGate first = new CriticalGate();
        CriticalGate second = new CriticalGate();
        java.util.concurrent.CountDownLatch admitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> guestFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var a = first.newTicket();
                    assertTrue(a.enterAsync().isDone());
                    a.onClose(() -> {
                        throw new AssertionError("injected actor resource cleanup error");
                    });
                    var b = second.newTicket();
                    assertTrue(b.enterAsync().isDone());
                } catch (Throwable failure) {
                    guestFailure.set(failure);
                } finally {
                    admitted.countDown();
                }
            });
            actor.send("hold");
            assertTrue(admitted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(guestFailure.get());
            assertTrue(first.held());
            assertTrue(second.held());
            runtime.stop(actor);
            assertFalse(first.held());
            assertFalse(second.held(),
                    "Error in one resource callback must not skip later revocation");
            assertEquals(0, runtime.trackedActorResourceCount(actor));
            assertTrue(actor.done().isDone());
            assertThrows(Throwable.class, () -> actor.done().join(),
                    "cleanup Error must be an observable actor terminal failure");
        }
    }

    @Test
    void throwingCloseObserverDoesNotStrandAlreadyGrantedSuccessor() {
        CriticalGate gate = new CriticalGate();
        var writer = gate.newTicket();
        var nextWriter = gate.newTicket();
        var lastWriter = gate.newTicket();
        assertTrue(writer.enterAsync().isDone());
        OresFuture<Void> nextAdmission = nextWriter.enterAsync();
        OresFuture<Void> lastAdmission = lastWriter.enterAsync();
        assertFalse(nextAdmission.isDone());
        writer.onClose(() -> {
            throw new IllegalStateException("injected close observer failure");
        });
        var failure = assertThrows(IllegalStateException.class, writer::close);
        assertEquals("injected close observer failure", failure.getMessage());
        assertTrue(nextAdmission.isDone(), "handoff must settle despite close callback failure");
        assertTrue(nextWriter.isHeld());
        assertFalse(lastAdmission.isDone());
        nextWriter.close();
        assertTrue(lastAdmission.isDone(), "subsequent FIFO handoff must still run");
        lastWriter.close();
        assertFalse(gate.held());
        assertEquals(0, gate.queuedCount());
    }

    @Test
    void wholeDomainCloseContinuesAfterMultipleThrowingObservers() {
        CriticalGate gate = new CriticalGate();
        var writer = gate.newTicket();
        var firstQueued = gate.newTicket(CriticalGate.Mode.READ);
        var secondQueued = gate.newTicket(CriticalGate.Mode.WRITE);
        assertTrue(writer.enterAsync().isDone());
        var firstAdmission = firstQueued.enterAsync();
        var secondAdmission = secondQueued.enterAsync();
        writer.onClose(() -> {
            throw new IllegalStateException("writer observer failed");
        });
        firstQueued.onClose(() -> {
            throw new IllegalStateException("reader observer failed");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, gate::closeAll);
        assertEquals("writer observer failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length,
                "later observer failures must be recorded without skipping cleanup");
        assertTrue(firstAdmission.isDone());
        assertTrue(secondAdmission.isDone());
        assertFalse(writer.isHeld());
        assertFalse(firstQueued.isHeld());
        assertFalse(secondQueued.isHeld());
        assertEquals(0, gate.queuedCount());
        assertFalse(gate.held());
        assertEquals(0, CriticalGate.waitingLogicalDomains());
        assertThrows(IllegalStateException.class, gate::newTicket);
        gate.closeAll();
    }

    @Test
    void directCoreDomainCloseDetachesActorResourceBeforeActorStops() throws Exception {
        CriticalGate gate = new CriticalGate();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    assertTrue(gate.newTicket().enterAsync().isDone());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    entered.countDown();
                }
            });
            actor.send("hold");
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, runtime.trackedActorResourceCount(actor));
            gate.closeAll();
            assertFalse(gate.held());
            assertEquals(0, runtime.trackedActorResourceCount(actor),
                    "closing a domain must not retain actor-owned ticket callbacks");
            runtime.stop(actor);
            assertEquals(0, CriticalGate.waitingLogicalDomains());
        }
    }

    @Test
    void duplicateActorAdmissionCannotDeregisterOriginalStopRevocation() throws Exception {
        CriticalGate gate = new CriticalGate();
        java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<CriticalGate.Ticket> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    CriticalGate.Ticket ticket = gate.newTicket();
                    captured.set(ticket);
                    assertTrue(ticket.enterAsync().isDone());
                    assertThrows(IllegalStateException.class, ticket::enterAsync);
                    assertTrue(ticket.isHeld(), "duplicate denial must not revoke original");
                } catch (Throwable failure) {
                    error.set(failure);
                } finally {
                    first.countDown();
                }
            });
            actor.send("hold");
            assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue(gate.held());
            runtime.stop(actor);
            assertFalse(gate.held(), "STOP must still revoke the first admission");
            assertFalse(captured.get().isHeld());
            assertEquals(0, CriticalGate.waitingLogicalDomains());
        }
    }

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
}
