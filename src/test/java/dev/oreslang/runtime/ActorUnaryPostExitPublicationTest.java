package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorUnaryPostExitPublicationTest {
    private static ActorMiddlewarePipeline.Envelope request() {
        return new ActorMiddlewarePipeline.Envelope("request", Map.of());
    }

    @Test
    void typedReplyIsImmutableAndExternallyPublishedOnlyAfterContextExit() throws Exception {
        var contextEntered = new AtomicBoolean();
        var observed = new CountDownLatch(1);
        var failed = new AtomicReference<Throwable>();
        var guestMutable = new ArrayList<>(List.of(2, 4, 6));
        var middleware = new ActorMiddlewareValuePipeline<List<Integer>>(
                List.of(), List.of(), Set.of(),
                req -> CompletableFuture.completedFuture(guestMutable));
        var bridge = new ActorUnaryReplyBridge<>(middleware);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(), turn -> {
                    contextEntered.set(true);
                    try { turn.run(); }
                    finally { contextEntered.set(false); }
                })) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                var ticket = new ActorUnaryReplySlot<
                        ActorMiddlewareValuePipeline.Reply<List<Integer>>>(
                        context.self().id(), context.self().generation(), UUID.randomUUID());
                bridge.dispatchActorOwnedPostExit(context, ticket, request(), () -> false)
                        .whenComplete((reply, error) -> {
                            try {
                                assertNull(error);
                                assertFalse(contextEntered.get(), "TurnExecutor must have exited");
                                assertFalse(ActorRuntime.inActorExecution());
                                assertFalse(runtime.currentThreadOwnsActorExecutionLease());
                                assertEquals(List.of(2, 4, 6), reply.value());
                                assertNotSame(guestMutable, reply.value());
                                assertThrows(UnsupportedOperationException.class,
                                        () -> reply.value().add(8));
                            } catch (Throwable thrown) { failed.set(thrown); }
                            finally { observed.countDown(); }
                        });
                assertTrue(contextEntered.get(), "external reply cannot arrive in receive");
            });
            actor.send(1);
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            assertNull(failed.get(), () -> String.valueOf(failed.get()));
        }
    }

    @Test
    void producerAfterStopCannotPublishExternalTypedReply() throws Exception {
        var accepted = new CountDownLatch(1);
        var producer = new CompletableFuture<Integer>();
        var exposed = new AtomicReference<java.util.concurrent.CompletionStage<
                ActorMiddlewareValuePipeline.Reply<Integer>>>();
        var bridge = new ActorUnaryReplyBridge<>(
                new ActorMiddlewareValuePipeline<Integer>(
                        List.of(), List.of(), Set.of(), req -> producer));
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                var ticket = new ActorUnaryReplySlot<
                        ActorMiddlewareValuePipeline.Reply<Integer>>(
                        context.self().id(), context.self().generation(), UUID.randomUUID());
                exposed.set(bridge.dispatchActorOwnedPostExit(
                        context, ticket, request(), () -> false));
                accepted.countDown();
            });
            actor.send(1);
            assertTrue(accepted.await(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> exposed.get().toCompletableFuture().join());
            producer.complete(99);
            assertThrows(RuntimeException.class, () -> exposed.get().toCompletableFuture().join());
        }
    }

    @Test
    void unsupportedGuestReplyFailsAfterContextExit() throws Exception {
        var contextEntered = new AtomicBoolean();
        var completed = new CountDownLatch(1);
        var problem = new AtomicReference<Throwable>();
        var bridge = new ActorUnaryReplyBridge<Object>(
                new ActorMiddlewareValuePipeline<Object>(
                        List.of(), List.of(), Set.of(),
                        req -> CompletableFuture.completedFuture(new Object())));
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(), turn -> {
                    contextEntered.set(true);
                    try { turn.run(); }
                    finally { contextEntered.set(false); }
                })) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                var ticket = new ActorUnaryReplySlot<
                        ActorMiddlewareValuePipeline.Reply<Object>>(
                        context.self().id(), context.self().generation(), UUID.randomUUID());
                bridge.dispatchActorOwnedPostExit(context, ticket, request(), () -> false)
                        .whenComplete((value, error) -> {
                            try {
                                assertFalse(contextEntered.get());
                                assertNull(value);
                                assertNotNull(error);
                            } catch (Throwable thrown) { problem.set(thrown); }
                            finally { completed.countDown(); }
                        });
            });
            actor.send(1);
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertNull(problem.get(), () -> String.valueOf(problem.get()));
        }
    }

    @Test
    void postCarrierPublicationRejectsOffTurnAdmission() {
        try (var runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class,
                    () -> runtime.publishAfterCurrentActorCarrierExit(() -> fail("off-turn")));
        }
    }
}
