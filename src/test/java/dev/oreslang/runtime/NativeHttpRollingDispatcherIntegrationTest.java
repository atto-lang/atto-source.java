package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real NativeHttp socket + pooled SHARED ActorRuntime worker integration.
 * No mock exchange, alternate HttpServer, guest privileged reload API or
 * synthetic supervisor future is used in the routing path.
 */
@Timeout(40)
final class NativeHttpRollingDispatcherIntegrationTest {
    private static HttpRequest request(int port) {
        return HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/version"))
                .timeout(Duration.ofSeconds(12)).GET().build();
    }

    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private NativeHttp.Exchange deferred;

        private State(ActorRuntime.ActorId owner) { this.owner = owner; }
        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }
    }

    private static final class VersionCode implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final AtomicReference<NativeHttp> http;
        private final String version;
        private final boolean deferFirst;
        private final boolean failStartup;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final AtomicReference<ActorRuntime.ActorRef<Object>> actor = new AtomicReference<>();

        VersionCode(SharedCodeImageStore.CodeImage image,
                    AtomicReference<NativeHttp> http,
                    String version, boolean deferFirst, boolean failStartup) {
            this.image = image;
            this.http = http;
            this.version = version;
            this.deferFirst = deferFirst;
            this.failStartup = failStartup;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName, ActorRuntime.ActorContext<Object> context) {
            if (failStartup) throw new IllegalStateException("injected candidate startup failure");
            actor.set(context.self());
            return new State(context.self().id());
        }

        @Override public void receiveActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            State state = (State) raw;
            assertEquals(context.self().id(), state.ownerActorId());
            if ("RELEASE_G1".equals(mail.value())) {
                if (state.deferred == null)
                    throw new IllegalStateException("no suspended G1 exchange");
                state.deferred.respond(200, "text/plain", version);
                state.deferred = null;
                return;
            }
            NativeHttp.Exchange exchange = http.get().claim((String) mail.value());
            if (deferFirst && state.deferred == null && entered.getCount() != 0) {
                // Keep the transport exchange alive across an actor mailbox
                // turn without blocking a scheduler carrier. A later control
                // mail delivers the deferred response under actor ownership.
                state.deferred = exchange;
                entered.countDown();
                return;
            }
            exchange.respond(200, "text/plain", version);
        }
    }

    @Test
    void oldExchangeSurvivesPromotionOnSameNativeHttpListenerAndActorProcess()
            throws Exception {
        final long pid = ProcessHandle.current().pid();
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime actors = new ActorRuntime(IsolatePolicy.developer());
             NativeHttp http = new NativeHttp(actors);
             HttpClient client = HttpClient.newHttpClient()) {
            var image = images.publish("rolling/config.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var httpRef = new AtomicReference<>(http);
            var g1Code = new VersionCode(image, httpRef, "G1", true, false);
            var g2Code = new VersionCode(image, httpRef, "G2", false, false);
            var g1 = http.handler(g1Code, "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1);
            g1.warmup().get(8, TimeUnit.SECONDS);
            var server = http.listen("127.0.0.1", 0, 16, 1024, 10_000);
            int port = server.port();

            CompletableFuture<HttpResponse<String>> previous = null;
            try (var rolling = new NativeHttpRollingDispatcher(server, 4451L, g1, 3)) {
                rolling.start();
                previous =
                        client.sendAsync(request(port), HttpResponse.BodyHandlers.ofString());
                assertTrue(g1Code.entered.await(8, TimeUnit.SECONDS),
                        "actual G1 worker must claim HTTP exchange before publication");
                assertEquals(1L, rolling.snapshot().activeEpoch());

                var failedStartup = new VersionCode(image, httpRef, "BAD", false, true);
                var bad = http.handler(failedStartup, "RollingWorker",
                        ActorRuntime.ActorKind.SHARED).pooled(1);
                assertThrows(ExecutionException.class,
                        () -> rolling.promoteAfterWarmup(1L, bad).get(8, TimeUnit.SECONDS));
                assertEquals(1L, rolling.snapshot().activeEpoch(),
                        "failed candidate must leave G1 serving");
                HttpResponse<String> survivingG1 = client.send(request(port),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, survivingG1.statusCode());
                assertEquals("G1", survivingG1.body(),
                        "G1 must keep serving while an unrelated exchange is deferred");

                var g2 = http.handler(g2Code, "RollingWorker",
                        ActorRuntime.ActorKind.SHARED).pooled(1);
                assertTrue(rolling.promoteAfterWarmup(1L, g2).get(8, TimeUnit.SECONDS));
                assertEquals(2L, rolling.snapshot().activeEpoch());
                assertFalse(rolling.admissionsDrained(1L));
                assertFalse(rolling.requestDrainAfterAdmissions(1L),
                        "cannot stop G1 until the deferred transport request completes");
                assertEquals("G2", client.send(request(port),
                        HttpResponse.BodyHandlers.ofString()).body());
                assertFalse(previous.isDone(), "G1 connection must still be pending");
                assertEquals(pid, ProcessHandle.current().pid());
                assertEquals(port, server.port(), "listener must not rebind");
                assertThrows(IllegalStateException.class, () -> server.accept(),
                        "rolling dispatcher owns the accept lane");

                g1Code.actor.get().send("RELEASE_G1");
                HttpResponse<String> first = previous.get(8, TimeUnit.SECONDS);
                assertEquals(200, first.statusCode());
                assertEquals("G1", first.body());

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!rolling.admissionsDrained(1L) && System.nanoTime() < deadline)
                    Thread.sleep(2);
                assertTrue(rolling.admissionsDrained(1L));
                assertTrue(rolling.requestDrainAfterAdmissions(1L),
                        "only completed G1 transport exchanges may start actor drain");
                g1Code.actor.get().getDone().get(8, TimeUnit.SECONDS);
                assertEquals(2, rolling.snapshot().liveGenerations(),
                        "terminal G1 actors must not automatically imply physical reclamation");
                assertThrows(IllegalStateException.class,
                        () -> rolling.acknowledgePhysicallyRetired(2L),
                        "current serving generation must never be retired");

                assertEquals("G2", client.send(request(port),
                        HttpResponse.BodyHandlers.ofString()).body());
                assertNull(rolling.failure(), "listener must survive successful reload");
            } finally {
                if (previous != null && !previous.isDone() && g1Code.actor.get() != null) {
                    try { g1Code.actor.get().send("RELEASE_G1"); }
                    catch (RuntimeException ignored) { /* old request already cancelled */ }
                }
            }
        }
    }

    @Test
    void rejectsForeignAndUnwarmedHandlersWithoutAffectingBoundListener()
            throws Exception {
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime actors = new ActorRuntime(IsolatePolicy.developer());
             NativeHttp http = new NativeHttp(actors);
             HttpClient client = HttpClient.newHttpClient()) {
            var image = images.publish("rolling/unready.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var ref = new AtomicReference<>(http);
            var g1 = http.handler(new VersionCode(image, ref, "stable", false, false),
                    "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1);
            var server = http.listen("127.0.0.1", 0, 8, 1024, 8_000);
            assertThrows(IllegalArgumentException.class,
                    () -> new NativeHttpRollingDispatcher(server, 77L, g1, 3));
            g1.warmup().get(8, TimeUnit.SECONDS);
            try (var rolling = new NativeHttpRollingDispatcher(server, 77L, g1, 3)) {
                rolling.start();
                assertThrows(IllegalArgumentException.class,
                        () -> rolling.promoteAfterWarmup(1L, g1));
                var stale = http.handler(new VersionCode(image, ref, "never", false, false),
                        "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1);
                assertFalse(rolling.promoteAfterWarmup(0L, stale).get(8, TimeUnit.SECONDS),
                        "stale expected epochs must not replace G1");
                assertEquals(1L, rolling.snapshot().activeEpoch());
                assertEquals("stable", client.send(request(server.port()),
                        HttpResponse.BodyHandlers.ofString()).body());
            }
        }
    }
}
