package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real HTTP/1.1 TCP sockets over JDK HttpServer, with state mutation
 * exclusively inside an Oreslang ActorRuntime mailbox and native FFM arena.
 * Not a test of source-language NativeHttp or native-image executable.
 */
final class ActorArenaHttpServerIntegrationTest {
    private record Result(int status, String body) { }

    private static void respond(HttpExchange x, int status, String payload) throws java.io.IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        x.getResponseHeaders().set("Content-Type", "text/plain");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static Result request(HttpClient client, int port, String method, String path) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> rs = client.send(rq, HttpResponse.BodyHandlers.ofString());
        return new Result(rs.statusCode(), rs.body());
    }

    private static long gcCount() {
        long count = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long observed = b.getCollectionCount();
            if (observed >= 0) count += observed;
        }
        return count;
    }

    @Test
    void realConcurrentHttpRequestsUseSerialActorAndPersistentFfmMemory() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        final int total = 96;
        AtomicReference<ActorArena> ownedArena = new AtomicReference<>();
        AtomicReference<Throwable> actorError = new AtomicReference<>();
        CountDownLatch initialized = new CountDownLatch(1);
        ConcurrentHashMap<Long, CompletableFuture<Long>> pending = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        AtomicBoolean sawShutdown = new AtomicBoolean();
        long start = System.nanoTime();
        long beforeGc = gcCount();

        try (ActorRuntime actors = new ActorRuntime();
             ExecutorService io = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var counter = new AtomicReference<ExplicitNativeI64Region.Slot>();
            var worker = actors.<Long>spawnShared(() -> (mail, context) -> {
                try {
                    if (mail == 0) {
                        ActorArena arena = context.actorOwnedMemory().orElseThrow().newActorArena(2, 2);
                        counter.set(arena.allocatePersistent(0));
                        ownedArena.set(arena);
                        initialized.countDown();
                        return;
                    }
                    CompletableFuture<Long> promise = pending.remove(mail);
                    if (promise == null) throw new IllegalStateException("missing HTTP request correlation");
                    ActorArena arena = ownedArena.get();
                    try (ActorArena.ScratchTurn turn = arena.beginTurn()) {
                        var temp = turn.allocate(mail);
                        if (turn.read(temp) != mail) {
                            throw new IllegalStateException("scratch request ID corrupted");
                        }
                        long next = arena.readPersistent(counter.get()) + 1;
                        arena.writePersistent(counter.get(), next);
                        promise.complete(next);
                    }
                } catch (Throwable failed) {
                    actorError.compareAndSet(null, failed);
                    CompletableFuture<Long> completion = pending.remove(mail);
                    if (completion != null) completion.completeExceptionally(failed);
                    initialized.countDown();
                    if (failed instanceof Error fatal) throw fatal;
                    if (failed instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                    throw new IllegalStateException(failed);
                }
            });
            worker.send(0L);
            assertTrue(initialized.await(10, TimeUnit.SECONDS));
            if (actorError.get() != null) fail(actorError.get());

            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            http.setExecutor(io);
            http.createContext("/", exchange -> {
                try (exchange) {
                    String method = exchange.getRequestMethod();
                    String path = exchange.getRequestURI().getRawPath();
                    if (!method.equals("GET")) {
                        respond(exchange, 405, "method not allowed");
                    } else if (path.equals("/health")) {
                        respond(exchange, 200, "42");
                    } else if (path.equals("/shutdown")) {
                        sawShutdown.set(true);
                        respond(exchange, 200, "bye");
                    } else if (path.equals("/counter")) {
                        long id = ids.incrementAndGet();
                        CompletableFuture<Long> finished = new CompletableFuture<>();
                        pending.put(id, finished);
                        try {
                            worker.send(id);
                            long value = finished.get(10, TimeUnit.SECONDS);
                            respond(exchange, 200, value + "," + (value * 43));
                        } catch (Exception failed) {
                            pending.remove(id);
                            respond(exchange, 500, "actor failure");
                        }
                    } else {
                        respond(exchange, 404, "not found");
                    }
                }
            });
            http.start();
            try {
                int port = http.getAddress().getPort();
                assertEquals(new Result(200, "42"), request(client, port, "GET", "/health"));
                assertEquals(new Result(404, "not found"), request(client, port, "GET", "/missing"));
                assertEquals(new Result(405, "method not allowed"), request(client, port, "POST", "/counter"));

                Semaphore clientConcurrency = new Semaphore(12);
                List<CompletableFuture<Result>> calls = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    clientConcurrency.acquire();
                    calls.add(CompletableFuture.supplyAsync(() -> {
                        try { return request(client, port, "GET", "/counter"); }
                        catch (Exception e) { throw new RuntimeException(e); }
                        finally { clientConcurrency.release(); }
                    }, io));
                }
                List<Long> values = new ArrayList<>();
                for (CompletableFuture<Result> call : calls) {
                    Result result = call.get(20, TimeUnit.SECONDS);
                    assertEquals(200, result.status, result.toString());
                    String[] parts = result.body.split(",");
                    assertEquals(2, parts.length);
                    long current = Long.parseLong(parts[0]);
                    assertEquals(current * 43, Long.parseLong(parts[1]));
                    values.add(current);
                }
                Collections.sort(values);
                for (int i = 0; i < total; ++i) assertEquals(i + 1L, values.get(i).longValue());
                assertEquals(new Result(200, "42"), request(client, port, "GET", "/health"));
                assertEquals(new Result(200, "bye"), request(client, port, "GET", "/shutdown"));
                assertTrue(sawShutdown.get());
            } finally {
                http.stop(0);
            }
            assertTrue(pending.isEmpty(), "all accepted HTTP requests must finish their actor reply");
            assertNull(actorError.get(), () -> "actor failed: " + actorError.get());
        }
        assertNotNull(ownedArena.get());
        assertTrue(ownedArena.get().physicallyClosed(),
                "FFM memory must be reclaimed after actor runtime shutdown");
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        System.out.printf("GRAAL_HTTP_LOOPBACK_PASS requests=101 counter=96 concurrent_clients=12 seconds=%.3f gc_collections_delta=%d actor=ActorRuntime storage=FFM%n",
                seconds, gcCount() - beforeGc);
    }
}
