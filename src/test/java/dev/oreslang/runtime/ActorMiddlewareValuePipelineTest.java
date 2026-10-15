package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class ActorMiddlewareValuePipelineTest {
    private static ActorMiddlewarePipeline.Key key(String id) {
        return new ActorMiddlewarePipeline.Key(id, "v1");
    }
    private static ActorMiddlewarePipeline.Envelope req(String message) {
        return new ActorMiddlewarePipeline.Envelope(message, Map.of());
    }
    private static final ActorMiddlewarePipeline.Phase S = ActorMiddlewarePipeline.Phase.SUPERVISOR;
    private static final ActorMiddlewarePipeline.Phase A = ActorMiddlewarePipeline.Phase.ACTOR;

    @Test
    void nextValContinueEnrichesAndReturnsTypedInt() {
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("trim"), S,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input.withMessage(input.message().strip()))),
                        false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("enrich"), A,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input.withHeader("X-Actor", "accepted"))),
                        false)),
                Set.of(),
                input -> {
                    assertEquals("7", input.message());
                    assertEquals("accepted", input.headers().get("x-actor"));
                    return CompletableFuture.completedFuture(Integer.parseInt(input.message()) * 5);
                });
        var outcome = pipeline.invoke(req("  7  "), () -> false).toCompletableFuture().join();
        assertEquals(35, ((ActorMiddlewareValuePipeline.Reply<Integer>) outcome).value());
    }

    @Test
    void nextValCanReturnTypedValueWithoutInvokingActor() {
        var invoked = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("short"), A,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Reply<>(99)),
                        false)),
                Set.of(), input -> {
                    invoked.incrementAndGet();
                    return CompletableFuture.completedFuture(1);
                });
        assertEquals(99, ((ActorMiddlewareValuePipeline.Reply<Integer>)
                pipeline.invoke(req("x"), () -> false).toCompletableFuture().join()).value());
        assertEquals(0, invoked.get());
    }

    @Test
    void supervisorMayRejectButCannotManufactureAnActorReply() {
        var reject = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("auth"), S,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Reject<>(403, "forbidden")), false)),
                List.of(), Set.of(), input -> { fail("terminal must not run"); return null; });
        var denied = reject.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(403, ((ActorMiddlewareValuePipeline.Reject<Integer>) denied).status());

        var illegal = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("auth"), S,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Reply<>(8)), false)),
                List.of(), Set.of(), input -> CompletableFuture.completedFuture(1));
        assertThrows(CompletionException.class,
                () -> illegal.invoke(req("x"), () -> false).toCompletableFuture().join());
    }

    @Test
    void aroundHookAllowsExactlyOneContinuation() {
        var terminal = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>around(key("twice"), A,
                        (request, next) -> {
                            next.proceed(request).toCompletableFuture().join();
                            return next.proceed(request);
                        }, false)),
                Set.of(), input -> CompletableFuture.completedFuture(terminal.incrementAndGet()));
        assertThrows(CompletionException.class,
                () -> pipeline.invoke(req("x"), () -> false).toCompletableFuture().join());
        assertEquals(1, terminal.get());
    }

    @Test
    void delayedNextCannotDispatchAfterShortCircuit() {
        var continuation = new AtomicReference<ActorMiddlewareValuePipeline.Next<Integer>>();
        var called = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>around(key("early"), A,
                        (input, next) -> {
                            continuation.set(next);
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewareValuePipeline.Reply<>(11));
                        }, false)),
                Set.of(), input -> {
                    called.incrementAndGet();
                    return CompletableFuture.completedFuture(1);
                });
        assertEquals(11, ((ActorMiddlewareValuePipeline.Reply<Integer>)
                pipeline.invoke(req("x"), () -> false).toCompletableFuture().join()).value());
        assertThrows(CompletionException.class, () ->
                continuation.get().proceed(req("late")).toCompletableFuture().join());
        assertEquals(0, called.get());
    }

    @Test
    void proofMatchesExactVersionAndImmutableRequestRevision() {
        var count = new AtomicInteger();
        var shared = key("normalize");
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, S,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input)), false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, A,
                        input -> {
                            count.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewareValuePipeline.Continue<>(input));
                        }, true)),
                Set.of(shared), input -> CompletableFuture.completedFuture(42));
        pipeline.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(0, count.get());

        var changed = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, S,
                        input -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input))),
                        ActorMiddlewareValuePipeline.Stage.<Integer>value(key("rewrite"), S,
                                input -> CompletableFuture.completedFuture(
                                        new ActorMiddlewareValuePipeline.Continue<>(
                                                input.withMessage(input.message()))))),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, A,
                        input -> {
                            count.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewareValuePipeline.Continue<>(input));
                        }, true)),
                Set.of(shared), input -> CompletableFuture.completedFuture(42));
        changed.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(1, count.get(), "identity revision should invalidate even equal-looking context");
    }

    @Test
    void pendingDownstreamMustSettleBeforeParentErrorIsPublished() {
        var pending = new CompletableFuture<Integer>();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>around(key("error"), A,
                        (request, next) -> {
                            next.proceed(request);
                            throw new IllegalStateException("post-next failure");
                        }, false)),
                Set.of(), input -> pending);
        var result = pipeline.invoke(req("x"), () -> false).toCompletableFuture();
        assertFalse(result.isDone());
        pending.complete(12);
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void cancelledBeforeDispatchNeverEntersActor() {
        var called = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(), input -> {
                    called.incrementAndGet();
                    return CompletableFuture.completedFuture(5);
                });
        assertThrows(CompletionException.class,
                () -> pipeline.invoke(req("x"), () -> true).toCompletableFuture().join());
        assertEquals(0, called.get());
    }
}
