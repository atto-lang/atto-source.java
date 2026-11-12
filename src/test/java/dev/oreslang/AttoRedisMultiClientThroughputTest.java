package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 * Opt-in real Atto Redis concurrent throughput comparison. Java only supplies
 * correctness-checking TCP clients and measurements; the guest owns every Map
 * access inside one actual Atto actor. All server I/O is callback-driven NIO2.
 *
 * The timing of each pipelined request is measured from the batch submission
 * until that request's full RESP2 response is consumed. Server and client share
 * the CI host; report hardware/CPU contention rather than production capacity.
 */
final class AttoRedisMultiClientThroughputTest {
    private static final int CLIENTS =
            Integer.getInteger("atto.redis.multi.clients", 8);
    private static final int PIPELINE =
            Integer.getInteger("atto.redis.multi.pipeline", 1);
    private static final int WARM =
            Integer.getInteger("atto.redis.multi.warm", 80);
    private static final int MEASURED =
            Integer.getInteger("atto.redis.multi.measured", 160);
    private record ClientResult(long completedAt, long[] latencies) { }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    private static String guest() throws Exception {
        try (InputStream in = AttoRedisMultiClientThroughputTest.class
                .getResourceAsStream("/atto/redis-native-nio/redis_bounded_clients_buffer.atto")) {
            assertNotNull(in, "real guest source must be pinned");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bulk(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("$" + bytes.length + "\r\n"));
        out.write(bytes);
        out.write(ascii("\r\n"));
        return out.toByteArray();
    }

    private static byte[] command(String method, byte[]... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + (args.length + 1) + "\r\n"));
        out.write(bulk(ascii(method)));
        for (byte[] arg : args) out.write(bulk(arg));
        return out.toByteArray();
    }

    private static void expect(Socket socket, byte[] value, String context)
            throws Exception {
        byte[] actual = socket.getInputStream().readNBytes(value.length);
        assertArrayEquals(value, actual, context);
    }

    private static Socket connect(Future<?> guest, int port) throws Exception {
        for (int trial = 0; trial < 150; trial++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("real Atto guest quit before listening");
            }
            try {
                Socket connection = new Socket("127.0.0.1", port);
                connection.setSoTimeout(20000);
                connection.setTcpNoDelay(true);
                return connection;
            } catch (java.net.ConnectException notStarted) {
                Thread.sleep(40); // benchmark CLIENT bootstrap, not server I/O
            }
        }
        throw new AssertionError("real Atto guest did not listen");
    }

    private static byte[] value(int client, int operation) {
        return new byte[]{
                (byte) 255, 0, (byte) client, (byte) (operation & 255), 13, 10
        };
    }

    private static void runOperations(Socket socket, int clientId,
            int count, int pipelineDepth, long[] latencies) throws Exception {
        byte[] key = new byte[]{(byte) 234, (byte) clientId, 0};
        for (int i = 0; i < count; i += pipelineDepth) {
            int size = Math.min(pipelineDepth, count - i);
            ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
            byte[][] expected = new byte[size][];
            for (int k = 0; k < size; k++) {
                int operation = i + k;
                if ((operation & 1) == 0) {
                    pipeline.write(command("SET", key, value(clientId, operation)));
                    expected[k] = ascii("+OK\r\n");
                } else {
                    pipeline.write(command("GET", key));
                    expected[k] = bulk(value(clientId, operation - 1));
                }
            }
            long submitted = System.nanoTime();
            socket.getOutputStream().write(pipeline.toByteArray());
            for (int k = 0; k < size; k++) {
                expect(socket, expected[k],
                        "real Atto reply routing client=" + clientId
                        + " request=" + (i + k) + " depth=" + pipelineDepth);
                if (latencies != null) {
                    latencies[i + k] = System.nanoTime() - submitted;
                }
            }
        }
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0, Math.min(sorted.length - 1,
                (int) Math.ceil(sorted.length * fraction) - 1))];
    }

    @Test
    @Timeout(value = 420, unit = TimeUnit.SECONDS)
    void realAttoConcurrentMeasuredThroughputWithFullRespOracle() throws Exception {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("ATTO_REDIS_MULTI_PERF_ENABLED")),
                "opt-in exact-source multi-client benchmark only");
        assertTrue(CLIENTS == 8 || CLIENTS == 32);
        assertTrue(PIPELINE == 1 || PIPELINE == 8 || PIPELINE == 32);
        assertTrue(WARM >= 20 && WARM <= 2000 && (WARM % 2) == 0);
        assertTrue(MEASURED >= 40 && MEASURED <= 3000 && (MEASURED % 2) == 0);

        int port = freePort();
        String code = guest();
        String bind = "network.listen_async(\"127.0.0.1\", 16399)";
        String cohort = "const int max_clients = 100;";
        assertEquals(1, code.split(java.util.regex.Pattern.quote(bind), -1).length - 1);
        assertEquals(1, code.split(java.util.regex.Pattern.quote(cohort), -1).length - 1);
        code = code.replace(bind,
                "network.listen_async(\"127.0.0.1\", " + port + ")")
                .replace(cohort, "const int max_clients = " + CLIENTS + ";");
        OresCompiler.parseAndTypeCheck(code);
        final String guestSource = code;

        try (ExecutorService workers = Executors.newFixedThreadPool(CLIENTS + 1)) {
            Future<?> guestFuture = workers.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, guestSource,
                            "atto-redis-concurrent-throughput.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });

            List<Socket> clients = new ArrayList<>();
            try {
                for (int i = 0; i < CLIENTS; i++) {
                    clients.add(connect(guestFuture, port));
                }
                CountDownLatch beginWarm = new CountDownLatch(1);
                CountDownLatch warmDone = new CountDownLatch(CLIENTS);
                CountDownLatch beginMeasurement = new CountDownLatch(1);
                List<Future<ClientResult>> runs = new ArrayList<>();
                for (int i = 0; i < CLIENTS; i++) {
                    final int clientIndex = i;
                    final Socket socket = clients.get(i);
                    runs.add(workers.submit(() -> {
                        beginWarm.await();
                        try {
                            runOperations(socket, clientIndex, WARM, PIPELINE, null);
                        } finally {
                            warmDone.countDown();
                        }
                        beginMeasurement.await();
                        long[] latency = new long[MEASURED];
                        runOperations(socket, clientIndex, MEASURED, PIPELINE, latency);
                        long finishedAt = System.nanoTime();
                        socket.getOutputStream().write(command("QUIT"));
                        expect(socket, ascii("+OK\r\n"), "real guest QUIT");
                        assertEquals(-1, socket.getInputStream().read(),
                                "Atto must retire this exact connection");
                        return new ClientResult(finishedAt, latency);
                    }));
                }
                beginWarm.countDown();
                assertTrue(warmDone.await(100, TimeUnit.SECONDS),
                        "all client warmups must drain before measurement");
                long start = System.nanoTime();
                beginMeasurement.countDown();
                long end = start;
                long[] samples = new long[CLIENTS * MEASURED];
                int off = 0;
                for (Future<ClientResult> task : runs) {
                    ClientResult result = task.get(160, TimeUnit.SECONDS);
                    end = Math.max(end, result.completedAt());
                    System.arraycopy(result.latencies(), 0, samples, off, MEASURED);
                    off += MEASURED;
                }
                guestFuture.get(40, TimeUnit.SECONDS);
                assertEquals(samples.length, off);
                Arrays.sort(samples);
                double ops = (CLIENTS * (double) MEASURED) * 1e9 / (end - start);
                assertTrue(Double.isFinite(ops) && ops > 0);
                System.out.printf("ATTO_MULTI_CLIENT_THROUGHPUT=PASS clients=%d pipeline=%d "
                        + "warm_per_client=%d measured_per_client=%d commands=%d "
                        + "ops_per_sec=%.1f p50_us=%d p95_us=%d p99_us=%d "
                        + "binary_oracle=PASS reply_order=PASS single_atto_map=TRUE%n",
                        CLIENTS, PIPELINE, WARM, MEASURED, samples.length,
                        ops, percentile(samples, .5) / 1000,
                        percentile(samples, .95) / 1000,
                        percentile(samples, .99) / 1000);
            } finally {
                for (Socket client : clients) client.close();
            }
        }
    }
}
