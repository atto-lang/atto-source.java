package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.devtools.ActorRuntimeDevtools;
import dev.oreslang.runtime.devtools.LiveMcpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests against a real ActorRuntime; not a guest compiler execution test. */
final class LiveMcpServerTest {
    private static final String TOKEN = "local-test-only-token-with-at-least-32-bytes";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private HttpResponse<String> post(int port, String token, String method,
            String body, String version, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", version)
                .header("Mcp-Method", method);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if ("tools/call".equals(method)) {
            builder.header("Mcp-Name", "atto.runtime_info");
        }
        if (origin != null) builder.header("Origin", origin);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String request(String method, String params) {
        String contents = params.substring(1, params.length() - 1);
        String metadata = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"atto-test\",\"version\":\"1\"}}";
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                + method + "\",\"params\":{" + contents
                + (contents.isBlank() ? "" : ",") + metadata + "}}";
    }

    @Test
    void realActorRuntimeVisibleWithoutExposingStateOrUpgradeTools() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             LiveMcpServer server = ActorRuntimeDevtools.start(runtime, TOKEN, 0)) {
            int port = server.port();
            assertEquals("127.0.0.1", java.net.InetAddress
                    .getByName("127.0.0.1").getHostAddress());

            var discover = post(port, TOKEN, "server/discover",
                    request("server/discover", "{}"), LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(200, discover.statusCode());
            assertTrue(discover.body().contains("2026-07-28"));

            var tools = post(port, TOKEN, "tools/list",
                    request("tools/list", "{}"), LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(200, tools.statusCode());
            assertTrue(tools.body().contains("atto.runtime_info"));
            assertTrue(tools.body().contains("atto.list_actors"));
            assertTrue(tools.body().contains("atto.inspect_actor"));
            assertTrue(tools.body().contains("\"resultType\":\"complete\""));
            assertFalse(tools.body().contains("hotload_commit"));
            assertFalse(tools.body().contains("atto.snapshot_actor"));

            String call = request("tools/call",
                    "{\"name\":\"atto.runtime_info\",\"arguments\":{}}");
            var before = post(port, TOKEN, "tools/call", call,
                    LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(200, before.statusCode());
            assertTrue(before.body().contains("\\\"actor_count\\\":0"), before.body());
            assertTrue(before.body().contains("NOT_IMPLEMENTED"));

            var actor = runtime.<String>spawn(() -> (message, context) -> { });
            var after = post(port, TOKEN, "tools/call", call,
                    LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(200, after.statusCode());
            assertTrue(after.body().contains("\\\"actor_count\\\":1"), after.body());
            var listRequest = request("tools/call",
                    "{\"name\":\"atto.list_actors\",\"arguments\":{\"limit\":8}}");
            HttpRequest listHttp = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Mcp-Protocol-Version", LiveMcpServer.PROTOCOL_VERSION)
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", "atto.list_actors")
                    .header("Authorization", "Bearer " + TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(listRequest)).build();
            var actorList = client.send(listHttp, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, actorList.statusCode(), actorList.body());
            assertTrue(actorList.body().contains(actor.id().value().toString()), actorList.body());
            assertTrue(actorList.body().contains("WEAKLY_CONSISTENT"));
            assertFalse(actorList.body().contains("codeActorState"));
            actor.stop();
        }
    }

    @Test
    void rejectsUntrustedRequestsAndMalformedWireData() throws Exception {
        try (LiveMcpServer server = LiveMcpServer.start(
                () -> java.util.Map.of("ok", true), TOKEN, 0)) {
            int p = server.port();
            String call = request("tools/call",
                    "{\"name\":\"atto.runtime_info\",\"arguments\":{}}");

            assertEquals(401, post(p, null, "tools/call", call,
                    LiveMcpServer.PROTOCOL_VERSION, null).statusCode());
            assertEquals(401, post(p, "wrong-token", "tools/call", call,
                    LiveMcpServer.PROTOCOL_VERSION, null).statusCode());
            assertEquals(403, post(p, TOKEN, "tools/call", call,
                    LiveMcpServer.PROTOCOL_VERSION, "http://evil.invalid").statusCode());
            assertEquals(400, post(p, TOKEN, "tools/call", call,
                    "2025-11-25", null).statusCode());
            assertEquals(400, post(p, TOKEN, "tools/list", call,
                    LiveMcpServer.PROTOCOL_VERSION, null).statusCode());

            var duplicate = post(p, TOKEN, "tools/list",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":null,"
                            + "\"method\":\"tools/list\"}",
                    LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(400, duplicate.statusCode());

            var tooLarge = post(p, TOKEN, "tools/list",
                    " ".repeat(70_000), LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(413, tooLarge.statusCode());

            var get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + p + "/mcp")).header("Authorization", "Bearer " + TOKEN).GET().build();
            assertEquals(405, client.send(get, HttpResponse.BodyHandlers.discarding())
                    .statusCode());

            var unknown = post(p, TOKEN, "tools/call",
                    request("tools/call", "{\"name\":\"atto.no_such_tool\"}"),
                    LiveMcpServer.PROTOCOL_VERSION, null);
            assertEquals(400, unknown.statusCode(),
                    "MCP-Name/header mismatch must reject unknown tool before dispatch");
        }
    }

    @Test
    void listenerRequiresExplicitStrongToken() {
        assertThrows(IllegalArgumentException.class,
                () -> LiveMcpServer.start(() -> java.util.Map.of(), "short", 0));
    }
}
