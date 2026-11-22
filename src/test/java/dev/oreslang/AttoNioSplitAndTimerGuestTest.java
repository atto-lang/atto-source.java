package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Async TCP v1.1 (spec chapter 26): a connection splits into independently
 * owned read and write halves so a server can push data while it reads, and
 * process.sleep_async provides nonblocking timers.
 */
final class AttoNioSplitAndTimerGuestTest {
    private static final int PORT = 16421;

    private static final String DUPLEX = """
            pub async fnc writer_loop(NioWriter out, Channel<Bytes> outbox): void {
              loop {
                val b = await (nb readch outbox);
                if b.size() == 0 then
                  await out.close_async();
                  return;
                fi
                await out.write_owned_bytes_async(b);
              }
            }

            pub async fnc ticker(Channel<Bytes> outbox): void {
              let int i = 0;
              loop {
                if i == 3 then return; fi
                await process.sleep_async(50);
                await (nb writech outbox, Bytes.from_string("tick\\n"));
                i = i + 1;
              }
            }

            pub async fnc reader_loop(NioReader input, Channel<Bytes> outbox): void {
              loop {
                val chunk = await input.read_owned_bytes_async(1024);
                if chunk.size() == 0 then
                  await input.close_async();
                  await (nb writech outbox, Bytes.empty());
                  return;
                fi
                await (nb writech outbox, Bytes.from_string("echo:").concat(chunk));
              }
            }

            pub async routine main(): void {
              val listener = await network.listen_async("127.0.0.1", 16421);
              val socket = await listener.accept_async();
              [const input, const out] = socket.split();
              val Channel<Bytes> outbox = Channel.new<Bytes>(16);
              task scope {
                let Future<void> w = task { await writer_loop(out, outbox); return; };
                let Future<void> t = task { await ticker(outbox); return; };
                let Future<void> r = task { await reader_loop(input, outbox); return; };
              }
              await listener.close_async();
              return;
            }
            """;

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void serverPushesWhileReadingThroughSplitHalves() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(DUPLEX));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, DUPLEX, "nio_split.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                }
                return null;
            });
            try (Socket client = connect(guest)) {
                client.setSoTimeout(7000);
                client.getOutputStream().write("hello\n".getBytes(StandardCharsets.US_ASCII));
                // Three timer-driven pushes arrive without any further client input.
                String expected = "echo:hello\ntick\ntick\ntick\n";
                byte[] got = client.getInputStream().readNBytes(expected.length());
                String text = new String(got, StandardCharsets.US_ASCII);
                assertTrue(text.contains("echo:hello\n"), text);
                assertEquals(3, text.split("tick\n", -1).length - 1, text);
                client.shutdownOutput();
                assertEquals(-1, client.getInputStream().read(), "write half closes after reader EOF");
            }
            guest.get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void sleepAsyncWaitsWithoutBlockingAndRejectsNegativeDelay() throws Exception {
        String program = """
                pub async routine main(): void {
                  val start = process.monotonic_ns();
                  await process.sleep_async(120);
                  stdio.println(process.monotonic_ns() - start >= 100000000);
                  return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "sleep.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("true", out.toString(StandardCharsets.UTF_8).trim());

        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            PolyglotException negative = assertThrows(PolyglotException.class, () -> context.eval(
                    Source.newBuilder(OresLanguage.ID,
                            "pub async routine main(): void { await process.sleep_async(-1); return; }",
                            "negative_sleep.atto").mimeType(OresLanguage.MIME_TYPE).build()));
            assertTrue(negative.getMessage().contains("sleep_async milliseconds"), negative.getMessage());
        }
    }

    @Test
    void splitConsumesTheConnection() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async routine main(): void {
                          val listener = await network.listen_async("127.0.0.1", 16422);
                          val socket = await listener.accept_async();
                          [const input, const out] = socket.split();
                          await socket.close_async();
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("use of moved value 'socket'"), failure.getMessage());
    }

    @Test
    void aHalfCannotBeCapturedByTwoTasks() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async routine main(): void {
                          val listener = await network.listen_async("127.0.0.1", 16422);
                          val socket = await listener.accept_async();
                          [const input, const out] = socket.split();
                          task scope {
                            let Future<void> a = task { await out.close_async(); return; };
                            let Future<void> b = task { await out.close_async(); return; };
                          }
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("use of moved value 'out'"), failure.getMessage());
    }

    @Test
    void halvesKeepTheirDirection() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc send_on(NioWriter out): void { return; }
                        pub async routine main(): void {
                          val listener = await network.listen_async("127.0.0.1", 16422);
                          val socket = await listener.accept_async();
                          [const input, const out] = socket.split();
                          await send_on(input);
                          return;
                        }
                        """));
        assertNotNull(failure.getMessage());
        RuntimeException noWrite = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async routine main(): void {
                          val listener = await network.listen_async("127.0.0.1", 16422);
                          val socket = await listener.accept_async();
                          [const input, const out] = socket.split();
                          await input.write_owned_bytes_async(Bytes.empty());
                          return;
                        }
                        """));
        assertTrue(noWrite.getMessage().contains("unknown NioReader member"), noWrite.getMessage());
    }

    private static Socket connect(Future<?> guest) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("guest returned before accepting");
            }
            try {
                return new Socket("127.0.0.1", PORT);
            } catch (java.net.ConnectException notReady) {
                Thread.sleep(40);
            }
        }
        fail("listener did not start");
        return null;
    }
}
