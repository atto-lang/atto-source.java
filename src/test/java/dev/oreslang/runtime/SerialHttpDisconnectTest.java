package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real client-side TCP RST after response headers and first streamed byte.
 * HTTP I/O failure must settle its exchange and preserve the serial actor
 * for the next request, without retaining a transfer or a mailbox tombstone.
 */
final class SerialHttpDisconnectTest {
    @Test void resetMidStreamDoesNotKillSerialActorOrLeakAdmission() throws Exception {
        final String chunk = "z".repeat(8192);
        String guest = """
                define serial actor Stream as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    if (request.target == "/drop") {
                      await request.start(200, "text/plain");
                      for (let i = 0; i < 4096; i = i + 1) {
                        try {
                          await request.write("%s");
                        } catch (err) {
                          stdio.println("WRITE_ABORTED");
                          return;
                        }
                      }
                      await request.finish();
                      stdio.println("WRITE_FINISHED");
                      return;
                    } fi
                    await request.respond(200, "text/plain", "still-alive");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 4, 65536, 12000);
                  stdio.println(server.port);
                  val handler = http.handler("Stream");
                  val dropped = await server.accept();
                  val droppedDone = dropped.completion;
                  handler.dispatch(rt borrow mut dropped);
                  val health = await server.accept();
                  val healthDone = health.completion;
                  handler.dispatch(rt borrow mut health);
                  await droppedDone;
                  await healthDone;
                  await server.shutdown(0);
                  stdio.println("DISCONNECT_METRICS");
                  stdio.println(server.active);
                  stdio.println(server.transfer_backlog);
                  stdio.println(server.tombstones);
                  return;
                }
                """.formatted(chunk);

        var port = new CompletableFuture<Integer>();
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                if (!port.isDone()) {
                    String seen = toString(StandardCharsets.UTF_8);
                    int eol = seen.indexOf('\n');
                    if (eol >= 0) port.complete(Integer.parseInt(seen.substring(0, eol).trim()));
                }
            }
        };
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(out).build();
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = worker.submit(() -> {
                int portNumber = port.get(10, TimeUnit.SECONDS);
                try (Socket socket = new Socket("127.0.0.1", portNumber)) {
                    socket.setSoTimeout(10_000);
                    socket.getOutputStream().write(("GET /drop HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();

                    // Wait for headers and actual streamed body, not merely a
                    // queued request. Then inject an immediate TCP reset.
                    ByteArrayOutputStream received = new ByteArrayOutputStream();
                    byte[] bytes = new byte[4096];
                    while (!received.toString(StandardCharsets.US_ASCII).contains("\r\n\r\n")) {
                        int n = socket.getInputStream().read(bytes);
                        if (n < 0) throw new AssertionError("response closed before headers");
                        received.write(bytes, 0, n);
                        if (received.size() > 16384) throw new AssertionError("oversize headers");
                    }
                    String prefix = received.toString(StandardCharsets.US_ASCII);
                    assertTrue(prefix.startsWith("HTTP/1.1 200"));
                    int bodyStart = prefix.indexOf("\r\n\r\n") + 4;
                    if (received.size() <= bodyStart) {
                        assertNotEquals(-1, socket.getInputStream().read(),
                                "TCP reset must occur after at least one streamed byte");
                    }
                    socket.setSoLinger(true, 0);
                }
                try (HttpClient healthy = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build()) {
                    HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + portNumber + "/health"))
                            .timeout(Duration.ofSeconds(20)).GET().build();
                    HttpResponse<String> response = healthy.send(request,
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertEquals("still-alive", response.body());
                }
                return null;
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, guest,
                        "serial-http-disconnect.ores").build());
                client.get(35, TimeUnit.SECONDS);
            } finally {
                client.cancel(true);
            }
        }
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        int at = lines.indexOf("DISCONNECT_METRICS");
        assertTrue(at > 0, () -> "missing drain metrics: " + lines);
        assertEquals("0", lines.get(at + 1), "active exchange leaked after TCP RST");
        assertEquals("0", lines.get(at + 2), "unclaimed transfer leaked after TCP RST");
        assertEquals("0", lines.get(at + 3), "tombstone leaked after TCP RST");
        assertTrue(lines.contains("WRITE_ABORTED"),
                "a committed midstream TCP reset must be observed by guest writer: " + lines);
        assertFalse(lines.contains("WRITE_FINISHED"),
                "the RST should interrupt the streaming write before completion");
    }
}
