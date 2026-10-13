package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CriticalRwGateTest {
    @Test
    void nestedForeignCloseDuringExitDrainCannotAdmitNextTurnOrPublishDone()
            throws Exception {
        CriticalGate gate = new CriticalGate();
        CountDownLatch guestEntered = new CountDownLatch(1);
        CountDownLatch guestRelease = new CountDownLatch(1);
        CountDownLatch firstCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstClose = new CountDownLatch(1);
        CountDownLatch nestedCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseNestedClose = new CountDownLatch(1);
        CountDownLatch secondTurnEntered = new CountDownLatch(1);
        AtomicReference<java.util.function.Consumer<Runnable>> registerAfterExit =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread externalClose = null;
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        if ("first".equals(message)) {
                            registerAfterExit.set(runtime.captureCurrentActorCarrierExit());
                            var ticket = gate.newTicket();
                            assertTrue(ticket.enterAsync().isDone());
                            ticket.onClose(() -> {
                                firstCloseEntered.countDown();
                                try {
                                    assertTrue(releaseFirstClose.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            });
                            guestEntered.countDown();
                            assertTrue(guestRelease.await(5, TimeUnit.SECONDS));
                        } else if ("second".equals(message)) {
                            secondTurnEntered.countDown();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        if ("first".equals(message)) guestEntered.countDown();
                    }
                });
                actor.send("first");
                assertTrue(guestEntered.await(5, TimeUnit.SECONDS));
                assertNotNull(registerAfterExit.get());
                gate.closeAll();
                guestRelease.countDown();
                assertTrue(firstCloseEntered.await(5, TimeUnit.SECONDS));
                // Simulate another protection domain closing from an external
                // thread while the first domain's exit callback is executing.
                externalClose = Thread.ofVirtual().start(() -> {
                    try {
                        registerAfterExit.get().accept(() -> {
                            nestedCloseEntered.countDown();
                            try {
                                assertTrue(releaseNestedClose.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        });
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                });
                // The off-carrier registration must be nonblocking while
                // the existing cleanup phase is active. If it executes its
                // callback inline, the external thread will remain stuck.
                externalClose.join(1_000);
                assertFalse(externalClose.isAlive(),
                        "nested close must enqueue rather than run concurrently");
                assertEquals(1L, nestedCloseEntered.getCount(),
                        "nested callback must not run before the first exits");
                releaseFirstClose.countDown();
                assertTrue(nestedCloseEntered.await(5, TimeUnit.SECONDS));
                actor.send("second");
                assertFalse(secondTurnEntered.await(150, TimeUnit.MILLISECONDS),
                        "nested foreign domain-close callback must fence next actor turn");
                releaseNestedClose.countDown();
                assertTrue(secondTurnEntered.await(5, TimeUnit.SECONDS));
                runtime.stop(actor);
                assertNull(failure.get());
            } finally {
                guestRelease.countDown();
                releaseFirstClose.countDown();
                releaseNestedClose.countDown();
                if (externalClose != null) externalClose.join(5_000);
            }
        }
        assertNull(failure.get());
        assertEquals(0, gate.queuedCount());
        assertFalse(gate.hasWriter());
    }

    @Test
    void deferredDomainCloseBlocksNextActorTurnUntilCallbacksFinish() throws Exception {
        CriticalGate gate = new CriticalGate();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch closeCallbackEntered = new CountDownLatch(1);
        CountDownLatch closeCallbackRelease = new CountDownLatch(1);
        CountDownLatch secondTurnEntered = new CountDownLatch(1);
        AtomicReference<Throwable> guestError = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        if ("first".equals(message)) {
                            var ticket = gate.newTicket();
                            assertTrue(ticket.enterAsync().isDone());
                            ticket.onClose(() -> {
                                closeCallbackEntered.countDown();
                                try {
                                    if (!closeCallbackRelease.await(5, TimeUnit.SECONDS)) {
                                        throw new AssertionError("close callback release timed out");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            });
                            firstEntered.countDown();
                            assertTrue(firstRelease.await(5, TimeUnit.SECONDS));
                        } else {
                            secondTurnEntered.countDown();
                        }
                    } catch (Throwable problem) {
                        guestError.compareAndSet(null, problem);
                    } finally {
                        if ("first".equals(message)) firstEntered.countDown();
                    }
                });
                actor.send("first");
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                gate.closeAll();
                assertTrue(gate.hasWriter(), "writer must be fenced until guest turn exit");
                firstRelease.countDown();
                assertTrue(closeCallbackEntered.await(5, TimeUnit.SECONDS),
                        "domain close callback was not deferred to carrier exit");
                actor.send("second");
                assertFalse(secondTurnEntered.await(150, TimeUnit.MILLISECONDS),
                        "a new turn may not run while physical-exit callbacks are active");
                closeCallbackRelease.countDown();
                assertTrue(secondTurnEntered.await(5, TimeUnit.SECONDS),
                        "the queued second message must run once the cleanup fence clears");
                assertNull(guestError.get());
                assertFalse(gate.hasWriter());
                runtime.stop(actor);
            } finally {
                firstRelease.countDown();
                closeCallbackRelease.countDown();
            }
        }
    }

    @Test
    void coreDomainCloseRetainsActorWriterThroughPhysicalTurnExit() throws Exception {
        CriticalGate core = new CriticalGate();
        CriticalRwGate gate = core.rwGate();
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        AtomicReference<Throwable> actorFailure = new AtomicReference<>();
        AtomicReference<CriticalRwGate.Ticket> grant = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        var writer = gate.newCriticalTicket();
                        grant.set(writer);
                        assertTrue(writer.enterAsync().isDone());
                        admitted.countDown();
                        assertTrue(releaseTurn.await(5, TimeUnit.SECONDS));
                        assertTrue(writer.isHeld(),
                                "domain close must not revoke a running writer");
                    } catch (Throwable failure) {
                        actorFailure.compareAndSet(null, failure);
                    } finally {
                        admitted.countDown();
                    }
                });
                actor.send("hold");
                assertTrue(admitted.await(5, TimeUnit.SECONDS));
                assertNull(actorFailure.get());
                assertTrue(gate.hasWriter());

                var queued = gate.newTicket(CriticalRwGate.Mode.READ);
                OresFuture<Void> waiting = queued.enterAsync();
                assertFalse(waiting.isDone());
                core.closeAll();
                assertTrue(gate.hasWriter(),
                        "core close cannot revoke active actor grant");
                assertTrue(grant.get().isHeld());
                assertTrue(waiting.isDone(),
                        "queued admission must be cancelled immediately");
                assertFalse(queued.isHeld());
                assertEquals(0, gate.queuedCount());
                assertEquals(1, gate.trackedTicketCount(),
                        "only deferred actor grant may remain tracked");
                assertEquals(1, runtime.trackedActorResourceCount(actor));
                assertThrows(IllegalStateException.class, core::newTicket);
                assertThrows(IllegalStateException.class, gate::newCriticalTicket);

                releaseTurn.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((grant.get().isHeld()
                        || gate.trackedTicketCount() != 0
                        || runtime.trackedActorResourceCount(actor) != 0)
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertFalse(grant.get().isHeld(),
                        "deferred critical grant must close after carrier exit");
                assertFalse(gate.hasWriter());
                assertEquals(0, gate.trackedTicketCount());
                assertEquals(0, runtime.trackedActorResourceCount(actor));
                runtime.stop(actor);
                assertNull(actorFailure.get());
            } finally {
                releaseTurn.countDown();
            }
        }
    }

    @Test
    void facadeDomainCloseCannotBypassActorReaderQuiescence() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        AtomicReference<Throwable> actorFailure = new AtomicReference<>();
        AtomicReference<CriticalRwGate.Ticket> grant = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        var reader = gate.newTicket(CriticalRwGate.Mode.READ);
                        grant.set(reader);
                        assertTrue(reader.enterAsync().isDone());
                        admitted.countDown();
                        assertTrue(releaseTurn.await(5, TimeUnit.SECONDS));
                        assertTrue(reader.isHeld(),
                                "facade close may not force early grant release");
                    } catch (Throwable failure) {
                        actorFailure.compareAndSet(null, failure);
                    } finally {
                        admitted.countDown();
                    }
                });
                actor.send("hold");
                assertTrue(admitted.await(5, TimeUnit.SECONDS));
                var writer = gate.newCriticalTicket();
                OresFuture<Void> pending = writer.enterAsync();
                assertFalse(pending.isDone());
                gate.close();
                assertEquals(1, gate.activeReaderCount());
                assertTrue(grant.get().isHeld());
                assertTrue(pending.isDone());
                assertFalse(writer.isHeld());
                assertEquals(0, gate.queuedCount());
                assertEquals(1, gate.trackedTicketCount());

                releaseTurn.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((grant.get().isHeld()
                        || gate.trackedTicketCount() != 0
                        || runtime.trackedActorResourceCount(actor) != 0)
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertFalse(grant.get().isHeld());
                assertEquals(0, gate.activeReaderCount());
                assertEquals(0, gate.trackedTicketCount());
                runtime.stop(actor);
                assertNull(actorFailure.get());
            } finally {
                releaseTurn.countDown();
            }
        }
    }

    @Test
    void concurrentCoreCloseStopAndQueuedCancellationPreserveQuiescence()
            throws Exception {
        for (int run = 0; run < 20; run++) {
            CriticalGate gate = new CriticalGate();
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<CriticalGate.Ticket> grant = new AtomicReference<>();
            try (ActorRuntime runtime = new ActorRuntime()) {
                Thread stop = null;
                Thread close = null;
                try {
                    var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                        try {
                            var writer = gate.newTicket();
                            grant.set(writer);
                            assertTrue(writer.enterAsync().isDone());
                            admitted.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            assertTrue(writer.isHeld(),
                                    "STOP/domain-close cannot release active guest grant");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            admitted.countDown();
                        }
                    });
                    actor.send("hold");
                    assertTrue(admitted.await(5, TimeUnit.SECONDS));
                    var waiting = gate.newTicket(CriticalGate.Mode.READ);
                    OresFuture<Void> future = waiting.enterAsync();
                    assertFalse(future.isDone());
                    CountDownLatch start = new CountDownLatch(1);
                    stop = Thread.ofVirtual().start(() -> {
                        try { start.await(); runtime.stop(actor); }
                        catch (Throwable problem) { failure.compareAndSet(null, problem); }
                    });
                    close = Thread.ofVirtual().start(() -> {
                        try { start.await(); gate.closeAll(); }
                        catch (Throwable problem) { failure.compareAndSet(null, problem); }
                    });
                    start.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((!future.isDone() || runtime.isAlive(actor))
                            && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertTrue(future.isDone(), "queued admission was not retracted");
                    assertFalse(runtime.isAlive(actor), "STOP admission was delayed");
                    assertTrue(grant.get().isHeld(),
                            "parallel STOP/close may not release active writer");
                    assertEquals(0, gate.queuedCount());
                } finally {
                    release.countDown();
                    if (stop != null) stop.join(5_000);
                    if (close != null) close.join(5_000);
                }
                assertNull(failure.get());
                assertFalse(gate.hasWriter());
                assertEquals(0, gate.queuedCount());
                assertEquals(0, CriticalGate.waitingLogicalDomains());
            }
        }
    }

    @Test
    void deniedNestedAdmissionsDoNotAccumulateTicketsOrRevokeLiveGrant() {
        CriticalRwGate gate = new CriticalRwGate();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            scheduler.startSync(() -> {
                var owner = gate.newTicket(CriticalRwGate.Mode.READ);
                assertTrue(owner.enterAsync().isDone());
                assertTrue(owner.isHeld());
                assertEquals(1, gate.trackedTicketCount());

                for (int n = 0; n < 256; n++) {
                    var deniedAsync = gate.newTicket(CriticalRwGate.Mode.WRITE);
                    assertThrows(IllegalStateException.class, deniedAsync::enterAsync);
                    var deniedSync = gate.newTicket(CriticalRwGate.Mode.WRITE);
                    assertThrows(IllegalStateException.class, deniedSync::enterSync);
                    assertEquals(1, gate.trackedTicketCount(),
                            "denied tickets must not accumulate in the facade");
                    assertTrue(owner.isHeld(), "denial must not revoke existing owner");
                }

                // A second admission call on this exact ticket is also invalid,
                // but cannot prematurely release the already-live first grant.
                assertThrows(IllegalStateException.class, owner::enterAsync);
                assertThrows(IllegalStateException.class, owner::enterSync);
                assertTrue(owner.isHeld());
                assertEquals(1, gate.activeReaderCount());
                owner.close();
                assertEquals(0, gate.trackedTicketCount());
                return null;
            }).join();
        } finally {
            gate.close();
        }
        assertEquals(0, gate.activeReaderCount());
    }

    @Test
    void concurrentReadersExcludeWritersAndPreserveWriterQueueOrder() {
        CriticalRwGate gate = new CriticalRwGate();
        var first = gate.newTicket(CriticalRwGate.Mode.READ);
        var second = gate.newTicket(CriticalRwGate.Mode.READ);
        var writer = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var lateReader = gate.newTicket(CriticalRwGate.Mode.READ);

        assertTrue(first.enterAsync().isDone());
        assertTrue(second.enterAsync().isDone());
        assertFalse(writer.enterAsync().isDone());
        assertFalse(lateReader.enterAsync().isDone());
        assertEquals(2, gate.activeReaderCount());
        assertEquals(2, gate.queuedCount());

        first.close();
        assertFalse(writer.isHeld(), "writer cannot overlap the remaining reader");
        second.close();
        assertTrue(writer.isHeld(), "writer at head must win before newer reader");
        assertFalse(lateReader.isHeld());
        assertEquals(0, gate.activeReaderCount());
        writer.close();
        assertTrue(lateReader.isHeld());
        assertEquals(1, gate.activeReaderCount());
        lateReader.close();
        assertEquals(0, gate.queuedCount());
        assertFalse(gate.hasWriter());
    }

    @Test
    void consecutiveHeadReadersAreAdmittedAsACohort() {
        CriticalRwGate gate = new CriticalRwGate();
        var initial = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var readA = gate.newTicket(CriticalRwGate.Mode.READ);
        var readB = gate.newTicket(CriticalRwGate.Mode.READ);
        var nextWrite = gate.newTicket(CriticalRwGate.Mode.WRITE);
        initial.enterAsync();
        readA.enterAsync();
        readB.enterAsync();
        nextWrite.enterAsync();

        initial.close();
        assertTrue(readA.isHeld());
        assertTrue(readB.isHeld());
        assertFalse(nextWrite.isHeld());
        readA.close();
        assertFalse(nextWrite.isHeld());
        readB.close();
        assertTrue(nextWrite.isHeld());
        nextWrite.close();
    }

    @Test
    void cancellingHeadWriterUnblocksReadersBehindItWithoutWaitingForActiveReader() {
        CriticalRwGate gate = new CriticalRwGate();
        var firstRead = gate.newTicket(CriticalRwGate.Mode.READ);
        var cancelledWriter = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var waitingRead = gate.newTicket(CriticalRwGate.Mode.READ);
        firstRead.enterAsync();
        OresFuture<Void> pendingWriter = cancelledWriter.enterAsync();
        OresFuture<Void> pendingRead = waitingRead.enterAsync();
        assertEquals(2, gate.queuedCount());
        assertFalse(pendingRead.isDone());
        assertTrue(pendingWriter.cancel(false));
        assertFalse(cancelledWriter.isHeld());
        assertTrue(waitingRead.isHeld(),
                "reader behind removed writer must join active readers immediately");
        assertEquals(2, gate.activeReaderCount());
        firstRead.close();
        waitingRead.close();
        assertEquals(0, gate.queuedCount());
    }

    @Test
    void unrequestedTicketConstructionIsBoundedAndCapacityRecoversOnRelease() {
        CriticalRwGate gate = new CriticalRwGate();
        java.util.List<CriticalRwGate.Ticket> reserved = new java.util.ArrayList<>();
        for (int n = 0; n < CriticalRwGate.MAX_TRACKED_TICKETS; n++) {
            reserved.add(gate.newTicket(CriticalRwGate.Mode.READ));
        }
        assertEquals(CriticalRwGate.MAX_TRACKED_TICKETS, gate.trackedTicketCount());
        assertEquals(0, gate.queuedCount(),
                "tickets never requested must not consume queue slots");
        var denial = assertThrows(IllegalStateException.class,
                () -> gate.newTicket(CriticalRwGate.Mode.WRITE));
        assertTrue(denial.getMessage().contains("limit"));
        assertEquals(CriticalRwGate.MAX_TRACKED_TICKETS, gate.trackedTicketCount());
        reserved.removeLast().close();
        assertEquals(CriticalRwGate.MAX_TRACKED_TICKETS - 1,
                gate.trackedTicketCount());
        var replacement = gate.newCriticalTicket();
        assertTrue(replacement.enterAsync().isDone());
        replacement.close();
        gate.close();
        assertEquals(0, gate.trackedTicketCount());
    }

    @Test
    void cancelledAdmissionReclaimsFacadeWrapperWithoutRevokingActiveWriter() {
        CriticalRwGate gate = new CriticalRwGate();
        var writer = gate.newCriticalTicket();
        assertTrue(writer.enterAsync().isDone());
        for (int n = 0; n < 1024; n++) {
            var reader = gate.newTicket(CriticalRwGate.Mode.READ);
            var waiting = reader.enterAsync();
            assertFalse(waiting.isDone());
            assertEquals(2, gate.trackedTicketCount());
            assertTrue(waiting.cancel(false));
            assertTrue(waiting.isCancelled());
            assertEquals(1, gate.trackedTicketCount(),
                    "cancelled admission wrapper must not remain retained");
            assertEquals(0, gate.queuedCount());
            assertTrue(writer.isHeld(),
                    "denied reader must not release the legitimate writer");
        }
        writer.close();
        assertEquals(0, gate.trackedTicketCount());
        assertEquals(0, gate.queuedCount());
        assertFalse(gate.hasWriter());
        gate.close();
    }

    @Test
    void cancellingWaiterRetractsItsSlotAndCannotResurrectAfterHandoff() {
        CriticalRwGate gate = new CriticalRwGate();
        var current = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var cancelled = gate.newTicket(CriticalRwGate.Mode.READ);
        var next = gate.newTicket(CriticalRwGate.Mode.WRITE);
        current.enterAsync();
        OresFuture<Void> pending = cancelled.enterAsync();
        next.enterAsync();
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(1, gate.queuedCount());
        current.close();
        assertFalse(cancelled.isHeld());
        assertTrue(next.isHeld());
        next.close();
    }

    @Test
    void cancellingGrantHandoffNeverLeaksReadersOrWriters() throws Exception {
        for (int n = 0; n < 200; n++) {
            CriticalRwGate gate = new CriticalRwGate();
            var owner = gate.newTicket(CriticalRwGate.Mode.WRITE);
            var candidate = gate.newTicket(CriticalRwGate.Mode.READ);
            var following = gate.newTicket(CriticalRwGate.Mode.WRITE);
            owner.enterAsync();
            OresFuture<Void> pending = candidate.enterAsync();
            following.enterAsync();
            Thread canceller = new Thread(() -> pending.cancel(false));
            canceller.start();
            owner.close();
            canceller.join();
            if (pending.isCancelled()) assertFalse(candidate.isHeld());
            candidate.close();
            if (following.isHeld()) following.close();
            assertEquals(0, gate.queuedCount());
            assertEquals(0, gate.activeReaderCount());
            assertFalse(gate.hasWriter());
            gate.close();
        }
    }

    @Test
    void queuedTasksYieldCarrierAndResumeOnSchedulerAfterGrant() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        var owner = gate.newTicket(CriticalRwGate.Mode.WRITE);
        owner.enterAsync();
        var queued = new AtomicReference<CriticalRwGate.Ticket>();
        var wasQueued = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (resume.initial()) {
                    var reader = gate.newTicket(CriticalRwGate.Mode.READ);
                    queued.set(reader);
                    OresFuture<Void> admitted = reader.enterAsync();
                    wasQueued.countDown();
                    return OresScheduler.await(admitted);
                }
                assertNull(resume.failure());
                var reader = queued.get();
                assertTrue(reader.isHeld(), "cannot execute after revoked admission");
                reader.close();
                return OresScheduler.done(42);
            });
            assertTrue(wasQueued.await(5, TimeUnit.SECONDS));
            assertEquals(1, gate.queuedCount());
            assertTrue(scheduler.startSync(() -> true).join(),
                    "carrier must service another task while reader waits");
            owner.close();
            assertEquals(42, task.join());
        }
        assertEquals(0, gate.queuedCount());
    }

    @Test
    void pendingFutureBlockingBridgeCannotParkOrdinarySchedulerCarrier() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Boolean> checked = scheduler.startSync(() -> {
                var pending = new OresFuture<Void>();
                assertThrows(IllegalStateException.class, pending::join);
                assertThrows(IllegalStateException.class, pending::get);
                assertThrows(IllegalStateException.class,
                        () -> pending.get(1, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> pending.get(0, TimeUnit.SECONDS),
                        "zero-timeout polling should remain allowed");
                assertEquals(42, OresFuture.completed(42).join(),
                        "settled Future must still be readable synchronously");
                return true;
            });
            assertTrue(checked.join());
        }
    }

    @Test
    void cancellingSchedulerTaskRevokesContendedRwAdmission() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        var writer = gate.newTicket(CriticalRwGate.Mode.WRITE);
        writer.enterAsync();
        var queued = new AtomicReference<CriticalRwGate.Ticket>();
        var enqueued = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (!resume.initial()) {
                    fail("cancelled continuation must never enter guest code");
                }
                var ticket = gate.newTicket(CriticalRwGate.Mode.READ);
                queued.set(ticket);
                var admission = ticket.enterAsync();
                enqueued.countDown();
                return OresScheduler.await(admission);
            });
            assertTrue(enqueued.await(5, TimeUnit.SECONDS));
            var ticket = queued.get();
            ticket.releaseOnCompletion(task);
            assertEquals(1, gate.queuedCount());
            assertTrue(task.cancel(false));
            assertEquals(0, gate.queuedCount(),
                    "cancelling the scheduler owner must retract pending admission");
            assertFalse(ticket.isHeld());
        } finally {
            writer.close();
        }
        assertFalse(gate.hasWriter());
    }

    @Test
    void sameLogicalContinuationCannotUpgradeOrNestWhileUnverified() {
        CriticalRwGate gate = new CriticalRwGate();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> completed = scheduler.startSync(() -> {
                var reader = gate.newTicket(CriticalRwGate.Mode.READ);
                reader.enterAsync();
                var write = gate.newTicket(CriticalRwGate.Mode.WRITE);
                try {
                    var error = assertThrows(IllegalStateException.class, write::enterAsync);
                    assertTrue(error.getMessage().contains("read-to-write"));
                } finally {
                    reader.close();
                }
                return null;
            });
            completed.join();
        }
        assertEquals(0, gate.activeReaderCount());
    }

    @Test
    void failureCompletionAndGateCloseRevokeAllGrantsAndWaiters() {
        CriticalRwGate gate = new CriticalRwGate();
        var active = gate.newTicket(CriticalRwGate.Mode.READ);
        var queued = gate.newTicket(CriticalRwGate.Mode.WRITE);
        active.enterAsync();
        OresFuture<Void> pending = queued.enterAsync();
        gate.close();
        assertFalse(active.isHeld());
        assertFalse(queued.isHeld());
        assertTrue(pending.isDone());
        assertEquals(0, gate.queuedCount());
        assertEquals(0, gate.activeReaderCount());
        assertFalse(gate.hasWriter());
        assertThrows(IllegalStateException.class,
                () -> gate.newTicket(CriticalRwGate.Mode.READ));
        active.close();
        queued.close();
    }

    @Test
    void unrequestedAdmissionCannotBecomeAnInfiniteWait() {
        CriticalRwGate gate = new CriticalRwGate();
        var ticket = gate.newTicket(CriticalRwGate.Mode.READ);
        var failure = assertThrows(IllegalStateException.class, ticket::admission);
        assertTrue(failure.getMessage().contains("not requested"));
        ticket.close();
        assertTrue(ticket.admission().isDone(), "closed ticket must be terminal");
    }

    @Test
    void activeReaderBoundQueuesExcessReadersAndAdmitsWhenCapacityReturns() {
        CriticalRwGate gate = new CriticalRwGate();
        java.util.List<CriticalRwGate.Ticket> admitted = new java.util.ArrayList<>();
        for (int i = 0; i < CriticalRwGate.MAX_ACTIVE_READERS; i++) {
            var reader = gate.newTicket(CriticalRwGate.Mode.READ);
            assertTrue(reader.enterAsync().isDone());
            admitted.add(reader);
        }
        assertEquals(CriticalRwGate.MAX_ACTIVE_READERS, gate.activeReaderCount());
        var overflow = gate.newTicket(CriticalRwGate.Mode.READ);
        assertFalse(overflow.enterAsync().isDone(), "reader cap must not over-admit");
        assertEquals(1, gate.queuedCount());
        admitted.getFirst().close();
        assertTrue(overflow.isHeld(), "vacated reader slot must hand off without parking");
        assertEquals(CriticalRwGate.MAX_ACTIVE_READERS, gate.activeReaderCount());
        gate.close();
        assertFalse(overflow.isHeld());
        assertEquals(0, gate.activeReaderCount());
        assertEquals(0, gate.queuedCount());
    }

    @Test
    void crossGateLogicalOwnershipRejectsOverlapWithoutBlockingOtherDomains() {
        CriticalRwGate one = new CriticalRwGate();
        CriticalRwGate two = new CriticalRwGate();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            var result = scheduler.startSync(() -> {
                var writer = one.newTicket(CriticalRwGate.Mode.WRITE);
                writer.enterAsync();
                try {
                    var reader = two.newTicket(CriticalRwGate.Mode.READ);
                    assertThrows(IllegalStateException.class, reader::enterAsync,
                            "same logical task cannot hold two unverified critical domains");
                } finally {
                    writer.close();
                }
                return true;
            });
            assertTrue(result.join());
        }
        assertFalse(one.hasWriter());
        assertEquals(0, two.queuedCount());
    }

    @Test
    void completionBindingIsSingleOwnerAndCancelsQueuedTickets() {
        CriticalRwGate gate = new CriticalRwGate();
        var owner = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var waiter = gate.newTicket(CriticalRwGate.Mode.READ);
        owner.enterAsync();
        OresFuture<Void> pending = waiter.enterAsync();
        OresFuture<Void> taskCompletion = new OresFuture<>();
        waiter.releaseOnCompletion(taskCompletion);
        var duplicate = assertThrows(IllegalStateException.class,
                () -> waiter.releaseOnCompletion(new OresFuture<>()));
        assertTrue(duplicate.getMessage().contains("already bound"));
        assertTrue(taskCompletion.cancel(false));
        assertTrue(pending.isDone(), "owner cancellation must settle admission Future");
        assertFalse(waiter.isHeld());
        assertEquals(0, gate.queuedCount());
        owner.close();
        assertEquals(0, gate.activeReaderCount());
        assertFalse(gate.hasWriter());
    }

    @Test
    void alreadySettledCompletionReleasesGrantAndCannotBeAttachedAgain() {
        CriticalRwGate gate = new CriticalRwGate();
        var reader = gate.newTicket(CriticalRwGate.Mode.READ);
        reader.enterAsync();
        reader.releaseOnCompletion(OresFuture.completed(null));
        assertFalse(reader.isHeld());
        assertThrows(IllegalStateException.class,
                () -> reader.releaseOnCompletion(new OresFuture<>()));
        assertEquals(0, gate.activeReaderCount());
    }

    @Test
    void taskCompletionReleasesPermitEvenWhenTaskFails() {
        CriticalRwGate gate = new CriticalRwGate();
        var writer = gate.newTicket(CriticalRwGate.Mode.WRITE);
        writer.enterAsync();
        OresFuture<Void> end = new OresFuture<>();
        writer.releaseOnCompletion(end);
        end.failFromRuntime(new IllegalStateException("task failed"));
        assertFalse(writer.isHeld());
        assertFalse(gate.hasWriter());
    }

    @Test
    void actorStopCannotReleaseSharedWriterUntilPhysicalTurnUnwinds() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        AtomicReference<Throwable> actorFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        CriticalRwGate.Ticket reader = null;
        Thread stopThread = null;
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var writer = gate.newCriticalTicket();
                    assertTrue(writer.enterAsync().isDone());
                    assertTrue(writer.isHeld());
                    writerEntered.countDown();
                    assertTrue(releaseTurn.await(5, TimeUnit.SECONDS),
                            "test never released the active actor turn");
                } catch (Throwable failure) {
                    actorFailure.compareAndSet(null, failure);
                } finally {
                    writerEntered.countDown();
                }
            });
            actor.send("hold");
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
            assertNull(actorFailure.get());
            assertTrue(gate.hasWriter());

            // Independent host-owned reader must remain behind the writer even
            // after STOP admission, until the original guest turn has exited.
            reader = gate.newTicket(CriticalRwGate.Mode.READ);
            OresFuture<Void> waiting = reader.enterAsync();
            assertFalse(waiting.isDone());
            stopThread = Thread.ofVirtual().start(() -> {
                try {
                    runtime.stop(actor);
                } catch (Throwable failure) {
                    stopFailure.compareAndSet(null, failure);
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.isAlive(actor) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(runtime.isAlive(actor), "host STOP was never admitted");
            assertTrue(gate.hasWriter(),
                    "STOP must not unlock a shared critical domain while guest code runs");
            assertFalse(waiting.isDone(),
                    "queued reader cannot execute until writer's carrier unwinds");
            assertEquals(1, gate.queuedCount());

            // Unlock the guest before the runtime's try-with-resources close.
            // The STOP thread must then retire the writer and admit the reader.
            releaseTurn.countDown();
            stopThread.join(5_000);
            assertFalse(stopThread.isAlive(), "STOP never finalized after carrier exit");
            assertNull(stopFailure.get());
            assertTrue(waiting.isDone(), "reader did not wake after physical quiescence");
            assertTrue(reader.isHeld());
            assertFalse(gate.hasWriter());
        } finally {
            releaseTurn.countDown();
            if (stopThread != null) stopThread.join(5_000);
            if (reader != null) reader.close();
            gate.close();
        }
        assertNull(actorFailure.get());
        assertNull(stopFailure.get());
        assertFalse(gate.hasWriter());
        assertEquals(0, gate.trackedTicketCount());
    }

    @Test
    void actorStopRetractsQueuedReaderBeforeRunningTurnExits() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        var independentWriter = gate.newCriticalTicket();
        assertTrue(independentWriter.enterAsync().isDone());
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        AtomicReference<OresFuture<Void>> pending = new AtomicReference<>();
        AtomicReference<Throwable> actorFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        try {
            try (ActorRuntime runtime = new ActorRuntime()) {
                Thread stopper = null;
                try {
                    var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                        try {
                            var reader = gate.newTicket(CriticalRwGate.Mode.READ);
                            pending.set(reader.enterAsync());
                            assertFalse(pending.get().isDone());
                            queued.countDown();
                            assertTrue(releaseTurn.await(5, TimeUnit.SECONDS));
                        } catch (Throwable failure) {
                            actorFailure.compareAndSet(null, failure);
                        } finally {
                            queued.countDown();
                        }
                    });
                    actor.send("queue");
                    assertTrue(queued.await(5, TimeUnit.SECONDS));
                    assertNull(actorFailure.get());
                    assertEquals(1, gate.queuedCount());
                    stopper = Thread.ofVirtual().start(() -> {
                        try {
                            runtime.stop(actor);
                        } catch (Throwable failure) {
                            stopFailure.compareAndSet(null, failure);
                        }
                    });
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (runtime.isAlive(actor) && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertFalse(runtime.isAlive(actor), "host STOP was never admitted");
                    // Revocation callbacks run off-lock, so observe their
                    // completion rather than inferring it from stopped=true.
                    while (gate.queuedCount() != 0 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertEquals(0, gate.queuedCount(),
                            "STOP must retract a pending ticket without parking on guest turn exit");
                    while (!pending.get().isDone() && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertTrue(pending.get().isDone(),
                            "STOP must settle the cancelled reader's admission Future");
                    assertTrue(independentWriter.isHeld(),
                            "STOP cannot release another actor or host owner's writer");
                    assertEquals(0, runtime.trackedActorResourceCount(actor));
                } finally {
                    releaseTurn.countDown();
                    if (stopper != null) stopper.join(5_000);
                }
            }
            assertNull(actorFailure.get());
            assertNull(stopFailure.get());
            assertEquals(1, gate.trackedTicketCount(),
                    "only the unrelated host writer must remain");
        } finally {
            releaseTurn.countDown();
            independentWriter.close();
            gate.close();
        }
        assertEquals(0, gate.trackedTicketCount());
    }

    @Test
    void actorStopRevokesAlreadyGrantedRWWriterAndFacadeOwnership() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        java.util.concurrent.CountDownLatch admitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<CriticalRwGate.Ticket> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var ticket = gate.newCriticalTicket();
                    captured.set(ticket);
                    assertTrue(ticket.enterAsync().isDone());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    admitted.countDown();
                }
            });
            actor.send("hold");
            assertTrue(admitted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(gate.hasWriter());
            assertEquals(1, gate.trackedTicketCount());
            runtime.stop(actor);
            assertFalse(gate.hasWriter(),
                    "STOP must release a granted admission without explicit guest cleanup");
            assertEquals(0, gate.trackedTicketCount(),
                    "STOP must retire the facade wrapper as well as the core ticket");
            assertFalse(captured.get().isHeld());
            assertEquals(0, CriticalRwGate.waitingLogicalDomains());
        }
    }

    @Test
    void actorStopRetractsQueuedRWWaiterWithoutReleasingHostOwner() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        var hostOwner = gate.newCriticalTicket();
        assertTrue(hostOwner.enterAsync().isDone());
        java.util.concurrent.CountDownLatch queued = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<OresFuture<Void>> future =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            try (ActorRuntime runtime = new ActorRuntime()) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        var waiter = gate.newTicket(CriticalRwGate.Mode.READ);
                        future.set(waiter.enterAsync());
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        queued.countDown();
                    }
                });
                actor.send("queue");
                assertTrue(queued.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertNull(failure.get());
                assertEquals(1, gate.queuedCount());
                runtime.stop(actor);
                assertEquals(0, gate.queuedCount());
                assertTrue(hostOwner.isHeld(), "STOP must not revoke another owner's permit");
                assertTrue(future.get().isDone());
                assertEquals(1, gate.trackedTicketCount(),
                        "only the independent host writer may remain tracked");
                assertEquals(0, CriticalRwGate.waitingLogicalDomains());
            }
        } finally {
            hostOwner.close();
            gate.close();
        }
    }

    @Test
    void simultaneousStopCancelAndReleaseCannotResurrectCriticalOwnership()
            throws Exception {
        for (int n = 0; n < 128; n++) {
            CriticalRwGate gate = new CriticalRwGate();
            var active = gate.newCriticalTicket();
            var waiting = gate.newTicket(CriticalRwGate.Mode.READ);
            assertTrue(active.enterAsync().isDone());
            var pending = waiting.enterAsync();
            assertFalse(pending.isDone());
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread stop = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    gate.close();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            Thread cancel = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    pending.cancel(false);
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            Thread release = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    active.close();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            start.countDown();
            stop.join(5_000);
            cancel.join(5_000);
            release.join(5_000);
            assertFalse(stop.isAlive());
            assertFalse(cancel.isAlive());
            assertFalse(release.isAlive());
            assertNull(failure.get(), "no lifecycle race may fail with an uncaught exception");
            assertTrue(pending.isDone(), "STOP must settle pending admission");
            assertFalse(active.isHeld());
            assertFalse(waiting.isHeld());
            assertEquals(0, gate.activeReaderCount());
            assertEquals(0, gate.queuedCount());
            assertFalse(gate.hasWriter());
            assertEquals(0, gate.trackedTicketCount(),
                    "STOP/cancellation/release must retire all tracked tickets");
        }
    }

    @Test
    void boundedCriticalQueueRejectsOverflowAndStopReclaimsAllTickets() {
        CriticalRwGate gate = new CriticalRwGate();
        var writer = gate.newCriticalTicket();
        assertTrue(writer.enterAsync().isDone());
        for (int n = 0; n < CriticalRwGate.MAX_WAITERS; n++) {
            var pending = gate.newTicket(CriticalRwGate.Mode.READ);
            assertFalse(pending.enterAsync().isDone());
        }
        assertEquals(CriticalRwGate.MAX_WAITERS, gate.queuedCount());
        assertEquals(CriticalRwGate.MAX_WAITERS + 1, gate.trackedTicketCount());
        var overflow = gate.newTicket(CriticalRwGate.Mode.READ);
        var denied = assertThrows(IllegalStateException.class, overflow::enterAsync);
        assertTrue(denied.getMessage().contains("full"));
        assertEquals(CriticalRwGate.MAX_WAITERS + 1, gate.trackedTicketCount(),
                "a denied overflow must immediately release its facade wrapper");
        gate.close();
        assertEquals(0, gate.queuedCount());
        assertEquals(0, gate.activeReaderCount());
        assertEquals(0, gate.trackedTicketCount());
        assertFalse(gate.hasWriter());
        assertFalse(writer.isHeld());
    }

    @Test
    void independentGatesAndDoubleCloseAreSafe() {
        CriticalRwGate first = new CriticalRwGate();
        CriticalRwGate second = new CriticalRwGate();
        var a = first.newTicket(CriticalRwGate.Mode.WRITE);
        var b = second.newTicket(CriticalRwGate.Mode.READ);
        a.enterAsync();
        b.enterAsync();
        assertTrue(a.isHeld());
        assertTrue(b.isHeld());
        a.close();
        a.close();
        b.close();
        assertFalse(first.hasWriter());
        assertEquals(0, second.activeReaderCount());
        first.close();
        first.close();
        second.close();
    }
}
