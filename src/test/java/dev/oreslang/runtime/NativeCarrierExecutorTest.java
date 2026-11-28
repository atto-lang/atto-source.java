package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    void nativeStartupRegistersEveryCarrierAndShutdownWaitsForPthreadJoins() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        // core=1, max=4: three compensation pthreads are parked, but must
        // still enter their registered JNI carrier loops before startup returns.
        NativeCarrierExecutor executor = new NativeCarrierExecutor(
                1, 4, 32, "ores-native-reaper-proof-" + System.nanoTime() + "-");
        try {
            assertEquals(0, executor.registeredCarrierCount());
            assertFalse(executor.isStarted());
            assertFalse(executor.awaitTermination(0, TimeUnit.SECONDS),
                    "awaitTermination must not succeed before shutdown");
            assertTrue(executor.ensureStarted());
            assertEquals(4, executor.registeredCarrierCount(),
                    "all JNI callbacks must be entered before successful startup");
            assertFalse(executor.isTerminated());
            assertFalse(executor.awaitTermination(0, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                "native reaper must join all four pthreads before reporting termination");
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.registeredCarrierCount());
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                executor::ensureStarted);
    }

    @Test
    void shutdownWithoutFirstActorNeedsNoNativeReaper() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        NativeCarrierExecutor executor = new NativeCarrierExecutor(
                1, 2, 16, "ores-native-no-start-" + System.nanoTime() + "-");
        assertFalse(executor.isStarted());
        assertFalse(executor.isTerminated());
        executor.shutdownNow();
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(0, TimeUnit.SECONDS));
        assertEquals(0, executor.registeredCarrierCount());
    }

    @Test
    void concurrentNativeStartAndShutdownAwaitActualPthreadJoins() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        for (int round = 0; round < 12; round++) {
            NativeCarrierExecutor executor = new NativeCarrierExecutor(
                    1, 2, 32, "ores-native-start-close-" + round + "-");
            var callers = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                var bootstrap = callers.submit(() -> {
                    go.await();
                    try {
                        executor.ensureStarted();
                    } catch (RejectedExecutionException | IllegalStateException closed) {
                        // Shutdown may win before the first native pool exists.
                    }
                    return null;
                });
                var close = callers.submit(() -> {
                    go.await();
                    executor.shutdownNow();
                    return null;
                });
                go.countDown();
                bootstrap.get(15, TimeUnit.SECONDS);
                close.get(15, TimeUnit.SECONDS);

                assertTrue(executor.isShutdown());
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                        "physical pthreads must be joined before termination is certified");
                assertTrue(executor.isTerminated());
                assertEquals(0, executor.registeredCarrierCount());
                assertThrows(RejectedExecutionException.class, executor::ensureStarted);
            } finally {
                go.countDown();
                callers.shutdownNow();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void runningNoncooperativeTurnPreventsPrematurePthreadJoinReceipt() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        NativeCarrierExecutor executor = new NativeCarrierExecutor(
                1, 1, 8, "ores-native-inflight-retirement-");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leaveTurn = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                entered.countDown();
                // Simulate an uncooperative guest turn: shutdownNow's
                // interruption must not be mistaken for actual retirement.
                while (leaveTurn.getCount() != 0L) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (InterruptedException ignored) {
                        // Keep the turn active until the explicit test release.
                    }
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(executor.shutdownNow().isEmpty());
            assertFalse(executor.isTerminated(),
                    "shutdown request is not a pthread_join receipt");
            assertFalse(executor.awaitTermination(20, TimeUnit.MILLISECONDS),
                    "an active native guest turn must fence physical retirement");
        } finally {
            leaveTurn.countDown();
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.registeredCarrierCount());
    }

    @Test
    void nativeCarrierSelfShutdownReturnsPendingWithoutSelfInterrupt()
            throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        NativeCarrierExecutor executor = new NativeCarrierExecutor(
                1, 1, 8, "ores-native-self-close-");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closeAllowed = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        var abandoned = new java.util.concurrent.atomic.AtomicReference<java.util.List<Runnable>>();
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var pendingRuns = new java.util.concurrent.atomic.AtomicInteger();
        Runnable pending = pendingRuns::incrementAndGet;
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    if (!closeAllowed.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("self-close release was not delivered");
                    }
                    abandoned.set(executor.requestShutdownFromCarrier());
                } catch (Throwable failure) {
                    failed.set(failure);
                } finally {
                    done.countDown();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            executor.execute(pending);
            closeAllowed.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNull(failed.get(), () -> String.valueOf(failed.get()));
            assertEquals(java.util.List.of(pending), abandoned.get());
            assertEquals(0, pendingRuns.get());
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(executor.isTerminated());
        } finally {
            closeAllowed.countDown();
            executor.shutdownNow();
        }
    }
}
