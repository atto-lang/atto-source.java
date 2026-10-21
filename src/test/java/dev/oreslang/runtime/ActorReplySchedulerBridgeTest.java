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
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var supervisor = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var received = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var reply = new AtomicReference<GenerationalReplyRegistry.Ticket<Integer>>();
            var witnessedActorLease = new AtomicBoolean(false);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                var ticket = registry.reserve(caller.get(), supervisor.get(), Integer.class);
                reply.set(ticket);
                ActorReplySchedulerBridge.bindCurrentActor(
                        runtime, ticket, caller.get(), Duration.ofSeconds(3), timers,
                        (value, failure) -> {
                            witnessedActorLease.set(runtime.currentThreadOwnsActorExecutionLease());
                            assertNull(failure);
                            assertEquals(19, value);
                            done.countDown();
                        });
                received.countDown();
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, ref));
            var service = runtime.<String>spawn(() -> (message, context) -> { });
            supervisor.set(runtime.bindSupervisorApplicationReplyEndpoint(registry, service));
            ref.send("begin");
            assertTrue(received.await(3, TimeUnit.SECONDS));
            var producer = new Thread(() -> assertTrue(reply.get().reply(supervisor.get(), 19)));
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
            var caller = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var child = new AtomicReference<GenerationalReplyRegistry.Endpoint>();
            var ticketRef = new AtomicReference<GenerationalReplyRegistry.Ticket<String>>();
            var settled = new CountDownLatch(1);
            var actorLease = new AtomicBoolean(false);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                var ticket = registry.reserve(caller.get(), child.get(), String.class);
                ticketRef.set(ticket);
                ActorReplySchedulerBridge.bindCurrentActor(
                        runtime, ticket, caller.get(), Duration.ofMillis(30), timers,
                        (value, failure) -> {
                            actorLease.set(runtime.currentThreadOwnsActorExecutionLease());
                            assertInstanceOf(TimeoutException.class, failure);
                            settled.countDown();
                        });
            });
            caller.set(runtime.bindActorReplyEndpoint(registry, ref));
            var service = runtime.<String>spawn(() -> (message, context) -> { });
            child.set(runtime.bindActorReplyEndpoint(registry, service));
            ref.send("begin");
            assertTrue(settled.await(3, TimeUnit.SECONDS));
            assertTrue(actorLease.get());
            assertFalse(ticketRef.get().reply(child.get(), "late"));
            assertEquals(0, registry.pendingCount());
            registry.close();
        }
    }
}
