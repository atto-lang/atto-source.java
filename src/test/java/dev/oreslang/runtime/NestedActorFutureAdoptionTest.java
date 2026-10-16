package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the real ActorRuntime one-shot invocation and physical
 * finalization barrier. Does not substitute a mocked scheduler or actor.
 */
final class NestedActorFutureAdoptionTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OresFuture<String> invoke(
            ActorRuntime runtime, CountDownLatch entered, OresFuture<Object> outer) {
        return (OresFuture<String>) (OresFuture) runtime.invokeAsync(
                ActorRuntime.ActorKind.SHARED,
                "request",
                (ActorRuntime.Invocation<String, Object>) (message, context) -> {
                    entered.countDown();
                    return outer;
                });
    }

    @Test
    void actorRemainsOutstandingUntilThreeDeepInnerSuccess() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            OresFuture<Object> outer = new OresFuture<>();
            OresFuture<Object> middle = new OresFuture<>();
            OresFuture<String> inner = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<String> answer = invoke(runtime, entered, outer);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            outer.completeFromRuntime(middle);
            assertFalse(answer.isDone(), "outer completion must not finalize actor");
            middle.completeFromRuntime(inner);
            assertFalse(answer.isDone(), "middle completion must not finalize actor");
            inner.completeFromRuntime("final");
            assertEquals("final", answer.get(3, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount(),
                    "completion publication waits for physical actor teardown");
        }
    }

    @Test
    void nestedInnerFailureKeepsFailureCategoryAndFinalizesOnce() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            OresFuture<Object> outer = new OresFuture<>();
            OresFuture<String> inner = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<String> answer = invoke(runtime, entered, outer);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            outer.completeFromRuntime(inner);
            assertFalse(answer.isDone());
            inner.failFromRuntime(new IllegalArgumentException("inner failure"));
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> answer.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void nestedInnerCancellationIsNotReportedAsSuccessOrOrdinaryFailure()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            OresFuture<Object> outer = new OresFuture<>();
            OresFuture<String> inner = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<String> answer = invoke(runtime, entered, outer);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            outer.completeFromRuntime(inner);
            assertFalse(answer.isDone());
            assertTrue(inner.cancel(false));
            assertThrows(CancellationException.class, () -> answer.get(3, TimeUnit.SECONDS));
            assertTrue(answer.isCancelled());
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void twoNodeFutureCycleCannotHoldActorLifetimeIndefinitely() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            OresFuture<Object> outer = new OresFuture<>();
            OresFuture<Object> inner = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<String> answer = invoke(runtime, entered, outer);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            outer.completeFromRuntime(inner);
            inner.completeFromRuntime(outer);
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> answer.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("cyclic"));
            assertEquals(0, runtime.actorCount());
        }
    }
}
