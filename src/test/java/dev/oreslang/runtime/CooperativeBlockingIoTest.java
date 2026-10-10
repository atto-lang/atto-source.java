package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class CooperativeBlockingIoTest {
    @TempDir Path root;

    private String run(String source, RuntimePermissions permissions, ByteArrayOutputStream output)
            throws Exception {
        Path program = root.resolve("program.ores");
        Files.writeString(program, source);
        LinkedProgramRunner.run(program, IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK), permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                output, new ByteArrayOutputStream());
        return output.toString();
    }

    @Test void blockingIoReturnsValuesInOrdinarySynchronousRoutine() throws Exception {
        Path dir = Files.createDirectories(root.resolve("data"));
        Path file = dir.resolve("hello.txt");
        String path = file.toString().replace("\\", "\\\\");
        String folder = dir.toString().replace("\\", "\\\\");
        String program = """
            pub routine main(): void {
              fs.write_text_atomic_blocking("%s", "hello");
              val content = fs.read_text_blocking("%s");
              val names = File.list_dir_blocking("%s");
              stdio.println(content);
              stdio.println(names.get(0));
              stdio.println(fs.exists_blocking("%s"));
              return;
            }
            """.formatted(path, path, folder, path);
        OresCompiler.parseAndTypeCheck(program);
        String output = run(program, RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString()),
                new ByteArrayOutputStream());
        assertTrue(output.contains("hello"), output);
        assertTrue(output.contains("hello.txt"), output);
        assertTrue(output.contains("true"), output);
        assertEquals("hello", Files.readString(file));
    }

    @Test void nestedTailReturnAfterImplicitIoSuspendPreservesControlFlow() throws Exception {
        Path file = root.resolve("branch.txt");
        Files.writeString(file, "match");
        String fileName = file.toString().replace("\\", "\\\\");
        String source = """
            fnc greeting(): String {
              return "hello";
            }
            fnc choose(String path): String {
              val String contents = fs.read_text_blocking(path);
              if contents == "match"; then
                return greeting();
              fi
              return "not found";
            }
            pub routine main(): void {
              stdio.println_blocking(choose("%s"));
              return;
            }
            """.formatted(fileName);
        String actual = run(source, RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString()),
                new ByteArrayOutputStream());
        assertTrue(actual.contains("hello"), actual);
    }

    @Test void firstClassBlockingNativeIoAliasFailsClosed() {
        String source = """
                pub routine main(): void {
                  val readFile = fs.read_text_blocking;
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("direct-call-only"),
                failure.getMessage());
    }

    @Test void strictNativeIoRejectsBareCallsAndAdmitsCooperativeSuffixes() {
        String previous = System.getProperty("ores.compiler.strictNativeIo");
        System.setProperty("ores.compiler.strictNativeIo", "true");
        try {
            for (String bare : new String[] {
                    "fs.read_text(\"foo\")",
                    "File.is_dir(\"foo\")",
                    "http.get_text(\"http://localhost/\")",
                    "stdio.println(\"hello\")",
                    "stdio.stdout.log(\"hello\")",
                    "stdio.stdout.logList([\"hello\"])",
                    "network.connect(\"localhost\", 80)"
            }) {
                String program = "pub routine main(): void { " + bare + "; return; }";
                IllegalArgumentException rejected = assertThrows(
                        IllegalArgumentException.class,
                        () -> OresCompiler.parseAndTypeCheck(program),
                        "strict native I/O name must reject " + bare);
                assertTrue(rejected.getMessage().contains("_blocking"),
                        rejected.getMessage());
            }
            OresCompiler.parseAndTypeCheck("""
                    pub routine main(): void {
                      fs.write_text_blocking("target", "hello");
                      val String text = fs.read_text_blocking("target");
                      stdio.println_blocking(text);
                      return;
                    }
                    """);
        } finally {
            if (previous == null) System.clearProperty("ores.compiler.strictNativeIo");
            else System.setProperty("ores.compiler.strictNativeIo", previous);
        }
    }

    @Test void explicitDenyAllSurvivesEmbeddingArgumentRoundTrip() {
        IsolatePolicy capabilityRich = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK);
        RuntimePermissions explicit = RuntimePermissions.denyAll();
        RuntimePermissions reconstructed = RuntimePermissions.fromApplicationArguments(
                explicit.applicationArguments(), capabilityRich);
        assertFalse(reconstructed.allows(RuntimePermissions.Permission.READ, root.toString()));
        assertFalse(reconstructed.allows(RuntimePermissions.Permission.WRITE, root.toString()));
        assertFalse(reconstructed.allows(RuntimePermissions.Permission.NET, "127.0.0.1:1"));
        // The original legacy no-argument embedding behavior still follows
        // declared capabilities, unlike an explicit empty permission policy.
        RuntimePermissions legacy = RuntimePermissions.fromApplicationArguments(
                new String[0], capabilityRich);
        assertTrue(legacy.allows(RuntimePermissions.Permission.READ, root.toString()));
    }

    @Test void blockingIOMaintainsDeniedCapabilities() throws Exception {
        String file = root.resolve("denied.txt").toString().replace("\\", "\\\\");
        String source = """
            pub routine main(): void {
              fs.write_text_blocking("%s", "forbidden");
              return;
            }
            """.formatted(file);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> run(source, RuntimePermissions.denyAll(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(root.resolve("denied.txt")));
        assertNotNull(failure);
    }

    @Test void slowBlockingHttpRequestReleasesSingleActorCarrier() throws Exception {
        String previous = System.getProperty("ores.runtime.actor.shared.parallelism");
        System.setProperty("ores.runtime.actor.shared.parallelism", "1");
        CountDownLatch probeRan = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                if (toString().contains("probe")) probeRan.countDown();
            }
        };
        var server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                if (!probeRan.await(5, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(503, -1);
                } else {
                    byte[] response = "reply".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String source = """
                define actor Probe as
                  receive(ActorMail<String> mail): void {
                    stdio.println("probe");
                    self.end(); return;
                  }
                end
                define actor Reader as
                  receive(ActorMail<String> mail): void {
                    self.send("reading");
                    val body = http.get_text_blocking("http://127.0.0.1:%d/");
                    stdio.println(body);
                    self.end(); return;
                  }
                end
                pub async routine main(): void {
                  val reader = spawn Reader(); await reader.ready;
                  val probe = spawn Probe(); await probe.ready;
                  reader.send("go");
                  await reader.outputs.next();
                  probe.send("go");
                  await reader.done; await probe.done;
                  return;
                }
                """.formatted(server.getAddress().getPort());
            String out = run(source, RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + server.getAddress().getPort()), output);
            assertTrue(probeRan.getCount() == 0, "peer was released by second actor");
            assertTrue(out.contains("reply"), out);
        } finally {
            server.stop(0);
            if (previous == null) System.clearProperty("ores.runtime.actor.shared.parallelism");
            else System.setProperty("ores.runtime.actor.shared.parallelism", previous);
        }
    }

    @Test void asyncAwaitAndImplicitBlockingHttpUseSameTransportResults() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                String answer = exchange.getRequestMethod().equals("POST")
                        ? "post:" + body : "get:ok";
                byte[] payload = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String source = """
                pub async routine main(): void {
                  val a = await http.get_text_async("http://127.0.0.1:%d/");
                  val b = http.get_text_blocking("http://127.0.0.1:%d/");
                  val c = await http.post_text_async("http://127.0.0.1:%d/", "A");
                  val d = http.post_text_blocking("http://127.0.0.1:%d/", "B");
                  stdio.println(a);
                  stdio.println(b);
                  stdio.println(c);
                  stdio.println(d);
                  return;
                }
                """.formatted(server.getAddress().getPort(), server.getAddress().getPort(),
                    server.getAddress().getPort(), server.getAddress().getPort());
            OresCompiler.parseAndTypeCheck(source);
            String output = run(source, RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + server.getAddress().getPort()),
                    new ByteArrayOutputStream());
            assertEquals(2, output.split("get:ok", -1).length - 1, output);
            assertTrue(output.contains("post:A"), output);
            assertTrue(output.contains("post:B"), output);
        } finally {
            server.stop(0);
        }
    }

    @Test void asyncHttpFailsBeforeSendWhenCapabilityIsDenied() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String source = """
                pub async routine main(): void {
                  await http.get_text_async("http://127.0.0.1:%d/");
                  return;
                }
                """.formatted(server.getAddress().getPort());
            assertThrows(RuntimeException.class,
                    () -> run(source, RuntimePermissions.denyAll(),
                            new ByteArrayOutputStream()));
            assertEquals(0, requests.get(), "denied request was sent");
        } finally {
            server.stop(0);
        }
    }
}
