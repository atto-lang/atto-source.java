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

/**
 * Compiler-backed real Atto NIO2 server lifecycle. It must recycle a bounded
 * active-socket slot with a new generation, retain the same Atto actor-owned
 * Map, and never deliver one session's response to a different generation.
 */
@Timeout(value = 220, unit = TimeUnit.SECONDS)
final class AttoRedisRecycledSessionsGuestTest {
    private static String source() throws Exception {
        try (InputStream in = AttoRedisRecycledSessionsGuestTest.class
                .getResourceAsStream("/atto/redis-native-nio/redis_recycled_sessions_buffer.atto")) {
            assertNotNull(in, "canonical Atto Redis guest fixture is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static int freePort() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127,0,0,1}))) {
            return listener.getLocalPort();
        }
    }
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] cat(byte[]... bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] b : bytes) out.write(b);
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
    private static void expect(Socket socket, byte[] expected) throws Exception {
        assertArrayEquals(expected,
                socket.getInputStream().readNBytes(expected.length),
                "RESP2 routing, binary data and session ordering");
    }
    private static void quit(Socket socket) throws Exception {
        socket.getOutputStream().write(command("QUIT"));
        expect(socket, ascii("+OK\r\n"));
        assertEquals(-1, socket.getInputStream().read(),
                "QUIT must close and retire this session");
    }
    private static Socket connect(Future<?> guest, int port) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (guest.isDone()) {
                guest.get(2, TimeUnit.SECONDS);
                fail("Atto guest ended before planned connections");
            }
            try {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(20000);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (java.net.ConnectException notBound) {
                Thread.sleep(30);
            }
        }
        throw new AssertionError("Atto guest never bound a loopback listener");
    }
    @FunctionalInterface
    private interface Sessions {
        void run(Future<?> guest, int port) throws Exception;
    }
    private static void execute(int active, int total, Sessions scenario) throws Exception {
        assertTrue(active >= 1 && active <= 100);
        assertTrue(total >= active);
        String fixture = source();
        String portMarker = "network.listen_async(\"127.0.0.1\", 16399)";
        String activeMarker = "const int max_active_clients = 100;";
        String totalMarker = "const int max_total_sessions = 0;";
        for (String marker : List.of(portMarker, activeMarker, totalMarker)) {
            assertEquals(2,
                    fixture.split(java.util.regex.Pattern.quote(marker), -1).length,
                    "the exact guest declaration must occur once: " + marker);
        }
        int port = freePort();
        String guestSource = fixture.replace(portMarker,
                "network.listen_async(\"127.0.0.1\", " + port + ")")
                .replace(activeMarker, "const int max_active_clients = " + active + ";")
                .replace(totalMarker, "const int max_total_sessions = " + total + ";");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guestSource));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context ctx = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    ctx.eval(Source.newBuilder(OresLanguage.ID, guestSource,
                            "atto-redis-recycled-sessions.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                scenario.run(guest, port);
                guest.get(120, TimeUnit.SECONDS);
            } catch (Exception failure) {
                try {
                    guest.get(2, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException guestFailure) {
                    throw new AssertionError("Real Atto guest crashed", guestFailure.getCause());
                } catch (java.util.concurrent.TimeoutException pending) {
                    failure.addSuppressed(pending);
                }
                throw failure;
            }
        }
    }

    @Test
    void sixtyFourSessionsRecycleTwoLeasesAndShareBinaryKeyspace() throws Exception {
        byte[] key = new byte[]{0, (byte)255, 13, 10};
        byte[] value = new byte[]{(byte)128, 0, (byte)254, 10};
        execute(2, 64, (guest, port) -> {
            for (int i = 0; i < 64; i++) {
                try (Socket socket = connect(guest, port)) {
                    if (i == 0) {
                        socket.getOutputStream().write(command("SET", key, value));
                        expect(socket, ascii("+OK\r\n"));
                    } else {
                        socket.getOutputStream().write(command("GET", key));
                        expect(socket, bulk(value));
                    }
                    quit(socket);
                }
            }
        });
        System.out.println("ATTO_REDIS_RECYCLING_TWO_SLOTS_64_SESSIONS=EXECUTED_PASS");
    }

    @Test
    void hundredLiveClientsThenTwentyReplacementConnections() throws Exception {
        byte[] key = new byte[]{0, 13, (byte)255};
        byte[] value = new byte[]{10, (byte)127, (byte)128, 0};
        execute(100, 120, (guest, port) -> {
            List<Socket> sockets = new ArrayList<>();
            try {
                Socket first = connect(guest, port);
                sockets.add(first);
                first.getOutputStream().write(command("SET", key, value));
                expect(first, ascii("+OK\r\n"));
                for (int i = 1; i < 100; i++) sockets.add(connect(guest, port));
                for (Socket socket : sockets) {
                    socket.getOutputStream().write(command("GET", key));
                }
                for (Socket socket : sockets) expect(socket, bulk(value));
                for (int i = 0; i < 20; i++) quit(sockets.get(i));
                for (int i = 0; i < 20; i++) {
                    sockets.get(i).close();
                    sockets.set(i, connect(guest, port));
                }
                for (Socket socket : sockets) {
                    socket.getOutputStream().write(command("GET", key));
                }
                for (Socket socket : sockets) expect(socket, bulk(value));
                for (Socket socket : sockets) quit(socket);
            } finally {
                for (Socket socket : sockets) socket.close();
            }
        });
        System.out.println("ATTO_REDIS_100_LIVE_20_GENERATION_REPLACEMENTS=EXECUTED_PASS");
    }

    @Test
    void oneThousandSequentialConnectionsRecycleEightSlots() throws Exception {
        execute(8, 1000, (guest, port) -> {
            for (int i = 0; i < 1000; i++) {
                try (Socket socket = connect(guest, port)) {
                    socket.getOutputStream().write(command("PING"));
                    expect(socket, ascii("+PONG\r\n"));
                    quit(socket);
                }
            }
        });
        System.out.println("ATTO_REDIS_1000_SEQUENTIAL_CONNECTIONS=EXECUTED_PASS");
    }

    @Test
    void orderlyDisconnectReleasesSlotWithoutKillingOtherClient() throws Exception {
        byte[] key = new byte[]{(byte)255, 0, 1};
        byte[] value = new byte[]{3, 13, 10, 0};
        execute(2, 5, (guest, port) -> {
            try (Socket first = connect(guest, port)) {
                first.getOutputStream().write(command("SET", key, value));
                expect(first, ascii("+OK\r\n"));
                try (Socket second = connect(guest, port)) {
                    first.close(); // no QUIT; NIO2 EOF must still retire slot
                    try (Socket replacement = connect(guest, port)) {
                        replacement.getOutputStream().write(command("GET", key));
                        expect(replacement, bulk(value));
                        quit(replacement);
                    }
                    second.getOutputStream().write(command("GET", key));
                    expect(second, bulk(value));
                    quit(second);
                }
            }
            for (int i = 0; i < 2; i++) {
                try (Socket extra = connect(guest, port)) {
                    extra.getOutputStream().write(command("PING"));
                    expect(extra, ascii("+PONG\r\n"));
                    quit(extra);
                }
            }
        });
        System.out.println("ATTO_REDIS_EOF_DISCONNECT_REPLACEMENT=EXECUTED_PASS");
    }

    @Test
    void invalidChannelPayloadAndBlockingNetworkApiFailCompile() throws Exception {
        String guest = source();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guest));
        String wrong = guest.replace(
                "writech requests, (slot_id, generation, request_id, kind, key, value, replies);",
                "writech requests, \"not an actor request\";");
        assertNotEquals(guest, wrong);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(wrong));
        String blocking = guest.replace("socket.read_buffer_async(1024)", "socket.read_line()");
        assertNotEquals(guest, blocking);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(blocking));
    }

    @Test
    void tcpResetRetiresSlotAndKeepsOtherClientRunning() throws Exception {
        byte[] key = new byte[]{0, (byte)255, 13};
        byte[] value = new byte[]{(byte)128, 0, 10};
        execute(2, 5, (guest, port) -> {
            try (Socket victim = connect(guest, port);
                 Socket survivor = connect(guest, port)) {
                survivor.getOutputStream().write(command("SET", key, value));
                expect(survivor, ascii("+OK\r\n"));
                victim.getOutputStream().write(command("PING"));
                expect(victim, ascii("+PONG\r\n"));
                // SO_LINGER=0 forces an RST instead of the normal FIN EOF path.
                victim.setSoLinger(true, 0);
                victim.close();
                try (Socket replacement = connect(guest, port)) {
                    replacement.getOutputStream().write(command("GET", key));
                    expect(replacement, bulk(value));
                    quit(replacement);
                }
                survivor.getOutputStream().write(command("GET", key));
                expect(survivor, bulk(value));
                quit(survivor);
            }
            for (int i = 0; i < 2; i++) {
                try (Socket extraSession = connect(guest, port)) {
                    extraSession.getOutputStream().write(command("PING"));
                    expect(extraSession, ascii("+PONG\r\n"));
                    quit(extraSession);
                }
            }
        });
        System.out.println("ATTO_REDIS_RESET_RETIREMENT_AND_REPLACEMENT=EXECUTED_PASS");
    }

    @Test
    void idleClientDoesNotStarveThirtyNineReplacementSessions() throws Exception {
        byte[] key = new byte[]{0, 13, (byte)255, 42};
        byte[] value = new byte[]{(byte)128, 0, 10, 7};
        execute(4, 40, (guest, port) -> {
            try (Socket idle = connect(guest, port)) {
                idle.getOutputStream().write(command("SET", key, value));
                expect(idle, ascii("+OK\r\n"));
                // An idle accepted client must not block a bounded cohort
                // of independently scheduled clients or hold the KV actor.
                for (int round = 0; round < 13; round++) {
                    List<Socket> wave = new ArrayList<>();
                    try {
                        for (int i = 0; i < 3; i++) {
                            wave.add(connect(guest, port));
                        }
                        for (Socket socket : wave) {
                            socket.getOutputStream().write(command("GET", key));
                        }
                        for (Socket socket : wave) expect(socket, bulk(value));
                        for (Socket socket : wave) quit(socket);
                    } finally {
                        for (Socket socket : wave) socket.close();
                    }
                }
                quit(idle);
            }
        });
        System.out.println("ATTO_REDIS_IDLE_PEER_39_REPLACEMENTS=EXECUTED_PASS");
    }
}
