package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native continuation contract: task await must hand a physical carrier
 * back to the scheduler, not park it on a Future.get().
 *
 * This tests the host scheduler and Task handle together; the Oreslang
 * source-level continuation compiler is still not wired.
 */
final class OresTaskCooperativeSchedulerTest {
    @Test
    void oneCarrierExecutesAnotherTaskWhileTheFirstAwaits() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> dependency = new OresFuture<>();
            AtomicInteger resumes = new AtomicInteger();
            OresTask<Integer> first = new OresTask<>(scheduler.start(resume -> {
                if (resumes.getAndIncrement() == 0) {
                    assertTrue(resume.initial());
                    return new OresScheduler.Await<Integer>(dependency);
                }
                assertFalse(resume.initial());
                if (resume.failure() != null) {
                    throw new IllegalStateException(resume.failure());
                }
                return new OresScheduler.Done<>(((Integer) resume.value()) + 1);
            }));

            // A single worker must finish this second task despite the
            // first task having an unresolved dependency.
            OresTask<Integer> second = new OresTask<>(scheduler.startSync(() -> 17));
            assertEquals(17, AsyncRuntime.await(second.asFuture()));
            assertFalse(first.isDone());
            assertEquals(1, resumes.get());

            dependency.completeFromRuntime(41);
            assertEquals(42, AsyncRuntime.await(first.asFuture()));
            assertEquals(2, resumes.get());
        }
    }

    @Test
    void cancellationOfAnAwaitingTaskDoesNotResumeItsGuestBody() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> unresolved = new OresFuture<>();
            AtomicInteger resumes = new AtomicInteger();
            OresTask<Integer> task = new OresTask<>(scheduler.start(resume -> {
                resumes.incrementAndGet();
                return new OresScheduler.Await<Integer>(unresolved);
            }));

            assertTrue(task.cancel());
            assertTrue(task.isCancelled());
            unresolved.completeFromRuntime(1);
            assertThrows(CancellationException.class,
                    () -> AsyncRuntime.await(task.asFuture()));
            // If the first execution raced cancellation before admission,
            // zero resumes is valid. Cancellation must never resume a
            // previously suspended guest frame after settlement.
            assertTrue(resumes.get() <= 1);
        }
    }

    @Test
    void ownerClosingSchedulerCancelsUnresolvedTasks() {
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Integer> never = new OresFuture<>();
            OresTask<Integer> task = new OresTask<>(scheduler.start(resume ->
                    new OresScheduler.Await<Integer>(never)));
            scheduler.close();
            assertTrue(task.isDone());
            assertTrue(task.isCancelled());
            assertThrows(CancellationException.class,
                    () -> AsyncRuntime.await(task.asFuture()));
        } finally {
            scheduler.close();
        }
    }
}
