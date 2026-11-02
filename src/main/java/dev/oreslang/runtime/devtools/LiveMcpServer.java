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
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
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
            if (!PROTOCOL_VERSION.equals(exchange.getRequestHeaders()
                    .getFirst("Mcp-Protocol-Version"))) {
                send(exchange, 400, "{\"error\":\"unsupported MCP protocol version\"}");
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
                parsed = Json.parse(new String(request, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException badJson) {
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
                        "supportedVersions", List.of(PROTOCOL_VERSION),
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "attolang-live-devtools", "version", "0.1.0"),
                        "ttlMs", 0, "cacheScope", "private");
                case "tools/list" -> result = Map.of("tools", List.of(Map.of(
                        "name", "atto.runtime_info",
                        "description", "Read a bounded, detached runtime summary; no actor state or memory access.",
                        "inputSchema", Map.of("type", "object",
                                "properties", Map.of(), "additionalProperties", false),
                        "annotations", Map.of("readOnlyHint", true))),
                        "ttlMs", 0, "cacheScope", "private");
                case "tools/call" -> {
                    Map<?, ?> p = (Map<?, ?>) params;
                    if (!"atto.runtime_info".equals(p.get("name"))) {
                        send(exchange, 200, error(id, -32602, "Unknown tool"));
                        return;
                    }
                    Object args = p.getOrDefault("arguments", Map.of());
                    if (!(args instanceof Map<?, ?> a) || !a.isEmpty()) {
                        send(exchange, 200, error(id, -32602, "Tool accepts no arguments"));
                        return;
                    }
                    Map<String, Object> snapshot = Objects.requireNonNull(
                            view.inspect(), "runtime view returned null");
                    // Do not retain the view's state; serialize each detached observation.
                    String json = Json.write(snapshot);
                    if (json.getBytes(StandardCharsets.UTF_8).length > 16_384) {
                        send(exchange, 200, error(id, -32603, "Runtime view exceeds bound"));
                        return;
                    }
                    result = Map.of("content",
                            List.of(Map.of("type", "text", "text", json)));
                }
                default -> {
                    send(exchange, 200, error(id, -32601, "Method not found"));
                    return;
                }
            }
            send(exchange, 200, Json.write(Map.of("jsonrpc", "2.0",
                    "id", id, "result", result)));
        } catch (RuntimeException failure) {
            // Never reflect Java exception names, actor state or stack traces over MCP.
            send(exchange, 500, error(null, -32603, "Internal error"));
        } finally {
            exchange.close();
        }
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
                    if (values.putIfAbsent(k, v) != null || (v == null && values.containsKey(k)
                            && values.size() != new java.util.HashSet<>(values.keySet()).size()))
                        throw new IllegalArgumentException("duplicate key");
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
