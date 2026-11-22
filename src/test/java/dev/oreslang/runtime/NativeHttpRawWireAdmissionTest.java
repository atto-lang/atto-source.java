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
                exchange.getRequestBody().readAllBytes();
                successfulBodies.incrementAndGet();
                status = 200;
            } catch (IllegalArgumentException badRequest) {
                status = 400;
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
                    "Host:   \r\n")).startsWith("HTTP/1.1 400"),
                    "blank Host must fail closed");
            assertTrue(request(address, get(
                    "X-Case: value\r\n")).startsWith("HTTP/1.1 400"),
                    "HTTP/1.1 without Host must fail closed");
            assertTrue(request(address, get(
                    "Host: localhost\r\nX-Long: " + "x".repeat(12_000) + "\r\n"))
                    .startsWith("HTTP/1.1 400"), "individual 12 KiB header must be rejected");
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
            assertTrue(request(address,
                    "POST / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + "Z\r\nfoo\r\n0\r\n\r\n").startsWith("HTTP/1.1 400"),
                    "malformed chunk-size must be a client error");
            assertTrue(request(address, get("Host: localhost\r\n")).startsWith("HTTP/1.1 200"),
                    "the listener must survive all prior malformed requests");
            assertEquals(4, admitted.get(),
                    "only two good GETs, valid chunked, and malformed chunk body may pass header admission");
            assertEquals(3, successfulBodies.get(),
                    "malformed chunk must never complete body processing");
        } finally {
            server.stop(0);
        }
    }
}
