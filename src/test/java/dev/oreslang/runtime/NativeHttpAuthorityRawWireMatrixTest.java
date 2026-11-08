package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional RFC 9112 Host-authority and typed request-framing tests on the
 * pending Java #2659 transport hardening tree. These use a real JDK HTTP
 * listener, not the AttoLang guest or an independent full NativeHttp runtime.
 */
@Timeout(40)
final class NativeHttpAuthorityRawWireMatrixTest {
    private static String status(InetSocketAddress address, String packet) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(address, 2_000);
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(
                    packet.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = reader.readLine();
            assertNotNull(line, "the wire must yield an HTTP status line");
            String field;
            do {
                field = reader.readLine();
                assertNotNull(field, "HTTP response header terminator required");
            } while (!field.isEmpty());
            int remaining = 0;
            while (reader.read() >= 0) {
                assertTrue(++remaining <= 65_536, "bounded rejection body required");
            }
            return line;
        }
    }

    private static String get(String hostBlock) {
        return "GET / HTTP/1.1\r\n" + hostBlock
                + "Connection: close\r\n\r\n";
    }

    @Test
    void authorityGrammarAndDuplicateHostsAreEnforcedOnTheRawWire() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 16);
        AtomicInteger admitted = new AtomicInteger();
        server.createContext("/", request -> {
            int httpStatus;
            try {
                NativeHttp.validateHeadersForAdmission(
                        request.getRequestHeaders(), request.getProtocol(), 1024);
                admitted.incrementAndGet();
                httpStatus = 200;
            } catch (IllegalArgumentException badRequest) {
                httpStatus = 400;
            }
            try {
                request.sendResponseHeaders(httpStatus, -1);
            } finally {
                request.close();
            }
        });
        server.start();
        try {
            InetSocketAddress address = server.getAddress();
            for (String host : new String[] {
                    "example.test:abc", "example.test:", "example.test:65536",
                    "2001:db8::1", "[::1", "[::1]:x",
                    "user@example.test", "example.test#fragment",
                    "example.test/evil", "example.test,attacker.test"
            }) {
                assertTrue(status(address,get("Host: "+host+"\r\n"))
                        .startsWith("HTTP/1.1 400"),
                        "must reject malformed authority: " + host);
            }
            assertTrue(status(address,get(
                    "Host: example.test\r\nhOsT: attacker.test\r\n"))
                    .startsWith("HTTP/1.1 400"),
                    "case-insensitive duplicate Host cannot bypass admission");
            for (String host : new String[] {
                    "example.test", "example.test:80",
                    "example.test:65535", "[::1]", "[::1]:8080"
            }) {
                assertTrue(status(address,get("Host: "+host+"\r\n"))
                        .startsWith("HTTP/1.1 200"),
                        "valid canonical authority must remain accepted: " + host);
            }
            assertEquals(5, admitted.get(),
                    "invalid raw-wire authority must never reach dispatch");
            assertTrue(status(address,get("Host: example.test\r\n"))
                    .startsWith("HTTP/1.1 200"), "listener survives rejected requests");
            assertEquals(6, admitted.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseExceptionsCannotForgeAnInbound413Quota() {
        assertEquals(413, NativeHttp.operationFailureStatus(
                new NativeHttp.RequestBodyTooLargeException(), true));
        assertEquals(500, NativeHttp.operationFailureStatus(
                new NativeHttp.RequestBodyTooLargeException(), false),
                "response-side quota-like error is not request admission");
        assertEquals(500, NativeHttp.operationFailureStatus(
                new IOException("body too large"), false),
                "message-only response failures cannot impersonate transport quota");
        assertEquals(400, NativeHttp.operationFailureStatus(
                new IOException("invalid hexadecimal chunk-size"), true));
        assertEquals(500, NativeHttp.operationFailureStatus(
                new IllegalStateException("body too large"), true),
                "untrusted exception messages must never classify HTTP 413");
    }
}
