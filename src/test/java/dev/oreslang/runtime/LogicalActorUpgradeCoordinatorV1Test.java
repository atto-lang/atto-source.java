package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalActorUpgradeCoordinatorV1Test {
    private static String hash(char c) { return String.valueOf(c).repeat(64); }
    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(hash(code),hash('1'),hash('2'),hash('3'));
    }
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Object mailboxMonitor(LogicalActorProxyMailboxV1 mailbox)
            throws Exception {
        var field = LogicalActorProxyMailboxV1.class.getDeclaredField("lock");
        field.setAccessible(true);
        return field.get(mailbox);
    }

    private static void awaitBlocked(Thread thread, String message)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive()
                && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(thread.isAlive() && thread.getState() == Thread.State.BLOCKED,
                message + " (state=" + thread.getState() + ")");
    }

    @Test
    void cleanCloseRevokesCoordinatorWithoutClosingUnderlyingActorOrMailbox() {
        try (var gate = new ActorUpgradeEpochGate(35, image('a'));
             var proxy = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
            coordinator.close();
            coordinator.close();
            assertThrows(IllegalStateException.class,
                    () -> coordinator.prepare(image('b')),
                    "a retired coordinator must not regain CONTROL publication authority");
            assertEquals(1L, gate.epoch());
            assertFalse(gate.frozen());
            assertFalse(proxy.snapshot().dispatchFrozen());
            var accepted = proxy.admit(bytes("still-open"));
            assertEquals(accepted.messageSequence(),
                    proxy.pollDispatch().messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(proxy, accepted.messageSequence());
        }
    }

    @Test
    void cleanCloseVersusPrepareIsLinearizedWithoutOrphanedAuthority()
            throws Exception {
        for (int attempt = 0; attempt < 32; attempt++) {
            try (var gate = new ActorUpgradeEpochGate(200 + attempt, image('a'));
                 var proxy = new LogicalActorProxyMailboxV1(2, 16, 32)) {
                var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
                var start = new java.util.concurrent.CountDownLatch(1);
                var tx = new java.util.concurrent.atomic.AtomicReference<
                        LogicalActorUpgradeCoordinatorV1.Transaction>();
                var closeWon = new java.util.concurrent.atomic.AtomicBoolean();
                var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread prep = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        tx.set(coordinator.prepare(image('b')));
                    } catch (IllegalStateException expectedClosed) {
                        // Close may linearize before preparation.
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                Thread closer = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        coordinator.close();
                        closeWon.set(true);
                    } catch (IllegalStateException expectedActive) {
                        // An active transaction must be resolved explicitly.
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                start.countDown();
                prep.join(5000);
                closer.join(5000);
                assertFalse(prep.isAlive() || closer.isAlive());
                assertNull(unexpected.get());
                if (closeWon.get()) {
                    assertNull(tx.get());
                    assertThrows(IllegalStateException.class,
                            () -> coordinator.prepare(image('c')));
                } else {
                    assertNotNull(tx.get());
                    tx.get().rollback();
                    coordinator.close();
                    assertThrows(IllegalStateException.class,
                            () -> coordinator.prepare(image('c')));
                }
                assertFalse(proxy.snapshot().dispatchFrozen());
                assertFalse(gate.frozen());
            }
        }
    }

    @Test
    void freezesNewDispatchUntilOldRouteMessagesAndSuspendedWorkDrain() {
        try (var gate = new ActorUpgradeEpochGate(7,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(8,64,512);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var first = proxy.admit(bytes("old"));
            assertEquals(first.messageSequence(),proxy.pollDispatch().messageSequence());
            var taskPin = gate.pin(); // a suspended continuation still pins old image
            var transaction = coordinator.prepare(image('b'));
            assertNull(proxy.pollDispatch());
            var queued = proxy.admit(bytes("new"));
            assertEquals(2L,queued.messageSequence());
            assertFalse(transaction.commit());
            LogicalActorProxyMailboxTestAccess.complete(proxy, first.messageSequence());
            assertFalse(transaction.commit(), "Task must physically exit before upgrade");
            taskPin.close();
            assertTrue(transaction.drained());
            assertTrue(transaction.commit());
            assertEquals(2L,gate.epoch());
            assertEquals(2L,proxy.snapshot().routeEpoch());
            assertEquals(2L,proxy.pollDispatch().messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(proxy, 2L);
        }
    }

    @Test
    void rollbackRestoresOldRouteWithoutDroppingQueuedMessages() {
        try (var gate = new ActorUpgradeEpochGate(8,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(4,64,256);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var tx = coordinator.prepare(image('b'));
            proxy.admit(bytes("held"));
            assertNull(proxy.pollDispatch());
            tx.rollback();
            assertEquals(image('a'),gate.image());
            assertEquals(1L,gate.epoch());
            assertEquals(1L,proxy.snapshot().routeEpoch());
            assertEquals(1L,proxy.pollDispatch().messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(proxy, 1L);
            assertThrows(IllegalStateException.class,tx::commit);
        }
    }

    @Test
    void incompatibleCodeDoesNotLeaveFrozenMailbox() {
        try (var gate = new ActorUpgradeEpochGate(9,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(4,64,256);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var incompatible = new ActorUpgradeEpochGate.Image(
                    hash('b'),hash('9'),hash('2'),hash('3'));
            assertThrows(SecurityException.class,()->coordinator.prepare(incompatible));
            assertFalse(proxy.snapshot().dispatchFrozen());
            assertFalse(coordinator.quarantined());
            assertEquals(1L,gate.epoch());
            assertNull(proxy.pollDispatch());
        }
    }

    @Test
    void staleForeignOrMismatchedEpochRejected() {
        try (var gate = new ActorUpgradeEpochGate(12,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(4,64,256)) {
            var tx = proxy.freezeDispatchForUpgrade();
            tx.commit();
            assertThrows(IllegalArgumentException.class,
                    () -> new LogicalActorUpgradeCoordinatorV1(gate,proxy));
        }
    }

    @Test
    void noSecondUpgradeMayStartWhileFirstIsFrozen() {
        try (var gate = new ActorUpgradeEpochGate(20,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(8,64,512);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var first = coordinator.prepare(image('b'));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.prepare(image('c')));
            assertThrows(IllegalStateException.class, coordinator::close);
            first.rollback();
            assertFalse(proxy.snapshot().dispatchFrozen());
            var second = coordinator.prepare(image('c'));
            assertTrue(second.commit());
            assertEquals(image('c'),gate.image());
            assertEquals(2L,proxy.snapshot().routeEpoch());
        }
    }

    @Test
    void externalClosureWhileFrozenCannotBeMistakenForCommittedRoute() {
        var gate = new ActorUpgradeEpochGate(21,image('a'));
        var proxy = new LogicalActorProxyMailboxV1(8,64,512);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy);
        var tx = coordinator.prepare(image('b'));
        proxy.close();
        assertThrows(IllegalStateException.class,tx::commit);
        assertEquals(image('a'),gate.image());
        assertEquals(1L,gate.epoch());
        assertThrows(IllegalStateException.class,coordinator::close);
        assertThrows(IllegalStateException.class,tx::rollback);
        // An unresolved/closed mailbox is not proof of a safe old-route
        // restart; the supervisor must quarantine the actor incarnation.
        gate.close();
    }


    @Test
    void codeGateRetiredBeforePrepareLeavesMailboxFrozenAndCoordinatorQuarantined() {
        var gate = new ActorUpgradeEpochGate(211, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        gate.close();
        proxy.admit(bytes("must-not-dispatch"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.prepare(image('b')));
        assertTrue(coordinator.quarantined());
        assertTrue(proxy.snapshot().dispatchFrozen(),
                "retired code authority must never be followed by old-route thaw");
        assertEquals(1, proxy.snapshot().queuedMessages());
        assertNull(proxy.pollDispatch(),
                "quarantine preserves accepted bytes without dispatching retired code");
        assertThrows(IllegalStateException.class, coordinator::close);
        proxy.close();
    }

    @Test
    void codeGateCloseCannotInterleaveWithPreparationEpochCheckAndFreeze()
            throws Exception {
        var gate = new ActorUpgradeEpochGate(212, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var prepared =
                new java.util.concurrent.atomic.AtomicReference<
                        LogicalActorUpgradeCoordinatorV1.Transaction>();
        var prepareFailure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var closeFailure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread preparer;
        Thread closer;

        // Hold the mailbox monitor so prepare reaches its cross-ledger
        // observation while the code-gate monitor is still held. A concurrent
        // close must block on that gate monitor, not retire code in between
        // the epoch check and dispatch freeze.
        synchronized (mailboxMonitor(proxy)) {
            preparer = Thread.ofPlatform().start(() -> {
                try {
                    prepared.set(coordinator.prepare(image('b')));
                } catch (Throwable failure) {
                    prepareFailure.set(failure);
                }
            });
            awaitBlocked(preparer,
                    "prepare must block on the mailbox while owning code authority");

            closer = Thread.ofPlatform().start(() -> {
                try {
                    gate.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            });
            awaitBlocked(closer,
                    "code-gate close must serialize behind preparation");
        }

        preparer.join(5000);
        closer.join(5000);
        assertFalse(preparer.isAlive() || closer.isAlive());
        assertNull(prepareFailure.get());
        assertNull(closeFailure.get());
        var transaction = prepared.get();
        assertNotNull(transaction);
        assertTrue(proxy.snapshot().dispatchFrozen());

        // Close wins only after prepare has established the freeze/candidate.
        // Resolving that now-retired candidate must quarantine, never thaw.
        assertThrows(IllegalStateException.class, transaction::rollback);
        assertTrue(coordinator.quarantined());
        assertTrue(proxy.snapshot().dispatchFrozen());
        proxy.close();
    }

    @Test
    void closedCodeGateDuringPublicationQuarantinesInsteadOfThawingOldRoute() {
        var gate = new ActorUpgradeEpochGate(23, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var transaction = coordinator.prepare(image('b'));
        proxy.admit(bytes("held"));
        gate.close(); // an independent physical retirement / supervisor race
        assertThrows(IllegalStateException.class, transaction::commit);
        assertTrue(coordinator.quarantined());
        assertTrue(proxy.snapshot().dispatchFrozen());
        assertEquals(1L, gate.epoch());
        assertEquals(1L, proxy.snapshot().routeEpoch());
        assertThrows(IllegalStateException.class, transaction::rollback);
        assertThrows(IllegalStateException.class, coordinator::close);
        // Only the trusted supervisor may tear down the quarantined proxy.
        proxy.close();
    }

    @Test
    void failedCandidateAbortMustNeverReopenOldRouteDispatch() {
        var gate = new ActorUpgradeEpochGate(24, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var transaction = coordinator.prepare(image('b'));
        proxy.admit(bytes("queued"));
        gate.close();
        assertThrows(IllegalStateException.class, transaction::rollback);
        assertTrue(coordinator.quarantined());
        assertTrue(proxy.snapshot().dispatchFrozen());
        assertTrue(proxy.snapshot().dispatchFrozen(), "failed abort cannot thaw");
        assertEquals(1, proxy.snapshot().queuedMessages());
        proxy.close();
    }

    @Test
    void failedDrainageObservationQuarantinesCoordinator() {
        var gate = new ActorUpgradeEpochGate(25, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var transaction = coordinator.prepare(image('b'));
        proxy.close(); // physical mailbox is no longer an authority
        assertThrows(IllegalStateException.class, transaction::drained);
        assertTrue(coordinator.quarantined());
        assertThrows(IllegalStateException.class, transaction::commit);
        gate.close();
    }

    @Test
    void racingCommitAndRollbackHaveExactlyOneWinner() throws Exception {
        for (int attempt = 0; attempt < 48; ++attempt) {
            try (var gate = new ActorUpgradeEpochGate(100 + attempt, image('a'));
                 var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
                 var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy)) {
                var transaction = coordinator.prepare(image('b'));
                var start = new java.util.concurrent.CountDownLatch(1);
                var wins = new java.util.concurrent.atomic.AtomicInteger();
                var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Runnable commit = () -> {
                    try {
                        start.await();
                        if (transaction.commit()) wins.incrementAndGet();
                    } catch (IllegalStateException expectedStale) {
                        // The competing supervisor operation already resolved
                        // this single transaction under the coordinator lock.
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                };
                Runnable rollback = () -> {
                    try {
                        start.await();
                        transaction.rollback();
                        wins.incrementAndGet();
                    } catch (IllegalStateException expectedStale) {
                        // Exactly one transaction resolver is authoritative.
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                };
                var a = Thread.ofPlatform().start(commit);
                var b = Thread.ofPlatform().start(rollback);
                start.countDown();
                a.join(5000);
                b.join(5000);
                assertFalse(a.isAlive() || b.isAlive());
                assertNull(unexpected.get());
                assertEquals(1, wins.get());
                assertFalse(coordinator.quarantined());
                assertFalse(proxy.snapshot().dispatchFrozen());
                assertEquals(gate.epoch(), proxy.snapshot().routeEpoch());
                if (gate.epoch() == 1L) assertEquals(image('a'), gate.image());
                else assertEquals(image('b'), gate.image());
            }
        }
    }

    @Test
    void failedAbiPreparationRetainsOldActorAndFutureAdmissionCapacity() {
        try (var gate = new ActorUpgradeEpochGate(22,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(3,64,192);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var bad = new ActorUpgradeEpochGate.Image(
                    hash('c'),hash('1'),hash('9'),hash('3'));
            assertThrows(SecurityException.class, () -> coordinator.prepare(bad));
            assertFalse(proxy.snapshot().dispatchFrozen());
            var accepted = proxy.admit(bytes("after-refusal"));
            assertEquals(1L,accepted.messageSequence());
            assertEquals(1L,proxy.pollDispatch().messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(proxy, 1L);
            assertEquals(0,proxy.snapshot().retainedMessages());
        }
    }
    @Test
    void externalCodeGateCloseDuringRollbackQuarantinesRatherThanThaws() {
        var gate = new ActorUpgradeEpochGate(310, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var tx = coordinator.prepare(image('b'));
        gate.close(); // Simulate an external supervisor retiring the candidate.
        assertThrows(IllegalStateException.class, tx::rollback);
        assertTrue(coordinator.quarantined());
        assertTrue(proxy.snapshot().dispatchFrozen());
        assertEquals(1L, proxy.snapshot().routeEpoch());
        assertThrows(IllegalStateException.class, coordinator::close);
        assertThrows(IllegalStateException.class, () -> coordinator.prepare(image('c')));
        proxy.close();
    }

    @Test
    void externalCodeGateCloseDuringCommitQuarantinesUnverifiedPublication() {
        var gate = new ActorUpgradeEpochGate(311, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var tx = coordinator.prepare(image('b'));
        gate.close();
        assertThrows(IllegalStateException.class, tx::commit);
        assertTrue(coordinator.quarantined());
        assertTrue(proxy.snapshot().dispatchFrozen());
        assertEquals(1L, proxy.snapshot().routeEpoch());
        assertEquals(1L, gate.epoch());
        assertThrows(IllegalStateException.class, tx::rollback);
        proxy.close();
    }

    @Test
    void lostMailboxDuringDrainCheckMustBeQuarantined() {
        var gate = new ActorUpgradeEpochGate(312, image('a'));
        var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
        var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy);
        var tx = coordinator.prepare(image('b'));
        proxy.close();
        assertThrows(IllegalStateException.class, tx::drained);
        assertTrue(coordinator.quarantined());
        assertThrows(IllegalStateException.class, coordinator::close);
        gate.close();
    }

    @Test
    void racingPrepareAndCommitCannotCreateTwoActiveTransactions() throws Exception {
        for (int i = 0; i < 32; ++i) {
            try (var gate = new ActorUpgradeEpochGate(400 + i, image('a'));
                 var proxy = new LogicalActorProxyMailboxV1(4, 64, 256);
                 var coordinator = new LogicalActorUpgradeCoordinatorV1(gate, proxy)) {
                var first = coordinator.prepare(image('b'));
                var start = new java.util.concurrent.CountDownLatch(1);
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    var committing = pool.submit(() -> {
                        start.await();
                        return first.commit();
                    });
                    var competing = pool.submit(() -> {
                        start.await();
                        try {
                            var second = coordinator.prepare(image('c'));
                            second.rollback();
                            return true;
                        } catch (IllegalStateException expectedBusy) {
                            return false;
                        }
                    });
                    start.countDown();
                    assertTrue(committing.get(5, java.util.concurrent.TimeUnit.SECONDS));
                    // A competitor may run before commit (rejected) or after
                    // commit (legally stages and rolls back). Neither is poison.
                    competing.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertFalse(coordinator.quarantined());
                    assertEquals(2L, gate.epoch());
                    assertEquals(2L, proxy.snapshot().routeEpoch());
                    assertEquals(image('b'), gate.image());
                    assertFalse(proxy.snapshot().dispatchFrozen());
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

}
