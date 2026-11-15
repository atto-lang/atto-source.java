package dev.oreslang.runtime.devtools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** No third-party dependencies; verifies actual loopback POST and security denial. */
public final class Smoke {
    private static final String TOKEN = "test-only-long-token-not-for-production";
    private Smoke() { }

    public static void main(String[] ignored) throws Exception {
        try (LiveMcpServer server = LiveMcpServer.start(
                () -> Map.of("backend", "smoke", "actor_count", 0), TOKEN, 0)) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"atto.runtime_info\",\"arguments\":{},"
                    + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                    + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"smoke\",\"version\":\"1\"}}}}";
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Mcp-Protocol-Version", LiveMcpServer.PROTOCOL_VERSION)
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", "atto.runtime_info")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json");
            HttpResponse<String> denied = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            require(denied.statusCode() == 401, "missing token was accepted");
            HttpResponse<String> ok = client.send(
                    request.header("Authorization", "Bearer " + TOKEN)
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            require(ok.statusCode() == 200 && ok.body().contains("actor_count"),
                    "authenticated runtime_info failed: " + ok);
            require(!ok.body().contains("hotload_commit"), "mutation exposed");
            HttpResponse<String> dns = client.send(
                    request.header("Origin", "http://malicious.invalid")
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            require(dns.statusCode() == 403, "origin bypass");
            System.out.println("HOST_PROTOCOL_TESTED: loopback MCP, token denial, origin denial");
        }
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }
}
