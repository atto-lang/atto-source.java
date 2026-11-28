package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreslang.OresLanguage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A REAL .atto guest -> live loopback HTTP server header oracle.
 * No source-level telemetry import or calls are used. It proves only CLIENT
 * traceparent injection; this is NOT a full OTLP/actor end-to-end test.
 *
 * Authored but NOT EXECUTED on the exact JDK 25 / GraalVM branch.
 */
@Timeout(35)
final class OutboundHttpAutoTraceGuestTest {
    @Test
    void unmodifiedGuestInjectsW3cRootOnlyWhenOperatorEnablesTracing() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    observed.set(exchange.getRequestHeaders()
                            .getFirst("traceparent"));
                    byte[] reply = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, reply.length);
                    exchange.getResponseBody().write(reply);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            String disabled = runGuest(port, false);
            assertEquals("200\n", disabled);
            assertNull(observed.get(),
                    "disabled compiler/runtime must not inject W3C headers");

            observed.set(null);
            String enabled = runGuest(port, true);
            assertEquals("200\n", enabled);
            TraceParent root = TraceParent.parse(observed.get()).orElseThrow();
            assertTrue(root.sampled());
            assertEquals(55, root.toHeader().length());
            assertNotEquals("0".repeat(32), root.traceId());
            assertNotEquals("0".repeat(16), root.spanId());
        } finally {
            server.stop(0);
        }
    }

    private static String runGuest(int port, boolean enabled) throws Exception {
        String guest = """
                pub async routine main(): void {
                  val response = await http.request(
                    "GET", "http://127.0.0.1:%d/", []);
                  stdio.println(response.status);
                  val body = response.take_body();
                  await body.close();
                  return;
                }
                """.formatted(port);
        Source source = Source.newBuilder(OresLanguage.ID, guest, "trace-http-client.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = enabled ? new String[] {
                "--ores-capabilities=STDIN,STDOUT,NETWORK,ACTOR_SPAWN,HOT_CODE_LOAD,SHARED_MEMORY",
                "--ores-telemetry=calls"
        } : new String[] {
                "--ores-capabilities=STDIN,STDOUT,NETWORK,ACTOR_SPAWN,HOT_CODE_LOAD,SHARED_MEMORY",
                "--ores-telemetry=off"
        };
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, args)
                .out(out).err(err).build()) {
            context.eval(source);
        }
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("Exception"),
                () -> err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8).replace("\r", "");
    }
}
