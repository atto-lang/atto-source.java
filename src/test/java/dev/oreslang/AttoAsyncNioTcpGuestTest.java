package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
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

/**
 * Genuine Atto compiler + Truffle guest uses only Future/await NIO2 network
 * calls. Java's test client uses a Socket; guest and compiler transport do not.
 */
final class AttoAsyncNioTcpGuestTest {
    @Test void realAttoGuestEchoesBinaryOverAsyncNioListener() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            port = probe.getLocalPort();
        }
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", %d);
                  stdio.println("READY");
                  val socket = await listener.accept_async();
                  val bytes = await socket.read_bytes_async(1024);
                  await socket.write_bytes_async(bytes);
                  await socket.close_async();
                  await listener.close_async();
                  return;
                }
                """.formatted(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (ExecutorService exec = Executors.newSingleThreadExecutor()) {
            Future<?> execution = exec.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=NETWORK,STDOUT"})
                        .out(stdout).build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, source,
                            "atto-nio2-binary-echo.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            boolean ready = false;
            for (int n = 0; n < 120; n++) {
                if (stdout.toString().contains("READY")) { ready = true; break; }
                if (execution.isDone()) {
                    execution.get(1, TimeUnit.SECONDS);
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(ready, "actual Atto guest did not start async NIO listener");
            byte[] payload = {(byte) 0xff, 0, 13, 10, 1};
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(4000);
                client.getOutputStream().write(payload);
                assertArrayEquals(payload, client.getInputStream().readNBytes(payload.length));
            }
            execution.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void blockingLegacyMethodsAreNotAdmittedOnNioSocket() {
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16397);
                  val socket = await listener.accept_async();
                  val data = socket.read_line();
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test void asyncByteWriteRejectsStringArgument() {
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", 16397);
                  val socket = await listener.accept_async();
                  await socket.write_bytes_async("not a byte list");
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }
}
