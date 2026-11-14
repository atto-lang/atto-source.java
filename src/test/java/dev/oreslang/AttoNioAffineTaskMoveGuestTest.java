package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
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
 * An accepted NioConnection is an affine, single-owner capability: it may MOVE
 * into exactly one async task (task-per-connection servers), but it can never
 * be copied, shared, captured twice, or used by the parent after the move.
 */
final class AttoNioAffineTaskMoveGuestTest {
    private static final int PORT = 16413;

    private static final String SERVER = """
            pub async fnc echo_session(NioConnection socket): void {
              loop {
                val bytes = await socket.read_bytes_async(1024);
                if bytes.size == 0 then
                  await socket.close_async();
                  return;
                fi
                await socket.write_bytes_async(bytes);
              }
            }

            pub async routine main(): void {
              val listener = await network.listen_async("127.0.0.1", 16413);
              task scope {
                let int accepted = 0;
                loop {
                  if accepted == 2 then break; fi
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

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void taskPerConnectionServesSecondClientWhileFirstIsIdle() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(SERVER),
                "owned NioConnection must move into an async session task");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, SERVER,
                                    "nio_affine_task_move.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                }
                return null;
            });
            try (Socket first = connect(guest); Socket second = connect(guest)) {
                first.setSoTimeout(7000);
                second.setSoTimeout(7000);
                // The first session is parked in read_bytes_async; the second
                // must still be served, proving independent session tasks.
                second.getOutputStream().write("second\n".getBytes(StandardCharsets.US_ASCII));
                assertEcho(second, "second\n");
                first.getOutputStream().write("first\n".getBytes(StandardCharsets.US_ASCII));
                assertEcho(first, "first\n");
                second.shutdownOutput();
                assertEquals(-1, second.getInputStream().read(), "EOF closes second session");
                first.shutdownOutput();
                assertEquals(-1, first.getInputStream().read(), "EOF closes first session");
            }
            guest.get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void movedSocketCannotBeCapturedTwice() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16414);
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
                """), "a unique socket owner must not be captured into two tasks");
        assertTrue(failure.getMessage().contains("use of moved value 'socket'"), failure.getMessage());
    }

    @Test
    void movedSocketIsUnusableInParent() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16414);
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
                """), "the parent loses the socket once a task captures it");
        assertTrue(failure.getMessage().contains("use of moved value 'socket'"), failure.getMessage());
    }

    @Test
    void listenerStaysWithItsOwnerTask() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16414);
                  task scope {
                    let Future<void> child = task {
                      await listener.close_async();
                      return;
                    };
                  }
                  return;
                }
                """), "NioListener is not Send and cannot move into a task");
        assertTrue(failure.getMessage().contains("non-Send execution capability"), failure.getMessage());
    }

    private static Socket connect(Future<?> guest) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (guest.isDone()) {
                guest.get(1, TimeUnit.SECONDS);
                fail("Atto guest returned before accepting");
            }
            try {
                return new Socket("127.0.0.1", PORT);
            } catch (java.net.ConnectException notReady) {
                Thread.sleep(40);
            }
        }
        fail("Atto NIO listener did not start");
        return null;
    }

    private static void assertEcho(Socket client, String expected) throws Exception {
        byte[] want = expected.getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(want, client.getInputStream().readNBytes(want.length));
    }
}
