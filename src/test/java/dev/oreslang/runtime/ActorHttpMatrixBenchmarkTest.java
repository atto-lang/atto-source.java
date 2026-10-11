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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real loopback HTTP 2x2 matrix: 5 SHARED vs 5 PRIVATE actors on GraalVM.
 * Transport and load generator are Java hosts. Guest fields live in actor FFM.
 * JVM GC data includes the host stack; do not attribute it solely to Oreslang.
 */
final class ActorHttpMatrixBenchmarkTest {
    private static final int ACTORS = 5;
    private static final int REQUESTS = 1000;
    private static final int ROUNDS = 4;
    private static final int WARMUP_PER_ACTOR = 25;
    private static final int[] CONCURRENCY = {1, 10, 100, 1000};
    private static final int BODY_LIMIT = 200;
    private record Reply(int status, String body, long nanos) { }
    private record ActorValue(long count, long checksum) { }
    private record Gc(long count, long millis) { }
    private static Gc gc() {
        long count = 0, millis = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.getCollectionCount() >= 0) count += b.getCollectionCount();
            if (b.getCollectionTime() >= 0) millis += b.getCollectionTime();
        }
        return new Gc(count, millis);
    }
    private static long rssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) { }
        return -1;
    }
    private static void response(HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.US_ASCII);
        x.getResponseHeaders().set("Content-Type", "text/plain");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("Connection", "close");
        x.sendResponseHeaders(status, payload.length);
        x.getResponseBody().write(payload);
    }
    private static Reply request(HttpClient client, int port, int actor) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/work/" + actor))
                .timeout(Duration.ofSeconds(45))
                .GET().build();
        long begin = System.nanoTime();
        var got = client.send(r, HttpResponse.BodyHandlers.ofString());
        return new Reply(got.statusCode(), got.body(), System.nanoTime() - begin);
    }
    private static double millis(long n) { return n / 1_000_000.0; }
    private static String metric(double d) { return String.format(Locale.ROOT, "%.3f", d); }
    private static double quantile(long[] values, double q) {
        if (values.length == 0) throw new IllegalArgumentException("no samples");
        return millis(values[Math.min(values.length - 1, (int) Math.ceil(q * values.length) - 1)]);
    }
    private static void executeMode(String mode, int round, org.graalvm.polyglot.Source guestSource) throws Exception {
        AtomicReference<Throwable> actorFailure = new AtomicReference<>();
        ConcurrentHashMap<Long, CompletableFuture<ActorValue>> pending = new ConcurrentHashMap<>();
        AtomicLong ticket = new AtomicLong();
        List<ActorArena> owned = new java.util.concurrent.CopyOnWriteArrayList<>();
        long started = System.nanoTime();
        long teardownStarted = started;
        // Only host/supervisor may prepare polyglot Contexts. Each worker owns
        // one parsed code target and executes it on its own admitted actor turn.
        org.graalvm.polyglot.Context[] contexts = new org.graalvm.polyglot.Context[ACTORS];
        org.graalvm.polyglot.Value[] functions = new org.graalvm.polyglot.Value[ACTORS];
        try {
            for (int i = 0; i < ACTORS; i++) {
                contexts[i] = org.graalvm.polyglot.Context.newBuilder(dev.oreslang.OresLanguage.ID)
                        .allowAllAccess(false).build();
                functions[i] = contexts[i].parse(guestSource);
                assertTrue(functions[i].canExecute());
            }
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
             HttpClient client = HttpClient.newBuilder()
                 .connectTimeout(Duration.ofSeconds(10)).build()) {
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<Long>[] actors = new ActorRuntime.ActorRef[ACTORS];
            CountDownLatch initialized = new CountDownLatch(ACTORS);
            for (int index = 0; index < ACTORS; index++) {
                var arena = new AtomicReference<ActorArena>();
                var slot = new AtomicReference<ExplicitNativeI64Region.Slot>();
                var factory = (java.util.function.Supplier<ActorRuntime.Behavior<Long>>) () -> (mail, ctx) -> {
                    try {
                        if (mail == 0L) {
                            ActorArena nativeArena = ctx.actorOwnedMemory().orElseThrow().newActorArena(2, 2);
                            slot.set(nativeArena.allocatePersistent(0));
                            arena.set(nativeArena);
                            owned.add(nativeArena);
                            initialized.countDown();
                            return;
                        }
                        CompletableFuture<ActorValue> promise = pending.remove(mail);
                        if (promise == null) throw new IllegalStateException("missing HTTP completion");
                        ActorArena nativeArena = arena.get();
                        try (var scope = nativeArena.beginTurn()) {
                            var ephemeral = scope.allocate(mail);
                            if (scope.read(ephemeral) != mail) throw new IllegalStateException("scratch corrupted");
                            long next = nativeArena.readPersistent(slot.get()) + 1;
                            nativeArena.writePersistent(slot.get(), next);
                            long computed = functions[index].execute(
                                    dev.oreslang.nodes.OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                                    "handle_request", next).asLong();
                            promise.complete(new ActorValue(next, computed));
                        }
                    } catch (Throwable failure) {
                        actorFailure.compareAndSet(null, failure);
                        var completion = pending.remove(mail);
                        if (completion != null) completion.completeExceptionally(failure);
                        initialized.countDown();
                        if (failure instanceof Error err) throw err;
                        if (failure instanceof RuntimeException err) throw err;
                        throw new IllegalStateException(failure);
                    }
                };
                actors[index] = mode.equals("shared") ?
                        runtime.spawnShared(factory) : runtime.spawnPrivate(factory);
                actors[index].send(0L);
            }
            assertTrue(initialized.await(20, TimeUnit.SECONDS), "all five actors initialized");
            if (actorFailure.get() != null) fail(actorFailure.get());
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 2048);
            http.setExecutor(io);
            http.createContext("/", x -> {
                try (x) {
                    if (!"GET".equals(x.getRequestMethod())) { response(x, 405, "method"); return; }
                    String path = x.getRequestURI().getPath();
                    if (path.equals("/health")) { response(x, 200, "ok"); return; }
                    if (!path.startsWith("/work/") || path.length() != 7
                            || path.charAt(6) < '0' || path.charAt(6) > '4') {
                        response(x, 404, "missing"); return;
                    }
                    int actorIndex = path.charAt(6) - '0';
                    long id = ticket.incrementAndGet();
                    CompletableFuture<ActorValue> done = new CompletableFuture<>();
                    pending.put(id, done);
                    try {
                        actors[actorIndex].send(id);
                        ActorValue value = done.get(40, TimeUnit.SECONDS);
                        response(x, 200, actorIndex + "," + value.count() + "," + value.checksum());
                    } catch (Exception failure) {
                        pending.remove(id);
                        response(x, 503, "backend unavailable");
                    }
                }
            });
            http.start();
            try {
                int port = http.getAddress().getPort();
                assertEquals(200, client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
                // Warm up actual HTTP connections, scheduler and FFM before
                // starting any timed measurements in this mode.
                for (int n = 0; n < ACTORS * WARMUP_PER_ACTOR; ++n) {
                    Reply warm = request(client, port, n % ACTORS);
                    assertEquals(200, warm.status(), "warmup request failed");
                }
                for (int c : CONCURRENCY) {
                    long[] latencies = new long[REQUESTS];
                    long[][] counts = new long[ACTORS][REQUESTS / ACTORS];
                    int[] perActor = new int[ACTORS];
                    Semaphore limit = new Semaphore(c);
                    List<CompletableFuture<Reply>> futures = new ArrayList<>(REQUESTS);
                    Gc gcBefore = gc();
                    long rssBefore = rssKb();
                    long begin = System.nanoTime();
                    for (int n = 0; n < REQUESTS; n++) {
                        final int target = n % ACTORS;
                        limit.acquire();
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            try { return request(client, port, target); }
                            catch (Exception ex) { throw new RuntimeException(ex); }
                            finally { limit.release(); }
                        }, io));
                    }
                    for (int i = 0; i < REQUESTS; i++) {
                        Reply reply = futures.get(i).get(50, TimeUnit.SECONDS);
                        assertEquals(200, reply.status(), "response " + i + ": " + reply.body());
                        String[] pieces = reply.body().split(",");
                        assertEquals(3, pieces.length);
                        int owner = Integer.parseInt(pieces[0]);
                        long count = Long.parseLong(pieces[1]);
                        assertEquals(43 * count, Long.parseLong(pieces[2]));
                        assertEquals(i % ACTORS, owner);
                        counts[owner][perActor[owner]++] = count;
                        latencies[i] = reply.nanos();
                    }
                    double seconds = (System.nanoTime() - begin) / 1_000_000_000.0;
                    Gc gcAfter = gc();
                    long rssAfter = rssKb();
                    for (int a = 0; a < ACTORS; ++a) {
                        assertEquals(REQUESTS / ACTORS, perActor[a]);
                        Arrays.sort(counts[a]);
                        long offset = WARMUP_PER_ACTOR + (long) (Arrays.stream(CONCURRENCY)
                                .takeWhile(n -> n != c).count()) * (REQUESTS / ACTORS);
                        for (int j = 0; j < REQUESTS / ACTORS; j++) {
                            assertEquals(offset + j + 1, counts[a][j],
                                    "actor " + a + " lost or duplicated request");
                        }
                    }
                    Arrays.sort(latencies);
                    assertTrue(pending.isEmpty(), "orphan requests");
                    assertNull(actorFailure.get(), () -> "actor failed " + actorFailure.get());
                    System.out.printf(Locale.ROOT,
                            "MATRIX backend=graal round=%d mode=%s isolation=%s guest_handler=oreslang actors=5 warmup_per_actor=%d requests=%d concurrency=%d " +
                            "p50_ms=%s p99_ms=%s rps=%s gc_count=%d gc_pause_ms=%d rss_delta_kb=%d errors=0%n",
                            round, mode, mode.equals("private") ? "actor-private-logical-memory" : "actor-shared-kind",
                            WARMUP_PER_ACTOR, REQUESTS, c, metric(quantile(latencies, .5)),
                            metric(quantile(latencies, .99)), metric(REQUESTS / seconds),
                            gcAfter.count - gcBefore.count, gcAfter.millis - gcBefore.millis,
                            (rssBefore < 0 || rssAfter < 0) ? -1 : rssAfter - rssBefore);
                }
            } finally {
                http.stop(0);
            }
            assertTrue(pending.isEmpty());
            teardownStarted = System.nanoTime();
            System.out.printf("MATRIX backend=graal round=%d mode=%s shutdown_begin=true%n", round, mode);
        }
        } finally {
            for (var context : contexts) if (context != null) context.close();
        }
        for (ActorArena arena : owned) assertTrue(arena.physicallyClosed());
        assertEquals(ACTORS, owned.size());
        long finished = System.nanoTime();
        System.out.printf(Locale.ROOT, "MATRIX backend=graal round=%d mode=%s shutdown_ms=%.3f mode_total_ms=%.3f physical_reclaim=true%n",
                          round, mode, millis(finished - teardownStarted), millis(finished - started));
    }

    @Test void fourConcurrencyLevelsAcrossFiveSharedAndFivePrivateActors() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        byte[] sourceBytes;
        try (var input = getClass().getResourceAsStream("/parity/http-guest-handler.ores")) {
            assertNotNull(input);
            sourceBytes = input.readAllBytes();
        }
        String text = new String(sourceBytes, java.nio.charset.StandardCharsets.UTF_8);
        var guestSource = org.graalvm.polyglot.Source.newBuilder(
                dev.oreslang.OresLanguage.ID, text, "http-guest-handler.ores")
                .mimeType(dev.oreslang.OresLanguage.MIME_TYPE).build();
        for (int round = 0; round < ROUNDS; round++) {
            // Balanced ordering AB/BA/BA/AB; independent fresh actor runtimes,
            // identical request workload and deterministic five-way routing.
            if (round == 0 || round == 3) {
                executeMode("shared", round, guestSource);
                executeMode("private", round, guestSource);
            } else {
                executeMode("private", round, guestSource);
                executeMode("shared", round, guestSource);
            }
        }
    }
}
