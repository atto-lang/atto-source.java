package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class StrictAsyncStdlibIoCurrentMainTest {
    @TempDir Path root;

    private Throwable runFailure(String program) throws Exception {
        Path source = root.resolve("strict-async-io.ores");
        Files.writeString(source, program);
        return assertThrows(Throwable.class, () ->
                LinkedProgramRunner.run(
                        source,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        System.getenv(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
    }

    private static String failureText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null) text.append(cursor.getMessage()).append('\n');
        }
        return text.toString();
    }

    private void assertFailsBeforeHostIo(String program) throws Exception {
        String text = failureText(runFailure(program));
        assertTrue(text.contains("strict readiness/completion I/O provider")
                || text.contains("strict nonblocking I/O provider"), text);
    }

    private static void assertCompilerRejected(String program) {
        Exception failure = assertThrows(Exception.class, () -> OresCompiler.parseAndTypeCheck(program));
        assertFalse(failureText(failure).isBlank());
    }

    private static void assertCompilerAccepts(String program) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void canonicalSurfaceIsFutureTyped() {
        assertCompilerAccepts("""
                pub async routine main(): void {
                  val Future<String> read = fs.read_text("x");
                  val Future<List<String>> list = fs.list_dir("x");
                  val Future<bool> dir = fs.is_dir("x");
                  val Future<bool> link = fs.is_symlink("x");
                  val Future<bool> exists = fs.exists("x");
                  val Future<void> write = fs.write_text("x", "y");
                  val Future<void> atomic = fs.write_text_atomic("x", "y");
                  val Future<void> append = fs.append_text("x", "y");
                  val Future<void> remove = fs.remove("x");
                  val Future<void> mkdir = fs.mkdir_all("x");
                  val Future<TcpConnection> socket = network.connect("127.0.0.1", 9);
                  val Future<String> get = http.get_text("http://127.0.0.1:9/");
                  val Future<String> post = http.post_text("http://127.0.0.1:9/", "x");
                  val Future<HttpServer> listener = http.listen("127.0.0.1", 0, 1, 1024, 1000);
                  val Future<void> out = stdio.stdout.write("x");
                  val Future<Option<String>> input = stdio.stdin.read_line();
                  return;
                }
                """);
    }

    @Test
    void legacyAndExplicitBlockingSpellingsAreRejected() {
        assertCompilerRejected("""
                pub async routine main(): void {
                  await fs.read_text_async("x");
                  return;
                }
                """);
        assertCompilerRejected("""
                pub async routine main(): void {
                  await network.connect_async("127.0.0.1", 9);
                  return;
                }
                """);
        assertCompilerRejected("""
                pub async routine main(): void {
                  await http.get_text_async("http://127.0.0.1:9/");
                  return;
                }
                """);
        assertCompilerRejected("""
                pub async routine main(): void {
                  await fs.read_text_sync("x");
                  return;
                }
                """);
        assertCompilerRejected("""
                pub async routine main(): void {
                  await fs.read_text_blocking("x");
                  return;
                }
                """);
    }

    @Test
    void ambientGlobalPrintIsRejected() {
        assertCompilerRejected("""
                pub routine main(): void {
                  print("no ambient stdout");
                  return;
                }
                """);
    }

    @Test
    void canonicalCallsFailClosedBeforeLegacyHostIo() throws Exception {
        assertFailsBeforeHostIo("""
                pub async routine main(): void {
                  await fs.read_text("definitely-not-read");
                  return;
                }
                """);
        assertFailsBeforeHostIo("""
                pub async routine main(): void {
                  await network.connect("127.0.0.1", 9);
                  return;
                }
                """);
        assertFailsBeforeHostIo("""
                pub async routine main(): void {
                  await http.get_text("http://127.0.0.1:9/");
                  return;
                }
                """);
        assertFailsBeforeHostIo("""
                pub async routine main(): void {
                  await stdio.stdout.write("must-not-be-written");
                  return;
                }
                """);
        assertFailsBeforeHostIo("""
                pub async routine main(): void {
                  await stdio.stdin.read_line();
                  return;
                }
                """);
    }
}
