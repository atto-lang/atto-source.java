package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-facing Future.get() must not leave abandoned callbacks on a producer
 * that remains pending after timeout or interruption.
 */
final class OresFutureHostWaiterDetachmentTest {
    @Test
    @Timeout(15)
    void repeatedTimedGetOnPendingProducerNeverRetainsWaiters() {
        OresFuture<Integer> producer = new OresFuture<>();
        for (int i = 0; i < 1_024; i++) {
            assertThrows(TimeoutException.class,
                    () -> producer.get(1L, TimeUnit.NANOSECONDS));
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "timed polling must detach each host waiter immediately");
        }
        assertFalse(producer.isDone(),
                "timing out a host wait must not cancel or complete its producer");
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertThrows(TimeoutException.class,
                () -> producer.get(0L, TimeUnit.NANOSECONDS));
        assertEquals(0, producer.pendingRuntimeWaiterCount(),
                "zero-timeout polling should not register at all");
    }

    @Test
    @Timeout(15)
    void interruptedHostGetDetachesWaiterWithoutCancellingSource() throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        CountDownLatch starting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread host = Thread.startVirtualThread(() -> {
            starting.countDown();
            try {
                producer.get();
                unexpected.set(new AssertionError("host get unexpectedly returned"));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        });
        assertTrue(starting.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (producer.pendingRuntimeWaiterCount() == 0
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, producer.pendingRuntimeWaiterCount(),
                "host must be suspended with exactly one runtime waiter");
        host.interrupt();
        host.join(5_000L);
        assertFalse(host.isAlive(), "interrupted host failed to exit");
        assertNull(unexpected.get(), () -> "host failed: " + unexpected.get());
        assertTrue(interrupted.get());
        assertFalse(producer.isDone());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertTrue(producer.completeFromRuntime(42));
        assertEquals(42, producer.get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelledCompletionStageBridgeReleasesObserverButDoesNotCancelProducer()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        java.util.concurrent.CompletableFuture<Integer> observer =
                producer.asCompletionStage().toCompletableFuture();
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(observer.cancel(false));
        assertTrue(observer.isCancelled());
        assertEquals(0, producer.pendingRuntimeWaiterCount(),
                "canceling the bridge must unlink its producer listener");
        assertFalse(producer.isDone(),
                "a host observation bridge must not own producer cancellation");
        assertTrue(producer.completeFromRuntime(31));
        assertEquals(31, producer.get(5, TimeUnit.SECONDS));
        assertTrue(observer.isCancelled());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void completedOrFailedBridgeNeverRetainsProducerListeners() throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        java.util.concurrent.CompletableFuture<Integer> observer =
                producer.asCompletionStage().toCompletableFuture();
        assertTrue(producer.completeFromRuntime(29));
        assertEquals(29, observer.get(5, TimeUnit.SECONDS));
        assertEquals(0, producer.pendingRuntimeWaiterCount());

        OresFuture<Integer> rejected = OresFuture.failed(
                new IllegalArgumentException("invalid"));
        java.util.concurrent.CompletableFuture<Integer> failedObserver =
                rejected.asCompletionStage().toCompletableFuture();
        java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> failedObserver.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(0, rejected.pendingRuntimeWaiterCount());
    }

    @Test
    @Timeout(20)
    void racingHostTimeoutAndProducerSettlementLeaveNoRetainedWaiters()
            throws Exception {
        for (int round = 0; round < 128; round++) {
            OresFuture<Integer> producer = new OresFuture<>();
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<Throwable> workerError = new AtomicReference<>();
            Thread setter = Thread.startVirtualThread(() -> {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("settler gate timed out");
                    }
                    assertTrue(producer.completeFromRuntime(7));
                } catch (Throwable failure) {
                    workerError.compareAndSet(null, failure);
                }
            });
            gate.countDown();
            try {
                try {
                    assertEquals(7, producer.get(1L, TimeUnit.MILLISECONDS));
                } catch (TimeoutException expectedRace) {
                    // Settlement is allowed after a host's timeout.
                }
            } finally {
                setter.join(5_000L);
            }
            assertFalse(setter.isAlive(), "producer stalled in round " + round);
            assertNull(workerError.get(),
                    () -> "producer failed: " + workerError.get());
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "host timeout/completion race leaked a waiter in round " + round);
            assertEquals(7, producer.join());
        }
    }
}
