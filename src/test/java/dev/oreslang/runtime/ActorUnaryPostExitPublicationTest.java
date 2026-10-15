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
    @Test
    void stopAfterGuestTurnButBeforeContextLeaveDropsStagedReply() throws Exception {
        var owner = new AtomicReference<ActorRuntime.ActorRef<Integer>>();
        var admitted = new CountDownLatch(1);
        var completedAfterReply = new java.util.concurrent.atomic.AtomicInteger();
        var ticketRef = new AtomicReference<ActorUnaryReplySlot<
                ActorMiddlewareValuePipeline.Reply<Integer>>>();
        var external = new AtomicReference<java.util.concurrent.CompletionStage<
                ActorMiddlewareValuePipeline.Reply<Integer>>>();
        var bridge = new ActorUnaryReplyBridge<>(
                new ActorMiddlewareValuePipeline<Integer>(
                        List.of(), List.of(), Set.of(),
                        req -> CompletableFuture.completedFuture(17)));

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(), turn -> {
                    turn.run();
                    // Trigger STOP after the guest callback unwound, but before
                    // TurnExecutor.execute returned to the actor dispatcher.
                    var actor = owner.get();
                    var ticket = ticketRef.get();
                    // First REPLIED turn settles the actor-local slot; the
                    // next turn freezes and stages its external publication.
                    if (actor != null && ticket != null
                            && ticket.state() == ActorUnaryReplySlot.State.REPLIED
                            && completedAfterReply.incrementAndGet() == 2) actor.stop();
                })) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                var ticket = new ActorUnaryReplySlot<
                        ActorMiddlewareValuePipeline.Reply<Integer>>(
                        context.self().id(), context.self().generation(), UUID.randomUUID());
                ticketRef.set(ticket);
                external.set(bridge.dispatchActorOwnedPostExit(
                        context, ticket, request(), () -> false));
                admitted.countDown();
            });
            owner.set(actor);
            actor.send(1);
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(2, completedAfterReply.get(), "must stop at staged publication boundary");
            assertThrows(RuntimeException.class, () -> external.get().toCompletableFuture().join());
        }
    }

    @Test
    void completedExternalRequestsDetachShutdownObserversOnLiveActor() throws Exception {
        var replied = new CountDownLatch(1);
        var inspected = new CountDownLatch(1);
        var problem = new AtomicReference<Throwable>();
        var baseline = new java.util.concurrent.atomic.AtomicInteger(-1);
        var bridge = new ActorUnaryReplyBridge<>(
                new ActorMiddlewareValuePipeline<Integer>(
                        List.of(), List.of(), Set.of(),
                        req -> CompletableFuture.completedFuture(12)));
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                try {
                    if (message == 1) {
                        baseline.set(context.self().getDone().pendingRuntimeWaiterCount());
                        var ticket = new ActorUnaryReplySlot<
                                ActorMiddlewareValuePipeline.Reply<Integer>>(
                                context.self().id(), context.self().generation(), UUID.randomUUID());
                        bridge.dispatchActorOwnedPostExit(
                                context, ticket, request(), () -> false)
                                .whenComplete((reply, failure) -> {
                                    if (failure != null || reply == null || reply.value() != 12)
                                        problem.set(new AssertionError("external reply failed", failure));
                                    replied.countDown();
                                });
                    } else {
                        assertEquals(baseline.get(),
                                context.self().getDone().pendingRuntimeWaiterCount(),
                                "external requests must detach owner-done observers");
                        inspected.countDown();
                    }
                } catch (Throwable thrown) {
                    problem.set(thrown);
                    inspected.countDown();
                }
            });
            actor.send(1);
            assertTrue(replied.await(3, TimeUnit.SECONDS));
            actor.send(2);
            assertTrue(inspected.await(3, TimeUnit.SECONDS));
            assertNull(problem.get(), () -> String.valueOf(problem.get()));
            assertTrue(actor.isAlive());
        }
    }

    @Test
    void boundedPostCarrierQueueRejectsOverflowWithoutRunningInline() throws Exception {
        var registered = new CountDownLatch(1);
        var published = new CountDownLatch(1024);
        var problem = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                try {
                    for (int i = 0; i < 1024; i++) {
                        runtime.publishAfterCurrentActorCarrierExit(published::countDown);
                    }
                    assertEquals(1024, published.getCount(), "publisher must never run inline");
                    assertThrows(java.util.concurrent.RejectedExecutionException.class,
                            () -> runtime.publishAfterCurrentActorCarrierExit(
                                    () -> fail("overflow publication")));
                } catch (Throwable error) {
                    problem.set(error);
                } finally {
                    registered.countDown();
                }
            });
            actor.send(1);
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertTrue(published.await(3, TimeUnit.SECONDS));
            assertNull(problem.get(), () -> String.valueOf(problem.get()));
        }
    }

    @Test
    void externalReplySubscriberCanWaitForLaterActorTurnWithoutDeadlockingCarrier() throws Exception {
        var completed = new CountDownLatch(1);
        var secondTurn = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var bridge = new ActorUnaryReplyBridge<>(
                new ActorMiddlewareValuePipeline<Integer>(
                        List.of(), List.of(), Set.of(),
                        req -> CompletableFuture.completedFuture(31)));
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                if (message == 2) {
                    secondTurn.countDown();
                    return;
                }
                var ticket = new ActorUnaryReplySlot<
                        ActorMiddlewareValuePipeline.Reply<Integer>>(
                        context.self().id(), context.self().generation(), UUID.randomUUID());
                bridge.dispatchActorOwnedPostExit(context, ticket, request(), () -> false)
                        .whenComplete((reply, error) -> {
                            try {
                                assertNull(error);
                                assertEquals(31, reply.value());
                                assertFalse(ActorRuntime.inActorExecution());
                                assertFalse(ActorRuntime.isActorCarrierThread());
                                // Deliberately block this external subscriber on
                                // the very same actor's next mailbox turn.
                                context.self().send(2);
                                assertTrue(secondTurn.await(3, TimeUnit.SECONDS),
                                        "external subscriber must not hold the actor scheduling lease");
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            } finally {
                                completed.countDown();
                            }
                        });
            });
            actor.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(actor.isAlive());
        }
    }

    @Test
    void postExitCallbackCannotExecuteOnActorDispatcherCarrier() throws Exception {
        var completed = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) ->
                    runtime.publishAfterCurrentActorCarrierExit(() -> {
                        try {
                            assertFalse(ActorRuntime.inActorExecution());
                            assertFalse(ActorRuntime.isActorCarrierThread());
                            assertFalse(runtime.currentThreadOwnsActorExecutionLease());
                            assertTrue(Thread.currentThread().getName()
                                    .startsWith("ores-external-reply-publisher-"));
                        } catch (Throwable thrown) {
                            error.set(thrown);
                        } finally {
                            completed.countDown();
                        }
                    }));
            actor.send(1);
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertNull(error.get(), () -> String.valueOf(error.get()));
        }
    }

}
