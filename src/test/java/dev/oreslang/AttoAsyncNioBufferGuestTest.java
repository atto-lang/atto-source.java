package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Exact real Atto guest with NIO2 owned Bytes, not mock transport. */
@Timeout(60)
final class AttoAsyncNioBufferGuestTest {
    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return probe.getLocalPort();
        }
    }

    private static Future<?> execute(ExecutorService executor, String guest) {
        return executor.submit(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .arguments(OresLanguage.ID,
                            new String[]{"--ores-capabilities=NETWORK"})
                    .build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, guest,
                        "atto-nio2-owned-buffer.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    private static Socket connect(Future<?> execution, int port) throws Exception {
        for (int n = 0; n < 120; n++) {
            if (execution.isDone()) {
                execution.get(1, TimeUnit.SECONDS);
                fail("guest terminated before listening");
            }
            try {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(5000);
                return socket;
            } catch (java.net.ConnectException notYetBound) {
                Thread.sleep(40);
            }
        }
        throw new AssertionError("guest did not listen on loopback");
    }

    @Test
    void singleClientUsesOwnedBytesWithoutPerByteBoxing() throws Exception {
        int port = freePort();
        String guest = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", %d);
                  val socket = await listener.accept_async();
                  const Bytes bytes = await socket.read_buffer_async(1024);
                  await socket.write_buffer_async(bytes);
                  await socket.close_async();
                  await listener.close_async();
                  return;
                }
                """.formatted(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guest));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> result = execute(executor, guest);
            try (Socket socket = connect(result, port)) {
                byte[] binary = {0, (byte) 255, 13, 10, 1, 99};
                socket.getOutputStream().write(binary);
                assertArrayEquals(binary,
                        socket.getInputStream().readNBytes(binary.length));
            }
            result.get(12, TimeUnit.SECONDS);
        }
    }

    @Test
    void twoClientsCanProgressOnIndependentCompletionDrivenReadTasks()
            throws Exception {
        int port = freePort();
        String guest = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", %d);
                  val first = await listener.accept_async();
                  val second = await listener.accept_async();
                  task scope {
                    let Future<void> one = task {
                      const Bytes data = await first.read_buffer_async(1024);
                      await first.write_buffer_async(data);
                      await first.close_async();
                      return;
                    };
                    let Future<void> two = task {
                      const Bytes data = await second.read_buffer_async(1024);
                      await second.write_buffer_async(data);
                      await second.close_async();
                      return;
                    };
                  }
                  await listener.close_async();
                  return;
                }
                """.formatted(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guest));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> result = execute(executor, guest);
            try (Socket first = connect(result, port);
                 Socket second = connect(result, port)) {
                byte[] a = {0, 13, 10, (byte) 255, 10};
                first.getOutputStream().write(a);
                // The second socket deliberately does not send until the
                // first has finished; sequential reads would deadlock here.
                assertArrayEquals(a, first.getInputStream().readNBytes(a.length));
                byte[] b = {(byte) 255, 0, 0, 42, 13, 10};
                second.getOutputStream().write(b);
                assertArrayEquals(b, second.getInputStream().readNBytes(b.length));
            }
            result.get(12, TimeUnit.SECONDS);
        }
    }

    @Test
    void movedNioSocketCannotBeUsedOrCapturedTwice() {
        String doubleCapture = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16398);
                  val socket = await listener.accept_async();
                  task scope {
                    let Future<void> first = task {
                      await socket.close_async();
                      return;
                    };
                    let Future<void> second = task {
                      await socket.close_async();
                      return;
                    };
                  }
                  await listener.close_async();
                  return;
                }
                """;
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(doubleCapture),
                "unique socket owner must not be captured into two tasks");
        String useAfterMove = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16398);
                  val socket = await listener.accept_async();
                  task scope {
                    let Future<void> child = task {
                      await socket.close_async();
                      return;
                    };
                    await socket.close_async();
                  }
                  await listener.close_async();
                  return;
                }
                """;
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(useAfterMove),
                "socket must be unusable in parent after task transfer");
    }

    @Test
    void writeBufferRejectsStringAndLegacySynchronousRead() {
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16398);
                  val socket = await listener.accept_async();
                  await socket.write_buffer_async("not Bytes");
                  return;
                }
                """));
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16398);
                  val socket = await listener.accept_async();
                  socket.read_line();
                  return;
                }
                """));
    }
}
