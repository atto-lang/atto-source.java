package dev.oreslang.runtime.devtools;

import java.util.Map;

/** Short-lived localhost-only integration fixture; not the production launcher. */
public final class ServeForSdkSmoke {
    private ServeForSdkSmoke() { }
    public static void main(String[] ignored) throws Exception {
        String token = System.getenv("ATTO_MCP_TEST_TOKEN");
        if (token == null) throw new IllegalArgumentException("test token missing");
        try (LiveMcpServer server = LiveMcpServer.start(
                () -> Map.of("backend", "sdk-fixture", "actor_count", 0),
                token, 0)) {
            System.out.println("MCP_PORT=" + server.port());
            System.out.flush();
            Thread.sleep(30000);
        }
    }
}
