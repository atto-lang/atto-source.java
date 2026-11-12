package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Atto actor sends an encoded native Atto batch over bounded async
 * http.request POST to a live loopback OTLP/HTTP JSON test receiver.
 * This tests a one-shot actor fixture, not runtime auto-probe→collector
 * ingress or a production batched/supervised collector.
 * Authored test: NO JDK25/Graal compiler execution receipt yet.
 */
@Timeout(40)
final class NativeTelemetryActorOtlpGuestTest {
    @TempDir Path root;

    @Test
    void nativeAttoActorPostsOtlpJsonToRealLoopbackReceiver() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/v1/traces", exchange -> {
                try {
                    if (!exchange.getRequestMethod().equals("POST")) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    received.set(exchange.getRequestBody().readAllBytes());
                    byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, reply.length);
                    exchange.getResponseBody().write(reply);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();
            Path source = root.resolve("native-collector-once.atto");
            Files.writeString(source, """
                    import module telemetry from "ores.std/telemetry";

                    define actor TelemetryCollector as
                      receive(ActorMail<String> mail): void {
                        val code = await telemetry.post_otlp_json_async(
                          "http://127.0.0.1:%d/v1/traces", mail.value);
                        stdio.println(code);
                        self.end();
                        return;
                      }
                    end

                    pub async routine main(): void {
                      val batch = telemetry.span_batch(1);
                      val record = telemetry.completed_span(
                        "0123456789abcdef0123456789abcdef",
                        "abcdef0123456789", "",
                        201, 2, 1780000000000000000, 1780000000000000500,
                        200, false);
                      batch.offer(record);
                      val worker = spawn TelemetryCollector();
                      await worker.get_ready();
                      worker.send(batch.to_otlp_json());
                      await worker.get_done();
                      return;
                    }
                    """.formatted(port));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try {
                LinkedProgramRunner.run(
                        source,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.NETWORK),
                        RuntimePermissions.denyAll().withAllowed(
                                RuntimePermissions.Permission.NET,
                                "127.0.0.1:" + port),
                        PermissionCheckMode.RUNTIME, ExecutionProfile.serverJit(),
                        Set.of(), System.getenv(), out, err);
                assertEquals("200\n", out.toString(StandardCharsets.UTF_8)
                                .replace("\r", ""),
                        () -> err.toString(StandardCharsets.UTF_8));
                assertEquals("application/json", contentType.get());
                String body = new String(received.get(), StandardCharsets.UTF_8);
                assertTrue(body.startsWith("{\"resourceSpans\":["), body);
                assertTrue(body.contains("\"traceId\":\"0123456789abcdef0123456789abcdef\""),
                        body);
                assertTrue(body.contains("\"spanId\":\"abcdef0123456789\""), body);
                assertTrue(body.contains("\"startTimeUnixNano\":\"1780000000000000000\""), body);
                assertTrue(body.contains("\"kind\":2"), body);
            } finally {
                server.stop(0);
            }
        }
    }
}
