package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Replaying a previously accepted executor callback must never re-enter its
 * actor, free a successor's physical permit, or jump the bounded FIFO queue.
 * These test the real Java host gate, not a source-free reference model.
 */
@Timeout(20)
final class ActorPhysicalTurnGateReplaySuccessorTest {
    @Test
    void completedCallbackReplayCannotStealNewPermitOrOvertakeFifo() {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var first = new AtomicReference<Runnable>();
        var second = new AtomicReference<Runnable>();
        var third = new AtomicReference<Runnable>();
        var deliveries = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();

        try {
            gate.dispatch(deliveries::incrementAndGet, first::set, failure::set);
            assertNotNull(first.get());
            first.get().run();
            assertEquals(1, deliveries.get());

            gate.dispatch(deliveries::incrementAndGet, second::set, failure::set);
            gate.dispatch(deliveries::incrementAndGet, third::set, failure::set);
            assertNotNull(second.get(), "the second turn owns the sole permit");
            assertNull(third.get(), "the third turn must remain queued");
            assertEquals(1, gate.queued());

            first.get().run();
            first.get().run();
            assertEquals(1, deliveries.get(), "an old callback is not a guest turn");
            assertEquals(1, gate.queued(), "replay may not free a newer permit");
            assertNull(third.get(), "replay may not bypass the FIFO successor");
            assertNull(failure.get());

            second.get().run();
            assertEquals(2, deliveries.get());
            assertNotNull(third.get(), "second completion must transfer the permit");
            assertEquals(0, gate.queued());
            third.get().run();
            assertEquals(3, deliveries.get());
            assertEquals(1, gate.peak());
            assertNull(failure.get());
        } finally {
            gate.close();
        }
    }

    @Test
    void concurrentReplayWhileOldCallbackRunsDoesNotAdmitQueuedSuccessor() throws Exception {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var first = new AtomicReference<Runnable>();
        var second = new AtomicReference<Runnable>();
        var failure = new AtomicReference<Throwable>();
        var deliveries = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                gate.dispatch(() -> {
                    deliveries.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("physical turn never released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }, first::set, failure::set);
                assertNotNull(first.get());
                var running = worker.submit(() -> first.get().run());
                assertTrue(entered.await(5, TimeUnit.SECONDS));

                gate.dispatch(deliveries::incrementAndGet, second::set, failure::set);
                assertEquals(1, gate.queued());
                assertNull(second.get(), "no successor while first physical turn runs");

                // A buggy executor may invoke the *same* accepted Runnable
                // from a different thread after its first invocation began.
                first.get().run();
                assertEquals(1, deliveries.get());
                assertEquals(1, gate.queued());
                assertNull(second.get(), "duplicate invocation must not transfer the permit");

                release.countDown();
                running.get(5, TimeUnit.SECONDS);
                assertNotNull(second.get());
                first.get().run(); // replay after physical completion remains inert
                assertEquals(1, deliveries.get());
                second.get().run();
                assertEquals(2, deliveries.get());
                assertEquals(0, gate.queued());
                assertEquals(1, gate.peak());
                assertNull(failure.get());
            } finally {
                release.countDown();
                gate.close();
            }
        }
    }
}
