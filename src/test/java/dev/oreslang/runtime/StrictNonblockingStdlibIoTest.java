package dev.oreslang.runtime;

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

    @Test
    void synchronousFilesystemCannotReachLegacyHostIo() throws Exception {
        assertStrictlyUnsupported("""
                pub routine main(): void {
                  fs.read_text("definitely-not-read");
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
    void tcpConnectCannotReachBlockingSocketOrWorkerFallback() throws Exception {
        assertStrictlyUnsupported("""
                pub async routine main(): void {
                  await network.connect_async("127.0.0.1", 9);
                  return;
                }
                """);
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
    void stdoutCannotReachPrintWriter() throws Exception {
        assertStrictlyUnsupported("""
                pub routine main(): void {
                  stdio.stdout.write("must-not-be-written");
                  return;
                }
                """);
    }

    @Test
    void stdinCannotReachBufferedReader() throws Exception {
        assertStrictlyUnsupported("""
                pub routine main(): void {
                  stdio.stdin.read_line();
                  return;
                }
                """);
    }

    @Test
    void globalPrintCannotBypassStrictStdlibBoundary() throws Exception {
        assertStrictlyUnsupported("""
                pub routine main(): void {
                  print("must-not-be-written");
                  return;
                }
                """);
    }
}
