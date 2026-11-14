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
    void terminalDrainReceiptWaitsForBlockedSubscriberDespiteVisibleSettlement()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        var receipts = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        producer.whenCompleteRuntime((value, error) -> {
            subscriberEntered.countDown();
            try {
                if (!releaseSubscriber.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("subscriber release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        producer.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
        Thread completing = Thread.startVirtualThread(() -> {
            try {
                assertTrue(producer.completeFromRuntime(42));
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });
        try {
            assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS));
            assertTrue(producer.isDone(), "terminal CAS precedes subscriber return");
            producer.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
            assertEquals(0, receipts.get(),
                    "neither receipt may release an actor arena during notification");
        } finally {
            releaseSubscriber.countDown();
        }
        completing.join(3_000);
        assertFalse(completing.isAlive());
        assertNull(failure.get());
        assertEquals(2, receipts.get(),
                "both receipts registered before the drain run exactly once");
        producer.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
        assertEquals(3, receipts.get(),
                "late drain receipt must complete immediately and exactly once");
    }

    @Test
    void receiptRegistrationRacingWithSettlementNeverGetsLost()
            throws Exception {
        for (int attempt = 0; attempt < 64; attempt++) {
            OresFuture<Integer> producer = new OresFuture<>();
            var callbackCount = new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch begin = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread settler = Thread.startVirtualThread(() -> {
                try {
                    if (!begin.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("settlement start timed out");
                    }
                    assertTrue(producer.completeFromRuntime(5));
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                }
            });
            producer.whenNotificationsDrainedRuntime(callbackCount::incrementAndGet);
            begin.countDown();
            producer.whenNotificationsDrainedRuntime(callbackCount::incrementAndGet);
            settler.join(3_000);
            assertFalse(settler.isAlive());
            assertNull(failure.get());
            assertEquals(2, callbackCount.get(), "lost drain receipt in round " + attempt);
        }
    }

    @Test
    void cancellationDrainReceiptWaitsForPhysicalRevoker()
            throws Exception {
        CountDownLatch revokerEntered = new CountDownLatch(1);
        CountDownLatch releaseRevoker = new CountDownLatch(1);
        var receipts = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        OresFuture<Integer> producer = new OresFuture<>(() -> {
            revokerEntered.countDown();
            try {
                if (!releaseRevoker.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("revoker release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        producer.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
        Thread canceller = Thread.startVirtualThread(() -> {
            try {
                assertTrue(producer.cancel(false));
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });
        try {
            assertTrue(revokerEntered.await(3, TimeUnit.SECONDS));
            assertTrue(producer.isCancelled(), "terminal state is already visible");
            assertEquals(0, receipts.get(),
                    "physical revocation must precede notification drain");
            producer.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
            assertEquals(0, receipts.get());
        } finally {
            releaseRevoker.countDown();
        }
        canceller.join(3_000);
        assertFalse(canceller.isAlive());
        assertNull(failure.get());
        assertEquals(2, receipts.get(),
                "both drain observers run once after revoker completion");
    }

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
