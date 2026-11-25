package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
 * Two independently scheduled Atto socket sessions, one shared KV actor, and
 * one Atto-owned broker routing replies through capacity-bounded channels.
 * Java provides only TCP test clients and never parses/stores RESP2 in guest.
 */
@Timeout(value = 80, unit = TimeUnit.SECONDS)
final class AttoRedisTwoClientBufferGuestTest {
    private static String source() throws Exception {
        try (InputStream in = AttoRedisTwoClientBufferGuestTest.class
                .getResourceAsStream("/atto/redis-native-nio/redis_two_client_buffer.atto")) {
            assertNotNull(in, "exact canonical source is required");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return probe.getLocalPort();
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
    private static byte[] bulk(byte[] bytes) throws Exception {
        return cat(ascii("$" + bytes.length + "\r\n"), bytes, ascii("\r\n"));
    }
    private static byte[] command(String operation, byte[]... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + (args.length + 1) + "\r\n"));
        out.write(bulk(ascii(operation)));
        for (byte[] arg : args) out.write(bulk(arg));
        return out.toByteArray();
    }
    private static Socket connect(Future<?> task, int port) throws Exception {
        for (int i = 0; i < 120; i++) {
            if (task.isDone()) {
                task.get(2, TimeUnit.SECONDS);
                fail("Atto server terminated before two clients connected");
            }
            try {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(7000);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (java.net.ConnectException notBound) {
                Thread.sleep(40);
            }
        }
        throw new AssertionError("Atto async server failed to bind");
    }
    private static void expect(Socket socket, byte[] wire) throws Exception {
        assertArrayEquals(wire, socket.getInputStream().readNBytes(wire.length),
                "strict RESP2 wire response");
    }
    private static void assertClosed(Socket socket) throws Exception {
        assertEquals(-1, socket.getInputStream().read(),
                "session should close on QUIT or protocol error");
    }
    @FunctionalInterface
    private interface Scenario {
        void run(Socket first, Socket second) throws Exception;
    }
    @FunctionalInterface
    private interface BeforeSecondConnect {
        void run(Socket first) throws Exception;
    }

    private static void runGuest(Scenario scenario) throws Exception {
        runGuest(null, scenario);
    }

    private static void runGuest(
            BeforeSecondConnect beforeSecond,
            Scenario scenario) throws Exception {
        int port = freePort();
        String original = source();
        String needle = "network.listen_async(\"127.0.0.1\", 16399)";
        assertEquals(1, original.split(java.util.regex.Pattern.quote(needle), -1).length - 1);
        String guest = original.replace(needle,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guest));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> run = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID,
                            guest, "atto-real-redis-2-client.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try {
                try (Socket first = connect(run, port)) {
                    if (beforeSecond != null) {
                        // Must complete an actual RESP2 response before the
                        // second TCP connection exists.
                        beforeSecond.run(first);
                    }
                    try (Socket second = connect(run, port)) {
                        scenario.run(first, second);
                    }
                }
                run.get(15, TimeUnit.SECONDS);
            } catch (Exception wireFailure) {
                // A connection reset often masks the actual failed Atto task.
                // Keep the test fail-closed and report the guest exception.
                try {
                    run.get(2, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException guestFailure) {
                    throw new AssertionError(
                            "Real Atto two-client guest failed before completing RESP2",
                            guestFailure.getCause());
                } catch (java.util.concurrent.TimeoutException pending) {
                    wireFailure.addSuppressed(pending);
                }
                throw wireFailure;
            }
        }
    }

    @Test
    void firstClientReceivesReplyBeforeSecondTcpConnects() throws Exception {
        byte[] key = new byte[]{0, (byte) 255, 10};
        byte[] value = new byte[]{13, 0, (byte) 128, 10};
        runGuest(first -> {
            first.getOutputStream().write(command("SET", key, value));
            expect(first, ascii("+OK\r\n"));
            first.getOutputStream().write(command("GET", key));
            expect(first, bulk(value));
        }, (a, b) -> {
            b.getOutputStream().write(command("GET", key));
            expect(b, bulk(value));
            a.getOutputStream().write(command("QUIT"));
            expect(a, ascii("+OK\r\n"));
            b.getOutputStream().write(command("QUIT"));
            expect(b, ascii("+OK\r\n"));
        });
        System.out.println("ATTO_REDIS_NO_SECOND_ACCEPT_HOL=EXECUTED_PASS");
    }

    @Test
    void bothClientsShareOneBinaryMapAndNoIdlePeerHeadOfLineBlocking()
            throws Exception {
        byte[] key = new byte[]{0, (byte) 255, 13, 10};
        byte[] value = new byte[]{(byte) 255, 0, 44, 13, 10, (byte) 128};
        runGuest((a,b) -> {
            a.getOutputStream().write(command("SET", key, value));
            // B has not sent anything. A must still get an answer.
            expect(a, ascii("+OK\r\n"));
            b.getOutputStream().write(command("GET", key));
            expect(b, bulk(value));
            b.getOutputStream().write(command("DEL", key));
            expect(b, ascii(":1\r\n"));
            a.getOutputStream().write(command("GET", key));
            expect(a, ascii("$-1\r\n"));
            a.getOutputStream().write(command("QUIT"));
            expect(a, ascii("+OK\r\n"));
            assertClosed(a);
            b.getOutputStream().write(command("QUIT"));
            expect(b, ascii("+OK\r\n"));
            assertClosed(b);
        });
        System.out.println("ATTO_REDIS_TWO_CLIENT_SHARED_BINARY_MAP=EXECUTED_PASS");
    }

    @Test
    void pipeliningAndInterleavingRouteResponsesToCorrectClients()
            throws Exception {
        byte[] key = new byte[]{0, 0, (byte) 255};
        byte[] value = new byte[1024];
        for (int i = 0; i < value.length; i++) value[i] = (byte) i;
        runGuest((a,b) -> {
            a.getOutputStream().write(cat(
                    command("PING"), command("SET", key, value),
                    command("GET", key)));
            expect(a, cat(ascii("+PONG\r\n"), ascii("+OK\r\n"), bulk(value)));
            b.getOutputStream().write(cat(
                    command("GET", key), command("DEL", key)));
            expect(b, cat(bulk(value), ascii(":1\r\n")));
            a.getOutputStream().write(command("GET", key));
            expect(a, ascii("$-1\r\n"));
            a.getOutputStream().write(command("QUIT"));
            b.getOutputStream().write(command("QUIT"));
            expect(a, ascii("+OK\r\n"));
            expect(b, ascii("+OK\r\n"));
        });
        System.out.println("ATTO_REDIS_TWO_CLIENT_ROUTING_AND_PIPELINE=EXECUTED_PASS");
    }

    @Test
    void malformedFirstClientDoesNotDestroyOtherClientsSharedActor()
            throws Exception {
        runGuest((a,b) -> {
            a.getOutputStream().write(ascii("*2\r\n$3\r\nGET\r\n$01\r\n"));
            expect(a, ascii("-ERR protocol\r\n"));
            assertClosed(a);
            b.getOutputStream().write(command("PING"));
            expect(b, ascii("+PONG\r\n"));
            b.getOutputStream().write(command("QUIT"));
            expect(b, ascii("+OK\r\n"));
        });
        System.out.println("ATTO_REDIS_TWO_CLIENT_PROTOCOL_ISOLATION=EXECUTED_PASS");
    }

    @Test
    void badChannelPayloadAndSynchronousSocketAreNotCompiled() throws Exception {
        String original = source();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(original));
        String wrongPayload = original.replace(
                "writech requests, (client_id, request_id, kind, key, value);",
                "writech requests, \"wrong payload\";");
        assertNotEquals(original, wrongPayload);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(wrongPayload));
        String blocking = original.replace(
                "socket.read_buffer_async(1024)", "socket.read_line()");
        assertNotEquals(original, blocking);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(blocking));
    }
}
