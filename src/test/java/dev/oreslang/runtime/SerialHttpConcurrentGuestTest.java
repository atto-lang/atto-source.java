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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine guest HTTP stress slice: distinct concurrent sockets and actor
 * state must share one serial actor, not one actor per request.
 */
final class SerialHttpConcurrentGuestTest {
    private static final int REQUESTS = 32;

    @Test void concurrentHttpClientsHaveOnePersistentSerialState() throws Exception {
        StringBuilder guest = new StringBuilder("""
                define serial actor Counter as
                  state { count: int = 0; }
                  pub increment(): int with state.read, state.write {
                    return self.handle_state(critical write |s| -> {
                      s.count = s.count + 1;
                      return s.count;
                    });
                  }
                  receive(ActorMail<String> mail): void {
                    val req = http.claim(mail.value);
                    stdio.println(self.increment());
                    await req.respond(200, "text/plain", "serial-ok");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 64, 1024, 20000);
                  stdio.println(server.port);
                  val handler = http.handler("Counter");
                """);
        for (int i = 0; i < REQUESTS; i++) {
            guest.append("val request").append(i).append(" = await server.accept();\n");
            guest.append("val completion").append(i).append(" = request").append(i)
                    .append(".completion;\n");
            guest.append("handler.dispatch(rt borrow mut request").append(i).append(");\n");
        }
        for (int i = 0; i < REQUESTS; i++) {
            guest.append("await completion").append(i).append(";\n");
        }
        guest.append("await server.shutdown(0);\nreturn;\n}\n");

        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                String content = toString(StandardCharsets.UTF_8);
                int newline = content.indexOf('\n');
                if (newline != -1 && !port.isDone()) {
                    port.complete(Integer.parseInt(content.substring(0, newline).trim()));
                }
            }
        };
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             ExecutorService work = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> clients = work.submit(() -> {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    int serverPort = port.get(15, TimeUnit.SECONDS);
                    List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
                    for (int i = 0; i < REQUESTS; i++) {
                        HttpRequest req = HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + serverPort + "/request-" + i))
                                .timeout(Duration.ofSeconds(30)).GET().build();
                        requests.add(client.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
                    }
                    CompletableFuture.allOf(requests.toArray(new CompletableFuture[0]))
                            .get(40, TimeUnit.SECONDS);
                    for (var req : requests) {
                        assertEquals(200, req.join().statusCode());
                        assertEquals("serial-ok", req.join().body());
                    }
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, guest.toString(),
                        "serial-http-concurrent.ores").build());
                clients.get(45, TimeUnit.SECONDS);
            } finally {
                clients.cancel(true);
            }
        }
        List<String> lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(REQUESTS + 1, lines.size(), "one port and exactly one increment per request");
        for (int i = 1; i <= REQUESTS; i++) {
            assertEquals(Integer.toString(i), lines.get(i),
                    "serial actor state must increment exactly once per admitted HTTP mail");
        }
    }
}
