package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actor-wide logical lane occupancy survives multiple independent Futures. */
final class SerialActorMailboxBarrierTest {

    @Test void nextUserMessageWaitsForBothIndependentCompletionBarriers() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);
        AtomicInteger messages = new AtomicInteger();
        OresFuture<Void> method = new OresFuture<>(() -> false, () -> { });
        OresFuture<Void> response = new OresFuture<>(() -> false, () -> { });

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                messages.incrementAndGet();
                if (mail == 1) {
                    runtime.suspendCurrentUserMailboxUntil(method);
                    runtime.suspendCurrentUserMailboxUntil(response);
                    admitted.countDown();
                } else if (mail == 2) {
                    secondProcessed.countDown();
                    context.self().stop();
                }
            });
            actor.ready().join();
            actor.send(1);
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            actor.send(2);
            method.completeFromRuntime(null);
            assertFalse(secondProcessed.await(100, TimeUnit.MILLISECONDS),
                    "method completion must not release HTTP response barrier");
            assertEquals(1, messages.get());

            response.completeFromRuntime(null);
            assertTrue(secondProcessed.await(5, TimeUnit.SECONDS),
                    "later mail must resume once both barriers complete");
            assertEquals(2, messages.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
        }
    }

    @Test void cancelledActorCannotBeRevivedByLateBarrierCompletions() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        OresFuture<Void> method = new OresFuture<>(() -> false, () -> { });
        OresFuture<Void> response = new OresFuture<>(() -> false, () -> { });
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                executed.incrementAndGet();
                runtime.suspendCurrentUserMailboxUntil(method);
                runtime.suspendCurrentUserMailboxUntil(response);
                admitted.countDown();
            });
            actor.ready().join();
            actor.send(1);
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            actor.send(2);
            assertEquals(1, method.pendingRuntimeWaiterCount());
            assertEquals(1, response.pendingRuntimeWaiterCount());
            actor.cancel();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, method.pendingRuntimeWaiterCount(),
                    "cancelled actor must release its callback from a pending producer");
            assertEquals(0, response.pendingRuntimeWaiterCount(),
                    "cancelled actor must release all independent producer callbacks");
            method.completeFromRuntime(null);
            response.completeFromRuntime(null);
            assertEquals(1, executed.get(),
                    "late completions cannot restart queued user work after cancellation");
        }
    }
}
