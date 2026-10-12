package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exact cancellation/producer arbitration across logical scheduler turns. */
final class OresSchedulerCancellationRaceTest {
    @Test
    void producerCompletionRacingCancellationNeverBecomesDuplicateResumeFailure()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2);
             ExecutorService producers = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 192; ++iteration) {
                OresFuture<Integer> awaited = new OresFuture<>();
                AtomicInteger resumptions = new AtomicInteger();
                OresFuture<Integer> task = scheduler.start(
                        new OresScheduler.CancellationAwareTask<>() {
                            @Override
                            public boolean cleanupInProgress() {
                                return false;
                            }

                            @Override
                            public void cancellationRequested(
                                    OresScheduler.TaskCancelledException ignored) { }

                            @Override
                            public OresScheduler.Step<Integer> resume(
                                    OresScheduler.Resume input) {
                                if (input.initial()) return OresScheduler.await(awaited);
                                resumptions.incrementAndGet();
                                return OresScheduler.done(42);
                            }
                        });

                // Ensure both host operations race a *registered* suspended
                // continuation; do not let a hot completed Future mask the race.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (awaited.pendingRuntimeWaiterCount() != 1
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(1, awaited.pendingRuntimeWaiterCount(),
                        "iteration " + iteration + " did not arm its await");

                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> cancellation = producers.submit(() -> {
                    start.await();
                    return task.cancel(false);
                });
                Future<Boolean> completion = producers.submit(() -> {
                    start.await();
                    return awaited.completeFromRuntime(7);
                });
                start.countDown();

                boolean cancelled = cancellation.get(5, TimeUnit.SECONDS);
                assertTrue(completion.get(5, TimeUnit.SECONDS));
                try {
                    int result = task.get(5, TimeUnit.SECONDS);
                    assertFalse(cancelled,
                            "accepted cancellation must win terminal publication");
                    assertEquals(42, result);
                } catch (CancellationException expected) {
                    assertTrue(cancelled,
                            "a cancelled result requires admitted cancellation");
                } catch (ExecutionException wrongFailure) {
                    fail("producer/cancellation arbitration failed in iteration "
                            + iteration, wrongFailure);
                }
                assertEquals(1, resumptions.get(),
                        "every started suspended task must re-enter exactly once");
                assertEquals(0, awaited.pendingRuntimeWaiterCount(),
                        "no producer waiter may outlive task completion");
            }
        }
    }
}
