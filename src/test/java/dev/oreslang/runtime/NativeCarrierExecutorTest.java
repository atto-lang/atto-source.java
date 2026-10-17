package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeCarrierExecutorTest {

    @Test
    void noBootstrapAdmissionRejectsColdNativePoolAndRunsOnlyAfterPublication()
            throws Exception {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        for (int round = 0; round < 24; ++round) {
            var pool = new NativeCarrierExecutor(1, 1, 16, "ores-native-no-bootstrap-");
            try {
                assertFalse(pool.readyForNoBootstrapAdmission());
                assertEquals(0, pool.getStartedCarrierCount());
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> pool.executeWithoutBootstrap(() -> {}));
                assertEquals(0, pool.getStartedCarrierCount(),
                        "rejected no-bootstrap admission must not create pthreads");

                var first = new CountDownLatch(1);
                pool.execute(first::countDown);
                assertTrue(first.await(3, TimeUnit.SECONDS));
                assertTrue(pool.readyForNoBootstrapAdmission());
                assertEquals(1, pool.getStartedCarrierCount());

                var later = new CountDownLatch(1);
                pool.executeWithoutBootstrap(later::countDown);
                assertTrue(later.await(3, TimeUnit.SECONDS));
                assertEquals(1, pool.getStartedCarrierCount(),
                        "host completion admission must reuse already published pthread");
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
                assertFalse(pool.readyForNoBootstrapAdmission());
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> pool.executeWithoutBootstrap(() -> {}));
            }
        }
    }

    @Test
    void noBootstrapAdmissionRespectsNativeReadyQueueSaturation() throws Exception {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        var pool = new NativeCarrierExecutor(1, 1, 1, "ores-native-no-bootstrap-full-");
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                occupied.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(occupied.await(3, TimeUnit.SECONDS));
            assertTrue(pool.readyForNoBootstrapAdmission());
            var queued = new CountDownLatch(1);
            pool.executeWithoutBootstrap(queued::countDown);
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> pool.executeWithoutBootstrap(() -> {}),
                    "bounded native queue must reject without allocating a second carrier");
            assertEquals(1, pool.getStartedCarrierCount());
            release.countDown();
            assertTrue(queued.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void firstAdmissionPublishesEveryNativeCarrierBeforeExecuteReturns() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        for (int round = 0; round < 48; round++) {
            NativeCarrierExecutor executor =
                    new NativeCarrierExecutor(3, 3, 8, "ores-native-publish-");
            try {
                assertEquals(0, executor.getStartedCarrierCount());
                executor.execute(() -> { });
                assertEquals(3, executor.getStartedCarrierCount(),
                        "nativeStart must not return before every pthread publishes its Java carrier identity");
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                        "published native carriers must leave their Java loops during shutdown");
                assertEquals(0, executor.getStartedCarrierCount());
            }
        }
    }

    @Test
    void manyLogicalTurnsMultiplexOntoOnePthreadCarrier() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 64, "ores-native-test-")) {
            assertEquals(0, executor.getStartedCarrierCount(),
                    "throughput constructor must not create a pthread before runnable work");
            CountDownLatch done = new CountDownLatch(32);
            Set<Long> pthreadIds = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < 32; i++) {
                executor.execute(() -> {
                    assertTrue(NativeCarrierExecutor.isNativeCarrierThread());
                    assertFalse(Thread.currentThread().isVirtual());
                    assertEquals(0, NativeCarrierExecutor.currentCarrierSlot());
                    long nativeId = NativeCarrierExecutor.currentNativeThreadId();
                    assertNotEquals(0L, nativeId);
                    pthreadIds.add(nativeId);
                    done.countDown();
                });
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, executor.getStartedCarrierCount(),
                    "first runnable work should start the bounded native carrier");
            assertEquals(1, pthreadIds.size(),
                    "logical Ores turns must multiplex over the configured pthread carrier");

            long accountingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.getCompletedTaskCount() != 32L
                    && System.nanoTime() < accountingDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(32L, executor.getCompletedTaskCount(),
                    "completion accounting must publish after each carrier turn returns");
        }
    }
}
