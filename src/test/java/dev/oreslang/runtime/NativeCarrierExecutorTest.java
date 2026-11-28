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
    void constructionAndUnusedShutdownNeverCreatePthreads() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        NativeCarrierExecutor executor =
                new NativeCarrierExecutor(1, 2, 64, "ores-native-cold-");
        assertEquals(0, executor.getLiveCarrierCount(),
                "eager pool initialization must not start any carrier");
        assertEquals(0, executor.getLargestPoolSize());
        executor.setCorePoolSize(2);
        assertEquals(0, executor.getLiveCarrierCount(),
                "changing a cold pool's parallelism must not start carriers");
        executor.shutdownNow();
        assertEquals(0, executor.getLiveCarrierCount());
        assertTrue(executor.isTerminated());
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> executor.execute(() -> {}));
    }


    @Test
    void coldPoolIsNotTerminatedUntilShutdown() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 4, "ores-native-cold-wait-")) {
            assertEquals(0, executor.getLiveCarrierCount());
            assertFalse(executor.isTerminated(), "a cold but open executor accepts work");
            assertFalse(executor.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "no native threads does not imply executor termination");
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(executor.isTerminated());
            assertEquals(0, executor.getLiveCarrierCount());
        }
    }

    @Test
    void shutdownWaitsForNativeReaperAfterActiveTurn() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 4, "ores-native-reap-wait-")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                executor.execute(() -> {
                    entered.countDown();
                    while (release.getCount() != 0L) {
                        try {
                            release.await(10, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // shutdownNow interrupts the turn; retain it solely
                            // to prove that native retirement awaits its exit.
                        }
                    }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS), "native carrier must start");
                assertFalse(executor.awaitTermination(0, TimeUnit.NANOSECONDS));
                executor.shutdownNow();
                assertFalse(executor.isTerminated(), "joined pthreads are not yet retired");
                assertFalse(executor.awaitTermination(50, TimeUnit.MILLISECONDS),
                        "a live noncooperative task prevents native-pool retirement");
            } finally {
                release.countDown();
            }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "JNI reaper must signal termination after pthread_join");
            assertTrue(executor.isTerminated());
        }
    }

    @Test
    void manyLogicalTurnsMultiplexOntoOnePthreadCarrier() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 64, "ores-native-test-")) {
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
