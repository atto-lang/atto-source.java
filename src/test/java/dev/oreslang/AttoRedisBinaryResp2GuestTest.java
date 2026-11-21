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
 * Real compiler + Truffle actor execution of canonical Redis-repo .atto:
 * RESP2 incremental framing, typed tuple command/reply mail, binary owned KV and
 * a real completion-driven NIO TCP listener. Java is only a test client.
 */
final class AttoRedisBinaryResp2GuestTest {
    private static final String RESOURCE =
            "/atto/redis-native-nio/redis_binary_resp2.atto";

    private static String source() throws Exception {
        try (InputStream resource =
                AttoRedisBinaryResp2GuestTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(resource, "pin the exact Redis .atto source");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] cat(byte[]... chunks) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }

    private static byte[] bulk(byte[] bytes) throws Exception {
        return cat(ascii("$" + bytes.length + "\r\n"), bytes, ascii("\r\n"));
    }

    private static byte[] command(byte[]... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + args.length + "\r\n"));
        for (byte[] arg : args) out.write(bulk(arg));
        return out.toByteArray();
    }

    private static byte[] command(String name, byte[]... args) throws Exception {
        byte[][] values = new byte[args.length + 1][];
        values[0] = ascii(name);
        System.arraycopy(args, 0, values, 1, args.length);
        return command(values);
    }

    private static Socket connect(Future<?> guest, int port) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("Atto guest exited before accepting a client");
            }
            try {
                Socket client = new Socket("127.0.0.1", port);
                client.setSoTimeout(9000);
                client.setTcpNoDelay(true);
                return client;
            } catch (java.net.ConnectException notYetBound) {
                Thread.sleep(40);
            }
        }
        fail("real native NIO Atto guest never bound loopback socket");
        return null;
    }

    private static String boundSource(int port) throws Exception {
        String source = source();
        String needle = "network.listen_async(\"127.0.0.1\", 16399)";
        assertEquals(1, source.split(
                java.util.regex.Pattern.quote(needle), -1).length - 1,
                "only one approved source port substitution");
        return source.replace(needle,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
    }

    private static void runGuest(Scenario scenario) throws Exception {
        int port = freePort();
        String code = boundSource(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code),
                "compiler must admit full Atto-native RESP2, actors, typed mail");
        try (ExecutorService execution = Executors.newSingleThreadExecutor()) {
            Future<?> guest = execution.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, code,
                                    "redis_binary_resp2.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (Socket client = connect(guest, port)) {
                scenario.verify(client);
            }
            guest.get(15, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void verify(Socket client) throws Exception;
    }

    private static void expect(Socket socket, byte[] reply, String reason)
            throws Exception {
        byte[] got = socket.getInputStream().readNBytes(reply.length);
        assertArrayEquals(reply, got, reason);
    }

    private static void assertClosed(Socket socket) throws Exception {
        assertEquals(-1, socket.getInputStream().read(),
                "guest must close malformed or QUIT connection");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void binaryKeyValueAndEmptyValueAcrossFragmentedPipelinedActorCommands()
            throws Exception {
        byte[] key = new byte[] {0, (byte) 255, 13, 10, 36, 0};
        byte[] value = new byte[] {(byte) 254, 0, 10, 13, 42, 36, 0};
        runGuest(client -> {
            // Socket receives a deliberately fragmented multibyte RESP header.
            client.getOutputStream().write(ascii("*1\r\n$4\r"));
            client.getOutputStream().flush();
            client.getOutputStream().write(ascii("\nPiNg\r\n"));
            expect(client, ascii("+PONG\r\n"), "fragmented case-insensitive PING");

            byte[] pipeline = cat(
                    command("GET", key),               // missing
                    command("sEt", key, value),        // binary SET
                    command("gEt", key),               // exact raw bytes
                    command("DEL", key),               // one deleted
                    command("DEL", key),               // repeat delete
                    command("GET", key),               // still missing
                    command("SET", key, new byte[0]),  // empty value is not nil
                    command("GET", key),               // $0\r\n\r\n
                    command("FOO"),                    // error, remains connected
                    command("QUIT"));
            client.getOutputStream().write(pipeline);
            client.getOutputStream().flush();
            expect(client, cat(
                    ascii("$-1\r\n"),
                    ascii("+OK\r\n"),
                    bulk(value),
                    ascii(":1\r\n"),
                    ascii(":0\r\n"),
                    ascii("$-1\r\n"),
                    ascii("+OK\r\n"),
                    bulk(new byte[0]),
                    ascii("-ERR unsupported\r\n"),
                    ascii("+OK\r\n")),
                    "pipelined binary roundtrip, tombstone and empty-vs-missing");
            assertClosed(client);
        });
        System.out.println("ATTO_REDIS_BINARY_TYPED_ACTOR_RESP2=EXECUTED_PASS");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rejectOversizedKeyBeforeBodyAllocationAndRetireSocket() throws Exception {
        runGuest(client -> {
            client.getOutputStream().write(ascii(
                    "*2\r\n$3\r\nGET\r\n$4097\r\n"));
            client.getOutputStream().flush();
            expect(client, ascii("-ERR protocol\r\n"),
                    "oversize key header rejected before reading body");
            assertClosed(client);
        });
        System.out.println("ATTO_REDIS_RESP2_OVERSIZE_FAIL_CLOSED=PASS");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rejectNoncanonicalLengthsAndTooManyArguments() throws Exception {
        runGuest(client -> {
            client.getOutputStream().write(ascii("*4\r\n"));
            client.getOutputStream().flush();
            expect(client, ascii("-ERR protocol\r\n"), "max 3 args");
            assertClosed(client);
        });
        runGuest(client -> {
            client.getOutputStream().write(ascii(
                    "*2\r\n$3\r\nGET\r\n$01\r\n"));
            client.getOutputStream().flush();
            expect(client, ascii("-ERR protocol\r\n"), "leading zero forbidden");
            assertClosed(client);
        });
        System.out.println("ATTO_REDIS_RESP2_NONCANONICAL_FAIL_CLOSED=PASS");
    }


    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void distinctBinaryKeysEmptyKeyAndLargeBinaryPayloadSurviveReadFragmentation()
            throws Exception {
        byte[] keyA = new byte[] {0};
        byte[] keyB = new byte[] {0, 0};
        byte[] empty = new byte[0];
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 255);
        }
        runGuest(client -> {
            // Fragmentation is inevitable for a 1024-byte read limit, and
            // responses also require reassembly when they exceed one read.
            client.getOutputStream().write(cat(
                    command("SET", keyA, payload),
                    command("SET", keyB, keyA),
                    command("SET", empty, empty),
                    command("GET", keyA),
                    command("GET", keyB),
                    command("GET", empty),
                    command("DEL", keyA),
                    command("GET", keyB),
                    command("QUIT")));
            client.getOutputStream().flush();
            expect(client, cat(
                    ascii("+OK\r\n"), ascii("+OK\r\n"), ascii("+OK\r\n"),
                    bulk(payload), bulk(keyA), bulk(empty),
                    ascii(":1\r\n"), bulk(keyA), ascii("+OK\r\n")),
                    "binary identity, zero-length key, and 1024-byte bulk length");
            assertClosed(client);
        });
        System.out.println("ATTO_REDIS_BINARY_1024_PAYLOAD_ZERO_KEY=PASS");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rejectNegativeBulkAndIncorrectBulkTrailers() throws Exception {
        runGuest(client -> {
            client.getOutputStream().write(ascii("*2\r\n$3\r\nGET\r\n$-1\r\n"));
            client.getOutputStream().flush();
            expect(client, ascii("-ERR protocol\r\n"), "no null bulk requests");
            assertClosed(client);
        });
        runGuest(client -> {
            client.getOutputStream().write(ascii("*1\r\n$4\r\nPING\rX"));
            client.getOutputStream().flush();
            expect(client, ascii("-ERR protocol\r\n"), "bad bulk trailer");
            assertClosed(client);
        });
        System.out.println("ATTO_REDIS_BINARY_BAD_TRAILER_NEGATIVE_BULK=PASS");
    }

    @Test
    void staticTypesRejectUnsafeActorMailAndSynchronousSocketRegression()
            throws Exception {
        String code = source();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        String typeBreak = code.replace(
                "worker.send((kind, key, value));",
                "worker.send(\"untyped text\");");
        assertNotEquals(code, typeBreak);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(typeBreak),
                "ActorMail<Tuple<int,List<int>,List<int>>> must reject string messages");
        String ioBreak = code.replace("socket.read_bytes_async(1024)",
                "socket.read_line()");
        assertNotEquals(code, ioBreak);
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(ioBreak),
                "async-only socket must reject legacy synchronous read");
    }
}
