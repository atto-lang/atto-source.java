package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real Atto 2/16/100 live sockets, shared actor-owned binary KV and bounded broker. */
@Timeout(value = 150, unit = TimeUnit.SECONDS)
final class AttoRedisBoundedClientsGuestTest {
    private static String source() throws Exception {
        try (InputStream in = AttoRedisBoundedClientsGuestTest.class
                .getResourceAsStream("/atto/redis-native-nio/redis_bounded_clients_buffer.atto")) {
            assertNotNull(in, "exact canonical Atto source required");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return s.getLocalPort();
        }
    }
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] cat(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
    private static byte[] bulk(byte[] value) throws Exception {
        return cat(ascii("$" + value.length + "\r\n"), value, ascii("\r\n"));
    }
    private static byte[] command(String op, byte[]... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + (args.length + 1) + "\r\n"));
        out.write(bulk(ascii(op)));
        for (byte[] arg : args) out.write(bulk(arg));
        return out.toByteArray();
    }
    private static Socket connect(Future<?> server, int port) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (server.isDone()) {
                server.get(2, TimeUnit.SECONDS);
                fail("Atto guest ended before expected TCP sessions");
            }
            try {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(15000);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (java.net.ConnectException notReady) {
                Thread.sleep(40);
            }
        }
        throw new AssertionError("Atto server did not bind loopback");
    }
    private static void expect(Socket socket, byte[] reply) throws Exception {
        assertArrayEquals(reply, socket.getInputStream().readNBytes(reply.length),
                "RESP2 reply identity/ordering and bytes");
    }
    private static void runGuest(int count) throws Exception {
        String original = source();
        String portMarker = "network.listen_async(\"127.0.0.1\", 16399)";
        String countMarker = "const int max_clients = 100;";
        assertEquals(2, original.split(java.util.regex.Pattern.quote(portMarker), -1).length);
        assertEquals(2, original.split(java.util.regex.Pattern.quote(countMarker), -1).length);
        int port = freePort();
        String guest = original.replace(portMarker,
                "network.listen_async(\"127.0.0.1\", " + port + ")")
                .replace(countMarker, "const int max_clients = " + count + ";");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guest));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guest,
                            "atto-redis-" + count + "-clients.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            List<Socket> sockets = new ArrayList<>();
            byte[] key = new byte[]{0, (byte) 255, 13, 10};
            byte[] value = new byte[]{(byte) 128, 0, (byte) 254, 10, 13};
            try {
                Socket first = connect(running, port);
                sockets.add(first);
                // First client must make progress before the other connections exist.
                first.getOutputStream().write(command("SET", key, value));
                expect(first, ascii("+OK\r\n"));
                first.getOutputStream().write(command("GET", key));
                expect(first, bulk(value));
                for (int i = 1; i < count; i++) sockets.add(connect(running, port));
                // Every socket remains live while the same actor-owned map is queried.
                for (Socket socket : sockets) {
                    socket.getOutputStream().write(command("GET", key));
                }
                for (Socket socket : sockets) expect(socket, bulk(value));
                for (Socket socket : sockets) {
                    socket.getOutputStream().write(command("QUIT"));
                }
                for (Socket socket : sockets) {
                    expect(socket, ascii("+OK\r\n"));
                    assertEquals(-1, socket.getInputStream().read(), "QUIT closes session");
                }
                running.get(40, TimeUnit.SECONDS);
            } catch (Exception wireFailure) {
                try {
                    running.get(2, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException actualGuestFailure) {
                    throw new AssertionError("Real bounded-client Atto guest failed",
                            actualGuestFailure.getCause());
                } catch (java.util.concurrent.TimeoutException pending) {
                    wireFailure.addSuppressed(pending);
                }
                throw wireFailure;
            } finally {
                for (Socket socket : sockets) socket.close();
            }
        }
        System.out.println("ATTO_REDIS_LIVE_SHARED_ACTOR_CLIENTS=" + count + " EXECUTED_PASS");
    }
    @Test void twoLiveClients() throws Exception { runGuest(2); }
    @Test void sixteenLiveClients() throws Exception { runGuest(16); }
    @Test void hundredLiveClients() throws Exception { runGuest(100); }
    @Test void invalidWireAndBlockingSocketCompileRejected() throws Exception {
        String original = source();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(original));
        String wrongPayload = original.replace(
                "writech requests, (client_id, request_id, kind, key, value, replies);",
                "writech requests, \"invalid\";");
        assertNotEquals(original, wrongPayload);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(wrongPayload));
        String blockingSocket = original.replace(
                "socket.read_buffer_async(1024)", "socket.read_line()");
        assertNotEquals(original, blockingSocket);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(blockingSocket));
    }

    @Test void nestedReplyChannelRejectsNonSendRcPayload() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc reject_nested(
                            Channel<Tuple[int, Channel<Rc<int>>]> requests
                        ): void {
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("task-safe"), failure::getMessage);
    }
}
