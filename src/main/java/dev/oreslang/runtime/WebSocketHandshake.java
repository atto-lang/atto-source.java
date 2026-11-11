package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;

/**
 * Strict RFC 6455 HTTP upgrade admission primitives.
 *
 * <p>This helper owns no HTTP exchange, socket, actor, or capability. Callers
 * must perform ordinary HTTP header-count/size admission before passing bounded
 * strings here, and must apply application authentication/origin policy before
 * granting a WebSocket connection capability.</p>
 */
public final class WebSocketHandshake {
    private static final String MAGIC =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_UPGRADE_HEADER_CHARS = 256;
    private static final int MAX_CONNECTION_HEADER_CHARS = 1024;

    private WebSocketHandshake() { }

    public static String admit(
            String method,
            String httpVersion,
            String upgrade,
            String connection,
            String webSocketVersion,
            String webSocketKey) {
        requireExact(method, "GET", "WebSocket upgrade requires GET");
        requireExact(httpVersion, "HTTP/1.1",
                "WebSocket upgrade requires HTTP/1.1");

        requireBoundedAsciiHeader(
                upgrade, MAX_UPGRADE_HEADER_CHARS, "Upgrade");
        if (!containsToken(upgrade, "websocket")) {
            throw new IllegalArgumentException(
                    "Upgrade header must contain websocket");
        }

        requireBoundedAsciiHeader(
                connection, MAX_CONNECTION_HEADER_CHARS, "Connection");
        if (!containsToken(connection, "upgrade")) {
            throw new IllegalArgumentException(
                    "Connection header must contain Upgrade");
        }

        requireExact(webSocketVersion, "13",
                "Sec-WebSocket-Version must be 13");
        requireCanonicalKey(webSocketKey);
        return acceptKey(webSocketKey);
    }

    public static String acceptKey(String webSocketKey) {
        requireCanonicalKey(webSocketKey);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(
                    (webSocketKey + MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "JDK is missing RFC6455-required SHA-1 digest", impossible);
        }
    }

    public static void requireAllowedOrigin(
            String origin,
            java.util.Set<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException(
                    "WebSocket origin allowlist must not be empty");
        }
        requireBoundedAsciiHeader(origin, 2048, "Origin");
        if (origin.equals("null") || origin.indexOf('*') >= 0) {
            throw new IllegalArgumentException(
                    "WebSocket Origin must be an explicit allowlisted origin");
        }
        if (!allowedOrigins.contains(origin)) {
            throw new SecurityException(
                    "WebSocket Origin is not allowlisted");
        }
    }

    private static void requireCanonicalKey(String key) {
        if (key == null || key.length() != 24) {
            throw new IllegalArgumentException(
                    "Sec-WebSocket-Key must be canonical Base64 for 16 bytes");
        }
        requireBoundedAsciiHeader(key, 24, "Sec-WebSocket-Key");

        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException(
                    "Sec-WebSocket-Key must be valid standard Base64",
                    invalidBase64);
        }
        if (decoded.length != 16
                || !Base64.getEncoder().encodeToString(decoded).equals(key)) {
            throw new IllegalArgumentException(
                    "Sec-WebSocket-Key must be canonical Base64 for 16 bytes");
        }
    }

    private static boolean containsToken(String value, String required) {
        boolean found = false;
        for (String raw : value.split(",", -1)) {
            String token = trimOptionalWhitespace(raw);
            if (token.isEmpty()) {
                throw new IllegalArgumentException(
                        "WebSocket token-list header contains an empty token");
            }
            requireHttpToken(token);
            if (token.toLowerCase(Locale.ROOT).equals(required)) {
                found = true;
            }
        }
        return found;
    }

    private static String trimOptionalWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isOptionalWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isOptionalWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isOptionalWhitespace(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static void requireHttpToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
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
                throw new IllegalArgumentException(
                        "WebSocket token-list header contains invalid token syntax");
            }
        }
    }

    private static void requireBoundedAsciiHeader(
            String value, int maxChars, String name) {
        if (value == null || value.isEmpty() || value.length() > maxChars) {
            throw new IllegalArgumentException(
                    name + " header is missing or exceeds configured bound");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch > 0x7e) {
                if (ch == '\t') continue;
                throw new IllegalArgumentException(
                        name + " header contains non-ASCII/control data");
            }
        }
    }

    private static void requireExact(
            String actual, String expected, String message) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(message);
        }
    }
}
