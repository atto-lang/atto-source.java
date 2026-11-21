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
            requireAsciiToken(method, "HTTP method");
            requireOriginFormTarget(target);
            if (!"HTTP/1.1".equals(httpVersion)) {
                throw new IllegalArgumentException(
                        "WebSocket upgrade requires HTTP/1.1");
            }
            requireHostAuthority(host);
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
        requireHostAuthority(host);
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
                || target.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "WebSocket request target must be canonical origin-form");
        }
        for (int i = 0; i < target.length(); i++) {
            char ch = target.charAt(i);
            if (ch <= 0x20 || ch == 0x7f || ch > 0x7e) {
                throw new IllegalArgumentException(
                        "WebSocket request target contains whitespace/control/non-ASCII data");
            }
            if (ch != '%') continue;
            if (i + 2 >= target.length()) {
                throw new IllegalArgumentException(
                        "WebSocket request target contains malformed percent escape");
            }
            int high = Character.digit(target.charAt(i + 1), 16);
            int low = Character.digit(target.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException(
                        "WebSocket request target contains malformed percent escape");
            }
            int decoded = (high << 4) | low;
            if (decoded <= 0x20 || decoded == '/' || decoded == '%'
                    || decoded == '\\' || decoded == 0x7f) {
                throw new IllegalArgumentException(
                        "WebSocket request target encodes forbidden delimiter/control data");
            }
            i += 2;
        }
    }

    private static void requireHostAuthority(String host) {
        if (host == null || host.isEmpty()
                || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0
                || host.indexOf('?') >= 0 || host.indexOf('#') >= 0
                || host.indexOf('@') >= 0 || host.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Host must be a canonical authority");
        }
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch <= 0x20 || ch == 0x7f || ch > 0x7e) {
                throw new IllegalArgumentException(
                        "Host contains whitespace/control/non-ASCII data");
            }
        }

        if (host.charAt(0) == '[') {
            int close = host.indexOf(']');
            if (close <= 1 || host.indexOf('[', 1) >= 0
                    || host.indexOf(']', close + 1) >= 0) {
                throw new IllegalArgumentException("Host contains malformed IPv6 authority");
            }
            String literal = host.substring(1, close);
            for (int i = 0; i < literal.length(); i++) {
                char ch = literal.charAt(i);
                boolean ok = Character.digit(ch, 16) >= 0 || ch == ':' || ch == '.';
                if (!ok) {
                    throw new IllegalArgumentException(
                            "Host IPv6 literal contains unsupported syntax");
                }
            }
            requireIpv6Literal(literal);
            String suffix = host.substring(close + 1);
            if (!suffix.isEmpty()) {
                if (suffix.charAt(0) != ':' || suffix.length() == 1) {
                    throw new IllegalArgumentException("Host contains malformed port");
                }
                requirePort(suffix.substring(1));
            }
            return;
        }

        int colon = host.indexOf(':');
        if (colon != host.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "IPv6 Host literals must use brackets");
        }
        String name = colon < 0 ? host : host.substring(0, colon);
        if (name.isEmpty() || name.startsWith(".") || name.endsWith(".")) {
            throw new IllegalArgumentException("Host name is empty or non-canonical");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = ch >= '0' && ch <= '9'
                    || ch >= 'A' && ch <= 'Z'
                    || ch >= 'a' && ch <= 'z'
                    || ch == '.' || ch == '-';
            if (!ok) {
                throw new IllegalArgumentException("Host name contains invalid syntax");
            }
        }
        if (colon >= 0) requirePort(host.substring(colon + 1));
    }

    /**
     * Validate an IP-literal without hostname lookup. Character filtering is
     * insufficient: e.g. "[:]" and "[2001:::1]" contain only allowed
     * characters but are not IPv6 addresses.
     */
    private static void requireIpv6Literal(String literal) {
        if (!literal.contains(":") || literal.contains(":::")
                || (literal.startsWith(":") && !literal.startsWith("::"))
                || (literal.endsWith(":") && !literal.endsWith("::"))
                || literal.indexOf("::") != literal.lastIndexOf("::")) {
            throw new IllegalArgumentException("Host contains malformed IPv6 literal");
        }

        boolean compressed = literal.contains("::");
        String[] groups = literal.split(":", -1);
        int hextets = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) {
                if (!compressed) {
                    throw new IllegalArgumentException("Host contains malformed IPv6 literal");
                }
                continue;
            }
            if (group.indexOf('.') >= 0) {
                if (i != groups.length - 1) {
                    throw new IllegalArgumentException("Host IPv4 tail must be last");
                }
                String[] octets = group.split("\\.", -1);
                if (octets.length != 4) {
                    throw new IllegalArgumentException("Host contains invalid embedded IPv4");
                }
                for (String octet : octets) {
                    if (octet.isEmpty() || octet.length() > 3
                            || (octet.length() > 1 && octet.charAt(0) == '0')) {
                        throw new IllegalArgumentException("Host contains invalid embedded IPv4");
                    }
                    int number = 0;
                    for (int j = 0; j < octet.length(); j++) {
                        char ch = octet.charAt(j);
                        if (ch < '0' || ch > '9') {
                            throw new IllegalArgumentException("Host contains invalid embedded IPv4");
                        }
                        number = 10 * number + (ch - '0');
                    }
                    if (number > 255) {
                        throw new IllegalArgumentException("Host contains invalid embedded IPv4");
                    }
                }
                hextets += 2;
            } else {
                if (group.length() > 4) {
                    throw new IllegalArgumentException("Host contains oversized IPv6 hextet");
                }
                for (int j = 0; j < group.length(); j++) {
                    if (Character.digit(group.charAt(j), 16) < 0) {
                        throw new IllegalArgumentException("Host contains invalid IPv6 hextet");
                    }
                }
                hextets++;
            }
        }

        if (compressed ? hextets >= 8 : hextets != 8) {
            throw new IllegalArgumentException("Host contains invalid IPv6 width");
        }
    }

    private static void requirePort(String rawPort) {
        if (rawPort.isEmpty()) {
            throw new IllegalArgumentException("Host port is empty");
        }
        int port = 0;
        for (int i = 0; i < rawPort.length(); i++) {
            char ch = rawPort.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("Host port must be decimal");
            }
            port = Math.addExact(Math.multiplyExact(port, 10), ch - '0');
            if (port > 65535) {
                throw new IllegalArgumentException("Host port must be in 1..65535");
            }
        }
        if (port < 1) {
            throw new IllegalArgumentException("Host port must be in 1..65535");
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
