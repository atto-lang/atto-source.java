package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class WebSocketRawUpgradeTest {
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String EXPECTED_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    private static String head(String host, String origin) {
        return "GET /echo HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Key: " + KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + (origin == null ? "" : "Origin: " + origin + "\r\n")
                + "\r\n";
    }

    private static String readResponseHead(Socket client) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        client.setSoTimeout(2_000);
        while (raw.size() < 4096) {
            int next = client.getInputStream().read();
            if (next < 0) break;
            raw.write(next);
            byte[] b = raw.toByteArray();
            int n = b.length;
            if (n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n'
                    && b[n - 2] == '\r' && b[n - 1] == '\n') break;
        }
        return raw.toString(StandardCharsets.US_ASCII);
    }

    @Test
    void exactLoopbackUpgradeYields101AndAdmitsMaskedDataFrame() throws Exception {
        try (var listener = new ServerSocket(0, 2,
                     InetAddress.getByName("127.0.0.1"));
             var client = new Socket("127.0.0.1", listener.getLocalPort());
             var accepted = listener.accept();
             var workers = Executors.newSingleThreadExecutor()) {
            String host = "127.0.0.1:" + listener.getLocalPort();
            String origin = "http://" + host;
            var work = workers.submit(() -> {
                var admitted = WebSocketRawUpgrade.upgrade(
                        accepted, host, Set.of(origin),
                        new WebSocketRawUpgrade.Limits(4096, 16, 2_000));
                var frame = WebSocketFrameCodec.readClientFrame(
                        accepted.getInputStream(), 1024);
                return new Object[] {admitted, frame};
            });
            client.getOutputStream().write(
                    head(host, origin).getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            String response = readResponseHead(client);
            assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
            assertTrue(response.contains(
                    "Sec-WebSocket-Accept: " + EXPECTED_ACCEPT + "\r\n"));
            // Masked client frame: FIN+text, masked len=2, XOR key 01 02 03 04.
            client.getOutputStream().write(new byte[] {
                    (byte) 0x81, (byte) 0x82, 1, 2, 3, 4,
                    (byte) ('o' ^ 1), (byte) ('k' ^ 2)
            });
            client.getOutputStream().flush();
            Object[] result = work.get(3, TimeUnit.SECONDS);
            var admission = (WebSocketRawUpgrade.Admission) result[0];
            var frame = (WebSocketFrameCodec.Frame) result[1];
            assertEquals("/echo", admission.target());
            assertEquals(origin, admission.origin());
            assertEquals(WebSocketFrameCodec.OPCODE_TEXT, frame.opcode());
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), frame.payload());
            assertFalse(accepted.isClosed());
        }
    }

    @Test
    void rejectsHostMismatchAndClosesSocketWithout101() throws Exception {
        assertRefused(host -> head("evil.example.test", "http://" + host));
    }

    @Test
    void rejectsMissingAndForgedOriginsAndNeverUpgrades() throws Exception {
        assertRefused(host -> head(host, null));
        assertRefused(host -> head(host, "http://evil.example.test"));
    }

    @Test
    void rejectsMalformedUpgradeAndOpeningBodies() throws Exception {
        assertRefused(host -> head(host, "http://" + host)
                .replace("Upgrade: websocket", "Upgrade: h2c"));
        assertRefused(host -> head(host, "http://" + host)
                .replace("\r\n\r\n", "\r\nContent-Length: 1\r\n\r\n"));
    }

    @Test
    void silentOpeningHeadHitsFiniteDeadlineAndClosesSocket() throws Exception {
        try (var listener = new ServerSocket(0, 2,
                     InetAddress.getByName("127.0.0.1"));
             var client = new Socket("127.0.0.1", listener.getLocalPort());
             var accepted = listener.accept();
             var workers = Executors.newSingleThreadExecutor()) {
            String host = "127.0.0.1:" + listener.getLocalPort();
            var work = workers.submit(() -> WebSocketRawUpgrade.upgrade(
                    accepted, host, Set.of("http://" + host),
                    new WebSocketRawUpgrade.Limits(4096, 16, 250)));
            client.getOutputStream().write("GET /".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> work.get(2, TimeUnit.SECONDS));
            assertInstanceOf(SocketTimeoutException.class, failed.getCause());
            assertTrue(accepted.isClosed());
        }
    }

    @Test
    void badHandshakeQuotaIsRejectedBeforeReading() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketRawUpgrade.Limits(255, 16, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketRawUpgrade.Limits(4096, 0, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketRawUpgrade.Limits(4096, 16, 31_000));
    }

    private static void assertRefused(java.util.function.Function<String, String> request)
            throws Exception {
        try (var listener = new ServerSocket(0, 2,
                     InetAddress.getByName("127.0.0.1"));
             var client = new Socket("127.0.0.1", listener.getLocalPort());
             var accepted = listener.accept();
             var workers = Executors.newSingleThreadExecutor()) {
            String host = "127.0.0.1:" + listener.getLocalPort();
            var work = workers.submit(() -> WebSocketRawUpgrade.upgrade(
                    accepted, host, Set.of("http://" + host),
                    new WebSocketRawUpgrade.Limits(4096, 16, 1_000)));
            client.getOutputStream().write(
                    request.apply(host).getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThrows(ExecutionException.class, () -> work.get(2, TimeUnit.SECONDS));
            assertTrue(accepted.isClosed(), "rejected connection must be sealed");
            assertEquals("", readResponseHead(client),
                    "denied handshake must not emit HTTP 101 or untrusted header text");
        }
    }
}
