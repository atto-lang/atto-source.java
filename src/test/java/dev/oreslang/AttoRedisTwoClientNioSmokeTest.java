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

/** Focused two-connection, one-actor binary Redis smoke before 100-client soak. */
final class AttoRedisTwoClientNioSmokeTest {
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] frame(byte[]... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + args.length + "\r\n"));
        for (byte[] arg : args) {
            out.write(ascii("$" + arg.length + "\r\n"));
            out.write(arg);
            out.write(ascii("\r\n"));
        }
        return out.toByteArray();
    }

    private static byte[] bulk(byte[] body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("$" + body.length + "\r\n"));
        out.write(body);
        out.write(ascii("\r\n"));
        return out.toByteArray();
    }

    private static void exchange(Socket socket, byte[] request, byte[] expected)
            throws Exception {
        socket.getOutputStream().write(request);
        socket.getOutputStream().flush();
        assertArrayEquals(expected, socket.getInputStream().readNBytes(expected.length));
    }

    private static Socket connect(Future<?> guest, int port) throws Exception {
        for (int i = 0; i < 150; i++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("Atto actor guest terminated before accepting two clients");
            }
            try {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(12000);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (java.net.ConnectException startup) {
                Thread.sleep(30); // test-only client, never server actor
            }
        }
        fail("two-client NIO2 gateway never bound");
        return null;
    }

    @Test
    @Timeout(value = 70, unit = TimeUnit.SECONDS)
    void twoTcpClientsSeeTheSameActorOwnedBinaryMap() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            port = probe.getLocalPort();
        }
        String source;
        try (InputStream in = getClass().getResourceAsStream(
                "/atto/redis-native-nio/redis_actor_nio_multiclient_tagged.atto")) {
            assertNotNull(in);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(1, source.split("serve_resp2_async", -1).length - 1);
        source = source.replace("serve_resp2_async(listener, 100, 100)",
                "serve_resp2_async(listener, 2, 2)");
        source = source.replace("network.listen_async(\"127.0.0.1\", 16399)",
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        final String guestCode = source;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guestCode));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guestCode,
                            "redis-two-client-gateway.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (Socket a = connect(guest, port); Socket b = connect(guest, port)) {
                byte[] key = new byte[] {0, (byte) 255, 13, 10};
                byte[] data = new byte[] {(byte) 254, 0, 42, 13, 10};
                exchange(a, frame(ascii("SET"), key, data), ascii("+OK\r\n"));
                // Different TCP socket must see the Atto actor's state, not
                // a Java HashMap or per-connection mock store.
                exchange(b, frame(ascii("GET"), key), bulk(data));
                exchange(a, frame(ascii("DEL"), key), ascii(":1\r\n"));
                exchange(b, frame(ascii("GET"), key), ascii("$-1\r\n"));
                exchange(a, frame(ascii("QUIT")), ascii("+OK\r\n"));
                exchange(b, frame(ascii("QUIT")), ascii("+OK\r\n"));
                assertEquals(-1, a.getInputStream().read());
                assertEquals(-1, b.getInputStream().read());
            }
            guest.get(18, TimeUnit.SECONDS);
        }
        System.out.println("ATTO_REDIS_TWO_REAL_CLIENTS_SHARED_ACTOR_KV=EXECUTED_PASS");
    }
}
