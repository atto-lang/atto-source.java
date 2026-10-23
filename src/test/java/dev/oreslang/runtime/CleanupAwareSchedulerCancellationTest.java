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
