package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateRwLoansTest {
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
        try (var loans = new ActorStateRwLoans(8)) {
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
        try (var loans = new ActorStateRwLoans(8)) {
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
        try (var loans = new ActorStateRwLoans(4)) {
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
        try (var loans = new ActorStateRwLoans(4)) {
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
        try (var loans = new ActorStateRwLoans(4)) {
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
        try (var loans = new ActorStateRwLoans(8)) {
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
        try (var loans = new ActorStateRwLoans(1)) {
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
        var loans = new ActorStateRwLoans(2);
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
        try (var loans = new ActorStateRwLoans(2)) {
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
        try (var loans = new ActorStateRwLoans(2)) {
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
        var loans = new ActorStateRwLoans(3, notifications::incrementAndGet);
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
        var loans = new ActorStateRwLoans(2);
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
    void sameLogicalExecutionCannotAcquireThroughAnotherSlotHandle() {
        try (var loans = new ActorStateRwLoans(4)) {
            var original = actor(7501, 151, 5);
            var rematerialized = actor(7501, 152, 6);
            var active = loans.acquireReadAsync(original).join();
            assertTrue(loans.acquireReadAsync(rematerialized).isCompletedExceptionally());
            assertTrue(loans.acquireWriteAsync(rematerialized).isCompletedExceptionally());
            assertEquals(1, loans.activeReaderCount());
            assertEquals(0, loans.pendingCount());
            active.close();
        }
    }

    @Test
    void oneExecutionSlotCannotServeTwoLiveLogicalExecutions() {
        try (var loans = new ActorStateRwLoans(4)) {
            var original = actor(7601, 161, 5);
            var conflicting = actor(7602, 161, 5);
            var active = loans.acquireReadAsync(original).join();
            assertTrue(loans.acquireReadAsync(conflicting).isCompletedExceptionally());
            assertTrue(loans.acquireWriteAsync(conflicting).isCompletedExceptionally());
            assertEquals(1, loans.activeReaderCount());
            active.close();
        }
    }

    @Test
    void oldReleasedLoanCannotAuthorizeSuspensionAfterExecutionChangesSlot() {
        try (var loans = new ActorStateRwLoans(4)) {
            var original = actor(7701, 171, 4);
            var rematerialized = actor(7701, 172, 5);
            var old = loans.acquireReadAsync(original).join();
            old.close();
            assertTrue(old.readyToSuspend(original));
            var current = loans.acquireWriteAsync(rematerialized).join();
            assertFalse(old.readyToSuspend(original),
                    "an old loan must not certify a newly acquired logical execution");
            assertFalse(current.readyToSuspend(rematerialized));
            current.close();
            assertTrue(old.readyToSuspend(original));
        }
    }

    @Test
    void pendingReservationAlsoFencesLogicalExecutionAndSlot() {
        try (var loans = new ActorStateRwLoans(4)) {
            var blocker = loans.acquireWriteAsync(actor(7801, 181, 1)).join();
            var queued = loans.acquireReadAsync(actor(7802, 182, 1));
            assertFalse(queued.isDone());
            assertTrue(loans.acquireWriteAsync(actor(7802, 183, 2))
                    .isCompletedExceptionally());
            assertTrue(loans.acquireReadAsync(actor(7803, 182, 1))
                    .isCompletedExceptionally());
            assertEquals(1, loans.pendingCount());
            blocker.close();
            queued.join().close();
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test
    void retirementWakeupFiresAfterPublisherAndPhysicalLoansBothDrain()
            throws Exception {
        var notifications = new AtomicInteger();
        var loans = new ActorStateRwLoans(4, notifications::incrementAndGet);
        var firstReader = loans.acquireReadAsync(actor(7901, 191, 1)).join();
        var queuedWriter = loans.acquireWriteAsync(actor(7902, 192, 1));
        var queuedReader = loans.acquireReadAsync(actor(7903, 193, 1));

        var enteredCallback = new java.util.concurrent.CountDownLatch(1);
        var releaseCallback = new java.util.concurrent.CountDownLatch(1);
        queuedReader.whenCompleteRuntime((loan, failure) -> {
            if (failure != null) throw new AssertionError(failure);
            enteredCallback.countDown();
            try {
                if (!releaseCallback.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("publisher callback not released");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            } finally {
                loan.close();
            }
        });

        // Cancel the queued writer: the remaining reader is promoted on the
        // cancellation publisher, not by another StateLoan.close() call.
        Thread cancelling = Thread.ofPlatform().start(
                () -> assertTrue(queuedWriter.cancel(false)));
        try {
            assertTrue(enteredCallback.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "queued reader was never promoted");
            firstReader.close();
            loans.close();
            assertEquals(0, notifications.get(),
                    "publication and a physical reader are still outstanding");
        } finally {
            releaseCallback.countDown();
            cancelling.join(5000);
        }
        assertFalse(cancelling.isAlive(), "cancellation publisher must finish");
        assertTrue(loans.physicallyRetired());
        assertEquals(1, notifications.get(),
                "last notification drain must deliver the one-shot retirement wake");
        loans.close();
        assertEquals(1, notifications.get());
    }

    @Test
    void closingPendingFutureWaitsForPhysicalFailureNotificationDrain()
            throws Exception {
        var notifications = new AtomicInteger();
        var loans = new ActorStateRwLoans(2, notifications::incrementAndGet);
        var held = loans.acquireWriteAsync(actor(8101, 211, 1)).join();
        var pending = loans.acquireReadAsync(actor(8102, 212, 1));
        var observedFailure = new AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var unblock = new java.util.concurrent.CountDownLatch(1);
        pending.whenCompleteRuntime((value, failure) -> {
            observedFailure.set(value == null && failure instanceof IllegalStateException);
            entered.countDown();
            try {
                if (!unblock.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("failure subscriber was never unblocked");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
        });
        var closingError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread closing = Thread.ofPlatform().start(() -> {
            try { loans.close(); }
            catch (Throwable failure) { closingError.set(failure); }
        });
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "pending failure callback must begin before physical release");
            held.close();
            assertFalse(loans.physicallyRetired(),
                    "settled Future may still have a physical notification in flight");
            assertEquals(0, notifications.get());
        } finally {
            unblock.countDown();
            closing.join(5000);
        }
        assertFalse(closing.isAlive());
        assertNull(closingError.get());
        assertTrue(observedFailure.get());
        assertTrue(loans.physicallyRetired());
        assertEquals(1, notifications.get());
    }

    @Test
    void cancelledPendingFutureAlsoWaitsForCancellationObserverDrain()
            throws Exception {
        var notifications = new AtomicInteger();
        var loans = new ActorStateRwLoans(2, notifications::incrementAndGet);
        var held = loans.acquireWriteAsync(actor(8201, 221, 1)).join();
        var pending = loans.acquireReadAsync(actor(8202, 222, 1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var unblock = new java.util.concurrent.CountDownLatch(1);
        var sawCancellation = new AtomicBoolean();
        pending.whenCompleteRuntime((value, failure) -> {
            sawCancellation.set(failure instanceof java.util.concurrent.CancellationException);
            entered.countDown();
            try {
                if (!unblock.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("cancellation subscriber was never unblocked");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
        });
        var cancelResult = new AtomicBoolean();
        var cancellingError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread cancelling = Thread.ofPlatform().start(() -> {
            try { cancelResult.set(pending.cancel(false)); }
            catch (Throwable failure) { cancellingError.set(failure); }
        });
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "cancelled Future must begin notifying its subscriber");
            loans.close();
            held.close();
            assertFalse(loans.physicallyRetired(),
                    "cancelled-but-undrained Future must retain the physical barrier");
            assertEquals(0, notifications.get());
        } finally {
            unblock.countDown();
            cancelling.join(5000);
        }
        assertFalse(cancelling.isAlive());
        assertNull(cancellingError.get());
        assertTrue(cancelResult.get());
        assertTrue(sawCancellation.get());
        assertTrue(loans.physicallyRetired());
        assertEquals(1, notifications.get());
    }

    @Test
    void reusedSchedulerSlotCannotHaveTwoLiveGenerations() {
        try (var loans = new ActorStateRwLoans(4)) {
            var oldGeneration = actor(8301, 231, 8);
            var newGeneration = actor(8302, 231, 9);
            var old = loans.acquireReadAsync(oldGeneration).join();
            assertTrue(loans.acquireReadAsync(newGeneration).isCompletedExceptionally(),
                    "slot reuse cannot overlap a physically live generation");
            assertTrue(loans.acquireWriteAsync(newGeneration).isCompletedExceptionally());
            old.close();
            var current = loans.acquireReadAsync(newGeneration).join();
            assertTrue(current.heldBy(newGeneration));
            current.close();
        }
    }

    @Test
    void releasedLoanCannotAuthorizeOldSlotWhileReusedGenerationIsLive() {
        try (var loans = new ActorStateRwLoans(4)) {
            var oldGeneration = actor(8401, 241, 2);
            var newGeneration = actor(8402, 241, 3);
            var old = loans.acquireReadAsync(oldGeneration).join();
            old.close();
            assertTrue(old.readyToSuspend(oldGeneration));
            var current = loans.acquireWriteAsync(newGeneration).join();
            assertFalse(old.readyToSuspend(oldGeneration),
                    "an old slot witness cannot certify suspension while reused slot is live");
            current.close();
            assertTrue(old.readyToSuspend(oldGeneration));
        }
    }

    @Test
    void cancelledCasBeforeHookCannotEvadePhysicalRetirementFence()
            throws Exception {
        var notifications = new AtomicInteger();
        var loans = new ActorStateRwLoans(2, notifications::incrementAndGet);
        var held = loans.acquireWriteAsync(actor(8501, 251, 1)).join();
        var waiting = loans.acquireReadAsync(actor(8502, 252, 1));
        var observedCancellation = new AtomicBoolean();
        waiting.whenCompleteRuntime((value, failure) ->
                observedCancellation.set(
                        failure instanceof java.util.concurrent.CancellationException));

        // Hold the internal arbiter monitor while cancellation claims its
        // terminal CAS; the hook cannot remove its Pending node yet. Releasing
        // the writer on this thread is re-entrant and exercises promoteLocked's
        // cancelled-head path before the cancellation hook can run.
        var field = ActorStateRwLoans.class.getDeclaredField("monitor");
        field.setAccessible(true);
        Object monitor = field.get(loans);
        var cancellationResult = new AtomicBoolean();
        var cancellationError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread cancelling = Thread.ofPlatform().unstarted(() -> {
            try { cancellationResult.set(waiting.cancel(false)); }
            catch (Throwable failure) { cancellationError.set(failure); }
        });
        try {
            synchronized (monitor) {
                cancelling.start();
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!waiting.isCancelled() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(waiting.isCancelled(),
                        "cancellation must claim terminal state before arbiter release");
                held.close();
                loans.close();
                assertFalse(loans.physicallyRetired(),
                        "cancelled Future has not run its hook or observer drain");
                assertEquals(0, notifications.get(),
                        "do not retire while cancelled host observers remain outstanding");
            }
        } finally {
            cancelling.join(5000);
        }
        assertFalse(cancelling.isAlive(), "cancellation must physically finish");
        assertNull(cancellationError.get());
        assertTrue(cancellationResult.get());
        assertTrue(observedCancellation.get());
        assertTrue(loans.physicallyRetired());
        assertEquals(1, notifications.get(),
                "retirement must be notified once after cancellation drains");
    }

    @Test
    void failedCancellationRevokerQuarantinesPhysicalRetirement()
            throws Exception {
        var wakes = new AtomicInteger();
        var loans = new ActorStateRwLoans(2, wakes::incrementAndGet);
        var holder = loans.acquireWriteAsync(actor(8601, 261, 1)).join();
        var pending = loans.acquireReadAsync(actor(8602, 262, 1));
        // A second waiter must not be promoted after the cancellation
        // revoker reports failure, even when the active writer retires.
        var queuedReader = loans.acquireReadAsync(actor(8605, 265, 1));
        assertFalse(queuedReader.isDone());
        var injected = new IllegalStateException("injected revocation failure");

        // OresFuture intentionally catches revoker failures and stores them
        // for runtime inspection. Inject AFTER its normal owner revocation,
        // proving that even a fully removed waiter cannot hide the failure.
        var hookField = OresFuture.class.getDeclaredField("cancelHook");
        hookField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var hook = (java.util.concurrent.atomic.AtomicReference<Runnable>)
                hookField.get(pending);
        Runnable original = hook.get();
        hook.set(() -> {
            original.run();
            throw injected;
        });
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertSame(injected, pending.cancellationHookFailureFromRuntime());
        assertSame(injected, loans.retirementQuarantineCause(),
                "the state arbiter must preserve the physical revocation failure");
        assertFalse(holder.heldBy(actor(8601, 261, 1)),
                "quarantine must revoke active logical state access");
        assertTrue(loans.acquireReadAsync(actor(8603, 263, 1))
                .isCompletedExceptionally(), "failed revocation must deny new readers");
        assertTrue(loans.acquireWriteAsync(actor(8604, 264, 1))
                .isCompletedExceptionally(), "failed revocation must deny new writers");
        assertEquals(0, loans.pendingCount(),
                "first revoker failure must automatically fail and drain older queued work");
        assertTrue(queuedReader.isCompletedExceptionally(),
                "quarantine must fail preexisting queued work without granting state authority");
        assertTrue(queuedReader.isDone(), "failed waiter must never remain parked");
        var failedQueued = assertThrows(
                java.util.concurrent.ExecutionException.class, queuedReader::get);
        assertSame(injected, failedQueued.getCause().getCause(),
                "failed queued operation must retain the original revoker failure");
        var denied = loans.acquireReadAsync(actor(8605, 265, 1));
        assertTrue(denied.isCompletedExceptionally(), "new admission remains closed");
        var deniedFailure = assertThrows(
                java.util.concurrent.ExecutionException.class, denied::get);
        assertSame(injected, deniedFailure.getCause().getCause(),
                "even after automatic shutdown admission must expose quarantine provenance");
        holder.close();
        loans.close();
        assertEquals(0, loans.activeWriterCount());
        assertEquals(0, loans.pendingCount());
        assertFalse(loans.physicallyRetired(),
                "failed revocation must quarantine the actor arena even after loan cleanup");
        assertEquals(0, wakes.get(),
                "the runtime must not receive a false safe-reclamation receipt");
    }

    @Test
    void cancellationObserverKeepsExecutionAndRawSlotReservedUntilDrained()
            throws Exception {
        var loans = new ActorStateRwLoans(4);
        var holder = loans.acquireWriteAsync(actor(8701, 271, 1)).join();
        var victim = actor(8702, 272, 5);
        var waiting = loans.acquireReadAsync(victim);
        var callbackEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseCallback = new java.util.concurrent.CountDownLatch(1);
        waiting.whenCompleteRuntime((value, failure) -> {
            if (!(failure instanceof java.util.concurrent.CancellationException)) {
                throw new AssertionError("expected cancellation", failure);
            }
            callbackEntered.countDown();
            try {
                if (!releaseCallback.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("cancellation observer timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        var cancelled = new AtomicBoolean();
        var threadError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread cancelling = Thread.ofPlatform().start(() -> {
            try { cancelled.set(waiting.cancel(false)); }
            catch (Throwable e) { threadError.set(e); }
        });
        try {
            assertTrue(callbackEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            holder.close();
            assertTrue(loans.acquireReadAsync(actor(8702, 273, 6))
                    .isCompletedExceptionally(), "same logical execution still retiring");
            assertTrue(loans.acquireWriteAsync(actor(8703, 272, 6))
                    .isCompletedExceptionally(), "same raw slot still retiring");
            assertTrue(loans.acquireReadAsync(victim).isCompletedExceptionally(),
                    "same full authority remains reserved until callback drain");
        } finally {
            releaseCallback.countDown();
            cancelling.join(5000);
            holder.close();
        }
        assertFalse(cancelling.isAlive());
        assertNull(threadError.get());
        assertTrue(cancelled.get());
        var reused = loans.acquireReadAsync(actor(8703, 272, 6)).join();
        reused.close();
        loans.close();
        assertTrue(loans.physicallyRetired());
    }

    @Test
    void promotedLoanReleasedDuringObserverStillFencesExecutionAndSlot()
            throws Exception {
        var loans = new ActorStateRwLoans(4);
        var holder = loans.acquireWriteAsync(actor(8801, 281, 1)).join();
        var victim = actor(8802, 282, 4);
        var waiting = loans.acquireReadAsync(victim);
        var callbackEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseCallback = new java.util.concurrent.CountDownLatch(1);
        waiting.whenCompleteRuntime((loan, failure) -> {
            if (failure != null || loan == null) {
                throw new AssertionError("expected granted loan", failure);
            }
            // Physical loan release is not the Future notification-drain
            // receipt. Other observers may still hold scheduler resources.
            loan.close();
            callbackEntered.countDown();
            try {
                if (!releaseCallback.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("promoted observer timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        var threadError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread promoting = Thread.ofPlatform().start(() -> {
            try { holder.close(); }
            catch (Throwable e) { threadError.set(e); }
        });
        try {
            assertTrue(callbackEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, loans.activeReaderCount(),
                    "granted loan was already physically released");
            assertTrue(loans.acquireWriteAsync(actor(8802, 283, 5))
                    .isCompletedExceptionally(), "logical execution cannot be rematerialized yet");
            assertTrue(loans.acquireReadAsync(actor(8803, 282, 5))
                    .isCompletedExceptionally(), "raw slot cannot be reused yet");
        } finally {
            releaseCallback.countDown();
            promoting.join(5000);
            holder.close();
        }
        assertFalse(promoting.isAlive());
        assertNull(threadError.get());
        var next = loans.acquireWriteAsync(actor(8803, 282, 5)).join();
        next.close();
        loans.close();
        assertTrue(loans.physicallyRetired());
    }

    @Test
    void shutdownFailureDrainRetainsOldLoanSuspensionFence()
            throws Exception {
        var loans = new ActorStateRwLoans(3);
        var victim = actor(8901, 291, 7);
        var old = loans.acquireReadAsync(victim).join();
        old.close();
        assertTrue(old.readyToSuspend(victim));
        var blocker = loans.acquireWriteAsync(actor(8902, 292, 1)).join();
        var waiting = loans.acquireReadAsync(victim);
        var callbackEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseCallback = new java.util.concurrent.CountDownLatch(1);
        waiting.whenCompleteRuntime((value, failure) -> {
            if (!(failure instanceof IllegalStateException)) {
                throw new AssertionError("expected shutdown failure", failure);
            }
            callbackEntered.countDown();
            try {
                if (!releaseCallback.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("shutdown observer timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        var threadError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread closing = Thread.ofPlatform().start(() -> {
            try { loans.close(); }
            catch (Throwable e) { threadError.set(e); }
        });
        try {
            assertTrue(callbackEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            blocker.close();
            assertFalse(old.readyToSuspend(victim),
                    "old loan cannot certify suspension while pending Future drains");
            assertFalse(loans.physicallyRetired());
        } finally {
            releaseCallback.countDown();
            closing.join(5000);
            blocker.close();
        }
        assertFalse(closing.isAlive());
        assertNull(threadError.get());
        assertTrue(old.readyToSuspend(victim));
        assertTrue(loans.physicallyRetired());
    }
}
