package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureTest {

    @Test
    void lateRuntimeWaiterOnCompletedFutureIsNotRetained() {
        OresFuture<Integer> completed = OresFuture.completed(7);
        AtomicInteger callbacks = new AtomicInteger();

        OresFuture.RuntimeWaiterRegistration registration =
                completed.whenCompleteRuntime((value, failure) -> {
                    assertNull(failure);
                    assertEquals(7, value);
                    callbacks.incrementAndGet();
                });

        assertEquals(1, callbacks.get());
        assertEquals(0, completed.queuedRuntimeWaiters(),
                "already-terminal Future must not retain a runtime waiter");

        registration.detach();
        assertEquals(0, completed.queuedRuntimeWaiters());
    }

    @Test
    void timedGetDetachesWaiterFromNeverCompletingFuture() {
        OresFuture<Integer> pending = new OresFuture<>();

        assertThrows(
                TimeoutException.class,
                () -> pending.get(5, TimeUnit.MILLISECONDS));

        assertEquals(0, pending.queuedRuntimeWaiters(),
                "timed out get must not stay rooted on a Future that may never settle");
    }

    @Test
    void interruptedGetDetachesWaiterFromNeverCompletingFuture() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        AtomicReference<Throwable> observed = new AtomicReference<>();

        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                pending.get();
                fail("pending Future unexpectedly completed");
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pending.queuedRuntimeWaiters() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, pending.queuedRuntimeWaiters());

        waiter.interrupt();
        waiter.join(2_000);

        assertFalse(waiter.isAlive());
        assertInstanceOf(InterruptedException.class, observed.get());
        assertEquals(0, pending.queuedRuntimeWaiters(),
                "interrupted get must unregister its latch callback");
    }

    @Test
    void explicitDetachSuppressesPendingRuntimeCallback() {
        OresFuture<Integer> pending = new OresFuture<>();
        AtomicInteger callbacks = new AtomicInteger();

        OresFuture.RuntimeWaiterRegistration registration =
                pending.whenCompleteRuntime((value, failure) -> callbacks.incrementAndGet());

        assertEquals(1, pending.queuedRuntimeWaiters());
        registration.detach();
        registration.detach();

        assertEquals(0, pending.queuedRuntimeWaiters());
        assertTrue(pending.completeFromRuntime(9));
        assertEquals(0, callbacks.get(),
                "detached runtime callback must not run after later settlement");
    }
}
