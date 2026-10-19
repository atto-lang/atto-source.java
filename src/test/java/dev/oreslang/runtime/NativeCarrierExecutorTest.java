package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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


    @Test
    void cachedHardAffinityRepairsRealCarrierDrift() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux"));
        int[] cpus = allowedLinuxCpus();
        assumeTrue(cpus.length >= 2);
        int home = cpus[0];
        int foreign = cpus[1];

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 16, "ores-native-drift-test-", home)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            executor.execute(() -> {
                try {
                    assertEquals(home, NativeCarrierExecutor.currentCpu());
                    // Simulate native/FFI code changing this pthread's mask without
                    // updating Oreslang's carrier-local affinity cache.
                    NativeCarrierExecutor.bindCurrentThreadToCpu(foreign);
                    assertEquals(foreign, NativeCarrierExecutor.currentCpu());
                    NativeCarrierExecutor.ensureCurrentCarrierCpu(home);
                    assertEquals(home, NativeCarrierExecutor.currentCpu(),
                            "observed CPU drift must invalidate the cached binding");
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    done.countDown();
                }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    private static int[] allowedLinuxCpus() throws Exception {
        String status = Files.readString(Path.of("/proc/self/status"));
        String line = status.lines()
                .filter(value -> value.startsWith("Cpus_allowed_list:"))
                .findFirst()
                .orElseThrow();
        String spec = line.substring(line.indexOf(':') + 1).trim();
        ArrayList<Integer> cpus = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] range = part.split("-");
            int first = Integer.parseInt(range[0]);
            int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            for (int cpu = first; cpu <= last; cpu++) cpus.add(cpu);
        }
        return cpus.stream().mapToInt(Integer::intValue).toArray();
    }

}
