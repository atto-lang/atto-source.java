package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mechanical tests for the execution-context cancellation protocol. The
 * compiler's SourceTask is the concrete implementation; these use a minimal
 * state machine to make scheduler/producer races deterministic.
 */
final class SourceTaskCancellationDrainTest {
    private static final class SuspendedCleanup
            implements OresScheduler.CancellationAwareTask<Integer> {
        final OresFuture<Void> abandoned = new OresFuture<>();
        final OresFuture<Void> cleanup = new OresFuture<>();
        final CountDownLatch enteredFirstAwait = new CountDownLatch(1);
        final CountDownLatch enteredCleanup = new CountDownLatch(1);
        final AtomicInteger turns = new AtomicInteger();
        final AtomicInteger requestCount = new AtomicInteger();
        final AtomicBoolean insideCleanup = new AtomicBoolean();
        final AtomicReference<Object> domain = new AtomicReference<>();
        final AtomicReference<OresScheduler.TaskCancelledException> observedCancellation =
                new AtomicReference<>();
        volatile Thread cleanupThread;

        @Override
        public void cancellationRequested() {
            requestCount.incrementAndGet();
        }

        @Override
        public boolean cleanupInProgress() {
            return insideCleanup.get();
        }

        @Override
        public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
            assertNotNull(OresScheduler.current(), "guest continuation requires a scheduler");
            Object currentDomain = OresScheduler.currentTaskDomain();
            assertNotNull(currentDomain);
            Object previous = domain.getAndSet(currentDomain);
            if (previous != null) assertSame(previous, currentDomain);
            int step = turns.getAndIncrement();
            if (step == 0) {
                assertTrue(resume.initial());
                enteredFirstAwait.countDown();
                return OresScheduler.await(abandoned);
            }
            if (step == 1) {
                observedCancellation.set(assertInstanceOf(
                        OresScheduler.TaskCancelledException.class, resume.failure()));
                insideCleanup.set(true);
                cleanupThread = Thread.currentThread();
                enteredCleanup.countDown();
                return OresScheduler.await(cleanup);
            }
            assertEquals(2, step, "cleanup continuation resumed more than once");
            assertNull(resume.failure(), "cancellation must not interrupt cleanup await");
            assertFalse(resume.initial());
            insideCleanup.set(false);
            return OresScheduler.done(42);
        }
    }

    @Test
    void cancelSuspendedTaskDrainsCleanupBeforePublishingCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            SuspendedCleanup body = new SuspendedCleanup();
            OresFuture<Integer> task = scheduler.start(body);
            assertTrue(body.enteredFirstAwait.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertFalse(task.cancel(false), "cancellation is single admission");
            assertTrue(body.enteredCleanup.await(3, TimeUnit.SECONDS));
            assertFalse(task.isDone(), "caller must not observe terminal before cleanup");
            assertEquals(1, body.requestCount.get());
            assertEquals(0, body.abandoned.pendingRuntimeWaiterCount());
            assertFalse(body.abandoned.isDone(),
                    "detach must not cancel the upstream producer");
            Thread producer = Thread.ofPlatform().start(() -> body.cleanup.completeFromRuntime(null));
            producer.join();
            CancellationException observed = assertThrows(
                    CancellationException.class, () -> task.get(3, TimeUnit.SECONDS));
            assertSame(body.observedCancellation.get(), observed,
                    "the terminal cancellation must preserve execution-context identity");
            assertTrue(task.isCancelled());
            assertEquals(3, body.turns.get());
            assertNotSame(producer, body.cleanupThread);
        }
    }

    @Test
    void schedulerCloseWaitsForSuspendedCleanupAndDoesNotKillCarrier() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        try {
            SuspendedCleanup body = new SuspendedCleanup();
            OresFuture<Integer> task = scheduler.start(body);
            assertTrue(body.enteredFirstAwait.await(3, TimeUnit.SECONDS));
            CountDownLatch closeFinished = new CountDownLatch(1);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    scheduler.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeFinished.countDown();
                }
            });
            assertTrue(body.enteredCleanup.await(3, TimeUnit.SECONDS));
            assertEquals(1L, closeFinished.getCount(),
                    "close cannot detach execution-context obligations");
            body.cleanup.completeFromRuntime(null);
            assertTrue(closeFinished.await(3, TimeUnit.SECONDS));
            closer.join();
            assertNull(closeFailure.get());
            assertTrue(task.isCancelled());
            assertEquals(3, body.turns.get());
        } finally {
            // Idempotent close, including after a failed assertion.
            scheduler.close();
        }
    }

    @Test
    void cancellationCleanupFailureIsNotReportedAsSuccessfulCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
            AtomicBoolean cleanupActive = new AtomicBoolean();
            AtomicInteger pc = new AtomicInteger();
            OresFuture<Void> input = new OresFuture<>();
            CountDownLatch enteredFirstAwait = new CountDownLatch(1);
            OresFuture<Integer> task = scheduler.start(new OresScheduler.CancellationAwareTask<>() {
                @Override public void cancellationRequested() { }
                @Override public boolean cleanupInProgress() { return cleanupActive.get(); }
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (pc.getAndIncrement() == 0) {
                        enteredFirstAwait.countDown();
                        return OresScheduler.await(input);
                    }
                    assertInstanceOf(OresScheduler.TaskCancelledException.class, resume.failure());
                    cleanupActive.set(true);
                    throw cleanupFailure;
                }
            });
            assertTrue(enteredFirstAwait.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(3, TimeUnit.SECONDS));
            assertSame(cleanupFailure, thrown.getCause());
            assertFalse(task.isCancelled());
            assertEquals(1, cleanupFailure.getSuppressed().length);
            assertInstanceOf(OresScheduler.TaskCancelledException.class,
                    cleanupFailure.getSuppressed()[0]);
        }
    }
}
