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
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live sockets and five real FFM ActorRuntime workers per mode.
 * Each admitted actor turn executes the exact guest counter_response(int)
 * source also compiled into the LLVM real-HTTP native test.
 * JDK HttpServer remains trusted transport; this does not lower http.listen.
 */
final class GuestSourceHttpActorParityTest {
    private static final int ACTORS = 5, REQUESTS = 1000;
    private record Response(int status, String body) { }
    private static void answer(HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        x.getResponseHeaders().set("Content-Type", "text/plain");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
    private static Response get(HttpClient c, int port, String path) throws Exception {
        var rq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45)).GET().build();
        var result = c.send(rq, HttpResponse.BodyHandlers.ofString());
        return new Response(result.statusCode(), result.body());
    }
    @Test
    void samePublicOreslangHttpKernelExecutesWithinSharedAndPrivateActorTurns() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        String sourceText;
        try (var stream = getClass().getResourceAsStream("/parity/http-guest-counter-handler.ores")) {
            assertNotNull(stream, "same Oreslang HTTP kernel not present");
            sourceText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sourceText.contains("pub fnc counter_response(int count): int"));
        for (String mode : List.of("shared", "private")) execute(mode, sourceText);
    }
    private static void execute(String mode, String text) throws Exception {
        CountDownLatch initialized = new CountDownLatch(ACTORS);
        ConcurrentHashMap<Long, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        AtomicReference<Throwable> failedActor = new AtomicReference<>();
        Context[] contexts = new Context[ACTORS];
        ActorArena[] arenas = new ActorArena[ACTORS];
        long started = System.nanoTime();
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<Long>[] actors = new ActorRuntime.ActorRef[ACTORS];
            for (int a = 0; a < ACTORS; a++) {
                final int index = a;
                AtomicReference<ExplicitNativeI64Region.Slot> slot = new AtomicReference<>();
                AtomicReference<Value> code = new AtomicReference<>();
                var behavior = (java.util.function.Supplier<ActorRuntime.Behavior<Long>>) () -> (mail, context) -> {
                    try {
                        if (mail == 0L) {
                            arenas[index] = context.actorOwnedMemory().orElseThrow().newActorArena(2, 2);
                            slot.set(arenas[index].allocatePersistent(0));
                            contexts[index] = Context.newBuilder(OresLanguage.ID)
                                    .allowAllAccess(false).build();
                            code.set(contexts[index].parse(Source.newBuilder(
                                    OresLanguage.ID, text, "http-guest-counter-handler.ores").build()));
                            initialized.countDown();
                            return;
                        }
                        CompletableFuture<String> promise = pending.remove(mail);
                        if (promise == null) throw new IllegalStateException("unknown request correlation " + mail);
                        try (ActorArena.ScratchTurn scratch = arenas[index].beginTurn()) {
                            var temporary = scratch.allocate(mail);
                            if (scratch.read(temporary) != mail)
                                throw new IllegalStateException("actor scratch leak");
                            long count = arenas[index].readPersistent(slot.get()) + 1;
                            arenas[index].writePersistent(slot.get(), count);
                            long answer = code.get().execute(
                                    OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                                    "counter_response", count).asLong();
                            promise.complete(index + "," + count + "," + answer);
                        }
                    } catch (Throwable err) {
                        failedActor.compareAndSet(null, err);
                        var waiter = pending.remove(mail);
                        if (waiter != null) waiter.completeExceptionally(err);
                        initialized.countDown();
                        if (err instanceof Error fatal) throw fatal;
                        if (err instanceof RuntimeException runtimeError) throw runtimeError;
                        throw new IllegalStateException(err);
                    }
                };
                actors[a] = mode.equals("shared") ? runtime.spawnShared(behavior) :
                                                       runtime.spawnPrivate(behavior);
                actors[a].send(0L);
            }
            assertTrue(initialized.await(20, TimeUnit.SECONDS), "actor initialization");
            if (failedActor.get() != null) fail(failedActor.get());
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
            server.setExecutor(io);
            server.createContext("/", x -> {
                try (x) {
                    String path = x.getRequestURI().getPath();
                    if (!"GET".equals(x.getRequestMethod())) {
                        answer(x, 405, "method");
                    } else if (path.equals("/health")) {
                        answer(x, 200, "ok");
                    } else if (path.startsWith("/work/") && path.length() == 7
                               && path.charAt(6) >= '0' && path.charAt(6) <= '4') {
                        int owner = path.charAt(6) - '0';
                        long id = ids.incrementAndGet();
                        CompletableFuture<String> reply = new CompletableFuture<>();
                        pending.put(id, reply);
                        try {
                            actors[owner].send(id);
                            answer(x, 200, reply.get(40, TimeUnit.SECONDS));
                        } catch (Exception err) {
                            pending.remove(id);
                            answer(x, 503, "actor unavailable");
                        }
                    } else {
                        answer(x, 404, "missing");
                    }
                }
            });
            server.start();
            try {
                int port = server.getAddress().getPort();
                assertEquals(new Response(200, "ok"), get(client, port, "/health"));
                assertEquals(new Response(404, "missing"), get(client, port, "/not-found"));
                Semaphore concurrency = new Semaphore(20);
                List<CompletableFuture<Response>> requests = new ArrayList<>();
                for (int n = 0; n < REQUESTS; n++) {
                    final int owner = n % ACTORS;
                    concurrency.acquire();
                    requests.add(CompletableFuture.supplyAsync(() -> {
                        try { return get(client, port, "/work/" + owner); }
                        catch (Exception ex) { throw new RuntimeException(ex); }
                        finally { concurrency.release(); }
                    }, io));
                }
                long[][] counters = new long[ACTORS][REQUESTS / ACTORS];
                int[] offset = new int[ACTORS];
                for (int n = 0; n < REQUESTS; n++) {
                    Response r = requests.get(n).get(60, TimeUnit.SECONDS);
                    assertEquals(200, r.status(), mode + " request " + n + ": " + r.body());
                    String[] fields = r.body().split(",");
                    assertEquals(3, fields.length);
                    int a = Integer.parseInt(fields[0]);
                    long count = Long.parseLong(fields[1]);
                    long checksum = Long.parseLong(fields[2]);
                    assertEquals(n % ACTORS, a);
                    assertEquals(count * 43, checksum, "guest code must compute response");
                    counters[a][offset[a]++] = count;
                }
                for (int a = 0; a < ACTORS; a++) {
                    assertEquals(REQUESTS / ACTORS, offset[a]);
                    Arrays.sort(counters[a]);
                    for (int n = 0; n < REQUESTS / ACTORS; n++)
                        assertEquals(n + 1L, counters[a][n]);
                }
            } finally {
                server.stop(0);
            }
            assertTrue(pending.isEmpty());
            assertNull(failedActor.get(), () -> "actor failed " + failedActor.get());
        } finally {
            for (Context context : contexts) if (context != null) context.close();
        }
        for (ActorArena arena : arenas) {
            assertNotNull(arena);
            assertTrue(arena.physicallyClosed(), "FFM must be released after actor shutdown");
        }
        System.out.printf("GRAAL_REAL_GUEST_HTTP_PASS mode=%s actors=5 requests=1000 guest_calls=1000 physical_reclaim=true duration_ms=%.1f%n",
                mode, (System.nanoTime() - started) / 1_000_000.0);
    }
}
