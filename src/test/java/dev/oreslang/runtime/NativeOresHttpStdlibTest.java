package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

final class NativeOresHttpStdlibTest {
    @Test void httpModuleIsOreslangOnlyAndClosedRegistered() throws Exception {
        String source = CoreLibrarySources.source("std/http");
        MixedSourceUnit compiled = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/http"), "http.ores", source);
        assertTrue(compiled.hasOresSource());
        assertFalse(compiled.hasJavaSource());
        assertFalse(source.contains("http.listen("));
    }

    @Test void incrementalHttpFramingAndSmugglingRegressionsExecuteAsOreslang() throws Exception {
        Path guest = Files.createTempFile("ores-native-http-wire-", ".ores");
        try {
            Files.writeString(guest, """
                import module http_wire from "std/http";
                pub routine main(): void {
                  val Array<int> valid_shared = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,13,10];
                  val http_wire.Head valid_shared_head = http_wire.parse_head(rt borrow valid_shared);
                  stdio.println(valid_shared_head.status);
                  stdio.println(valid_shared_head.consumed);
                  val Array<int> valid_head = arr[72,69,65,68,32,47,105,115,111,108,97,116,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,67,111,110,110,101,99,116,105,111,110,58,32,99,108,111,115,101,13,10,13,10];
                  val http_wire.Head valid_head_head = http_wire.parse_head(rt borrow valid_head);
                  stdio.println(valid_head_head.status);
                  stdio.println(valid_head_head.consumed);
                  val Array<int> smuggling = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,67,111,110,116,101,110,116,45,76,101,110,103,116,104,58,32,48,13,10,13,10];
                  val http_wire.Head smuggling_head = http_wire.parse_head(rt borrow smuggling);
                  stdio.println(smuggling_head.status);
                  stdio.println(smuggling_head.consumed);
                  val Array<int> dup_host = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,72,111,115,116,58,32,97,103,97,105,110,13,10,13,10];
                  val http_wire.Head dup_host_head = http_wire.parse_head(rt borrow dup_host);
                  stdio.println(dup_host_head.status);
                  stdio.println(dup_host_head.consumed);
                  val Array<int> bare_lf = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,10,10];
                  val http_wire.Head bare_lf_head = http_wire.parse_head(rt borrow bare_lf);
                  stdio.println(bare_lf_head.status);
                  stdio.println(bare_lf_head.consumed);
                  val Array<int> partial = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10];
                  val http_wire.Head partial_head = http_wire.parse_head(rt borrow partial);
                  stdio.println(partial_head.status);
                  stdio.println(partial_head.consumed);
                  val Array<int> pipelines = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,13,10,71,69,84,32,47,105,115,111,108,97,116,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,122,13,10,13,10];
                  val http_wire.Head pipelines_head = http_wire.parse_head(rt borrow pipelines);
                  stdio.println(pipelines_head.status);
                  stdio.println(pipelines_head.consumed);
                  val Array<int> unsupported = arr[80,79,83,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,13,10];
                  val http_wire.Head unsupported_head = http_wire.parse_head(rt borrow unsupported);
                  stdio.println(unsupported_head.status);
                  stdio.println(unsupported_head.consumed);
                  return;
                }
                """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LinkedProgramRunner.run(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
            String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\\s+");
            int[] expected = {200,41,200,63,400,60,400,54,400,0,0,0,200,41,405,42};
            int[] actual = Arrays.stream(lines).mapToInt(Integer::parseInt).toArray();
            assertArrayEquals(expected, actual,
                    "Oreslang byte framing must reject ambiguous CL/TE/host and preserve pipelined bytes");
        } finally {
            Files.deleteIfExists(guest);
        }
    }
    @Test void realOreslangHttpOverNioSupportsFragmentsPipeliningAndStrictFraming() throws Exception {
        Path guest = Files.createTempFile("ores-http-over-net-", ".ores");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            Files.writeString(guest, """
                import module net_core from "std/net";
                import module http_wire from "std/http";
                pub async routine main(): void {
                  val NioTcpListener listener = net_core.listen_loopback(0, 64, 16, 256);
                  stdio.println(listener.port);
                  for (let int i = 0; i < 4; i = i + 1) {
                    val Future<NioTcpConnection> incoming = net_core.accept(rt borrow listener);
                    val NioTcpConnection peer = await incoming;
                    val Future<void> response = http_wire.serve_connection(peer);
                    await response;
                  }
                  val Future<void> shutdown = net_core.close_listener(listener);
                  await shutdown;
                  return;
                }
                """);
            java.io.PipedInputStream stream = new java.io.PipedInputStream(8192);
            java.io.PipedOutputStream out = new java.io.PipedOutputStream(stream);
            java.util.concurrent.Future<?> program = pool.submit(() -> {
                try {
                    LinkedProgramRunner.run(guest,
                            IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                            ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
                } catch (Exception error) { throw new RuntimeException(error); }
                finally { try { out.close(); } catch (Exception ignored) { } }
            });
            java.util.concurrent.Future<String> announced = pool.submit(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    return reader.readLine();
                }
            });
            String portText = announced.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (portText == null) {
                try { program.get(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException fail) { throw new AssertionError(fail.getCause()); }
                fail("HTTP guest did not announce listener port");
            }
            int port = Integer.parseInt(portText.trim());
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write("GET /shared HTTP/1.1\\r\\nHost: loc".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                socket.getOutputStream().write("alhost\\r\\nConnection: close\\r\\n\\r\\n".getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200 OK\\r\\n"), response);
                assertTrue(response.contains("Content-Length: 3\\r\\n"), response);
                assertTrue(response.endsWith("ok\\n"), response);
            }
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write((
                        "GET /isolated HTTP/1.1\\r\\nHost: localhost\\r\\n\\r\\n" +
                        "GET /shared HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n")
                        .getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertEquals(2, response.split("HTTP/1.1 200 OK", -1).length - 1, response);
                assertTrue(response.contains("Connection: keep-alive"), response);
                assertTrue(response.endsWith("ok\\n"), response);
            }
            for (String invalid : java.util.List.of(
                    "GET /shared HTTP/1.1\\r\\nHost: localhost\\r\\nContent-Length: 0\\r\\n\\r\\n",
                    "GET /shared HTTP/1.1\\r\\nConnection: close\\r\\n\\r\\n")) {
                try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(invalid.getBytes(StandardCharsets.US_ASCII));
                    String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\\r\\n"), response);
                    assertTrue(response.contains("Content-Length: 0\\r\\n"), response);
                }
            }
            program.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            Files.deleteIfExists(guest);
        }
    }

}
