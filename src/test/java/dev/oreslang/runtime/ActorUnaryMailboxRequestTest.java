package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable host mailbox integration: reply capability is a VM envelope
 * sidecar; the guest-transferred String message has no capability field.
 */
@Timeout(20)
final class ActorUnaryMailboxRequestTest {
    @Test
    void actorToActorUnaryUsesSerializedMailboxAndTypedReplyFuture() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(16);
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var responder = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var requestIssued = new CountDownLatch(1);
            var completion = new AtomicReference<OresFuture<String>>();
            var calleeLease = new AtomicReference<Boolean>();

            var worker = runtime.<String>spawn(() -> (message, ctx) -> {
                calleeLease.set(runtime.currentThreadOwnsActorExecutionLease());
                assertEquals("ping", message);
                assertTrue(ctx.replyCurrentCall("pong"));
                assertFalse(ctx.replyCurrentCall("duplicate"));
            });
            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                completion.set(ActorReplySchedulerBridge.requestCurrentActor(
                        runtime, registry, caller.get(), responder.get(),
                        worker, "ping", String.class, Duration.ofSeconds(3), timer));
                requestIssued.countDown();
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, client));
            responder.set(runtime.bindActorReplyEndpoint(registry, worker));
            client.send("go");
            assertTrue(requestIssued.await(3, TimeUnit.SECONDS));
            assertEquals("pong", completion.get().get(3, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, calleeLease.get());
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void actorAndSupervisorApplicationServiceCallEachOtherWithoutControlAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(8);
            var clientEndpoint = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var workerEndpoint = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var supervisorAppEndpoint = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var issuedByClient = new CountDownLatch(1);
            var issuedByService = new CountDownLatch(1);
            var actorResult = new AtomicReference<OresFuture<String>>();
            var supervisorResult = new AtomicReference<OresFuture<String>>();
            var unauthorized = new AtomicReference<Throwable>();

            var worker = runtime.<String>spawn(() ->
                    (message, ctx) -> assertTrue(ctx.replyCurrentCall("stats")));
            var serviceRef = new AtomicReference<ActorRuntime.ActorRef<String>>();
            var supervisorService = runtime.<String>spawn(() -> (message, ctx) -> {
                if (message.equals("policy")) {
                    assertTrue(ctx.replyCurrentCall("restricted-policy"));
                } else if (message.equals("get-stats")) {
                    supervisorResult.set(ActorReplySchedulerBridge.requestCurrentSupervisorApplication(
                            runtime, registry, supervisorAppEndpoint.get(), serviceRef.get(),
                            workerEndpoint.get(), worker, "stats", String.class,
                            Duration.ofSeconds(3), timer));
                    issuedByService.countDown();
                }
            });
            serviceRef.set(supervisorService);

            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                if (message.equals("ask-policy")) {
                    actorResult.set(ActorReplySchedulerBridge.requestCurrentActor(
                            runtime, registry, clientEndpoint.get(), supervisorAppEndpoint.get(),
                            supervisorService, "policy", String.class,
                            Duration.ofSeconds(3), timer));
                    issuedByClient.countDown();
                } else {
                    try {
                        ActorReplySchedulerBridge.requestCurrentSupervisorApplication(
                                runtime, registry, supervisorAppEndpoint.get(),
                                supervisorService, workerEndpoint.get(), worker, "stats",
                                String.class, Duration.ofSeconds(3), timer);
                    } catch (SecurityException expected) {
                        unauthorized.set(expected);
                    }
                }
            });
            clientEndpoint.set(runtime.bindActorReplyEndpoint(registry, client));
            workerEndpoint.set(runtime.bindActorReplyEndpoint(registry, worker));
            supervisorAppEndpoint.set(runtime.bindSupervisorApplicationReplyEndpoint(registry, supervisorService));
            client.send("ask-policy");
            assertTrue(issuedByClient.await(3, TimeUnit.SECONDS));
            assertEquals("restricted-policy", actorResult.get().get(3, TimeUnit.SECONDS));

            supervisorService.send("get-stats");
            assertTrue(issuedByService.await(3, TimeUnit.SECONDS));
            assertEquals("stats", supervisorResult.get().get(3, TimeUnit.SECONDS));

            client.send("impersonate");
            long timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (unauthorized.get() == null && System.nanoTime() < timeoutAt) Thread.sleep(2);
            assertInstanceOf(SecurityException.class, unauthorized.get());
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void handlerThrowAfterStagingNeverPublishesSuccess() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(4);
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var callee = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var issued = new CountDownLatch(1);
            var returned = new AtomicReference<OresFuture<String>>();
            var worker = runtime.<String>spawn(() -> (message, ctx) -> {
                assertTrue(ctx.replyCurrentCall("wrong-success"));
                throw new IllegalStateException("handler aborted after staging");
            });
            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                returned.set(ActorReplySchedulerBridge.requestCurrentActor(
                        runtime, registry, caller.get(), callee.get(), worker, "work",
                        String.class, Duration.ofSeconds(3), timer));
                issued.countDown();
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, client));
            callee.set(runtime.bindActorReplyEndpoint(registry, worker));
            client.send("request");
            assertTrue(issued.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> returned.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void unansweredCallFailsAndDoesNotLeakPendingSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(2);
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var callee = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var issued = new CountDownLatch(1);
            var future = new AtomicReference<OresFuture<String>>();
            var worker = runtime.<String>spawn(() -> (message, ctx) -> {
                assertEquals("unanswered", message);
                // Return without replying: an unanswered call must fail.
            });
            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                future.set(ActorReplySchedulerBridge.requestCurrentActor(
                        runtime, registry, caller.get(), callee.get(), worker, "unanswered",
                        String.class, Duration.ofSeconds(3), timer));
                issued.countDown();
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, client));
            callee.set(runtime.bindActorReplyEndpoint(registry, worker));
            client.send("begin");
            assertTrue(issued.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void failedMailboxAdmissionFailsReservedFutureAndReclaimsSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(2);
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var callee = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var issued = new CountDownLatch(1);
            var future = new AtomicReference<OresFuture<String>>();
            var worker = runtime.<Object>spawn(() ->
                    (message, ctx) -> fail("invalid mutable host payload must not execute"));
            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                future.set(ActorReplySchedulerBridge.requestCurrentActor(
                        runtime, registry, caller.get(), callee.get(), worker,
                        new StringBuilder("mutable host data"), String.class,
                        Duration.ofSeconds(3), timer));
                issued.countDown();
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, client));
            callee.set(runtime.bindActorReplyEndpoint(registry, worker));
            client.send("begin");
            assertTrue(issued.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void wrongResponseTypeCannotSettleUnarySlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(2);
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var callee = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var issued = new CountDownLatch(1);
            var future = new AtomicReference<OresFuture<String>>();
            var worker = runtime.<String>spawn(() -> (message, ctx) ->
                    assertThrows(IllegalArgumentException.class,
                            () -> ctx.replyCurrentCall(42)));
            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                future.set(ActorReplySchedulerBridge.requestCurrentActor(
                        runtime, registry, caller.get(), callee.get(), worker, "wrong-type",
                        String.class, Duration.ofSeconds(3), timer));
                issued.countDown();
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, client));
            callee.set(runtime.bindActorReplyEndpoint(registry, worker));
            client.send("begin");
            assertTrue(issued.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void endpointImpersonationAndCalleeRedirectionAreDeniedBeforeReservation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(4);
            var checked = new CountDownLatch(1);
            var denied = new AtomicReference<Throwable>();
            var ownEndpoint = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var foreignEndpoint = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var targetEndpoint = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var target = runtime.<String>spawn(() ->
                    (message, ctx) -> fail("forged request must not execute"));
            var other = runtime.<String>spawn(() -> (message, ctx) -> { });
            var caller = runtime.<String>spawn(() -> (message, ctx) -> {
                try {
                    var own = ownEndpoint.get();
                    var foreign = foreignEndpoint.get();
                    var callee = targetEndpoint.get();
                    assertThrows(SecurityException.class, () ->
                            runtime.bindActorReplyEndpoint(registry, ctx.self()));
                    assertThrows(SecurityException.class, () ->
                            ActorReplySchedulerBridge.requestCurrentActor(
                                    runtime, registry, foreign, callee, target, "work",
                                    String.class, Duration.ofSeconds(3), timer));
                    assertThrows(SecurityException.class, () ->
                            ActorReplySchedulerBridge.requestCurrentActor(
                                    runtime, registry, own, foreign, target, "work",
                                    String.class, Duration.ofSeconds(3), timer));
                    assertThrows(SecurityException.class, () ->
                            runtime.bindSupervisorApplicationReplyEndpoint(registry, ctx.self()));
                } catch (Throwable failure) {
                    denied.set(failure);
                } finally {
                    checked.countDown();
                }
            });
            ownEndpoint.set(runtime.bindActorReplyEndpoint(registry, caller));
            foreignEndpoint.set(runtime.bindActorReplyEndpoint(registry, other));
            targetEndpoint.set(runtime.bindActorReplyEndpoint(registry, target));
            caller.send("audit");
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertNull(denied.get(), "actor must not be able to impersonate either endpoint");
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void stoppingActiveActorImmediatelyRevokesPendingCallsBeforeItsCarrierExits()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var registry = new GenerationalReplyRegistry(1);
            var running = new CountDownLatch(1);
            var allowExit = new CountDownLatch(1);
            var source = runtime.<String>spawn(() -> (message, ctx) -> {
                running.countDown();
                assertTrue(allowExit.await(3, TimeUnit.SECONDS));
            });
            var target = runtime.<String>spawn(() -> (message, ctx) -> { });
            var caller = runtime.bindActorReplyEndpoint(registry, source);
            var callee = runtime.bindActorReplyEndpoint(registry, target);
            var ticket = registry.reserve(caller, callee, String.class);
            try {
                source.send("hold");
                assertTrue(running.await(3, TimeUnit.SECONDS));
                source.stop();
                assertEquals(0, registry.pendingCount(),
                        "stop must revoke even when the actor has not exited its carrier");
                assertFalse(ticket.reply(callee, "stale"));
            } finally {
                allowExit.countDown();
            }
            source.done().get(3, TimeUnit.SECONDS);
            registry.close();
        }
    }

    @Test
    void retiringCallerIncarnationRejectsLateResponseAndFreesSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var registry = new GenerationalReplyRegistry(1);
            var source = runtime.<String>spawn(() -> (message, ctx) -> { });
            var target = runtime.<String>spawn(() -> (message, ctx) -> { });
            var caller = runtime.bindActorReplyEndpoint(registry, source);
            var callee = runtime.bindActorReplyEndpoint(registry, target);
            var ticket = registry.reserve(caller, callee, String.class);
            assertEquals(1, registry.pendingCount());
            source.stop();
            source.done().get(3, TimeUnit.SECONDS);
            assertEquals(0, registry.pendingCount());
            assertFalse(ticket.reply(callee, "late"));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> ticket.result().toCompletableFuture().get(3, TimeUnit.SECONDS));
            registry.close();
        }
    }

    @Test
    void ordinarySendHasNoForgedReplyCapability() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var received = new CountDownLatch(1);
            var denied = new AtomicReference<Throwable>();
            var actor = runtime.<String>spawn(() -> (message, ctx) -> {
                try {
                    ctx.replyCurrentCall("forged");
                } catch (SecurityException expected) {
                    denied.set(expected);
                } finally {
                    received.countDown();
                }
            });
            actor.send("ordinary");
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, denied.get());
        }
    }
}
