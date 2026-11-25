package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime source-envelope integration: detect dependencies only
 * after serial mailbox suspension, never simply after request allocation.
 * These host-injected executor refs are NOT guest actor fields.
 */
@Timeout(30)
final class ActorRequestCycleIntegrationTest {
    private static final class Forwarder implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicInteger reached = new AtomicInteger();
        private volatile ActorRuntime.ActorRef<Object> destination;

        Forwarder(SharedCodeImageStore.CodeImage image,
                  CountDownLatch started, CountDownLatch release) {
            this.image = image;
            this.started = started;
            this.release = release;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorProtocol actorProtocol(String type) {
            return ActorRuntime.ActorProtocol.UNARY;
        }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String type, ActorRuntime.ActorContext<Object> context) {
            return context.self()::id;
        }

        @Override public void receiveActor(
                String type, ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            fail("unary actors cannot receive stream mail");
        }

        @Override public Object requestActor(
                String type, ActorRuntime.ActorOwnedGuestState state,
                Object message, ActorRuntime.ActorContext<Object> context) throws Exception {
            reached.incrementAndGet();
            return switch ((String) message) {
                case "cycle-A" -> destination.request("cycle-B");
                case "cycle-B" -> destination.request("cycle-return");
                case "cycle-return" -> "should not have run inside blocked mailbox";
                case "forward-block" -> destination.request("block");
                case "fire-without-await" -> {
                    destination.request("fast");
                    yield "not-suspended";
                }
                case "block" -> {
                    if (started != null) started.countDown();
                    if (release != null && !release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test service unblock timed out");
                    }
                    yield "unblocked";
                }
                case "fast" -> "ready";
                default -> "ok:" + message;
            };
        }
    }

    @Test
    void serialActorToSupervisorToActorCycleFailsWithoutStrandingMailboxes()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("cycle-integration.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var aImpl = new Forwarder(image, null, null);
            var bImpl = new Forwarder(image, null, null);
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, aImpl, "A");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, bImpl, "SupervisorData");
            aImpl.destination = b;
            bImpl.destination = a;
            try {
                a.ready().get(2, TimeUnit.SECONDS);
                b.ready().get(2, TimeUnit.SECONDS);
                var failed = assertThrows(ExecutionException.class,
                        () -> a.request("cycle-A").get(3, TimeUnit.SECONDS));
                assertInstanceOf(ActorRequestDependencyGraph.CyclicRequestException.class,
                        failed.getCause());
                assertEquals(0, runtime.activeSerialRequestDependencyCount());
                assertTrue(a.isAlive());
                assertTrue(b.isAlive());
                assertEquals("ok:recovered-a",
                        a.request("recovered-a").get(2, TimeUnit.SECONDS));
                assertEquals("ok:recovered-b",
                        b.request("recovered-b").get(2, TimeUnit.SECONDS));
                assertEquals(0, runtime.activeSerialRequestDependencyCount());
            } finally {
                a.cancel();
                b.cancel();
            }
        }
    }

    @Test
    void onlyTrulySuspendedRequestAddsDependencyAndReleasesOnCompletion()
            throws Exception {
        var serviceStarted = new CountDownLatch(1);
        var serviceRelease = new CountDownLatch(1);
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("suspension-integration.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var serviceImpl = new Forwarder(image, serviceStarted, serviceRelease);
            var callerImpl = new Forwarder(image, null, null);
            var service = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, serviceImpl, "Service");
            var caller = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, callerImpl, "Caller");
            callerImpl.destination = service;
            try {
                caller.ready().get(2, TimeUnit.SECONDS);
                service.ready().get(2, TimeUnit.SECONDS);
                assertEquals("not-suspended",
                        caller.request("fire-without-await").get(2, TimeUnit.SECONDS));
                assertEquals(0, runtime.activeSerialRequestDependencyCount(),
                        "mere outbound requests must not block the actor");

                var pending = caller.request("forward-block");
                assertTrue(serviceStarted.await(2, TimeUnit.SECONDS));
                // The caller's source receive must suspend on its outbound
                // request before the producer thread can settle it.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (runtime.activeSerialRequestDependencyCount() == 0
                        && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                assertEquals(1, runtime.activeSerialRequestDependencyCount());
                serviceRelease.countDown();
                assertEquals("unblocked", pending.get(2, TimeUnit.SECONDS));
                assertEquals(0, runtime.activeSerialRequestDependencyCount());
                assertEquals("ok:after", caller.request("after").get(2, TimeUnit.SECONDS));
            } finally {
                serviceRelease.countDown();
                caller.cancel();
                service.cancel();
            }
        }
    }

    @Test
    void requesterTerminationReleasesGraphEvenWhenServiceAlreadyStarted()
            throws Exception {
        var serviceStarted = new CountDownLatch(1);
        var serviceRelease = new CountDownLatch(1);
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("terminate-cycle-integration.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var serviceImpl = new Forwarder(image, serviceStarted, serviceRelease);
            var callerImpl = new Forwarder(image, null, null);
            var service = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, serviceImpl, "Service");
            var caller = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, callerImpl, "Caller");
            callerImpl.destination = service;
            try {
                caller.ready().get(2, TimeUnit.SECONDS);
                service.ready().get(2, TimeUnit.SECONDS);
                var pending = caller.request("forward-block");
                assertTrue(serviceStarted.await(2, TimeUnit.SECONDS));
                caller.stop();
                assertThrows(ExecutionException.class,
                        () -> pending.get(3, TimeUnit.SECONDS));
                caller.done().get(3, TimeUnit.SECONDS);
                assertEquals(0, runtime.activeSerialRequestDependencyCount(),
                        "requester termination must release dependency leases");
            } finally {
                serviceRelease.countDown();
                caller.cancel();
                service.cancel();
            }
        }
    }
}
