package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorMiddlewarePipelineTest {

    private static ActorMiddlewarePipeline.Stage stage(
            String id, ActorMiddlewarePipeline.Phase phase, boolean allowSkip,
            ActorMiddlewarePipeline.Middleware handler) {
        return new ActorMiddlewarePipeline.Stage(
                new ActorMiddlewarePipeline.Key(id, "v1"), phase, handler, allowSkip);
    }

    @Test
    void concurrentNextAdmissionMustPublishChildBeforeParentSettlement() throws Exception {
        var continuation = new java.util.concurrent.atomic.AtomicReference<ActorMiddlewarePipeline.Next>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var parent = new CompletableFuture<ActorMiddlewarePipeline.Outcome>();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(),
                List.of(stage("around", ActorMiddlewarePipeline.Phase.ACTOR, false,
                        (req, next) -> {
                            continuation.set(next);
                            return parent;
                        })),
                Set.of(), request -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("terminal admission timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return CompletableFuture.completedFuture(
                            new ActorMiddlewarePipeline.Outcome(200, "child"));
                });
        var result = pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("x", Map.of()), () -> false)
                .toCompletableFuture();
        var nextResult = new java.util.concurrent.atomic.AtomicReference<
                java.util.concurrent.CompletionStage<ActorMiddlewarePipeline.Outcome>>();
        Thread worker = new Thread(() -> nextResult.set(
                continuation.get().proceed(new ActorMiddlewarePipeline.Envelope("x", Map.of()))));
        worker.start();
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            parent.complete(new ActorMiddlewarePipeline.Outcome(200, "parent"));
            assertFalse(result.isDone(),
                    "parent cannot commit before admitted child is published and settled");
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertEquals("parent", result.get(2, java.util.concurrent.TimeUnit.SECONDS).body());
        assertEquals("child", nextResult.get().toCompletableFuture()
                .get(2, java.util.concurrent.TimeUnit.SECONDS).body());
        assertThrows(CompletionException.class, () -> continuation.get()
                .proceed(new ActorMiddlewarePipeline.Envelope("late", Map.of()))
                .toCompletableFuture().join());
    }

    @Test
    void supervisorEnrichesBeforeActorAndActorCleansBeforeTerminal() {
        var calls = new java.util.ArrayList<String>();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(stage("Trace", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                        (req, next) -> {
                            calls.add("supervisor");
                            return next.proceed(req.withHeader("X-Trace", "scoped"));
                        })),
                List.of(stage("Clean", ActorMiddlewarePipeline.Phase.ACTOR, false,
                        (req, next) -> {
                            calls.add("actor");
                            assertEquals("scoped", req.headers().get("x-trace"));
                            return next.proceed(req.withMessage(req.message().strip()));
                        })),
                Set.of(), request -> {
                    calls.add("terminal");
                    assertEquals("hi", request.message());
                    return CompletableFuture.completedFuture(
                            new ActorMiddlewarePipeline.Outcome(200, request.message()));
                });

        var result = pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("  hi  ", Map.of()), () -> false)
                .toCompletableFuture().join();
        assertEquals("hi", result.body());
        assertEquals(List.of("supervisor", "actor", "terminal"), calls);
    }

    @Test
    void exactAllowlistedSupervisorProofMaySkipMatchingIdempotentActorStage() {
        var ranActor = new AtomicInteger();
        var key = new ActorMiddlewarePipeline.Key("Normalize", "v1");
        var pipeline = new ActorMiddlewarePipeline(
                List.of(stage("Normalize", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                        (req, next) -> next.proceed(req.withMessage(req.message().strip())))),
                List.of(stage("Normalize", ActorMiddlewarePipeline.Phase.ACTOR, true,
                        (req, next) -> {
                            ranActor.incrementAndGet();
                            return next.proceed(req);
                        })),
                Set.of(key),
                req -> CompletableFuture.completedFuture(
                        new ActorMiddlewarePipeline.Outcome(200, req.message())));
        var result = pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("  ok  ", Map.of()), () -> false)
                .toCompletableFuture().join();
        assertEquals("ok", result.body());
        assertEquals(0, ranActor.get());
    }

    @Test
    void ForgedHeaderAndDifferentVersionCannotSkipActorMiddleware() {
        var invoked = new AtomicInteger();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(stage("Normalize", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                        (req, next) -> next.proceed(req))),
                List.of(new ActorMiddlewarePipeline.Stage(
                        new ActorMiddlewarePipeline.Key("Normalize", "v2"),
                        ActorMiddlewarePipeline.Phase.ACTOR,
                        (req, next) -> { invoked.incrementAndGet(); return next.proceed(req); },
                        true)),
                Set.of(new ActorMiddlewarePipeline.Key("Normalize", "v1")),
                req -> CompletableFuture.completedFuture(
                        new ActorMiddlewarePipeline.Outcome(200, req.message())));
        // Arbitrary user metadata is never authoritative proof.
        pipeline.invoke(new ActorMiddlewarePipeline.Envelope(
                "hi", Map.of("X-Middleware-Processed", "true")), () -> false)
                .toCompletableFuture().join();
        assertEquals(1, invoked.get());
    }

    @Test
    void laterSupervisorMutationInvalidatesOtherwiseValidSkipProof() {
        var actorCalls = new AtomicInteger();
        var key = new ActorMiddlewarePipeline.Key("Normalize", "v1");
        var pipeline = new ActorMiddlewarePipeline(
                List.of(
                        stage("Normalize", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                                (req, next) -> next.proceed(req.withMessage(req.message().strip()))),
                        stage("Rewrite", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                                (req, next) -> next.proceed(req.withMessage("changed")))),
                List.of(stage("Normalize", ActorMiddlewarePipeline.Phase.ACTOR, true,
                        (req, next) -> { actorCalls.incrementAndGet(); return next.proceed(req); })),
                Set.of(key),
                req -> CompletableFuture.completedFuture(
                        new ActorMiddlewarePipeline.Outcome(200, req.message())));
        assertEquals("changed", pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("  ok  ", Map.of()), () -> false)
                .toCompletableFuture().join().body());
        assertEquals(1, actorCalls.get());
    }

    @Test
    void middlewareFailureAfterForwardingCannotSettleBeforeDownstream() {
        var pendingTerminal = new CompletableFuture<ActorMiddlewarePipeline.Outcome>();
        var pipeline = new ActorMiddlewarePipeline(List.of(),
                List.of(stage("ThrowAfterNext", ActorMiddlewarePipeline.Phase.ACTOR, false,
                        (req, next) -> {
                            next.proceed(req);
                            throw new IllegalStateException("boom");
                        })),
                Set.of(), req -> pendingTerminal);
        var result = pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("ok", Map.of()), () -> false)
                .toCompletableFuture();
        assertFalse(result.isDone(), "downstream must drain before early failure is visible");
        pendingTerminal.complete(new ActorMiddlewarePipeline.Outcome(200, "ok"));
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void lateNextAfterShortCircuitCannotEnterActor() {
        var captured = new java.util.concurrent.atomic.AtomicReference<ActorMiddlewarePipeline.Next>();
        var invoked = new AtomicInteger();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(stage("Block", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                        (req, next) -> {
                            captured.set(next);
                            return CompletableFuture.completedFuture(
                                    new ActorMiddlewarePipeline.Outcome(403, "stop"));
                        })),
                List.of(), Set.of(), req -> {
                    invoked.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ActorMiddlewarePipeline.Outcome(200, "unexpected"));
                });
        assertEquals(403, pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("ok", Map.of()), () -> false)
                .toCompletableFuture().join().status());
        assertThrows(CompletionException.class, () -> captured.get()
                .proceed(new ActorMiddlewarePipeline.Envelope("ok", Map.of()))
                .toCompletableFuture().join());
        assertEquals(0, invoked.get());
    }

    @Test
    void noAllowlistMeansNoSkipEvenIfBothStagesHaveSameId() {
        var actor = new AtomicInteger();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(stage("Normalize", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                        (req, next) -> next.proceed(req))),
                List.of(stage("Normalize", ActorMiddlewarePipeline.Phase.ACTOR, true,
                        (req, next) -> { actor.incrementAndGet(); return next.proceed(req); })),
                Set.of(),
                req -> CompletableFuture.completedFuture(
                        new ActorMiddlewarePipeline.Outcome(200, req.message())));
        pipeline.invoke(new ActorMiddlewarePipeline.Envelope("hi", Map.of()), () -> false)
                .toCompletableFuture().join();
        assertEquals(1, actor.get());
    }

    @Test
    void continuationCannotInvokeActorTwice() {
        var terminalCalls = new AtomicInteger();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(),
                List.of(stage("Duplicate", ActorMiddlewarePipeline.Phase.ACTOR, false,
                        (req, next) -> {
                            next.proceed(req).toCompletableFuture().join();
                            return next.proceed(req);
                        })),
                Set.of(),
                req -> {
                    terminalCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ActorMiddlewarePipeline.Outcome(200, req.message()));
                });
        assertThrows(CompletionException.class, () ->
                pipeline.invoke(new ActorMiddlewarePipeline.Envelope("hi", Map.of()), () -> false)
                        .toCompletableFuture().join());
        assertEquals(1, terminalCalls.get());
    }

    @Test
    void shortCircuitBeforeNextDoesNotExecuteTerminal() {
        var terminalCalls = new AtomicInteger();
        var pipeline = new ActorMiddlewarePipeline(
                List.of(stage("Reject", ActorMiddlewarePipeline.Phase.SUPERVISOR, false,
                        (req, next) -> CompletableFuture.completedFuture(
                                new ActorMiddlewarePipeline.Outcome(403, "forbidden")))),
                List.of(),
                Set.of(), req -> {
                    terminalCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ActorMiddlewarePipeline.Outcome(200, "unexpected"));
                });
        assertEquals(403, pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("hi", Map.of()), () -> false)
                .toCompletableFuture().join().status());
        assertEquals(0, terminalCalls.get());
    }

    @Test
    void cancellationAndHeaderManipulationFailClosed() {
        var cancelled = new AtomicBoolean(true);
        var pipeline = new ActorMiddlewarePipeline(List.of(), List.of(), Set.of(),
                req -> CompletableFuture.completedFuture(new ActorMiddlewarePipeline.Outcome(200, "ok")));
        assertThrows(CompletionException.class, () -> pipeline.invoke(
                new ActorMiddlewarePipeline.Envelope("hi", Map.of()), cancelled::get)
                .toCompletableFuture().join());
        assertThrows(SecurityException.class,
                () -> new ActorMiddlewarePipeline.Envelope("x", Map.of("Authorization", "attacker")));
        assertThrows(SecurityException.class,
                () -> new ActorMiddlewarePipeline.Envelope("x", Map.of()).withHeader("Content-Length", "999"));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorMiddlewarePipeline.Envelope("x", Map.of("X-Custom", "ok\r\ninjected: 1")));
    }

    @Test
    void unknownEquivalenceProofAndDuplicateStagesAreRejected() {
        var handler = stage("same", ActorMiddlewarePipeline.Phase.ACTOR, false,
                (req, next) -> next.proceed(req));
        var terminal = (ActorMiddlewarePipeline.Terminal)
                req -> CompletableFuture.completedFuture(new ActorMiddlewarePipeline.Outcome(200, "ok"));
        assertThrows(IllegalArgumentException.class, () -> new ActorMiddlewarePipeline(
                List.of(), List.of(handler), Set.of(new ActorMiddlewarePipeline.Key("same", "v1")), terminal));
        assertThrows(IllegalArgumentException.class, () -> new ActorMiddlewarePipeline(
                List.of(), List.of(handler, handler), Set.of(), terminal));
    }
}
