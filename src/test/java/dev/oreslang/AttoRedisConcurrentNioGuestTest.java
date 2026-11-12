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
 * Executes real compiled Atto actors and asynchronous NIO2 sockets, never a
 * Java substitute for KV state. Concurrent clients use one actor-owned Map.
 * All blocking Socket calls below are TEST CLIENT I/O, not guest implementation.
 */
final class AttoRedisConcurrentNioGuestTest {
    private static final int CLIENTS = 8;
    private static final byte[] OK = ascii("+OK\r\n");
    private static final byte[] NIL = ascii("$-1\r\n");

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String resource() throws Exception {
        try (InputStream in = AttoRedisConcurrentNioGuestTest.class.getResourceAsStream(
                "/atto/redis-native-nio/redis_actor_nio_multiclient.atto")) {
            assertNotNull(in, "missing real Atto concurrent guest");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return probe.getLocalPort();
        }
    }

    private static byte[] frame(byte[]... args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ascii("*" + args.length + "\r\n"));
        for (byte[] arg : args) {
            output.write(ascii("$" + arg.length + "\r\n"));
            output.write(arg);
            output.write(ascii("\r\n"));
        }
        return output.toByteArray();
    }

    private static byte[] bulk(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ascii("$" + bytes.length + "\r\n"));
        output.write(bytes);
        output.write(ascii("\r\n"));
        return output.toByteArray();
    }

    private static void expect(Socket socket, byte[] bytes, String label) throws Exception {
        byte[] actual = socket.getInputStream().readNBytes(bytes.length);
        assertArrayEquals(bytes, actual, label);
    }

    private static void request(Socket socket, byte[] expected, String label,
            byte[]... args) throws Exception {
        socket.getOutputStream().write(frame(args));
        expect(socket, expected, label);
    }

    private static Socket awaitGuest(Future<?> guest, int port) throws Exception {
        for (int retry = 0; retry < 250; retry++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("concurrent Atto guest exited before listener readiness");
            }
            try {
                return new Socket("127.0.0.1", port);
            } catch (java.net.ConnectException starting) {
                Thread.sleep(40); // readiness probe in TEST CLIENT only
            }
        }
        fail("concurrent Atto guest failed to bind TCP listener");
        return null;
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realAttoActorSharesOneMapAcrossEightAsyncTcpClients() throws Exception {
        String source = resource();
        int port = unusedPort();
        String bind = "network.listen_async(\"127.0.0.1\", 16401)";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(bind), -1).length - 1);
        source = source.replace(bind,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(source);

        final String guestCode = source;
        try (ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> guest = runner.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guestCode,
                            "redis_actor_nio_multiclient.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failed) {
                    throw new RuntimeException(failed);
                }
            });
            Socket[] clients = new Socket[CLIENTS];
            try {
                clients[0] = awaitGuest(guest, port);
                for (int i = 1; i < CLIENTS; i++) {
                    clients[i] = new Socket("127.0.0.1", port);
                }
                for (Socket client : clients) {
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(15000);
                }
                byte[] shared = {0, (byte) 255, 10, 13, 42};
                byte[] data = {(byte) 254, 0, 10, 13, (byte) 255};
                request(clients[0], OK, "client0 shared SET", ascii("SET"), shared, data);
                request(clients[1], bulk(data), "client1 must observe client0 write",
                        ascii("GET"), shared);
                request(clients[2], ascii(":1\r\n"), "client2 deletes shared key",
                        ascii("DEL"), shared);
                request(clients[3], NIL, "client3 observes cross-client delete",
                        ascii("GET"), shared);

                // All clients pipeline SET and GET without awaiting between them.
                // Unique keys and binary payloads identify any misrouted reply.
                byte[][] keys = new byte[CLIENTS][];
                byte[][] values = new byte[CLIENTS][];
                for (int i = 0; i < CLIENTS; i++) {
                    keys[i] = new byte[]{(byte) i, (byte) 255, 0, 10};
                    values[i] = new byte[]{(byte) (50 + i), 0, (byte) 254, 13, 10};
                    ByteArrayOutputStream batch = new ByteArrayOutputStream();
                    batch.write(frame(ascii("SET"), keys[i], values[i]));
                    batch.write(frame(ascii("GET"), keys[i]));
                    clients[i].getOutputStream().write(batch.toByteArray());
                }
                for (int i = 0; i < CLIENTS; i++) {
                    expect(clients[i], OK, "pipelined SET routed to client " + i);
                    expect(clients[i], bulk(values[i]),
                            "pipelined binary GET routed to client " + i);
                }
                for (int i = 0; i < CLIENTS; i++) {
                    int another = (i + 1) % CLIENTS;
                    request(clients[i], bulk(values[another]),
                            "client " + i + " sees another client's key",
                            ascii("GET"), keys[another]);
                }
                for (int i = 0; i < CLIENTS; i++) {
                    request(clients[i], OK, "QUIT for client " + i, ascii("QUIT"));
                    assertEquals(-1, clients[i].getInputStream().read(),
                            "client " + i + " socket must close after QUIT");
                }
            } finally {
                for (Socket client : clients) {
                    if (client != null) client.close();
                }
            }
            guest.get(25, TimeUnit.SECONDS);
        }
        System.out.println("ATTO_REDIS_SHARED_STORE_EIGHT_CLIENTS=PASS "
                + "clients=8 pipelined=YES cross_client_visibility=YES "
                + "binary_resp2=YES guest_kv_actor=ONE nio2_async=YES");
    }
}
