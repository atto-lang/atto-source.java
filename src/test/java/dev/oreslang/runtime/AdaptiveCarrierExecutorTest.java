package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveCarrierExecutorTest {
    @Test void expandsForIndependentRunnableWorkBeforeQueueing() throws Exception {
        var serial = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) task ->
                Thread.ofPlatform().daemon(true)
                        .name("adaptive-test-" + serial.incrementAndGet()).unstarted(task);
        try (var workers = new AdaptiveCarrierExecutor(2, 5, 8, factory)) {
            assertEquals(2, workers.minimumCarriers());
            assertEquals(2, workers.prestartAllCoreThreads());
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(5);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int i = 0; i < 5; i++) {
                    tasks.add(workers.submit(() -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("carrier launch timed out");
                        return null;
                    }));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(5, workers.getPoolSize());
                assertTrue(workers.getLargestPoolSize() <= 5);
            } finally {
                release.countDown();
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void boundedReadyQueueRejectsWithoutCallerRuns() throws Exception {
        var factory = (java.util.concurrent.ThreadFactory) task ->
                Thread.ofPlatform().daemon(true).unstarted(task);
        try (var workers = new AdaptiveCarrierExecutor(1, 1, 1, factory)) {
            workers.prestartAllCoreThreads();
            var busy = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                workers.execute(() -> {
                    busy.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                assertTrue(busy.await(5, TimeUnit.SECONDS));
                workers.execute(() -> { });
                assertThrows(RejectedExecutionException.class,
                        () -> workers.execute(() -> { }));
            } finally {
                release.countDown();
            }
        }
    }

    @Test void runnableBurstDoesNotDependOnWorkerActiveSampling() throws Exception {
        // All newly created workers deliberately wait before entering the
        // ThreadPoolExecutor worker body. The old getActiveCount()+queue
        // heuristic incorrectly stopped growing when newly started carriers
        // had not yet transitioned to ACTIVE. Admission-based growth cannot.
        var permitWorkerStart = new CountDownLatch(1);
        var created = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) runnable ->
                Thread.ofPlatform().daemon(true)
                        .name("adaptive-lagged-worker-" + created.incrementAndGet())
                        .unstarted(() -> {
                            try {
                                if (!permitWorkerStart.await(10, TimeUnit.SECONDS))
                                    throw new AssertionError("worker startup gate timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            runnable.run();
                        });
        try (var workers = new AdaptiveCarrierExecutor(2, 20, 32, factory)) {
            assertEquals(2, workers.prestartAllCoreThreads());
            var submitted = new ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int i = 0; i < 20; i++)
                    submitted.add(workers.submit(() -> { }));
                assertEquals(20, workers.getPoolSize(),
                        "all 20 admitted runnable turns need available carriers even before ACTIVE");
                assertEquals(20, workers.getLargestPoolSize());
                assertEquals(20, workers.getCorePoolSize());
            } finally {
                permitWorkerStart.countDown();
            }
            for (var future : submitted) future.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void noGrowthAdmissionReusesWarmJvmCarrierWithoutStartingAnother() throws Exception {
        var created = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) task ->
                Thread.ofPlatform().daemon(true)
                        .name("adaptive-no-growth-" + created.incrementAndGet()).unstarted(task);
        try (var workers = new AdaptiveCarrierExecutor(1, 3, 8, factory)) {
            assertEquals(1, workers.prestartAllCoreThreads());
            assertEquals(1, workers.getPoolSize());

            var occupied = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var later = new CountDownLatch(1);
            workers.execute(() -> {
                occupied.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));

            workers.executeWithoutCarrierGrowth(later::countDown);
            assertFalse(later.await(100, TimeUnit.MILLISECONDS),
                    "no-growth admission must queue behind the warm carrier, not start another thread");
            assertEquals(1, workers.getPoolSize());
            assertEquals(1, workers.getLargestPoolSize());
            assertEquals(1, workers.getCorePoolSize());

            release.countDown();
            assertTrue(later.await(2, TimeUnit.SECONDS));
            assertEquals(1, created.get(),
                    "reactor-safe admission must reuse the existing JVM carrier");
        }
    }

    @Test void noGrowthAdmissionRejectsColdJvmPoolWithoutCreatingCarrier() {
        var created = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) task -> {
            created.incrementAndGet();
            return Thread.ofPlatform().daemon(true).unstarted(task);
        };
        try (var workers = new AdaptiveCarrierExecutor(1, 3, 8, factory)) {
            assertEquals(0, workers.getPoolSize());
            assertThrows(RejectedExecutionException.class,
                    () -> workers.executeWithoutCarrierGrowth(() -> {}));
            assertEquals(0, created.get());
            assertEquals(0, workers.getPoolSize());
        }
    }

    @Test void zeroMinimumStartsColdAndGrowsForRunnableWork() throws Exception {
        var created = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) task ->
                Thread.ofPlatform().daemon(true)
                        .name("adaptive-cold-" + created.incrementAndGet())
                        .unstarted(task);
        try (var workers = new AdaptiveCarrierExecutor(0, 3, 8, factory)) {
            assertEquals(0, workers.minimumCarriers());
            assertEquals(0, workers.getPoolSize());
            assertEquals(0, workers.prestartAllCoreThreads(),
                    "zero-resident pool must not create a bootstrap carrier");

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var task = workers.submit(() -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, workers.getPoolSize(),
                    "first runnable turn creates exactly one carrier");
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsInvalidLimits() {
        var factory = (java.util.concurrent.ThreadFactory) task -> new Thread(task);
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCarrierExecutor(-1, 5, 1, factory));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCarrierExecutor(0, 0, 1, factory));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCarrierExecutor(3, 2, 1, factory));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCarrierExecutor(1, 257, 1, factory));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCarrierExecutor(1, 3, 0, factory));
    }
}
