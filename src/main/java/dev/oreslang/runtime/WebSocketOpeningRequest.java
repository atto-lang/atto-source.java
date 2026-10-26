package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded raw HTTP/1.1 parser for one RFC 6455 opening request.
 *
 * <p>The parser is intentionally narrower than a general HTTP server. It reads
 * one request head only, requires CRLF, rejects obs-fold and bodies, and
 * preserves duplicate detection for singleton WebSocket headers before any
 * normalized handshake validation occurs.</p>
 */
public final class WebSocketOpeningRequest {
    private static final int DEFAULT_MAX_HEADER_BYTES = 32 * 1024;
    private static final int DEFAULT_MAX_HEADER_COUNT = 64;

    private WebSocketOpeningRequest() { }

    public record Request(
            String method,
            String target,
            String httpVersion,
            String host,
            String upgrade,
            String connection,
            String webSocketVersion,
            String webSocketKey,
            Optional<String> origin) {
        public Request {
            if (origin == null) origin = Optional.empty();
        }

        public String admit() {
            return WebSocketHandshake.admit(
                    method,
                    httpVersion,
                    upgrade,
                    connection,
                    webSocketVersion,
                    webSocketKey);
        }
    }

    public static Request read(InputStream input) throws IOException {
        return read(input, DEFAULT_MAX_HEADER_BYTES, DEFAULT_MAX_HEADER_COUNT);
    }

    public static Request read(
            InputStream input,
            int maxHeaderBytes,
            int maxHeaderCount) throws IOException {
        if (input == null) throw new IllegalArgumentException("WebSocket handshake input is required");
        if (maxHeaderBytes < 256 || maxHeaderBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maxHeaderBytes must be in 256..1048576");
        }
        if (maxHeaderCount < 1 || maxHeaderCount > 1024) {
            throw new IllegalArgumentException("maxHeaderCount must be in 1..1024");
        }

        Counter counter = new Counter(maxHeaderBytes);
        String requestLine = readCrlfLine(input, counter);
        if (requestLine.isEmpty()) {
            throw new IllegalArgumentException("WebSocket request line is empty");
        }
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3
                || parts[0].isEmpty()
                || parts[1].isEmpty()
                || parts[2].isEmpty()) {
            throw new IllegalArgumentException("malformed WebSocket HTTP request line");
        }
        requireAsciiToken(parts[0], "HTTP method");
        requireOriginFormTarget(parts[1]);
        if (!parts[2].equals("HTTP/1.1")) {
            throw new IllegalArgumentException("WebSocket upgrade requires HTTP/1.1");
        }

        Map<String, String> singleton = new LinkedHashMap<>();
        String upgrade = null;
        String connection = null;
        int count = 0;

        while (true) {
            String line = readCrlfLine(input, counter);
            if (line.isEmpty()) break;
            count++;
            if (count > maxHeaderCount) {
                throw new IllegalArgumentException("WebSocket request header count exceeds configured bound");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new IllegalArgumentException("WebSocket request rejects obsolete folded headers");
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("malformed WebSocket request header");
            }
            String name = line.substring(0, colon);
            requireAsciiToken(name, "HTTP header name");
            String lower = name.toLowerCase(Locale.ROOT);
            String value = trimOws(line.substring(colon + 1));
            requireHeaderValue(value, name);

            switch (lower) {
                case "upgrade" ->
                        upgrade = appendTokenList(upgrade, value, "Upgrade");
                case "connection" ->
                        connection = appendTokenList(connection, value, "Connection");
                case "host",
                     "sec-websocket-key",
                     "sec-websocket-version",
                     "origin",
                     "content-length",
                     "transfer-encoding" ->
                        putSingleton(singleton, lower, value, name);
                default -> {
                    // Extension/subprotocol/auth/application headers remain
                    // available to a future higher layer but are deliberately
                    // not materialized by this protocol-minimal parser.
                }
            }
        }

        String host = requireSingleton(singleton, "host", "Host");
        String key = requireSingleton(
                singleton, "sec-websocket-key", "Sec-WebSocket-Key");
        String version = requireSingleton(
                singleton, "sec-websocket-version", "Sec-WebSocket-Version");
        if (upgrade == null || connection == null) {
            throw new IllegalArgumentException(
                    "WebSocket Upgrade and Connection headers are required");
        }

        String contentLength = singleton.get("content-length");
        if (contentLength != null && !contentLength.equals("0")) {
            throw new IllegalArgumentException(
                    "WebSocket opening request body is not accepted");
        }
        if (singleton.containsKey("transfer-encoding")) {
            throw new IllegalArgumentException(
                    "WebSocket opening request transfer encoding is not accepted");
        }

        return new Request(
                parts[0],
                parts[1],
                parts[2],
                host,
                upgrade,
                connection,
                version,
                key,
                Optional.ofNullable(singleton.get("origin")));
    }

    private static String readCrlfLine(InputStream input, Counter counter)
            throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawCr = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("truncated WebSocket HTTP request head");
            }
            counter.add();
            if (sawCr) {
                if (value != '\n') {
                    throw new IllegalArgumentException(
                            "WebSocket HTTP request requires CRLF line endings");
                }
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (value == '\r') {
                sawCr = true;
                continue;
            }
            if (value == '\n') {
                throw new IllegalArgumentException(
                        "WebSocket HTTP request requires CRLF line endings");
            }
            if (value == 0 || value > 0x7f) {
                throw new IllegalArgumentException(
                        "WebSocket HTTP request head must be ASCII without NUL");
            }
            line.write(value);
        }
    }

    private static void requireOriginFormTarget(String target) {
        if (!target.startsWith("/") || target.startsWith("//")
                || target.indexOf('#') >= 0
                || target.indexOf('\r') >= 0
                || target.indexOf('\n') >= 0
                || target.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "WebSocket request target must be canonical origin-form");
        }
    }

    private static void requireAsciiToken(String token, String what) {
        if (token.isEmpty()) throw new IllegalArgumentException(what + " cannot be empty");
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            boolean alphaNumeric =
                    ch >= '0' && ch <= '9'
                    || ch >= 'A' && ch <= 'Z'
                    || ch >= 'a' && ch <= 'z';
            boolean punctuation =
                    ch == '!' || ch == '#' || ch == '$' || ch == '%'
                    || ch == '&' || ch == '\'' || ch == '*'
                    || ch == '+' || ch == '-' || ch == '.'
                    || ch == '^' || ch == '_' || ch == 0x60
                    || ch == '|' || ch == '~';
            if (!alphaNumeric && !punctuation) {
                throw new IllegalArgumentException(what + " contains invalid token syntax");
            }
        }
    }

    private static void requireHeaderValue(String value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " header cannot be empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch < 0x20 && ch != '\t') || ch == 0x7f || ch > 0x7f) {
                throw new IllegalArgumentException(
                        name + " header contains control/non-ASCII data");
            }
        }
    }

    private static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static String appendTokenList(
            String existing, String value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " header cannot be empty");
        }
        return existing == null ? value : existing + "," + value;
    }

    private static void putSingleton(
            Map<String, String> singleton,
            String lowerName,
            String value,
            String displayName) {
        if (singleton.putIfAbsent(lowerName, value) != null) {
            throw new IllegalArgumentException(
                    "duplicate singleton WebSocket header " + displayName);
        }
    }

    private static String requireSingleton(
            Map<String, String> singleton,
            String lowerName,
            String displayName) {
        String value = singleton.get(lowerName);
        if (value == null) {
            throw new IllegalArgumentException(
                    displayName + " header is required");
        }
        return value;
    }

    private static final class Counter {
        private final int limit;
        private int bytes;

        private Counter(int limit) {
            this.limit = limit;
        }

        private void add() {
            bytes++;
            if (bytes > limit) {
                throw new IllegalArgumentException(
                        "WebSocket request headers exceed configured byte bound");
            }
        }
    }
}
