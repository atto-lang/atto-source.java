package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual scheduler/physical Future tests, not a mock cancellation registry. */
@Timeout(20)
final class CleanupAwareSchedulerCancellationTest {
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
                            assertFalse(OresScheduler.cancellationCleanupTurn());
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

    @Test void producerCallbackClaimedBeforeCancelCannotPoisonLaterCleanupAwait()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> cleanup = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch callbackPastCancellationCheck = new CountDownLatch(1);
        CountDownLatch releaseStaleCallback = new CountDownLatch(1);
        CountDownLatch cleanupAwaiting = new CountDownLatch(1);
        AtomicBoolean pausedOnce = new AtomicBoolean();
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger cleanupStarts = new AtomicInteger();
        AtomicInteger quiescent = new AtomicInteger();

        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> work = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            int turn = resumes.incrementAndGet();
                            if (turn == 1) {
                                assertTrue(resume.initial());
                                started.countDown();
                                return OresScheduler.await(source);
                            }
                            if (turn == 2) {
                                assertTrue(OresScheduler.cancellationCleanupTurn());
                                assertInstanceOf(CancellationException.class, resume.failure());
                                cleanupStarts.incrementAndGet();
                                cleanupAwaiting.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            assertEquals(3, turn, "stale producer must not create a turn");
                            assertTrue(OresScheduler.cancellationCleanupTurn());
                            assertNull(resume.failure(), "only the cleanup Future can resume cleanup");
                            assertEquals(Integer.valueOf(8), resume.value());
                            return OresScheduler.done(8);
                        }
                    }, quiescent::incrementAndGet);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (source.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, source.pendingRuntimeWaiterCount(),
                    "wait until the source await callback is registered");

            // OresFuture.unwrap() runs inside deliverAwaitCompletion AFTER
            // its initial cancellation check and BEFORE pendingResume CAS.
            // This deterministic gate represents an already-claimed callback,
            // which a later detach() cannot retract.
            CompletionException lateFailure =
                    new CompletionException(new IllegalStateException("late producer")) {
                        @Override public Throwable getCause() {
                            if (pausedOnce.compareAndSet(false, true)) {
                                callbackPastCancellationCheck.countDown();
                                try {
                                    if (!releaseStaleCallback.await(6, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "test did not release source callback");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            }
                            return super.getCause();
                        }
                    };
            Thread producerThread = new Thread(
                    () -> source.failFromRuntime(lateFailure), "late-source-producer");
            producerThread.start();
            try {
                assertTrue(callbackPastCancellationCheck.await(3, TimeUnit.SECONDS));
                assertTrue(work.cancel(false));
                assertFalse(work.cancel(false), "cancellation request is single-shot");
                assertTrue(cleanupAwaiting.await(3, TimeUnit.SECONDS));
                // The stale producer must be released only after the cleanup
                // Future actually owns the CURRENT await registration.
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (cleanup.pendingRuntimeWaiterCount() == 0
                        && System.nanoTime() < cleanupDeadline) {
                    Thread.sleep(1);
                }
                assertEquals(1, cleanup.pendingRuntimeWaiterCount());
                assertFalse(work.isDone(), "cleanup Future still owns lexical retirement");
                assertEquals(0, quiescent.get());
            } finally {
                releaseStaleCallback.countDown();
                producerThread.join(3_000);
            }
            assertFalse(producerThread.isAlive(), "detached producer callback must exit");
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertEquals(1, cleanup.pendingRuntimeWaiterCount(),
                    "stale producer cannot displace the cleanup await");
            assertFalse(work.isDone(), "stale producer cannot publish task cancellation");
            assertEquals(0, quiescent.get(), "no premature physical retirement");
            assertEquals(2, resumes.get(), "no stale guest continuation resurrection");

            cleanup.completeFromRuntime(8);
            assertThrows(CancellationException.class,
                    () -> work.get(3, TimeUnit.SECONDS));
            assertEquals(3, resumes.get(), "exactly one authenticated cleanup continuation");
            assertEquals(1, cleanupStarts.get());
            assertEquals(1, quiescent.get(), "physical retirement is exactly once");
            assertFalse(scheduler.hasIncompleteCleanup());
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
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

    @Test void wallDeadlineWithoutCancellationInjectionQuarantinesSuspendedSource()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cleanQuiescence = new AtomicInteger();
        IllegalStateException deadlineFailure = new IllegalStateException("wall deadline");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> work = scheduler.startWithWallDeadline(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (!resume.initial()) {
                                fail("deadline must not resurrect suspended guest source");
                            }
                            started.countDown();
                            return OresScheduler.await(producer);
                        }
                    },
                    cleanQuiescence::incrementAndGet,
                    TimeUnit.SECONDS.toNanos(2),
                    deadlineFailure);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (producer.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, producer.pendingRuntimeWaiterCount());

            ExecutionException terminal = assertThrows(
                    ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
            assertInstanceOf(OresScheduler.CleanupIncompleteError.class,
                    terminal.getCause(), "deadline cannot stand in for lexical unwind");
            assertSame(deadlineFailure, terminal.getCause().getCause());
            assertFalse(work.isCancelled());
            assertEquals(0, cleanQuiescence.get(),
                    "aborted lexical frames cannot receive a clean retirement");
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "caller must retain/quarantine resource ownership");
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertFalse(producer.isCancelled(), "borrowed producer remains independent");
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
                            if (!releaseCleanup.await(4, TimeUnit.SECONDS)) {
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

