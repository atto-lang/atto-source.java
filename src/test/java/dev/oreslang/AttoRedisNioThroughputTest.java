package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance diagnostic with real Atto actor + NIO2 guest, not Java-owned KV.
 * All blocking sockets/stream reads in THIS class are benchmark CLIENT operations,
 * and must never be copied into Atto guest or AsyncNioTcp runtime code.
 *
 * Set ATTO_REDIS_PERF_ENABLED=1 and run an ephemeral official Redis server at
 * 127.0.0.1:26379. This is not a production/native-image performance claim.
 */
final class AttoRedisNioThroughputTest {
    private static final int REDIS_PORT = 26379;
    private static final int WARM = 200;
    private static final int MEASURED = 600;
    private static final int TRIALS = 3;
    private static final int KEYSPACE = 64;
    private static final byte[] OK = ascii("+OK\r\n");
    private static final byte[] NIL = ascii("$-1\r\n");

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static String resource(String name) throws Exception {
        try (InputStream input = AttoRedisNioThroughputTest.class.getResourceAsStream(
                "/atto/redis-native-nio/" + name)) {
            assertNotNull(input, "missing benchmark resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return server.getLocalPort();
        }
    }

    private static byte[] frame(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + parts.length + "\r\n"));
        for (byte[] part : parts) {
            out.write(ascii("$" + part.length + "\r\n"));
            out.write(part);
            out.write(ascii("\r\n"));
        }
        return out.toByteArray();
    }

    private static void check(Socket client, byte[] request, byte[] expected,
            String label, int trial, int i) throws Exception {
        client.getOutputStream().write(request);
        byte[] got = client.getInputStream().readNBytes(expected.length);
        if (!Arrays.equals(got, expected)) {
            fail("RESP_ORACLE_FAILURE label=" + label + " trial=" + trial
                    + " request=" + i + " got=" + Arrays.toString(got)
                    + " wanted=" + Arrays.toString(expected));
        }
    }

    private static byte[] bulk(byte[] value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("$" + value.length + "\r\n"));
        out.write(value);
        out.write(ascii("\r\n"));
        return out.toByteArray();
    }

    private static double measure(Socket client, String label, int trial) throws Exception {
        Map<Integer, byte[]> oracle = new HashMap<>();
        long[] nanos = new long[MEASURED];
        long begin = 0;
        for (int i = 0; i < WARM + MEASURED; i++) {
            int kind = i % 10;
            int keySlot = ((i / 10) * 7 + (kind <= 4 ? kind
                    : kind <= 8 ? kind - 5 : 0)) % KEYSPACE;
            byte[] key = new byte[] {(byte) keySlot, (byte) (keySlot * 17)};
            byte[] value = new byte[64];
            Arrays.fill(value, (byte) (i % 251));
            byte[] request;
            byte[] expected;
            if (kind <= 4) {
                request = frame(ascii("SET"), key, value);
                oracle.put(keySlot, value);
                expected = OK;
            } else if (kind <= 8) {
                request = frame(ascii("GET"), key);
                byte[] previous = oracle.get(keySlot);
                expected = previous == null ? NIL : bulk(previous);
            } else {
                request = frame(ascii("DEL"), key);
                expected = ascii(oracle.remove(keySlot) == null ? ":0\r\n" : ":1\r\n");
            }
            if (i == WARM) begin = System.nanoTime();
            long started = System.nanoTime();
            check(client, request, expected, label, trial, i);
            if (i >= WARM) nanos[i - WARM] = System.nanoTime() - started;
        }
        double seconds = (System.nanoTime() - begin) * 1e-9;
        Arrays.sort(nanos);
        double rate = MEASURED / seconds;
        System.out.printf("ATTO_NATIVE_NIO_BENCH label=%s trial=%d checks=%d measured=%d "
                        + "ops_per_sec=%.1f p50_us=%d p95_us=%d p99_us=%d correctness=PASS%n",
                label, trial + 1, WARM + MEASURED, MEASURED, rate,
                nanos[MEASURED * 50 / 100] / 1000,
                nanos[MEASURED * 95 / 100] / 1000,
                nanos[MEASURED * 99 / 100] / 1000);
        // Clear every live key outside the timed interval, for both Redis and Atto.
        for (int keySlot : oracle.keySet()) {
            byte[] key = new byte[] {(byte) keySlot, (byte) (keySlot * 17)};
            check(client, frame(ascii("DEL"), key), ascii(":1\r\n"),
                    label + "_cleanup", trial, keySlot);
        }
        return rate;
    }

    private static double median(double[] data) {
        double[] sorted = data.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double runTrials(Socket client, String label) throws Exception {
        client.setTcpNoDelay(true);
        client.setSoTimeout(20000);
        double[] rates = new double[TRIALS];
        for (int i = 0; i < TRIALS; i++) {
            rates[i] = measure(client, label, i);
        }
        double result = median(rates);
        System.out.printf("ATTO_NATIVE_NIO_MEDIAN label=%s trials=%d ops_per_sec=%.1f%n",
                label, TRIALS, result);
        return result;
    }

    private static Socket awaitGuest(Future<?> guest, int port) throws Exception {
        for (int attempt = 0; attempt < 250; attempt++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("real Atto async-NIO guest exited before TCP acceptance");
            }
            try {
                return new Socket("127.0.0.1", port);
            } catch (java.net.ConnectException starting) {
                Thread.sleep(40); // test client readiness only; NEVER on actor carriers.
            }
        }
        fail("real Atto async-NIO listener did not bind");
        return null;
    }

    private static double runGuest(String filename, String label) throws Exception {
        String code = resource(filename);
        int port = freePort();
        String constant = "network.listen_async(\"127.0.0.1\", 16399)";
        assertEquals(1, code.split(java.util.regex.Pattern.quote(constant), -1).length - 1);
        code = code.replace(constant,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(code);
        final String guestCode = code;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guestCode, filename)
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failed) {
                    throw new RuntimeException(failed);
                }
            });
            double rate;
            try (Socket client = awaitGuest(guest, port)) {
                rate = runTrials(client, label);
                check(client, frame(ascii("QUIT")), OK, label, TRIALS, 0);
                assertEquals(-1, client.getInputStream().read());
            }
            guest.get(30, TimeUnit.SECONDS);
            return rate;
        }
    }

    private static void verifyFramedNioGuest(String filename) throws Exception {
        String code = resource(filename);
        int port = freePort();
        String bind = "network.listen_async(\"127.0.0.1\", 16399)";
        assertTrue(code.contains(bind));
        code = code.replace(bind, "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(code);
        final String guestCode = code;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID,
                                    guestCode, filename)
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (Socket client = awaitGuest(guest, port)) {
                client.setTcpNoDelay(true);
                client.setSoTimeout(20000);
                byte[] key = {0, (byte) 255, 13, 10};
                byte[] data = {(byte) 254, 0, 10, 13, 42};
                ByteArrayOutputStream commands = new ByteArrayOutputStream();
                commands.write(frame(ascii("PING")));
                commands.write(frame(ascii("GET"), key));
                commands.write(frame(ascii("SET"), key, new byte[0]));
                commands.write(frame(ascii("GET"), key));
                commands.write(frame(ascii("SET"), key, data));
                commands.write(frame(ascii("GET"), key));
                commands.write(frame(ascii("DEL"), key));
                commands.write(frame(ascii("DEL"), key));
                commands.write(frame(ascii("GET"), key));
                commands.write(frame(ascii("QUIT")));
                client.getOutputStream().write(commands.toByteArray());
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.write(ascii("+PONG\r\n"));
                expected.write(NIL);
                expected.write(OK);
                expected.write(bulk(new byte[0]));
                expected.write(OK);
                expected.write(bulk(data));
                expected.write(ascii(":1\r\n"));
                expected.write(ascii(":0\r\n"));
                expected.write(NIL);
                expected.write(OK);
                assertArrayEquals(expected.toByteArray(),
                        client.getInputStream().readNBytes(expected.size()),
                        "real framed NIO Atto actor must preserve binary RESP2 without host KV");
                assertEquals(-1, client.getInputStream().read());
            }
            guest.get(15, TimeUnit.SECONDS);
        }
        System.out.println("ATTO_NATIVE_NIO_FRAMED_REAL_ACTOR_BINARY_PIPELINE=PASS fixture=" + filename);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void framedNioFastPathPreservesBinaryEmptyNilAndPipelining() throws Exception {
        verifyFramedNioGuest("redis_actor_nio_fast.atto");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void singleReplyNioActorPreservesBinaryEmptyNilAndPipelining() throws Exception {
        verifyFramedNioGuest("redis_actor_nio_single_reply.atto");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void ownedBytesNioActorPreservesBinaryEmptyNilAndPipelining() throws Exception {
        verifyFramedNioGuest("redis_actor_nio_owned_bytes.atto");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void correlatedOwnedBytesActorPreservesBinaryAndPipelining() throws Exception {
        verifyFramedNioGuest("redis_actor_nio_correlated_bytes.atto");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void correlatedOwnedBytesActorPreserves256RequestPipelineOrdering() throws Exception {
        String filename = "redis_actor_nio_correlated_bytes.atto";
        String code = resource(filename);
        int port = freePort();
        String bind = "network.listen_async(\"127.0.0.1\", 16399)";
        assertTrue(code.contains(bind));
        code = code.replace(bind, "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(code);
        final String guestCode = code;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID,
                                    guestCode, filename)
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (Socket client = awaitGuest(guest, port)) {
                client.setTcpNoDelay(true);
                client.setSoTimeout(30000);
                ByteArrayOutputStream requests = new ByteArrayOutputStream();
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                for (int i = 0; i < 128; i++) {
                    byte[] key = {(byte) i, 0, (byte) 255};
                    byte[] value = {0, (byte) i, (byte) 254, 13, 10};
                    requests.write(frame(ascii("SET"), key, value));
                    expected.write(OK);
                }
                for (int i = 0; i < 128; i++) {
                    byte[] key = {(byte) i, 0, (byte) 255};
                    byte[] value = {0, (byte) i, (byte) 254, 13, 10};
                    requests.write(frame(ascii("GET"), key));
                    expected.write(bulk(value));
                }
                client.getOutputStream().write(requests.toByteArray());
                assertArrayEquals(expected.toByteArray(),
                        client.getInputStream().readNBytes(expected.size()),
                        "256 pipelined typed actor request IDs must preserve output order");
                check(client, frame(ascii("QUIT")), OK, filename, 0, 256);
                assertEquals(-1, client.getInputStream().read());
            }
            guest.get(30, TimeUnit.SECONDS);
        }
        System.out.println("ATTO_CORRELATED_REAL_ACTOR_256_PIPELINE=PASS");
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void compareRealAttoNioBeforeAfterAndOfficialRedisMemoryOnly() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("ATTO_REDIS_PERF_ENABLED")),
                "requires explicit same-runner performance gate and official Redis");
        double baseline = runGuest("redis_binary_resp2_baseline.atto", "atto_nio_baseline");
        double optimized = runGuest("redis_binary_resp2.atto", "atto_nio_fingerprint");
        double framed = runGuest("redis_actor_nio_fast.atto", "atto_nio_framed");
        double singleReply = runGuest("redis_actor_nio_single_reply.atto", "atto_nio_single_reply");
        double ownedBytes = runGuest("redis_actor_nio_owned_bytes.atto", "atto_nio_owned_bytes");
        double redis;
        try (Socket client = new Socket("127.0.0.1", REDIS_PORT)) {
            redis = runTrials(client, "official_redis_no_persistence");
        }
        System.out.printf("ATTO_NATIVE_NIO_COMPARISON baseline=%.1f optimized=%.1f "
                + "framed=%.1f single_reply=%.1f owned_bytes=%.1f redis=%.1f optimized_over_baseline=%.4f "
                + "framed_over_optimized=%.4f single_over_framed=%.4f bytes_over_single=%.4f bytes_over_redis=%.4f%n",
                baseline, optimized, framed, singleReply, ownedBytes, redis, optimized / baseline,
                framed / optimized, singleReply / framed, ownedBytes / singleReply, ownedBytes / redis);
    }

    /**
     * Real Atto Bytes actor running inside optimizing GraalVM plus the exact
     * standalone Rust RESP2 oracle as an external TEST CLIENT. The guest
     * continues to use NIO2 and alone owns its Map<Bytes,Bytes>. This is
     * strictly one-client until reply correlation is implemented.
     */
    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void rustOracleChecksRealOwnedBytesActorAndOfficialRedis() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("ATTO_REDIS_RUST_ORACLE_ENABLED")),
                "requires explicit exact-blob Rust oracle/GraalVM carrier");
        assertEquals("1", System.getenv("ATTO_REQUIRE_OPTIMIZING_TRUFFLE"),
                "certification must forbid interpreter fallback");
        String binary = System.getenv("ATTO_REDIS_RUST_BENCH_BIN");
        assertNotNull(binary, "the exact-source Rust benchmark executable is required");
        assertTrue(new java.io.File(binary).canExecute(), "Rust benchmark is not executable");

        String filename = "redis_actor_nio_owned_bytes.atto";
        String code = resource(filename);
        int port = freePort();
        String bind = "network.listen_async(\"127.0.0.1\", 16399)";
        assertTrue(code.contains(bind));
        code = code.replace(bind,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(code);
        final String guestCode = code;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guestCode, filename)
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            Process benchmark = null;
            try {
                // Unlike a readiness TCP probe, the Rust driver is the FIRST
                // connection. This one-client Atto guest retires on EOF.
                benchmark = new ProcessBuilder(
                        binary,
                        "--rust", "127.0.0.1:" + REDIS_PORT,
                        "--atto", "127.0.0.1:" + port,
                        "--requests", "5000", "--warmup", "2000",
                        "--trials", "1", "--clients", "1", "--pipeline", "1",
                        "--keys", "1024", "--value-bytes", "64", "--seed", "42")
                        .redirectErrorStream(true).start();
                if (!benchmark.waitFor(180, TimeUnit.SECONDS)) {
                    benchmark.destroyForcibly();
                    fail("Rust RESP2 oracle timed out");
                }
                String output = new String(
                        benchmark.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, benchmark.exitValue(), output);
                assertTrue(output.contains("atto_median_ops_per_sec="), output);
                assertTrue(output.contains("rust_median_ops_per_sec="), output);
                assertTrue(output.contains("correctness=PASS"), output);
                assertFalse(output.contains("RESP_ORACLE_FAILURE"), output);
                System.out.println("ATTO_GRAALVM_REAL_RUST_ORACLE=EXECUTED_PASS");
                System.out.println(output);
                guest.get(30, TimeUnit.SECONDS);
            } finally {
                if (benchmark != null && benchmark.isAlive()) benchmark.destroyForcibly();
                if (!guest.isDone()) guest.cancel(true);
            }
        }
    }
}
