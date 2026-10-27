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
final class StrictNonblockingStdlibIoTest {
    @TempDir Path root;

    private Throwable runFailure(String program) throws Exception {
        Path source = root.resolve("strict-io.ores");
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

    private void assertStrictlyUnsupported(String program) throws Exception {
        String text = failureText(runFailure(program));
        assertTrue(
                text.contains("strict readiness/completion I/O provider")
                        || text.contains("strict nonblocking I/O provider"),
                text);
    }

    private static void assertCompilerRejected(String program) {
        Exception failure = assertThrows(Exception.class, () -> OresCompiler.parseAndTypeCheck(program));
        String text = failureText(failure);
        assertTrue(
                text.contains("unknown strict stdlib member")
                        || text.contains("unknown")
                        || text.contains("function"),
                text);
    }

    private static void assertCompilerAccepts(String program) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void explicitBlockingAndLegacyAsyncSuffixesAreNotGuestApi() {
        assertCompilerRejected("""
                pub routine main(): void {
                  fs.read_text_blocking("x");
                  return;
                }
                """);
        assertCompilerRejected("""
                pub routine main(): void {
                  fs.read_text_sync("x");
                  return;
                }
                """);
        assertCompilerRejected("""
                pub async routine main(): void {
                  await fs.read_text_async("x");
                  return;
                }
                """);
    }

    @Test
    void canonicalFilesystemCannotFallBackToIoWorker() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await fs.read_text("definitely-not-read");
                  return;
                }
                """);
    }

    @Test
    void tcpConnectCannotReachBlockingSocketOrWorkerFallback() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await network.connect("127.0.0.1", 9);
                  return;
                }
                """);
    }

    @Test
    void httpClientCannotReachSynchronousHttpClient() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await http.get_text("http://127.0.0.1:9/");
                  return;
                }
                """);
    }

    @Test
    void httpListenerCannotReachLegacyJdkHttpServer() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await http.listen("127.0.0.1", 0, 1, 1024, 1000);
                  return;
                }
                """);
    }

    @Test
    void canonicalStdoutAndStdinCannotReachBlockingStreams() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await stdio.stdout.write("must-not-be-written");
                  return;
                }
                """);
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await stdio.stdin.read_line();
                  return;
                }
                """);
    }

    @Test
    void globalPrintIsNotAnAmbientIoBuiltin() {
        assertCompilerRejected("""
                pub routine main(): void {
                  print("must-not-be-written");
                  return;
                }
                """);
    }

    @Test
    void canonicalSurfaceTypechecksWithFutureResults() {
        assertCompilerAccepts("""
                pub async routine main(): void {
                  val Future<String> read = fs.read_text("x");
                  val Future<List<String>> listing = fs.list_dir("x");
                  val Future<bool> dir = fs.is_dir("x");
                  val Future<bool> link = fs.is_symlink("x");
                  val Future<bool> exists = fs.exists("x");
                  val Future<void> atomic = fs.write_text_atomic("x", "y");
                  val Future<TcpConnection> connected = network.connect("127.0.0.1", 9);
                  val Future<String> get = http.get_text("http://127.0.0.1:9/");
                  val Future<HttpServer> listener = http.listen("127.0.0.1", 0, 1, 1024, 1000);
                  val Future<void> out = stdio.stdout.write("x");
                  val Future<Option<String>> input = stdio.stdin.read_line();
                  return;
                }
                """);
    }
}
