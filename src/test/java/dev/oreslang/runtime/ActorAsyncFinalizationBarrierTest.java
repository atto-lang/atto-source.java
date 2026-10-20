package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorAsyncFinalizationBarrierTest {

    @Test
    void successfulAsyncInvocationIsNotPublishedUntilTurnExecutorReturns() throws Exception {
        CountDownLatch guestTurnFinished = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            guestTurnFinished.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            OresFuture<String> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (message, context) -> "done:" + message);

            assertTrue(guestTurnFinished.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone(),
                    "guest result must stay staged while TurnExecutor still owns the carrier/context");

            releaseCarrier.countDown();

            assertEquals("done:request", result.get(2, TimeUnit.SECONDS));
            assertTrue(result.isDone());
            assertEquals(0, runtime.actorCount(),
                    "one-shot actor must be finalized before its result becomes observable");
        }
    }

    @Test
    void privateSynchronousResultIsDetachedBeforeSupervisorPublication()
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<OresBytes> actorOwned =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<OresBytes> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.PRIVATE,
                    "request",
                    (message, context) -> {
                        OresBytes bytes = OresBytes.copyOf(
                                new byte[] {1, 2, (byte) 255});
                        actorOwned.set(bytes);
                        return bytes;
                    });

            OresBytes exported = result.get(5, TimeUnit.SECONDS);
            assertNotNull(actorOwned.get());
            assertNotSame(actorOwned.get(), exported,
                    "PRIVATE one-shot result must not expose actor-owned Bytes");
            assertEquals(actorOwned.get(), exported);
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void privateFutureBackedResultDetachesProducerAndActorDomains()
            throws Exception {
        OresFuture<Object> producer = new OresFuture<>();
        OresBytes producerBytes = OresBytes.copyOf(
                new byte[] {9, 8, 7, (byte) 254});
        CountDownLatch invoked = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            OresFuture<OresBytes> result =
                    (OresFuture<OresBytes>) (OresFuture) runtime.invokeAsync(
                            ActorRuntime.ActorKind.PRIVATE,
                            "request",
                            (ActorRuntime.Invocation<String, Object>)
                                    (message, context) -> {
                                        invoked.countDown();
                                        return producer;
                                    });

            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            producer.completeFromRuntime(producerBytes);

            OresBytes exported = result.get(5, TimeUnit.SECONDS);
            assertNotSame(producerBytes, exported,
                    "PRIVATE future result must not publish producer-owned Bytes");
            assertEquals(producerBytes, exported);
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void sharedBytesResultRemainsImmutableZeroCopy() throws Exception {
        OresBytes shared = OresBytes.copyOf(new byte[] {4, 5, 6});
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<OresBytes> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (message, context) -> shared);
            assertSame(shared, result.get(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void failedAsyncInvocationIsNotPublishedUntilTurnExecutorReturns() throws Exception {
        CountDownLatch guestTurnFinished = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            guestTurnFinished.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            OresFuture<String> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (message, context) -> {
                        throw new IllegalStateException("boom");
                    });

            assertTrue(guestTurnFinished.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone(),
                    "guest failure must stay staged until the actor leaves TurnExecutor");

            releaseCarrier.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("boom", failure.getCause().getMessage());
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void nestedFutureResultUsesTheSameFinalizationBarrier() throws Exception {
        CountDownLatch guestTurnFinished = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            guestTurnFinished.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            OresFuture<String> result = (OresFuture<String>) (OresFuture) runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (ActorRuntime.Invocation<String, Object>)
                            (message, context) -> OresFuture.completed("future:" + message));

            assertTrue(guestTurnFinished.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());

            releaseCarrier.countDown();

            assertEquals("future:request", result.get(2, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
