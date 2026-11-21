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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource-fencing integration: an actual guest actor suspends indefinitely
 * while expired HTTP messages accumulate behind the logical serial turn.
 * Connection admission must recycle; mailbox tombstones must remain bounded.
 */
final class SerialHttpStalledActorTest {
    @Test void permanentlySuspendedActorCannotAccumulateUnboundedExpiredTransfers()
            throws Exception {
        final int capacity = 4;
        final int attempts = 12;
        final String guest = """
                define serial actor Blocked as
                  receive(ActorMail<String> mail): void {
                    if (mail.value == "stall") {
                      stdio.println("BLOCKED");
                      val Channel<int> neverWritten = Channel.new<int>(0);
                      val int unreachable = await (nb readch neverWritten);
                      return;
                    } fi
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "ok");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 4, 1024, 450);
                  stdio.println(server.port);
                  val handler = http.handler("Blocked");
                  val first = await server.accept();
                  val finished = first.completion;
                  handler.dispatch(rt borrow mut first);
                  await finished;
                  handler.send("stall");
                  for (let i = 0; i < 12; i = i + 1) {
                    val next = await server.accept();
                    val completed = next.completion;
                    handler.dispatch(rt borrow mut next);
                    await completed;
                  }
                  stdio.println("FINAL_METRICS");
                  stdio.println(server.active);
                  stdio.println(server.transfer_backlog);
                  stdio.println(server.tombstones);
                  stdio.println(server.transfer_rejected);
                  // Ordinary user mail must survive selective HTTP retirement.
                  handler.send("unrelated-user-mail");
                  await server.shutdown(0);
                  stdio.println("POST_SHUTDOWN");
                  stdio.println(server.active);
                  stdio.println(server.transfer_backlog);
                  stdio.println(server.tombstones);
                  // A listener shutdown is not a cancellation of this actor.
                  handler.send("still-alive-user-mail");
                  stdio.println("ACTOR_PRESERVED");
                  return;
                }
                """;

        var port = new CompletableFuture<Integer>();
        var blocked = new CompletableFuture<Void>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                String text = toString(StandardCharsets.UTF_8);
                int firstNewline = text.indexOf('\n');
                if (firstNewline >= 0 && !port.isDone()) {
                    port.complete(Integer.parseInt(text.substring(0, firstNewline).trim()));
                }
                if (text.contains("BLOCKED\n")) blocked.complete(null);
            }
        };
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var clients = workers.submit(() -> {
                try (HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build()) {
                    URI base = URI.create("http://127.0.0.1:" + port.get(10, TimeUnit.SECONDS));
                    HttpRequest initial = HttpRequest.newBuilder(base.resolve("/start"))
                            .timeout(Duration.ofSeconds(5)).GET().build();
                    assertEquals(200, client.send(initial,
                            HttpResponse.BodyHandlers.ofString()).statusCode());
                    blocked.get(10, TimeUnit.SECONDS);
                    int expired = 0;
                    int overloaded = 0;
                    for (int i = 0; i < attempts; i++) {
                        HttpRequest request = HttpRequest.newBuilder(base.resolve("/queued-" + i))
                                .timeout(Duration.ofSeconds(8)).GET().build();
                        int status = client.send(request,
                                HttpResponse.BodyHandlers.ofString()).statusCode();
                        if (status == 504) expired++;
                        else if (status == 503) overloaded++;
                        else fail("unexpected stalled actor HTTP status " + status);
                    }
                    assertEquals(capacity, expired,
                            "only the first four unclaimed transfers may time out");
                    assertEquals(attempts - capacity, overloaded,
                            "after budget saturation new dispatches fail with 503");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, guest,
                        "stalled-serial-http.ores").build());
                clients.get(40, TimeUnit.SECONDS);
                // Prove physical actor/HTTP quiescence while the outer
                // Polyglot Context is still valid. The subsequent
                // try-with-resources close is intentionally idempotent.
                OresContextLifecycle.prepareForDisposal(context);
            } finally {
                clients.cancel(true);
            }
        }
        List<String> lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        int marker = lines.indexOf("FINAL_METRICS");
        assertTrue(marker > 0, () -> "missing final state metrics: " + lines);
        assertEquals("0", lines.get(marker + 1), "all HTTP connection permits recycled");
        assertEquals(Integer.toString(capacity), lines.get(marker + 2),
                "the actor retains exactly the bounded unclaimed tombstone set");
        assertEquals(Integer.toString(capacity), lines.get(marker + 3));
        assertEquals(Integer.toString(attempts - capacity), lines.get(marker + 4));
        int stoppedAt = lines.indexOf("POST_SHUTDOWN");
        assertTrue(stoppedAt > marker, () -> "missing shutdown drain: " + lines);
        assertEquals("0", lines.get(stoppedAt + 1), "no active HTTP after shutdown");
        assertEquals("0", lines.get(stoppedAt + 2), "queued HTTP leases reclaimed");
        assertEquals("0", lines.get(stoppedAt + 3), "queued tombstone counters reclaimed");
        assertEquals("ACTOR_PRESERVED", lines.get(stoppedAt + 4),
                "listener shutdown must not cancel the persistent actor");
    }
}
