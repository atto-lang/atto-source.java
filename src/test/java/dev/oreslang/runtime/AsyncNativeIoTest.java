package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AsyncNativeIoTest {
    @TempDir Path root;

    private String run(String program, RuntimePermissions permissions, OutputStream output) throws Exception {
        Path source = root.resolve("test.ores");
        Files.writeString(source, program);
        LinkedProgramRunner.run(source, IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ, IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK), permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(), output, new ByteArrayOutputStream());
        return output.toString();
    }

    @Test void filesystemAwaitsReturnValuesAndPropagatePermissionFailures() throws Exception {
        String path = root.resolve("item.txt").toString();
        String output = run("""
            pub async routine main(): void {
              await fs.mkdir_all_async("%s/sub");
              await fs.write_text_async("%s", "hello");
              await fs.append_text_async("%s", " world");
              stdio.println(await fs.exists_async("%s"));
              stdio.println(await fs.read_text_async("%s"));
              await fs.remove_async("%s");
              stdio.println(await fs.exists_async("%s"));
              try { await fs.read_text_async("%s"); }
              catch (failure) { stdio.println("missing-caught"); }
              try { await fs.write_text_async("/tmp/ores-async-denied.txt", "blocked"); }
              catch (failure) { stdio.println("denied-caught"); }
              return;
            }
            """.formatted(root, path, path, path, path, path, path, path),
            RuntimePermissions.denyAll().withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString()), new ByteArrayOutputStream());
        assertEquals("true\nhello world\nfalse\nmissing-caught\ndenied-caught\n", output.replace("\r", ""));
        assertFalse(Files.exists(Path.of("/tmp/ores-async-denied.txt")));
    }

    @Test void untrustedActorCannotLaunderParentFilesystemCapabilityThroughWorker() throws Exception {
        Path path = root.resolve("blocked.txt");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
            define untrusted actor Worker as
              receive(ActorMail<String> mail): void {
                try {
                  val operation = fs.write_text_async(mail.value, "escaped");
                  self.send("unexpected-admission");
                } catch (failure) { self.send("denied"); }
                self.end(); return;
              }
            end
            pub async routine main(): void {
              val worker = spawn Worker(); await worker.ready;
              worker.send("%s");
              for await const output of worker.outputs { stdio.println(output.value); }
              await worker.done; return;
            }
            """.formatted(path), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, root.toString()), new ByteArrayOutputStream()));
        assertTrue(failure.getMessage().contains("restricted capability"), failure.getMessage());
        assertFalse(Files.exists(path));
    }

    @Test void asyncCanonicalizationRejectsSymlinkEscape() throws Exception {
        Path permitted = Files.createDirectory(root.resolve("permitted"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(permitted.resolve("escape"), outside);
        String output = run("""
            pub async routine main(): void {
              try { await fs.write_text_async("%s/escape/secret.txt", "escaped"); }
              catch (failure) { stdio.println("denied"); }
              return;
            }
            """.formatted(permitted), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, permitted.toString()), new ByteArrayOutputStream());
        assertEquals("denied\n", output.replace("\r", ""));
        assertFalse(Files.exists(outside.resolve("secret.txt")));
    }

    @Test void boundedAdmissionNeverRunsOnCallerAndCloseSettlesPendingWork() throws Exception {
        try (var io = new AsyncNativeIo(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            var first = io.submit("slow", () -> {
                assertNotSame(caller, Thread.currentThread());
                entered.countDown(); hold.await(); return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var rejected = io.submit("overflow", () -> fail("must not execute"));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
            assertEquals(7, io.submitControl("close", () -> 7).get(1, TimeUnit.SECONDS),
                    "socket close must remain available when data admission is full");
            assertFalse(first.cancel(true), "mutations do not promise cancellable rollback");
            io.close();
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertTrue(io.submit("closed", () -> 1).isDone());
        }
    }

    @Test void delayedTcpReadReleasesSingleActorCarrierAndSupportsWriteAndEof() throws Exception {
        String previous = System.getProperty("ores.runtime.actor.shared.parallelism");
        System.setProperty("ores.runtime.actor.shared.parallelism", "1");
        CountDownLatch probeRan = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                if (toString().contains("probe")) probeRan.countDown();
            }
        };
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peer = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peer.submit(() -> {
                try (var socket = server.accept()) {
                    assertTrue(probeRan.await(5, TimeUnit.SECONDS), "await must release the only actor carrier");
                    socket.getOutputStream().write("reply\n".getBytes());
                    socket.shutdownOutput();
                    assertEquals("ack", new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine());
                }
                return null;
            });
            String program = """
                define actor Probe as
                  receive(ActorMail<String> mail): void { stdio.println("probe"); self.end(); return; }
                end
                define actor Reader as
                  receive(ActorMail<String> mail): void {
                    val socket = await network.connect_async("127.0.0.1", %d);
                    val reading = socket.read_line_async();
                    self.send("reading");
                    val line = await reading;
                    stdio.println(line);
                    stdio.println(await socket.read_line_async());
                    await socket.write_text_async("ack\\n");
                    await socket.close_async();
                    self.end(); return;
                  }
                end
                pub async routine main(): void {
                  val reader = spawn Reader(); await reader.ready;
                  val probe = spawn Probe(); await probe.ready;
                  reader.send("go");
                  await reader.outputs.next();
                  probe.send("go");
                  await reader.done; await probe.done; return;
                }
                """.formatted(server.getLocalPort());
            run(program, RuntimePermissions.denyAll().withAllowed(RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + server.getLocalPort()), output);
            exchange.get(2, TimeUnit.SECONDS);
            assertTrue(output.toString().contains("reply"));
            assertTrue(output.toString().contains("None"));
        } finally {
            if (previous == null) System.clearProperty("ores.runtime.actor.shared.parallelism");
            else System.setProperty("ores.runtime.actor.shared.parallelism", previous);
        }
    }

    @Test void httpClientAwaitsGetPostAndDoesNotFollowRedirects() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestMethod().equals("POST") ? exchange.getRequestBody().readAllBytes() : "hello".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            exchange.sendResponseHeaders(302, 0); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            String output = run("""
                pub async routine main(): void {
                  stdio.println(await http.get_text_async("%s/"));
                  stdio.println(await http.post_text_async("%s/", "posted"));
                  await http.get_text_async("%s/redirect");
                  stdio.println("redirect-not-followed");
                  return;
                }
                """.formatted(url, url, url), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getAddress().getPort()), new ByteArrayOutputStream());
            assertEquals("hello\nposted\nredirect-not-followed\n", output.replace("\r", ""));
        } finally { server.stop(0); }
    }

    @Test void oversizedHttpResponsesFailBeforeUnboundedMaterialization() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = new byte[NativeIo.MAX_HTTP_TEXT_BYTES + 1];
        java.util.Arrays.fill(body, (byte) 'x');
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); }
            catch (IOException closedEarly) { /* Client may reject declared size without reading. */ }
            finally { exchange.close(); }
        });
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try { exchange.getResponseBody().write(body); }
            catch (IOException closedEarly) { /* Client stops after bounded read. */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            String output = run("""
                pub async routine main(): void {
                  try {
                    await http.get_text_async("%s/large");
                    stdio.println("unexpected-large");
                  } catch (failure) { stdio.println("large-rejected"); }
                  try {
                    await http.get_text_async("%s/chunked");
                    stdio.println("unexpected-chunked");
                  } catch (failure) { stdio.println("chunked-rejected"); }
                  return;
                }
                """.formatted(url, url), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getAddress().getPort()),
                    new ByteArrayOutputStream());
            assertEquals("large-rejected\nchunked-rejected\n", output.replace("\r", ""));
        } finally { server.stop(0); }
    }

    @Test void oversizedSocketLineRejectsAndClosesConnection() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peers = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peers.submit(() -> {
                try (var peer = server.accept()) {
                    byte[] line = new byte[NativeIo.MAX_SOCKET_LINE_BYTES + 2];
                    java.util.Arrays.fill(line, (byte) 'a');
                    line[line.length - 1] = (byte) '\n';
                    try { peer.getOutputStream().write(line); }
                    catch (SocketException closedEarly) { /* Oversized line closes socket. */ }
                }
                return null;
            });
            String output = run("""
                pub async routine main(): void {
                  val socket = await network.connect_async("127.0.0.1", %d);
                  try {
                    await socket.read_line_async();
                    stdio.println("unexpected");
                  } catch (failure) { stdio.println("line-too-long"); }
                  await socket.close_async();
                  return;
                }
                """.formatted(server.getLocalPort()), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getLocalPort()),
                    new ByteArrayOutputStream());
            exchange.get(5, TimeUnit.SECONDS);
            assertEquals("line-too-long\n", output.replace("\r", ""));
        }
    }

    @Test void boundedNetworkWriteRejectsAsciiAndMultiByteOverflow() {
        assertEquals(3, NativeIo.checkedNetworkText("abc", NativeIo.MAX_SOCKET_WRITE_BYTES,
                "network.socket.write_text").length);
        assertThrows(IllegalArgumentException.class, () ->
                NativeIo.checkedNetworkText("x".repeat(NativeIo.MAX_SOCKET_WRITE_BYTES + 1),
                        NativeIo.MAX_SOCKET_WRITE_BYTES, "network.socket.write_text"));
        assertThrows(IllegalArgumentException.class, () ->
                NativeIo.checkedNetworkText("€".repeat(NativeIo.MAX_SOCKET_WRITE_BYTES / 2),
                        NativeIo.MAX_SOCKET_WRITE_BYTES, "network.socket.write_text"));
    }

    @Test void boundedLineReaderPreservesCrLfAndFinalEofLine() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peers = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peers.submit(() -> {
                try (var peer = server.accept()) {
                    peer.getOutputStream().write("one\r\ntwo\nthree\rfour".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    peer.shutdownOutput();
                }
                return null;
            });
            String output = run("""
                pub async routine main(): void {
                  val socket = await network.connect_async("127.0.0.1", %d);
                  stdio.println(await socket.read_line_async());
                  stdio.println(await socket.read_line_async());
                  stdio.println(await socket.read_line_async());
                  stdio.println(await socket.read_line_async());
                  stdio.println(await socket.read_line_async());
                  await socket.close_async();
                  return;
                }
                """.formatted(server.getLocalPort()), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getLocalPort()),
                    new ByteArrayOutputStream());
            exchange.get(5, TimeUnit.SECONDS);
            assertEquals("Some(one)\nSome(two)\nSome(three)\nSome(four)\nNone\n",
                    output.replace("\r", ""));
        }
    }

    @Test void multibyteUtf8LineCannotBypassWireByteLimit() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peers = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peers.submit(() -> {
                try (var peer = server.accept()) {
                    // 22,000 UTF-16 characters, but 66,000 UTF-8 bytes.
                    byte[] line = ("€".repeat(22_000) + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try { peer.getOutputStream().write(line); }
                    catch (SocketException closedEarly) { /* Expected on overflow. */ }
                }
                return null;
            });
            String output = run("""
                pub async routine main(): void {
                  val socket = await network.connect_async("127.0.0.1", %d);
                  try {
                    await socket.read_line_async();
                    stdio.println("unexpected");
                  } catch (failure) { stdio.println("utf8-overflow-rejected"); }
                  await socket.close_async();
                  return;
                }
                """.formatted(server.getLocalPort()), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getLocalPort()),
                    new ByteArrayOutputStream());
            exchange.get(5, TimeUnit.SECONDS);
            assertEquals("utf8-overflow-rejected\n", output.replace("\r", ""));
        }
    }

    @Test void exactBoundedAsciiLineIsAcceptedWithoutTruncation() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peers = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peers.submit(() -> {
                try (var peer = server.accept()) {
                    byte[] line = new byte[NativeIo.MAX_SOCKET_LINE_BYTES + 1];
                    java.util.Arrays.fill(line, (byte) 'a');
                    line[line.length - 1] = (byte) '\n';
                    peer.getOutputStream().write(line);
                }
                return null;
            });
            String output = run("""
                pub async routine main(): void {
                  val socket = await network.connect_async("127.0.0.1", %d);
                  try {
                    await socket.read_line_async();
                    stdio.println("accepted-exact-boundary");
                  } catch (failure) { stdio.println("incorrect-rejection"); }
                  await socket.close_async();
                  return;
                }
                """.formatted(server.getLocalPort()), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getLocalPort()),
                    new ByteArrayOutputStream());
            exchange.get(5, TimeUnit.SECONDS);
            assertEquals("accepted-exact-boundary\n", output.replace("\r", ""));
        }
    }

    @Test void socketReadAndWriteProgressInParallelWithoutLockInversion() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peers = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peers.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    String ping = new BufferedReader(new InputStreamReader(peer.getInputStream()))
                            .readLine();
                    assertEquals("ping", ping);
                    peer.getOutputStream().write("pong\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            });
            String output = run("""
                pub async routine main(): void {
                  val socket = await network.connect_async("127.0.0.1", %d);
                  val incoming = socket.read_line_async();
                  await socket.write_text_async("ping\\n");
                  stdio.println(await incoming);
                  await socket.close_async();
                  return;
                }
                """.formatted(server.getLocalPort()), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getLocalPort()),
                    new ByteArrayOutputStream());
            exchange.get(5, TimeUnit.SECONDS);
            assertEquals("Some(pong)\n", output.replace("\r", ""));
        }
    }

    @Test void concurrentReadsOnSameSocketDoNotConsumeMultipleBlockedWorkers() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peers = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peers.submit(() -> {
                try (var peer = server.accept()) {
                    // Give both independently submitted native I/O jobs an
                    // opportunity to start before completing the blocked read.
                    Thread.sleep(400);
                    peer.getOutputStream().write("one\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    peer.shutdownOutput();
                }
                return null;
            });
            String output = run("""
                pub async routine main(): void {
                  val socket = await network.connect_async("127.0.0.1", %d);
                  val first = socket.read_line_async();
                  val second = socket.read_line_async();
                  try { stdio.println(await first); }
                  catch (failure) { stdio.println("overlap-rejected"); }
                  try { stdio.println(await second); }
                  catch (failure) { stdio.println("overlap-rejected"); }
                  await socket.close_async();
                  return;
                }
                """.formatted(server.getLocalPort()), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getLocalPort()),
                    new ByteArrayOutputStream());
            exchange.get(5, TimeUnit.SECONDS);
            assertTrue(output.contains("Some(one)"), output);
            assertEquals(1, output.split("overlap-rejected", -1).length - 1, output);
        }
    }
}
