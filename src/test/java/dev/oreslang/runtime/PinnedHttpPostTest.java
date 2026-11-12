package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real pinned NIO-selector POST wire test, not a guest/OTLP export proof. */
@Timeout(25)
final class PinnedHttpPostTest {
    @Test
    void postWritesOneExactBoundedBinaryBodyAndTransportOwnedLength() throws Exception {
        byte[] payload = new byte[] {0, 1, 13, 10, 34, 92, (byte) 255};
        AtomicReference<String> requestHead = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream wire = socket.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    int tail = 0;
                    while (true) {
                        int c = wire.read();
                        if (c < 0) throw new AssertionError("POST header EOF");
                        head.write(c);
                        tail = ((tail << 8) | c);
                        if (head.size() > 32768 + 1024) {
                            throw new AssertionError("POST header unbounded");
                        }
                        if (tail == 0x0d0a0d0a) break;
                    }
                    requestHead.set(head.toString(StandardCharsets.ISO_8859_1));
                    received.set(wire.readNBytes(payload.length));
                    socket.getOutputStream().write((
                            "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                }
            });
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    host -> List.of(InetAddress.getByName("127.0.0.1")),
                    SSLContext.getDefault(), 4096)) {
                try (var response = transport.request(
                        URI.create("http://origin.example:" + server.getLocalPort() + "/v1/traces"),
                        "POST", List.of(Map.entry("Content-Type", "application/json")),
                        payload)) {
                    assertEquals(202, response.status());
                    assertEquals(0, response.streamBody(new ByteArrayOutputStream(),
                            () -> false));
                }
            }
            peer.join(8000);
            assertFalse(peer.isAlive(), "server must have observed complete POST");
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertArrayEquals(payload, received.get());
            String head = requestHead.get();
            assertTrue(head.startsWith("POST /v1/traces HTTP/1.1\r\n"), head);
            assertTrue(head.contains("Content-Length: 7\r\n"), head);
            assertEquals(1, head.split("Content-Length:", -1).length - 1);
            assertTrue(head.contains("Content-Type: application/json\r\n"), head);
        }
    }

    @Test
    void rejectsUnsupportedBodiesBeforeNetworkOrBorrowingResources() throws Exception {
        try (PinnedHttpTransport transport = new PinnedHttpTransport(
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                host -> { throw new AssertionError("invalid request must not resolve"); },
                SSLContext.getDefault(), 4096)) {
            URI target = URI.create("http://127.0.0.1:9/v1/traces");
            assertThrows(IllegalArgumentException.class,
                    () -> transport.request(target, "GET", List.of(), new byte[] {1}));
            assertThrows(IllegalArgumentException.class,
                    () -> transport.request(target, "PUT", List.of(), new byte[] {1}));
            assertThrows(IllegalArgumentException.class,
                    () -> transport.request(target, "POST", List.of(),
                            new byte[PinnedHttpTransport.MAX_REQUEST_BODY_BYTES + 1]));
            assertThrows(IllegalArgumentException.class,
                    () -> transport.request(target, "POST",
                            List.of(Map.entry("Content-Length", "0")), new byte[0]));
            assertEquals(0, transport.activeSockets());
        }
    }
}
