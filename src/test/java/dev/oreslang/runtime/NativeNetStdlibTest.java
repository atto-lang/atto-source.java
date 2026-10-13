package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class NativeNetStdlibTest {
    @Test void sourceModuleOwnsTcpOrchestrationWithoutHostInterop() throws Exception {
        String source = CoreLibrarySources.source("std/net");
        MixedSourceUnit parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/net"), "net.ores", source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("stdio."));
        assertFalse(source.contains("http.listen"));
    }

    @Test void realGuestAcceptReadWriteThroughNonblockingKernel() throws Exception {
        Path entry = Files.createTempFile("ores-net-guest-", ".ores");
        ExecutorService runner = Executors.newFixedThreadPool(2);
        try {
            Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val listener = net_core.listen_loopback(0, 32, 4, 16);
                  stdio.println(listener.port);
                  val peer = await net_core.accept(listener);
                  val bytes = await net_core.read(peer, 64);
                  val int sent = await net_core.write(peer, bytes);
                  stdio.println(sent);
                  await net_core.close_connection(peer);
                  await net_core.close_listener(listener);
                  return;
                }
                """);
            PipedInputStream readPort = new PipedInputStream(8192);
            PipedOutputStream output = new PipedOutputStream(readPort);
            Future<?> program = runner.submit(() -> {
                try {
                    LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(), output, new ByteArrayOutputStream());
                } catch (Exception e) { throw new RuntimeException(e); }
                finally { try { output.close(); } catch (IOException ignored) {} }
            });
            Future<String> line = runner.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(readPort))) {
                    return reader.readLine();
                }
            });
            // Any guest compilation failure must also terminate the stream.
            String portText = line.get(20, TimeUnit.SECONDS);
            assertNotNull(portText, "guest must produce listening port");
            int port = Integer.parseInt(portText.trim());
            assertTrue(port > 0);
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
                assertEquals("PING", new String(socket.getInputStream().readNBytes(4),
                        StandardCharsets.US_ASCII));
            }
            program.get(20, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
            Files.deleteIfExists(entry);
        }
    }
}
