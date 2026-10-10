package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs guest Oreslang without Java interop. Java here is only the test driver,
 * since the reference interpreter is hosted by Graal/Truffle.
 */
@Timeout(30)
final class NativeStdioChannelsTest {
    private record Output(String stdout, String stderr) { }

    private static Output run(String source, String stdin) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Source code = Source.newBuilder(OresLanguage.ID, source, "stdio-channels.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)))
                .out(stdout).err(stderr).build()) {
            context.eval(code);
        }
        return new Output(
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nativePipelineFinishesOnEofAndWritesDistinctDescriptors() throws Exception {
        String code = Files.readString(Path.of("examples/native-stdio-channels.ores"));
        Output result = run(code, "alpha\nbeta\n");
        assertEquals("alpha\nbeta\n", result.stdout());
        assertEquals("stdin closed\n", result.stderr());
    }

    @Test
    void nativeObservableConsumesClosedBufferedChannelExactlyOnce() throws Exception {
        Output result = run("""
                import module Observable from "std/observable";
                pub routine main(): void {
                  val Channel<int> numbers = Channel.new<int>(2);
                  writech numbers, 4;
                  writech numbers, 5;
                  numbers.close();
                  for const item of Observable.from_ch(numbers) {
                    stdio.stdout.write(item);
                  }
                  return;
                }
                """, "");
        assertEquals("45", result.stdout());
    }

    @Test
    void directChannelIterationUsesSuspendableSourceTurns() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  for const line of stdio.stdin {
                    stdio.println(line);
                  }
                  return;
                }
                """, "one\ntwo\n");
        assertEquals("one\ntwo\n", result.stdout());
    }

    @Test
    void nativeNonBlockingOutputReturnsAnAwaitableAdmissionFuture() throws Exception {
        Output result = run("""
                import module Stdio from "std/stdio";
                pub async routine main(): void {
                  await Stdio.println("first");
                  await Stdio.eprintln("error");
                  return;
                }
                """, "");
        assertEquals("first\n", result.stdout());
        assertEquals("error\n", result.stderr());
    }

    @Test
    void nativeFormattedLinesAndFlushBarriersPreserveOrdering() throws Exception {
        Output result = run("""
                import module Stdio from "std/stdio";
                pub routine main(): void {
                  Stdio.println_parts_blocking(["hello", "-", "world"]);
                  Stdio.println_blocking("next");
                  Stdio.flush_blocking();
                  Stdio.eprintln_blocking("warning");
                  Stdio.err_flush_blocking();
                  return;
                }
                """, "");
        assertEquals("hello-world\\nnext\\n", result.stdout());
        assertEquals("warning\\n", result.stderr());
    }

    @Test
    void observableDoesNotConsumeBeforeFirstPull() throws Exception {
        Output result = run("""
                import module Observable from "std/observable";
                pub routine main(): void {
                  val Channel<int> inbox = Channel.new<int>(0);
                  val Future<void> pending = nb writech inbox, 9;
                  val Iterator<int> stream = Observable.from_ch(inbox);
                  for const value of stream {
                    stdio.stdout.write(value);
                    break;
                  }
                  inbox.close();
                  return;
                }
                """, "");
        assertEquals("9", result.stdout());
    }

    @Test
    void outputChannelRejectsReadDirection() {
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  val msg = readch stdio.out;
                  return;
                }
                """, ""));
    }

    @Test
    void inputChannelRejectsWriteDirection() {
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  writech stdio.in, "illegal";
                  return;
                }
                """, ""));
    }

    @Test
    void stdinOversizedLineIsAnErrorNotEof() {
        String huge = "x".repeat(65_538) + "\n";
        Exception problem = assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  for const line of stdio.in {
                    stdio.println(line);
                  }
                  return;
                }
                """, huge));
        assertTrue(problem.toString().contains("stdio")
                || problem.toString().contains("stdin")
                || problem.toString().contains("line")
                || problem.getCause() != null);
    }

    @Test
    void sourceAllowsSynchronousGeneratorWithoutAsync() throws Exception {
        OresCompiler.parseAndTypeCheck("""
                pub gen* stdin_lines(): Iterator<String> {
                  for const line of stdio.in {
                    yield line;
                  }
                  return;
                }
                pub routine main(): void { return; }
                """);
    }
}
