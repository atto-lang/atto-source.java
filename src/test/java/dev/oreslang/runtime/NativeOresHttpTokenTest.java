package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class NativeOresHttpTokenTest {
    @Test void forgedOrRevokedTokenCannotBecomeSocketAuthority() throws Exception {
        Path entry = Files.createTempFile("ores-http-forged-token-", ".ores");
        try {
            Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val Future<Array<int>> reading = net_core.read_token("forged-connection", 4);
                  val Array<int> bytes = await reading;
                  return;
                }
                """);
            RuntimeException denied = assertThrows(RuntimeException.class,
                    () -> LinkedProgramRunner.run(entry,
                            IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                            ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                            new ByteArrayOutputStream()));
            assertTrue(denied.toString().contains("invalid or revoked context-local TCP token"),
                    () -> "must reject forged TCP token, got " + denied);
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test void tokenizedHttpTasksAdvanceOnDifferentConnectionsWithoutRawHandleTransfer() throws Exception {
        Path entry = Files.createTempFile("ores-http-owned-tokens-", ".ores");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Files.writeString(entry, """
                import module net_core from "std/net";
                import module http_server from "std/http_server";
                pub async routine main(): void {
                  val NioTcpListener listener = net_core.listen_loopback(0, 64, 8, 128);
                  stdio.println(listener.port);
                  val Future<String> accepting_first = net_core.accept_token(rt borrow listener);
                  val String first = await accepting_first;
                  val Future<void> first_task = http_server.serve_token(first);
                  val Future<String> accepting_second = net_core.accept_token(rt borrow listener);
                  val String second = await accepting_second;
                  val Future<void> second_task = http_server.serve_token(second);
                  await second_task;
                  await first_task;
                  val Future<void> shutdown = net_core.close_listener(listener);
                  await shutdown;
                  return;
                }
                """);
            PipedInputStream pipe = new PipedInputStream(8192);
            PipedOutputStream out = new PipedOutputStream(pipe);
            Future<?> guest = pool.submit(() -> {
                try {
                    LinkedProgramRunner.run(entry,
                            IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                            ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
                } catch (Exception ex) { throw new RuntimeException(ex); }
                finally { try { out.close(); } catch (IOException ignored) { } }
            });
            Future<String> line = pool.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(pipe, StandardCharsets.UTF_8))) {
                    return reader.readLine();
                }
            });
            String announce = line.get(30, TimeUnit.SECONDS);
            if (announce == null) {
                try { guest.get(1, TimeUnit.SECONDS); }
                catch (ExecutionException failure) { throw new AssertionError(failure.getCause()); }
                fail("guest never announced TCP port");
            }
            int port = Integer.parseInt(announce.trim());
            try (Socket slow = new Socket("127.0.0.1", port)) {
                slow.setSoTimeout(5000);
                slow.getOutputStream().write("GET /shared HTTP/1.1\r\nHost: local".getBytes(StandardCharsets.US_ASCII));
                slow.getOutputStream().flush();
                try (Socket fast = new Socket("127.0.0.1", port)) {
                    fast.setSoTimeout(5000);
                    fast.getOutputStream().write(("GET /isolated HTTP/1.1\r\n" +
                            "Host: localhost\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    String secondReply = new String(fast.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    assertTrue(secondReply.startsWith("HTTP/1.1 200 OK\r\n"), secondReply);
                    assertTrue(secondReply.endsWith("ok\n"), secondReply);
                }
                slow.getOutputStream().write("host\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                String firstReply = new String(slow.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(firstReply.startsWith("HTTP/1.1 200 OK\r\n"), firstReply);
            }
            guest.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            Files.deleteIfExists(entry);
        }
    }
}
