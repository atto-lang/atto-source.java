package dev.oreslang.runtime.devtools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Host protocol smoke with detached synthetic actor descriptors; not real guest execution. */
public final class DiscoverySmoke {
    private static final String TOKEN = "local-test-only-discovery-token-123456";
    private static final String META =
            "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
    private DiscoverySmoke() { }

    private static String body(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":"
                + arguments + "," + META + "}}";
    }

    private static HttpResponse<String> call(HttpClient client, int port, String name,
            String arguments) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Mcp-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", name)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body(name, arguments)))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws Exception {
        LiveMcpServer.RuntimeView view = new LiveMcpServer.RuntimeView() {
            @Override public Map<String, Object> inspect() {
                return Map.of("backend", "synthetic-smoke-only");
            }
            @Override public boolean hasActorDiscovery() { return true; }
            @Override public boolean hasTaskDiscovery() { return true; }
            @Override public Map<String, Object> listTasks(int limit) {
                return Map.of("tasks", List.of(Map.of(
                        "task_id", 1, "lifecycle", "ACTIVE")),
                        "consistency", "SYNTHETIC_ONLY");
            }
            @Override public Map<String, Object> listActors(int limit) {
                return Map.of("actors", List.of(Map.of(
                        "actor_id", "11111111-1111-1111-1111-111111111111",
                        "kind", "PRIVATE", "mailbox_pending", 2)),
                        "consistency", "SYNTHETIC_ONLY");
            }
            @Override public Map<String, Object> inspectActor(String id) {
                return Map.of("found", true, "actor_id", id);
            }
        };
        try (LiveMcpServer server = LiveMcpServer.start(view, TOKEN, 0)) {
            HttpClient client = HttpClient.newHttpClient();
            var list = call(client, server.port(), "atto.list_actors", "{\"limit\":8}");
            must(list.statusCode() == 200 && list.body().contains("11111111-1111"),
                    "list_actors did not return descriptor");
            must(list.body().contains("\"resultType\":\"complete\""),
                    "missing MCP complete envelope");
            var tasks = call(client, server.port(), "atto.list_tasks", "{\"limit\":4}");
            must(tasks.statusCode() == 200 && tasks.body().contains("\"task_id\":1"),
                    "list_tasks did not return logical Task metadata");
            var inspect = call(client, server.port(), "atto.inspect_actor",
                    "{\"actor_id\":\"11111111-1111-1111-1111-111111111111\"}");
            must(inspect.statusCode() == 200 && inspect.body().contains("\"found\":true"),
                    "inspect_actor failed");
            var huge = call(client, server.port(), "atto.list_actors", "{\"limit\":65}");
            must(huge.body().contains("limit must be 1..64"),
                    "unbounded actor query was accepted");
            var unknown = call(client, server.port(), "atto.snapshot_actor", "{}");
            must(unknown.body().contains("Unknown tool"),
                    "unimplemented state snapshot was advertised");
            System.out.println("HOST_PROTOCOL_TESTED: synthetic actor list, inspect and bounded errors");
        }
    }

    private static void must(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}
