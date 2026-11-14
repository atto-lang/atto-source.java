package dev.oreslang.runtime.devtools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in, loopback-only, read-only MCP 2026-07-28 adapter for trusted host use.
 * This does NOT expose actor state, memory addresses, or hot-upgrade mutations.
 * The attached view must return detached, redacted values (no guest objects).
 */
public final class LiveMcpServer implements AutoCloseable {
    public interface RuntimeView {
        Map<String, Object> inspect();
        default boolean hasTaskDiscovery() { return false; }
        default Map<String, Object> listTasks(int limit) {
            throw new UnsupportedOperationException("task discovery unavailable");
        }
        default boolean hasActorDiscovery() { return false; }
        default Map<String, Object> listActors(int limit) {
            throw new UnsupportedOperationException("actor discovery unavailable");
        }
        default Map<String, Object> inspectActor(String actorId) {
            throw new UnsupportedOperationException("actor discovery unavailable");
        }
    }

    public static final String PROTOCOL_VERSION = "2026-07-28";
    private static final int MAX_REQUEST_BYTES = 65_536;
    private final HttpServer http;
    private final ThreadPoolExecutor executor;
    private final byte[] expectedToken;
    private final RuntimeView view;

    private LiveMcpServer(HttpServer http, ThreadPoolExecutor executor,
                          RuntimeView view, String token) {
        this.http = http;
        this.executor = executor;
        this.view = view;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        http.createContext("/mcp", this::handle);
        http.setExecutor(executor);
        http.start();
    }

    /** No implicit startup: only a trusted host may call this factory. */
    public static LiveMcpServer start(RuntimeView view, String bearerToken, int port)
            throws IOException {
        Objects.requireNonNull(view, "view");
        if (bearerToken == null || bearerToken.length() < 32
                || bearerToken.length() > 4096 || !bearerToken.chars()
                .allMatch(c -> c >= 33 && c <= 126)) {
            throw new IllegalArgumentException("a host-injected high-entropy token is required");
        }
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port");
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                    Thread t = new Thread(runnable, "atto-live-mcp");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        try {
            return new LiveMcpServer(server, pool, view, bearerToken);
        } catch (RuntimeException error) {
            server.stop(0);
            pool.shutdownNow();
            throw error;
        }
    }

    public int port() { return http.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"/mcp".equals(exchange.getRequestURI().getPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                send(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            int port = port();
            if (!("127.0.0.1:" + port).equals(host)
                    && !("localhost:" + port).equals(host)) {
                send(exchange, 403, "{\"error\":\"invalid host\"}");
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !("http://127.0.0.1:" + port).equals(origin)
                    && !("http://localhost:" + port).equals(origin)) {
                send(exchange, 403, "{\"error\":\"invalid origin\"}");
                return;
            }
            List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
            String auth = authHeaders != null && authHeaders.size() == 1
                    ? authHeaders.getFirst() : null;
            byte[] offered = auth != null && auth.startsWith("Bearer ")
                    ? auth.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!MessageDigest.isEqual(expectedToken, offered)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            List<String> versionHeaders = exchange.getRequestHeaders()
                    .get("Mcp-Protocol-Version");
            if (versionHeaders == null || versionHeaders.size() != 1) {
                send(exchange, 400, error(null, -32600, "MCP protocol version header required"));
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (accept == null || !accept.contains("application/json")
                    || !accept.contains("text/event-stream")
                    || type == null || !type.toLowerCase(java.util.Locale.ROOT)
                            .startsWith("application/json")) {
                send(exchange, 406, "{\"error\":\"MCP headers required\"}");
                return;
            }
            byte[] request = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (request.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, "{\"error\":\"request too large\"}");
                return;
            }
            Object parsed;
            try {
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(request)).toString();
                parsed = Json.parse(decoded);
            } catch (IllegalArgumentException
                     | java.nio.charset.CharacterCodingException badJson) {
                send(exchange, 400, error(null, -32700, "Invalid JSON"));
                return;
            }
            if (!(parsed instanceof Map<?, ?> msg)
                    || !"2.0".equals(msg.get("jsonrpc"))
                    || !(msg.get("method") instanceof String method)
                    || !msg.containsKey("id")
                    || !(msg.get("id") instanceof String || msg.get("id") instanceof Number)) {
                send(exchange, 400, error(null, -32600, "Invalid Request"));
                return;
            }
            Object id = msg.get("id");
            String declaredMethod = exchange.getRequestHeaders().getFirst("Mcp-Method");
            String declaredName = exchange.getRequestHeaders().getFirst("Mcp-Name");
            Object params = msg.get("params");
            if (!(params instanceof Map<?, ?> parameters)
                    || !(parameters.get("_meta") instanceof Map<?, ?> metadata)
                    || !(metadata.get("io.modelcontextprotocol/protocolVersion") instanceof String)
                    || !(metadata.get("io.modelcontextprotocol/clientCapabilities") instanceof Map<?, ?>)) {
                send(exchange, 400, error(id, -32602, "Missing or invalid MCP request metadata"));
                return;
            }
            Object requestedVersion = metadata.get("io.modelcontextprotocol/protocolVersion");
            if (!PROTOCOL_VERSION.equals(requestedVersion)) {
                Map<String, Object> versionError = new LinkedHashMap<>();
                versionError.put("jsonrpc", "2.0");
                versionError.put("id", id);
                versionError.put("error", Map.of(
                        "code", -32022,
                        "message", "Unsupported protocol version",
                        "data", Map.of("supported", List.of(PROTOCOL_VERSION),
                                "requested", String.valueOf(requestedVersion))));
                send(exchange, 400, Json.write(versionError));
                return;
            }
            if (!PROTOCOL_VERSION.equals(versionHeaders.getFirst())) {
                send(exchange, 400, error(id, -32020, "MCP protocol version header mismatch"));
                return;
            }
            Object clientInfo = metadata.get("io.modelcontextprotocol/clientInfo");
            if (clientInfo != null
                    && (!(clientInfo instanceof Map<?, ?> info)
                        || !(info.get("name") instanceof String)
                        || !(info.get("version") instanceof String))) {
                send(exchange, 400, error(id, -32602, "Invalid MCP client information"));
                return;
            }
            if (!method.equals(declaredMethod)) {
                send(exchange, 400, error(id, -32020, "MCP header mismatch"));
                return;
            }
            if ("tools/call".equals(method)) {
                if (!(params instanceof Map<?, ?> p)
                        || !(p.get("name") instanceof String name)
                        || !name.equals(declaredName)) {
                    send(exchange, 400, error(id, -32020, "MCP tool name mismatch"));
                    return;
                }
            } else if (declaredName != null && !declaredName.isBlank()) {
                send(exchange, 400, error(id, -32020, "Unexpected MCP name"));
                return;
            }
            Object result;
            switch (method) {
                case "server/discover" -> result = Map.of(
                        "resultType", "complete",
                        "supportedVersions", List.of(PROTOCOL_VERSION),
                        "capabilities", Map.of("tools", Map.of()),
                        "ttlMs", 0, "cacheScope", "private");
                case "tools/list" -> {
                    List<Map<String, Object>> tools = new ArrayList<>();
                    tools.add(tool("atto.runtime_info",
                            "Read a detached runtime summary; no actor private state.",
                            Map.of("type", "object", "properties", Map.of(),
                                   "additionalProperties", false)));
                    if (view.hasActorDiscovery()) {
                        tools.add(tool("atto.list_actors",
                                "List at most 64 bounded actor descriptors, not a state snapshot.",
                                Map.of("type", "object", "properties",
                                        Map.of("limit", Map.of("type", "integer",
                                                "minimum", 1, "maximum", 64)),
                                        "additionalProperties", false)));
                        tools.add(tool("atto.inspect_actor",
                                "Inspect one actor descriptor by UUID; no private fields.",
                                Map.of("type", "object", "properties",
                                        Map.of("actor_id", Map.of("type", "string")),
                                        "required", List.of("actor_id"),
                                        "additionalProperties", false)));
                    }
                    if (view.hasTaskDiscovery()) {
                        tools.add(tool("atto.list_tasks",
                                "List at most 64 active logical Tasks, not physical threads or TaskLocal state.",
                                Map.of("type", "object", "properties",
                                        Map.of("limit", Map.of("type", "integer",
                                                "minimum", 1, "maximum", 64)),
                                        "additionalProperties", false)));
                    }
                    result = Map.of("resultType", "complete", "tools", tools,
                            "ttlMs", 0, "cacheScope", "private");
                }
                case "tools/call" -> {
                    Map<?, ?> p = (Map<?, ?>) params;
                    Object args = p.containsKey("arguments") ? p.get("arguments") : Map.of();
                    if (!(args instanceof Map<?, ?> a)) {
                        send(exchange, 200, error(id, -32602, "Arguments must be an object"));
                        return;
                    }
                    String name = (String) p.get("name");
                    Map<String, Object> data;
                    if ("atto.runtime_info".equals(name) && a.isEmpty()) {
                        data = Objects.requireNonNull(view.inspect(), "null runtime view");
                    } else if ("atto.list_actors".equals(name) && view.hasActorDiscovery()) {
                        if (a.size() > 1 || (a.size() == 1 && !a.containsKey("limit"))) {
                            send(exchange, 200, error(id, -32602, "Unexpected list arguments"));
                            return;
                        }
                        Object rawLimit = a.getOrDefault("limit", null);
                        int limit = 32;
                        if (rawLimit != null) {
                            if (!(rawLimit instanceof java.math.BigDecimal n)
                                    || n.scale() > 0 || n.compareTo(java.math.BigDecimal.ONE) < 0
                                    || n.compareTo(java.math.BigDecimal.valueOf(64)) > 0) {
                                send(exchange, 200, error(id, -32602, "limit must be 1..64"));
                                return;
                            }
                            limit = n.intValueExact();
                        }
                        data = Objects.requireNonNull(view.listActors(limit), "null actor listing");
                    } else if ("atto.list_tasks".equals(name) && view.hasTaskDiscovery()) {
                        if (a.size() > 1 || (a.size() == 1 && !a.containsKey("limit"))) {
                            send(exchange, 200, error(id, -32602, "Unexpected task list arguments"));
                            return;
                        }
                        Object rawLimit = a.get("limit");
                        int limit = 32;
                        if (rawLimit != null) {
                            if (!(rawLimit instanceof java.math.BigDecimal n)
                                    || n.scale() > 0 || n.compareTo(java.math.BigDecimal.ONE) < 0
                                    || n.compareTo(java.math.BigDecimal.valueOf(64)) > 0) {
                                send(exchange, 200, error(id, -32602, "limit must be 1..64"));
                                return;
                            }
                            limit = n.intValueExact();
                        }
                        data = Objects.requireNonNull(view.listTasks(limit), "null task listing");
                    } else if ("atto.inspect_actor".equals(name) && view.hasActorDiscovery()) {
                        Object requested = a.get("actor_id");
                        if (a.size() != 1 || !(requested instanceof String actorId)
                                || actorId.length() != 36
                                || !actorId.matches("[0-9a-fA-F-]{36}")) {
                            send(exchange, 200, error(id, -32602, "Invalid actor_id"));
                            return;
                        }
                        data = Objects.requireNonNull(view.inspectActor(actorId), "null actor view");
                    } else {
                        send(exchange, 200, error(id, -32602, "Unknown tool or invalid arguments"));
                        return;
                    }
                    String json = Json.write(data);
                    if (json.getBytes(StandardCharsets.UTF_8).length > 16_384) {
                        send(exchange, 200, error(id, -32603, "Runtime view exceeds bound"));
                        return;
                    }
                    result = Map.of("resultType", "complete",
                            "content", List.of(Map.of("type", "text", "text", json)),
                            "structuredContent", data,
                            "isError", false);
                }
                default -> {
                    send(exchange, 200, error(id, -32601, "Method not found"));
                    return;
                }
            }
            Map<String, Object> completed = new LinkedHashMap<>();
            if (result instanceof Map<?, ?> fields) {
                for (var entry : fields.entrySet()) {
                    completed.put((String) entry.getKey(), entry.getValue());
                }
            }
            completed.put("_meta", Map.of("io.modelcontextprotocol/serverInfo",
                    Map.of("name", "attolang-live-devtools", "version", "0.1.0")));
            send(exchange, 200, Json.write(Map.of("jsonrpc", "2.0",
                    "id", id, "result", completed)));
        } catch (RuntimeException failure) {
            // Never reflect Java exception names, actor state or stack traces over MCP.
            send(exchange, 500, error(null, -32603, "Internal error"));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> tool(String name, String description,
            Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description,
                "inputSchema", inputSchema,
                "annotations", Map.of("readOnlyHint", true));
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return Json.write(response);
    }

    private static void send(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @Override public void close() {
        http.stop(0);
        executor.shutdownNow();
        java.util.Arrays.fill(expectedToken, (byte) 0);
    }

    /** Dependency-free, bounded JSON codec. Duplicate keys and malformed input fail closed. */
    static final class Json {
        private final String input;
        private int at;
        private Json(String s) { input = s; }
        static Object parse(String s) {
            Json p = new Json(s);
            Object value = p.value(0);
            p.white();
            if (p.at != s.length()) throw new IllegalArgumentException("trailing JSON");
            return value;
        }
        private void white() {
            while (at < input.length() && " \r\n\t".indexOf(input.charAt(at)) >= 0) at++;
        }
        private boolean take(char c) {
            white();
            if (at < input.length() && input.charAt(at) == c) { at++; return true; }
            return false;
        }
        private void need(char c) {
            if (!take(c)) throw new IllegalArgumentException("expected " + c);
        }
        private Object value(int depth) {
            if (depth > 24) throw new IllegalArgumentException("JSON too deep");
            white();
            if (at >= input.length()) throw new IllegalArgumentException("truncated JSON");
            char c = input.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++;
                Map<String, Object> values = new LinkedHashMap<>();
                if (take('}')) return values;
                do {
                    white();
                    if (at >= input.length() || input.charAt(at) != '"')
                        throw new IllegalArgumentException("expected key");
                    String k = string();
                    need(':');
                    Object v = value(depth + 1);
                    if (values.containsKey(k))
                        throw new IllegalArgumentException("duplicate key");
                    values.put(k, v);
                    if (take('}')) return values;
                    need(',');
                } while (true);
            }
            if (c == '[') {
                at++;
                List<Object> values = new ArrayList<>();
                if (take(']')) return values;
                do {
                    values.add(value(depth + 1));
                    if (take(']')) return values;
                    need(',');
                } while (true);
            }
            for (String keyword : List.of("true", "false", "null")) {
                if (input.startsWith(keyword, at)) {
                    at += keyword.length();
                    return switch (keyword) {
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        default -> null;
                    };
                }
            }
            int start = at;
            if (c == '-') at++;
            if (at < input.length() && input.charAt(at) == '0') at++;
            else {
                if (at >= input.length() || input.charAt(at) < '1'
                        || input.charAt(at) > '9') throw new IllegalArgumentException("number");
                while (at < input.length() && Character.isDigit(input.charAt(at))) at++;
            }
            if (at < input.length() && input.charAt(at) == '.') {
                at++;
                int f = at;
                while (at < input.length() && Character.isDigit(input.charAt(at))) at++;
                if (at == f) throw new IllegalArgumentException("fraction");
            }
            if (at < input.length() && (input.charAt(at) == 'e'
                    || input.charAt(at) == 'E')) {
                at++;
                if (at < input.length() && (input.charAt(at) == '+'
                        || input.charAt(at) == '-')) at++;
                int e = at;
                while (at < input.length() && Character.isDigit(input.charAt(at))) at++;
                if (at == e) throw new IllegalArgumentException("exponent");
            }
            return new java.math.BigDecimal(input.substring(start, at));
        }
        private String string() {
            need('"');
            StringBuilder b = new StringBuilder();
            while (at < input.length()) {
                char c = input.charAt(at++);
                if (c == '"') return b.toString();
                if (c < 32) throw new IllegalArgumentException("control in string");
                if (c != '\\') { b.append(c); continue; }
                if (at == input.length()) throw new IllegalArgumentException("bad escape");
                char e = input.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (at + 4 > input.length()) throw new IllegalArgumentException("unicode");
                        try { b.append((char) Integer.parseInt(input.substring(at, at + 4), 16)); }
                        catch (NumberFormatException bad) { throw new IllegalArgumentException("unicode"); }
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("escape");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }
        static String write(Object o) {
            StringBuilder b = new StringBuilder();
            append(b, o, 0);
            return b.toString();
        }
        private static void append(StringBuilder b, Object o, int depth) {
            if (depth > 24) throw new IllegalArgumentException("JSON too deep");
            if (o == null) { b.append("null"); return; }
            if (o instanceof String s) {
                b.append('"');
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '"' -> b.append("\\\"");
                        case '\\' -> b.append("\\\\");
                        case '\n' -> b.append("\\n");
                        case '\r' -> b.append("\\r");
                        case '\t' -> b.append("\\t");
                        default -> {
                            if (c < 32) b.append(String.format("\\u%04x", (int) c));
                            else b.append(c);
                        }
                    }
                }
                b.append('"');
            } else if (o instanceof Boolean || o instanceof Number) {
                b.append(o);
            } else if (o instanceof Map<?, ?> m) {
                b.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("JSON key");
                    if (!first) b.append(',');
                    first = false;
                    append(b, e.getKey(), depth + 1);
                    b.append(':');
                    append(b, e.getValue(), depth + 1);
                }
                b.append('}');
            } else if (o instanceof List<?> list) {
                b.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) b.append(',');
                    first = false;
                    append(b, item, depth + 1);
                }
                b.append(']');
            } else throw new IllegalArgumentException("unsupported JSON value");
        }
    }
}
