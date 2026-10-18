package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host get() must release its subscriber on every early exit. */
@Timeout(15)
final class OresFutureHostWaiterCleanupTest {

    @Test
    void repeatedTimedOutGetsDoNotRetainWaitersOrCancelProducer() throws Exception {
        var producer = new OresFuture<Integer>();
        for (int i = 0; i < 512; i++) {
            assertThrows(TimeoutException.class,
                    () -> producer.get(1, TimeUnit.NANOSECONDS));
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "timed host reads must not retain waiters");
        }
        assertFalse(producer.isDone(), "polling must not cancel shared producer");
        assertTrue(producer.completeFromRuntime(42));
        assertEquals(42, producer.get(5, TimeUnit.SECONDS));
    }

    @Test
    void interruptedBlockingGetReclaimsWaiterWithoutCancellingProducer()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var started = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread reader = Thread.startVirtualThread(() -> {
            started.countDown();
            try {
                producer.get();
                failure.set(new AssertionError("pending get unexpectedly completed"));
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (producer.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, producer.pendingRuntimeWaiterCount());
            reader.interrupt();
            reader.join(5_000);
            assertFalse(reader.isAlive(), "interrupted host reader did not exit");
            assertNull(failure.get());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertFalse(producer.isDone());
        } finally {
            reader.interrupt();
            reader.join(5_000);
        }
        assertTrue(producer.completeFromRuntime(43));
        assertEquals(43, producer.get(5, TimeUnit.SECONDS));
    }

    @Test
    void terminalSettlementRacingWithHostTimeoutDoesNotStrandWaiters()
            throws Exception {
        for (int round = 0; round < 64; round++) {
            var producer = new OresFuture<Integer>();
            var failure = new AtomicReference<Throwable>();
            Thread reader = Thread.startVirtualThread(() -> {
                try {
                    producer.get(1, TimeUnit.MILLISECONDS);
                } catch (TimeoutException expected) {
                    // Producer may still be pending at the timeout.
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                }
            });
            try {
                assertTrue(producer.completeFromRuntime(round));
                reader.join(5_000);
                assertFalse(reader.isAlive(), "host reader stalled in round " + round);
                assertNull(failure.get());
                assertEquals(0, producer.pendingRuntimeWaiterCount(),
                        "no waiters may remain after timeout/settlement race");
                assertEquals(round, producer.get(5, TimeUnit.SECONDS));
            } finally {
                reader.interrupt();
                reader.join(5_000);
            }
        }
    }
}
