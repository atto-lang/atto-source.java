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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A claimed HTTP request can expire during an indefinitely suspended turn. */
final class SerialHttpClaimedTimeoutTest {
    @Test void claimedRequestTimeoutDoesNotRetainTransferOrCancelPersistentActor()
            throws Exception {
        String guest = """
                define serial actor Suspended as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    stdio.println("CLAIMED");
                    val Channel<int> never = Channel.new<int>(0);
                    val int ignored = await (nb readch never);
                    await request.respond(200, "text/plain", "impossible");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 2, 1024, 250);
                  stdio.println(server.port);
                  val incoming = await server.accept();
                  val completion = incoming.completion;
                  val handler = http.handler("Suspended");
                  handler.dispatch(rt borrow mut incoming);
                  stdio.println(await completion);
                  stdio.println(server.active);
                  stdio.println(server.transfer_backlog);
                  stdio.println(server.tombstones);
                  await server.shutdown(0);
                  return;
                }
                """;
        var port = new CompletableFuture<Integer>();
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int start, int len) {
                super.write(bytes, start, len);
                if (!port.isDone()) {
                    String text = toString(StandardCharsets.UTF_8);
                    int newline = text.indexOf('\n');
                    if (newline >= 0)
                        port.complete(Integer.parseInt(text.substring(0, newline)));
                }
            }
        };
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(out).build();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = workers.submit(() -> {
                int p = port.get(10, TimeUnit.SECONDS);
                try (HttpClient client = HttpClient.newHttpClient()) {
                    HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + p + "/claimed"))
                            .timeout(Duration.ofSeconds(6)).GET().build();
                    HttpResponse<String> response =
                            client.send(req, HttpResponse.BodyHandlers.ofString());
                    assertEquals(504, response.statusCode());
                }
                return null;
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, guest,
                        "serial-http-claimed-timeout.ores").build());
                request.get(10, TimeUnit.SECONDS);
            } finally {
                request.cancel(true);
            }
        }
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("CLAIMED\n"), output);
        assertTrue(output.endsWith("504\n0\n0\n0\n"), output);
    }
}
