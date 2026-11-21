package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic counter-fault injection against the production reservation
 * close path. This is a regression guard, not permission for guest reflection:
 * the otherwise-private constructors/counters are accessed by host tests only.
 */
@Timeout(15)
final class ActorMemoryReservationFailureAtomicityTest {
    private static ActorRuntime.ActorMemorySlice isolatedSlice(ActorRuntime runtime)
            throws ReflectiveOperationException {
        Constructor<ActorRuntime.ActorMemorySlice> ctor =
                ActorRuntime.ActorMemorySlice.class.getDeclaredConstructor(
                        ActorRuntime.class, ActorRuntime.ActorId.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(runtime, ActorRuntime.ActorId.create(), 64L);
    }

    private static ActorRuntime.MemoryReservation isolatedReservation(
            ActorRuntime runtime,
            ActorRuntime.ActorMemorySlice slice,
            long bytes) throws ReflectiveOperationException {
        Constructor<ActorRuntime.MemoryReservation> ctor =
                ActorRuntime.MemoryReservation.class.getDeclaredConstructor(
                        ActorRuntime.class, ActorRuntime.ActorMemorySlice.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(runtime, slice, bytes);
    }

    private static AtomicLong counter(Object owner, String fieldName)
            throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (AtomicLong) field.get(owner);
    }

    /**
     * The private slice's accounting reverses its underflow before throwing.
     * Consequently, the reservation's close capability must remain live.
     */
    @Test
    void privateSliceUnderflowDoesNotConsumeReservation() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var slice = isolatedSlice(runtime);
            var reservation = isolatedReservation(runtime, slice, 16);
            AtomicLong local = counter(slice, "usedBytes");
            AtomicLong global = counter(runtime, "privateMemoryBytes");
            try {
                local.set(0);
                global.set(0);
                assertThrows(IllegalStateException.class, reservation::close);
                assertEquals(0, local.get(), "failed release must undo the debit");

                local.set(16);
                global.set(16);
                reservation.close(); // retry required after repaired accounting
                reservation.close(); // idempotent if retry succeeded
                assertEquals(0, local.get());
                assertEquals(0, global.get());
            } finally {
                // Keep even a red regression test from corrupting runtime close.
                global.set(local.get());
                slice.close();
            }
        }
    }

    /**
     * Aggregate runtime-budget failures must also preserve close/retry
     * authority after ActorMemorySlice.release restores its local counter.
     */
    @Test
    void aggregateBudgetUnderflowDoesNotConsumeReservation() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var slice = isolatedSlice(runtime);
            var reservation = isolatedReservation(runtime, slice, 16);
            AtomicLong local = counter(slice, "usedBytes");
            AtomicLong global = counter(runtime, "privateMemoryBytes");
            try {
                local.set(16);
                global.set(0);
                assertThrows(IllegalStateException.class, reservation::close);
                assertEquals(16, local.get(), "failed aggregate release must restore slice");
                assertEquals(0, global.get());

                global.set(16);
                reservation.close();
                reservation.close();
                assertEquals(0, local.get());
                assertEquals(0, global.get());
            } finally {
                global.set(local.get());
                slice.close();
            }
        }
    }

    @Test
    void sliceCloseUnderflowRetainsAggregateRefundAuthorityForRetry() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var slice = isolatedSlice(runtime);
            AtomicLong local = counter(slice, "usedBytes");
            AtomicLong global = counter(runtime, "privateMemoryBytes");
            try {
                local.set(16);
                global.set(0);

                assertThrows(IllegalStateException.class, slice::close);
                assertTrue(slice.closed(),
                        "guest access stays revoked after a failed aggregate refund");
                assertEquals(16, local.get(),
                        "failed close must retain the exact outstanding debit");
                assertEquals(0, global.get());

                global.set(16);
                slice.close();
                slice.close();
                assertEquals(0, local.get(),
                        "successful retry consumes the retained debit exactly once");
                assertEquals(0, global.get());
            } finally {
                // Repair the synthetic aggregate counter before runtime close.
                global.set(local.get());
                slice.close();
            }
        }
    }

    @Test
    void duplicateCloseRefundsReservationOnce() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var slice = isolatedSlice(runtime);
            var reservation = isolatedReservation(runtime, slice, 16);
            AtomicLong local = counter(slice, "usedBytes");
            AtomicLong global = counter(runtime, "privateMemoryBytes");
            local.set(16);
            global.set(16);
            var start = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread[] workers = new Thread[8];
            try {
                for (int i = 0; i < workers.length; i++) {
                    workers[i] = new Thread(() -> {
                        try {
                            start.await();
                            reservation.close();
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        }
                    }, "memory-reservation-close-" + i);
                    workers[i].start();
                }
                start.countDown();
                for (Thread worker : workers) {
                    worker.join(5000);
                    assertFalse(worker.isAlive(), "reservation close worker stalled");
                }
                assertNull(failure.get(), "competing closes must never double refund");
                assertEquals(0, local.get());
                assertEquals(0, global.get());
            } finally {
                start.countDown();
                for (Thread worker : workers) {
                    if (worker != null) worker.join(5000);
                }
                global.set(local.get());
                slice.close();
            }
        }
    }
}
