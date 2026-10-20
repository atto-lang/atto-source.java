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

    @Test void registeredButNotEnteredWorkerIsNotWarmForNoGrowthAdmission()
            throws Exception {
        var permitWorkerLoop = new CountDownLatch(1);
        var wrapperEntered = new CountDownLatch(1);
        var created = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) workerLoop ->
                Thread.ofPlatform().daemon(true)
                        .name("adaptive-registered-not-started-" + created.incrementAndGet())
                        .unstarted(() -> {
                            wrapperEntered.countDown();
                            try {
                                if (!permitWorkerLoop.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("worker-loop gate timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            workerLoop.run();
                        });

        try (var workers = new AdaptiveCarrierExecutor(1, 2, 8, factory)) {
            workers.execute(() -> { });
            assertTrue(wrapperEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, workers.getPoolSize(),
                    "ThreadPoolExecutor may register a worker before its loop enters");
            assertEquals(0, workers.startedWorkerCount(),
                    "registered worker is not yet safe reusable reactor capacity");
            assertFalse(workers.readyForNoGrowthAdmission());
            assertThrows(RejectedExecutionException.class,
                    () -> workers.executeWithoutCarrierGrowth(() -> {}),
                    "reactor-safe path must reject a registered-but-not-entered worker");

            permitWorkerLoop.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (workers.startedWorkerCount() == 0 && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertEquals(1, workers.startedWorkerCount());
            assertTrue(workers.readyForNoGrowthAdmission());

            var delivered = new CountDownLatch(1);
            workers.executeWithoutCarrierGrowth(delivered::countDown);
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(1, created.get(),
                    "no-growth reuse must not create a replacement worker");
        } finally {
            permitWorkerLoop.countDown();
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

    @Test void noGrowthAdmissionRejectsContendedNormalStartupWithoutParking()
            throws Exception {
        var created = new AtomicInteger();
        var factory = (java.util.concurrent.ThreadFactory) task ->
                Thread.ofPlatform().daemon(true)
                        .name("adaptive-nonparking-" + created.incrementAndGet()).unstarted(task);
        try (var workers = new AdaptiveCarrierExecutor(1, 2, 8, factory)) {
            assertEquals(1, workers.prestartAllCoreThreads());
            var lockField = AdaptiveCarrierExecutor.class.getDeclaredField("admission");
            lockField.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(workers);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Thread blockedAdmission = Thread.ofPlatform().start(() -> {
                lock.lock();
                try {
                    entered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    lock.unlock();
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                long start = System.nanoTime();
                assertThrows(RejectedExecutionException.class,
                        () -> workers.executeWithoutCarrierGrowth(() -> {}),
                        "host-only completion cannot wait behind carrier admission/startup");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
                assertEquals(1, workers.getPoolSize());
                assertEquals(1, created.get());
            } finally {
                release.countDown();
                blockedAdmission.join(3000);
                assertFalse(blockedAdmission.isAlive());
            }

            var delivered = new CountDownLatch(1);
            workers.executeWithoutCarrierGrowth(delivered::countDown);
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(1, created.get(),
                    "rejected and accepted non-growth calls must not create more workers");
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

    @Test void shutdownNowUnwrapsQueuedNoGrowthTasksAndRetiresCredits()
            throws Exception {
        var factory = (java.util.concurrent.ThreadFactory) task ->
                Thread.ofPlatform().daemon(true).name("adaptive-shutdown-model").unstarted(task);
        var workers = new AdaptiveCarrierExecutor(1, 1, 8, factory);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Runnable pending = () -> { };
        try {
            assertEquals(1, workers.prestartAllCoreThreads());
            workers.execute(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            workers.executeWithoutCarrierGrowth(pending);

            List<Runnable> abandoned = workers.shutdownNow();
            assertEquals(List.of(pending), abandoned,
                    "shutdown must expose the original host task, not its identity wrapper");
            release.countDown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            workers.shutdownNow();
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
