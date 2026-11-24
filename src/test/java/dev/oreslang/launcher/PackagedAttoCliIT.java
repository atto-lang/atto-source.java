package dev.oreslang.launcher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises packaged resources, linked checking and execution in a fresh CLI process. */
@Timeout(30)
final class PackagedAttoCliIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void executableServesConcurrentBinaryClients(boolean sharedSelector) throws Exception {
        Files.writeString(directory.resolve("session.atto"), """
                import module num from "atto.core/num";
                define module session as
                  pub susp fnc echo(NioConnection socket): void {
                    loop {
                      val bytes = await socket.read_bytes_async(num.min(32768, 512));
                      if bytes.size == 0 then
                        await socket.close_async();
                        return;
                      fi
                      await socket.write_bytes_async(bytes);
                    }
                  }
                end
                """);
        Path source = Files.writeString(directory.resolve("server.atto"), """
                import module session from "./session";
                import module path from "atto.std/path";

                async fnc serve_session(NioConnection socket): void {
                  session.echo(socket);
                  return;
                }

                pub suspends fnc main(): void {
                  val listener = await network.listen_async("127.0.0.1", 0);
                  stdio.println(await listener.local_port_async());
                  task scope {
                    let int accepted = 0;
                    loop {
                      if accepted == 8 then break; fi
                      val socket = await listener.accept_async();
                      let Future<void> serving = task {
                        await serve_session(socket);
                        return;
                      };
                      accepted = accepted + 1;
                    }
                  }
                  await listener.close_async();
                  stdio.println(path.join("reactor", "done"));
                  return;
                }
                """);
        String permission = "--allow-net=127.0.0.1:0";
        assertEquals(0, run("--check", permission, source.toString()));

        Process server = launch(sharedSelector, permission, "--control-workers=2", source.toString());
        List<Socket> clients = new ArrayList<>();
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
            String portLine = output.readLine();
            assertNotNull(portLine, this::diagnostic);
            int port = Integer.parseInt(portLine);
            byte[][] data = new byte[8][];
            for (int i = 0; i < data.length; i++) {
                Socket client = new Socket();
                clients.add(client);
                client.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                client.setSoTimeout(5000);
                data[i] = new byte[16_384 + i];
                new Random(i).nextBytes(data[i]);
            }
            // Leave the first connection idle until all the other continuations
            // make progress. A pinned accept or read cannot serialize the guest.
            for (int i = 7; i >= 0; i--) {
                Socket client = clients.get(i);
                client.getOutputStream().write(data[i]);
                client.shutdownOutput();
                assertArrayEquals(data[i], client.getInputStream().readAllBytes());
            }
            assertTrue(server.waitFor(5, TimeUnit.SECONDS), this::diagnostic);
            assertEquals(0, server.exitValue(), this::diagnostic);
            assertEquals(Path.of("reactor", "done").toString(), output.readLine());
        } finally {
            server.destroyForcibly();
            for (Socket client : clients) client.close();
        }
    }

    @Test
    void checkAndExecutionBothRejectBorrowAcrossSuspension() throws Exception {
        Path source = Files.writeString(directory.resolve("borrow.atto"), """
                define class Box as
                  pub let int value = 42;
                end
                pub susp fnc main(): void {
                  let Box box = new Box();
                  val borrow Box view = rt borrow box;
                  await process.sleep_async(1);
                  stdio.println(view.value);
                  return;
                }
                """);
        assertEquals(1, run("--check", source.toString()));
        assertTrue(diagnostic().contains("ordinary borrow is live"), this::diagnostic);
        assertEquals(1, run(source.toString()));
        assertTrue(diagnostic().contains("ordinary borrow is live"), this::diagnostic);
    }

    @Test
    void checkedCallerRejectsAnUncheckedExtensionlessProvider() throws Exception {
        Files.writeString(directory.resolve("provider.ores"),
                "define module values as pub fnc value(): int { return 7; } end");
        Path source = Files.writeString(directory.resolve("consumer.atto"), """
                import module values from "./provider";
                pub fnc main(): int { return values.value(); }
                """);
        assertEquals(1, run("--check", source.toString()));
        assertTrue(diagnostic().contains("ATTO_CHECKED_I64_IMPORT_GRAPH_UNSUPPORTED"), this::diagnostic);
        assertEquals(1, run(source.toString()));
        assertTrue(diagnostic().contains("ATTO_CHECKED_I64_IMPORT_GRAPH_UNSUPPORTED"), this::diagnostic);
    }

    private int run(String... arguments) throws Exception {
        Process process = launch(false, arguments);
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), this::diagnostic);
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    private Process launch(boolean sharedSelector, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        String nativeExecutable = System.getProperty("atto.cli.executable");
        if (nativeExecutable == null) {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("--enable-native-access=ALL-UNNAMED");
        } else {
            command.add(Path.of(nativeExecutable).toAbsolutePath().toString());
        }
        command.add("-Dores.runtime.nio.shared-selector=" + sharedSelector);
        if (nativeExecutable == null) {
            command.add("-jar");
            command.add(System.getProperty("atto.cli.jar"));
        }
        command.addAll(Arrays.asList(arguments));
        return new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(directory.resolve("stderr").toFile())
                .start();
    }

    private String diagnostic() {
        try {
            return Files.readString(directory.resolve("stderr"));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
