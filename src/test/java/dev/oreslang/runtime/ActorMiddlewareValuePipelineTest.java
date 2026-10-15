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
    void concurrentTypedNextCannotEscapeParentCompletionFence() throws Exception {
        var nextRef = new AtomicReference<ActorMiddlewareValuePipeline.Next<Integer>>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var parent = new CompletableFuture<ActorMiddlewareValuePipeline.Outcome<Integer>>();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>around(key("race"), A,
                        (request, draft, next) -> {
                            nextRef.set(next);
                            return parent;
                        }, false)),
                Set.of(),
                request -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS))
                            throw new IllegalStateException("terminal stalled");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return CompletableFuture.completedFuture(9);
                });
        var result = pipeline.invoke(req("before"), () -> false).toCompletableFuture();
        var child = new AtomicReference<CompletionStage<ActorMiddlewareValuePipeline.Outcome<Integer>>>();
        Thread worker = new Thread(() -> child.set(
                nextRef.get().proceed(req("after"),
                        ActorMiddlewareValuePipeline.ResponseDraft.defaults())));
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            parent.complete(new ActorMiddlewareValuePipeline.Reply<>(17));
            assertFalse(result.isDone(), "typed reply must wait for admitted child completion");
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertEquals(17, ((ActorMiddlewareValuePipeline.Reply<Integer>)
                result.get(2, TimeUnit.SECONDS)).value());
        assertEquals(9, ((ActorMiddlewareValuePipeline.Reply<Integer>)
                child.get().toCompletableFuture().get(2, TimeUnit.SECONDS)).value());
        assertThrows(CompletionException.class, () ->
                nextRef.get().proceed(req("late"),
                        ActorMiddlewareValuePipeline.ResponseDraft.defaults())
                        .toCompletableFuture().join());
    }

    @Test
    void requestContinuedWithResponseDraftPreservesStatusAndHeadersAtTerminal() {
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("status"), S,
                        (input, draft) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(
                                        input, draft.withStatus(201)
                                                .withHeader("Content-Type", "application/json"))),
                        false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>around(key("pass"), A,
                        (input, draft, next) -> next.proceed(input, draft), false)),
                Set.of(), input -> CompletableFuture.completedFuture(42));
        var result = (ActorMiddlewareValuePipeline.Reply<Integer>)
                pipeline.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(42, result.value());
        assertEquals(201, result.response().status());
        assertEquals("application/json", result.response().headers().get("content-type"));
    }

    @Test
    void nextValContinueEnrichesAndReturnsTypedInt() {
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("trim"), S,
                        (input, res) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input.withMessage(input.message().strip()), res)),
                        false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("enrich"), A,
                        (input, res) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input.withHeader("X-Actor", "accepted"), res)),
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
                        (input, res) -> CompletableFuture.completedFuture(
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
    void unaryResponseDraftCanSetSafeStatusAndContentTypeWithoutWriting() {
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("created"), A,
                        (input, res) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Reply<>(73,
                                        res.withStatus(201)
                                            .withHeader("Content-Type", "application/json"))),
                        false)),
                Set.of(), input -> { fail("value middleware already answered"); return null; });
        var reply = (ActorMiddlewareValuePipeline.Reply<Integer>)
                pipeline.invoke(req("create"), () -> false).toCompletableFuture().join();
        assertEquals(73, reply.value());
        assertEquals(201, reply.response().status());
        assertEquals("application/json", reply.response().headers().get("content-type"));
        assertThrows(IllegalArgumentException.class,
                () -> reply.response().withStatus(204));
        assertThrows(SecurityException.class,
                () -> reply.response().withHeader("Content-Length", "999"));
        assertThrows(IllegalArgumentException.class,
                () -> reply.response().withHeader("X-Custom", "ok\r\nInjected: yes"));
    }

    @Test
    void supervisorMayRejectButCannotManufactureAnActorReply() {
        var reject = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("auth"), S,
                        (input, res) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Reject<>(403, "forbidden")), false)),
                List.of(), Set.of(), input -> { fail("terminal must not run"); return null; });
        var denied = reject.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(403, ((ActorMiddlewareValuePipeline.Reject<Integer>) denied).status());

        var illegal = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key("auth"), S,
                        (input, res) -> CompletableFuture.completedFuture(
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
                        (request, res, next) -> {
                            next.proceed(request, res).toCompletableFuture().join();
                            return next.proceed(request, res);
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
                        (input, res, next) -> {
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
                continuation.get().proceed(req("late"), ActorMiddlewareValuePipeline.ResponseDraft.defaults()).toCompletableFuture().join());
        assertEquals(0, called.get());
    }

    @Test
    void proofMatchesExactVersionAndImmutableRequestRevision() {
        var count = new AtomicInteger();
        var shared = key("normalize");
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, S,
                        (input, res) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input, res)), false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, A,
                        (input, res) -> {
                            count.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewareValuePipeline.Continue<>(input, res));
                        }, true)),
                Set.of(shared), input -> CompletableFuture.completedFuture(42));
        pipeline.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(0, count.get());

        var changed = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, S,
                        (input, res) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(input, res)), false),
                        ActorMiddlewareValuePipeline.Stage.<Integer>value(key("rewrite"), S,
                                (input, res) -> CompletableFuture.completedFuture(
                                        new ActorMiddlewareValuePipeline.Continue<>(
                                                input.withMessage(input.message()), res)), false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(shared, A,
                        (input, res) -> {
                            count.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewareValuePipeline.Continue<>(input, res));
                        }, true)),
                Set.of(shared), input -> CompletableFuture.completedFuture(42));
        changed.invoke(req("x"), () -> false).toCompletableFuture().join();
        assertEquals(1, count.get(), "identity revision should invalidate even equal-looking context");
    }

    @Test
    void modifiedResponseDraftInvalidatesSkipDespiteUnchangedRequest() {
        var key = key("safe");
        var actorCalls = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(
                        ActorMiddlewareValuePipeline.Stage.<Integer>value(key, S,
                                (input, res) -> CompletableFuture.completedFuture(
                                        new ActorMiddlewareValuePipeline.Continue<>(
                                                input, res.withStatus(201))), false),
                        ActorMiddlewareValuePipeline.Stage.<Integer>value(key("reset"), S,
                                (input, res) -> CompletableFuture.completedFuture(
                                        new ActorMiddlewareValuePipeline.Continue<>(
                                                input, res.withStatus(200))), false)),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(key, A,
                        (input, res) -> {
                            actorCalls.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewareValuePipeline.Continue<>(input, res));
                        }, true)),
                Set.of(key), input -> CompletableFuture.completedFuture(17));
        pipeline.invoke(req("stable"), () -> false).toCompletableFuture().join();
        assertEquals(1, actorCalls.get());
    }

    @Test
    void pendingDownstreamMustSettleBeforeParentErrorIsPublished() {
        var pending = new CompletableFuture<Integer>();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(),
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>around(key("error"), A,
                        (request, res, next) -> {
                            next.proceed(request, res);
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
