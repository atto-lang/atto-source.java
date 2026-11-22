package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Seeded stress proof of the NIO reactor boundary with a real Atto guest.
 *
 * <p>A task-per-connection echo server serves many concurrent clients that
 * write randomly sized chunks with random pauses, half-close, and read to EOF.
 * The test proves:</p>
 * <ul>
 *   <li>every byte is echoed exactly once, in order, per connection;</li>
 *   <li>guest code never executes on an NIO reactor or completion thread: a
 *       sampler inspects their stacks throughout the run and fails on any
 *       guest (Truffle node or call target) frame;</li>
 *   <li>after the guest finishes and its context closes, no NIO reactor or
 *       completion thread is left alive (VM close quiescence).</li>
 * </ul>
 */
final class AttoNioReactorStressGuestTest {

    private static final String SERVER = """
            pub async fnc echo_session(NioConnection socket): void {
              loop {
                val bytes = await socket.read_bytes_async(512);
                if bytes.size == 0 then
                  await socket.close_async();
                  return;
                fi
                await socket.write_bytes_async(bytes);
              }
            }

            pub async routine main(): void {
              val listener = await network.listen_async("127.0.0.1", %d);
              task scope {
                let int accepted = 0;
                loop {
                  if accepted == %d then break; fi
                  val socket = await listener.accept_async();
                  let Future<void> session = task {
                    await echo_session(socket);
                    return;
                  };
                  accepted = accepted + 1;
                }
              }
              await listener.close_async();
              return;
            }
            """;

    private static boolean isNioThread(Thread thread) {
        String name = thread.getName();
        return name.startsWith("ores-nio-reactor-") || name.startsWith("ores-nio-completion-");
    }

    /** A frame that belongs to guest execution rather than host NIO plumbing. */
    private static boolean isGuestFrame(StackTraceElement frame) {
        String type = frame.getClassName();
        return type.startsWith("dev.oreslang.nodes.")
                || type.contains("OptimizedCallTarget")
                || type.startsWith("com.oracle.truffle.api.impl.DefaultCallTarget");
    }

    private static Set<String> liveNioThreads() {
        Set<String> names = ConcurrentHashMap.newKeySet();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && isNioThread(thread)) names.add(thread.getName());
        }
        return names;
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static Socket connect(int port) throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            try {
                return new Socket("127.0.0.1", port);
            } catch (ConnectException starting) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("guest listener never started on " + port);
    }

    /** One client: random chunks and pauses, half-close, read the echo to EOF. */
    private static byte[][] client(int port, long seed) throws Exception {
        Random random = new Random(seed);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        ByteArrayOutputStream echoed = new ByteArrayOutputStream();
        try (Socket socket = connect(port)) {
            socket.setSoTimeout(20_000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            Thread reader = new Thread(() -> {
                try {
                    in.transferTo(echoed);
                } catch (Exception ignored) {
                    // The assertion on the echoed bytes reports any loss.
                }
            }, "nio-stress-reader-" + seed);
            reader.start();
            int chunks = 1 + random.nextInt(8);
            for (int c = 0; c < chunks; c++) {
                byte[] chunk = new byte[1 + random.nextInt(random.nextInt(8) == 0 ? 4000 : 300)];
                random.nextBytes(chunk);
                out.write(chunk);
                out.flush();
                sent.write(chunk);
                if (random.nextInt(3) == 0) Thread.sleep(random.nextInt(4));
            }
            socket.shutdownOutput();
            reader.join(20_000);
        }
        return new byte[][] {sent.toByteArray(), echoed.toByteArray()};
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {11, 2026, 777})
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void concurrentChunkedEchoKeepsGuestOffNioThreadsAndQuiescesOnClose(long seed) throws Exception {
        int port = freePort();
        int connections = 24;
        String source = SERVER.formatted(port, connections);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Set<String> before = liveNioThreads();

        List<String> violations = new CopyOnWriteArrayList<>();
        Set<String> sampledNioThreads = ConcurrentHashMap.newKeySet();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    Thread thread = entry.getKey();
                    if (!isNioThread(thread) || before.contains(thread.getName())) continue;
                    sampledNioThreads.add(thread.getName());
                    for (StackTraceElement frame : entry.getValue()) {
                        if (isGuestFrame(frame)) {
                            violations.add(thread.getName() + " ran guest frame " + frame);
                            break;
                        }
                    }
                }
                Thread.onSpinWait();
            }
        }, "nio-stress-sampler");
        sampler.setDaemon(true);
        sampler.start();

        try (ExecutorService guest = Executors.newSingleThreadExecutor();
             ExecutorService clients = Executors.newFixedThreadPool(connections)) {
            Future<?> execution = guest.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=NETWORK"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, source, "atto-nio-reactor-stress.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            List<Future<byte[][]>> results = new ArrayList<>();
            for (int i = 0; i < connections; i++) {
                long clientSeed = seed * 1_000 + i;
                results.add(clients.submit(() -> client(port, clientSeed)));
            }
            for (int i = 0; i < connections; i++) {
                byte[][] exchange = results.get(i).get(60, TimeUnit.SECONDS);
                assertArrayEquals(exchange[0], exchange[1],
                        "seed " + seed + " client " + i + ": echo must be exact, in order, exactly once ("
                                + exchange[0].length + " sent, " + exchange[1].length + " echoed)");
            }
            execution.get(60, TimeUnit.SECONDS);
        } finally {
            sampling.set(false);
            sampler.join(5_000);
        }

        assertEquals(List.of(), violations, "guest code must never run on NIO threads");
        assertTrue(sampledNioThreads.stream().anyMatch(n -> n.startsWith("ores-nio-completion-")),
                "the sampler must have observed the VM's completion carriers: " + sampledNioThreads);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<String> leaked;
        do {
            leaked = liveNioThreads();
            leaked.removeAll(before);
            if (leaked.isEmpty()) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals(Set.of(), leaked, "closing the guest context retires every NIO thread it started");
    }
}
