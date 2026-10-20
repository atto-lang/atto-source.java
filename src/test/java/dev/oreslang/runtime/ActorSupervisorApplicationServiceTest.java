package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host harness proof of the typed DATA-service composition proposed for
 * child -> supervisor application RPC. This is not yet source syntax for
 * SupervisorAppRef and must never be implemented on reserved CONTROL.
 */
@Timeout(20)
final class ActorSupervisorApplicationServiceTest {
    private static final class AppService implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final AtomicInteger calls = new AtomicInteger();

        AppService(SharedCodeImageStore.CodeImage image) { this.image = image; }

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
            fail("the application endpoint must not receive event CONTROL mail");
        }

        @Override public Object requestActor(
                String type, ActorRuntime.ActorOwnedGuestState state,
                Object request, ActorRuntime.ActorContext<Object> context) {
            calls.incrementAndGet();
            if ("fail".equals(request)) throw new IllegalArgumentException("config unavailable");
            return "configuration:" + request;
        }
    }

    private static final class Child implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        // Runtime/host-injected application service capability, not an actor
        // guest field or a CONTROL mailbox reference.
        private final ActorRuntime.ActorRef<Object> appService;

        Child(SharedCodeImageStore.CodeImage image,
              ActorRuntime.ActorRef<Object> appService) {
            this.image = image;
            this.appService = appService;
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
            fail("child must not use event protocol for application requests");
        }

        @Override public Object requestActor(
                String type, ActorRuntime.ActorOwnedGuestState state,
                Object request, ActorRuntime.ActorContext<Object> context) {
            if ("self".equals(request)) {
                // The source-language variant must also detect this; runtime
                // already rejects a same-actor request before mailbox enqueue.
                return context.self().request("self");
            }
            // Child's unary turn suspends; application service receives a
            // separate DATA request and eventually settles child's Future.
            return appService.request(request);
        }
    }

    @Test
    void childCanCallSupervisorOwnedDataServiceAndResumeItsOriginalTurn() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("child-app-service.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var app = new AppService(image);
            ActorRuntime.ActorRef<Object> service = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, app, "ConfigService");
            service.ready().get(2, TimeUnit.SECONDS);
            var child = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, new Child(image, service), "Child");
            child.ready().get(2, TimeUnit.SECONDS);

            // Supervisor/root caller -> child -> explicitly granted DATA service
            // actor -> child completion -> supervisor/root Future.
            assertEquals("configuration:tenant-a",
                    child.request("tenant-a").get(2, TimeUnit.SECONDS));
            assertEquals("configuration:tenant-b",
                    child.request("tenant-b").get(2, TimeUnit.SECONDS));
            assertEquals(2, app.calls.get());

            var appFailure = assertThrows(ExecutionException.class,
                    () -> child.request("fail").get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, appFailure.getCause());
            assertTrue(child.isAlive(), "service error must not kill its caller");
            assertEquals("configuration:recovered",
                    child.request("recovered").get(2, TimeUnit.SECONDS));

            var selfCallFailure = assertThrows(ExecutionException.class,
                    () -> child.request("self").get(2, TimeUnit.SECONDS));
            assertTrue(selfCallFailure.getCause().getMessage().contains("itself"));
            assertTrue(child.isAlive(), "self-call is rejected before re-entrant dispatch");

            child.stop();
            child.done().get(2, TimeUnit.SECONDS);
            service.stop();
            service.done().get(2, TimeUnit.SECONDS);
        }
    }
}
