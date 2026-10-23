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
