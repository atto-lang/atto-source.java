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
}
