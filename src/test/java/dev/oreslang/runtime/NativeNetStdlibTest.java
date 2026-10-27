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
    @Test void canonicalHostedAndLegacyNamesResolveTheSameNioModule() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("net.ores"));
        assertFalse(CoreLibrarySources.packagedCorePaths().contains("net.ores"));
        assertEquals(CoreLibrarySources.unitId("std/net"),
                CoreLibrarySources.unitId("ores.std/net"));
        assertEquals(CoreLibrarySources.source("std/net"),
                CoreLibrarySources.source("ores.std/net"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/net"));
    }


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
            source.append("  return;\n}\n");
            Files.writeString(entry, source.toString());
            LinkedProgramRunner.run(entry,
                    IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                    ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test void realGuestAcceptReadWriteThroughNonblockingKernel() throws Exception {
        Path entry = Files.createTempFile("ores-net-guest-", ".ores");
        ExecutorService runner = Executors.newSingleThreadExecutor();
        int port;
        try (ServerSocket reservation = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        try {
            Files.writeString(entry, ("""
                import module net_core from "ores.std/net";
                pub async routine main(): void {
                  val NioTcpListener listener = net_core.listen_loopback(%d, 32, 4, 16);
                  val Future<NioTcpConnection> pending_accept = net_core.accept(rt borrow listener);
                  val NioTcpConnection peer = await pending_accept;
                  val Future<Array<int>> pending_read = net_core.read(rt borrow peer, 64);
                  val Array<int> bytes = await pending_read;
                  val Future<int> pending_write = net_core.write(rt borrow peer, bytes);
                  val int sent = await pending_write;
                  val Future<void> pending_close = net_core.close_connection(peer);
                  await pending_close;
                  val Future<void> pending_shutdown = net_core.close_listener(listener);
                  await pending_shutdown;
                  return;
                }
                """).formatted(port));
            Future<?> program = runner.submit(() -> {
                try {
                    LinkedProgramRunner.run(entry,
                            IsolatePolicy.developer().withCapabilities(
                                    IsolatePolicy.Capability.NETWORK),
                            ExecutionProfile.serverJit(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Socket socket = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (socket == null && System.nanoTime() < deadline) {
                Socket attempt = new Socket();
                try {
                    attempt.connect(
                            new InetSocketAddress(
                                    InetAddress.getByName("127.0.0.1"), port),
                            100);
                    socket = attempt;
                } catch (ConnectException notReady) {
                    attempt.close();
                    if (program.isDone()) {
                        program.get(2, TimeUnit.SECONDS);
                        fail("guest exited before accepting loopback TCP");
                    }
                    Thread.sleep(10);
                }
            }
            assertNotNull(socket, "guest loopback listener did not become ready");
            try (Socket connected = socket) {
                connected.setSoTimeout(2000);
                connected.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
                assertEquals("PING", new String(connected.getInputStream().readNBytes(4),
                        StandardCharsets.US_ASCII));
            }
            program.get(20, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
            assertTrue(runner.awaitTermination(3, TimeUnit.SECONDS));
            Files.deleteIfExists(entry);
        }
    }
    @Test void realGuestOutboundConnectReadWriteThroughNonblockingKernel()
            throws Exception {
        Path entry = Files.createTempFile("ores-net-connect-guest-", ".ores");
        ExecutorService host = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(
                0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            int port = listener.getLocalPort();
            Future<String> echo = host.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    accepted.setSoTimeout(3000);
                    accepted.getOutputStream().write(
                            "PING".getBytes(StandardCharsets.US_ASCII));
                    return new String(
                            accepted.getInputStream().readNBytes(4),
                            StandardCharsets.US_ASCII);
                }
            });

            Files.writeString(entry, ("""
                import module net_core from "std/net";
                pub async routine main(): void {
                  val Future<NioTcpConnection> pending =
                    net_core.connect_ipv4("127.0.0.1", %d);
                  val NioTcpConnection peer = await pending;
                  val Future<Array<int>> pending_read =
                    net_core.read(rt borrow peer, 4);
                  val Array<int> bytes = await pending_read;
                  val Future<int> pending_write =
                    net_core.write(rt borrow peer, bytes);
                  val int sent = await pending_write;
                  val Future<void> pending_close =
                    net_core.close_connection(peer);
                  await pending_close;
                  return;
                }
                """).formatted(port));

            LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer().withCapabilities(
                            IsolatePolicy.Capability.NETWORK),
                    ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            assertEquals("PING", echo.get(5, TimeUnit.SECONDS),
                    "outbound guest connection must use the same async read/write surface");
        } finally {
            host.shutdownNow();
            assertTrue(host.awaitTermination(3, TimeUnit.SECONDS));
            Files.deleteIfExists(entry);
        }
    }

    @Test void realGuestDuplexReadAndWriteOverlapOnOneConnection()
            throws Exception {
        Path entry = Files.createTempFile("ores-net-duplex-guest-", ".ores");
        ExecutorService host = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(
                0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            int port = listener.getLocalPort();
            Future<String> transcript = host.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    accepted.setSoTimeout(5000);
                    accepted.getOutputStream().write(
                            "PING".getBytes(StandardCharsets.US_ASCII));

                    String first = new String(
                            accepted.getInputStream().readNBytes(4),
                            StandardCharsets.US_ASCII);
                    if (!first.equals("PING")) return "bad-first:" + first;

                    // This write makes the guest's already-pending read ready
                    // only after its sibling write has completed.
                    accepted.getOutputStream().write(
                            "PONG".getBytes(StandardCharsets.US_ASCII));

                    String second = new String(
                            accepted.getInputStream().readNBytes(4),
                            StandardCharsets.US_ASCII);
                    return first + ":" + second;
                }
            });

            Files.writeString(entry, ("""
                import module net_core from "std/net";
                pub async routine main(): void {
                  val NioTcpConnection peer =
                    await net_core.connect_ipv4("127.0.0.1", %d);

                  val Array<int> ping =
                    await net_core.read(rt borrow peer, 4);

                  // Deliberately arm the next read before starting the write.
                  // Both Futures must coexist on one physical selector key.
                  val Future<Array<int>> pending_read =
                    net_core.read(rt borrow peer, 4);
                  val Future<int> pending_write =
                    net_core.write(rt borrow peer, ping);

                  val int sent = await pending_write;
                  val Array<int> pong = await pending_read;

                  val Future<int> final_write =
                    net_core.write(rt borrow peer, pong);
                  val int echoed = await final_write;
                  await net_core.close_connection(peer);
                  return;
                }
                """).formatted(port));

            LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer().withCapabilities(
                            IsolatePolicy.Capability.NETWORK),
                    ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            assertEquals("PING:PONG", transcript.get(5, TimeUnit.SECONDS),
                    "guest must overlap one pending read with one write on the same connection");
        } finally {
            host.shutdownNow();
            assertTrue(host.awaitTermination(3, TimeUnit.SECONDS));
            Files.deleteIfExists(entry);
        }
    }

}
