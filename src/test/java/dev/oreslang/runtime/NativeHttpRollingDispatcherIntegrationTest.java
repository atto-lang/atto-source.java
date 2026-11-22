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
                .timeout(Duration.ofSeconds(30)).GET().build();
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
            var server = http.listen("127.0.0.1", 0, 16, 1024, 30_000);
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
                assertThrows(IllegalStateException.class,
                        () -> rolling.acknowledgePhysicallyRetired(1L),
                        "traffic drained is not worker termination, still less Task/FFI proof");
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
            assertThrows(SecurityException.class,
                    () -> http.listen("0.0.0.0", 0, 8, 1024, 8_000),
                    "package-private test fixture must not bypass non-loopback permissions");
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
                try (NativeHttp foreign = new NativeHttp(actors)) {
                    var foreignPool = foreign.handler(
                            new VersionCode(image, ref, "foreign", false, false),
                            "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1);
                    assertThrows(SecurityException.class,
                            () -> rolling.promoteAfterWarmup(1L, foreignPool),
                            "same actor runtime does not grant another HTTP context authority");
                }
                assertEquals("stable", client.send(request(server.port()),
                        HttpResponse.BodyHandlers.ofString()).body());
            }
        }
    }
    @Test
    void supervisorConfigTransactionValidatesBeforeStagingAndSurvivesFailedCandidate()
            throws Exception {
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime actors = new ActorRuntime(IsolatePolicy.developer());
             NativeHttp http = new NativeHttp(actors);
             HttpClient client = HttpClient.newHttpClient()) {
            var image = images.publish("rolling/transaction.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var ref = new AtomicReference<>(http);
            var g1 = http.handler(new VersionCode(image, ref, "G1", false, false),
                    "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1);
            g1.warmup().get(8, TimeUnit.SECONDS);
            var server = http.listen("127.0.0.1", 0, 16, 1024, 30_000);
            int port = server.port();
            long pid = ProcessHandle.current().pid();
            java.util.concurrent.atomic.AtomicInteger candidateCount =
                    new java.util.concurrent.atomic.AtomicInteger();
            var initial = new NativeHttpRollingReloadCoordinator.Config(
                    "proxy", 1, java.util.Map.of("version", "G1"));
            try (var rolling = new NativeHttpRollingDispatcher(server, 10001, g1, 3)) {
                rolling.start();
                try (var coordinator = new NativeHttpRollingReloadCoordinator(
                        rolling,
                        initial,
                        (old, proposed) -> {
                            if ("blocked".equals(proposed.settings().get("version")))
                                throw new IllegalArgumentException("invalid configuration");
                        },
                        proposed -> {
                            candidateCount.incrementAndGet();
                            boolean fail = "FAIL_READY".equals(
                                    proposed.settings().get("version"));
                            var code = new VersionCode(image, ref,
                                    proposed.settings().get("version"), false, fail);
                            return http.handler(code, "RollingWorker",
                                    ActorRuntime.ActorKind.SHARED).pooled(1);
                        })) {
                    assertEquals("G1", client.send(request(port),
                            HttpResponse.BodyHandlers.ofString()).body());
                    var invalid = new NativeHttpRollingReloadCoordinator.Config(
                            "proxy", 2, java.util.Map.of("version", "blocked"));
                    assertThrows(ExecutionException.class,
                            () -> coordinator.reload(1, invalid).get(8, TimeUnit.SECONDS));
                    assertEquals(0, candidateCount.get(),
                            "invalid input must never create candidate actors");

                    var failed = new NativeHttpRollingReloadCoordinator.Config(
                            "proxy", 2, java.util.Map.of("version", "FAIL_READY"));
                    assertThrows(ExecutionException.class,
                            () -> coordinator.reload(1, failed).get(8, TimeUnit.SECONDS));
                    assertEquals(1L, coordinator.published().epoch());
                    assertEquals("G1", client.send(request(port),
                            HttpResponse.BodyHandlers.ofString()).body());

                    var next = new NativeHttpRollingReloadCoordinator.Config(
                            "proxy", 2, java.util.Map.of("version", "G2"));
                    var promotion = coordinator.reload(1, next).get(8, TimeUnit.SECONDS);
                    assertEquals(NativeHttpRollingReloadCoordinator.Status.PUBLISHED,
                            promotion.status());
                    assertEquals(2L, coordinator.published().epoch());
                    assertEquals("G2", client.send(request(port),
                            HttpResponse.BodyHandlers.ofString()).body());

                    var stale = coordinator.reload(1,
                            new NativeHttpRollingReloadCoordinator.Config(
                                    "proxy", 3, java.util.Map.of("version", "STALE")))
                            .get(8, TimeUnit.SECONDS);
                    assertEquals(NativeHttpRollingReloadCoordinator.Status.STALE, stale.status());
                    assertEquals(2, candidateCount.get(),
                            "stale epoch should fail before candidate allocation");
                    // A stale request must refund its bounded staging permit.
                    // Ten retries exceed the eight-candidate admission cap.
                    for (int attempt = 0; attempt < 10; attempt++) {
                        assertEquals(NativeHttpRollingReloadCoordinator.Status.STALE,
                                coordinator.reload(1,
                                    new NativeHttpRollingReloadCoordinator.Config(
                                            "proxy", 3, java.util.Map.of("version", "STALE")))
                                    .get(8, TimeUnit.SECONDS).status());
                    }
                    assertThrows(ExecutionException.class,
                            () -> coordinator.reload(2,
                                    new NativeHttpRollingReloadCoordinator.Config(
                                            "different_service", 3,
                                            java.util.Map.of("version", "FORBIDDEN")))
                                    .get(8, TimeUnit.SECONDS));
                    assertEquals(2, candidateCount.get(),
                            "invalid service identity must not construct candidate pools");

                    var g3 = new NativeHttpRollingReloadCoordinator.Config(
                            "proxy", 3, java.util.Map.of("version", "G3"));
                    assertEquals(NativeHttpRollingReloadCoordinator.Status.PUBLISHED,
                            coordinator.reload(2, g3).get(8, TimeUnit.SECONDS).status());
                    assertEquals("G3", client.send(request(port),
                            HttpResponse.BodyHandlers.ofString()).body());
                    assertEquals(port, server.port());
                    assertEquals(pid, ProcessHandle.current().pid());
                    assertEquals(3L, coordinator.published().epoch());
                    assertEquals(3, rolling.snapshot().liveGenerations(),
                            "old generations remain retained pending physical proof");
                    assertThrows(IllegalStateException.class,
                            () -> rolling.acknowledgePhysicallyRetired(1L),
                            "unverified physical retirement must fail closed");
                    assertNull(rolling.failure());
                }
            }
        }
    }

    @Test
    void immutableConfigRejectsMalformedUntrustedInput() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("version", "one");
        var snapshot = new NativeHttpRollingReloadCoordinator.Config("proxy", 1, mutable);
        mutable.put("version", "two");
        assertEquals("one", snapshot.settings().get("version"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.settings().put("version", "three"));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeHttpRollingReloadCoordinator.Config(
                        "bad service", 1, java.util.Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeHttpRollingReloadCoordinator.Config(
                        "proxy", 0, java.util.Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeHttpRollingReloadCoordinator.Config(
                        "proxy", 1, java.util.Map.of("invalid key", "value")));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeHttpRollingReloadCoordinator.Config(
                        "proxy", 1, java.util.Map.of("key", "contains" + (char) 0 + "NUL")));
    }

    @Test
    void competingValidatedReloadsPublishExactlyOneSuccessor() throws Exception {
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime actors = new ActorRuntime(IsolatePolicy.developer());
             NativeHttp http = new NativeHttp(actors);
             HttpClient client = HttpClient.newHttpClient()) {
            var image = images.publish("rolling/concurrent.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var ref = new AtomicReference<>(http);
            var g1 = http.handler(new VersionCode(image, ref, "G1", false, false),
                    "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1);
            g1.warmup().get(8, TimeUnit.SECONDS);
            var server = http.listen("127.0.0.1", 0, 16, 1024, 30_000);
            try (var rolling = new NativeHttpRollingDispatcher(server, 10002L, g1, 3)) {
                rolling.start();
                var initial = new NativeHttpRollingReloadCoordinator.Config(
                        "proxy", 1, java.util.Map.of("version", "G1"));
                try (var coordinator = new NativeHttpRollingReloadCoordinator(
                        rolling, initial, (before, next) -> { },
                        next -> http.handler(new VersionCode(image, ref,
                                        next.settings().get("version"), false, false),
                                "RollingWorker", ActorRuntime.ActorKind.SHARED).pooled(1))) {
                    var first = coordinator.reload(1L,
                            new NativeHttpRollingReloadCoordinator.Config(
                                    "proxy", 2, java.util.Map.of("version", "G2A")));
                    var competing = coordinator.reload(1L,
                            new NativeHttpRollingReloadCoordinator.Config(
                                    "proxy", 3, java.util.Map.of("version", "G2B")));
                    var a = first.get(8, TimeUnit.SECONDS);
                    var b = competing.get(8, TimeUnit.SECONDS);
                    long successes = java.util.stream.Stream.of(a, b).filter(
                            value -> value.status() ==
                                    NativeHttpRollingReloadCoordinator.Status.PUBLISHED).count();
                    assertEquals(1, successes, "exactly one epoch compare-and-swap must win");
                    assertEquals(2L, rolling.snapshot().activeEpoch());
                    assertEquals(2L, coordinator.published().epoch());
                    var winner = a.status() ==
                            NativeHttpRollingReloadCoordinator.Status.PUBLISHED ? a : b;
                    assertEquals(winner.config().settings().get("version"),
                            client.send(request(server.port()),
                                    HttpResponse.BodyHandlers.ofString()).body());
                    assertNull(rolling.failure());
                }
            }
        }
    }

}
