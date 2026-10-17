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
    void awaitTerminationRequiresShutdownEvenIfNoPthreadsEverStarted() throws Exception {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        var pool = new NativeCarrierExecutor(1, 1, 8, "ores-native-await-cold-");
        try {
            assertEquals(0, pool.getStartedCarrierCount());
            assertFalse(pool.isTerminated());
            assertFalse(pool.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "cold construction is not an executor termination event");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(pool.isTerminated());
        }
    }

    @Test
    void shutdownCannotClaimTerminatedWhileNativeBootstrapAdmissionIsOutstanding()
            throws Exception {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        var pool = new NativeCarrierExecutor(1, 1, 8, "ores-native-bootstrap-barrier-");
        var field = NativeCarrierExecutor.class.getDeclaredField("bootstrapAdmissions");
        field.setAccessible(true);
        var attempts = (java.util.concurrent.atomic.AtomicInteger) field.get(pool);
        // Force only the publication window, not a fake Java carrier. This
        // pinpoints the exact false-positive that a pthread attach race
        // previously introduced to isTerminated/awaitTermination.
        attempts.incrementAndGet();
        try {
            pool.shutdownNow();
            assertFalse(pool.isTerminated());
            assertFalse(pool.awaitTermination(20, TimeUnit.MILLISECONDS));
        } finally {
            attempts.decrementAndGet();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(pool.isTerminated());
        }
    }

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
    void noBootstrapAdmissionRejectsContendedFenceWithoutWaiting() throws Exception {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        var pool = new NativeCarrierExecutor(1, 1, 8, "ores-native-admission-lock-");
        try {
            var started = new CountDownLatch(1);
            pool.execute(started::countDown);
            assertTrue(started.await(3, TimeUnit.SECONDS));

            var admission = NativeCarrierExecutor.class.getDeclaredField("queueAdmission");
            admission.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantLock) admission.get(pool);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Thread blocker = Thread.ofPlatform().start(() -> {
                lock.lock();
                try {
                    locked.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    lock.unlock();
                }
            });
            try {
                assertTrue(locked.await(2, TimeUnit.SECONDS));
                long start = System.nanoTime();
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> pool.executeWithoutBootstrap(() -> {}),
                        "selector-originated admission cannot wait for the queue-admission lock");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000,
                        "contended admission must reject rather than park a selector");
                assertEquals(1, pool.getStartedCarrierCount());
            } finally {
                release.countDown();
                blocker.join(3000L);
                assertFalse(blocker.isAlive());
            }

            var delivered = new CountDownLatch(1);
            pool.executeWithoutBootstrap(delivered::countDown);
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownDrainReturnsValueEqualDistinctQueuedTasksExactlyOnce() throws Exception {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        var pool = new NativeCarrierExecutor(1, 1, 4, "ores-native-equal-tasks-");
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        final class EqualRunnable implements Runnable {
            private final int id;
            private EqualRunnable(int id) { this.id = id; }
            @Override public void run() { fail("queued task " + id + " must not run"); }
            @Override public boolean equals(Object other) {
                return other instanceof EqualRunnable;
            }
            @Override public int hashCode() { return 1; }
        }
        try {
            pool.execute(() -> {
                occupied.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(occupied.await(3, TimeUnit.SECONDS));
            Runnable first = new EqualRunnable(1);
            Runnable second = new EqualRunnable(2);
            pool.executeWithoutBootstrap(first);
            pool.executeWithoutBootstrap(second);
            var abandoned = pool.shutdownNow();
            assertEquals(2, abandoned.size());
            assertSame(first, abandoned.get(0),
                    "native shutdown must not confuse value-equal task identities");
            assertSame(second, abandoned.get(1));
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> pool.executeWithoutBootstrap(new EqualRunnable(3)));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
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
