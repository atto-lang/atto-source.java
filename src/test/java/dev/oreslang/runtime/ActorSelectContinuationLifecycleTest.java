package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActorSelectContinuationLifecycleTest {
    @Test
    @Timeout(15)
    void stopCancelsOwnedSelectAndLateProducerCannotReenterDeadActor()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var selectedRef = new AtomicReference<
                OresFuture<ChannelRuntime.SelectResult>>();
        var callbackRuns = new AtomicInteger();
        var armed = new CountDownLatch(1);

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var selected = ChannelRuntime.SelectSet.of(
                        ChannelRuntime.await(producer))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
                selectedRef.set(selected);

                var target = runtime.captureCurrentContinuationTarget();
                runtime.enqueueOnCompletion(
                        selected,
                        target,
                        (winner, failure) -> {
                            if (!runtime.currentThreadOwnsActorExecutionLease()) {
                                throw new AssertionError(
                                        "select continuation escaped actor execution lane");
                            }
                            callbackRuns.incrementAndGet();
                        });
                armed.countDown();
            });

            actor.send("arm");
            assertTrue(armed.await(5, TimeUnit.SECONDS),
                    "actor did not arm its nonblocking select");
            assertEquals(1, producer.pendingRuntimeWaiterCount());
            assertFalse(selectedRef.get().isDone());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "actor did not terminate after STOP");

            var selected = selectedRef.get();
            assertTrue(selected.isCancelled(),
                    "actor-owned pending select must be cancelled at STOP");
            assertThrows(CancellationException.class, selected::join);
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "STOP retained a losing Future select waiter");
            assertEquals(0, callbackRuns.get(),
                    "cancelled select continuation re-entered stopped actor");

            // Settlement belongs to the producer, not to the dead actor.
            assertTrue(producer.completeFromRuntime(77));
            assertEquals(77, producer.get(5, TimeUnit.SECONDS));
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertEquals(0, callbackRuns.get(),
                    "late producer settlement resurrected actor continuation");
        }
    }
}
