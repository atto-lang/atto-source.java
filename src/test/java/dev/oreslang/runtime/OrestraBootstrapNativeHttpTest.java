package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual GraalVM guest Oreslang source + real HTTP/1.1 loopback integration.
 * This fixture is byte-identical to orestra/orestra's bootstrap source.
 * It makes no claim about LLVM, durability, OCI or authenticated state.
 */
final class OrestraBootstrapNativeHttpTest {
    @Test
    @Timeout(50)
    void bootedActorsExposeLivenessButDenyReadinessAndUnauthorizedWrites() throws Exception {
        String program;
        try (InputStream input = getClass().getResourceAsStream("/orestra/native-bootstrap.ores")) {
            assertNotNull(input, "mirrored Orestra source missing");
            program = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(program.contains("define actor StateBootstrap as"));
        assertTrue(program.contains("define actor SchedulerBootstrap as"));
        assertTrue(program.contains("define actor NodeAgentBootstrap as"));
        assertTrue(program.contains("http.listen(\"127.0.0.1\""));
        // Parsing/type checking must succeed on genuine source, not a mock API.
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        CompletableFuture<Integer> portFuture = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] buffer, int offset, int length) {
                super.write(buffer, offset, length);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (!portFuture.isDone() && end >= 0) {
                    String firstLine = text.substring(0, end).trim();
                    try { portFuture.complete(Integer.parseInt(firstLine)); }
                    catch (NumberFormatException bad) { portFuture.completeExceptionally(bad); }
                }
            }
        };

        try (Context context = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                .out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> exercise = clients.submit(() -> {
                try (HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3)).build()) {
                    int port = portFuture.get(12, TimeUnit.SECONDS);
                    assertTrue(port > 0 && port <= 65535);
                    for (int round = 0; round < 2; ++round) {
                        assertReply(http, port, "GET", "/healthz", 200,
                                "{\"status\":\"ok\",\"component\":\"orestra-bootstrap\"}");
                        assertReply(http, port, "GET", "/readyz", 503,
                                "{\"status\":\"not_ready\",\"reason\":\"native_backend_and_storage_unverified\"}");
                        assertReply(http, port, "GET", "/v1alpha1/default/workload", 401,
                                "{\"error\":\"authentication_required\"}");
                        assertReply(http, port, "GET", "/missing", 404,
                                "{\"error\":\"not_found\"}");
                        assertReply(http, port, "PUT", "/v1alpha1/default/workload/x", 401,
                                "{\"error\":\"authentication_required\"}");
                        assertReply(http, port, "DELETE", "/v1alpha1/default/workload/x", 401,
                                "{\"error\":\"authentication_required\"}");
                        assertReply(http, port, "POST", "/healthz", 404,
                                "{\"error\":\"not_found\"}");
                        assertReply(http, port, "GET", "/readyz", 503,
                                "{\"status\":\"not_ready\",\"reason\":\"native_backend_and_storage_unverified\"}");
                    }
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, program,
                        "orestra-native-bootstrap.ores").build());
                exercise.get(20, TimeUnit.SECONDS);
            } finally {
                exercise.cancel(true);
            }
        }
    }

    private static void assertReply(HttpClient client, int port, String method, String path,
                                    int expectedStatus, String expectedJson) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(4))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), path);
        assertEquals(expectedJson, response.body(), path);
        assertEquals("application/json",
                response.headers().firstValue("Content-Type").orElseThrow(), path);
    }
}
