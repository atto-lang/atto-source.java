package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Concrete actor-dispatch refinement: the ledger never becomes another guest executor. */
final class ActorContinuationLedgerIntegrationTest {
    @Test
    void readyContinuationPrecedesLaterMailAndStillOwnsActorCarrier() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        OresFuture<Integer> future = new OresFuture<>();

        var config = new ActorRuntime.DispatcherConfig(2, 2, 1, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("register".equals(message)) {
                    var target = runtime.captureCurrentContinuationTarget();
                    runtime.enqueueOnCompletion(future, target, (value, failure) -> {
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                        assertNull(failure);
                        assertEquals(42, value);
                        order.add("resume");
                    });
                    registered.countDown();
                    if (!releaseReceive.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("initial receive was not released");
                    }
                } else if ("later".equals(message)) {
                    order.add("later");
                    delivered.countDown();
                    context.self().stop();
                }
            });
            try {
                actor.send("register");
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                actor.send("later");
                assertTrue(future.completeFromRuntime(42));
            } finally {
                releaseReceive.countDown();
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of("resume", "later"), order,
                    "the continuation lane must not be overtaken by later user mail");
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void completionAfterActorStopCannotInvokeCapturedContinuation() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var target = runtime.captureCurrentContinuationTarget();
                runtime.enqueueOnCompletion(future, target, (value, failure) ->
                        callbacks.incrementAndGet());
                registered.countDown();
                context.self().stop();
            });
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            future.completeFromRuntime(1);
            assertEquals(0, callbacks.get(),
                    "retired actor may not be resurrected by a stale Future completion");
        }
    }
}
