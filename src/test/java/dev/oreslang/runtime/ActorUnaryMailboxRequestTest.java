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
