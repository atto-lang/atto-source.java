package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Checks actor-lane reply wakeup instead of producer-thread guest reentry. */
final class ActorReplySchedulerBridgeTest {
    @Test
    void remoteReplyIsResumedOnCallingActorsSingleWriterLane() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timers = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(4);
            var caller = registry.newActorEndpoint();
            var supervisor = registry.newSupervisorApplicationEndpoint();
            var received = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var reply = new AtomicReference<GenerationalReplyRegistry.Ticket<Integer>>();
            var witnessedActorLease = new AtomicBoolean(false);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                var ticket = registry.reserve(caller, supervisor, Integer.class);
                reply.set(ticket);
                ActorReplySchedulerBridge.bindCurrentActor(
                        runtime, ticket, caller, Duration.ofSeconds(3), timers,
                        (value, failure) -> {
                            witnessedActorLease.set(runtime.currentThreadOwnsActorExecutionLease());
                            assertNull(failure);
                            assertEquals(19, value);
                            done.countDown();
                        });
                received.countDown();
            });
            ref.send("begin");
            assertTrue(received.await(3, TimeUnit.SECONDS));
            var producer = new Thread(() -> assertTrue(reply.get().reply(supervisor, 19)));
            producer.start();
            producer.join(3000);
            assertFalse(producer.isAlive());
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(witnessedActorLease.get(), "continuation must hold caller actor lease");
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }

    @Test
    void deadlineResumesOnCallerLaneAndFencesLateSuccess() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var timers = Executors.newSingleThreadScheduledExecutor()) {
            var registry = new GenerationalReplyRegistry(1);
            var caller = registry.newActorEndpoint();
            var child = registry.newActorEndpoint();
            var ticketRef = new AtomicReference<GenerationalReplyRegistry.Ticket<String>>();
            var settled = new CountDownLatch(1);
            var actorLease = new AtomicBoolean(false);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                var ticket = registry.reserve(caller, child, String.class);
                ticketRef.set(ticket);
                ActorReplySchedulerBridge.bindCurrentActor(
                        runtime, ticket, caller, Duration.ofMillis(30), timers,
                        (value, failure) -> {
                            actorLease.set(runtime.currentThreadOwnsActorExecutionLease());
                            assertInstanceOf(TimeoutException.class, failure);
                            settled.countDown();
                        });
            });
            ref.send("begin");
            assertTrue(settled.await(3, TimeUnit.SECONDS));
            assertTrue(actorLease.get());
            assertFalse(ticketRef.get().reply(child, "late"));
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }
}
