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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in external CLIENT oracle for actual Atto guest vs ephemeral Redis.
 * All blocking sockets below are benchmark clients; the real Atto server
 * still uses only async NIO2 and its actor-owned Map<Bytes,Bytes>.
 */
final class AttoRedisNativeActorMultiBatchPerfTest {
    private static final int REDIS_PORT = 26379;
    private static final int REPETITIONS = 5;
    private static final int WARM_WINDOWS = 200;
    private static final int MEASURED_WINDOWS = 400;
    private static final int REQUESTS_PER_WINDOW = 16;

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
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

    private static byte[] bulk(byte[] value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("$" + value.length + "\r\n"));
        out.write(value);
        out.write(ascii("\r\n"));
        return out.toByteArray();
    }

    private static byte[] join(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    /** One bounded window, 16 mutation/read commands, no key overlap. */
    private static byte[][] window(int clientId, int index) throws Exception {
        byte[] key = {(byte) clientId, 0, (byte) 0xff, 13, 10};
        byte[] value = new byte[64];
        Arrays.fill(value, (byte) ((index * 13 + clientId * 17) % 251));
        byte[] request = join(
                frame(ascii("SET"), key, value), frame(ascii("GET"), key),
                frame(ascii("DEL"), key), frame(ascii("GET"), key),
                frame(ascii("SET"), key, value), frame(ascii("GET"), key),
                frame(ascii("DEL"), key), frame(ascii("GET"), key));
        byte[] reply = join(
                ascii("+OK\r\n"), bulk(value), ascii(":1\r\n"), ascii("$-1\r\n"),
                ascii("+OK\r\n"), bulk(value), ascii(":1\r\n"), ascii("$-1\r\n"));
        return new byte[][] {join(request, request), join(reply, reply)};
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return s.getLocalPort();
        }
    }

    private static Socket connect(int port) throws Exception {
        Socket client = new Socket("127.0.0.1", port);
        client.setTcpNoDelay(true);
        client.setSoTimeout(20_000);
        return client;
    }

    private static Socket connectGuest(Future<?> guest, int port) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (guest.isDone()) {
                guest.get(2, TimeUnit.SECONDS);
                fail("compiled Atto guest terminated before TCP bind");
            }
            try {
                return connect(port);
            } catch (java.net.ConnectException notYetBound) {
                Thread.sleep(40); // external benchmark CLIENT only
            }
        }
        throw new AssertionError("Atto async NIO listener unavailable");
    }

    private static double measure(Socket[] clients, String label) throws Exception {
        AtomicLong startNanos = new AtomicLong();
        CyclicBarrier warmBarrier = new CyclicBarrier(clients.length,
                () -> startNanos.set(System.nanoTime()));
        try (ExecutorService pool = Executors.newFixedThreadPool(clients.length)) {
            ArrayList<Future<Long>> results = new ArrayList<>();
            for (int client = 0; client < clients.length; client++) {
                final int id = client;
                Callable<Long> run = () -> {
                    for (int w = 0; w < WARM_WINDOWS + MEASURED_WINDOWS; w++) {
                        if (w == WARM_WINDOWS) warmBarrier.await(25, TimeUnit.SECONDS);
                        byte[][] pair = window(id, w);
                        clients[id].getOutputStream().write(pair[0]);
                        byte[] answer = clients[id].getInputStream().readNBytes(pair[1].length);
                        assertArrayEquals(pair[1], answer,
                                "wire oracle must validate every pipelined reply client="
                                        + id + " window=" + w);
                    }
                    return System.nanoTime();
                };
                results.add(pool.submit(run));
            }
            long ended = 0;
            for (Future<Long> result : results) {
                ended = Math.max(ended, result.get(150, TimeUnit.SECONDS));
            }
            assertTrue(startNanos.get() > 0 && ended > startNanos.get());
            long requests = (long) clients.length * MEASURED_WINDOWS * REQUESTS_PER_WINDOW;
            double rate = requests / ((ended - startNanos.get()) * 1e-9);
            System.out.printf("ATTO_MULTI_PIPELINE label=%s clients=%d depth=16 "
                            + "warm_per_client=%d measured_per_client=%d rate=%.1f "
                            + "oracle=PASS profiler=OFF%n",
                    label, clients.length, WARM_WINDOWS * REQUESTS_PER_WINDOW,
                    MEASURED_WINDOWS * REQUESTS_PER_WINDOW, rate);
            return rate;
        }
    }

    private static double runAtto(int clientCount, int trial, boolean window)
            throws Exception {
        String filename = clientCount == 8
                ? (window ? "redis_native_multiclient_actor_batches_8.atto"
                        : "redis_native_multiclient_window.atto")
                : (window ? "redis_native_multiclient_actor_batches_32.atto"
                        : "redis_native_multiclient_window_32.atto");
        String program;
        try (InputStream in = AttoRedisNativeActorMultiBatchPerfTest.class
                .getResourceAsStream("/atto/redis-native-nio/" + filename)) {
            assertNotNull(in, "missing actual Atto guest");
            program = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String bind = "network.listen_async(\"127.0.0.1\", 16399)";
        int port = freePort();
        assertTrue(program.contains(bind));
        program = program.replace(bind,
                "network.listen_async(\"127.0.0.1\", " + port + ")");
        OresCompiler.parseAndTypeCheck(program);
        final String compiled = program;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, compiled, filename)
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            Socket[] clients = new Socket[clientCount];
            try {
                for (int i = 0; i < clientCount; i++) {
                    clients[i] = connectGuest(guest, port);
                }
                double ops = measure(clients, (window ? "actor_batch_" : "per_command_window_")
                        + clientCount + "_trial_" + trial);
                for (Socket client : clients) {
                    client.getOutputStream().write(frame(ascii("QUIT")));
                    assertArrayEquals(ascii("+OK\r\n"), client.getInputStream().readNBytes(5));
                    assertEquals(-1, client.getInputStream().read());
                }
                guest.get(30, TimeUnit.SECONDS);
                return ops;
            } finally {
                for (Socket client : clients) if (client != null) client.close();
            }
        }
    }

    private static double runRedis(int count, int trial) throws Exception {
        Socket[] clients = new Socket[count];
        try {
            for (int i = 0; i < count; i++) clients[i] = connect(REDIS_PORT);
            return measure(clients, "redis_" + count + "_trial_" + trial);
        } finally {
            for (Socket client : clients) if (client != null) client.close();
        }
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    /**
     * Five ABBA-style paired real-guest tests. Within each repetition both
     * variants run on the SAME host, against the SAME strict binary RESP2
     * oracle and network window. The order alternates to reduce JIT/drift.
     * Official Redis has no persistence and is measured alongside them.
     */
    /**
     * Independent mailbox intake is preserved in both actual Atto guests.
     * Only the per-client admission window (1 vs up to 16) changes.
     * Run five alternated real-guest pairs against the same strict oracle.
     */
    @Test
    @Timeout(value = 1_800, unit = TimeUnit.SECONDS)
    void compareOwnedActorMicrobatchesAgainstPerCommandMultiClientWindow()
            throws Exception {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("ATTO_REDIS_MULTI_BATCH_PERF_ENABLED")),
                "requires opt-in matching GraalVM and ephemeral Redis benchmark");
        for (int clients : new int[] {8, 32}) {
            double[] parallel = new double[REPETITIONS];
            double[] window = new double[REPETITIONS];
            double[] redis = new double[REPETITIONS];
            for (int trial = 0; trial < REPETITIONS; trial++) {
                if ((trial & 1) == 0) {
                    parallel[trial] = runAtto(clients, trial, false);
                    window[trial] = runAtto(clients, trial, true);
                    redis[trial] = runRedis(clients, trial);
                } else {
                    redis[trial] = runRedis(clients, trial);
                    window[trial] = runAtto(clients, trial, true);
                    parallel[trial] = runAtto(clients, trial, false);
                }
            }
            double baseMedian = median(parallel);
            double windowMedian = median(window);
            double redisMedian = median(redis);
            System.out.printf("ATTO_ACTOR_MULTI_BATCH_COMPARE clients=%d depth=16 repetitions=5 "
                            +  "per_command_median=%.1f actor_batch_median=%.1f speedup=%.4f "
                            +  "redis_median=%.1f actor_batch_over_redis=%.4f "
                            + "oracle=PASS profiler=OFF%n",
                    clients, baseMedian, windowMedian, windowMedian / baseMedian,
                    redisMedian, windowMedian / redisMedian);
        }
    }
}
