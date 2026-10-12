package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structured cancellation never completes a task until its scheduler-owned
 * cleanup continuation has run. These are deterministic host/scheduler tests;
 * the actor STOP CONTROL lane is a separate integration release gate.
 */
@Timeout(20)
final class OresSchedulerStructuredCancellationTest {
    private static void awaitWaiter(OresFuture<?> producer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (producer.pendingRuntimeWaiterCount() != 1
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, producer.pendingRuntimeWaiterCount(),
                "source task never actually suspended on its producer");
    }

    @Test
    void cancellationResumesSameLogicalTaskForCleanupAndWaitsForCleanupFuture() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> producer = new OresFuture<>();
            OresFuture<Void> cleanupGate = new OresFuture<>();
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Object> owner = new AtomicReference<>();
            AtomicReference<Object> cleanupOwner = new AtomicReference<>();
            CountDownLatch cleanupEntered = new CountDownLatch(1);

            OresFuture<Integer> result = scheduler.start(resume -> {
                int step = callbacks.getAndIncrement();
                assertSame(scheduler, OresScheduler.current(),
                        "guest cleanup must execute on the owning scheduler");
                if (step == 0) {
                    owner.set(OresScheduler.currentTaskDomain());
                    return OresScheduler.await(producer);
                }
                assertSame(owner.get(), OresScheduler.currentTaskDomain());
                if (step == 1) {
                    assertInstanceOf(java.util.concurrent.CancellationException.class,
                            resume.failure());
                    cleanupOwner.set(OresScheduler.currentTaskDomain());
                    cleanupEntered.countDown();
                    return OresScheduler.await(cleanupGate);
                }
                assertEquals(2, step, "cleanup must not replay");
                assertSame(cleanupOwner.get(), OresScheduler.currentTaskDomain());
                assertNull(resume.failure());
                return OresScheduler.done(42);
            });

            awaitWaiter(producer);
            assertTrue(scheduler.requestStructuredCancellation(result));
            assertFalse(scheduler.requestStructuredCancellation(result),
                    "one cancellation request must own the failed resume");
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone(),
                    "task completion may not precede guest cleanup settlement");
            assertFalse(producer.isDone(),
                    "canceling the consumer must not cancel its shared producer");
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "cancellation must detach the old producer waiter");
            assertTrue(producer.completeFromRuntime(99),
                    "a late producer must retain its independent completion");
            assertFalse(result.isDone());
            cleanupGate.completeFromRuntime(null);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            assertEquals(3, callbacks.get(),
                    "task must not complete successfully after cancellation");
            assertFalse(scheduler.requestStructuredCancellation(result));
        }
    }

    @Test
    void foreignOrSettledFutureCannotRequestCancellationOfAnotherTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1);
             OresScheduler foreign = new OresScheduler(1)) {
            OresFuture<Integer> producer = new OresFuture<>();
            OresFuture<Integer> result = scheduler.start(resume -> {
                if (resume.initial()) return OresScheduler.await(producer);
                return OresScheduler.done(7);
            });
            awaitWaiter(producer);
            assertFalse(foreign.requestStructuredCancellation(result));
            assertFalse(scheduler.requestStructuredCancellation(OresFuture.completed(8)));
            producer.completeFromRuntime(3);
            assertEquals(7, result.get(5, TimeUnit.SECONDS));
            assertFalse(scheduler.requestStructuredCancellation(result));
        }
    }

    @Test
    void cancellationRacingProducerCompletionNeverRunsTwoGuestCleanupTurns()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int attempt = 0; attempt < 50; attempt++) {
                OresFuture<Integer> producer = new OresFuture<>();
                AtomicInteger resumes = new AtomicInteger();
                AtomicInteger cleanup = new AtomicInteger();
                OresFuture<Integer> task = scheduler.start(resume -> {
                    if (resumes.getAndIncrement() == 0) {
                        return OresScheduler.await(producer);
                    }
                    if (resume.failure() instanceof java.util.concurrent.CancellationException) {
                        cleanup.incrementAndGet();
                    }
                    return OresScheduler.done(1);
                });
                awaitWaiter(producer);
                CountDownLatch go = new CountDownLatch(1);
                AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
                Thread producerThread = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        producer.completeFromRuntime(1);
                    } catch (Throwable t) {
                        backgroundFailure.compareAndSet(null, t);
                    }
                });
                Thread cancelThread = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        scheduler.requestStructuredCancellation(task);
                    } catch (Throwable t) {
                        backgroundFailure.compareAndSet(null, t);
                    }
                });
                go.countDown();
                producerThread.join();
                cancelThread.join();
                assertNull(backgroundFailure.get());
                try {
                    task.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                    assertInstanceOf(java.util.concurrent.CancellationException.class,
                            expected.getCause());
                }
                assertEquals(2, resumes.get(), "resume must happen at most once per settled await");
                assertTrue(cleanup.get() <= 1);
            }
        }
    }
}
