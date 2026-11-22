package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual scheduler/physical Future tests, not a mock cancellation registry. */
@Timeout(20)
final class CleanupAwareSchedulerCancellationTest {
    @Test void actorStopPhysicalGateWaitsForTerminalFutureSubscriberDrain()
            throws Exception {
        // A Task may leave the live Task set BEFORE its terminal Future
        // notifies a synchronous subscriber on a physical worker. Actor
        // arenas cannot be reclaimed while that callback remains active.
        OresFuture<Integer> producer = new OresFuture<>();
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (resume.initial()) {
                                awaiting.countDown();
                                return OresScheduler.await(producer);
                            }
                            return OresScheduler.done(7);
                        }
                    });
            assertTrue(awaiting.await(3, TimeUnit.SECONDS));
            work.whenCompleteRuntime((value, failure) -> {
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        callbackFailure.set(new AssertionError(
                                "test terminal subscriber was never released"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbackFailure.set(interrupted);
                }
            });
            try {
                producer.completeFromRuntime(7);
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                assertFalse(scheduler.actorStopPhysicallyQuiescent(),
                        "terminal Future settlement is not a subscriber-drain receipt");
            } finally {
                releaseCallback.countDown();
            }
            assertEquals(Integer.valueOf(7), work.get(3, TimeUnit.SECONDS));
            // The physical scheduler lease and all synchronous notification
            // receipts must retire before an actor considers this safe.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!scheduler.actorStopPhysicallyQuiescent()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(scheduler.actorStopPhysicallyQuiescent());
            assertNull(callbackFailure.get());
        }
    }

    @Test void actorStopPhysicalGateWaitsForInFlightRetirementCallback()
            throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(
                    ignored -> OresScheduler.done(1),
                    () -> {
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                                callbackFailure.set(new AssertionError(
                                        "physical callback was not released"));
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            callbackFailure.set(interrupted);
                        }
                    });
            try {
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                assertFalse(scheduler.actorStopPhysicallyQuiescent(),
                        "removing a Task from the live set cannot retire an in-flight loan");
                assertFalse(work.isDone(), "Future publication follows physical retirement");
            } finally {
                releaseCallback.countDown();
            }
            assertEquals(Integer.valueOf(1), work.get(3, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!scheduler.actorStopPhysicallyQuiescent()
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(scheduler.actorStopPhysicallyQuiescent());
        }
    }

    @Test void failedPhysicalRetirementCallbackFailsClosed()
            throws Exception {
        RuntimeException failure = new IllegalStateException("physical loan release failed");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch retiredAttempted = new CountDownLatch(1);
            OresFuture<Integer> work = scheduler.start(
                    ignored -> OresScheduler.done(3),
                    () -> {
                        retiredAttempted.countDown();
                        throw failure;
                    });
            assertTrue(retiredAttempted.await(3, TimeUnit.SECONDS));
            // Callback exceptions must not convert a failed physical release
            // to a safe arena-retirement outcome.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!scheduler.hasIncompleteCleanup()
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(scheduler.hasIncompleteCleanup());
            long physicalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!scheduler.actorStopPhysicallyQuiescent()
                    && System.nanoTime() < physicalDeadline) Thread.onSpinWait();
            assertTrue(scheduler.actorStopPhysicallyQuiescent(),
                    "quiescence alone does not imply successful cleanup");
        }
    }

    @Test void suspendedTaskRunsDeferredCleanupBeforePublishingCancellation()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Integer> cleanup = new OresFuture<>();
        CountDownLatch pending = new CountDownLatch(1);
        CountDownLatch unwinding = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        int pc;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (pc++ == 0) {
                                assertTrue(resume.initial());
                                pending.countDown();
                                return OresScheduler.await(producer);
                            }
                            if (pc == 2) {
                                assertTrue(OresScheduler.cancellationCleanupTurn());
                                assertInstanceOf(CancellationException.class, resume.failure());
                                entered.incrementAndGet();
                                unwinding.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            // The initial terminal cancellation is injected only
                            // once, but every later await resume remains within
                            // the scheduler-authenticated cleanup authority.
                            assertTrue(OresScheduler.cancellationCleanupTurn(),
                                    "async defer resume must retain cleanup authority");
                            assertEquals(Integer.valueOf(8), resume.value());
                            return OresScheduler.done(8);
                        }
                    });
            assertTrue(pending.await(3, TimeUnit.SECONDS));
            assertTrue(work.cancel(false));
            assertFalse(work.isDone(), "guest defers have not finished");
            assertTrue(unwinding.await(3, TimeUnit.SECONDS));
            assertFalse(work.isDone(), "even pending async cleanup retains ownership");
            assertFalse(producer.isCancelled(), "borrowed producer must not be revoked");
            cleanup.completeFromRuntime(8);
            assertThrows(CancellationException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
            assertEquals(1, entered.get());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    @Test void cancellationDuringRunningTurnCannotStartCleanupOnForeignThread()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Void> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Void>() {
                        int pc;
                        @Override public OresScheduler.Step<Void> resume(
                                OresScheduler.Resume resume) throws Exception {
                            if (pc++ == 0) {
                                entered.countDown();
                                if (!leave.await(4, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test carrier timeout");
                                }
                                return OresScheduler.cooperate();
                            }
                            assertTrue(OresScheduler.cancellationCleanupTurn());
                            assertInstanceOf(CancellationException.class, resume.failure());
                            cleanup.countDown();
                            return OresScheduler.done(null);
                        }
                    });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(work.cancel(false));
                assertFalse(work.isDone());
                assertEquals(1, cleanup.getCount(),
                        "cleanup cannot run while the previous turn executes");
            } finally {
                leave.countDown();
            }
            assertTrue(cleanup.await(3, TimeUnit.SECONDS));
            assertThrows(CancellationException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationRequestIsSingleShotAndLaterCallbacksCannotReviveIt()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> cleanup = new OresFuture<>();
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        int pc;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (pc++ == 0) {
                                awaiting.countDown();
                                return OresScheduler.await(source);
                            }
                            if (pc == 2) {
                                cleaning.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            return OresScheduler.done(1);
                        }
                    });
            assertTrue(awaiting.await(3, TimeUnit.SECONDS));
            assertTrue(work.cancel(false));
            assertFalse(work.cancel(false));
            assertTrue(cleaning.await(3, TimeUnit.SECONDS));
            source.completeFromRuntime(50);
            assertFalse(work.isDone());
            cleanup.completeFromRuntime(1);
            assertThrows(CancellationException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
            assertFalse(work.cancel(false));
        }
    }

    @Test void panicInCleanupCannotBeReclassifiedAsCancellation()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        AssertionError fatal = new AssertionError("cleanup panic");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        int pc;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (pc++ == 0) {
                                waiting.countDown();
                                return OresScheduler.await(source);
                            }
                            throw fatal;
                        }
                    });
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(work.cancel(false));
            assertSame(fatal, assertThrows(ExecutionException.class,
                    () -> work.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(work.isCancelled());
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "panic during cancellation unwind must quarantine actor/FFI loans");
        }
    }

    @Test void closingSchedulerWhileCleanupAwaitsFailsClosed() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> blockedCleanup = new OresFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch startedCleanup = new CountDownLatch(1);
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        int pc;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (pc++ == 0) {
                                waiting.countDown();
                                return OresScheduler.await(source);
                            }
                            startedCleanup.countDown();
                            return OresScheduler.await(blockedCleanup);
                        }
                    });
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(work.cancel(false));
            assertTrue(startedCleanup.await(3, TimeUnit.SECONDS));
            scheduler.close();
            ExecutionException err = assertThrows(ExecutionException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
            assertInstanceOf(OresScheduler.CleanupIncompleteError.class,
                    err.getCause(), "closed lane cannot claim completed defers");
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "incomplete lexical cleanup must quarantine its arena");
            assertFalse(work.isCancelled());
            assertFalse(source.isCancelled());
            assertEquals(0, blockedCleanup.pendingRuntimeWaiterCount());
        } finally {
            scheduler.close();
        }
    }

    @Test void shutdownDuringActiveCleanupCannotPublishDoneBeforeCarrierExit()
            throws Exception {
        OresFuture<Void> upstream = new OresFuture<>();
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch enteredCleanup = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AtomicInteger cleanupEntries = new AtomicInteger();
        OresScheduler scheduler = new OresScheduler(2);
        try {
            OresFuture<Void> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Void>() {
                        int pc;
                        @Override public OresScheduler.Step<Void> resume(
                                OresScheduler.Resume resume) throws Exception {
                            if (pc++ == 0) {
                                suspended.countDown();
                                return OresScheduler.await(upstream);
                            }
                            assertTrue(OresScheduler.cancellationCleanupTurn());
                            cleanupEntries.incrementAndGet();
                            enteredCleanup.countDown();
                            // Model a cleanup frame that keeps executing:
                            // close() interrupts carriers, but an interrupt
                            // is not a physical-exit receipt.
                            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                            boolean released = false;
                            while (!released && System.nanoTime() < until) {
                                try {
                                    released = releaseCleanup.await(25, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException ignored) {
                                    // keep running until explicitly released
                                }
                            }
                            if (!released) {
                                throw new IllegalStateException("test cleanup carrier timeout");
                            }
                            return OresScheduler.done(null);
                        }
                    });
            assertTrue(suspended.await(3, TimeUnit.SECONDS));
            assertTrue(work.cancel(false));
            assertTrue(enteredCleanup.await(3, TimeUnit.SECONDS));
            Thread closer = new Thread(scheduler::close, "close-during-source-cleanup");
            closer.start();
            try {
                // Wait until close() has revoked the Task and parked in
                // awaitTermination; otherwise the cleanup may finish first
                // and a clean Cancelled outcome would be correct.
                long parkedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (closer.getState() != Thread.State.TIMED_WAITING
                        && closer.isAlive() && System.nanoTime() < parkedBy) {
                    Thread.sleep(2);
                }
                assertEquals(Thread.State.TIMED_WAITING, closer.getState(),
                        "close() must be waiting for the still-running cleanup carrier");
                // Scheduler shutdown fences queued turns; it cannot treat a
                // still-executing cleanup frame as a physical release receipt.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!scheduler.hasIncompleteCleanup() &&
                        System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
                assertTrue(scheduler.hasIncompleteCleanup(),
                        "closing an executing cleanup frame must quarantine");
                assertFalse(work.isDone(),
                        "no terminal Future while the cleanup carrier still executes");
                assertFalse(work.isCancelled(),
                        "cancel request is not a physical cleanup acknowledgment");
            } finally {
                releaseCleanup.countDown();
                closer.join(3000);
            }
            assertFalse(closer.isAlive());
            assertEquals(1, cleanupEntries.get(),
                    "another carrier must not replay the cleanup turn");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
            assertInstanceOf(OresScheduler.CleanupIncompleteError.class,
                    failure.getCause());
            assertFalse(work.isCancelled(),
                    "shutdown cleanup failure must dominate logical cancellation");
            assertEquals(0, upstream.pendingRuntimeWaiterCount());
        } finally {
            releaseCleanup.countDown();
            scheduler.close();
        }
    }

    @Test void terminalFutureWaitsForPhysicalQuiescenceHookEvenDuringClose()
            throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch allowRetirement = new CountDownLatch(1);
        OresScheduler scheduler = new OresScheduler(1);
        Thread closer = null;
        try {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            return OresScheduler.done(42);
                        }
                    },
                    () -> {
                        hookEntered.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                released = allowRetirement.await(25, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                // shutdownNow is not a physical retire receipt.
                            }
                        }
                    });
            assertTrue(hookEntered.await(3, TimeUnit.SECONDS));
            assertFalse(work.isDone(),
                    "the quiescence callback still owns the actor lease");
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "a terminal Task must still pin the actor arena while onQuiescent runs");
            closer = new Thread(scheduler::close, "close-before-lease-retirement");
            closer.start();
            assertFalse(work.isDone(),
                    "external close must not overtake the quiescence callback");
            allowRetirement.countDown();
            assertEquals(Integer.valueOf(42), work.get(3, TimeUnit.SECONDS));
            closer.join(3000);
            assertFalse(closer.isAlive());
            assertFalse(scheduler.hasIncompleteCleanup(),
                    "a successful physical release returns the Task to the scheduler pool");
        } finally {
            allowRetirement.countDown();
            if (closer != null) closer.join(3000);
            scheduler.close();
        }
    }

    @Test void failedPhysicalRetirementQuarantinesCleanupTaskInsteadOfPublishingSuccess()
            throws Exception {
        IllegalStateException failedLoanRevocation =
                new IllegalStateException("actor FFI loan did not retire");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            return OresScheduler.done(42);
                        }
                    },
                    () -> { throw failedLoanRevocation; });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
            assertInstanceOf(OresScheduler.CleanupIncompleteError.class,
                    failure.getCause(), "incomplete physical release must dominate Done");
            assertSame(failedLoanRevocation, failure.getCause().getCause());
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "outstanding actor/FFI loans must remain registered");
            assertFalse(work.isCancelled());
        }
    }

    @Test void failedPhysicalRetirementPreservesFailureIdentityForOrdinaryTask()
            throws Exception {
        IllegalStateException failedLoanRevocation =
                new IllegalStateException("ordinary Task owner release failed");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(
                    resume -> OresScheduler.done(42),
                    () -> { throw failedLoanRevocation; });
            assertSame(failedLoanRevocation,
                    assertThrows(ExecutionException.class,
                            () -> work.get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "physical retirement failure cannot be swept away");
        }
    }

    @Test void ordinaryNonCleanupFutureRetainsImmediateCancellation() throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.start(resume -> {
                waiting.countDown();
                return OresScheduler.await(producer);
            });
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(work.cancel(false));
            assertTrue(work.isCancelled());
            assertFalse(producer.isCancelled());
        }
    }
}
