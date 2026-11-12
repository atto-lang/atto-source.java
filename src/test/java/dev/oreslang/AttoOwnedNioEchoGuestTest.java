package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Minimal independent wire proof for the actual Atto-owned NIO Future API. */
final class AttoOwnedNioEchoGuestTest {
    @Test
    void realAttoOwnedBytesEchoTransfersBinaryAndEOF() throws Exception {
        int port;
        try (ServerSocket p = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            port = p.getLocalPort();
        }
        String program = """
                pub async routine main(): void {
                    val listener = await network.listen_async("127.0.0.1", PORT);
                    val socket = await listener.accept_async();
                    val request = await socket.read_owned_bytes_async(1024);
                    await socket.write_owned_bytes_async(request);
                    await socket.close_async();
                    await listener.close_async();
                }
                """.replace("PORT", Integer.toString(port));
        OresCompiler.parseAndTypeCheck(program);
        byte[] payload = new byte[]{0, -1, 13, 10, -2, 7};
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> guest = executor.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID,
                                new String[]{"--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, program, "owned-nio-echo.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try (Socket socket = connect(guest, port)) {
                socket.setSoTimeout(4000);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
                byte[] reply = socket.getInputStream().readNBytes(payload.length);
                if (!java.util.Arrays.equals(payload, reply)) {
                    if (guest.isDone()) guest.get(1, TimeUnit.SECONDS);
                }
                assertArrayEquals(payload, reply);
            }
            guest.get(10, TimeUnit.SECONDS);
        }
    }
    private static Socket connect(Future<?> guest, int port) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (guest.isDone()) guest.get(1, TimeUnit.SECONDS);
            try {
                return new Socket("127.0.0.1", port);
            } catch (java.net.ConnectException ignored) {
                Thread.sleep(30);
            }
        }
        throw new IOException("guest did not bind listener");
    }
}
