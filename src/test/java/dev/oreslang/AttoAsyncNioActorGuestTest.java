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

/** Real Atto binary TCP via NIO2 Futures AND a spawned Atto actor mailbox. */
final class AttoAsyncNioActorGuestTest {
    @Test void spawnedAttoActorEchoesBinaryWithOnlyAsyncNetworkIo() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            port = probe.getLocalPort();
        }
        String source = """
                define actor BinaryEchoActor as
                  receive(ActorMail<List<int>> mail): void {
                    self.send(mail.value);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn BinaryEchoActor();
                  await worker.get_ready();
                  val listener = await network.listen_async("127.0.0.1", %d);
                  val socket = await listener.accept_async();
                  val bytes = await socket.read_bytes_async(1024);
                  worker.send(bytes);
                  val result = (await worker.outputs.next()).value.unwrap();
                  await socket.write_bytes_async(result.value);
                  await socket.close_async();
                  await listener.close_async();
                  await worker.get_done();
                  return;
                }
                """.formatted(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        try (ExecutorService exec = Executors.newSingleThreadExecutor()) {
            Future<?> guest = exec.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[] {"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, source,
                            "atto-nio2-spawned-actor-binary-echo.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException("real Atto actor over NIO2 guest failed", failure);
                }
            });
            boolean echoed = false;
            byte[] payload = {(byte) 0xff, 0, 13, 10, 7};
            for (int n = 0; n < 120; n++) {
                if (guest.isDone()) { guest.get(1, TimeUnit.SECONDS); break; }
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(5000);
                    client.getOutputStream().write(payload);
                    assertArrayEquals(payload, client.getInputStream().readNBytes(payload.length));
                    echoed = true;
                    break;
                } catch (java.net.ConnectException starting) {
                    Thread.sleep(50);
                }
            }
            assertTrue(echoed, "Atto actor did not echo over genuine async NIO socket");
            guest.get(10, TimeUnit.SECONDS);
        }
    }
}
