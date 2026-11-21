package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebSocketOpeningRequestTest {

    @Test
    void parsesBoundedCanonicalOpeningRequestAndPreservesOrigin() throws Exception {
        String raw = "GET /chat?room=1 HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: https://app.example.test\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";

        var request = WebSocketOpeningRequest.read(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)),
                4096,
                16);

        assertEquals("GET", request.method());
        assertEquals("/chat?room=1", request.target());
        assertEquals("example.test", request.host());
        assertEquals("https://app.example.test", request.origin().orElseThrow());
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", request.admit());
    }

    @Test
    void rejectsDuplicateSingletonHeadersBeforeNormalization() {
        for (String duplicate : new String[] {
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n",
                "Sec-WebSocket-Version: 13\r\n",
                "Host: second.example.test\r\n",
                "Origin: https://second.example.test\r\n"
        }) {
            String raw = baseHead()
                    .replace("\r\n\r\n", "\r\n" + duplicate + "\r\n");
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketOpeningRequest.read(bytes(raw), 4096, 32));
        }
    }

    @Test
    void combinesRepeatableUpgradeAndConnectionTokenLists() throws Exception {
        String raw = "GET /chat HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Upgrade: h2c\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: keep-alive\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        var request = WebSocketOpeningRequest.read(bytes(raw), 4096, 16);
        assertEquals("h2c,websocket", request.upgrade());
        assertEquals("keep-alive,Upgrade", request.connection());
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", request.admit());
    }

    @Test
    void acceptsCanonicalHostPortAndBracketedIpv6Authorities() throws Exception {
        for (String host : new String[] {
                "example.test",
                "example.test:443",
                "127.0.0.1:8080",
                "[::1]",
                "[2001:db8::1]:8443"
        }) {
            String raw = baseHead().replace(
                    "Host: example.test",
                    "Host: " + host);
            var request = WebSocketOpeningRequest.read(bytes(raw), 4096, 32);
            assertEquals(host, request.host());
            assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", request.admit());
        }
    }

    @Test
    void rejectsObsFoldBareLfBodiesAndNonOriginTargets() {
        String folded = baseHead().replace(
                "Connection: Upgrade\r\n",
                "Connection: Upgrade\r\n\tkeep-alive\r\n");
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(folded), 4096, 32));

        String bareLf = baseHead().replace("\r\n", "\n");
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(bareLf), 4096, 32));

        String body = baseHead().replace(
                "\r\n\r\n",
                "\r\nContent-Length: 1\r\n\r\n");
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(body), 4096, 32));

        String transfer = baseHead().replace(
                "\r\n\r\n",
                "\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(transfer), 4096, 32));

        String absolute = baseHead().replace(
                "GET /chat HTTP/1.1",
                "GET http://example.test/chat HTTP/1.1");
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(absolute), 4096, 32));
    }

    @Test
    void rejectsAmbiguousRequestTargetsAndHostAuthorities() {
        for (String target : new String[] {
                "/chat\tbad",
                "/chat%0d%0aX-Evil:yes",
                "/chat%00",
                "/chat%5cadmin",
                "//example.test/chat"
        }) {
            String raw = baseHead().replace(
                    "GET /chat HTTP/1.1",
                    "GET " + target + " HTTP/1.1");
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketOpeningRequest.read(bytes(raw), 4096, 32), target);
        }

        for (String host : new String[] {
                "example.test evil.test",
                "user@example.test",
                "example.test/path",
                "example.test,evil.test",
                "2001:db8::1",
                "example.test:0",
                "example.test:65536"
        }) {
            String raw = baseHead().replace(
                    "Host: example.test",
                    "Host: " + host);
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketOpeningRequest.read(bytes(raw), 4096, 32), host);
        }
    }

    @Test
    void rejectsEncodedC0ControlsAndAmbiguousPathEscapes() {
        for (String target : new String[] {
                "/chat%01", "/chat%09", "/chat%1f", "/chat%20",
                "/chat%2fadmin", "/chat%25admin"
        }) {
            String raw = baseHead().replace(
                    "GET /chat HTTP/1.1",
                    "GET " + target + " HTTP/1.1");
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketOpeningRequest.read(bytes(raw), 4096, 32), target);
        }
        assertThrows(IllegalArgumentException.class, () ->
                new WebSocketOpeningRequest.Request(
                        "GET", "/chat%1f", "HTTP/1.1", "example.test",
                        "websocket", "Upgrade", "13",
                        "dGhlIHNhbXBsZSBub25jZQ==",
                        java.util.Optional.empty()));
    }

    @Test
    void rejectsMalformedBracketedIpv6EvenWhenCharactersLookValid() {
        for (String host : new String[] {
                "[:]", "[1]", "[2001:::db8]", "[1::2::3]",
                "[1:2:3:4:5:6:7:8::]", "[::ffff:999.1.1.1]",
                "[::ffff:01.1.1.1]"
        }) {
            String raw = baseHead().replace(
                    "Host: example.test", "Host: " + host);
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketOpeningRequest.read(bytes(raw), 4096, 32), host);
        }
    }

    @Test
    void acceptsFullIpv6AndCanonicalEmbeddedIpv4Literal() throws Exception {
        for (String host : new String[] {
                "[2001:db8:0:0:0:0:0:1]",
                "[::ffff:192.0.2.1]"
        }) {
            String raw = baseHead().replace(
                    "Host: example.test", "Host: " + host);
            assertEquals(host,
                    WebSocketOpeningRequest.read(bytes(raw), 4096, 32).host());
        }
    }

    @Test
    void enforcesByteAndHeaderCountBoundsBeforeUnboundedAccumulation() {
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(baseHead()), 256, 32));

        String count = "GET /chat HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "X-1: a\r\n"
                + "X-2: b\r\n"
                + "X-3: c\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketOpeningRequest.read(bytes(count), 4096, 4));
    }

    @Test
    void directRequestConstructionCannotBypassTargetHostOrVersionChecks() {
        assertThrows(IllegalArgumentException.class, () ->
                new WebSocketOpeningRequest.Request(
                        "GET",
                        "http://example.test/chat",
                        "HTTP/1.1",
                        "example.test",
                        "websocket",
                        "Upgrade",
                        "13",
                        "dGhlIHNhbXBsZSBub25jZQ==",
                        java.util.Optional.empty()));

        assertThrows(IllegalArgumentException.class, () ->
                new WebSocketOpeningRequest.Request(
                        "GET",
                        "/chat",
                        "HTTP/1.1",
                        "user@example.test",
                        "websocket",
                        "Upgrade",
                        "13",
                        "dGhlIHNhbXBsZSBub25jZQ==",
                        java.util.Optional.empty()));

        assertThrows(IllegalArgumentException.class, () ->
                new WebSocketOpeningRequest.Request(
                        "GET",
                        "/chat",
                        "HTTP/2",
                        "example.test",
                        "websocket",
                        "Upgrade",
                        "13",
                        "dGhlIHNhbXBsZSBub25jZQ==",
                        java.util.Optional.empty()));
    }

    @Test
    void missingOriginRemainsExplicitlyAbsentForNonBrowserPolicyLayer() throws Exception {
        var request = WebSocketOpeningRequest.read(bytes(baseHead()), 4096, 16);
        assertFalse(request.origin().isPresent());
        assertTrue(request.target().startsWith("/"));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String baseHead() {
        return "GET /chat HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
    }
}
