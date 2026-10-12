package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Producer-independent host waiter retention: a suspended actor's Future may
 * remain pending indefinitely; host polling must not grow its callback queue.
 */
final class OresFutureHostWaiterRetentionTest {
    @Test void thousandsOfTimedWaitsDetachWithoutCancellingProducer() throws Exception {
        var future = new OresFuture<Long>(() -> false, () -> {});
        for (int attempt = 0; attempt < 2_000; attempt++) {
            assertThrows(TimeoutException.class,
                    () -> future.get(1, TimeUnit.MICROSECONDS));
            if ((attempt & 127) == 0)
                assertEquals(0, future.pendingRuntimeWaiterCount(),
                        "timed wait callback retained after timeout " + attempt);
        }
        assertEquals(0, future.pendingRuntimeWaiterCount());
        assertFalse(future.isDone(), "a timed observer cannot cancel its producer");
        assertTrue(future.completeFromRuntime(42L));
        assertEquals(42L, future.get(1, TimeUnit.SECONDS));
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }

    @Test void interruptedBlockingGetDoesNotRetainCallbackOrSettleProducer()
            throws Exception {
        var future = new OresFuture<String>(() -> false, () -> {});
        var failure = new AtomicReference<Throwable>();
        var entered = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            entered.countDown();
            try { future.get(); }
            catch (Throwable t) { failure.set(t); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (future.pendingRuntimeWaiterCount() == 0 && System.nanoTime() < until)
            Thread.yield();
        assertEquals(1, future.pendingRuntimeWaiterCount());
        waiter.interrupt();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertEquals(0, future.pendingRuntimeWaiterCount());
        assertFalse(future.isDone());
        assertTrue(future.completeFromRuntime("ready"));
        assertEquals("ready", future.get());
    }

    @Test void completedAndZeroTimeoutObserversDoNotRetainCallback() throws Exception {
        var future = new OresFuture<String>(() -> false, () -> {});
        for (int i = 0; i < 1000; i++)
            assertThrows(TimeoutException.class, () -> future.get(0, TimeUnit.NANOSECONDS));
        assertEquals(0, future.pendingRuntimeWaiterCount());
        assertTrue(future.failFromRuntime(new IllegalStateException("terminal")));
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, future::get).getCause());
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }
}
