package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerCleanupCloseTest {
    @Test
    void schedulerCloseDoesNotPublishFailureBeforeDeferredCancellation() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Integer> gate = new OresFuture<>();
            OresFuture<Integer> cleanup = new OresFuture<>();
            CountDownLatch initial = new CountDownLatch(1);
            CountDownLatch cleaning = new CountDownLatch(1);
            OresFuture<Integer> task = scheduler.start(
                    new OresScheduler.CleanupAwareTask<>() {
                        int pc;
                        @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume event) {
                            if (pc++ == 0) {
                                initial.countDown();
                                return OresScheduler.await(gate);
                            }
                            assertInstanceOf(CancellationException.class, event.failure());
                            cleaning.countDown();
                            return OresScheduler.await(cleanup);
                        }
                    });
            assertTrue(initial.await(5, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            scheduler.close();
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertTrue(task.isCancelled());
            assertTrue(java.util.Arrays.stream(cancelled.getSuppressed())
                    .anyMatch(e -> e instanceof RejectedExecutionException),
                    "incomplete cleanup must remain observable as a diagnostic");
        } finally {
            scheduler.close();
        }
    }

    @Test
    void failedCleanupCannotReplaceAcceptedCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> gate = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<Integer> task = scheduler.start(
                    new OresScheduler.CleanupAwareTask<>() {
                        int pc;
                        @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume event) {
                            if (pc++ == 0) {
                                entered.countDown();
                                return OresScheduler.await(gate);
                            }
                            assertInstanceOf(CancellationException.class, event.failure());
                            throw new IllegalStateException("cleanup failed");
                        }
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertTrue(java.util.Arrays.stream(cancelled.getSuppressed())
                    .anyMatch(e -> e instanceof IllegalStateException
                            && "cleanup failed".equals(e.getMessage())));
        }
    }
}
