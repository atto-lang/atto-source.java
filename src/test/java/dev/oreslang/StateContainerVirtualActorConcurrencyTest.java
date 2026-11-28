package dev.oreslang;

import dev.oreslang.runtime.StateContainerAccessGate;
import dev.oreslang.runtime.VirtualActorActivationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.oreslang.runtime.StateContainerAccessGate.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

final class StateContainerVirtualActorConcurrencyTest {
    @Test
    void concurrentReadWriteGateNeverOverlapsIncompatibleLeases() throws Exception {
        final var gate = new StateContainerAccessGate(4);
        final int workers = 8, rounds = 1_000;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(workers);
        final var failures = new AtomicReference<Throwable>();
        final var total = new AtomicInteger();
        for (int i = 0; i < workers; i++) {
            final boolean write = i % 3 == 0;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < rounds; j++) {
                        StateContainerAccessGate.Lease lease;
                        do {
                            lease = write ? gate.tryWrite() : gate.tryRead();
                            if (lease == null) Thread.onSpinWait();
                        } while (lease == null);
                        final var acquired = lease;
                        try (acquired) {
                            acquired.requireActive(write ? WRITE : READ);
                            if (write) {
                                if (gate.readerCount() != 0)
                                    throw new AssertionError("writer overlaps readers");
                            } else if (gate.writerActive()) {
                                throw new AssertionError("reader overlaps writer");
                            }
                            total.incrementAndGet();
                        }
                    }
                } catch (Throwable failure) {
                    failures.compareAndSet(null, failure);
                } finally {
                    done.countDown();
                }
            }, "state-access-stress-" + i);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS),
                "workers failed to terminate; state access must never park");
        assertNull(failures.get(), () -> "concurrent state admission failure: " + failures.get());
        assertEquals(workers * rounds, total.get());
        assertEquals(0, gate.readerCount());
        assertFalse(gate.writerActive());
        gate.close();
        assertTrue(gate.leasesQuiescentAfterClose());
    }

    @Test
    void concurrentLogicalActivationPublishesExactlyOneInitializer() throws Exception {
        final var registry = new VirtualActorActivationRegistry("app", 1, 32);
        final var logical = registry.resolve("Cart", "shared-key");
        final int workers = 16;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(workers);
        final var initializers = new AtomicInteger();
        final var failures = new AtomicReference<Throwable>();
        final var references = new VirtualActorActivationRegistry.Activation[workers];
        for (int i = 0; i < workers; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    start.await();
                    var activation = registry.activate(logical);
                    references[index] = activation;
                    if (activation.initializer()) initializers.incrementAndGet();
                } catch (Throwable failure) {
                    failures.compareAndSet(null, failure);
                } finally {
                    done.countDown();
                }
            }, "virtual-actor-activate-" + i).start();
        }
        start.countDown();
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(failures.get(), () -> "concurrent activation failure: " + failures.get());
        assertEquals(1, initializers.get(), "one logical identity must have one publisher");
        var first = references[0];
        assertNotNull(first);
        for (var ref : references) {
            assertNotNull(ref);
            assertEquals(first.incarnation(), ref.incarnation());
        }
        for (var ref : references) {
            if (ref.initializer()) registry.publish(ref);
        }
        assertTrue(first.active());
        registry.deactivate(first);
        var restarted = registry.activate(logical);
        assertTrue(restarted.incarnation() > first.incarnation());
        assertFalse(first.active(), "stale activation cannot authorize successor");
        registry.abort(restarted);
        registry.close();
    }
}
