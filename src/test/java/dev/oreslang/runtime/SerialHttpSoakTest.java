package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual HTTP sockets + source-compiled persistent serial actor under bursts.
 * Defaults to a bounded CI smoke load; the funded soak workflow may set
 * ORES_HTTP_SOAK_REQUESTS=50000 and ORES_HTTP_SOAK_CLIENTS=1000.
 */
final class SerialHttpSoakTest {
    private record Results(int success, int rejected) {}

    @Test void repeatedConcurrentHttpWavesPreserveMonotonicSerialStateAndReleaseBudgets()
            throws Exception {
        int total = boundedEnv("ORES_HTTP_SOAK_REQUESTS", 256, 1, 100_000);
        int clients = boundedEnv("ORES_HTTP_SOAK_CLIENTS", 64, 1, 1_000);
        int capacity = Math.max(64, Math.min(2_048, clients * 2));
        boolean heapProfile = Boolean.parseBoolean(
                System.getenv().getOrDefault("ORES_HTTP_SOAK_HEAP_PROFILE", "false"));
        List<Long> retainedHeapSamples = new ArrayList<>();
        String guest = """
            define serial actor Counter as
              state { count: int = 0; }
              pub increment(): int with state.read, state.write {
                return self.handle_state(critical |s| -> {
                  s.count = s.count + 1;
                  return s.count;
                });
              }
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                stdio.println(self.increment());
                await request.respond(200, "text/plain", "ok");
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, %d, 1024, 30000);
              stdio.println(server.port);
              val handler = http.handler("Counter");
              for (let i = 0; i < %d; i = i + 1) {
                val incoming = await server.accept();
                if (incoming.target == "/__stop") {
                  await incoming.respond(200, "text/plain", "stopped");
                  break;
                } fi
                handler.dispatch(rt borrow mut incoming);
              }
              await server.shutdown(0);
              stdio.println("SOAK_METRICS");
              stdio.println(server.active);
              stdio.println(server.transfer_backlog);
              stdio.println(server.tombstones);
              stdio.println(server.transfer_backlog_limit);
              return;
            }
            """.formatted(capacity, total + 1);

        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                if (!port.isDone()) {
                    String seen = toString(StandardCharsets.UTF_8);
                    int newline = seen.indexOf('\n');
                    if (newline >= 0) port.complete(Integer.parseInt(seen.substring(0, newline).trim()));
                }
            }
        };
        Results stats;
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Results> load = executor.submit(() -> {
                int success = 0, rejected = 0;
                try (HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10)).build()) {
                    int serverPort = port.get(15, TimeUnit.SECONDS);
                    URI root = URI.create("http://127.0.0.1:" + serverPort);
                    for (int sent = 0; sent < total; sent += clients) {
                        int wave = Math.min(clients, total - sent);
                        List<CompletableFuture<HttpResponse<String>>> requests =
                                new ArrayList<>(wave);
                        for (int i = 0; i < wave; i++) {
                            HttpRequest req = HttpRequest.newBuilder(
                                    root.resolve("/work-" + (sent + i)))
                                    .timeout(Duration.ofSeconds(35)).GET().build();
                            requests.add(client.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
                        }
                        CompletableFuture.allOf(requests.toArray(new CompletableFuture[0]))
                                .get(45, TimeUnit.SECONDS);
                        for (var pending : requests) {
                            HttpResponse<String> response = pending.join();
                            if (response.statusCode() == 200) {
                                assertEquals("ok", response.body());
                                success++;
                            } else if (response.statusCode() == 503) {
                                rejected++;
                            } else {
                                fail("Unexpected HTTP status under bounded soak: "
                                        + response.statusCode());
                            }
                        }
                        if (heapProfile && ((long) (sent + wave) * 4L / total)
                                > retainedHeapSamples.size()) {
                            // Take best-effort post-GC samples only at wave
                            // boundaries, where all clients have completed and
                            // the serial actor has no outstanding HTTP work.
                            System.gc();
                            Thread.sleep(35);
                            retainedHeapSamples.add(java.lang.management.ManagementFactory
                                    .getMemoryMXBean().getHeapMemoryUsage().getUsed());
                        }
                    }
                    // All workload responses have resolved; the stop request is
                    // admitted only afterward, so the supervisor can drain.
                    for (int attempt = 0; attempt < 8; attempt++) {
                        HttpRequest stop = HttpRequest.newBuilder(root.resolve("/__stop"))
                                .timeout(Duration.ofSeconds(10)).GET().build();
                        HttpResponse<String> last = client.send(stop,
                                HttpResponse.BodyHandlers.ofString());
                        if (last.statusCode() == 200) {
                            assertEquals("stopped", last.body());
                            return new Results(success, rejected);
                        }
                        assertEquals(503, last.statusCode(), "stop retry is overload-only");
                    }
                    throw new AssertionError("could not admit stop request after 8 attempts");
                }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, guest, "serial-http-soak.ores").build());
                stats = load.get(Math.max(120, total / 250) + 90L, TimeUnit.SECONDS);
            } finally {
                load.cancel(true);
            }
        }
        List<String> lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        int marker = lines.indexOf("SOAK_METRICS");
        assertTrue(marker >= 1, () -> "missing end-of-drain metrics: " + lines.size());
        assertEquals(5, lines.size() - marker, "metrics comprise four numeric lines");
        assertEquals("0", lines.get(marker + 1), "active transports drained");
        assertEquals("0", lines.get(marker + 2), "unclaimed transfer tickets drained");
        assertEquals("0", lines.get(marker + 3), "expired tombstones reclaimed");
        assertEquals(Integer.toString(Math.min(capacity, 1_024)), lines.get(marker + 4));
        assertEquals(total, stats.success() + stats.rejected(),
                "every generated request must receive a response");
        if (heapProfile) {
            System.out.println("HTTP_SOAK_POST_GC_HEAP_SAMPLES_BYTES="
                    + retainedHeapSamples);
            assertTrue(retainedHeapSamples.size() >= 4,
                    "50k stress must sample four distinct settled wave boundaries");
            // Discard the first warm-up sample to avoid JVM class-loading and
            // JIT cache growth. This is a regression alarm, not a heap proof.
            long warmed = retainedHeapSamples.get(1);
            long last = retainedHeapSamples.get(retainedHeapSamples.size() - 1);
            long maxDrift = Math.max(64L * 1024L * 1024L, warmed / 2);
            assertTrue(last <= warmed + maxDrift,
                    "retained heap grew by more than the bounded post-warm-up "
                    + "alarm threshold; samples=" + retainedHeapSamples);
        }
        assertEquals(stats.success(), marker - 1,
                "each successful response must correspond to one actor invocation");
        Set<Integer> seen = new HashSet<>();
        for (int i = 1; i < marker; i++) {
            int state = Integer.parseInt(lines.get(i));
            assertTrue(seen.add(state), "duplicate actor state transition: " + state);
        }
        for (int i = 1; i <= stats.success(); i++) {
            assertTrue(seen.contains(i), "missing actor state transition: " + i);
        }
    }

    private static int boundedEnv(String name, int defaultValue, int min, int max) {
        String specified = System.getenv(name);
        if (specified == null || specified.isBlank()) return defaultValue;
        int result = Integer.parseInt(specified);
        if (result < min || result > max) throw new IllegalArgumentException(name + " out of range");
        return result;
    }
}
