package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real guest inbound/actor/outbound transport admission, not a timed benchmark. */
@Timeout(45)
final class SharkBenchTransportGuestTest {
    @TempDir Path root;

    @Test
    void guestFetchesUpstreamAndRecoversAfterNon200() throws Exception {
        byte[] payload = "{\"He\":{\"name\":\"Helium\",\"number\":2,\"group\":18}}"
                .getBytes(StandardCharsets.UTF_8);
        AtomicInteger hits = new AtomicInteger();
        CompletableFuture<Integer> listenerPort = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            private void noticePort() {
                String text = toString(StandardCharsets.UTF_8);
                int newline = text.indexOf('\n');
                if (newline >= 0 && !listenerPort.isDone()) {
                    try {
                        listenerPort.complete(Integer.parseInt(text.substring(0, newline).trim()));
                    } catch (NumberFormatException failure) {
                        listenerPort.completeExceptionally(failure);
                    }
                }
            }
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                noticePort();
            }
            @Override public synchronized void write(int value) {
                super.write(value);
                noticePort();
            }
        };
        try (var upstreamWorkers = Executors.newVirtualThreadPerTaskExecutor();
             var guestWorker = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            upstream.setExecutor(upstreamWorkers);
            upstream.createContext("/element.json", exchange -> {
                try {
                    if (!exchange.getRequestMethod().equals("GET")
                            || !exchange.getRequestURI().toString().equals("/element.json")) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    int hit = hits.incrementAndGet();
                    if (hit == 2) {
                        exchange.sendResponseHeaders(503, -1);
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, payload.length);
                        exchange.getResponseBody().write(payload);
                    }
                } finally {
                    exchange.close();
                }
            });
            upstream.start();
            int upstreamPort = upstream.getAddress().getPort();
            Path source = root.resolve("sharkbench-transport.atto");
            Files.writeString(source, """
                define actor TransportWorker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    val response = await http.request(
                      "GET", "http://127.0.0.1:%d/element.json",
                      [("Accept", "application/json")]
                    );
                    if response.status != 200; then
                      await response.close();
                      await request.respond(502, "text/plain", "upstream status");
                    else
                      val body = response.take_body();
                      let int count = 0;
                      while true {
                        val chunk = await body.read_chunk(8192);
                        if chunk.size == 0; then break; fi
                        count = count + chunk.size;
                        if count > 131072; then break; fi
                      }
                      await body.close();
                      if count == %d; then
                        await request.respond(200, "text/plain", "transport-only");
                      else
                        await request.respond(502, "text/plain", "wrong upstream size");
                      fi
                    fi
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 8, 1024, 5000);
                  val handler = http.handler("TransportWorker");
                  stdio.println(server.port);
                  for (let int i = 0; i < 3; i = i + 1) {
                    val request = await server.accept();
                    val completion = request.completion;
                    handler.dispatch(rt mut borrow request);
                    await completion;
                  }
                  await server.shutdown(0);
                  stdio.println(server.active);
                  return;
                }
                """.formatted(upstreamPort, payload.length));
            var permissions = RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET,
                    "127.0.0.1:0", "127.0.0.1:" + upstreamPort);
            try {
                var guest = guestWorker.submit(() -> {
                    try {
                        LinkedProgramRunner.run(
                                source,
                                IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                                permissions, PermissionCheckMode.RUNTIME,
                                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                                output, new ByteArrayOutputStream());
                        if (!listenerPort.isDone()) {
                            listenerPort.completeExceptionally(new AssertionError("guest exited before listening"));
                        }
                    } catch (Throwable failure) {
                        listenerPort.completeExceptionally(failure);
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                });
                try {
                    int port = listenerPort.get(15, TimeUnit.SECONDS);
                    assertTrue(port > 0 && port <= 65535);
                    HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(3))
                            .version(HttpClient.Version.HTTP_1_1)
                            .followRedirects(HttpClient.Redirect.NEVER).build();
                    var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/transport-smoke"))
                            .timeout(Duration.ofSeconds(7)).GET().build();
                    for (int status : new int[]{200, 502, 200}) {
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(status, response.statusCode());
                        assertEquals(status == 200 ? "transport-only" : "upstream status", response.body());
                    }
                    guest.get(10, TimeUnit.SECONDS);
                    assertEquals(3, hits.get(), "one real upstream request per guest handler");
                    assertEquals(port + "\n0", output.toString(StandardCharsets.UTF_8)
                            .replace("\r", "").trim(), "listener must drain to zero");
                } finally {
                    guest.cancel(true);
                }
            } finally {
                upstream.stop(0);
            }
        }
    }
}
