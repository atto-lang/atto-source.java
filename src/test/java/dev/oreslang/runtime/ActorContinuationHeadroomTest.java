package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for #401. The control lane has exactly 1,024 independently
 * reserved slots, even when the actor's configured user mailbox is empty.
 */
@Timeout(20)
final class ActorContinuationHeadroomTest {

    @Test
    void selfReplenishingContinuationStreamCannotStarveWaitingUserMail()
            throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        var config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 64);
        var seedCanReturn = new CountDownLatch(1);
        var firstContinuationQueued = new CountDownLatch(1);
        var userHandled = new CountDownLatch(1);
        var continuationsRan = new AtomicInteger();
        var continuationsSeenByUser = new AtomicInteger(-1);

        try (ActorRuntime runtime = new ActorRuntime(
                base,
                config,
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable())) {
            var actor = runtime.<String>spawnShared(() -> {
                AtomicReference<ActorRuntime.ContinuationTarget> target =
                        new AtomicReference<>();
                AtomicReference<java.util.function.BiConsumer<Integer, Throwable>> chain =
                        new AtomicReference<>();
                chain.set((value, failure) -> {
                    int ran = continuationsRan.incrementAndGet();
                    if (ran < 200) {
                        runtime.enqueueOnCompletion(
                                OresFuture.completed(ran),
                                target.get(),
                                chain.get());
                    }
                });

                return (message, context) -> {
                    if ("seed".equals(message)) {
                        target.set(runtime.captureCurrentContinuationTarget());
                        runtime.enqueueOnCompletion(
                                OresFuture.completed(0),
                                target.get(),
                                chain.get());
                        firstContinuationQueued.countDown();
                        if (!seedCanReturn.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("seed gate timed out");
                        return;
                    }
                    if ("user".equals(message)) {
                        continuationsSeenByUser.set(continuationsRan.get());
                        userHandled.countDown();
                        context.self().stop();
                    }
                };
            });

            actor.send("seed");
            assertTrue(firstContinuationQueued.await(3, TimeUnit.SECONDS));
            actor.send("user");
            seedCanReturn.countDown();

            assertTrue(userHandled.await(5, TimeUnit.SECONDS),
                    "bounded continuation priority must eventually service waiting user mail");
            assertEquals(32, continuationsSeenByUser.get(),
                    "user mail must run immediately after the fixed continuation burst");
            assertTrue(continuationsRan.get() < 200,
                    "user mail must preempt an otherwise self-replenishing continuation chain");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        } finally {
            seedCanReturn.countDown();
        }
    }

    @Test
    void oneThousandTwentyFifthSourceContinuationIsRejectedWithIdleUserCapacity()
            throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy oneUserMessage = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                1,
                Duration.ofSeconds(10),
                false);

        AtomicInteger initiallyAccepted = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> overflowFuture = new AtomicReference<>();
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        CountDownLatch attempted = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(base)) {
            var actor = runtime.<String>spawnShared(oneUserMessage, () -> (message, context) -> {
                try {
                    for (int i = 0; i < 1_024; i++) {
                        OresFuture<Integer> task = context.runtime().startActorTask(
                                resume -> OresScheduler.done(1));
                        if (!task.isDone()) initiallyAccepted.incrementAndGet();
                    }
                    overflowFuture.set(context.runtime().startActorTask(
                            resume -> OresScheduler.done(1)));
                } catch (Throwable failure) {
                    producerFailure.set(failure);
                } finally {
                    attempted.countDown();
                }
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            Object cell = actorCell(runtime, actor.id());
            assertNotNull(cell);
            actor.send("fill");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertNull(producerFailure.get(), () -> String.valueOf(producerFailure.get()));
            assertEquals(1_024, initiallyAccepted.get(),
                    "all reserved continuation slots must be available");
            OresFuture<Integer> overflow = overflowFuture.get();
            assertNotNull(overflow);
            assertTrue(overflow.isDone(),
                    "the 1,025th continuation must be rejected immediately");
            ExecutionException rejection = assertThrows(
                    ExecutionException.class, () -> overflow.get(0, TimeUnit.MILLISECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                    rejection.getCause());

            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent(),
                    "control-plane capacity exhaustion must fail-stop the actor");
            assertEquals(1L, runtime.runtimeStats().continuationAdmissionFailures(),
                    "control-plane overload must be observable without reading actor internals");
            assertEquals(0, accounting(cell, "queuedContinuations"),
                    "teardown must drain all continuation slots exactly once");
            assertEquals(0, accounting(cell, "queuedUserMessages"));
            assertEquals(0, accounting(cell, "queuedMessages"));
        }
    }

    private static Object actorCell(ActorRuntime runtime, ActorRuntime.ActorId id)
            throws ReflectiveOperationException {
        Field actors = ActorRuntime.class.getDeclaredField("actors");
        actors.setAccessible(true);
        return ((Map<?, ?>) actors.get(runtime)).get(id);
    }

    private static int accounting(Object cell, String field) throws ReflectiveOperationException {
        Field f = cell.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(cell);
    }
}
