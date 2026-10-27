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
    void bareSynchronousFilesystemNameIsCompilerRejected() {
        assertCompilerRejected("""
                pub routine main(): void {
                  fs.read_text("definitely-not-read");
                  return;
                }
                """);
    }

    @Test
    void blockingSuffixIsNotAdmittedUntilContinuationLoweringExists() {
        assertCompilerRejected("""
                pub routine main(): void {
                  fs.read_text_blocking("definitely-not-read");
                  return;
                }
                """);
    }

    @Test
    void asynchronousFilesystemCannotFallBackToIoWorker() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await fs.read_text_async("definitely-not-read");
                  return;
                }
                """);
    }

    @Test
    void hostnameConnectFailsClosedBeforeBlockingDns() throws Exception {
        String text = failureText(runFailure("""
                pub async routine main(): void {
                  await network.connect_async("localhost", 9);
                  return;
                }
                """));
        assertTrue(
                text.contains("numeric IPv4 literal")
                        || text.contains("async DNS is installed"),
                text);
    }

    @Test
    void httpClientCannotReachSynchronousHttpClient() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await http.get_text_async("http://127.0.0.1:9/");
                  return;
                }
                """);
    }

    @Test
    void httpListenerCannotReachLegacyJdkHttpServer() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await http.listen_async("127.0.0.1", 0, 1, 1024, 1000);
                  return;
                }
                """);
    }

    @Test
    void bareStdoutAndStdinNamesAreCompilerRejected() {
        assertCompilerRejected("""
                pub routine main(): void {
                  stdio.stdout.write("must-not-be-written");
                  return;
                }
                """);
        assertCompilerRejected("""
                pub routine main(): void {
                  stdio.stdin.read_line();
                  return;
                }
                """);
    }

    @Test
    void asynchronousStdoutCannotFallBackToPrintWriter() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await stdio.stdout.write_async("must-not-be-written");
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
    void realSelectorTcpSurfaceRemainsCompilerAdmitted() {
        assertCompilerAccepts("""
                pub fnc verify_selector_surface(
                  borrow NioTcpListener listener,
                  borrow NioTcpConnection connection
                ): void {
                  val int port = listener.port;
                  val Future<NioTcpConnection> accepted = listener.accept_async();
                  val Future<NioTcpConnection> outbound =
                      network.connect_async("127.0.0.1", 9);
                  val Future<Array<int>> read = connection.read_bytes_async(64);
                  val Future<void> close_connection = connection.close_async();
                  val Future<void> close_listener = listener.close_async();
                  return;
                }
                """);
        assertCompilerAccepts("""
                pub fnc open_selector_listener(): NioTcpListener {
                  return network.listen_nonblocking("127.0.0.1", 0, 8, 2, 4);
                }
                """);
    }

    @Test
    void asyncSurfaceTypechecksWithFutureResults() {
        assertCompilerAccepts("""
                pub async routine main(): void {
                  val Future<String> read = fs.read_text_async("x");
                  val Future<bool> exists = fs.exists_async("x");
                  val Future<NioTcpConnection> connected = network.connect_async("127.0.0.1", 9);
                  val Future<String> get = http.get_text_async("http://127.0.0.1:9/");
                  val Future<void> out = stdio.stdout.write_async("x");
                  val Future<Option<String>> input = stdio.stdin.read_line_async();
                  return;
                }
                """);
    }
}
