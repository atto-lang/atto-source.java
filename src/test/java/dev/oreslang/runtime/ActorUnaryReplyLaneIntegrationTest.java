package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorUnaryReplyLaneIntegrationTest {
    private static ActorMiddlewarePipeline.Envelope request() {
        return new ActorMiddlewarePipeline.Envelope("request", Map.of());
    }

    @Test
    void settledHostMiddlewarePublishesUnaryReplyOnlyInSubsequentActorTurn() throws Exception {
        var completed = new CountDownLatch(1);
        var inReceive = new AtomicBoolean();
        var thrown = new AtomicReference<Throwable>();
        var seen = new AtomicReference<Integer>();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(),
                req -> CompletableFuture.completedFuture(84));
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                inReceive.set(true);
                try {
                    var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                            context.self().id(), context.self().generation(), UUID.randomUUID());
                    var result = bridge.dispatchActorOwned(context, slot, request(), () -> false);
                    assertEquals(ActorUnaryReplySlot.State.PENDING, slot.state(),
                            "an already-settled producer cannot publish inline");
                    result.whenComplete((reply, error) -> {
                        try {
                            assertNull(error);
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            assertEquals(context.self().id(),
                                    ActorRuntime.currentActorId().orElseThrow());
                            assertFalse(inReceive.get(), "must unwind receive first");
                            assertEquals(ActorUnaryReplySlot.State.REPLIED, slot.state());
                            seen.set(reply.value());
                        } catch (Throwable failure) {
                            thrown.set(failure);
                        } finally {
                            completed.countDown();
                        }
                    });
                } catch (Throwable failure) {
                    thrown.set(failure);
                    completed.countDown();
                } finally {
                    inReceive.set(false);
                }
            });
            actor.send(1);
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertNull(thrown.get(), () -> String.valueOf(thrown.get()));
            assertEquals(84, seen.get());
        }
    }

    @Test
    void completedUnaryRequestDetachesActorDoneObserverWhileActorRemainsAlive() throws Exception {
        var replied = new CountDownLatch(1);
        var checked = new CountDownLatch(1);
        var problem = new AtomicReference<Throwable>();
        var initialWaiters = new java.util.concurrent.atomic.AtomicInteger(-1);
        var bridge = new ActorUnaryReplyBridge<>(
                new ActorMiddlewareValuePipeline<Integer>(
                        List.of(), List.of(), Set.of(),
                        req -> CompletableFuture.completedFuture(7)));
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                try {
                    if (message == 1) {
                        initialWaiters.set(
                                context.self().getDone().pendingRuntimeWaiterCount());
                        var slot = new ActorUnaryReplySlot<
                                ActorMiddlewareValuePipeline.Reply<Integer>>(
                                context.self().id(), context.self().generation(),
                                UUID.randomUUID());
                        bridge.dispatchActorOwned(context, slot, request(), () -> false)
                                .whenComplete((result, failure) -> replied.countDown());
                    } else {
                        assertEquals(initialWaiters.get(),
                                context.self().getDone().pendingRuntimeWaiterCount(),
                                "completed requests must not accumulate stop waiters");
                        checked.countDown();
                    }
                } catch (Throwable failure) {
                    problem.set(failure);
                    checked.countDown();
                }
            });
            actor.send(1);
            assertTrue(replied.await(3, TimeUnit.SECONDS));
            actor.send(2);
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertNull(problem.get(), () -> String.valueOf(problem.get()));
            assertTrue(actor.isAlive());
        }
    }

    @Test
    void actorTerminationCancelsPendingUnaryTicketBeforeProducerSettles() throws Exception {
        var dispatched = new CountDownLatch(1);
        var thrown = new AtomicReference<Throwable>();
        var slotRef = new AtomicReference<ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>>();
        var completionRef = new AtomicReference<CompletionStage<ActorMiddlewareValuePipeline.Reply<Integer>>>();
        var producer = new CompletableFuture<Integer>();
        var bridge = new ActorUnaryReplyBridge<>(new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(), req -> producer));
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                try {
                    var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                            context.self().id(), context.self().generation(), UUID.randomUUID());
                    slotRef.set(slot);
                    completionRef.set(bridge.dispatchActorOwned(context, slot, request(), () -> false));
                } catch (Throwable failure) {
                    thrown.set(failure);
                } finally {
                    dispatched.countDown();
                }
            });
            actor.send(1);
            assertTrue(dispatched.await(3, TimeUnit.SECONDS));
            assertNull(thrown.get());
            assertEquals(ActorUnaryReplySlot.State.PENDING, slotRef.get().state());
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(ActorUnaryReplySlot.State.CANCELLED, slotRef.get().state());
            assertThrows(RuntimeException.class,
                    () -> completionRef.get().toCompletableFuture().join());
            producer.complete(22);
            assertEquals(ActorUnaryReplySlot.State.CANCELLED, slotRef.get().state());
        }
    }

    @Test
    void actorOwnedUnaryDispatchRejectsOffTurnInvocationBeforeClaim() throws Exception {
        var ready = new CountDownLatch(1);
        var contextRef = new AtomicReference<ActorRuntime.ActorContext<Integer>>();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(),
                req -> CompletableFuture.completedFuture(4));
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                contextRef.set(context);
                ready.countDown();
            });
            actor.send(1);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                    actor.id(), actor.generation(), UUID.randomUUID());
            assertThrows(SecurityException.class,
                    () -> bridge.dispatchActorOwned(
                            contextRef.get(), slot, request(), () -> false));
            assertEquals(ActorUnaryReplySlot.State.PENDING, slot.state());
        }
    }
}
