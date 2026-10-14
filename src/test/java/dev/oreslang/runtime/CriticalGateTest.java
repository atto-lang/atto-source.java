package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalGateTest {
    @Test
    void allHostReleaseStopAndDomainCloseOrdersRespectPhysicalGrant()
            throws Exception {
        // Replay each permutation against the real actor runtime, rather than
        // treating an abstract model as proof that critical admission works.
        // The owning guest is kept inside a physical turn until every host
        // operation has run; no earlier operation may grant the queued reader.
        for (int order = 0; order < 24; order++) {
            int[] operations = {0, 1, 2, 3};
            int permutation = order;
            for (int n = 4; n > 1; n--) {
                int next = permutation % n;
                permutation /= n;
                int displaced = operations[n - 1];
                operations[n - 1] = operations[next];
                operations[next] = displaced;
            }
            CriticalGate gate = new CriticalGate();
            OresFuture<Void> foreignCompletion = new OresFuture<>();
            java.util.concurrent.CountDownLatch entered =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch leave =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<CriticalGate.Ticket> grant =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread stopping = null;
            try (ActorRuntime runtime = new ActorRuntime()) {
                try {
                    var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                        try {
                            var writer = gate.newTicket();
                            grant.set(writer);
                            assertTrue(writer.enterAsync().isDone());
                            writer.releaseOnCompletion(foreignCompletion);
                            entered.countDown();
                            assertTrue(leave.await(5,
                                    java.util.concurrent.TimeUnit.SECONDS));
                            assertTrue(writer.isHeld(),
                                    "foreign release must not revoke a physical guest grant");
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            entered.countDown();
                        }
                    });
                    actor.send("hold");
                    assertTrue(entered.await(5,
                            java.util.concurrent.TimeUnit.SECONDS));
                    assertNotNull(grant.get());
                    var queued = gate.newTicket(CriticalGate.Mode.READ);
                    var pending = queued.enterAsync();
                    assertFalse(pending.isDone());

                    for (int action : operations) {
                        switch (action) {
                            case 0 -> grant.get().close();
                            case 1 -> foreignCompletion.completeFromRuntime(null);
                            case 2 -> gate.closeAll();
                            case 3 -> {
                                stopping = Thread.ofVirtual().start(() -> {
                                    try { runtime.stop(actor); }
                                    catch (Throwable error) {
                                        failure.compareAndSet(null, error);
                                    }
                                });
                                long deadline = System.nanoTime()
                                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                                while (runtime.isAlive(actor)
                                        && System.nanoTime() < deadline) {
                                    Thread.onSpinWait();
                                }
                                assertFalse(runtime.isAlive(actor));
                            }
                            default -> throw new AssertionError("unexpected operation");
                        }
                        assertTrue(grant.get().isHeld(),
                                "release order " + order + " revoked live guest writer");
                        assertFalse(queued.isHeld(),
                                "release order " + order + " admitted reader early");
                    }
                    assertEquals(0, gate.queuedCount());
                    assertTrue(pending.isDone());
                    leave.countDown();
                    if (stopping != null) stopping.join(5_000);
                    assertNull(failure.get(), "release order " + order);
                    long deadline = System.nanoTime()
                            + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while ((grant.get().isHeld()
                            || runtime.trackedActorResourceCount(actor) != 0)
                            && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertFalse(grant.get().isHeld());
                    assertFalse(queued.isHeld());
                    assertEquals(0, runtime.trackedActorResourceCount(actor));
                } finally {
                    leave.countDown();
                    if (stopping != null) stopping.join(5_000);
                    gate.closeAll();
                }
            }
            assertNull(failure.get(), "release order " + order);
            assertFalse(gate.held());
            assertEquals(0, CriticalGate.waitingLogicalDomains());
        }
    }

    @Test
    void anotherMessageOnSameActorCannotReleasePriorMessageGrantInline() throws Exception {
        CriticalGate gate = new CriticalGate();
        java.util.concurrent.CountDownLatch firstEntered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch secondEntered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch leaveSecond =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<CriticalGate.Ticket> grant =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        if ("first".equals(message)) {
                            var writer = gate.newTicket();
                            grant.set(writer);
                            assertTrue(writer.enterAsync().isDone());
                            firstEntered.countDown();
                        } else if ("second".equals(message)) {
                            // Both envelopes have the same actor-wide fallback
                            // domain, but not the same logical invocation.
                            grant.get().close();
                            assertTrue(grant.get().isHeld(),
                                    "a later message cannot impersonate ticket owner");
                            secondEntered.countDown();
                            assertTrue(leaveSecond.await(5,
                                    java.util.concurrent.TimeUnit.SECONDS));
                            assertTrue(grant.get().isHeld(),
                                    "foreign message release must await carrier exit");
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        if ("first".equals(message)) firstEntered.countDown();
                        if ("second".equals(message)) secondEntered.countDown();
                    }
                });
                actor.send("first");
                assertTrue(firstEntered.await(5,
                        java.util.concurrent.TimeUnit.SECONDS));
                actor.send("second");
                assertTrue(secondEntered.await(5,
                        java.util.concurrent.TimeUnit.SECONDS));
                assertNull(failure.get());
                assertTrue(grant.get().isHeld());
                leaveSecond.countDown();
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while ((grant.get().isHeld()
                        || runtime.trackedActorResourceCount(actor) != 0)
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(grant.get().isHeld(),
                        "deferred release must retire after actor turn exit");
                assertEquals(0, runtime.trackedActorResourceCount(actor));
                runtime.stop(actor);
            } finally {
                leaveSecond.countDown();
            }
        }
        assertNull(failure.get());
        assertFalse(gate.hasWriter());
        assertEquals(0, CriticalGate.waitingLogicalDomains());
    }

    @Test
    void foreignTicketCloseCannotReleaseGrantedWriterDuringGuestTurn() throws Exception {
        CriticalGate gate = new CriticalGate();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<CriticalGate.Ticket> owned =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        CriticalGate.Ticket writer = gate.newTicket();
                        owned.set(writer);
                        assertTrue(writer.enterAsync().isDone());
                        entered.countDown();
                        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        assertTrue(writer.isHeld(),
                                "foreign close must not revoke a live actor writer");
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        entered.countDown();
                    }
                });
                actor.send("hold");
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertNull(failure.get());
                var reader = gate.newTicket(CriticalGate.Mode.READ);
                var admission = reader.enterAsync();
                assertFalse(admission.isDone());
                Thread closer = Thread.ofVirtual().start(owned.get()::close);
                closer.join(5_000);
                assertFalse(closer.isAlive(), "foreign close may not block an actor");
                assertTrue(owned.get().isHeld());
                assertFalse(admission.isDone(), "foreign close must not admit a reader early");
                assertEquals(1, runtime.trackedActorResourceCount(actor));
                release.countDown();
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while ((!admission.isDone()
                        || runtime.trackedActorResourceCount(actor) != 0)
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                assertTrue(admission.isDone(), "reader must wake after guest carrier exit");
                assertTrue(reader.isHeld());
                assertFalse(owned.get().isHeld());
                assertEquals(0, runtime.trackedActorResourceCount(actor));
                reader.close();
                runtime.stop(actor);
            } finally {
                release.countDown();
            }
        }
        assertNull(failure.get());
        assertEquals(0, gate.queuedCount());
        assertFalse(gate.held());
    }

    @Test
    void guestOwnedExplicitTicketCloseReleasesAtItsLexicalBoundary() throws Exception {
        CriticalGate gate = new CriticalGate();
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch exitTurn = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            try {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        var writer = gate.newTicket();
                        assertTrue(writer.enterAsync().isDone());
                        writer.close();
                        assertFalse(writer.isHeld(), "owning guest may end its lexical scope");
                        released.countDown();
                        assertTrue(exitTurn.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        released.countDown();
                    }
                });
                actor.send("close");
                assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertNull(failure.get());
                var next = gate.newTicket(CriticalGate.Mode.READ);
                assertTrue(next.enterAsync().isDone(),
                        "logical owner release should not wait for whole actor turn");
                assertTrue(next.isHeld());
                next.close();
                exitTurn.countDown();
                runtime.stop(actor);
            } finally {
                exitTurn.countDown();
            }
        }
        assertNull(failure.get());
    }

    @Test
    void domainCloseRunsOverOneHundredThousandAdmissionReleaseTransitions() {
        // 20,000 independently torn-down domains * at least six state
        // transitions each: grant, queue, queue, cancel/release, close, settle.
        // Alternate operation ordering to replay near-grant/close edges.
        for (int n = 0; n < 20_000; n++) {
            CriticalGate gate = new CriticalGate();
            var writer = gate.newTicket(CriticalGate.Mode.WRITE);
            var reader = gate.newTicket(CriticalGate.Mode.READ);
            var last = gate.newTicket(CriticalGate.Mode.WRITE);
            assertTrue(writer.enterAsync().isDone());
            var readAdmission = reader.enterAsync();
            var lastAdmission = last.enterAsync();
            assertFalse(readAdmission.isDone());
            switch (n & 3) {
                case 0 -> {
                    readAdmission.cancel(false);
                    gate.closeAll();
                }
                case 1 -> {
                    gate.closeAll();
                    readAdmission.cancel(false);
                }
                case 2 -> {
                    writer.close();
                    gate.closeAll();
                }
                default -> {
                    lastAdmission.cancel(false);
                    writer.close();
                    gate.closeAll();
                }
            }
            assertTrue(readAdmission.isDone());
            assertTrue(lastAdmission.isDone());
            assertFalse(writer.isHeld());
            assertFalse(reader.isHeld());
            assertFalse(last.isHeld());
            assertEquals(0, gate.queuedCount());
            assertEquals(0, gate.activeReaderCount());
            assertFalse(gate.hasWriter());
            gate.closeAll(); // idempotence
        }
        assertEquals(0, CriticalGate.waitingLogicalDomains());
    }

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
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!runtime.isActorPhysicalLaneQuiescent(actor)
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(runtime.isActorPhysicalLaneQuiescent(actor),
                    "this test verifies immediate close only after quiescence");
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
