package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression proving the same actor receives successive HTTP exchanges. */
class NativeHttpPooledActorTest {
    @Test void sharedAndIsolatedWorkersHandleMultipleExchangesWithoutEnding() throws Exception {
        String program = """
            define actor Shared as
              receive(ActorMail<String> mail): void {
                val exchange = http.claim(mail.value);
                await exchange.respond(200, "text/plain", "ok");
                return;
              }
            end
            define isoactor Isolated as
              receive(ActorMail<String> mail): void {
                val exchange = http.claim(mail.value);
                await exchange.respond(200, "text/plain", "ok");
                return;
              }
            end
            pub async routine main(): void {
              val shared = http.pool_handler("Shared", 1);
              val isolated = http.pool_handler("Isolated", 1);
              val server = http.listen("127.0.0.1", 0, 32, 1024, 10000);
              stdio.println(server.port);
              for (let int i = 0; i < 20; i = i + 1) {
                val exchange = await server.accept();
                val completed = exchange.completion;
                if exchange.target == "/shared"; then
                  shared.dispatch(rt borrow mut exchange);
                else
                  isolated.dispatch(rt borrow mut exchange);
                fi
                await completed;
              }
              stdio.println("POOL_CREATIONS");
              stdio.println(shared.workers_created);
              stdio.println(isolated.workers_created);
              stdio.println("POOL_REUSES");
              stdio.println(shared.workers_reused);
              stdio.println(isolated.workers_reused);
              stdio.println(server.active);
              stdio.println("SERVER_POOL_COUNTERS");
              stdio.println(server.pooled_created);
              stdio.println(server.pooled_reused);
              await server.shutdown(0);
              return;
            }
            """;
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int length) {
                super.write(bytes, off, length);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (end >= 0 && !port.isDone())
                    port.complete(Integer.parseInt(text.substring(0, end).trim()));
            }
        };
        try (Context context = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of()).out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> work = clients.submit(() -> {
                try (HttpClient http = HttpClient.newHttpClient()) {
                    for (int i = 0; i < 20; i++) {
                        String path = i % 2 == 0 ? "/shared" : "/isolated";
                        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                                port.get(10, TimeUnit.SECONDS) + path))
                                .timeout(Duration.ofSeconds(8)).GET().build();
                        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode());
                        assertEquals("ok", response.body());
                    }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, program, "pooled-http-test.ores").build());
            work.get(30, TimeUnit.SECONDS);
        }
        String result = output.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("POOL_CREATIONS\n1\n1\nPOOL_REUSES\n9\n9\n0"),
            "pool did not reuse one actor per mode: " + result);
        assertTrue(result.contains("SERVER_POOL_COUNTERS\n2\n18"),
            "server-owned counters did not retain completed reuse accounting: " + result);
    }
}
