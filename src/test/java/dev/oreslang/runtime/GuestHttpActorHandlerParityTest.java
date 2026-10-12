package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real HTTP TCP listener with 5 ActorRuntime workers (SHARED or PRIVATE).
 * The application response formula is computed by the exact guest Oreslang
 * source compiled by LLVM in its separate native HTTP integration test.
 * Listener/transport remain host code; no source-level http.listen claim.
 */
final class GuestHttpActorHandlerParityTest {
    private static final int ACTORS = 5;
    private static final int REQUESTS = 250;
    private record Answer(int actor, long count, long value) { }
    private static void respond(HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        x.getResponseHeaders().set("Content-Type", "text/plain");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
    private static Answer call(HttpClient http, int port, int actor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/work/" + actor))
                .timeout(Duration.ofSeconds(20)).GET().build();
        var result = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode(), result.body());
        String[] fields = result.body().split(",");
        assertEquals(3, fields.length);
        return new Answer(Integer.parseInt(fields[0]),
                Long.parseLong(fields[1]), Long.parseLong(fields[2]));
    }
    private static void run(String kind, Source guestSource) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReferenceArray<ActorArena> arenas = new AtomicReferenceArray<>(ACTORS);
        AtomicReferenceArray<ExplicitNativeI64Region.Slot> counters = new AtomicReferenceArray<>(ACTORS);
        AtomicReferenceArray<Context> contexts = new AtomicReferenceArray<>(ACTORS);
        AtomicReferenceArray<Value> handlers = new AtomicReferenceArray<>(ACTORS);
        CountDownLatch ready = new CountDownLatch(ACTORS);
        ConcurrentHashMap<Long, CompletableFuture<Answer>> pending = new ConcurrentHashMap<>();
        AtomicLong tickets = new AtomicLong();
        try {
            // Host creates and parses polyglot Contexts BEFORE actor turns.
            // An actor must not install host/supervisor-owned exit hooks.
            for (int i = 0; i < ACTORS; i++) {
                Context guest = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false).build();
                contexts.set(i, guest);
                Value handler = guest.parse(guestSource);
                assertTrue(handler.canExecute());
                handlers.set(i, handler);
            }
            try (ActorRuntime group = new ActorRuntime();
                 ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
                 HttpClient http = HttpClient.newBuilder()
                         .connectTimeout(Duration.ofSeconds(5)).build()) {
                @SuppressWarnings("unchecked")
                ActorRuntime.ActorRef<Long>[] refs = new ActorRuntime.ActorRef[ACTORS];
                for (int i = 0; i < ACTORS; i++) {
                    final int actorIndex = i;
                    java.util.function.Supplier<ActorRuntime.Behavior<Long>> factory =
                        () -> (message, turn) -> {
                            try {
                                if (message == 0L) {
                                    ActorArena memory = turn.actorOwnedMemory()
                                            .orElseThrow().newActorArena(2, 2);
                                    arenas.set(actorIndex, memory);
                                    counters.set(actorIndex, memory.allocatePersistent(0));
                                    // Guest code is invoked only on this actor's
                                    // exclusive admitted lane, with a host-prepared Context.
                                    assertNotNull(handlers.get(actorIndex));
                                    ready.countDown();
                                    return;
                                }
                                CompletableFuture<Answer> promise = pending.remove(message);
                                if (promise == null) throw new IllegalStateException("unknown actor request");
                                ActorArena memory = arenas.get(actorIndex);
                                try (ActorArena.ScratchTurn scratch = memory.beginTurn()) {
                                    var scratchId = scratch.allocate(message);
                                    assertEquals(message.longValue(), scratch.read(scratchId));
                                    long next = memory.readPersistent(counters.get(actorIndex)) + 1L;
                                    memory.writePersistent(counters.get(actorIndex), next);
                                    long value = handlers.get(actorIndex).execute(
                                            OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                                            "handle_request", next).asLong();
                                    promise.complete(new Answer(actorIndex, next, value));
                                }
                            } catch (Throwable ex) {
                                failure.compareAndSet(null, ex);
                                var promise = pending.remove(message);
                                if (promise != null) promise.completeExceptionally(ex);
                                ready.countDown();
                                if (ex instanceof Error fatal) throw fatal;
                                if (ex instanceof RuntimeException runtime) throw runtime;
                                throw new IllegalStateException(ex);
                            }
                        };
                    refs[i] = kind.equals("shared") ?
                            group.spawnShared(factory) : group.spawnPrivate(factory);
                    refs[i].send(0L);
                }
                assertTrue(ready.await(20, TimeUnit.SECONDS), "all guest actors initialized");
                assertNull(failure.get(), () -> "guest setup failed: " + failure.get());
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
                server.setExecutor(io);
                server.createContext("/", x -> {
                    try (x) {
                        String path = x.getRequestURI().getRawPath();
                        if (!x.getRequestMethod().equals("GET")) {
                            respond(x, 405, "method");
                            return;
                        }
                        if (path.equals("/health")) {
                            respond(x, 200, "ok");
                            return;
                        }
                        if (path.length() != 7 || !path.startsWith("/work/")
                                || path.charAt(6) < '0' || path.charAt(6) > '4') {
                            respond(x, 404, "missing");
                            return;
                        }
                        int actorIndex = path.charAt(6) - '0';
                        long id = tickets.incrementAndGet();
                        CompletableFuture<Answer> future = new CompletableFuture<>();
                        pending.put(id, future);
                        try {
                            refs[actorIndex].send(id);
                            Answer reply = future.get(20, TimeUnit.SECONDS);
                            respond(x, 200, reply.actor() + "," +
                                    reply.count() + "," + reply.value());
                        } catch (Exception ex) {
                            pending.remove(id);
                            respond(x, 503, "actor unavailable");
                        }
                    }
                });
                server.start();
                try {
                    int port = server.getAddress().getPort();
                    Semaphore concurrency = new Semaphore(12);
                    List<CompletableFuture<Answer>> replies = new ArrayList<>();
                    for (int i = 0; i < REQUESTS; i++) {
                        int target = i % ACTORS;
                        concurrency.acquire();
                        replies.add(CompletableFuture.supplyAsync(() -> {
                            try { return call(http, port, target); }
                            catch (Exception ex) { throw new RuntimeException(ex); }
                            finally { concurrency.release(); }
                        }, io));
                    }
                    long[][] observed = new long[ACTORS][REQUESTS / ACTORS];
                    int[] indexes = new int[ACTORS];
                    for (int i = 0; i < REQUESTS; i++) {
                        Answer answer = replies.get(i).get(30, TimeUnit.SECONDS);
                        assertEquals(i % ACTORS, answer.actor());
                        assertEquals(answer.count() * 43, answer.value());
                        observed[answer.actor()][indexes[answer.actor()]++] = answer.count();
                    }
                    for (int i = 0; i < ACTORS; i++) {
                        assertEquals(REQUESTS / ACTORS, indexes[i]);
                        Arrays.sort(observed[i]);
                        for (int n = 0; n < REQUESTS / ACTORS; n++)
                            assertEquals(n + 1L, observed[i][n]);
                    }
                    assertTrue(pending.isEmpty());
                    assertNull(failure.get(), () -> "guest handler exception " + failure.get());
                } finally {
                    server.stop(0);
                }
            }
        } finally {
            for (int i = 0; i < ACTORS; i++) {
                Context context = contexts.get(i);
                if (context != null) context.close();
            }
        }
        for (int i = 0; i < ACTORS; i++) {
            assertNotNull(arenas.get(i));
            assertTrue(arenas.get(i).physicallyClosed(), "native actor state must be reclaimed");
        }
        System.out.printf("GRAAL_GUEST_HTTP_ACTOR_PASS mode=%s actors=5 requests=%d " +
                "concurrent_clients=12 handler=Oreslang guest FFM=true errors=0%n", kind, REQUESTS);
    }
    @Test
    void liveHttpRequestsExecuteIdenticalGuestOreslangHandlerInSharedAndPrivateActors() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/parity/http-guest-handler.ores")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        String source = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(source.contains("pub fnc handle_request(int count): int"));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "http-guest-handler.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        run("shared", guest);
        run("private", guest);
    }
}
