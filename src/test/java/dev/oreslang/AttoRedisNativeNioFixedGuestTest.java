package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

/** Executes the exact Redis-repo .atto byte-prefix RESP2 actor on genuine async NIO2. */
final class AttoRedisNativeNioFixedGuestTest {
    private static final String RESOURCE = "/atto/redis-native-nio/redis_native_nio_fixed.atto";
    private static final int PORT = 16398;

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void attoOwnsKvAndParsesFragmentedAndPipelinedRespOnAsyncNio() throws Exception {
        String code;
        try (InputStream file = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(file, "must mirror canonical Redis .atto guest");
            code = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code),
                "actual Atto compiler must accept in-Atto RESP grammar and actor");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, code,
                                    "redis_native_nio_fixed.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            try (Socket client = connect(guest)) {
                client.setSoTimeout(7000);
                client.setTcpNoDelay(true);
                // Split a RESP command in the middle of its bulk header; all
                // framing/dispatch lives in Atto, not the host test client.
                client.getOutputStream().write("*1\r\n$4".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                client.getOutputStream().write("\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                assertFrame(client, "+PONG\r\n", "fragmented PING");
                String stream = "*2\r\n$3\r\nGET\r\n$6\r\nanswer\r\n"
                        + "*3\r\n$3\r\nSET\r\n$6\r\nanswer\r\n$2\r\n41\r\n"
                        + "*2\r\n$3\r\nGET\r\n$6\r\nanswer\r\n"
                        + "*2\r\n$3\r\nDEL\r\n$6\r\nanswer\r\n"
                        + "*2\r\n$3\r\nDEL\r\n$6\r\nanswer\r\n"
                        + "*2\r\n$3\r\nGET\r\n$6\r\nanswer\r\n"
                        + "*1\r\n$4\r\nQUIT\r\n";
                client.getOutputStream().write(stream.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                assertFrame(client,
                        "$-1\r\n+OK\r\n$2\r\n41\r\n:1\r\n:0\r\n$-1\r\n+OK\r\n",
                        "pipelined SET/GET/DEL owned by Atto actor");
                assertEquals(-1, client.getInputStream().read(), "QUIT must close");
            }
            guest.get(15, TimeUnit.SECONDS);
        }
        System.out.println("ATTO_REDIS_NATIVE_ASYNC_NIO_KV_GUEST=EXECUTED_PASS");
    }

    private static Socket connect(Future<?> guest) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("Atto async guest returned before a connection");
            }
            try {
                return new Socket("127.0.0.1", PORT);
            } catch (java.net.ConnectException notReady) {
                Thread.sleep(40);
            }
        }
        fail("Atto native NIO listener did not start");
        return null;
    }

    private static void assertFrame(Socket client, String expected, String description)
            throws Exception {
        byte[] want = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] got = client.getInputStream().readNBytes(want.length);
        assertArrayEquals(want, got, description);
    }
}
