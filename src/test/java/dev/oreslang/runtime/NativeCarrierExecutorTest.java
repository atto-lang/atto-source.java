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

    @Test
    void hardAffinityCarrierIsPinnedBeforeItsFirstRunnable() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux"));
        int cpu = NativeCarrierExecutor.currentCpu();
        assertTrue(cpu >= 0);
        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 16, "ores-native-pinned-test-", cpu)) {
            CountDownLatch done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger observed =
                    new java.util.concurrent.atomic.AtomicInteger(-1);
            executor.execute(() -> {
                observed.set(NativeCarrierExecutor.currentCpu());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(cpu, observed.get(),
                    "startup pinning must complete before the first submitted runnable");
        }
    }

    @Test
    void startupPinningRejectsMultiCarrierPools() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux"));
        int cpu = NativeCarrierExecutor.currentCpu();
        assertTrue(cpu >= 0);
        assertThrows(IllegalArgumentException.class,
                () -> new NativeCarrierExecutor(1, 2, 16, "ores-native-invalid-pin-", cpu));
    }

}
