package dev.oreslang.runtime;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WebSocketHandshakeTest {

    @Test
    void admitsCanonicalUpgradeAndDerivesRfcAcceptKey() {
        String accepted = WebSocketHandshake.admit(
                "GET",
                "HTTP/1.1",
                "websocket",
                "keep-alive, Upgrade",
                "13",
                "dGhlIHNhbXBsZSBub25jZQ==");

        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accepted);
    }

    @Test
    void tokenMatchingIsCaseInsensitiveButSyntaxRemainsStrict() {
        assertEquals(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketHandshake.admit(
                        "GET",
                        "HTTP/1.1",
                        "WebSocket",
                        " keep-alive , UpGrAdE ",
                        "13",
                        "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET", "HTTP/1.1", "websocket", "upgrade,",
                        "13", "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET", "HTTP/1.1", "web socket", "upgrade",
                        "13", "dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void rejectsWrongMethodProtocolVersionOrUpgradeTokens() {
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "POST", "HTTP/1.1", "websocket", "Upgrade",
                        "13", "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET", "HTTP/2", "websocket", "Upgrade",
                        "13", "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET", "HTTP/1.1", "h2c", "Upgrade",
                        "13", "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET", "HTTP/1.1", "websocket", "keep-alive",
                        "13", "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET", "HTTP/1.1", "websocket", "Upgrade",
                        "12", "dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void rejectsNoncanonicalOrWrongSizedKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.acceptKey("not-base64-not-base64!!"));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.acceptKey("YWJjZA=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.acceptKey("dGhlIHNhbXBsZSBub25jZQ=\n"));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.acceptKey("dGhlIHNhbXBsZSBub25jZQ=A"));
    }

    @Test
    void boundedHeadersRejectControlsAndOversizeBeforeTokenParsing() {
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET",
                        "HTTP/1.1",
                        "websocket\r\nX-Injected: yes",
                        "Upgrade",
                        "13",
                        "dGhlIHNhbXBsZSBub25jZQ=="));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.admit(
                        "GET",
                        "HTTP/1.1",
                        "websocket",
                        "x".repeat(1025),
                        "13",
                        "dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void browserOriginPolicyIsExactAndFailClosed() {
        Set<String> allowed = Set.of(
                "https://app.example.test",
                "https://admin.example.test");

        WebSocketHandshake.requireAllowedOrigin(
                "https://app.example.test", allowed);

        assertThrows(SecurityException.class, () ->
                WebSocketHandshake.requireAllowedOrigin(
                        "https://evil.example.test", allowed));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.requireAllowedOrigin("null", allowed));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.requireAllowedOrigin(
                        "https://*.example.test", allowed));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketHandshake.requireAllowedOrigin(
                        "https://app.example.test", Set.of()));
    }
}
