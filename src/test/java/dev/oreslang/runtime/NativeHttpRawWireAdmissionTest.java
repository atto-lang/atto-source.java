package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the actual JDK HTTP/1.1 wire parser and the NativeHttp admission
 * policy together. The companion unit tests cover the admission helper's
 * boundary cases; this test checks what the JDK passes through to it.
 *
 * This is a host-transport test, not compiler-backed AttoLang guest execution.
 */
final class NativeHttpRawWireAdmissionTest {
    private static String request(InetSocketAddress address, String raw) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(address, 2_000);
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            BufferedReader lines = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = lines.readLine();
            assertNotNull(statusLine, "transport must send an HTTP status line");
            String header;
            do {
                header = lines.readLine();
                assertNotNull(header, "response headers must be terminated");
            } while (!header.isEmpty());
            // JDK's *pre-handler parser* writes a bounded explanatory body
            // for some rejected framing (for example duplicate Content-Length).
            // Consume that body before requiring EOF; do not assume every
            // HTTP 400 has an empty response body.
            int responseBodyChars = 0;
            while (lines.read() != -1) {
                if (++responseBodyChars > 65_536) {
                    fail("unexpectedly long HTTP error response body");
                }
            }
            return statusLine;
        }
    }

    private static String get(String headerBlock) {
        return "GET / HTTP/1.1\r\n"
                + headerBlock
                + "Connection: close\r\n\r\n";
    }

    @Test
    void hostValidationRejectsMalformedRawRequestsAndListenerRecovers() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 8);
        java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger successfulBodies = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            int status;
            try {
                NativeHttp.validateHeadersForAdmission(
                        exchange.getRequestHeaders(), exchange.getProtocol(), 1024);
                admitted.incrementAndGet();
                // Mirror NativeHttp.bodyText's bounded read on the raw JDK wire.
                // Merely validating declared Content-Length misses chunked
                // bodies that grow past the quota after header admission.
                byte[] body = exchange.getRequestBody().readNBytes(1025);
                if (body.length > 1024)
                    throw new NativeHttp.RequestBodyTooLargeException();
                successfulBodies.incrementAndGet();
                status = 200;
            } catch (IllegalArgumentException badRequest) {
                status = badRequest instanceof NativeHttp.RequestBodyTooLargeException ? 413 : 400;
            } catch (IOException malformedRequestBody) {
                status = NativeHttp.operationFailureStatus(malformedRequestBody, true);
            }
            try {
                exchange.sendResponseHeaders(status, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            InetSocketAddress address = server.getAddress();
            assertTrue(request(address, get("Host: localhost\r\n")).startsWith("HTTP/1.1 200"),
                    "one valid Host must remain accepted");
            assertTrue(request(address, get(
                    "Host: localhost\r\nHost: attacker.test\r\n")).startsWith("HTTP/1.1 400"),
                    "the JDK must preserve duplicate Host information for the gate");
            assertTrue(request(address, get(
                    "Host: localhost,attacker.test\r\n")).startsWith("HTTP/1.1 400"),
                    "joined Host must not become a routing ambiguity");
            assertTrue(request(address, get(
                    "Host: localhost\r\nhOsT: attacker.test\r\n")).startsWith("HTTP/1.1 400"),
                    "mixed-case duplicate Host must not bypass case-insensitive lookup");
            assertTrue(request(address, get(
                    "Host: example.test:abc\r\n")).startsWith("HTTP/1.1 400"),
                    "non-numeric Host ports must fail admission");
            assertTrue(request(address, get(
                    "Host: :8080\r\n")).startsWith("HTTP/1.1 400"),
                    "empty Host names must fail admission");
            assertTrue(request(address, get(
                    "Host: localhost:65536\r\n")).startsWith("HTTP/1.1 400"),
                    "out-of-range Host ports must fail admission");
            assertTrue(request(address, get(
                    "Host: [gggg]\r\n")).startsWith("HTTP/1.1 400"),
                    "malformed bracketed IPv6 literals must fail admission");
            assertTrue(request(address, get(
                    "Host:   \r\n")).startsWith("HTTP/1.1 400"),
                    "blank Host must fail closed");
            assertTrue(request(address, get(
                    "X-Case: value\r\n")).startsWith("HTTP/1.1 400"),
                    "HTTP/1.1 without Host must fail closed");
            assertTrue(request(address, get(
                    "Host: localhost\r\nX-Long: " + "x".repeat(12_000) + "\r\n"))
                    .startsWith("HTTP/1.1 400"), "individual 12 KiB header must be rejected");
            assertTrue(request(address, get(
                    "Host: localhost\r\nContent-Length: 1025\r\n"))
                    .startsWith("HTTP/1.1 413"),
                    "over-quota declared length must be rejected before body read as 413");
            assertTrue(request(address, get(
                    "Host: localhost\r\nContent-Length: 9223372036854775808\r\n"))
                    .startsWith("HTTP/1.1 400"),
                    "overflowed decimal length must be rejected as invalid framing, not a quota");
            assertTrue(request(address, get(
                    "Host: localhost\r\nContent-Length: 0\r\nContent-Length: 0\r\n"))
                    .startsWith("HTTP/1.1 400"), "duplicate Content-Length must be rejected");
            assertTrue(request(address, get(
                    "Host: localhost\r\nContent-Length: 0,0\r\n"))
                    .startsWith("HTTP/1.1 400"), "joined Content-Length must be rejected");
            assertTrue(request(address,
                    "POST / HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n0\r\n\r\n")
                    .startsWith("HTTP/1.1 400"), "TE/CL disagreement must be rejected");
            assertTrue(request(address,
                    "POST / HTTP/1.1\r\nHost: localhost\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n0\r\n\r\n")
                    .startsWith("HTTP/1.1 200"), "single valid chunked body must still work");
            // Content-Length is absent: the quota must be enforced on decoded
            // streamed bytes, not only on advertised body length.
            String chunkedPrefix = "POST / HTTP/1.1\r\nHost: localhost\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
            assertTrue(request(address, chunkedPrefix + "400\r\n"
                    + "x".repeat(1024) + "\r\n0\r\n\r\n").startsWith("HTTP/1.1 200"),
                    "exactly 1024 decoded chunked bytes must be accepted");
            assertTrue(request(address, chunkedPrefix + "401\r\n"
                    + "x".repeat(1025) + "\r\n0\r\n\r\n").startsWith("HTTP/1.1 413"),
                    "1025 decoded chunked bytes must be rejected as typed quota 413");
            assertTrue(request(address,
                    "POST / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + "Z\r\nfoo\r\n0\r\n\r\n").startsWith("HTTP/1.1 400"),
                    "malformed chunk-size must be a client error");
            for (String host : new String[] {
                    "example.test:abc", ":8080", "localhost:65536", "[gggg]",
                    "localhost:", "::1", "example.test%20attacker", "localhost/other",
                    "[2001:::db8]", "[::ffff:999.1.1.1]",
                    "999.1.1.1", "127.1", "127.0.1", "2130706433",
                    "127.000.0.1", "192.0.2.256", "1.2.3.4.5", "127.1:80"
            }) {
                assertTrue(request(address, get("Host: " + host + "\r\n"))
                                .startsWith("HTTP/1.1 400"),
                        "invalid Host must fail on actual socket before guest: " + host);
            }
            for (String host : new String[] {"localhost:8443", "example.test:65535"}) {
                assertTrue(request(address, get("Host: " + host + "\r\n"))
                                .startsWith("HTTP/1.1 200"),
                        "valid Host port rejected by socket admission: " + host);
            }
            assertTrue(request(address, get("Host: localhost\r\n")).startsWith("HTTP/1.1 200"),
                    "the listener must survive all prior malformed requests");
            assertEquals(8, admitted.get(),
                    "four good GETs, two valid chunked, oversized chunked and malformed chunk may pass headers");
            assertEquals(6, successfulBodies.get(),
                    "oversized and malformed chunked requests must not complete body processing");
        } finally {
            server.stop(0);
        }
    }
}
