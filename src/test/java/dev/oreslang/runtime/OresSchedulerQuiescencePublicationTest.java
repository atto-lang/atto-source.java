package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerQuiescencePublicationTest {
    @Test
    void lateCancellationCannotNotifyWhileQuiescenceCallbackIsStillActive() throws Exception {
        CountDownLatch retirementEntered = new CountDownLatch(1);
        CountDownLatch releaseRetirement = new CountDownLatch(1);
        AtomicInteger retirements = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>)
                            resume -> OresScheduler.done(42),
                    () -> {
                        retirements.incrementAndGet();
                        retirementEntered.countDown();
                        try {
                            if (!releaseRetirement.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("quiescence release timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    });
            try {
                assertTrue(retirementEntered.await(5, TimeUnit.SECONDS));
                assertTrue(result.cancel(false),
                        "the task Future is still pending while retirement is active");
                assertFalse(result.isDone(),
                        "accepted cancellation must not publish before quiescence completes");
                assertFalse(result.isCancelled());
            } finally {
                releaseRetirement.countDown();
            }
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            assertEquals(1, retirements.get(), "lifecycle retirement must run exactly once");
        }
    }
}
