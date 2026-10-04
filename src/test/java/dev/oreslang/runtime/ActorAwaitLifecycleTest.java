package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorAwaitLifecycleTest {

    private static ActorRuntime.DispatcherConfig singleCarrierConfig() {
        return new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                8,
                Long.MAX_VALUE,
                1024);
    }

    @Test
    void stoppingSuspendedActorDetachesAwaitedFuture() throws Exception {
        OresFuture<String> source = new OresFuture<>();
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicInteger continuationRuns = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.<String>spawnPrivate(() -> (message, context) -> {
                        suspended.countDown();
                        context.suspendOn(source, (value, failure, resumeContext) -> {
                            continuationRuns.incrementAndGet();
                            resumed.countDown();
                        });
                        fail("suspendOn must end the actor carrier turn");
                    });

            actor.send("wait");
            assertTrue(suspended.await(2, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.queuedRuntimeWaiters() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, source.queuedRuntimeWaiters());

            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, source.queuedRuntimeWaiters(),
                    "actor finalization must detach the suspended Future callback");

            assertTrue(source.completeFromRuntime("late"));
            assertFalse(resumed.await(100, TimeUnit.MILLISECONDS));
            assertEquals(0, continuationRuns.get(),
                    "late Future completion must not revive a stopped actor");
        }
    }

    @Test
    void runtimeCloseDetachesSuspendedActorAwait() throws Exception {
        OresFuture<String> source = new OresFuture<>();
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicInteger continuationRuns = new AtomicInteger();

        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig());
        ActorRuntime.ActorRef<String> actor =
                runtime.<String>spawnShared(() -> (message, context) -> {
                    suspended.countDown();
                    context.suspendOn(source, (value, failure, resumeContext) ->
                            continuationRuns.incrementAndGet());
                });

        actor.send("wait");
        assertTrue(suspended.await(2, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (source.queuedRuntimeWaiters() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, source.queuedRuntimeWaiters());

        runtime.close();

        assertEquals(0, source.queuedRuntimeWaiters(),
                "runtime close must release suspended actor Future registrations");
        assertTrue(source.completeFromRuntime("late"));
        assertEquals(0, continuationRuns.get());
        assertTrue(actor.awaitTermination(1, TimeUnit.SECONDS));
    }
}
