package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
              val worker = spawn Worker(); await worker.get_ready();
              worker.send("%s");
              for await const output of worker.outputs { stdio.println(output.value); }
              await worker.get_done(); return;
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

    @Test void coreNumericLoggingDoesNotChangeIoFailuresOrLeakGuestExceptionText() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        CoreLog.configure(CoreLog.Level.WARN);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var io = new AsyncNativeIo(1)) {
            var blocked = io.submit("slow", () -> {
                started.countDown();
                release.await();
                return 19;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var rejected = io.submit("private-operation-name", () -> fail("must not run"));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class,
                            () -> rejected.get(2, TimeUnit.SECONDS)).getCause());
            release.countDown();
            assertEquals(19, blocked.get(2, TimeUnit.SECONDS));

            var failed = io.submit("another-secret-operation", () -> {
                throw new IllegalStateException("PRIVATE-GUEST-EXCEPTION-TEXT");
            });
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> failed.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(7, io.submit("normal", () -> 7).get(2, TimeUnit.SECONDS),
                    "diagnostic publication must not change later admissions");

            var output = new ByteArrayOutputStream();
            CoreLog.dumpRetained(new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
            String json = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("\"event\":\"async.io.rejected\""));
            assertTrue(json.contains("\"event\":\"async.io.failed\""));
            assertFalse(json.contains("PRIVATE-GUEST-EXCEPTION-TEXT"));
            assertFalse(json.contains("private-operation-name"));
            assertFalse(json.contains("another-secret-operation"));
            assertFalse(json.contains("\"value\":0"));
        } finally {
            release.countDown();
            CoreLog.configure(CoreLog.Level.OFF);
        }
        var afterDisable = new ByteArrayOutputStream();
        CoreLog.dumpRetained(new PrintStream(afterDisable));
        assertEquals("", afterDisable.toString(),
                "explicitly disabled diagnostics must not export prior I/O events");
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
                  val reader = spawn Reader(); await reader.get_ready();
                  val probe = spawn Probe(); await probe.get_ready();
                  reader.send("go");
                  await reader.outputs.next();
                  probe.send("go");
                  await reader.get_done(); await probe.get_done(); return;
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
    @Test
    void resourceReleaseFailureIsRetainedAndRetriedAfterWorkerExit() throws Exception {
        AsyncNativeIo io = new AsyncNativeIo(1);
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable flakyResource = () -> {
            if (closes.incrementAndGet() == 1) {
                throw new IOException("synthetic first close failure");
            }
        };
        io.register(flakyResource);

        IllegalStateException first =
                assertThrows(IllegalStateException.class, io::close);
        assertTrue(first.getMessage().contains(
                "native I/O resources did not release after worker exit"));
        assertEquals(1, closes.get(),
                "first failure must not silently retire the resource");

        assertDoesNotThrow(io::close,
                "second close must retry the retained resource");
        assertEquals(2, closes.get());
    }

    @Test
    void uncheckedHostCleanupFailureCannotStrandOtherResourcesOrDisableRetry() throws Exception {
        AsyncNativeIo io = new AsyncNativeIo(1);
        AtomicInteger flakyAttempts = new AtomicInteger();
        AtomicInteger healthyAttempts = new AtomicInteger();
        AutoCloseable flaky = () -> {
            if (flakyAttempts.incrementAndGet() == 1) {
                throw new AssertionError("injected host callback failure");
            }
        };
        AutoCloseable healthy = healthyAttempts::incrementAndGet;
        io.register(flaky);
        io.register(healthy);

        IllegalStateException first = assertThrows(IllegalStateException.class, io::close);
        assertTrue(first.getMessage().contains("native I/O resources did not release"));
        assertEquals(1, healthyAttempts.get(),
                "unchecked Error must not skip independent accepted resource cleanup");
        assertEquals(1, flakyAttempts.get());
        assertTrue(java.util.Arrays.stream(first.getSuppressed())
                        .anyMatch(ex -> ex instanceof AssertionError),
                "host Error must remain visible in physical-shutdown failure receipt");
        assertDoesNotThrow(io::close, "failed resource must remain retryable");
        assertEquals(2, flakyAttempts.get());
        assertEquals(1, healthyAttempts.get(),
                "successfully closed resource must not close a second time");
    }

    @Test
    void fatalHostCleanupStillAttemptsIndependentResourcesAndRemainsRetryable() throws Exception {
        AsyncNativeIo io = new AsyncNativeIo(1);
        AtomicInteger fatalAttempts = new AtomicInteger();
        AtomicInteger healthyAttempts = new AtomicInteger();
        OutOfMemoryError injected = new OutOfMemoryError("synthetic fatal cleanup");
        io.register(() -> {
            if (fatalAttempts.incrementAndGet() == 1) throw injected;
        });
        io.register(healthyAttempts::incrementAndGet);

        assertSame(injected, assertThrows(OutOfMemoryError.class, io::close),
                "VM-fatal cleanup errors must retain their identity");
        assertEquals(1, healthyAttempts.get(),
                "independent resources must still be attempted");
        assertEquals(1, fatalAttempts.get());
        assertDoesNotThrow(io::close,
                "failed fatal resource must remain registered for retry");
        assertEquals(2, fatalAttempts.get());
        assertEquals(1, healthyAttempts.get());
    }


    @Test
    void physicalClosePublishesTerminalFutureBeforeDroppingTracking() throws Exception {
        AsyncNativeIo io = new AsyncNativeIo(1);
        var pendingField = AsyncNativeIo.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<OresFuture<?>> tracked =
                (java.util.Set<OresFuture<?>>) pendingField.get(io);
        OresFuture<Integer> future = new OresFuture<>();
        synchronized (io) { tracked.add(future); }

        io.close();
        assertTrue(future.isDone(), "shutdown must settle the accepted future");
        assertTrue(tracked.isEmpty(), "only terminal futures may leave tracking");
        assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> future.get(1, TimeUnit.SECONDS)).getCause());
    }


}
