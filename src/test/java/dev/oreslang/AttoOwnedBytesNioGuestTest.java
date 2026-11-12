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
import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backed first-class Bytes and NIO2 ownership/transport proof. */
final class AttoOwnedBytesNioGuestTest {
    @Test void ownedBinaryBytesRoundTripThroughRealAttoGuest() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            port = probe.getLocalPort();
        }
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", %d);
                  val socket = await listener.accept_async();
                  val Bytes data = await socket.read_owned_bytes_async(32);
                  if data.size() eq 5; do
                    if data.byte_at(0) eq 255; do
                      await socket.write_owned_bytes_async(data);
                    fi
                  fi
                  await socket.close_async();
                  await listener.close_async();
                  return;
                }
                """.formatted(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                "first-class Bytes must typecheck in compiled Atto guest");
        try (ExecutorService exec = Executors.newSingleThreadExecutor()) {
            Future<?> guest = exec.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=NETWORK"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, source, "atto-owned-bytes-nio.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException("actual Atto Bytes guest failed", failure);
                }
            });
            byte[] payload = {(byte) 255, 0, 13, 10, (byte) 128};
            boolean successful = false;
            for (int n = 0; n < 120; n++) {
                if (guest.isDone()) { guest.get(1, TimeUnit.SECONDS); break; }
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(5000);
                    client.getOutputStream().write(payload);
                    assertArrayEquals(payload, client.getInputStream().readNBytes(payload.length));
                    successful = true;
                    break;
                } catch (java.net.ConnectException starting) {
                    Thread.sleep(50); // Client bootstrap only; guest has NIO2 completions.
                }
            }
            assertTrue(successful, "real first-class Bytes NIO guest never echoed");
            guest.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsInvalidOwnedBytesSocketValue() {
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16397);
                  val socket = await listener.accept_async();
                  await socket.write_owned_bytes_async("not Bytes");
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test void rejectsBytesWithGenericArguments() {
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16397);
                  val socket = await listener.accept_async();
                  val Bytes<int> data = await socket.read_owned_bytes_async(8);
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }
    @Test void staticallyRejectsUseAfterOwnedSocketWrite() {
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16397);
                  val socket = await listener.accept_async();
                  val Bytes data = await socket.read_owned_bytes_async(8);
                  await socket.write_owned_bytes_async(data);
                  val int illegal = data.byte_at(0);
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

}
