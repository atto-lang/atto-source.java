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
            var caller = registry.newActorEndpoint();
            var responder = registry.newActorEndpoint();
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
                        runtime, registry, caller, responder,
                        worker, "ping", String.class, Duration.ofSeconds(3), timer));
                requestIssued.countDown();
            });
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
            var clientEndpoint = registry.newActorEndpoint();
            var workerEndpoint = registry.newActorEndpoint();
            var supervisorAppEndpoint = registry.newSupervisorApplicationEndpoint();
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
                            runtime, registry, supervisorAppEndpoint, serviceRef.get(),
                            workerEndpoint, worker, "stats", String.class,
                            Duration.ofSeconds(3), timer));
                    issuedByService.countDown();
                }
            });
            serviceRef.set(supervisorService);

            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                if (message.equals("ask-policy")) {
                    actorResult.set(ActorReplySchedulerBridge.requestCurrentActor(
                            runtime, registry, clientEndpoint, supervisorAppEndpoint,
                            supervisorService, "policy", String.class,
                            Duration.ofSeconds(3), timer));
                    issuedByClient.countDown();
                } else {
                    try {
                        ActorReplySchedulerBridge.requestCurrentSupervisorApplication(
                                runtime, registry, supervisorAppEndpoint,
                                supervisorService, workerEndpoint, worker, "stats",
                                String.class, Duration.ofSeconds(3), timer);
                    } catch (SecurityException expected) {
                        unauthorized.set(expected);
                    }
                }
            });
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
            var caller = registry.newActorEndpoint();
            var callee = registry.newActorEndpoint();
            var issued = new CountDownLatch(1);
            var returned = new AtomicReference<OresFuture<String>>();
            var worker = runtime.<String>spawn(() -> (message, ctx) -> {
                assertTrue(ctx.replyCurrentCall("wrong-success"));
                throw new IllegalStateException("handler aborted after staging");
            });
            var client = runtime.<String>spawn(() -> (message, ctx) -> {
                returned.set(ActorReplySchedulerBridge.requestCurrentActor(
                        runtime, registry, caller, callee, worker, "work",
                        String.class, Duration.ofSeconds(3), timer));
                issued.countDown();
            });
            client.send("request");
            assertTrue(issued.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> returned.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, registry.pendingCount());
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
