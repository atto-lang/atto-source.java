package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real .atto guest to actual HTTP loopback POST wire; NOT OTLP exporter test. */
@Timeout(35)
final class OutboundHttpPostGuestTest {
    @TempDir Path root;

    @Test
    void nativeGuestCanPostUtf8JsonAndBinaryBytesWithoutFramingAccess()
            throws Exception {
        AtomicReference<byte[]> json = new AtomicReference<>();
        AtomicReference<byte[]> binary = new AtomicReference<>();
        AtomicReference<String> jsonLength = new AtomicReference<>();
        AtomicReference<String> binaryLength = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/v1/traces", exchange -> {
                try {
                    assertEquals("POST", exchange.getRequestMethod());
                    jsonLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                    assertEquals("application/json",
                            exchange.getRequestHeaders().getFirst("Content-Type"));
                    json.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(202, -1);
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/octets", exchange -> {
                try {
                    assertEquals("POST", exchange.getRequestMethod());
                    binaryLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                    binary.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(204, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();
            Path program = root.resolve("post-traces.atto");
            Files.writeString(program, """
                    pub async routine main(): void {
                      val first = await http.request(
                        "POST", "http://127.0.0.1:%d/v1/traces",
                        [("Content-Type", "application/json")],
                        "{\\"resourceSpans\\":[]}");
                      stdio.println(first.status);
                      await first.close();
                      val second = await http.request(
                        "POST", "http://127.0.0.1:%d/octets",
                        [("Content-Type", "application/octet-stream")],
                        [0, 1, 13, 10, 255]);
                      stdio.println(second.status);
                      await second.close();
                      return;
                    }
                    """.formatted(port, port));
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try {
                LinkedProgramRunner.run(program,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.NETWORK),
                        RuntimePermissions.denyAll()
                                .withAllowed(RuntimePermissions.Permission.NET,
                                        "127.0.0.1:" + port),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(), System.getenv(), stdout, stderr);
                assertEquals("202\n204\n", stdout.toString(StandardCharsets.UTF_8)
                        .replace("\r", ""), () -> stderr.toString(StandardCharsets.UTF_8));
                assertArrayEquals("{\"resourceSpans\":[]}".getBytes(StandardCharsets.UTF_8),
                        json.get());
                assertArrayEquals(new byte[] {0, 1, 13, 10, (byte) 255}, binary.get());
                assertEquals(Integer.toString(json.get().length), jsonLength.get());
                assertEquals("5", binaryLength.get());
            } finally {
                server.stop(0);
            }
        }
    }
}
