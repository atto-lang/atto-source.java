package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Host Future blocking bridges may never park an actor or scheduler carrier. */
final class OresFutureBlockingBridgeTest {
    @Test
    void schedulerCarrierRejectsEveryPendingBlockingBridgeWithoutConsumingTheProducer() {
        try (var scheduler = new OresScheduler(1)) {
            var checked = scheduler.startSync(() -> {
                OresFuture<Integer> pending = new OresFuture<>();
                assertThrows(IllegalStateException.class, pending::join);
                assertThrows(IllegalStateException.class, pending::get);
                assertThrows(IllegalStateException.class,
                        () -> pending.get(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,
                        () -> pending.get(0, TimeUnit.SECONDS),
                        "zero-timeout observation cannot park the carrier");
                assertEquals(0, pending.pendingRuntimeWaiterCount(),
                        "denied blocking calls must not allocate parked subscribers");
                assertFalse(pending.isDone(), "rejecting a host bridge cannot cancel the producer");

                var completed = OresFuture.completed(42);
                assertEquals(42, completed.join());
                assertEquals(42, completed.get());
                assertEquals(42, completed.get(1, TimeUnit.SECONDS));
                return true;
            });
            assertTrue(checked.join());
            assertEquals(17, scheduler.startSync(() -> 17).join(),
                    "rejected bridge cannot stall later scheduler work");
        }
    }

    @Test
    void repeatedHostTimeoutsDoNotRetainCallbacksOrCancelProducer() {
        OresFuture<Integer> pending = new OresFuture<>();
        for (int i = 0; i < 1024; i++) {
            assertThrows(TimeoutException.class,
                    () -> pending.get(1, TimeUnit.NANOSECONDS));
            assertEquals(0, pending.pendingRuntimeWaiterCount(),
                    "timed host reads must release their waiter even if producer never settles");
        }
        assertFalse(pending.isDone());
        assertTrue(pending.completeFromRuntime(23));
        assertEquals(23, pending.join());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
    }

    @Test
    void interruptedHostWaitDetachesWithoutCancellingProducer() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread host = new Thread(() -> {
            started.countDown();
            try {
                pending.get();
                unexpected.set(new AssertionError("a pending Future must not complete"));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        }, "host-future-get-interruption");
        host.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pending.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, pending.pendingRuntimeWaiterCount());
        } finally {
            host.interrupt();
            host.join(5_000);
        }
        assertFalse(host.isAlive(), "host must terminate after interrupt");
        assertNull(unexpected.get());
        assertTrue(interrupted.get());
        assertEquals(0, pending.pendingRuntimeWaiterCount(),
                "interrupted get must not retain a callback on a suspended producer");
        assertFalse(pending.isDone());
        assertTrue(pending.completeFromRuntime(7));
        assertEquals(7, pending.join());
    }
}
