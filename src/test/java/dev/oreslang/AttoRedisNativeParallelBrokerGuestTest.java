package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * Actual native Atto guest, one actor-owned Map<Bytes,Bytes>, 8 real TCP
 * sessions, one single-consumer Atto reply broker and per-session channels.
 * Blocking Socket reads are CLIENT-ONLY in this JUnit oracle.
 */
final class AttoRedisNativeParallelBrokerGuestTest {
    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] resp(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + parts.length + "\r\n"));
        for (byte[] part : parts) {
            out.write(ascii("$" + part.length + "\r\n"));
            out.write(part);
            out.write(ascii("\r\n"));
        }
        return out.toByteArray();
    }

    private static byte[] bulk(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("$" + bytes.length + "\r\n"));
        out.write(bytes);
        out.write(ascii("\r\n"));
        return out.toByteArray();
    }

    private static void roundTrip(Socket client, byte[] request, byte[] expected)
            throws Exception {
        client.getOutputStream().write(request);
        assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length),
                "exact response oracle including NUL, CR/LF and invalid UTF-8");
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return probe.getLocalPort();
        }
    }

    private static Socket connect(Future<?> guest, int port) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("Atto guest exited before all client sessions connected");
            }
            try {
                Socket client = new Socket("127.0.0.1", port);
                client.setTcpNoDelay(true);
                client.setSoTimeout(15_000);
                return client;
            } catch (java.net.ConnectException notReady) {
                Thread.sleep(40); // JUnit CLIENT readiness only
            }
        }
        fail("Atto listener never started");
        return null;
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void eightConcurrentSessionsShareAttoMapAndNeverCrossRouteReplies()
            throws Exception {
        String fixture = "/atto/redis-native-nio/redis_native_multiclient_parallel.atto";
        String program;
        try (InputStream stream = getClass().getResourceAsStream(fixture)) {
            assertNotNull(stream, "missing real Atto guest");
            program = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String bind = "network.listen_async(\"127.0.0.1\", 16399)";
        assertEquals(1, program.split(java.util.regex.Pattern.quote(bind), -1).length - 1);
        int port = freePort();
        program = program.replace(bind,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(program);
        final String guestCode = program;
        try (ExecutorService guestExecutor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = guestExecutor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID, new String[] {
                                "--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guestCode, fixture)
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failed) {
                    throw new RuntimeException(failed);
                }
            });

            Socket[] sessions = new Socket[8];
            try {
                sessions[0] = connect(guest, port);
                // First session must not be blocked on accepting the eighth.
                roundTrip(sessions[0], resp(ascii("PING")), ascii("+PONG\r\n"));
                for (int i = 1; i < sessions.length; i++) {
                    sessions[i] = connect(guest, port);
                }
                byte[] sharedKey = {0, (byte) 0xff, 13, 10};
                byte[] sharedValue = {(byte) 0xfe, 0, 13, 10, 7, 42};
                roundTrip(sessions[0], resp(ascii("SET"), sharedKey, sharedValue),
                        ascii("+OK\r\n"));
                roundTrip(sessions[7], resp(ascii("GET"), sharedKey), bulk(sharedValue));

                try (ExecutorService concurrent = Executors.newFixedThreadPool(8)) {
                    @SuppressWarnings("unchecked")
                    Future<?>[] tasks = new Future<?>[8];
                    for (int clientId = 0; clientId < 8; clientId++) {
                        final int index = clientId;
                        tasks[index] = concurrent.submit(() -> {
                            try {
                                byte[] key = {(byte) index, 0, (byte) 0xa0, 13};
                                byte[] value = new byte[64 + index];
                                Arrays.fill(value, (byte) (index * 29 + 1));
                                ByteArrayOutputStream requests = new ByteArrayOutputStream();
                                requests.write(resp(ascii("SET"), key, value));
                                requests.write(resp(ascii("GET"), key));
                                requests.write(resp(ascii("DEL"), key));
                                sessions[index].getOutputStream().write(requests.toByteArray());
                                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                                expected.write(ascii("+OK\r\n"));
                                expected.write(bulk(value));
                                expected.write(ascii(":1\r\n"));
                                assertArrayEquals(expected.toByteArray(),
                                        sessions[index].getInputStream()
                                                .readNBytes(expected.size()),
                                        "client " + index + " must receive only its own replies");
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        });
                    }
                    for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
                }
                roundTrip(sessions[3], resp(ascii("DEL"), sharedKey), ascii(":1\r\n"));
                roundTrip(sessions[1], resp(ascii("GET"), sharedKey), ascii("$-1\r\n"));
                for (Socket session : sessions) {
                    roundTrip(session, resp(ascii("QUIT")), ascii("+OK\r\n"));
                    assertEquals(-1, session.getInputStream().read());
                }
                guest.get(20, TimeUnit.SECONDS);
                System.out.println("ATTO_NATIVE_8_CLIENTS_PARALLEL_ROUTING_SHARED_MAP=PASS");
            } finally {
                for (Socket session : sessions) {
                    if (session != null) session.close();
                }
            }
        }
    }

    @Test
    void rejectsWrongTypedMailboxAndBlockingSocketMutation() throws Exception {
        String program;
        try (InputStream stream = getClass().getResourceAsStream(
                "/atto/redis-native-nio/redis_native_multiclient_parallel.atto")) {
            assertNotNull(stream);
            program = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String admitted = "writech requests, (client_id, next_id, kind, frame[2], frame[3]);";
        assertTrue(program.contains(admitted));
        String wrongType = program.replace(admitted, "writech requests, \"invalid\";");
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(wrongType),
                "typed Channel<Tuple<int,int,int,Bytes,Bytes>> must reject String");
        String selector = "socket.read_resp2_binary_batch_async(16)";
        assertTrue(program.contains(selector));
        String blocking = program.replace(selector, "socket.read_line()");
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(blocking),
                "native Atto session must reject synchronous socket APIs");
    }

    @Test
    void rejectsReuseOfAlreadyCapturedAffineSocket() throws Exception {
        String program;
        try (InputStream stream = getClass().getResourceAsStream(
                "/atto/redis-native-nio/redis_native_multiclient_parallel.atto")) {
            assertNotNull(stream);
            program = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String original = "await serve_tagged_session(socket_2, 2, requests, reply_2);";
        assertTrue(program.contains(original));
        String duplicate = program.replace(original,
                "await serve_tagged_session(first, 2, requests, reply_2);");
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(duplicate),
                "same owned NIO socket must not be captured by two independent tasks");
    }
}
