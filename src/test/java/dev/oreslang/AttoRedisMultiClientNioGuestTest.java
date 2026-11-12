package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 * Compiled Atto actor owns one Map<Bytes,Bytes>; real NIO2 gateway serves
 * 100 concurrent TCP clients. All JDK Socket usage is TEST CLIENT ONLY.
 * This is an ephemeral, finite-acceptance correctness gate, not a benchmark.
 */
final class AttoRedisMultiClientNioGuestTest {
    private static final int CLIENTS = Integer.getInteger("atto.redis.clients", 100);
    private static final String RESOURCE =
            "/atto/redis-native-nio/redis_actor_nio_multiclient_tagged.atto";

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] cat(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] item : parts) out.write(item);
        return out.toByteArray();
    }

    private static byte[] bulk(byte[] data) throws Exception {
        return cat(ascii("$" + data.length + "\r\n"), data, ascii("\r\n"));
    }

    private static byte[] command(byte[]... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + args.length + "\r\n"));
        for (byte[] value : args) out.write(bulk(value));
        return out.toByteArray();
    }

    private static int freePort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return server.getLocalPort();
        }
    }

    private static String boundGuest(int port) throws Exception {
        String source;
        try (InputStream in = AttoRedisMultiClientNioGuestTest.class
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "exact tagged multi-client Atto fixture must exist");
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String pinned = "network.listen_async(\"127.0.0.1\", 16399)";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(pinned), -1).length - 1);
        assertTrue(source.contains("serve_resp2_async(listener, 100, 100)"));
        String accept = "serve_resp2_async(listener, 100, 100)";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(accept), -1).length - 1);
        return source.replace(pinned,
                "network.listen_async(\"127.0.0.1\", " + port + ")")
                .replace(accept, "serve_resp2_async(listener, "
                        + CLIENTS + ", " + CLIENTS + ")");
    }

    private static Socket connect(Future<?> guest, int port) throws Exception {
        Exception recent = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("guest finished before all clients were accepted");
            }
            try {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 1800);
                    socket.setSoTimeout(25000);
                    socket.setTcpNoDelay(true);
                    return socket;
                } catch (Exception failed) {
                    socket.close();
                    throw failed;
                }
            } catch (java.io.IOException notYetBound) {
                recent = notYetBound;
                Thread.sleep(30); // test client only, not actor/NIO2 runtime
            }
        }
        throw new AssertionError("NIO2 listener did not accept client", recent);
    }

    private static void checkedClient(Future<?> guest, int port, int ordinal)
            throws Exception {
        // Distinct arbitrary binary keys and values. Every reply is compared
        // byte-for-byte against a per-client RESP2 oracle.
        byte[] key = new byte[] {(byte) ordinal, 0, (byte) 255, 13, 10};
        byte[] value = new byte[] {(byte) (ordinal ^ 0xa5), 0, 13, 10, (byte) 255};
        try (Socket socket = connect(guest, port)) {
            socket.getOutputStream().write(cat(
                    command(ascii("SET"), key, value),
                    command(ascii("GET"), key),
                    command(ascii("DEL"), key),
                    command(ascii("GET"), key),
                    command(ascii("QUIT"))));
            socket.getOutputStream().flush();
            byte[] expected = cat(ascii("+OK\r\n"), bulk(value),
                    ascii(":1\r\n"), ascii("$-1\r\n"), ascii("+OK\r\n"));
            byte[] actual = socket.getInputStream().readNBytes(expected.length);
            assertArrayEquals(expected, actual,
                    "binary oracle/client reply misrouting at client #" + ordinal);
            assertEquals(-1, socket.getInputStream().read(),
                    "QUIT must retire this client without stopping shared KV actor");
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void oneRealAttoMapServesHundredConcurrentBinaryClientsDespiteIdlePeer()
            throws Exception {
        int port = freePort();
        String program = boundGuest(port);
        assertTrue(CLIENTS >= 2 && CLIENTS <= 100,
                "concurrent Atto regression test accepts 2..100 clients");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        java.util.concurrent.atomic.AtomicReference<Context> activeContext =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Test executors must never hang the JUnit worker on close() after a
        // failed guest: blocking ExecutorService.close() can wait indefinitely.
        // Daemon threads are test-only; no server actor or NIO thread is changed.
        ExecutorService guestExecutor = Executors.newSingleThreadExecutor(job -> {
            Thread thread = new Thread(job, "atto-redis-e2e-guest");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService clientsExecutor = Executors.newFixedThreadPool(CLIENTS, job -> {
            Thread thread = new Thread(job, "atto-redis-e2e-client");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> guest = guestExecutor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    activeContext.set(context);
                    try {
                        context.eval(Source.newBuilder(OresLanguage.ID, program,
                                    "redis_actor_nio_multiclient_tagged.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                    } finally {
                        activeContext.compareAndSet(context, null);
                    }
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });

            CountDownLatch letIdleQuit = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger completedFast =
                    new java.util.concurrent.atomic.AtomicInteger();
            try {
            // One peer connects first and remains idle: it must not block
            // the other 99 clients from the same Atto-owned Map.
            CountDownLatch idleConnected = new CountDownLatch(1);
            Future<?> idle = clientsExecutor.submit(() -> {
                try (Socket socket = connect(guest, port)) {
                    idleConnected.countDown();
                    if (!letIdleQuit.await(70, TimeUnit.SECONDS)) {
                        throw new AssertionError("fast clients were starved by idle client");
                    }
                    socket.getOutputStream().write(command(ascii("QUIT")));
                    assertArrayEquals(ascii("+OK\r\n"),
                            socket.getInputStream().readNBytes(5));
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            assertTrue(idleConnected.await(15, TimeUnit.SECONDS));
            List<Future<?>> fast = new ArrayList<>();
            for (int i = 0; i < CLIENTS - 1; i++) {
                final int index = i;
                fast.add(clientsExecutor.submit(() -> {
                    try {
                        checkedClient(guest, port, index);
                        completedFast.incrementAndGet();
                    } catch (Exception error) {
                        System.err.println("ATTO_MULTI_CLIENT_FAIL index=" + index
                                + " cause=" + error.getClass().getSimpleName());
                        throw new RuntimeException(error);
                    }
                }));
            }
            try {
                for (Future<?> future : fast) future.get(90, TimeUnit.SECONDS);
            } finally {
                letIdleQuit.countDown();
            }
            idle.get(25, TimeUnit.SECONDS);
            guest.get(45, TimeUnit.SECONDS);
            } finally {
                letIdleQuit.countDown();
                System.out.println("ATTO_MULTI_CLIENT_PROGRESS completed_fast="
                        + completedFast.get() + " target=" + (CLIENTS - 1));
                // This context close is emergency TEST teardown only.
                // Never wait on it from JUnit if the guest is uncooperative.
                if (!guest.isDone()) {
                    Context active = activeContext.get();
                    if (active != null) {
                        Thread emergency = new Thread(() -> {
                            try { active.close(true); }
                            catch (RuntimeException alreadyClosing) { }
                        }, "atto-guest-emergency-close");
                        emergency.setDaemon(true);
                        emergency.start();
                    }
                    guest.cancel(true);
                }
            }
        } finally {
            clientsExecutor.shutdownNow();
            guestExecutor.shutdownNow();
        }
        System.out.println("ATTO_REDIS_REAL_TCP_CLIENTS_ONE_ATTO_MAP=" + CLIENTS
                + "_EXECUTED_PASS");
    }

    @Test void gatewayRefusesActorWithoutTaggedBinaryStreamOperator() {
        String invalid = """
                define actor Untagged implements StreamOperator<Tuple[int,Bytes,Bytes],Tuple[string,Bytes]> as
                  receive(ActorMail<Tuple[int,Bytes,Bytes]> mail): void {
                    self.send(("OK", mail.value[2]));
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Untagged();
                  val listener = await network.listen_async("127.0.0.1", 16399);
                  await worker.serve_resp2_async(listener, 100, 100);
                  return;
                }
                """;
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(invalid),
                "gateway cannot reinterpret an untagged actor output stream");
        assertTrue(error.getMessage().contains(
                "serve_resp2_async requires StreamOperator"), error::getMessage);
    }
}
