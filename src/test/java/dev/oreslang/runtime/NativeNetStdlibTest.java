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

    @Test void networkCapabilityDenialFailsBeforeSocketAdmission() throws Exception {
        Path entry = Files.createTempFile("ores-net-denied-", ".ores");
        try {
            Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val NioTcpListener listener = net_core.listen_loopback(0, 8, 2, 4);
                  return;
                }
                """);
            RuntimeException rejection = assertThrows(RuntimeException.class,
                    () -> LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            assertTrue(rejection.toString().contains("NETWORK"),
                    () -> "expected NET capability denial, got " + rejection);
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test void contextEnforcesAggregateReactorBudget() throws Exception {
        Path entry = Files.createTempFile("ores-net-listener-quota-", ".ores");
        try {
            StringBuilder source = new StringBuilder("""
                    import module net_core from "std/net";
                    pub async routine main(): void {
                    """);
            for (int i = 0; i < 17; i++) {
                source.append("  val NioTcpListener l").append(i)
                        .append(" = net_core.listen_loopback(0, 8, 1, 4);\n");
            }
            source.append("  return;\n}\n");
            Files.writeString(entry, source.toString());
            RuntimeException rejection = assertThrows(RuntimeException.class, () ->
                    LinkedProgramRunner.run(entry,
                            IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                            ExecutionProfile.serverJit(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            assertTrue(rejection.toString().contains("TCP listener capacity exhausted"),
                    () -> "expected admission limit rather than unbounded reactor threads: " + rejection);
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test void closedListenersAreReapedForRepeatedGuestRestarts() throws Exception {
        Path entry = Files.createTempFile("ores-net-listener-restart-", ".ores");
        try {
            StringBuilder source = new StringBuilder("""
                    import module net_core from "std/net";
                    pub async routine main(): void {
                    """);
            for (int i = 0; i < 20; i++) {
                source.append("  val NioTcpListener l").append(i)
                        .append(" = net_core.listen_loopback(0, 8, 1, 4);\n");
                source.append("  val Future<void> stop").append(i)
                        .append(" = net_core.close_listener(l").append(i).append(");\n");
                source.append("  await stop").append(i).append(";\n");
            }
            source.append("  stdio.println(777);\n  return;\n}\n");
            Files.writeString(entry, source.toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry,
                    IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                    ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
            assertTrue(out.toString().contains("777"),
                    "completed guest restarts must eventually release the context listener quota");
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test void guestAwaitsTwoIndependentListenersWithoutCrossingSocketReplies()
            throws Exception {
        // Exercise actual compiled guest net_core/await operations, not only
        // the host-only NioReadinessGroup API. The two pending accepts share a
        // VM selector and cannot resume either guest turn on that selector.
        Path entry = Files.createTempFile("ores-net-dual-guest-", ".ores");
        ExecutorService runner = Executors.newFixedThreadPool(2);
        CompletableFuture<int[]> publishedPorts = new CompletableFuture<>();
        PipedInputStream stream = new PipedInputStream(8192);
        PipedOutputStream output = new PipedOutputStream(stream);
        try {
            Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val NioTcpListener first = net_core.listen_loopback(0, 32, 2, 8);
                  val NioTcpListener second = net_core.listen_loopback(0, 32, 2, 8);
                  stdio.println(first.port);
                  stdio.println(second.port);

                  val Future<NioTcpConnection> first_accept = net_core.accept(rt borrow first);
                  val Future<NioTcpConnection> second_accept = net_core.accept(rt borrow second);
                  val NioTcpConnection first_peer = await first_accept;
                  val NioTcpConnection second_peer = await second_accept;

                  val Future<Array<int>> first_read = net_core.read(rt borrow first_peer, 1);
                  val Future<Array<int>> second_read = net_core.read(rt borrow second_peer, 1);
                  val Array<int> first_bytes = await first_read;
                  val Array<int> second_bytes = await second_read;

                  val Future<int> first_write = net_core.write(rt borrow first_peer, first_bytes);
                  val Future<int> second_write = net_core.write(rt borrow second_peer, second_bytes);
                  val int first_written = await first_write;
                  val int second_written = await second_write;
                  stdio.println(first_written + second_written);

                  val Future<void> close_first = net_core.close_connection(first_peer);
                  val Future<void> close_second = net_core.close_connection(second_peer);
                  await close_first;
                  await close_second;
                  val Future<void> stop_first = net_core.close_listener(first);
                  val Future<void> stop_second = net_core.close_listener(second);
                  await stop_first;
                  await stop_second;
                  stdio.println(4242);
                  return;
                }
                """);
            Future<?> program = runner.submit(() -> {
                try {
                    LinkedProgramRunner.run(entry,
                            IsolatePolicy.developer()
                                    .withCapabilities(IsolatePolicy.Capability.NETWORK),
                            ExecutionProfile.serverJit(), output, new ByteArrayOutputStream());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                } finally {
                    try { output.close(); } catch (IOException ignored) { }
                }
            });
            Future<String> outputReader = runner.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String first = reader.readLine();
                    String second = reader.readLine();
                    if (first == null || second == null) {
                        throw new EOFException("guest exited before publishing both ports");
                    }
                    publishedPorts.complete(new int[]{
                            Integer.parseInt(first.trim()), Integer.parseInt(second.trim())});
                    StringBuilder rest = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                        rest.append(line).append('\\n');
                    return rest.toString();
                } catch (Throwable failure) {
                    publishedPorts.completeExceptionally(failure);
                    throw failure;
                }
            });
            int[] ports = publishedPorts.get(20, TimeUnit.SECONDS);
            assertEquals(2, ports.length);
            assertTrue(ports[0] > 0 && ports[1] > 0);
            assertNotEquals(ports[0], ports[1], "independent listeners must own distinct ports");

            try (Socket first = new Socket(InetAddress.getByName("127.0.0.1"), ports[0]);
                 Socket second = new Socket(InetAddress.getByName("127.0.0.1"), ports[1])) {
                first.setSoTimeout(5000);
                second.setSoTimeout(5000);
                first.getOutputStream().write('A');
                second.getOutputStream().write('Z');
                assertEquals('A', first.getInputStream().read(),
                        "first guest Future resumed on the wrong connection");
                assertEquals('Z', second.getInputStream().read(),
                        "second guest Future resumed on the wrong connection");
            }
            program.get(20, TimeUnit.SECONDS);
            String remainder = outputReader.get(5, TimeUnit.SECONDS);
            assertTrue(remainder.contains("2"), "both guest writes must complete");
            assertTrue(remainder.contains("4242"),
                    "both source-level close Futures must finish before main returns");
        } finally {
            try { output.close(); } catch (IOException ignored) { }
            try { stream.close(); } catch (IOException ignored) { }
            runner.shutdownNow();
            Files.deleteIfExists(entry);
        }
    }

    @Test void realGuestAcceptReadWriteThroughNonblockingKernel() throws Exception {
        Path entry = Files.createTempFile("ores-net-guest-", ".ores");
        ExecutorService runner = Executors.newFixedThreadPool(2);
        try {
            Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val NioTcpListener listener = net_core.listen_loopback(0, 32, 4, 16);
                  stdio.println(listener.port);
                  val Future<NioTcpConnection> pending_accept = net_core.accept(rt borrow listener);
                  val NioTcpConnection peer = await pending_accept;
                  val Future<Array<int>> pending_read = net_core.read(rt borrow peer, 64);
                  val Array<int> bytes = await pending_read;
                  val Future<int> pending_write = net_core.write(rt borrow peer, bytes);
                  val int sent = await pending_write;
                  stdio.println(sent);
                  val Future<void> pending_close = net_core.close_connection(peer);
                  await pending_close;
                  val Future<void> pending_shutdown = net_core.close_listener(listener);
                  await pending_shutdown;
                  return;
                }
                """);
            PipedInputStream readPort = new PipedInputStream(8192);
            PipedOutputStream output = new PipedOutputStream(readPort);
            Future<?> program = runner.submit(() -> {
                try {
                    LinkedProgramRunner.run(entry, IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
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
            if (portText == null) {
                try {
                    program.get(2, TimeUnit.SECONDS);
                } catch (ExecutionException failure) {
                    throw new AssertionError("Oreslang guest server failed before listening",
                            failure.getCause());
                }
                fail("guest exited without producing listening port");
            }
            int port = Integer.parseInt(portText.trim());
            assertTrue(port > 0);
            try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
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
