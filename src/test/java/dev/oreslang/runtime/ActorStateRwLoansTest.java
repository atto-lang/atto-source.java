package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateRwLoansTest {
    // Test-only actor owner identity: production code must get this from the
    // authenticated allocation registry, never a client-controlled value.
    private static final ExecutionContext.AllocationDomain OWNER =
            ExecutionContext.actorBound(7, 1, 1, 1, 9, 3, 11, 0, 0)
                    .allocationDomain();

    private static ExecutionContext actor(
            long execution, long slot, long executionGeneration) {
        return ExecutionContext.actorBound(
                7, execution,
                slot, executionGeneration,
                9, 3, 11,
                0, 0);
    }

    @Test
    void readersCoexistWriterIsExclusiveAndLateReaderCannotBypassWriter() {
        try (var loans = new ActorStateRwLoans(OWNER, 8, 1024)) {
            var r1ctx = actor(1001, 41, 1);
            var r2ctx = actor(1002, 42, 1);
            var wctx = actor(1003, 43, 1);
            var latectx = actor(1004, 44, 1);

            var r1 = loans.acquireReadAsync(r1ctx).join();
            var r2 = loans.acquireReadAsync(r2ctx).join();
            assertEquals(2, loans.activeReaderCount());
            assertEquals(0, loans.activeWriterCount());

            var writer = loans.acquireWriteAsync(wctx);
            var lateReader = loans.acquireReadAsync(latectx);
            assertFalse(writer.isDone());
            assertFalse(lateReader.isDone(),
                    "reader arriving after queued writer must not bypass it");

            r1.close();
            assertFalse(writer.isDone());
            r2.close();

            var w = writer.join();
            assertEquals(0, loans.activeReaderCount());
            assertEquals(1, loans.activeWriterCount());
            assertTrue(w.heldBy(wctx));
            assertFalse(lateReader.isDone());

            w.close();
            var late = lateReader.join();
            assertEquals(1, loans.activeReaderCount());
            assertTrue(late.heldBy(latectx));
            late.close();
        }
    }

    @Test
    void contiguousQueuedReadersPromoteAsCohortBeforeFollowingWriter() {
        try (var loans = new ActorStateRwLoans(OWNER, 8, 1024)) {
            var firstWriterCtx = actor(2001, 51, 1);
            var firstWriter = loans.acquireWriteAsync(firstWriterCtx).join();

            var r1 = loans.acquireReadAsync(actor(2002, 52, 1));
            var r2 = loans.acquireReadAsync(actor(2003, 53, 1));
            var writer = loans.acquireWriteAsync(actor(2004, 54, 1));
            assertFalse(r1.isDone());
            assertFalse(r2.isDone());
            assertFalse(writer.isDone());

            firstWriter.close();
            var a = r1.join();
            var b = r2.join();
            assertEquals(2, loans.activeReaderCount());
            assertFalse(writer.isDone());

            a.close();
            assertFalse(writer.isDone());
            b.close();
            var w = writer.join();
            assertEquals(1, loans.activeWriterCount());
            w.close();
        }
    }

    @Test
    void equivalentContextSurvivesCarrierMigrationButStaleGenerationDoesNot() throws Exception {
        try (var loans = new ActorStateRwLoans(OWNER, 4, 1024)) {
            var original = actor(3001, 61, 8);
            var rematerialized = actor(3001, 61, 8);
            var stale = actor(3001, 61, 7);
            var loan = loans.acquireWriteAsync(original).join();

            AtomicBoolean observed = new AtomicBoolean();
            Thread carrier = Thread.ofPlatform().start(
                    () -> observed.set(loan.heldBy(rematerialized)));
            carrier.join();

            assertTrue(observed.get());
            assertFalse(loan.heldBy(stale));
            assertFalse(loan.readyToSuspend(rematerialized),
                    "live protected access cannot create actor-visible suspension");
            loan.close();
            assertTrue(loan.readyToSuspend(rematerialized));
        }
    }

    @Test
    void foreignOrStaleActorIncarnationCannotEnterBoundRwArbiter() {
        try (var loans = new ActorStateRwLoans(OWNER, 4, 1024)) {
            var current = actor(3501, 66, 1);
            var loan = loans.acquireReadAsync(current).join();

            var foreignActor = ExecutionContext.actorBound(
                    7, 3502, 67, 1,
                    9, 4, 11,
                    0, 0);
            var staleActor = ExecutionContext.actorBound(
                    7, 3503, 68, 1,
                    9, 3, 10,
                    0, 0);
            assertTrue(loans.acquireReadAsync(foreignActor).isCompletedExceptionally());
            assertTrue(loans.acquireWriteAsync(staleActor).isCompletedExceptionally());
            assertEquals(1, loans.activeReaderCount());
            loan.close();
        }
    }

    @Test
    void sameLogicalExecutionCannotRecursivelyAcquireOrChangeMode() {
        try (var loans = new ActorStateRwLoans(OWNER, 4, 1024)) {
            var context = actor(4001, 71, 1);
            var equivalent = actor(4001, 71, 1);
            var read = loans.acquireReadAsync(context).join();
            assertTrue(loans.acquireReadAsync(equivalent).isCompletedExceptionally());
            assertTrue(loans.acquireWriteAsync(equivalent).isCompletedExceptionally());
            read.close();
        }
    }

    @Test
    void cancellingQueuedWriterLetsFollowingReaderJoinLiveReaders() {
        try (var loans = new ActorStateRwLoans(OWNER, 8, 1024)) {
            var first = loans.acquireReadAsync(actor(5001, 81, 1)).join();
            var writer = loans.acquireWriteAsync(actor(5002, 82, 1));
            var reader = loans.acquireReadAsync(actor(5003, 83, 1));

            assertFalse(reader.isDone());
            assertTrue(writer.cancel(false));
            var promoted = reader.join();
            assertEquals(2, loans.activeReaderCount());

            promoted.close();
            first.close();
        }
    }

    @Test
    void unboundNonActorAndQueueOverflowFailClosed() {
        try (var loans = new ActorStateRwLoans(OWNER, 1, 1024)) {
            assertTrue(loans.acquireReadAsync(
                    ExecutionContext.task(7, 6001)).isCompletedExceptionally());
            assertTrue(loans.acquireReadAsync(
                    ExecutionContext.actor(7, 6002, 9, 3, 11, 0, 0))
                    .isCompletedExceptionally());

            var active = loans.acquireWriteAsync(actor(6003, 91, 1)).join();
            var queued = loans.acquireReadAsync(actor(6004, 92, 1));
            assertTrue(loans.acquireReadAsync(actor(6005, 93, 1))
                    .isCompletedExceptionally());
            active.close();
            queued.join().close();
        }
    }

    @Test
    void logicalRetirementFencesStateButDoesNotReleasePhysicalSuspensionBarrier() {
        var loans = new ActorStateRwLoans(OWNER, 2, 1024);
        var execution = actor(7101, 111, 5);
        var active = loans.acquireWriteAsync(execution).join();

        assertFalse(active.readyToSuspend(execution));
        loans.close();
        assertFalse(active.heldBy(execution),
                "logical close must immediately revoke state access");
        assertFalse(active.readyToSuspend(execution),
                "logical retirement cannot permit suspension while the physical loan is live");
        assertFalse(loans.physicallyRetired(),
                "actor arena cannot retire while the physical state loan remains held");

        active.close();
        assertTrue(active.readyToSuspend(execution));
        assertTrue(loans.physicallyRetired());
    }

    @Test
    void foreignAndStaleExecutionsCannotBypassLoanSuspensionBarrier() {
        try (var loans = new ActorStateRwLoans(OWNER, 2, 1024)) {
            var owner = actor(7201, 121, 9);
            var sameExecutionOnAnotherCarrier = actor(7201, 121, 9);
            var staleSlot = actor(7201, 121, 8);
            var foreign = actor(7202, 122, 9);
            var active = loans.acquireReadAsync(owner).join();

            assertFalse(active.readyToSuspend(sameExecutionOnAnotherCarrier));
            assertFalse(active.readyToSuspend(staleSlot));
            assertFalse(active.readyToSuspend(foreign));
            active.close();

            assertTrue(active.readyToSuspend(sameExecutionOnAnotherCarrier));
            assertFalse(active.readyToSuspend(staleSlot),
                    "stale generation cannot certify safe suspension after physical release");
            assertFalse(active.readyToSuspend(foreign));
        }
    }

    @Test
    void oldReleasedLoanCannotCertifySuspensionAfterSameExecutionReacquires() {
        try (var loans = new ActorStateRwLoans(OWNER, 2, 1024)) {
            var owner = actor(7301, 131, 2);
            var first = loans.acquireReadAsync(owner).join();
            first.close();
            assertTrue(first.readyToSuspend(owner));

            var second = loans.acquireWriteAsync(owner).join();
            assertFalse(first.readyToSuspend(owner),
                    "old released handle cannot authorize suspension while a new loan is live");
            assertFalse(second.readyToSuspend(owner));

            second.close();
            assertTrue(first.readyToSuspend(owner));
            assertTrue(second.readyToSuspend(owner));
        }
    }

    @Test
    void retirementWakeupFiresOnceOnlyAfterAllPhysicalReadersRelease() {
        var notifications = new AtomicInteger();
        var loans = new ActorStateRwLoans(OWNER, 3, 1024, notifications::incrementAndGet);
        var first = loans.acquireReadAsync(actor(7401, 141, 1)).join();
        var second = loans.acquireReadAsync(actor(7402, 142, 1)).join();
        var queued = loans.acquireWriteAsync(actor(7403, 143, 1));

        loans.close();
        assertTrue(queued.isCompletedExceptionally());
        assertFalse(loans.physicallyRetired());
        assertEquals(0, notifications.get());

        first.close();
        assertFalse(loans.physicallyRetired());
        assertEquals(0, notifications.get());

        second.close();
        assertTrue(loans.physicallyRetired());
        assertEquals(1, notifications.get());
        second.close();
        first.close();
        loans.close();
        assertEquals(1, notifications.get(),
                "retirement notification must be delivered at most once");
    }

    @Test
    void closeFailsPendingAndFencesActiveAuthority() {
        var loans = new ActorStateRwLoans(OWNER, 2, 1024);
        var activeContext = actor(7001, 101, 1);
        var active = loans.acquireWriteAsync(activeContext).join();
        var pending = loans.acquireReadAsync(actor(7002, 102, 1));
        loans.close();

        assertTrue(pending.isCompletedExceptionally());
        assertFalse(active.heldBy(activeContext),
                "arbiter close immediately fences state access");
        active.close();
        assertEquals(0, loans.activeWriterCount());
    }

    @Test
    void foreignActorCannotClaimRegionEvenWhenItIsUnused() {
        try (var loans = new ActorStateRwLoans(OWNER, 4, 2)) {
            var foreignActor = ExecutionContext.actorBound(
                    7, 9991, 901, 1, 9, 4, 11, 0, 0);
            var staleIncarnation = ExecutionContext.actorBound(
                    7, 9992, 902, 1, 9, 3, 10, 0, 0);
            assertTrue(loans.acquireReadAsync(foreignActor).isCompletedExceptionally(),
                    "a foreign first caller must not capture region ownership");
            assertTrue(loans.acquireWriteAsync(staleIncarnation).isCompletedExceptionally(),
                    "a stale actor generation must not capture an unused region");
            assertEquals(0, loans.activeReaderCount());
            assertEquals(0, loans.activeWriterCount());
            loans.acquireReadAsync(actor(9993, 903, 1)).join().close();
        }
    }

    @Test
    void readerCapacityIsBoundedAndFifoWriterBarrierIsPreserved() {
        try (var loans = new ActorStateRwLoans(OWNER, 4, 1)) {
            var first = loans.acquireReadAsync(actor(8001, 201, 1)).join();
            var second = loans.acquireReadAsync(actor(8002, 202, 1));
            var writer = loans.acquireWriteAsync(actor(8003, 203, 1));
            var lateReader = loans.acquireReadAsync(actor(8004, 204, 1));
            assertEquals(1, loans.activeReaderCount());
            assertEquals(3, loans.pendingCount());
            assertFalse(second.isDone());
            assertFalse(writer.isDone());
            assertFalse(lateReader.isDone());

            first.close();
            var secondLoan = second.join();
            assertEquals(1, loans.activeReaderCount());
            assertFalse(writer.isDone());
            assertFalse(lateReader.isDone());
            secondLoan.close();
            var writerLoan = writer.join();
            assertEquals(1, loans.activeWriterCount());
            assertFalse(lateReader.isDone(),
                    "a new reader may not overtake an older writer");
            writerLoan.close();
            lateReader.join().close();
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test
    void overflowAtReaderCapFailsWithoutLeakingReservations() {
        try (var loans = new ActorStateRwLoans(OWNER, 1, 1)) {
            var firstContext = actor(8101, 211, 1);
            var queuedContext = actor(8102, 212, 1);
            var overflowContext = actor(8103, 213, 1);
            var first = loans.acquireReadAsync(firstContext).join();
            var queued = loans.acquireReadAsync(queuedContext);
            assertFalse(queued.isDone());
            assertTrue(loans.acquireReadAsync(overflowContext).isCompletedExceptionally(),
                    "bounded queue must fail closed rather than allocate forever");
            assertTrue(loans.noOutstandingLoanFor(overflowContext));
            assertFalse(loans.noOutstandingLoanFor(queuedContext));
            first.close();
            queued.join().close();
            assertTrue(loans.noOutstandingLoanFor(queuedContext));
        }
    }

    @Test
    void cancelledQueuedReaderDoesNotStealCapacityOrBlockWriter() {
        try (var loans = new ActorStateRwLoans(OWNER, 3, 1)) {
            var held = loans.acquireReadAsync(actor(8201, 221, 1)).join();
            var cancelledContext = actor(8202, 222, 1);
            var pendingRead = loans.acquireReadAsync(cancelledContext);
            var writer = loans.acquireWriteAsync(actor(8203, 223, 1));
            assertTrue(pendingRead.cancel(false));
            assertTrue(loans.noOutstandingLoanFor(cancelledContext));
            assertEquals(1, loans.pendingCount());
            held.close();
            writer.join().close();
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test
    void constructionRejectsUnboundNonActorAndUnsafeReaderLimits() {
        assertThrows(NullPointerException.class,
                () -> new ActorStateRwLoans(null, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(
                        ExecutionContext.AllocationDomain.none(), 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(
                        new ExecutionContext.AllocationDomain(
                                ExecutionContext.AllocationKind.TASK, 9, 3, 11),
                        1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(OWNER, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(OWNER, 1, 65537));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(OWNER, -1, 1));
    }

    @Test
    void observedLoanReleaseCannotCertifyReacquiredExecution() {
        try (var loans = new ActorStateRwLoans(OWNER, 2, 1)) {
            var original = actor(8301, 231, 1);
            var first = loans.acquireReadAsync(original).join();
            assertFalse(loans.noOutstandingLoanFor(original));
            first.close();
            assertTrue(loans.noOutstandingLoanFor(original));
            var second = loans.acquireWriteAsync(original).join();
            assertFalse(loans.noOutstandingLoanFor(original),
                    "old released loan is not proof against a new reservation");
            second.close();
            assertTrue(loans.noOutstandingLoanFor(original));
            assertFalse(loans.noOutstandingLoanFor(
                    ExecutionContext.actorBound(7, 8301, 231, 1, 9, 3, 12, 0, 0)));
            assertFalse(loans.noOutstandingLoanFor(ExecutionContext.task(7, 8301)));
        }
    }


    @Test
    @SuppressWarnings("unchecked")
    void publisherDrainingAfterLastLoanReleaseDeliversRetirementOnce() throws Exception {
        var wakeups = new AtomicInteger();
        var loans = new ActorStateRwLoans(OWNER, 4, 1, wakeups::incrementAndGet);
        var active = loans.acquireReadAsync(actor(8401, 241, 1)).join();
        var enteredPublication = new CountDownLatch(1);
        var allowPublicationToDrain = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        // Deterministically interleave an already-publishing completion and
        // final actor-loan closure. No guest code uses reflection.
        var monitorField = ActorStateRwLoans.class.getDeclaredField("monitor");
        monitorField.setAccessible(true);
        var queueField = ActorStateRwLoans.class.getDeclaredField("completions");
        queueField.setAccessible(true);
        var publisherMethod = ActorStateRwLoans.class.getDeclaredMethod("publish");
        publisherMethod.setAccessible(true);

        var monitor = monitorField.get(loans);
        synchronized (monitor) {
            var completions = (ArrayDeque<Runnable>) queueField.get(loans);
            completions.addLast(() -> {
                enteredPublication.countDown();
                try {
                    if (!allowPublicationToDrain.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("publisher did not receive release signal");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("publisher interrupted", e);
                }
            });
        }
        var publisher = Thread.ofPlatform().start(() -> {
            try {
                publisherMethod.invoke(loans);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        try {
            assertTrue(enteredPublication.await(5, TimeUnit.SECONDS),
                    "test publisher must enter its active callback");
            loans.close();
            active.close();
            assertFalse(loans.physicallyRetired(),
                    "physical retirement waits for in-flight publisher");
            assertEquals(0, wakeups.get());
        } finally {
            allowPublicationToDrain.countDown();
            publisher.join(5000);
        }
        assertFalse(publisher.isAlive());
        assertNull(failure.get(), "publisher must not throw");
        assertTrue(loans.physicallyRetired());
        assertEquals(1, wakeups.get(),
                "publisher drains to zero and must notify exactly once");
    }


    @Test
    void stopWaitsForQueuedFutureHostNotificationDrain() throws Exception {
        var retirementWakeups = new AtomicInteger();
        var loans = new ActorStateRwLoans(OWNER, 2, 1,
                retirementWakeups::incrementAndGet);
        var held = loans.acquireWriteAsync(actor(8501, 251, 1)).join();
        var queued = loans.acquireReadAsync(actor(8502, 252, 1));
        var notificationEntered = new CountDownLatch(1);
        var allowNotificationExit = new CountDownLatch(1);
        var closerFailure = new AtomicReference<Throwable>();
        var callbackFailure = new AtomicReference<Throwable>();

        queued.whenCompleteRuntime((ignored, failure) -> {
            notificationEntered.countDown();
            try {
                if (!allowNotificationExit.await(5, TimeUnit.SECONDS)) {
                    callbackFailure.set(new AssertionError("host notification timed out"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callbackFailure.set(e);
            }
        });
        var closer = Thread.ofPlatform().start(() -> {
            try {
                loans.close();
            } catch (Throwable failure) {
                closerFailure.set(failure);
            }
        });
        try {
            assertTrue(notificationEntered.await(5, TimeUnit.SECONDS),
                    "STOP should deliver a terminal notification to queued host listener");
            assertTrue(queued.isCompletedExceptionally());
            held.close();
            assertFalse(loans.physicallyRetired(),
                    "reclamation must wait for terminal Future callbacks to return");
            assertEquals(0, retirementWakeups.get());
        } finally {
            allowNotificationExit.countDown();
            closer.join(5000);
        }
        assertFalse(closer.isAlive());
        assertNull(callbackFailure.get());
        assertNull(closerFailure.get());
        assertTrue(loans.physicallyRetired());
        assertEquals(1, retirementWakeups.get());
    }

    @Test
    void cancelledPendingFutureCannotMakeStopPhysicallyRetireEarly() throws Exception {
        var retirementWakeups = new AtomicInteger();
        var loans = new ActorStateRwLoans(OWNER, 2, 1,
                retirementWakeups::incrementAndGet);
        var held = loans.acquireWriteAsync(actor(8601, 261, 1)).join();
        var queued = loans.acquireReadAsync(actor(8602, 262, 1));
        var cancellationCallbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var cancellationFailure = new AtomicReference<Throwable>();
        var callbackFailure = new AtomicReference<Throwable>();
        var cancellationWon = new AtomicBoolean();
        queued.whenCompleteRuntime((ignored, failure) -> {
            cancellationCallbackEntered.countDown();
            try {
                if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                    callbackFailure.set(new AssertionError("cancel callback timed out"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callbackFailure.set(e);
            }
        });
        var canceller = Thread.ofPlatform().start(() -> {
            try {
                cancellationWon.set(queued.cancel(false));
            } catch (Throwable failure) {
                cancellationFailure.set(failure);
            }
        });
        try {
            assertTrue(cancellationCallbackEntered.await(5, TimeUnit.SECONDS));
            loans.close();
            held.close();
            assertFalse(loans.physicallyRetired(),
                    "cancelled Future is removed from queue but notifications remain live");
            assertEquals(0, retirementWakeups.get());
        } finally {
            releaseCallback.countDown();
            canceller.join(5000);
        }
        assertFalse(canceller.isAlive());
        assertTrue(cancellationWon.get());
        assertNull(cancellationFailure.get());
        assertNull(callbackFailure.get());
        assertTrue(loans.physicallyRetired());
        assertEquals(1, retirementWakeups.get());
    }

    @Test
    void pendingCapacityHasFiniteRuntimeCeiling() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(OWNER, 8193, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateRwLoans(OWNER, Integer.MAX_VALUE, 1));

        try (var loans = new ActorStateRwLoans(OWNER, 0, 1)) {
            var held = loans.acquireWriteAsync(actor(8701, 271, 1)).join();
            assertTrue(loans.acquireReadAsync(actor(8702, 272, 1))
                    .isCompletedExceptionally(),
                    "zero pending capacity must reject a contending reader");
            held.close();
            loans.acquireReadAsync(actor(8703, 273, 1)).join().close();
        }
    }


    @Test
    void stoppedActorNeverReceivesSuspensionClearanceFromAnEmptyLoanLedger() {
        var loans = new ActorStateRwLoans(OWNER, 2, 1);
        var ownerExecution = actor(8801, 281, 1);
        assertTrue(loans.noOutstandingLoanFor(ownerExecution));
        loans.close();
        assertTrue(loans.physicallyRetired());
        assertFalse(loans.noOutstandingLoanFor(ownerExecution),
                "an empty ledger on a stopped actor is not an execution permit");
        assertTrue(loans.acquireReadAsync(ownerExecution).isCompletedExceptionally());
    }


    @Test
    void throwingHostListenerDoesNotStrandRetirementOrSkipDrainReceipt() {
        var retirementWakeups = new AtomicInteger();
        var loans = new ActorStateRwLoans(OWNER, 2, 1,
                retirementWakeups::incrementAndGet);
        var held = loans.acquireWriteAsync(actor(8901, 291, 1)).join();
        var queued = loans.acquireReadAsync(actor(8902, 292, 1));
        var callbackRan = new AtomicBoolean();
        queued.whenCompleteRuntime((ignored, failure) -> {
            callbackRan.set(true);
            throw new IllegalStateException("injected host notification failure");
        });
        loans.close();
        held.close();
        assertTrue(callbackRan.get());
        assertTrue(queued.isCompletedExceptionally());
        assertTrue(loans.physicallyRetired(),
                "the Future drain receipt must survive a throwing host waiter");
        assertEquals(1, retirementWakeups.get());
    }


    @Test
    void nearEpochExhaustionReservesCapacityForAlreadyQueuedRequest() throws Exception {
        try (var loans = new ActorStateRwLoans(OWNER, 3, 1)) {
            var held = loans.acquireWriteAsync(actor(9001, 301, 1)).join();
            // Test-only near-overflow injection under no concurrent callers.
            // Production code never modifies the monotonic epoch ledger.
            var nextEpoch = ActorStateRwLoans.class.getDeclaredField("nextEpoch");
            nextEpoch.setAccessible(true);
            nextEpoch.setLong(loans, Long.MAX_VALUE - 1);

            var grantedLastEpoch = loans.acquireReadAsync(actor(9002, 302, 1));
            assertFalse(grantedLastEpoch.isDone());
            assertTrue(loans.acquireReadAsync(actor(9003, 303, 1))
                    .isCompletedExceptionally(),
                    "queue must reserve grant epochs before accepting another waiter");
            assertEquals(1, loans.pendingCount());
            held.close();

            var finalLoan = grantedLastEpoch.join();
            assertEquals(Long.MAX_VALUE, finalLoan.epoch());
            finalLoan.close();
            assertTrue(loans.acquireReadAsync(actor(9004, 304, 1))
                    .isCompletedExceptionally(),
                    "an exhausted grant epoch must never wrap or resurrect authority");
        }
    }


    @Test
    void rawStateRwArbiterConstructionIsNotPublicGuestAuthority() {
        var constructors = ActorStateRwLoans.class.getDeclaredConstructors();
        assertEquals(2, constructors.length);
        for (var ctor : constructors) {
            assertFalse(java.lang.reflect.Modifier.isPublic(ctor.getModifiers()),
                    "only trusted runtime-package code may construct a physical RW arbiter");
        }
    }

}
