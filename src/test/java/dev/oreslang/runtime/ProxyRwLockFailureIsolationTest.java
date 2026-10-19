package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only lease settlement/retirement checks; not guest actor scheduling. */
@Timeout(10)
final class ProxyRwLockFailureIsolationTest {
    @Test
    void throwingSubscriberCannotStrandLaterReservedReaderBatch() throws Exception {
        var gate = new ProxyRwLock();
        var writer = gate.acquireAsync(true).get(1, TimeUnit.SECONDS);
        var first = gate.acquireAsync(false);
        var second = gate.acquireAsync(false);
        var third = gate.acquireAsync(false);
        var observed = new AtomicInteger();
        first.whenCompleteRuntime((lease, failure) -> {
            observed.incrementAndGet();
            throw new AssertionError("injected observer failure");
        });
        second.whenCompleteRuntime((lease, failure) -> observed.incrementAndGet());
        third.whenCompleteRuntime((lease, failure) -> observed.incrementAndGet());

        writer.close();
        assertEquals(3, observed.get(), "all FIFO readers must be published");
        assertTrue(first.isDone() && second.isDone() && third.isDone());
        assertEquals(3, gate.activeReaders());
        first.get(1, TimeUnit.SECONDS).close();
        second.get(1, TimeUnit.SECONDS).close();
        third.get(1, TimeUnit.SECONDS).close();
        assertTrue(gate.idle());
    }

    @Test
    void failureDeliveryAndIndependentRetirementCallbacksAreExhaustive() throws Exception {
        var gate = new ProxyRwLock();
        var writer = gate.acquireAsync(true).get(1, TimeUnit.SECONDS);
        var a = gate.acquireAsync(false);
        var b = gate.acquireAsync(true);
        var c = gate.acquireAsync(false);
        var reported = new AtomicInteger();
        a.whenCompleteRuntime((ignored, failure) -> {
            reported.incrementAndGet();
            throw new AssertionError("first failure observer");
        });
        b.whenCompleteRuntime((ignored, failure) -> reported.incrementAndGet());
        c.whenCompleteRuntime((ignored, failure) -> reported.incrementAndGet());

        gate.failWaiters(new IllegalStateException("actor domain STOP"));
        assertEquals(3, reported.get());
        for (var waiting : new OresFuture<?>[]{a, b, c}) {
            assertTrue(waiting.isCompletedExceptionally());
            assertThrows(ExecutionException.class,
                    () -> waiting.get(1, TimeUnit.SECONDS));
        }
        var idleCallbacks = new AtomicInteger();
        gate.whenIdle(() -> {
            idleCallbacks.incrementAndGet();
            throw new IllegalStateException("failed retirement bookkeeping");
        });
        gate.whenIdle(idleCallbacks::incrementAndGet);
        writer.close();
        assertEquals(2, idleCallbacks.get(), "one failing callback cannot skip another");
        assertTrue(gate.idle());
        assertEquals(0, gate.queuedWaiters());
    }
}
