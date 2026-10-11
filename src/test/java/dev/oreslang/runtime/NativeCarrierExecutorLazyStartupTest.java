package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class NativeCarrierExecutorLazyStartupTest {
    @Test
    void descriptorOnlyPoolStartsNoPthreadsUntilWorkArrives() throws Exception {
        var latch = new CountDownLatch(1);
        NativeCarrierExecutor pool = new NativeCarrierExecutor(
                2, 2, 16, "ores-jni-lazy-regression-");
        try {
            assertEquals(0, pool.getStartedCarrierCount(),
                    "constructing native pool must not start a pthread");
            assertEquals(2, pool.getMaximumPoolSize());
            pool.execute(latch::countDown);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(pool.getStartedCarrierCount() >= 1);
            assertTrue(pool.getStartedCarrierCount() <= 2);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void coldNativePoolCanCloseWithoutEverStartingWorkers() throws Exception {
        NativeCarrierExecutor pool = new NativeCarrierExecutor(
                1, 1, 4, "ores-jni-cold-close-");
        assertEquals(0, pool.getStartedCarrierCount());
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
    }

    @Test
    void concurrentFirstSubmissionsShareOneNativeStart() throws Exception {
        NativeCarrierExecutor pool = new NativeCarrierExecutor(
                2, 2, 32, "ores-jni-concurrent-first-");
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        Thread first = Thread.ofPlatform().start(() -> submitAfter(start, pool, done));
        Thread second = Thread.ofPlatform().start(() -> submitAfter(start, pool, done));
        try {
            assertEquals(0, pool.getStartedCarrierCount());
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS),
                    "both concurrent first submissions must be serviced");
            first.join();
            second.join();
            assertTrue(pool.getStartedCarrierCount() <= 2);
        } finally {
            start.countDown();
            first.join();
            second.join();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void submitAfter(CountDownLatch start, NativeCarrierExecutor pool,
                                    CountDownLatch done) {
        try {
            start.await();
            pool.execute(done::countDown);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
