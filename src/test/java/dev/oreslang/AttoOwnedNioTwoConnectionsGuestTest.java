package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Atto compiler + async NIO2 verifies two simultaneously live accepted
 * connections without sharing socket objects or storage outside the guest.
 * This is not yet a concurrent per-client actor Redis server.
 */
final class AttoOwnedNioTwoConnectionsGuestTest {

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void twoConnectionsStayOpenAndExchangeDistinctBinaryData() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            port = reservation.getLocalPort();
        }
        String src = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", PORT);
                  val first = await listener.accept_async();
                  val second = await listener.accept_async();
                  const Bytes msg_first = await first.read_owned_bytes_async(64);
                  const Bytes msg_second = await second.read_owned_bytes_async(64);
                  await first.write_owned_bytes_async(msg_first);
                  await second.write_owned_bytes_async(msg_second);
                  await first.close_async();
                  await second.close_async();
                  await listener.close_async();
                }
                """.replace("PORT", Integer.toString(port));
        OresCompiler.parseAndTypeCheck(src);
        byte[] firstPayload = {0, -1, 13, 10, 7};
        byte[] secondPayload = {5, 0, -2, 42, 10, 13};
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> execution = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID, new String[]{
                                "--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, src,
                            "owned-nio-two-accepted-connections.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (Socket clientA = connect(execution, port);
                 Socket clientB = connect(execution, port)) {
                clientA.setSoTimeout(8000);
                clientB.setSoTimeout(8000);
                clientA.getOutputStream().write(firstPayload);
                clientA.getOutputStream().flush();
                clientB.getOutputStream().write(secondPayload);
                clientB.getOutputStream().flush();
                byte[] actualA = clientA.getInputStream().readNBytes(firstPayload.length);
                byte[] actualB = clientB.getInputStream().readNBytes(secondPayload.length);
                if (!java.util.Arrays.equals(firstPayload, actualA)
                        || !java.util.Arrays.equals(secondPayload, actualB)) {
                    if (execution.isDone()) execution.get(2, TimeUnit.SECONDS);
                }
                assertArrayEquals(firstPayload, actualA);
                assertArrayEquals(secondPayload, actualB);
            }
            execution.get(15, TimeUnit.SECONDS);
        }
    }

    private static Socket connect(Future<?> execution, int port) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (execution.isDone()) execution.get(1, TimeUnit.SECONDS);
            try {
                return new Socket("127.0.0.1", port);
            } catch (java.net.ConnectException notBound) {
                Thread.sleep(30);
            }
        }
        fail("Atto two-client listener never bound");
        return null;
    }
}
