package dev.oreslang.runtime;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exact production listener-slot identity model; not guest runtime evidence. */
@Timeout(15)
final class HttpPooledWorkerListenerSlotsTest {
    private static final class EqualListener {
        @Override public boolean equals(Object other) { return other instanceof EqualListener; }
        @Override public int hashCode() { return 1; }
    }

    @Test
    void equalButDistinctListenersNeverShareTheirWorkerGenerations() {
        var slots = new HttpPooledWorkerListenerSlots<EqualListener, Object>(2);
        var a = new EqualListener();
        var b = new EqualListener();
        assertNotSame(a, b);
        assertEquals(a, b, "test owners deliberately collide under equals()");
        Object a0 = new Object();
        Object b0 = new Object();
        Object a1 = new Object();
        Object b1 = new Object();

        var sA0 = slots.next(a);
        var sB0 = slots.next(b);
        assertNull(sA0.previous());
        assertNull(sB0.previous());
        slots.replace(a, sA0, a0);
        slots.replace(b, sB0, b0);
        var sA1 = slots.next(a);
        var sB1 = slots.next(b);
        slots.replace(a, sA1, a1);
        slots.replace(b, sB1, b1);

        assertSame(a0, slots.next(a).previous());
        assertSame(b0, slots.next(b).previous());
        assertSame(a1, slots.next(a).previous());
        assertSame(b1, slots.next(b).previous());
        assertEquals(2, slots.listenerCount());

        slots.forget(a);
        assertEquals(1, slots.listenerCount());
        assertNull(slots.next(a).previous(), "retired A cannot reuse an old generation");
        assertSame(b0, slots.next(b).previous(),
                "retiring listener A cannot replace B's slot");
    }

    @Test
    void staleReplacementAndInvalidPoolSizesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpPooledWorkerListenerSlots<>(0));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpPooledWorkerListenerSlots<>(4097));
        var slots = new HttpPooledWorkerListenerSlots<Object, Object>(1);
        var owner = new Object();
        var initial = slots.next(owner);
        Object worker = new Object();
        slots.replace(owner, initial, worker);
        assertThrows(IllegalStateException.class,
                () -> slots.replace(owner, initial, new Object()),
                "old selection cannot overwrite an already-installed incarnation");
        var current = slots.next(owner);
        assertSame(worker, current.previous());
        slots.forget(owner);
        assertThrows(IllegalStateException.class,
                () -> slots.replace(owner, current, new Object()));
        assertThrows(NullPointerException.class, () -> slots.next(null));
    }

    @RepeatedTest(32)
    void concurrentListenersNeverExchangeWorkersDuringSlotRotation() throws Exception {
        final int listenerCount = 32;
        final int iterations = 128;
        var slots = new HttpPooledWorkerListenerSlots<Object, OwnerValue>(3);
        var ready = new CountDownLatch(listenerCount);
        var go = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < listenerCount; i++) {
                final Object owner = new Object();
                tasks.add(threads.submit(() -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("listener start deadline");
                    }
                    for (int n = 0; n < iterations; n++) {
                        // Handler.worker() serializes next and replace as a
                        // single logical operation; mirror that ownership.
                        synchronized (slots) {
                            var selected = slots.next(owner);
                            if (selected.previous() != null) {
                                assertSame(owner, selected.previous().owner);
                            }
                            slots.replace(owner, selected, new OwnerValue(owner));
                        }
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(listenerCount, slots.listenerCount());
        }
    }

    private record OwnerValue(Object owner) {}
}
