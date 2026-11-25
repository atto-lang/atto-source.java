package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic queued-dispatch cancellation reproducer.
 * Cancelled wrappers must skip context entry, and claimed wrappers must
 * hold physical quiescence until context admission unwinds.
 */
final class SchedulerQueuedCancellationQuiescenceTest {
    @Test
    void cancellationRevokesQueuedCarrierBeforeContextEntry() {
        Queue<Runnable> admitted = new ArrayDeque<>();
        AtomicInteger contextEntries = new AtomicInteger();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "cancel-admission-test", 1,
                admitted::add,
                turn -> {
                    contextEntries.incrementAndGet();
                    turn.run();
                });
        try {
            TaskHandle<Void> child = TaskHandle.start(scheduler,
                    new OresScheduler.CleanupAwareTask<>() {
                        @Override
                        public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                            return OresScheduler.done(null);
                        }
                    });
            assertEquals(1, admitted.size(), "initial dispatch must be accepted");
            assertTrue(child.cancel("cancel before first entered turn"));
            assertTrue(child.getDone().isDone(),
                    "a queued wrapper loses context admission when cancellation wins");

            Runnable admittedTurn = admitted.remove();
            admittedTurn.run();

            assertEquals(0, contextEntries.get());
        } finally {
            while (!admitted.isEmpty()) admitted.remove().run();
            scheduler.close();
        }
    }

    @Test
    void cancellationWaitsForClaimedContextAdmissionToExit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger guestExecutions = new AtomicInteger();
        try (var scheduler = OresScheduler.runtimeOwned(
                "cancel-claimed-context", 1, task -> Thread.ofPlatform().start(task),
                turn -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    turn.run();
                })) {
            var task = scheduler.start(resume -> {
                guestExecutions.incrementAndGet();
                return OresScheduler.done(1);
            }, retired::countDown);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(task.cancel(false));
                assertEquals(1, retired.getCount());
            } finally {
                release.countDown();
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(0, guestExecutions.get());
        }
    }
}
